package xyz.cncnlp.soundwave

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException

/** Real BackupStore + SharedPreferences/files, not a transaction model. No external network/account. */
@RunWith(RobolectricTestRunner::class)
class BackupRestoreRecoveryTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private fun song(id: String) = Song(id, id, "fixture", "wy", playedAt = System.currentTimeMillis())
    private fun input(): JSONObject = JSONObject().put("schema", "soundwave-backup").put("version", 1)
        .put("user", JSONObject().put("favs", JSONArray().put(song("new").toJson())))
        .put("history", JSONArray().put(song("new-history").toJson()))
        .put("downloads", JSONArray().put(JSONObject().put("song", song("new-download").toJson()).put("path", "/fixture/new-download.mp3")))
        .put("settings", JSONObject().put("theme_mode", "dark").put("pass", "INPUT-SECRET").put("token", "INPUT-TOKEN"))
        .put("scripts", JSONObject().put("script_meta", "[{\"id\":\"known\",\"enabled\":false,\"code\":\"INPUT-SCRIPT-SECRET\"}]"))
        .put("unaccepted", "INPUT-UNACCEPTED-SECRET")
    private fun files() = File(ctx.filesDir, "backup_restore_recovery").listFiles().orEmpty().toList()
    private fun failAtScripts(error: RuntimeException = IllegalStateException("fixture late write")): Context = object : ContextWrapper(ctx) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val p = super.getSharedPreferences(name, mode)
            return if (name != "soundwave_scripts") p else object : SharedPreferences by p {
                override fun edit(): SharedPreferences.Editor = throw error
            }
        }
    }
    private fun fail() { assertTrue(runCatching { BackupStore.restore(failAtScripts(), input()) }.isFailure) }
    private fun blocked() {
        assertTrue(BackupRestoreRecovery.hasPending(ctx))
        assertEquals(LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN, LegacyUnassignedSnapshot.explicitManualSyncGateState(ctx))
    }
    @Before fun prepare() {
        LocalStore.flushPendingWrites()
        listOf("soundwave", "soundwave_userdata", "soundwave_scripts", "listen_stats_v1", "avatar_map", "soundwave_legacy_local", "backup_restore_recovery_v1")
            .forEach { ctx.getSharedPreferences(it, 0).edit().clear().commit() }
        File(ctx.filesDir, "backup_restore_recovery").deleteRecursively()
        ctx.getSharedPreferences("soundwave", 0).edit().putString("user", "fixture-owner").putBoolean("logged_in", true)
            .putString("pass", "LOCAL-PASSWORD-SECRET").putString("token", "LOCAL-TOKEN-SECRET").putString("theme_mode", "light").commit()
        ctx.getSharedPreferences("soundwave_scripts", 0).edit().putString("script_meta", "[{\"id\":\"known\",\"enabled\":true,\"code\":\"LOCAL-SCRIPT-SECRET\"}]").commit()
        LocalStore.save(ctx, LocalStore.UserBundle(favs = mutableListOf(song("old"))))
        LegacyUnassignedSnapshot.capture(ctx)
    }
    @Test fun lateFailurePreservesPrivateProjectedInputAndBeforeCopy() {
        fail()
        val record = files().single()
        val raw = record.readText()
        assertTrue(raw.contains("old")); assertTrue(raw.contains("new"))
        listOf("INPUT-SECRET", "INPUT-TOKEN", "INPUT-SCRIPT-SECRET", "INPUT-UNACCEPTED-SECRET", "LOCAL-PASSWORD-SECRET", "LOCAL-TOKEN-SECRET", "LOCAL-SCRIPT-SECRET")
            .forEach { assertFalse("must exclude $it", raw.contains(it)) }
        assertEquals("LOCAL-PASSWORD-SECRET", ctx.getSharedPreferences("soundwave", 0).getString("pass", null))
        assertEquals(listOf("new"), LocalStore.load(ctx).favs.map { it.id })
        blocked()
    }
    @Test fun persistedRecordCanBeReadThroughFreshContextAndConfirmedReplayDoesNotDuplicate() {
        fail()
        val id = files().single().name
        val reentered = ContextWrapper(ctx) // no in-memory recovery state is required
        assertTrue(BackupRestoreRecovery.status(reentered).pending)
        val ticket = BackupRestoreRecovery.review(reentered)
        BackupStore.resume(reentered, ticket)
        assertFalse(BackupRestoreRecovery.hasPending(ctx))
        assertEquals(listOf("new-history"), LocalStore.loadHistory(ctx).map { it.id })
        assertEquals(listOf("new-download"), LocalStore.loadDownloads(ctx).map { it.song.id })
        assertEquals(id, files().single().name) // before image/input are retained after success
        assertEquals(LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN, LegacyUnassignedSnapshot.accountSyncGateState(ctx))
    }
    @Test fun mutationAfterReviewRejectsReplayWithoutOverwritingNewFavorite() {
        fail(); val ticket = BackupRestoreRecovery.review(ctx)
        LocalStore.save(ctx, LocalStore.UserBundle(favs = mutableListOf(song("later-edit"))))
        assertTrue(runCatching { BackupStore.resume(ctx, ticket) }.isFailure)
        assertEquals(listOf("later-edit"), LocalStore.load(ctx).favs.map { it.id }); blocked()
    }
    @Test fun historyChangeBeforeReviewIsNotSilentlyAcceptedAsNewCheckpoint() {
        fail(); LocalStore.replaceHistory(ctx, listOf(song("after-interruption")))
        assertTrue(runCatching { BackupRestoreRecovery.review(ctx) }.isFailure)
        assertEquals(listOf("after-interruption"), LocalStore.loadHistory(ctx).map { it.id }); blocked()
    }
    @Test fun identityChangeAfterConfirmationRetainsRecordAndGate() {
        fail(); val ticket = BackupRestoreRecovery.review(ctx); val original = files().single().readBytes()
        ctx.getSharedPreferences("soundwave", 0).edit().putString("user", "different-owner").commit()
        assertTrue(runCatching { BackupStore.resume(ctx, ticket) }.isFailure)
        assertArrayEquals(original, files().single().readBytes()); blocked()
    }
    @Test fun corruptRecordNeverClearsGateOrStartsAReplacementImport() {
        fail(); files().single().writeText("corrupted fixture")
        assertTrue(BackupRestoreRecovery.status(ctx).message.contains("损坏"))
        assertTrue(runCatching { BackupRestoreRecovery.review(ctx) }.isFailure)
        assertTrue(runCatching { BackupStore.restore(ctx, input()) }.isFailure)
        assertEquals("corrupted fixture", files().single().readText()); blocked()
    }
    @Test fun simulatedAbruptExitWithoutCatchRefusesUnknownPartialStateAfterReentry() {
        BackupRestoreRecovery.begin(ctx, input().also { it.remove("unaccepted") })
        LocalStore.save(ctx, LocalStore.UserBundle(favs = mutableListOf(song("unknown-stage"))))
        // No interrupted() checkpoint: simulate on-disk prepared record from a stopped process.
        BackupRestoreRecovery.enforcePendingGate(ContextWrapper(ctx))
        assertTrue(runCatching { BackupRestoreRecovery.review(ContextWrapper(ctx)) }.isFailure)
        assertEquals(listOf("unknown-stage"), LocalStore.load(ctx).favs.map { it.id }); blocked()
    }
    @Test fun preparedRecordBeforeAnyMutationAllowsExplicitReplayButNotAutomaticReplay() {
        BackupRestoreRecovery.begin(ctx, input().also { it.remove("unaccepted") })
        BackupRestoreRecovery.enforcePendingGate(ContextWrapper(ctx))
        assertEquals(listOf("old"), LocalStore.load(ctx).favs.map { it.id })
        assertNotNull(BackupRestoreRecovery.review(ContextWrapper(ctx))); blocked()
    }
    @Test fun repeatedFailureKeepsOriginalBeforeImageAndInputAndSameCancellation() {
        fail(); val raw = files().single().readBytes(); val ticket = BackupRestoreRecovery.review(ctx)
        val cancelled = CancellationException("fixture replay cancelled")
        assertSame(cancelled, runCatching { BackupStore.resume(failAtScripts(cancelled), ticket) }.exceptionOrNull())
        assertArrayEquals(raw, files().single().readBytes()); blocked()
    }
    @Test fun failedRecordWritePreventsDataMutation() {
        File(ctx.filesDir, "backup_restore_recovery").writeText("not a directory")
        assertTrue(runCatching { BackupStore.restore(ctx, input()) }.isFailure)
        assertEquals(listOf("old"), LocalStore.load(ctx).favs.map { it.id })
        assertEquals("light", ctx.getSharedPreferences("soundwave", 0).getString("theme_mode", null))
    }
    @Test fun avatarPublicationFailureIsNotReportedAsCompletedRestore() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        bitmap.recycle()
        val root = input(); root.getJSONObject("user").put("avatar", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val context = object : ContextWrapper(ctx) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val p = super.getSharedPreferences(name, mode)
                return if (name != "avatar_map") p else object : SharedPreferences by p {
                    override fun edit(): SharedPreferences.Editor = throw IllegalStateException("fixture avatar pointer write")
                }
            }
        }
        assertTrue(runCatching { BackupStore.restore(context, root) }.isFailure)
        assertTrue(BackupRestoreRecovery.status(ctx).pending); blocked()
    }
    @Test fun changeBetweenPreparationAndFirstWriteIsRejectedWithoutAcceptingNewCheckpoint() {
        val context = object : ContextWrapper(ctx) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val p = super.getSharedPreferences(name, mode)
                if (name != "backup_restore_recovery_v1") return p
                return object : SharedPreferences by p {
                    override fun edit(): SharedPreferences.Editor {
                        val edit = p.edit()
                        return object : SharedPreferences.Editor by edit {
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                                edit.putString(key, value); return this
                            }
                            override fun commit(): Boolean {
                                val success = edit.commit()
                                LocalStore.save(ctx, LocalStore.UserBundle(favs = mutableListOf(song("concurrent-new"))))
                                return success
                            }
                        }
                    }
                }
            }
        }
        assertTrue(runCatching { BackupStore.restore(context, input()) }.isFailure)
        assertEquals(listOf("concurrent-new"), LocalStore.load(ctx).favs.map { it.id })
        assertTrue(runCatching { BackupRestoreRecovery.review(ctx) }.isFailure)
        blocked()
    }
    @Test fun mutationAtFirstStagePublicationRejectsBeforeBusinessWritesAndKeepsOldCheckpoint() {
        val context = object : ContextWrapper(ctx) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val p = super.getSharedPreferences(name, mode)
                if (name != "backup_restore_recovery_v1") return p
                return object : SharedPreferences by p {
                    override fun edit(): SharedPreferences.Editor {
                        val edit = p.edit()
                        var userStage = false
                        return object : SharedPreferences.Editor by edit {
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                                edit.putString(key, value)
                                if (key == "stage" && value == "user") userStage = true
                                return this
                            }
                            override fun commit(): Boolean {
                                val success = edit.commit()
                                if (userStage) LocalStore.save(ctx, LocalStore.UserBundle(favs = mutableListOf(song("stage-concurrent"))))
                                return success
                            }
                        }
                    }
                }
            }
        }
        assertTrue(runCatching { BackupStore.restore(context, input()) }.isFailure)
        assertEquals(listOf("stage-concurrent"), LocalStore.load(ctx).favs.map { it.id })
        assertTrue(runCatching { BackupRestoreRecovery.review(ctx) }.isFailure)
        blocked()
    }
    @Test fun normalSuccessPreservesBeforeCopyAndNewInputDoesNotOverwriteIt() {
        BackupStore.restore(ctx, input()); val first = files().single(); val bytes = first.readBytes()
        BackupStore.restore(ctx, input())
        assertEquals(2, files().size); assertArrayEquals(bytes, first.readBytes())
        assertFalse(BackupRestoreRecovery.status(ctx).pending)
    }
}
