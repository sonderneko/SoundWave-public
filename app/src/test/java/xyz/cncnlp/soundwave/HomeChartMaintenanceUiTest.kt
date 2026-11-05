package xyz.cncnlp.soundwave

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.ui.common.ProvideAppOverlayHost
import xyz.cncnlp.soundwave.ui.common.AppOverlayHostLayer
import xyz.cncnlp.soundwave.ui.home.ChartHeroCard
import xyz.cncnlp.soundwave.ui.home.ChartRankRow
import xyz.cncnlp.soundwave.ui.home.chartRankMovementLabel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xhdpi")
class HomeChartMaintenanceUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val song = Song("rank-test", "榜单歌曲", "歌手", "wy")

    @Test fun firstThreeAndRemainingCoversHaveIdenticalSize() {
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Column {
                    listOf(1, 2, 3, 4, 100).forEach { rank ->
                        ChartRankRow(song, rank, false, onMore = {}) {}
                    }
                }
            }
        }
        listOf(1, 2, 3, 4, 100).forEach { rank ->
            rule.onNodeWithTag("chart_cover_$rank", useUnmergedTree = true)
                .assertWidthIsEqualTo(52.dp).assertHeightIsEqualTo(52.dp)
        }
        rule.onNodeWithText("01").assertExists()
        rule.onNodeWithText("100").assertExists()
    }

    @Test fun moreButtonHasIndependentClickTargetAndDoesNotPlay() {
        var plays = 0
        var menus = 0
        rule.setContent {
            SoundwaveDesignTheme(isDark = true) {
                ChartRankRow(song, 1, true, onMore = { menus++ }) { plays++ }
            }
        }
        rule.onNodeWithContentDescription("更多：榜单歌曲").assertHasClickAction().performClick()
        assertEquals(1, menus)
        assertEquals(0, plays)
        rule.onNodeWithText(song.name).performClick()
        assertEquals(1, plays)
    }

    @Test fun multiselectHidesMoreAndPreservesSelectionClick() {
        var toggles = 0
        var menus = 0
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ChartRankRow(song, 4, false, multiMode = true, selected = true,
                    onMore = { menus++ }) { toggles++ }
            }
        }
        rule.onNodeWithContentDescription("更多：榜单歌曲").assertDoesNotExist()
        rule.onNodeWithText(song.name).performClick()
        assertEquals(1, toggles)
        assertEquals(0, menus)
    }

    @Test fun unknownUpdateNeverShowsInventedFreshness() {
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ChartHeroCard(RankBoard("热歌榜", source = "wy"), "网易云", onSwitch = {})
            }
        }
        rule.onNodeWithText("刚刚更新", substring = true).assertDoesNotExist()
        rule.onNodeWithText("每天更新", substring = true).assertDoesNotExist()
    }

    @Test fun realMovementHasDirectionAndUnknownOrZeroHasNoDecoration() {
        rule.setContent {
            SoundwaveDesignTheme(isDark = true) {
                Column {
                    ChartRankRow(song.copy(rankChange = 5), 1, false) {}
                    ChartRankRow(song.copy(rankChange = -2), 2, false) {}
                    ChartRankRow(song.copy(rankChange = 0), 3, false) {}
                    ChartRankRow(song, 4, false) {}
                }
            }
        }
        rule.onNodeWithContentDescription("排名上升 5 位", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("排名下降 2 位", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("↑5", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("↓2", useUnmergedTree = true).assertExists()
        assertEquals("", chartRankMovementLabel(null))
        assertEquals("", chartRankMovementLabel(0))
        assertEquals("↓2147483648", chartRankMovementLabel(Int.MIN_VALUE))
    }

    @Test fun actualPlatformUpdateAppearsUnderBoardName() {
        val board = RankBoard("热歌榜", "wy", updatedAt = 1792656000000L)
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ChartHeroCard(board, "网易云", onSwitch = {})
            }
        }
        val title = rule.onNodeWithText(board.name).fetchSemanticsNode().boundsInRoot
        val update = rule.onNodeWithText(board.officialUpdateLabel()).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(update.top >= title.bottom)
    }

    @Test fun unifiedSongSheetSemanticsDispatchAllFourActionsWithoutPlaying() {
        var action = ""
        rule.setContent {
            SoundwaveDesignTheme(isDark = true) {
                DesignTrackActionSheetContent(song, liked = false, showDownload = true,
                    canDelete = false,
                    onSimilar = {}, onLike = { action = "like" },
                    onAddNext = { action = "next" }, onAdd = { action = "playlist" },
                    onDownload = { action = "download" },
                    onDelete = { error("must not expose delete") }, onDislike = {})
            }
        }
        // MENU-UNIFY：排行榜 ⋯ 已归一至全 App 共用歌曲操作抽屉（HomePage → DesignTrackActionSheet
        // → TrackActionsSheet，其内容即此处演练的 DesignTrackActionSheetContent 委托层）；
        // Exercise the shared sheet's accessible actions; handset touch acceptance is separate.
        rule.onNodeWithContentDescription("收藏歌曲").assertHasClickAction().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals("like", action) }
        rule.onNodeWithText("添加到歌单").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals("playlist", action) }
        rule.onNodeWithContentDescription("下一首播放").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals("next", action) }
        rule.onNodeWithContentDescription("下载").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals("download", action) }
        rule.onNodeWithText("从歌单删除").assertDoesNotExist()
    }
}
