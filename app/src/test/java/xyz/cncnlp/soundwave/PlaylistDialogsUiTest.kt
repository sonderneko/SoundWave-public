package xyz.cncnlp.soundwave

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.ui.common.CreatePlaylistDialog
import xyz.cncnlp.soundwave.ui.common.OnlineImportDialog
import xyz.cncnlp.soundwave.ui.common.RenamePlaylistDialog
import xyz.cncnlp.soundwave.ui.common.ProvideAppOverlayHost
import xyz.cncnlp.soundwave.ui.common.AppOverlayHostLayer

/** Compose behavior/semantics coverage; deliberately avoids platform-window hit testing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w420dp-h900dp-xhdpi")
class PlaylistDialogsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun assertHeading(title: String) {
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)).assertExists()
        rule.onNodeWithText(title).assertIsDisplayed()
    }

    private fun assertButton(description: String) {
        rule.onNodeWithContentDescription(description)
            .assertExists()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test fun onlineImportBlankIsDisabledAndTrimmedNonBlankValueIsImported() {
        var imported: String? = null
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                OnlineImportDialog(onImport = { imported = it }, onDismiss = {})
            }
        }

        assertHeading("导入在线歌单")
        rule.onNodeWithContentDescription("歌单链接 / ID").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).assertExists()
        assertButton("导入")
        rule.onNodeWithContentDescription("导入").assertIsNotEnabled()

        rule.onNode(hasSetTextAction()).performTextInput("   https://music.example/playlist/42   ")
        rule.onNodeWithContentDescription("导入").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals("https://music.example/playlist/42", imported) }
    }

    @Test fun onlineImportInitialChangeResetsInputAndImeDoneDoesNotSubmit() {
        var initial by mutableStateOf("https://music.example/old")
        var imported: String? = null
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                OnlineImportDialog(onImport = { imported = it }, onDismiss = {}, initial = initial)
            }
        }

        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("typed but not submitted")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.runOnIdle { assertEquals(null, imported) }

        rule.runOnIdle { initial = "https://music.example/new" }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, AnnotatedString("https://music.example/new")
        ))
        rule.runOnIdle { assertEquals(null, imported) }
    }

    @Test fun createPlaylistCanEditNameAndConfirmWhenEmpty() {
        var name by mutableStateOf("旧名称")
        var confirmed = 0
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                CreatePlaylistDialog(
                    name = name,
                    onNameChange = { name = it },
                    onConfirm = { confirmed++ },
                    onDismiss = {},
                    onCancel = {}
                )
            }
        }

        assertHeading("新建歌单")
        rule.onNodeWithContentDescription("歌单名称").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("新收藏")
        rule.runOnIdle { assertEquals("新收藏", name) }
        rule.onNode(hasSetTextAction()).performTextClearance()
        assertButton("创建")
        rule.onNodeWithContentDescription("创建").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun renameEditsButCancelDismissesWithoutSaving() {
        var name by mutableStateOf("原名称")
        var saved = 0
        var dismissed = 0
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                RenamePlaylistDialog(
                    name = name,
                    onNameChange = { name = it },
                    onConfirm = { saved++ },
                    onDismiss = { dismissed++ }
                )
            }
        }

        assertHeading("编辑名称")
        rule.onNodeWithContentDescription("歌单名称").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("修改后的名称")
        rule.runOnIdle { assertEquals("修改后的名称", name) }
        assertButton("取消")
        rule.onNodeWithContentDescription("取消").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle {
            assertEquals(0, saved)
            assertEquals(1, dismissed)
        }
    }

    @Test fun onlineEditorRemainsUsableWithDarkThemeAndLargeFont() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                SoundwaveDesignTheme(isDark = true) {
                    OnlineImportDialog(onImport = {}, onDismiss = {}, initial = "https://music.example/list")
                }
            }
        }
        assertHeading("导入在线歌单")
        rule.onNode(hasSetTextAction()).assertIsDisplayed()
        assertButton("导入")
        assertButton("取消")
    }

    @Test fun systemBackUsesDismissRatherThanExplicitCancelOrConfirm() {
        var visible by mutableStateOf(true)
        var dismissals = 0
        var cancellations = 0
        var confirmations = 0
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ProvideAppOverlayHost {
                    Box(Modifier.fillMaxSize()) {
                        if (visible) CreatePlaylistDialog(
                            name = "待创建", onNameChange = {},
                            onConfirm = { confirmations++ },
                            onDismiss = { dismissals++; visible = false },
                            onCancel = { cancellations++ }
                        )
                        AppOverlayHostLayer()
                    }
                }
            }
        }
        rule.onNodeWithText("新建歌单").assertExists()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("新建歌单").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(0, cancellations)
            assertEquals(0, confirmations)
        }
    }
}
