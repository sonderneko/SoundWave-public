package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WebDavAccountAvatarApplyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val saved = mutableMapOf<SharedPreferences, Map<String, *>>()
    private val accountPrefs get() = context.getSharedPreferences("webdav-avatar-apply-test", 0)

    private val authPrefs get() = context.getSharedPreferences("soundwave", Context.MODE_PRIVATE)

    @Before fun isolate() {
        listOf(LocalStore.prefs(context), accountPrefs, authPrefs,
            context.getSharedPreferences("avatar_map", 0)).forEach {
            saved[it] = it.all.toMap()
            it.edit().clear().commit()
        }
    }

    @After fun restore() {
        saved.forEach { (prefs, values) ->
            val editor = prefs.edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
        }
    }

    private fun TestScope.controller(): CloudSyncController = CloudSyncController(
        context, this, accountPrefs, { emptyList() },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        mainDispatcher = StandardTestDispatcher(testScheduler),
    )

    /**
     * 账号登录已删除：等价事件 = 本地资料建立/改名（首次命名或 completeSuccessfulLocalRename）。
     * 生产接线中控制器 prefs 即 "soundwave" 本体，ListenStats 的作用域检查读同一文件；
     * 本测试控制器用隔离 prefs 文件，故把当前资料名镜像过去，使改名检查与生产一致。
     */
    private fun login(controller: CloudSyncController, owner: String) {
        val old = controller.user
        if (old.isNotBlank()) authPrefs.edit().putString("user", old).commit()
        else authPrefs.edit().remove("user").commit()
        assertTrue(controller.completeSuccessfulLocalRename(old, owner, controller.verifiedAvatarPathForLocalRename(old)))
        authPrefs.edit().putString("user", owner).commit()
    }

    private fun image(owner: String, color: Int): String {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try { AvatarStore.saveFromBitmap(context, bitmap, owner) } finally { bitmap.recycle() }
    }

    @Test fun unverifiedStoredIdentityCannotStartAvatarSync() = runTest {
        val controller = controller()
        assertNull(controller.prepareWebDavAvatarSync())
        controller.user = "unknown-${UUID.randomUUID()}"
        controller.userBundle = LocalStore.UserBundle(avatarB64 = "unverified-generic-avatar")
        assertNull(controller.prepareWebDavAvatarSync())
    }

    @Test fun establishedProfileWithoutLocalAvatarCanRestorePortableAvatarLocally() = runTest {
        val controller = controller()
        val owner = "restore-${UUID.randomUUID()}"
        login(controller, owner)
        val session = requireNotNull(controller.prepareWebDavAvatarSync())
        assertEquals("", session.avatarB64)
        val source = image("fixture-${UUID.randomUUID()}", Color.BLUE)
        val portable = AvatarStore.toB64(source)
        val sourceBytes = java.io.File(source).readBytes()
        session.apply(portable)
        assertTrue(AvatarStore.isAccountAvatarPath(context, owner, controller.avatarPath))
        assertTrue(controller.userBundle.avatarB64.startsWith("data:image/jpeg;base64,"))
        assertArrayEquals(sourceBytes, java.io.File(source).readBytes())
        assertEquals(controller.userBundle.avatarB64, LocalStore.load(context).avatarB64)
        assertTrue(requireNotNull(controller.prepareWebDavAvatarSync()).avatarB64.isNotBlank())
    }

    @Test fun explicitVerifiedAvatarExportsButGenericBundleAndFallbackPathDoNot() = runTest {
        val controller = controller()
        val owner = "export-${UUID.randomUUID()}"
        login(controller, owner)
        val path = image(owner, Color.RED)
        controller.avatarPath = path
        controller.userBundle = controller.userBundle.copy(avatarB64 = "unverified-bundle")
        assertEquals("", requireNotNull(controller.prepareWebDavAvatarSync()).avatarB64)
        assertTrue(controller.acceptExplicitLocalAvatar(owner, path))
        assertEquals(AvatarStore.toB64(path), requireNotNull(controller.prepareWebDavAvatarSync()).avatarB64)
        assertEquals(path, controller.avatarPath)
    }

    @Test fun switchingAccountWhileSyncIsInFlightCannotRestoreOldAvatar() = runTest {
        val controller = controller()
        login(controller, "old-${UUID.randomUUID()}")
        val session = requireNotNull(controller.prepareWebDavAvatarSync())
        login(controller, "new-${UUID.randomUUID()}")
        val before = controller.avatarPath
        assertFalse(session.isCurrent())
        session.apply(AvatarStore.toB64(image("fixture-${UUID.randomUUID()}", Color.GREEN)))
        assertEquals(before, controller.avatarPath)
    }

    @Test fun logoutWhileSyncIsInFlightDisablesImportAndExport() = runTest {
        val controller = controller()
        login(controller, "logout-${UUID.randomUUID()}")
        val session = requireNotNull(controller.prepareWebDavAvatarSync())
        controller.user = ""
        assertFalse(session.isCurrent())
        assertNull(controller.prepareWebDavAvatarSync())
        session.apply(AvatarStore.toB64(image("fixture-${UUID.randomUUID()}", Color.BLUE)))
        assertEquals("", controller.avatarPath)
    }

    @Test fun newerExplicitAvatarSelectionWinsOverPendingRemoteRestore() = runTest {
        val controller = controller()
        val owner = "mutation-${UUID.randomUUID()}"
        login(controller, owner)
        val session = requireNotNull(controller.prepareWebDavAvatarSync())
        val selected = image(owner, Color.RED)
        assertTrue(controller.acceptExplicitLocalAvatar(owner, selected))
        assertFalse(session.isCurrent())
        session.apply(AvatarStore.toB64(image("fixture-${UUID.randomUUID()}", Color.BLUE)))
        assertEquals(selected, controller.avatarPath)
    }

    @Test fun identityChangeRevokesInFlightSession() = runTest {
        val controller = controller()
        login(controller, "blocked-${UUID.randomUUID()}")
        val session = requireNotNull(controller.prepareWebDavAvatarSync())
        controller.invalidatePendingLocalIdentityChange()
        assertFalse(session.isCurrent())
    }

    @Test fun identityChangeRevokesSessionAndBlocksReissueUntilReestablished() = runTest {
        val controller = controller()
        val owner = "retired-${UUID.randomUUID()}"
        login(controller, owner)
        val session = requireNotNull(controller.prepareWebDavAvatarSync())
        controller.invalidatePendingLocalIdentityChange()
        assertFalse(session.isCurrent())
        assertNull(controller.prepareWebDavAvatarSync())
    }

    @Test fun dataOnlyApplyNeverPromotesUnboundAvatarOrChangesLocalPath() = runTest {
        val controller = controller()
        controller.userBundle = LocalStore.UserBundle(avatarB64 = "local-only-data")
        val localPath = image("fixture-${UUID.randomUUID()}", Color.RED)
        controller.avatarPath = localPath
        controller.applyWebDavBundleLocally(LocalStore.UserBundle(avatarB64 = "unbound-remote-avatar"))
        assertEquals(localPath, controller.avatarPath)
        assertEquals("local-only-data", controller.userBundle.avatarB64)
    }

    @Test fun establishedProfileWithoutAnyDataStillBindsAvatarImport() = runTest {
        val controller = controller()
        val owner = "no-cloud-${UUID.randomUUID()}"
        login(controller, owner)
        assertNotNull(controller.prepareWebDavAvatarSync())
    }
}
