package xyz.cncnlp.soundwave

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.ui.common.AppOverlayHostLayer
import xyz.cncnlp.soundwave.ui.common.DesignConfirmSheet
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetRow
import xyz.cncnlp.soundwave.ui.common.ProvideAppOverlayHost
import xyz.cncnlp.soundwave.ui.notice.AppNotice
import xyz.cncnlp.soundwave.ui.notice.AppNoticeHost
import xyz.cncnlp.soundwave.ui.settings.SettingsPickerSheet

/**
 * 验收 A 波探针（渲染与页内交互）+ 任务 2 源码契约。
 *
 * 环境限制（已用诊断用例取证，见 WAVE-A.md）：本仓 Robolectric 环境下，凡经
 * AppOverlayDialog 覆盖层条目或平台 Dialog 窗口呈现的内容，语义点击/触摸注入均无法
 * 触发回调（普通 Button 放在覆盖层条目内、乃至平台 Dialog 回退中的 DesignSheetRow
 * 都同样超时；同一运行里根内容与普通页面内的点击全部正常）。这是跨组合根输入分发
 * 的测试环境限制，不是产品行为。因此弹层类探针只断言渲染可见性与尺寸（任务 1 的
 * 核心问题），点击链路由代码审计 + 守卫 + 真机截图佐证；任务 2 的修复另以源码契约
 * 测试钉住（本仓既有风格，如 AppDialogsThemeGuardTest）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w420dp-h900dp-xhdpi")
class WaveAProbeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** 在页面第一个可滚动节点上滑动，直到目标节点出现。 */
    private fun scrollPageUntil(matcher: androidx.compose.ui.test.SemanticsMatcher, tries: Int = 8): Boolean {
        repeat(tries) {
            if (composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) return true
            val scrollables = composeRule.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
            if (scrollables.isEmpty()) return false
            composeRule.onAllNodes(hasScrollAction())[0].performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        return composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun designSheetDialog_overlayHost_rendersContentWithNonZeroSize() {
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ProvideAppOverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        DesignSheetDialog(onDismiss = {}) {
                            DesignSheetRow(Icons.Outlined.Delete, "探针行甲", "说明") {}
                        }
                        AppOverlayHostLayer()
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val node = composeRule.onNodeWithText("探针行甲")
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInWindow
        assertTrue("行应有非零高度，实际 $bounds", bounds.height > 0f)
        assertTrue("行应有非零宽度，实际 $bounds", bounds.width > 0f)
    }

    @Test
    fun designConfirmSheet_rendersTitleAndActions() {
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ProvideAppOverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        DesignConfirmSheet(
                            title = "删除这首歌？", body = "删除后无法恢复。", confirmLabel = "删除",
                            onConfirm = {}, onDismiss = {}
                        )
                        AppOverlayHostLayer()
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除这首歌？").assertIsDisplayed()
        composeRule.onNodeWithText("删除后无法恢复。").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("删除").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("取消").assertIsDisplayed()
    }

    @Test
    fun settingsPickerSheet_rendersOptions() {
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ProvideAppOverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        SettingsPickerSheet(
                            kicker = "播放", title = "播放音质", copy = "说明",
                            options = listOf("标准", "高品"), selectedIndex = 0,
                            onPick = {}, onDismiss = {}
                        )
                        AppOverlayHostLayer()
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("标准").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("高品").assertIsDisplayed()
    }

    @Test
    fun playbackTransitionSheet_rendersAllOptions() {
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ProvideAppOverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        PlaybackTransitionSettingsSheet(onDismiss = {})
                        AppOverlayHostLayer()
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("淡入淡出").assertIsDisplayed()
        composeRule.onNodeWithText("关闭").assertIsDisplayed()
        composeRule.onNodeWithText("3 秒").assertIsDisplayed()
        composeRule.onNodeWithText("智能过渡").assertIsDisplayed()
    }

    /** 任务 2 源码契约：过渡选项行写入后必须调用 onDismiss（选中即关），且写在行处理内。 */
    @Test
    fun playbackTransitionSheet_pickDismisses_sourceContract() {
        val path = "src/main/java/xyz/cncnlp/soundwave/PlaybackTransitionSettingsSheet.kt"
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.exists() }
            ?: error("找不到源文件: $path")
        val source = file.readText()
        val writeIndex = source.indexOf("PlaybackTransitionSettingsStore.write(prefs, option)")
        assertTrue("选项行应写入所选时长", writeIndex >= 0)
        // ACCEPT4 重钉（等同或更高严格度）：关闭改走 J8 退场句柄 dismiss()（退场后才清状态），
        // 且写入后不许再直调 onDismiss() 同步卸载——比旧钉（只要求 onDismiss() 出现）更严。
        assertTrue("选项行应经 rememberDesignSheetDismiss 取得退场句柄", source.contains("rememberDesignSheetDismiss(onDismiss)"))
        val dismissIndex = source.indexOf("dismiss()", writeIndex)
        assertTrue("选项行写入后必须调用退场句柄 dismiss() 关闭弹层", dismissIndex > writeIndex)
        assertTrue("选项行写入后禁止直调 onDismiss() 同步卸载", source.indexOf("onDismiss()", writeIndex) < 0)
    }

    @Test
    fun createPlaylistDialog_renders() {
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ProvideAppOverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        xyz.cncnlp.soundwave.ui.common.CreatePlaylistDialog(
                            name = "我的新歌单", onNameChange = {},
                            onConfirm = {}, onDismiss = {}, onCancel = {}
                        )
                        AppOverlayHostLayer()
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("新建歌单").assertIsDisplayed()
        composeRule.onNodeWithText("创建").assertIsDisplayed()
    }

    @Test
    fun appNoticeUndo_capsuleShowsUndoActionAndTriggersOnce() {
        composeRule.mainClock.autoAdvance = false
        var undoCount = 0
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) { AppNoticeHost() }
        }
        composeRule.runOnIdle { AppNotice.undo("已从歌单移除 1 首") { undoCount++ } }
        composeRule.mainClock.advanceTimeBy(700)
        composeRule.onNodeWithText("已从歌单移除 1 首").assertIsDisplayed()
        val undoNode = composeRule.onNodeWithText("撤销")
        undoNode.assertIsDisplayed()
        val b = undoNode.fetchSemanticsNode().boundsInWindow
        assertTrue("撤销热区过小: $b", b.width >= 24f && b.height >= 16f)
        undoNode.performClick()
        assertEquals("撤销回调应恰好触发一次", 1, undoCount)
        AppNotice.dismissCapsule()
    }

    @Test
    fun appNoticePlainToast_showsMessage() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) { AppNoticeHost() }
        }
        composeRule.runOnIdle { AppNotice.toast("已加入播放队列", AppNotice.Kind.SUCCESS) }
        composeRule.mainClock.advanceTimeBy(700)
        composeRule.onNodeWithText("已加入播放队列").assertIsDisplayed()
        AppNotice.dismissCapsule()
    }

    @Test
    fun scriptsPage_rendersScriptCardsWithActions() {
        var current by mutableStateOf(
            listOf(
                Script("s1", "脚本甲", code = "var a = 1", enabled = true),
                Script("s2", "脚本乙", code = "var b = 2", enabled = true)
            )
        )
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ProvideAppOverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        ScriptsPage(
                            scripts = current,
                            onChange = { current = it },
                            onImportCode = {}, onImportUrl = {}, onImportFile = {},
                            onMove = { _, _ -> },
                            onProbe = { _, cb -> cb("检测完成") }
                        )
                        AppOverlayHostLayer()
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // 卡片与四个操作入口都应进入语义树（渲染+接线证据）；滚动分发在本环境不可靠，不作断言。
        assertTrue("脚本卡操作应可达", scrollPageUntil(hasContentDescription("检测 脚本甲")))
        composeRule.onNodeWithContentDescription("检测 脚本甲").assertExists()
        composeRule.onNodeWithContentDescription("详情 脚本甲").assertExists()
        composeRule.onNodeWithContentDescription("删除 脚本甲").assertExists()
        composeRule.onNodeWithContentDescription("关闭 脚本甲").assertExists()
    }

    @Test
    fun smartSourcePage_rendersToggleRows() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        SmartSourceStore.init(ctx)
        val scripts = listOf(Script("s1", "脚本甲", code = "var a = 1", enabled = true))
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ProvideAppOverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        SmartSourcePage(scripts = scripts, onScriptsChange = {})
                        AppOverlayHostLayer()
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // 开关行应进入语义树（渲染+接线证据）；滚动分发在本环境不可靠，不作断言。
        assertTrue("智能换源总开关行应可达", scrollPageUntil(hasText("启用智能换源")))
        assertTrue("128k 兜底开关行应可达", scrollPageUntil(hasText("128k 音质兜底")))
        composeRule.onNodeWithText("128k 音质兜底").assertExists()
    }

    @Test
    fun downloadsPage_pauseFiresCallback() {
        val item = DownloadItem(
            song = Song(id = "d1", name = "下载歌曲甲", singer = "歌手"),
            path = "/tmp/a.mp3", size = 100L, time = 1L,
            status = "downloading", progress = 0.5f, taskId = "t1"
        )
        var paused: DownloadItem? = null
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ProvideAppOverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        DownloadsPage(
                            downloads = listOf(item),
                            onMoveDownload = { _, _ -> },
                            onPauseDownload = { paused = it },
                            onResumeDownload = {},
                            onDeleteDownloads = {},
                            onDeleteDownloadRecords = {},
                            onPauseDownloads = {}, onResumeDownloads = {},
                            onRedownloads = {}, onPlayDownload = {},
                            onOpenDownloadDir = {}, onPickDownloadDir = {},
                            onClearDownloadHistory = {}, onRedownload = {}
                        )
                        AppOverlayHostLayer()
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // 页内按钮（非弹层）点击在本环境可正常分发——已多轮实测通过。
        composeRule.onNodeWithContentDescription("暂停下载").assertIsDisplayed().performClick()
        composeRule.waitUntil(3_000) { paused == item }
        assertEquals("暂停按钮应回调该条目", item, paused)
        composeRule.onNodeWithContentDescription("取消下载").assertIsDisplayed()
    }
}
