package xyz.cncnlp.soundwave

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import xyz.cncnlp.soundwave.image.ImageDecodeLimits

@RunWith(RobolectricTestRunner::class)
class AvatarStoreReadOnlyPathTest {
    @Test
    fun accountLookupReadsExistingUserAvatarWithoutChangingLastUser() = withFixture { context, user, userFile, prefs ->
        val accountAvatar = byteArrayOf(9, 8, 7, 6)
        userFile.writeBytes(accountAvatar)

        assertEquals(userFile.absolutePath, AvatarStore.path(context, user))
        assertArrayEquals(accountAvatar, userFile.readBytes())
        assertEquals("previous-local-user", prefs.getString("last_user", null))
    }

    @Test
    fun missingAccountAvatarFallsBackToLegacyFileWithoutCopyingOrBinding() = withFixture { context, user, userFile, prefs ->
        val legacyAvatar = byteArrayOf(1, 3, 5, 7)
        val legacyFile = AvatarStore.file(context)
        legacyFile.writeBytes(legacyAvatar)

        assertEquals(legacyFile.absolutePath, AvatarStore.path(context, user))
        assertEquals("", AvatarStore.accountSpecificPath(context, user))
        assertArrayEquals(legacyAvatar, legacyFile.readBytes())
        assertFalse(userFile.exists())
        assertEquals("previous-local-user", prefs.getString("last_user", null))
    }

    @Test
    fun deprecatedBindUserCannotPromoteTheSharedLegacyAvatar() = withFixture { context, user, userFile, prefs ->
        val legacyAvatar = byteArrayOf(4, 3, 2, 1)
        val legacyFile = AvatarStore.file(context)
        legacyFile.writeBytes(legacyAvatar)

        @Suppress("DEPRECATION")
        val boundPath = AvatarStore.bindUser(context, user)

        assertEquals("", boundPath)
        assertEquals("", AvatarStore.accountSpecificPath(context, user))
        assertFalse(userFile.exists())
        assertArrayEquals(legacyAvatar, legacyFile.readBytes())
        assertEquals(user, prefs.getString("last_user", null))
    }

