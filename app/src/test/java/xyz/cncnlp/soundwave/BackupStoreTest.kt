package xyz.cncnlp.soundwave

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.WEB_DAV_LEGACY_DATA_BLOCKED_MESSAGE
import xyz.cncnlp.soundwave.sync.WebDavHttpRequest
import xyz.cncnlp.soundwave.sync.WebDavHttpResponse
import xyz.cncnlp.soundwave.sync.WebDavSavedCredentials
import xyz.cncnlp.soundwave.sync.WebDavSyncClient
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WebDavTransport
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class BackupStoreTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val preferenceNames = listOf("soundwave", "soundwave_scripts", "soundwave_userdata", "soundwave_legacy_local", "avatar_map", "listen_stats_v1", "backup_restore_recovery_v1")
    private val avatarUser = "backup_test_avatar_user"
    private lateinit var originalPreferences: Map<String, Map<String, *>>
    private lateinit var originalAvatarFiles: Map<File, ByteArray?>

    @Before
    fun snapshotPreferences() {
        originalPreferences = preferenceNames.associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
        }
        originalAvatarFiles = listOf(
            context.filesDir.resolve("user_avatar.jpg"),
            context.filesDir.resolve("user_avatar_${avatarUser}.jpg")
        ).associateWith { file -> if (file.exists()) file.readBytes() else null }
    }

    @After
    fun restorePreferences() {
        originalPreferences.forEach { (name, values) ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.forEach { (key, value) -> putPreference(editor, key, value) }
            editor.commit()
        }
        originalAvatarFiles.forEach { (file, contents) ->
            if (contents == null) file.delete() else file.writeBytes(contents)
        }
    }

    @Test
    fun backupNeverExportsOrRestoresAccountIdentityOrSessionState() {
        val prefs = context.getSharedPreferences("soundwave", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("user", "destination-user")
            .putBoolean("logged_in", true)
            .putString("pass", "destination-encrypted-pass")
            .commit()

        val exportedSettings = BackupStore.export(context).getJSONObject("settings")
        assertFalse(exportedSettings.has("user"))
        assertFalse(exportedSettings.has("logged_in"))
        assertFalse(exportedSettings.has("pass"))

        val incoming = JSONObject()
            .put("schema", "soundwave-backup")
            .put("version", 1)
            .put("user", JSONObject())
            .put("settings", JSONObject()
                .put("user", "source-user")
                .put("logged_in", false)
                .put("pass", "source-encrypted-pass"))
        BackupStore.restore(context, incoming)

        assertEquals("destination-user", prefs.getString("user", null))
        assertTrue(prefs.getBoolean("logged_in", false))
        assertEquals("destination-encrypted-pass", prefs.getString("pass", null))
    }

    @Test
    fun backupRestoresLocalDailyStatsOlderThanCloudRetention() {
        val now = System.currentTimeMillis()
        val day = ListeningCalendar.dayKey(now - 120L * 24L * 60L * 60L * 1000L)
        val daily = JSONObject().put(day, JSONObject().put("songs", JSONObject().put("wy:old", JSONObject()
            .put("name", "Old song")
            .put("singer", "Old artist")
            .put("cover", "")
            .put("devices", JSONObject().put("local-install", JSONObject().put("sec", 75L).put("cnt", 2L))))))
        assertTrue(ListenStats.restoreLocalDailyListenStats(context, daily.toString(), now))

        val backup = BackupStore.export(context)
        assertTrue(backup.getJSONObject("dailyListenStats").has(day))
        context.getSharedPreferences("listen_stats_v1", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(ListenStats.restoreLocalDailyListenStats(context, "{}", now))

        BackupStore.restore(context, backup)

        val restored = JSONObject(ListenStats.localDailyListenStats(context, now))
        assertTrue(restored.has(day))
        assertEquals(2L, restored.getJSONObject(day).getJSONObject("songs")
            .getJSONObject("wy:old").getJSONObject("devices").getJSONObject("local-install").getLong("cnt"))
    }

    @Test
    fun invalidUserPayloadsAreRejectedWithoutChangingLocalData() {
        val favorite = Song(id = "local-favorite", name = "本地收藏", singer = "歌手", source = "wy")
        val bundle = LocalStore.UserBundle().apply {
            addFavorite(favorite)
            addOrReplacePlaylist(Playlist("local-playlist", "本地歌单", local = true), nowMs = 123L)
        }
        LocalStore.save(context, bundle)
        LocalStore.pushHistory(context, Song(id = "local-history", name = "本地历史", singer = "歌手", source = "wy"))
        LocalStore.addDownload(
            context,
            DownloadItem(
                song = Song(id = "local-download", name = "本地下载", singer = "歌手", source = "wy"),
                path = "file:///local/download.mp3",
                size = 123L,
                time = 456L
            )
        )
        val settings = context.getSharedPreferences("soundwave", Context.MODE_PRIVATE)
        val scripts = context.getSharedPreferences("soundwave_scripts", Context.MODE_PRIVATE)
        settings.edit().putString("backup_test_setting", "local-setting").commit()
        scripts.edit().putString("backup_test_script", "local-script").commit()

        val userBefore = LocalStore.load(context).toCloudJson().toString()
        val historyBefore = LocalStore.loadHistory(context)
        val downloadsBefore = LocalStore.loadDownloads(context)
        val settingsBefore = settings.all.toMap()
        val scriptsBefore = scripts.all.toMap()
        val invalidBackups = listOf(
            backupWithMutationPayload(),
            backupWithMutationPayload().put("user", JSONObject.NULL),
            backupWithMutationPayload().put("user", JSONArray()),
            backupWithMutationPayload().put("user", "not-an-object")
        )

        invalidBackups.forEach { backup ->
            var rejected = false
            try {
                BackupStore.restore(context, backup)
            } catch (error: IllegalArgumentException) {
                rejected = true
                assertEquals("备份用户数据缺失或格式无效", error.message)
            }
            assertTrue("invalid user payload must be rejected", rejected)
            assertEquals(userBefore, LocalStore.load(context).toCloudJson().toString())
            assertEquals(historyBefore, LocalStore.loadHistory(context))
            assertEquals(downloadsBefore, LocalStore.loadDownloads(context))
            assertEquals(settingsBefore, settings.all)
            assertEquals(scriptsBefore, scripts.all)
        }
    }

    @Test
    fun previewCountsOnlyRestorableEntriesWithoutMutatingBackupJson() {
        val song = Song(id = "preview-song", name = "预览歌曲", singer = "歌手", source = "wy", playedAt = System.currentTimeMillis())
        val user = JSONObject()
            .put("favs", JSONArray()
                .put(song.toJson())
                .put(JSONObject()))
            .put("pls", JSONArray()
                .put(JSONObject().put("id", "liked").put("songs", JSONArray()))
                .put(JSONObject().put("id", "local").put("songs", JSONArray()))
                .put(JSONObject().put("id", "custom-playlist").put("songs", JSONArray().put(song.toJson()))))
            .put("avatar", "data:image/png;base64,preview-avatar")
        val backup = JSONObject()
            .put("schema", "soundwave-backup")
            .put("version", 1)
            .put("user", user)
            .put("history", JSONArray().put(song.toJson()).put(JSONObject()))
            .put("downloads", JSONArray()
                .put(JSONObject().put("song", song.toJson()))
                .put(JSONObject().put("id", "direct-download"))
                .put(JSONObject().put("song", JSONObject())))
            .put("scripts", JSONObject()
                .put("script_meta", "[{\"id\":\"local\",\"enabled\":true}]")
                .put("script_code_local", "trusted-code"))
        val originalJson = backup.toString()

        val preview = BackupStore.preview(backup)

        assertEquals(1, preview.favoriteCount)
        assertEquals(1, preview.playlistCount)
        assertEquals(1, preview.historyCount)
        assertEquals(2, preview.downloadCount)
        assertTrue(preview.hasAvatar)
        assertTrue(preview.hasScripts)
        assertEquals("preview must not mutate the selected backup", originalJson, backup.toString())

        // Source code alone is not portable; only non-empty order/enabled configuration counts.
        val codeOnlyBackup = JSONObject(originalJson).put("scripts", JSONObject()
            .put("script_meta", "[]").put("script_code_local", "trusted-code"))
        val codeOnlyJson = codeOnlyBackup.toString()
        assertFalse(BackupStore.preview(codeOnlyBackup).hasScripts)
        assertEquals(codeOnlyJson, codeOnlyBackup.toString())
    }

    @Test
    fun previewRejectsMissingUserDataBeforeConfirmation() {
        val backup = JSONObject().put("schema", "soundwave-backup").put("version", 1)
        val originalJson = backup.toString()

        var rejected = false
        try {
            BackupStore.preview(backup)
        } catch (error: IllegalArgumentException) {
            rejected = true
            assertEquals("备份用户数据缺失或格式无效", error.message)
        }

        assertTrue("missing user data must be rejected before confirmation", rejected)
        assertEquals(originalJson, backup.toString())
    }

    @Test
    fun validBackupStillRestoresUserDataHistoryDownloadsAndPreferences() {
        context.getSharedPreferences("soundwave", Context.MODE_PRIVATE).edit()
            .putString("theme_mode", "light").commit()
        LocalStore.save(
            context,
            LocalStore.UserBundle().apply {
                addFavorite(Song(id = "old-favorite", name = "旧收藏", singer = "歌手", source = "wy"))
            }
        )
        LocalStore.pushHistory(context, Song(id = "old-history", name = "旧历史", singer = "歌手", source = "wy"))
        LocalStore.addDownload(
            context,
            DownloadItem(
                song = Song(id = "old-download", name = "旧下载", singer = "歌手", source = "wy"),
                path = "file:///old/download.mp3",
                size = 1L,
                time = 1L
            )
        )
        val backupFavorite = Song(id = "backup-favorite", name = "备份收藏", singer = "歌手", source = "wy")
        val backupHistory = Song(id = "backup-history", name = "备份历史", singer = "歌手", source = "wy", playedAt = System.currentTimeMillis() - 789L)
        val backupDownload = Song(id = "backup-download", name = "备份下载", singer = "歌手", source = "wy")
        val backup = JSONObject()
            .put("schema", "soundwave-backup")
            .put("version", 1)
            .put("user", JSONObject().put("favs", JSONArray().put(backupFavorite.toJson())))
            .put("history", JSONArray().put(backupHistory.toJson()))
            .put("downloads", JSONArray().put(JSONObject()
                .put("song", backupDownload.toJson())
                .put("path", "file:///backup/download.mp3")
                .put("size", 789L)
                .put("time", 987L)
                .put("status", "done")))
            .put("settings", JSONObject().put("theme_mode", "dark"))

        BackupStore.restore(context, backup)

        assertEquals(listOf("backup-favorite"), LocalStore.load(context).favs.map { it.id })
        assertEquals(listOf("backup-history"), LocalStore.loadHistory(context).map { it.id })
        assertEquals("file:///backup/download.mp3", LocalStore.loadDownloads(context).single().path)
        assertEquals("dark", context.getSharedPreferences("soundwave", Context.MODE_PRIVATE)
            .getString("theme_mode", null))
    }

    @Test
    fun backupHistoryPreviewAndRestoreDropExpiredAndPreserveRecentTimestamp() {
        val now = System.currentTimeMillis()
        val expired = Song("expired-backup-history", "Expired", "Artist", "wy",
            playedAt = now - PlaybackHistoryPolicy.RETENTION_MS - 1L)
        val recent = Song("recent-backup-history", "Recent", "Artist", "wy", playedAt = now - 1_000L)
        val backup = JSONObject()
            .put("schema", "soundwave-backup")
            .put("version", 1)
            .put("user", JSONObject().put("favs", JSONArray()))
            .put("history", JSONArray().put(expired.toJson()).put(recent.toJson()))

        assertEquals(1, BackupStore.preview(backup).historyCount)
        BackupStore.restore(context, backup)

        val restored = LocalStore.loadHistory(context)
        assertEquals(listOf("recent-backup-history"), restored.map { it.id })
        assertEquals(recent.playedAt, restored.single().playedAt)
    }

    @Test
    fun genericRestoreAfterEmptyCaptureFailsClosedAndBlocksWebDavBeforeCredentialsOrTransport() {
        prepareVerifiedEmptySnapshot()
        val snapshotPreferences = context.getSharedPreferences(
            LegacyUnassignedSnapshot.SNAPSHOT_PREFERENCES, Context.MODE_PRIVATE
        )
        val immutableSnapshot = snapshotPreferences.getString(LegacyUnassignedSnapshot.SNAPSHOT_KEY, null)
        val rejected = runCatching {
            BackupStore.restore(context, JSONObject().put("schema", "soundwave-backup").put("version", 1))
        }.exceptionOrNull()
        assertTrue("invalid backup must fail before writing or invalidating the gate", rejected != null)
        assertEquals(
            LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN,
            LegacyUnassignedSnapshot.accountSyncGateState(context)
        )

        val restoredSong = Song("generic-restore-favorite", "Restored locally", "Fixture artist", "wy")
        BackupStore.restore(
            context,
            JSONObject()
                .put("schema", "soundwave-backup")
                .put("version", 1)
                .put("user", JSONObject().put("favs", JSONArray().put(restoredSong.toJson())))
        )

        assertEquals(listOf(restoredSong.id), LocalStore.load(context).favs.map { it.id })
        assertEquals(
            LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN,
            LegacyUnassignedSnapshot.accountSyncGateState(context)
        )
        assertEquals("the captured raw snapshot remains immutable", immutableSnapshot,
            snapshotPreferences.getString(LegacyUnassignedSnapshot.SNAPSHOT_KEY, null))
        assertTrue(
            context.getSharedPreferences(LegacyUnassignedSnapshot.SOURCE_PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(LegacyUnassignedSnapshot.RESTORE_INVALIDATION_KEY, false)
        )

        val credentialsRead = AtomicInteger()
        val requests = mutableListOf<WebDavHttpRequest>()
        val client = WebDavSyncClient(WebDavTransport { request ->
            requests += request
            WebDavHttpResponse(500)
        })
        val result = client.syncWithCredentials(
            local = LocalStore.load(context),
            credentialProvider = {
                credentialsRead.incrementAndGet()
                WebDavSavedCredentials("https://example.invalid/dav", "fixture-user", "fixture-pass")
            },
            gateStateProvider = { LegacyUnassignedSnapshot.accountSyncGateState(context) }
        )
        assertTrue(result is WebDavSyncResult.Failure)
        assertEquals(WEB_DAV_LEGACY_DATA_BLOCKED_MESSAGE, (result as WebDavSyncResult.Failure).message)
        assertEquals("the gate must reject before reading saved WebDAV credentials", 0, credentialsRead.get())
        assertTrue("the gate must reject before any WebDAV transport request", requests.isEmpty())

        assertEquals(LegacyUnassignedSnapshot.Result.ALREADY_COMPLETE, LegacyUnassignedSnapshot.capture(context))
        assertEquals(
            LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN,
            LegacyUnassignedSnapshot.accountSyncGateState(context)
        )
        assertEquals(listOf(restoredSong.id), LocalStore.load(context).favs.map { it.id })
    }

    @Test
    fun partialGenericRestoreAfterGlobalWriteKeepsDataAndFailsClosed() {
        prepareVerifiedEmptySnapshot()
        context.getSharedPreferences("soundwave", Context.MODE_PRIVATE).edit()
            .putString("theme_mode", "light").commit()
        val restoredSong = Song("partial-restore-favorite", "Partial restore", "Fixture artist", "wy")
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val preferences = super.getSharedPreferences(name, mode)
                if (name != "soundwave_scripts") return preferences
                // Preflight reads must succeed. Fail only when the merged script configuration
                // is written, after the global bundle, provenance and allowed settings persist.
                return object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor =
                        throw IllegalStateException("fixture failure after global writes")
                }
            }
        }
        val failure = runCatching {
            BackupStore.restore(
                failingContext,
                JSONObject()
                    .put("schema", "soundwave-backup")
                    .put("version", 1)
                    .put("user", JSONObject().put("favs", JSONArray().put(restoredSong.toJson())))
                    .put("settings", JSONObject().put("theme_mode", "dark"))
                    .put("scripts", JSONObject().put("script_meta", "[{\"id\":\"fixture\",\"enabled\":true}]"))
            )
        }.exceptionOrNull()

        assertTrue("the fixture must fail after the first legacy write", failure != null)
        assertEquals("fixture failure after global writes", failure?.message)
        assertEquals(listOf(restoredSong.id), LocalStore.load(context).favs.map { it.id })
        assertEquals("dark", context.getSharedPreferences("soundwave", Context.MODE_PRIVATE)
            .getString("theme_mode", null))
        assertTrue(context.getSharedPreferences(LegacyUnassignedSnapshot.SOURCE_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(LegacyUnassignedSnapshot.RESTORE_INVALIDATION_KEY, false))
        assertEquals(
            LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN,
            LegacyUnassignedSnapshot.accountSyncGateState(context)
        )
    }

    @Test
    fun downloadMetadataFieldsRoundTripThroughBackup() {
        val item = DownloadItem(
            song = Song(id = "metadata-download", name = "元数据下载", singer = "歌手", source = "wy"),
            path = "file:///download/metadata.mp3",
            size = 123L,
            time = 456L,
            quality = "flac",
            platform = "kg",
            scriptId = "local-script-id",
            resolveFailure = "resolution failed",
            taskId = "download-task-id"
        )
        LocalStore.addDownload(context, item)

        val backup = BackupStore.export(context)
        val exported = backup.getJSONArray("downloads").getJSONObject(0)
        assertEquals("flac", exported.getString("quality"))
        assertEquals("kg", exported.getString("platform"))
        assertEquals("local-script-id", exported.getString("scriptId"))
        assertEquals("resolution failed", exported.getString("resolveFailure"))
        assertEquals("download-task-id", exported.getString("taskId"))

        LocalStore.clearDownloads(context)
        BackupStore.restore(context, backup)

        val restored = LocalStore.loadDownloads(context).single()
        assertEquals("flac", restored.quality)
        assertEquals("kg", restored.platform)
        assertEquals("local-script-id", restored.scriptId)
        assertEquals("resolution failed", restored.resolveFailure)
        assertEquals("download-task-id", restored.taskId)
    }

    @Test
    fun ordinaryPreferencesStillRoundTripThroughBackup() {
        val prefs = context.getSharedPreferences("soundwave", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("theme_mode", "dark")
            .putBoolean("download_mt", true)
            .putInt("prefetch_interval_sec", 7)
            .commit()

        val backup = BackupStore.export(context)
        prefs.edit()
            .putString("theme_mode", "light")
            .putBoolean("download_mt", false)
            .putInt("prefetch_interval_sec", 0)
            .commit()

        BackupStore.restore(context, backup)

        assertEquals("dark", prefs.getString("theme_mode", null))
        assertTrue(prefs.getBoolean("download_mt", false))
        assertEquals(7, prefs.getInt("prefetch_interval_sec", 0))
    }

    @Test
    fun restorePersistsAvatarAndLeavesItUnchangedWhenMissingOrMalformed() {
        context.getSharedPreferences("soundwave", Context.MODE_PRIVATE).edit()
            .putString("user", avatarUser)
            .commit()
        val existingPath = AvatarStore.saveFromB64(context, avatarUser, avatarDataUrl(Color.BLUE))
        assertTrue("test setup must create an existing avatar", existingPath.isNotBlank())
        val existingBytes = File(existingPath).readBytes()

        BackupStore.restore(context, backupWithAvatar(avatarDataUrl(Color.GREEN)))

        val restoredPath = AvatarStore.path(context, avatarUser)
        assertTrue("valid backup avatar must publish a usable path", restoredPath.isNotBlank())
        assertNotEquals("avatar replacement publishes an immutable new version", existingPath, restoredPath)
        assertTrue("the previous avatar version must remain unchanged", existingBytes.contentEquals(File(existingPath).readBytes()))
        val restoredFile = File(restoredPath)
        val restoredBytes = restoredFile.readBytes()
        assertFalse("valid backup avatar must replace the independent stored avatar", existingBytes.contentEquals(restoredBytes))
        val restoredBitmap = BitmapFactory.decodeFile(restoredPath)
        assertTrue("restored avatar must remain decodable", restoredBitmap != null)
        restoredBitmap?.recycle()

        BackupStore.restore(context, backupWithAvatar(null))
        assertEquals(restoredPath, AvatarStore.path(context, avatarUser))
        assertTrue("backup without an avatar must not overwrite the existing file", restoredBytes.contentEquals(restoredFile.readBytes()))

        BackupStore.restore(context, backupWithAvatar("not-base64"))
        assertEquals(restoredPath, AvatarStore.path(context, avatarUser))
        assertTrue("malformed avatar data must not overwrite the existing file", restoredBytes.contentEquals(restoredFile.readBytes()))

        var rejected = false
        try {
            BackupStore.restore(context, JSONObject().put("schema", "soundwave-backup").put("version", 1))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("malformed backup structure must be rejected", rejected)
        assertTrue("rejected backup must not change the existing avatar", restoredBytes.contentEquals(restoredFile.readBytes()))
    }

    private fun prepareVerifiedEmptySnapshot() {
        context.getSharedPreferences(LegacyUnassignedSnapshot.SOURCE_PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(LegacyUnassignedSnapshot.SNAPSHOT_PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
        assertEquals(LegacyUnassignedSnapshot.Result.COPIED, LegacyUnassignedSnapshot.capture(context))
        assertEquals(
            LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN,
            LegacyUnassignedSnapshot.accountSyncGateState(context)
        )
    }

    private fun backupWithAvatar(avatarB64: String?): JSONObject {
        val user = JSONObject()
        if (avatarB64 != null) user.put("avatar", avatarB64)
        return JSONObject()
            .put("schema", "soundwave-backup")
            .put("version", 1)
            .put("user", user)
    }

    private fun avatarDataUrl(color: Int): String {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try {
            val bytes = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            "data:image/png;base64," + android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    private fun backupWithMutationPayload(): JSONObject = JSONObject()
        .put("schema", "soundwave-backup")
        .put("version", 1)
        .put("history", JSONArray())
        .put("downloads", JSONArray())
        .put("settings", JSONObject().put("backup_test_setting", "incoming-setting"))
        .put("scripts", JSONObject().put("backup_test_script", "incoming-script"))

    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
}
