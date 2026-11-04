package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * accept6-backnav（用户 2026-11-04 23:17）：设置导航栈回归——取代 r655 扁平化时代
 * settingsBackTarget 恒回根的旧钉（AppRouteSettingsBackTest）。深度-1 子页返回根
 * （行为不变）；深度-2 子页链返回直接父页；多父级页的父级由进入路径决定。
 */
class SettingsNavStackTest {

    /** 设置全部子页（MePage 渲染 6 + SettingsHub 全屏页 11），均有设置根入口。 */
    private val allSubs = listOf(
        "preload", "lyrics", "theme", "dock", "search", "download",
        "smart", "scripts", "cache", "crash", "stats", "history",
        "backup", "downloads", "about", "data_flow", "notifications"
    )

    @Test
    fun everyDepthOneSubBacksToRoot() {
        for (sub in allSubs) {
            val stack = SettingsNavStack.pushed(emptyList(), sub)
            assertEquals("push $sub", listOf(sub), stack)
            assertEquals("current $sub", sub, SettingsNavStack.current(stack))
            assertNull("backTarget $sub", SettingsNavStack.backTarget(stack))
            assertEquals("pop $sub", emptyList<String>(), SettingsNavStack.popped(stack))
        }
    }

    @Test
    fun statsToHistoryBacksToStats() {
        val stack = SettingsNavStack.pushed(SettingsNavStack.pushed(emptyList(), "stats"), "history")
        assertEquals(listOf("stats", "history"), stack)
        assertEquals("history", SettingsNavStack.current(stack))
        assertEquals("stats", SettingsNavStack.backTarget(stack))
        val once = SettingsNavStack.popped(stack)
        assertEquals(listOf("stats"), once)
        assertNull(SettingsNavStack.backTarget(once))
    }

    @Test
    fun aboutToDataFlowBacksToAbout() {
        val stack = SettingsNavStack.pushed(SettingsNavStack.pushed(emptyList(), "about"), "data_flow")
        assertEquals("about", SettingsNavStack.backTarget(stack))
    }

    @Test
    fun notificationsToScriptsBacksToNotifications() {
        val stack = SettingsNavStack.pushed(SettingsNavStack.pushed(emptyList(), "notifications"), "scripts")
        assertEquals("notifications", SettingsNavStack.backTarget(stack))
    }

    /** 多父级页：同一页从根进入回根、从父页进入回父页——静态父映射不可行的三个实例。 */
    @Test
    fun multiParentPagesFollowEntryPath() {
        assertNull(SettingsNavStack.backTarget(SettingsNavStack.pushed(emptyList(), "history")))
        assertEquals("stats", SettingsNavStack.backTarget(listOf("stats", "history")))
        assertNull(SettingsNavStack.backTarget(SettingsNavStack.pushed(emptyList(), "scripts")))
        assertEquals("notifications", SettingsNavStack.backTarget(listOf("notifications", "scripts")))
        assertNull(SettingsNavStack.backTarget(SettingsNavStack.pushed(emptyList(), "data_flow")))
        assertEquals("about", SettingsNavStack.backTarget(listOf("about", "data_flow")))
    }

    @Test
    fun pushCurrentTopIsNoOpAndPushAncestorTruncates() {
        assertEquals(listOf("stats", "history"), SettingsNavStack.pushed(listOf("stats", "history"), "history"))
        assertEquals(listOf("stats"), SettingsNavStack.pushed(listOf("stats", "history"), "stats"))
        assertEquals(emptyList<String>(), SettingsNavStack.popped(emptyList()))
        assertNull(SettingsNavStack.backTarget(emptyList()))
        assertNull(SettingsNavStack.current(emptyList()))
    }

    @Test
    fun depthCountsStackPosition() {
        assertEquals(0, SettingsNavStack.depth(emptyList(), null))
        assertEquals(1, SettingsNavStack.depth(listOf("stats"), "stats"))
        assertEquals(2, SettingsNavStack.depth(listOf("stats", "history"), "history"))
        // 已弹栈的离场页深度 = 当前栈深 + 1（转场方向/压暗判定用）。
        assertEquals(2, SettingsNavStack.depth(listOf("stats"), "history"))
    }

    @Test
    fun isForwardFollowsStackSemantics() {
        // 推入：根 → 子页、子页 → 孙页。
        assertTrue(SettingsNavStack.isForward(listOf("stats"), null, "stats"))
        assertTrue(SettingsNavStack.isForward(listOf("stats", "history"), "stats", "history"))
        // 弹栈：孙页 → 子页、子页 → 根（from 已不在栈中）。
        assertFalse(SettingsNavStack.isForward(listOf("stats"), "history", "stats"))
        assertFalse(SettingsNavStack.isForward(emptyList(), "stats", null))
        // 无变化不算前进。
        assertFalse(SettingsNavStack.isForward(listOf("stats"), "stats", "stats"))
    }
}