    @Test
    fun authenticatedAvatarRestorePublishesNewFileAndClearsTombstoneWithoutOverwritingStaleBytes() = withFixture { context, user, userFile, prefs ->
        val legacyFile = AvatarStore.file(context)
        val legacyBefore = legacyFile.readBytes()
        val staleSource = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        staleSource.eraseColor(android.graphics.Color.RED)
        val staleBytes = ByteArrayOutputStream().use { output ->
            assertTrue(staleSource.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        staleSource.recycle()
        userFile.writeBytes(staleBytes)
        AvatarStore.retireUsername(context, user)
        assertEquals("", AvatarStore.accountSpecificPath(context, user))
        assertTrue(userFile.readBytes().contentEquals(staleBytes))

        val source = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.BLUE)
        val encoded = ByteArrayOutputStream().use { output ->
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
            Base64.getEncoder().encodeToString(output.toByteArray())
        }
        source.recycle()

        val restored = AvatarStore.saveAccountAvatarFromB64(context, user, "data:image/png;base64,$encoded")

        assertFalse(userFile.absolutePath == restored)
        assertEquals(restored, AvatarStore.accountSpecificPath(context, user))
        assertTrue(userFile.exists())
        assertFalse("only an authenticated remote avatar clears the retired mapping", AvatarStore.isUsernameRetired(context, user))
        assertNotNull(BitmapFactory.decodeFile(restored))
        assertArrayEquals("immutable restore preserves the hidden stale file", staleBytes, userFile.readBytes())
        assertArrayEquals(legacyBefore, legacyFile.readBytes())
        assertEquals("previous-local-user", prefs.getString("last_user", null))
    }

    @Test
    fun renameCopyRetainsVerifiedSourceAndCopiesOnlyToAnUnusedTarget() = withFixture { context, oldUser, sourceFile, _ ->
        val newUser = "rename_target_${UUID.randomUUID().toString().replace("-", "")}"
        val target = File(context.filesDir, "user_avatar_${newUser}.jpg")
        val ownedBytes = byteArrayOf(7, 6, 5, 4, 3)
        try {
            sourceFile.writeBytes(ownedBytes)

            val copied = AvatarStore.copyAccountAvatarForRename(context, oldUser, newUser, sourceFile.absolutePath)

            assertEquals(target.absolutePath, copied.avatarPath)
            assertTrue(copied.protectionsPersisted)
            assertArrayEquals("rename never deletes the old local avatar", ownedBytes, sourceFile.readBytes())
            assertArrayEquals(ownedBytes, target.readBytes())
            assertEquals("the old username is retired but its bytes remain", "", AvatarStore.accountSpecificPath(context, oldUser))
            assertEquals(target.absolutePath, AvatarStore.accountSpecificPath(context, newUser))
        } finally {
            target.delete()
        }
    }

    @Test
    fun renameWithStaleDestinationHidesButPreservesBothAvatarFiles() = withFixture { context, oldUser, sourceFile, _ ->
        val newUser = "stale_target_${UUID.randomUUID().toString().replace("-", "")}"
        val target = File(context.filesDir, "user_avatar_${newUser}.jpg")
        val ownedBytes = byteArrayOf(1, 2, 3, 4)
        val staleBytes = byteArrayOf(9, 8, 7, 6, 5)
        try {
            sourceFile.writeBytes(ownedBytes)
            target.writeBytes(staleBytes)

            val copied = AvatarStore.copyAccountAvatarForRename(context, oldUser, newUser, sourceFile.absolutePath)

            assertEquals("an existing username destination is never overwritten", "", copied.avatarPath)
            assertTrue("both hidden-name markers were durably stored", copied.protectionsPersisted)
            assertArrayEquals(ownedBytes, sourceFile.readBytes())
            assertArrayEquals(staleBytes, target.readBytes())
            assertTrue(AvatarStore.isUsernameRetired(context, oldUser))
            assertTrue(AvatarStore.isUsernameRetired(context, newUser))
            assertEquals("", AvatarStore.accountSpecificPath(context, oldUser))
            assertEquals("", AvatarStore.accountSpecificPath(context, newUser))
            assertEquals("", AvatarStore.path(context, newUser))
        } finally {
            target.delete()
        }
    }

    @Test
    fun failedRetirementAndAuthenticatedTombstoneClearAreReportedAndFailClosed() = withFixture { context, user, userFile, _ ->
        val neverRetired = "commit-failure-${UUID.randomUUID()}"
        val failingContext = CommitFailingContext(context)
        val source = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.CYAN)
        val encoded = ByteArrayOutputStream().use { output ->
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
            Base64.getEncoder().encodeToString(output.toByteArray())
        }
        source.recycle()
        try {
            assertFalse("retirement commit failure must be returned", AvatarStore.retireUsername(failingContext, neverRetired))
            assertFalse(AvatarStore.isUsernameRetired(context, neverRetired))

            userFile.writeBytes(byteArrayOf(3, 1, 4, 1))
            assertTrue(AvatarStore.retireUsername(context, user))
            assertFalse("clear commit failure must be returned", AvatarStore.clearRetiredUsername(failingContext, user))
            assertTrue(AvatarStore.isUsernameRetired(context, user))

            val restored = AvatarStore.saveAccountAvatarFromB64(
                failingContext, user, "data:image/png;base64,$encoded"
            )

            assertEquals("a written file stays hidden when tombstone removal cannot be committed", "", restored)
            assertTrue(AvatarStore.isUsernameRetired(context, user))
            assertEquals("", AvatarStore.accountSpecificPath(context, user))
            assertTrue("the write is non-destructive and keeps local bytes", userFile.exists() && userFile.length() > 0L)
        } finally {
            AvatarStore.clearRetiredUsername(context, neverRetired)
        }
    }

    @Test
    fun successiveChoicesHaveDistinctPathsAndPinnedBytesSurviveBoundedCleanup() = withFixture { context, user, _, _ ->
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        val first = AvatarStore.saveFromBitmap(context, bitmap, user)
        val original = File(first).readBytes()
        AvatarStore.retainVersion(first)
        try {
            repeat(12) { index ->
                bitmap.eraseColor(if (index % 2 == 0) android.graphics.Color.BLUE else android.graphics.Color.GREEN)
                val next = AvatarStore.saveFromBitmap(context, bitmap, user)
                assertFalse("every publication changes Compose/Coil identity", first == next)
                assertEquals(next, AvatarStore.accountSpecificPath(context, user))
                assertNotNull(BitmapFactory.decodeFile(next))
            }
            assertArrayEquals(original, File(first).readBytes())
            assertTrue("retention is bounded even while an older visible file is pinned",
                File(first).parentFile!!.listFiles()!!.size <= 4)
        } finally {
            AvatarStore.releaseVersion(first)
            AvatarStore.clear(context, user)
            bitmap.recycle()
        }
    }

