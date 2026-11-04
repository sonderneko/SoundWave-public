package xyz.cncnlp.soundwave

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.ui.common.DockMetrics
import xyz.cncnlp.soundwave.ui.common.LocalDockContentInset

/**
 * r655 第 17 项：设置页末尾几行在 Dock（迷你条 + 底栏）宿主之上必须能真正收到点击。
 *
 * 复刻 AppRoot 的 Scaffold 结构：bottomBar 为固定高度 Dock 宿主（真实 MiniPlayer + BottomNav），
 * 内容区只吃顶部 padding（与 AppRoot 一致），页面通过 LocalDockContentInset 获得底部避让。
 * 滚到底后点击末尾行（崩溃诊断 / 关于 APP），断言回调触发——即点击没有被 Dock 宿主或
 * 其它透明层吃掉；并断言最后一行完整位于 Dock 宿主上沿之上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class SettingsBottomReachabilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val song = Song(id = "s1", name = "Glass Horizon", singer = "Mira Vale", source = "wy", duration = 200_000)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun host(dockStyle: String, onAbout: () -> Unit, onSub: (String?) -> Unit) {
        // 全量运行时其它测试遗留的全局状态可能让页面持续有帧任务（单独运行可空闲）：固定手动推进时钟，
        // waitForIdle 只等重组/布局完成，不再等动画与 delay 循环。
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        Column(Modifier.fillMaxWidth().height(PLAYER_DOCK_HOST_HEIGHT_DP.dp), verticalArrangement = Arrangement.Bottom) {
                            MiniPlayer(
                                song = song, playing = false, progressState = androidx.compose.runtime.remember { mutableFloatStateOf(0.3f) },
                                resolvingState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }, style = dockStyle, onClick = {}, onToggle = {}
                            )
                            Spacer(Modifier.height(if (dockStyle == "capsule") 8.dp else DockMetrics.MiniGap))
                            androidx.compose.material3.Surface(color = Color.Transparent) {
                                BottomNav(Tab.SETTINGS, style = dockStyle) { }
                            }
                        }
                    }
                ) { pad ->
                    val inset = (if (dockStyle == "capsule") DockMetrics.CapsuleNavHeight else DockMetrics.BarNavHeight) +
                        (if (dockStyle == "capsule") DockMetrics.CapsuleMiniHeight else DockMetrics.MiniHeight) + DockMetrics.MiniGap + DockMetrics.ContentExtra
                    CompositionLocalProvider(LocalDockContentInset provides inset) {
                        Box(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
                            MePage(
                                serverUrl = "", preferQ = "无损", loggedIn = true, user = "Amanda", status = "", sleepRemain = 0L,
                                onUrl = {}, onQuality = {}, onSleep = {},
                                themeMode = "light", onOpenAbout = onAbout, settingsSub = null, onSettingsSub = onSub
                            )
                        }
                    }
                }
            }
        }
    }

    private fun settle() {
        repeat(4) { rule.mainClock.advanceTimeBy(250) }
        rule.waitForIdle()
    }

    private fun assertLastRowsClickable(dockStyle: String) {
        var about = 0
        val subs = mutableListOf<String?>()
        host(dockStyle, onAbout = { about++ }, onSub = { subs += it })
        settle()
        // 用户会把设置页滚到最底：直接按语义动作滚到列表末尾（手动时钟下 performScrollToNode 会空转）。
        repeat(6) {
            rule.onNodeWithTag("settings_list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            settle()
        }
        rule.waitForIdle()
        // 最后一行完整位于 Dock 宿主（150dp）上沿之上，不与迷你条/底栏重叠。
        val rootBottom = rule.onNodeWithTag("settings_list").fetchSemanticsNode().boundsInRoot.bottom
        val rowBottom = rule.onNodeWithText("关于 APP").fetchSemanticsNode().boundsInRoot.bottom
        val dockTop = rootBottom - with(rule.density) { PLAYER_DOCK_HOST_HEIGHT_DP.dp.toPx() }
        assert(rowBottom <= dockTop) { "最后一行 bottom=$rowBottom 落在 Dock 宿主上沿 $dockTop 之下（$dockStyle）" }
        rule.onNodeWithText("关于 APP").performClick()
        rule.onNodeWithText("崩溃诊断").performClick()
        settle()
        assertEquals("关于 APP 行必须收到点击（$dockStyle）", 1, about)
        assertEquals("崩溃诊断行必须收到点击（$dockStyle）", listOf<String?>("crash"), subs)
    }

    @Test fun lastSettingsRowsReceiveClicksAboveDefaultDock() = assertLastRowsClickable("default")

    @Test fun lastSettingsRowsReceiveClicksAboveCapsuleDock() = assertLastRowsClickable("capsule")
}
