package xyz.cncnlp.soundwave

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.ui.notice.AppNotice

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w420dp-h900dp-xhdpi")
class SettingsNotificationsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun bellHasAccessibleNameAndOpensNotificationRoute() {
        var route: String? = null
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                AppPageHeader("设置", loggedIn = false, showAvatar = false,
                    trailing = { SettingsNotificationButton { route = "notifications" } })
            }
        }
        rule.onNodeWithContentDescription("查看通知").assertHasClickAction().performClick()
        assertEquals("notifications", route)
        // accept6-backnav：notifications 从设置根/横幅进入（深度-1），弹栈落根（旧 settingsBackTarget 钉的等价新钉）。
        assertNull(SettingsNavStack.backTarget(listOf("notifications")))
    }

    @Test fun notificationsContentShowsNoUpdateCardAndScriptEntryWorks() {
        var scriptOpens = 0
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                SettingsNotificationsContent(emptyList(), onOpenScripts = { scriptOpens++ })
            }
        }
        // A 波追加（用户裁定）：消息中心不再出现「应用更新」类卡片与检查更新入口（保留在关于页）。
        rule.onNodeWithText("应用更新").assertDoesNotExist()
        rule.onNodeWithText("检查更新").assertDoesNotExist()
        rule.onNodeWithText("尚未检查更新", substring = true).assertDoesNotExist()
        rule.onNodeWithText("尚未导入音源脚本，暂无检测报告").assertIsDisplayed()
        assertEquals(0, scriptOpens)
        rule.onNodeWithText("前往音源脚本").performClick()
        assertEquals(1, scriptOpens)
    }

    @Test fun inboxCardExpandsAndCollapsesOnTap() {
        AppNotice.attach(rule.activity)
        AppNotice.clearInbox()
        rule.runOnIdle {
            AppNotice.inbox(
                AppNotice.Category.SYNC, "同步完成",
                "同步完成明细：歌单 12 个、歌曲 345 首、封面 678 张，全部校验通过，无冲突需要处理。"
            )
        }
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                SettingsNotificationsContent(emptyList(), onOpenScripts = {})
            }
        }
        rule.onNodeWithText("同步完成").assertIsDisplayed()
        rule.onNodeWithText("展开全文").assertIsDisplayed().performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithText("收起").assertIsDisplayed()
        rule.onNodeWithText("收起").performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithText("展开全文").assertIsDisplayed()
        AppNotice.clearInbox()
    }

    @Test fun savedReportNotifiesOpenPageWithoutStartingProbe() {
        ScriptProbeCache.init(rule.activity)
        val script = Script(id = "notice-ui-live", name = "实时检测脚本", code = "notice ui live")
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                SettingsNotificationsPage(listOf(script), {})
            }
        }
        // 验收二轮 E3：没有报告时脚本不得占位成条，只剩区级空态说明。
        rule.onNodeWithText("实时检测脚本").assertDoesNotExist()
        rule.onNodeWithText("最近 30 分钟内没有新的检测报告", substring = true).assertIsDisplayed()
        rule.runOnIdle {
            // 报告落盘（检测完成）且在 30 分钟保留窗内，条目才出现。
            ScriptProbeCache.save(ScriptProbeCache.Report(script.id, script.name, emptyList(),
                checkedAt = System.currentTimeMillis(), scriptHash = ScriptProbeCache.scriptHash(script.code), complete = false))
        }
        rule.waitForIdle()
        rule.onNodeWithText("实时检测脚本").assertIsDisplayed()
        rule.onNodeWithText("完整检测 · 未完成", substring = true).assertIsDisplayed()
        rule.onNodeWithText("报告时间：", substring = true).assertIsDisplayed()
    }
}