    @Test
    fun uriAndBase64ImportsSampleWithinPixelBudgetAndRejectOversizedPayloads() = withFixture { context, user, _, _ ->
        val allowedFile = File(context.cacheDir, "avatar-allowed-${UUID.randomUUID()}.jpg")
        val oversizedFile = File(context.cacheDir, "avatar-oversized-${UUID.randomUUID()}.jpg")
        try {
            writePng(allowedFile, 4096, 1024)
            val uri = Uri.fromFile(allowedFile)
            val directInput = context.contentResolver.openInputStream(uri)
            assertNotNull("Robolectric must open the file URI fixture", directInput)
            directInput!!.close()
            val sampled = AvatarStore.decodeUri(context, uri)
            assertNotNull(sampled)
            sampled!!.let {
                assertEquals(1024, it.width)
                assertEquals(256, it.height)
                it.recycle()
            }

            // Adapted from hark SW-03-06: a source just over the old 4 MP cap is not
            // rejected — ordinary phone photos exceed it — it samples down to the
            // avatar edge budget instead. The payload-size gates still reject.
            writePng(oversizedFile, 2049, 2049)
            val sampledOversized = AvatarStore.decodeUri(context, Uri.fromFile(oversizedFile))
            assertNotNull(sampledOversized)
            sampledOversized!!.let {
                assertTrue(it.width <= ImageDecodeLimits.MAX_AVATAR_DIMENSION)
                assertTrue(it.height <= ImageDecodeLimits.MAX_AVATAR_DIMENSION)
                it.recycle()
            }
            val oversizedData = "data:image/jpeg;base64," +
                android.util.Base64.encodeToString(oversizedFile.readBytes(), android.util.Base64.NO_WRAP)
            assertTrue(AvatarStore.saveAccountAvatarFromB64(context, user, oversizedData).isNotEmpty())
            assertTrue(AvatarStore.accountSpecificPath(context, user).isNotEmpty())
            assertEquals("", AvatarStore.saveAccountAvatarFromB64(
                context, user, "A".repeat(ImageDecodeLimits.MAX_AVATAR_ENCODED_CHARS + 1)
            ))
        } finally {
            allowedFile.delete()
            oversizedFile.delete()
        }
    }

    @Test
    fun accountAvatarExportBoundsDecodedPixelsBeforeEncoding() = withFixture { context, _, _, _ ->
        val source = File(context.cacheDir, "avatar-export-${UUID.randomUUID()}.jpg")
        try {
            writePng(source, 4096, 1024)
            assertTrue("test JPEG should stay under the compressed byte budget", source.length() <= ImageDecodeLimits.MAX_COMPRESSED_BYTES_LONG)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            assertEquals(4096, bounds.outWidth)
            assertEquals(1024, bounds.outHeight)
            val sample = ImageDecodeLimits.sampleSize(bounds.outWidth, bounds.outHeight, ImageDecodeLimits.MAX_AVATAR_DIMENSION)
            val decodedProbe = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            assertNotNull("sampled file decode should work", decodedProbe)
            val probeOutput = java.io.ByteArrayOutputStream()
            assertTrue("JPEG encode should work", decodedProbe!!.compress(Bitmap.CompressFormat.JPEG, 90, probeOutput))
            decodedProbe.recycle()
            val encoded = AvatarStore.toB64(source.absolutePath)
            assertTrue(encoded.startsWith("data:image/jpeg;base64,"))
            assertTrue(encoded.length <= ImageDecodeLimits.MAX_AVATAR_ENCODED_CHARS)
            val bytes = android.util.Base64.decode(encoded.substringAfter(','), android.util.Base64.DEFAULT)
            val result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(result)
            result!!.let {
                assertTrue(it.width <= ImageDecodeLimits.MAX_AVATAR_DIMENSION)
                assertTrue(it.height <= ImageDecodeLimits.MAX_AVATAR_DIMENSION)
                it.recycle()
            }
        } finally {
            source.delete()
        }
    }

    private fun writePng(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun withFixture(block: (Context, String, File, SharedPreferences) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("avatar_map", Context.MODE_PRIVATE)
        val previousLastUser = prefs.getString("last_user", null)
        val previousRetiredUsers = prefs.getStringSet("retired_users_v1", null)?.toSet()
        val legacyFile = AvatarStore.file(context)
        val previousLegacyBytes = legacyFile.takeIf { it.exists() }?.readBytes()
        val user = "readonly_${UUID.randomUUID().toString().replace("-", "")}"
        val userFile = File(context.filesDir, "user_avatar_${user}.jpg")
        try {
            prefs.edit().putString("last_user", "previous-local-user").commit()
            legacyFile.parentFile?.mkdirs()
            legacyFile.writeBytes(byteArrayOf(2, 4, 6, 8))
            block(context, user, userFile, prefs)
        } finally {
            AvatarStore.clear(context, user)
            if (previousLegacyBytes == null) legacyFile.delete() else legacyFile.writeBytes(previousLegacyBytes)
            val editor = prefs.edit()
            if (previousLastUser == null) editor.remove("last_user") else editor.putString("last_user", previousLastUser)
            if (previousRetiredUsers.isNullOrEmpty()) editor.remove("retired_users_v1")
            else editor.putStringSet("retired_users_v1", previousRetiredUsers)
            editor.commit()
        }
    }

    private class CommitFailingContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val delegate = super.getSharedPreferences(name, mode)
            if (name != "avatar_map") return delegate
            return object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    val editor = delegate.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                            editor.putStringSet(key, values)
                            return this
                        }

                        override fun remove(key: String): SharedPreferences.Editor {
                            editor.remove(key)
                            return this
                        }

                        override fun commit(): Boolean = false
                    }
                }
            }
        }
    }
}
