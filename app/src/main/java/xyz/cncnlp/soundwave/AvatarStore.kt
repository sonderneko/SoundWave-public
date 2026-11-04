package xyz.cncnlp.soundwave

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import xyz.cncnlp.soundwave.image.ImageDecodeLimits

/** 自定义头像按用户名保留；用户名身份无法稳定验证时用退休标记隐藏文件但不删除。 */
object AvatarStore {
    private const val FILE = "user_avatar.jpg"
    private const val PREF = "avatar_map"
    private const val KEY_LAST = "last_user"
    private const val KEY_RETIRED_USERS = "retired_users_v1"

    private fun safeUser(user: String): String =
        user.trim().lowercase().replace(Regex("[^a-z0-9_\\-@.]"), "_").ifEmpty { "default" }

    private val versionLock = Any()
    private val pinnedVersions = mutableMapOf<String, Int>()
    private val ownerVersions = java.util.WeakHashMap<Any, String>()
    private const val RETAINED_VERSIONS = 3

    private fun versionDirectory(ctx: Context, user: String): File {
        // Exact, case-sensitive owner key; no lossy username sanitization for new files.
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest(user.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(ctx.applicationContext.filesDir, "avatar_versions/" + key)
    }

    private fun fileFor(ctx: Context, user: String): File {
        if (user.isNotBlank()) {
            val dir = versionDirectory(ctx, user)
            val name = ctx.applicationContext.getSharedPreferences(PREF, 0)
                .getString("version_" + dir.name, "").orEmpty()
            if (Regex("[a-f0-9-]{36}\\.jpg").matches(name)) {
                val current = File(dir, name)
                if (current.isFile && current.length() > 0L) return current
            }
        }
        val name = if (user.isBlank()) FILE else "user_avatar_${safeUser(user)}.jpg"
        return File(ctx.applicationContext.filesDir, name)
    }

    /** Ordinary immutable file paths are consumed unchanged by all image loaders. */
    private fun writeVersion(ctx: Context, user: String, bitmap: Bitmap): String = synchronized(versionLock) {
        val dir = versionDirectory(ctx, user)
        if (!dir.isDirectory && !dir.mkdirs()) return@synchronized ""
        val target = File(dir, java.util.UUID.randomUUID().toString() + ".jpg")
        try {
            val written = FileOutputStream(target).use { output ->
                val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                output.flush()
                output.fd.sync()
                ok
            }
            if (!written || target.length() <= 0L) {
                target.delete()
                return@synchronized ""
            }
            val saved = ctx.applicationContext.getSharedPreferences(PREF, 0).edit()
                .putString("version_" + dir.name, target.name).commit()
            if (!saved) {
                target.delete()
                return@synchronized ""
            }
            // Cleanup is best-effort after publication: failure must not delete the new target.
            runCatching { pruneVersions(dir, target.absolutePath) }
            target.absolutePath
        } catch (_: Exception) {
            target.delete()
            ""
        }
    }

    /** Weak owners do not pin files forever when an Activity/controller is recreated. */
    fun retainOwnerVersion(owner: Any, path: String) = synchronized(versionLock) {
        if (path.isBlank()) ownerVersions.remove(owner) else ownerVersions[owner] = path
        Unit
    }

    fun retainVersion(path: String) = synchronized(versionLock) {
        if (path.isNotBlank()) pinnedVersions[path] = (pinnedVersions[path] ?: 0) + 1
    }

    fun releaseVersion(path: String) = synchronized(versionLock) {
        val count = pinnedVersions[path] ?: return@synchronized
        if (count > 1) pinnedVersions[path] = count - 1 else pinnedVersions.remove(path)
        // Publication performs pruning with the durable current pointer. Releasing a reference
        // must never guess the current version from timestamps (ties and clock changes are legal).
    }

    private fun pruneVersions(dir: File, currentPath: String) {
        val versions = dir.listFiles()?.filter { it.isFile && Regex("[a-f0-9-]{36}\\.jpg").matches(it.name) }
            ?.sortedByDescending { it.lastModified() } ?: return
        val keep = versions.filter { it.absolutePath != currentPath }.take(RETAINED_VERSIONS - 1)
            .mapTo(mutableSetOf()) { it.absolutePath }
        keep += currentPath
        versions.filter { it.absolutePath !in keep && it.absolutePath !in pinnedVersions && it.absolutePath !in ownerVersions.values }
            .forEach { it.delete() }
    }

    private fun retiredUsersOrNull(ctx: Context): Set<String>? = runCatching {
        ctx.applicationContext.getSharedPreferences(PREF, 0)
            .getStringSet(KEY_RETIRED_USERS, emptySet()).orEmpty().toSet()
    }.getOrNull()

    fun isUsernameRetired(ctx: Context, user: String): Boolean =
        user.isNotBlank() && (retiredUsersOrNull(ctx)?.contains(safeUser(user)) ?: true)

    /** Retire only the username-to-avatar association; preserve all avatar bytes. */
    fun retireUsername(ctx: Context, user: String): Boolean {
        if (user.isBlank()) return false
        val appContext = ctx.applicationContext
        val current = retiredUsersOrNull(appContext) ?: return false
        if (safeUser(user) in current) return true
        val retired = current + safeUser(user)
        return runCatching {
            appContext.getSharedPreferences(PREF, 0).edit()
                .putStringSet(KEY_RETIRED_USERS, retired).commit()
        }.getOrDefault(false)
    }

    /** Clear a tombstone only after an authenticated remote avatar or explicit in-session avatar choice. */
    fun clearRetiredUsername(ctx: Context, user: String): Boolean {
        if (user.isBlank()) return false
        val appContext = ctx.applicationContext
        val current = retiredUsersOrNull(appContext) ?: return false
        if (safeUser(user) !in current) return true
        val retired = current - safeUser(user)
        return runCatching {
            val editor = appContext.getSharedPreferences(PREF, 0).edit()
            if (retired.isEmpty()) editor.remove(KEY_RETIRED_USERS)
            else editor.putStringSet(KEY_RETIRED_USERS, retired)
            editor.commit()
        }.getOrDefault(false)
    }

    fun path(ctx: Context, user: String = ""): String {
        val appContext = ctx.applicationContext
        val u = user.ifBlank {
            appContext.getSharedPreferences(PREF, 0).getString(KEY_LAST, "").orEmpty()
        }
        if (u.isNotBlank() && isUsernameRetired(appContext, u)) return ""
        accountSpecificPath(appContext, u).takeIf { it.isNotEmpty() }?.let { return it }
        // 兼容旧单文件；它始终只是未分配头像，不会被提升为账号头像。
        val legacy = File(appContext.filesDir, FILE)
        if (legacy.exists() && legacy.length() > 0) return legacy.absolutePath
        return ""
    }

    /** Return an existing account-owned avatar only while its username association is not retired. */
    fun accountSpecificPath(ctx: Context, user: String): String {
        if (user.isBlank() || isUsernameRetired(ctx, user)) return ""
        val accountFile = fileFor(ctx.applicationContext, user)
        return if (accountFile.exists() && accountFile.length() > 0) accountFile.absolutePath else ""
    }

    /** Ownership check ignores retirement only for an explicit, authenticated in-session choice. */
    fun isAccountAvatarPath(ctx: Context, user: String, path: String): Boolean =
        user.isNotBlank() && path.isNotBlank() &&
            fileFor(ctx.applicationContext, user).absolutePath == File(path).absolutePath

    fun file(ctx: Context): File = fileFor(ctx, "")

    fun clear(ctx: Context, user: String = "") = synchronized(versionLock) {
        runCatching {
            val dir = versionDirectory(ctx, user)
            dir.listFiles()?.filter { it.isFile && Regex("[a-f0-9-]{36}\\.jpg").matches(it.name) }
                ?.filter { it.absolutePath !in pinnedVersions && it.absolutePath !in ownerVersions.values }
                ?.forEach { it.delete() }
            ctx.applicationContext.getSharedPreferences(PREF, 0).edit()
                .remove("version_" + dir.name).commit()
            val legacy = File(ctx.applicationContext.filesDir,
                if (user.isBlank()) FILE else "user_avatar_${safeUser(user)}.jpg")
            if (legacy.absolutePath !in pinnedVersions && legacy.absolutePath !in ownerVersions.values) legacy.delete()
        }
        Unit
    }

    /**
     * @deprecated Generic legacy avatars are display-only. Use account-specific restore or
     * the explicit account-owned rename migration instead.
     */
    @Deprecated("Generic legacy avatars must not be promoted to account-owned storage")
    fun bindUser(ctx: Context, user: String): String {
        if (user.isBlank()) return path(ctx)
        val appContext = ctx.applicationContext
        appContext.getSharedPreferences(PREF, 0).edit().putString(KEY_LAST, user).apply()
        return accountSpecificPath(appContext, user)
    }

    /**
     * Copy only the currently verified old-account file after server-confirmed rename.
     * Existing destination files are never overwritten; both old and stale destination bytes remain intact.
     */
    data class RenameCopyOutcome(val avatarPath: String, val protectionsPersisted: Boolean)

    fun copyAccountAvatarForRename(
        ctx: Context,
        oldUser: String,
        newUser: String,
        verifiedSourcePath: String
    ): RenameCopyOutcome {
        if (oldUser.isBlank() || newUser.isBlank()) return RenameCopyOutcome("", false)
        val appContext = ctx.applicationContext
        val source = verifiedSourcePath.takeIf { it.isNotBlank() }?.let(::File)
        // accountSpecificPath may be hidden by the preflight retirement marker; this raw path is
        // accepted only when it exactly matches the path verified by the active account session.
        val expectedSourceFile = fileFor(appContext, oldUser)
        val expectedSource = expectedSourceFile.takeIf { it.exists() && it.length() > 0L }?.absolutePath.orEmpty()
        val target = fileFor(appContext, newUser)
        val samePath = source != null && expectedSource.isNotBlank() &&
            source.absolutePath == File(expectedSource).absolutePath &&
            source.absolutePath == target.absolutePath && source.exists() && source.length() > 0L
        if (samePath) {
            val oldRetired = retireUsername(appContext, oldUser)
            val targetAvailable = clearRetiredUsername(appContext, newUser)
            return RenameCopyOutcome(target.absolutePath, oldRetired && targetAvailable)
        }

        if (source == null || expectedSource.isBlank() ||
            source.absolutePath != File(expectedSource).absolutePath ||
            !source.exists() || source.length() <= 0L
        ) {
            return RenameCopyOutcome("", retireBoth(appContext, oldUser, newUser))
        }
        if (target.exists()) {
            // The destination may belong to a former holder of this username. Hide, never overwrite it.
            return RenameCopyOutcome("", retireBoth(appContext, oldUser, newUser))
        }

        return try {
            val copied = source.copyTo(target, overwrite = false)
            if (!copied.exists() || copied.length() != source.length()) {
                RenameCopyOutcome("", retireBoth(appContext, oldUser, newUser))
            } else {
                val oldRetired = retireUsername(appContext, oldUser)
                val targetAvailable = clearRetiredUsername(appContext, newUser)
                RenameCopyOutcome(copied.absolutePath, oldRetired && targetAvailable)
            }
        } catch (_: Exception) {
            RenameCopyOutcome("", retireBoth(appContext, oldUser, newUser))
        }
    }

    private fun retireBoth(ctx: Context, oldUser: String, newUser: String): Boolean {
        val oldRetired = retireUsername(ctx, oldUser)
        val newRetired = retireUsername(ctx, newUser)
        return oldRetired && newRetired
    }

    fun saveFromUri(ctx: Context, uri: Uri, user: String = ""): String {
        val raw = decodeUri(ctx, uri) ?: return ""
        return try { saveFromBitmap(ctx, raw, user) } catch (_: Exception) { "" }
        finally { raw.recycle() }
    }

    fun saveFromBitmap(ctx: Context, bmp: Bitmap, user: String = ""): String = try {
        val sized = scale(bmp, 1024)
        try {
            val u = user.ifBlank {
                ctx.applicationContext.getSharedPreferences(PREF, 0).getString(KEY_LAST, "").orEmpty()
            }
            val path = writeVersion(ctx, u, sized)
            if (path.isNotBlank()) {
                // Generic legacy data remains display-only; never promoted by account restoration.
                runCatching {
                    FileOutputStream(File(ctx.applicationContext.filesDir, FILE)).use { output ->
                        sized.compress(Bitmap.CompressFormat.JPEG, 90, output)
                    }
                }
                if (u.isNotBlank()) ctx.applicationContext.getSharedPreferences(PREF, 0)
                    .edit().putString(KEY_LAST, u).apply()
            }
            path
        } finally { if (sized !== bmp) sized.recycle() }
    } catch (_: Exception) { "" }

    fun decodeUri(ctx: Context, uri: Uri): Bitmap? {
        return try { decodeUriSampled(ctx, uri, ImageDecodeLimits.MAX_AVATAR_DIMENSION) }
        catch (_: Exception) { null }
    }

    /** Playlist covers have a stricter inline limit than avatars; never save an unsyncable selection. */
    fun toPlaylistCoverB64(path: String): String {
        var bitmap: Bitmap? = null
        return try {
            val decoded = decodeFileSampled(File(path), ImageDecodeLimits.MAX_AVATAR_DIMENSION) ?: return ""
            bitmap = decoded
            var current = scale(decoded, 1024)
            if (current !== decoded) decoded.recycle()
            bitmap = current
            val prefix = "data:image/jpeg;base64,"
            while (true) {
                for (quality in 90 downTo 40 step 10) {
                    val output = java.io.ByteArrayOutputStream()
                    if (!current.compress(Bitmap.CompressFormat.JPEG, quality, output)) return ""
                    val bytes = output.toByteArray()
                    // Base64 grows by 4 characters per 3 bytes, plus the data URL header.
                    if (prefix.length + 4L * ((bytes.size + 2L) / 3L) <= MAX_CLOUD_INLINE_IMAGE_CHARS) {
                        return prefix + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    }
                }
                val longest = maxOf(current.width, current.height)
                if (longest <= 256) return ""
                val smaller = scale(current, maxOf(256, longest * 3 / 4))
                if (smaller === current) return ""
                current.recycle()
                current = smaller
                bitmap = current
            }
            @Suppress("UNREACHABLE_CODE") ""
        } catch (_: Exception) { "" } finally { bitmap?.recycle() }
    }

    fun toB64(path: String): String {
        var decoded: Bitmap? = null
        return try {
            val sampled = decodeFileSampled(File(path), ImageDecodeLimits.MAX_AVATAR_DIMENSION) ?: return ""
            decoded = sampled
            // The source is sampled before pixel allocation; output remains within the sync payload budget.
            var q = 90
            var bytes = ByteArray(0)
            while (q >= 40) {
                val bos = java.io.ByteArrayOutputStream()
                if (!sampled.compress(Bitmap.CompressFormat.JPEG, q, bos)) return ""
                bytes = bos.toByteArray()
                if (bytes.size <= 1_200_000) break
                q -= 10
            }
            if (bytes.isEmpty() || bytes.size > ImageDecodeLimits.MAX_AVATAR_DECODED_BYTES) return ""
            val result = "data:image/jpeg;base64," +
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            result.takeIf { it.length <= ImageDecodeLimits.MAX_AVATAR_ENCODED_CHARS }.orEmpty()
        } catch (_: Exception) { "" } finally {
            decoded?.recycle()
        }
    }

    fun saveFromB64(ctx: Context, user: String, b64: String): String {
        if (b64.isBlank()) return ""
        return try {
            val raw = decodeB64(b64) ?: return ""
            try { saveFromBitmap(ctx, raw, user) } finally { raw.recycle() }
        } catch (_: Exception) { "" }
    }

    /** Restore only the authenticated account file; never mutate the generic legacy avatar. */
    fun saveAccountAvatarFromB64(ctx: Context, user: String, b64: String): String {
        if (user.isBlank() || b64.isBlank()) return ""
        return try {
            val raw = decodeB64(b64) ?: return ""
            val sized = scale(raw, 1024)
            val path = try { writeVersion(ctx, user, sized) } finally {
                if (sized !== raw) sized.recycle()
                raw.recycle()
            }
            if (path.isBlank() || !clearRetiredUsername(ctx, user)) "" else path
        } catch (_: Exception) { "" }
    }

    private fun decodeB64(b64: String): Bitmap? {
        if (b64.length > ImageDecodeLimits.MAX_AVATAR_ENCODED_CHARS) return null
        val payload = if (b64.startsWith("data:", ignoreCase = true)) {
            val comma = b64.indexOf(',')
            if (comma <= 0 || !b64.substring(0, comma).contains(";base64", ignoreCase = true)) return null
            b64.substring(comma + 1)
        } else b64
        if (payload.isEmpty() || payload.length > ImageDecodeLimits.MAX_AVATAR_ENCODED_CHARS) return null
        val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        return decodeBytesSampled(bytes, ImageDecodeLimits.MAX_AVATAR_DIMENSION)
    }

    /**
     * Sampling for user-picked avatar/cover sources. Unlike ImageDecodeLimits.sampleSize this
     * never rejects a source for exceeding a pixel-count budget: an ordinary phone photo
     * (e.g. 4000x3000) must still import, sampled down to the target edge — the memory bound
     * comes from the sampled output size and the compressed-byte budgets, not from refusing
     * the source. (Adaptation of hark SW-03-06, whose 4 MP source cap would silently reject
     * normal camera photos as avatars.)
     */
    private fun avatarSampleSize(width: Int, height: Int, targetMaxDimension: Int): Int {
        if (width <= 0 || height <= 0 || targetMaxDimension <= 0) return 0
        var sample = 1
        while ((width + sample - 1) / sample > targetMaxDimension ||
            (height + sample - 1) / sample > targetMaxDimension
        ) {
            sample = sample shl 1
        }
        return sample
    }

    private fun decodeUriSampled(ctx: Context, uri: Uri, targetMaxDimension: Int): Bitmap? {
        val resolver = ctx.applicationContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsInput = resolver.openInputStream(uri) ?: return null
        boundsInput.use { input -> decodeBoundedStream(input, bounds) }
        val sample = avatarSampleSize(bounds.outWidth, bounds.outHeight, targetMaxDimension)
        if (sample == 0) return null
        val input = resolver.openInputStream(uri) ?: return null
        return input.use { decodeBoundedStream(it, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    private fun decodeBoundedStream(input: InputStream, options: BitmapFactory.Options): Bitmap? =
        BufferedInputStream(BoundedAvatarInputStream(input)).use { BitmapFactory.decodeStream(it, null, options) }

    private fun decodeFileSampled(file: File, targetMaxDimension: Int): Bitmap? {
        if (!file.isFile || file.length() <= 0L ||
            file.length() > ImageDecodeLimits.MAX_COMPRESSED_BYTES_LONG
        ) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = avatarSampleSize(bounds.outWidth, bounds.outHeight, targetMaxDimension)
        if (sample == 0) return null
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun decodeBytesSampled(bytes: ByteArray, targetMaxDimension: Int): Bitmap? {
        if (bytes.isEmpty() || bytes.size > ImageDecodeLimits.MAX_AVATAR_DECODED_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = avatarSampleSize(bounds.outWidth, bounds.outHeight, targetMaxDimension)
        if (sample == 0) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Counts bytes actually consumed from local/cloud-backed content streams, independent of headers. */
    private class BoundedAvatarInputStream(input: InputStream) : FilterInputStream(input) {
        private var bytesRead = 0L

        override fun read(): Int {
            if (bytesRead >= ImageDecodeLimits.MAX_COMPRESSED_BYTES_LONG) {
                val extra = super.read()
                if (extra < 0) return -1
                throw IOException("Avatar image exceeds byte limit")
            }
            val value = super.read()
            if (value >= 0) bytesRead++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            val remaining = ImageDecodeLimits.MAX_COMPRESSED_BYTES_LONG - bytesRead
            if (remaining <= 0L) return readAfterLimit()
            val allowed = minOf(length.toLong(), remaining + 1L).toInt()
            val count = super.read(buffer, offset, allowed)
            if (count > 0) {
                bytesRead += count
                if (bytesRead > ImageDecodeLimits.MAX_COMPRESSED_BYTES_LONG) {
                    throw IOException("Avatar image exceeds byte limit")
                }
            }
            return count
        }

        private fun readAfterLimit(): Int {
            val extra = super.read()
            if (extra < 0) return -1
            throw IOException("Avatar image exceeds byte limit")
        }
    }

    private fun scale(src: Bitmap, max: Int): Bitmap {
        val w = src.width
        val h = src.height
        val m = maxOf(w, h)
        if (m <= max) return src
        val scale = max.toFloat() / m
        return Bitmap.createScaledBitmap(src, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
    }
}
