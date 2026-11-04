package xyz.cncnlp.soundwave

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreVisualUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun designSchemesUseTheUnifiedPageBackgroundAndCoralPaletteWithReadablePrimaryText() {
        val light = soundwaveDesignLightScheme()
        val night = soundwaveDesignNightScheme()

        // ACCEPT5 规格变更重钉（用户 2026-11-04 终局定版，WAVE-ACCEPT5-NOTICE E）：
        // 页面背景全 app 统一偏白——设计浅色 background 不再是暖纸 Paper，
        // 改与根主题 background 同值 #FAFAFA（字面 + 跨 scheme 同值双钉，严于原单钉）。
        assertEquals(Color(0xFFFAFAFA.toInt()), light.background)
        assertEquals(lightScheme().background, light.background)
        assertEquals(SoundwaveDesignPalette.Surface, light.surface)
        assertEquals(SoundwaveDesignPalette.Accent, light.primary)
        assertEquals(SoundwaveDesignPalette.AccentSoft, light.secondaryContainer)
        assertEquals(SoundwaveDesignPalette.NightPaper, night.background)
        assertEquals(SoundwaveDesignPalette.NightSurface, night.surface)
        assertEquals(SoundwaveDesignPalette.Accent, night.primary)
        assertTrue(contrastRatio(light.onBackground, light.background) >= 7.0)
        assertTrue(contrastRatio(light.onPrimary, light.primary) >= 4.5)
        assertTrue(contrastRatio(night.onBackground, night.background) >= 7.0)
        assertTrue(contrastRatio(night.onPrimary, night.primary) >= 4.5)
    }

    @Test
    fun redesignedBottomNavigationStillDispatchesAllExistingDestinations() {
        var selectedTab: Tab? = null
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                BottomNav(
                    tab = Tab.HOME,
                    primary = SoundwaveDesignPalette.Accent,
                    dark = false,
                    themeMode = "light",
                    onTab = { selectedTab = it }
                )
            }
        }

        composeRule.onNodeWithContentDescription("首页", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("搜索", useUnmergedTree = true).performClick()
        assertEquals(Tab.SEARCH, selectedTab)
        composeRule.onNodeWithContentDescription("音乐库", useUnmergedTree = true).performClick()
        assertEquals(Tab.PLAYLISTS, selectedTab)
        composeRule.onNodeWithContentDescription("设置", useUnmergedTree = true).performClick()
        assertEquals(Tab.SETTINGS, selectedTab)
    }

    @Test
    fun redesignedMiniPlayerStillDispatchesPlayAndNextActions() {
        val song = Song(id = "visual-test", name = "设计测试曲", singer = "测试歌手")
        val progress = mutableStateOf(0.42f)
        var playToggleCount = 0
        var nextCount = 0

        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                MiniPlayer(
                    song = song,
                    playing = false,
                    progressState = progress,
                    resolvingState = androidx.compose.runtime.mutableStateOf(false),
                    onClick = {},
                    onToggle = { playToggleCount++ },
                    onNext = { nextCount++ }
                )
            }
        }

        composeRule.onNodeWithContentDescription("播放：设计测试曲", useUnmergedTree = true).performClick()
        assertEquals(1, playToggleCount)
        // 设计稿移动端迷你条不显示“下一首”按钮：通过左滑 / 无障碍自定义动作触发。
        val nextAction = composeRule.onNode(
            androidx.compose.ui.test.SemanticsMatcher("has next custom action") { node ->
                node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.CustomActions) { null }?.any { it.label == "下一首：设计测试曲" } == true
            }, useUnmergedTree = true
        ).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].first { it.label == "下一首：设计测试曲" }
        composeRule.runOnIdle { nextAction.action() }
        assertEquals(1, nextCount)
    }

    @Test
    fun redesignedFullPlayerPlayButtonKeepsAccessiblePausedStateAndToggleAction() {
        var toggleCount = 0
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                AppleMusicControls(
                    playing = false,
                    playMode = PlayMode.SEQUENCE,
                    volume = 0.5f,
                    onVolume = {},
                    showVolume = false,
                    volumePressed = false,
                    onVolumePressed = {},
                    onPrev = {},
                    onToggle = { toggleCount++ },
                    onNext = {},
                    onLyrics = {},
                    onDevice = {},
                    onQueue = {},
                    immersiveSelected = false
                )
            }
        }

        composeRule.onNodeWithContentDescription("播放", useUnmergedTree = true).performClick()
        assertEquals(1, toggleCount)
    }

    @Test
    fun searchPresentationKeepsStaleRowsAndSeparatesEmptyLoadingAndFailure() {
        val songs = listOf("cached-song")

        assertEquals(
            SearchResultPresentation.Loading,
            searchResultPresentation(LoadableContent.Loading, hasItems = false)
        )
        assertEquals(
            SearchResultPresentation.Ready,
            searchResultPresentation(LoadableContent.Available(songs, 1L), hasItems = true)
        )
        assertEquals(
            SearchResultPresentation.Empty,
            searchResultPresentation(LoadableContent.Available(songs, 1L), hasItems = false)
        )
        assertEquals(
            SearchResultPresentation.Stale("网络暂时中断"),
            searchResultPresentation(LoadableContent.Stale(songs, 1L, "网络暂时中断"), hasItems = true)
        )
        assertEquals(
            SearchResultPresentation.Error("网络不可用"),
            searchResultPresentation(LoadableContent.Unavailable("网络不可用"), hasItems = false)
        )
        assertEquals(
            SearchResultPresentation.Empty,
            searchResultPresentation(LoadableContent.Empty(1L), hasItems = false)
        )
    }

    @Test
    fun searchErrorStateRetryIsAccessibleAndThemeSecondaryTextStaysReadable() {
        var retryCount = 0
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                SpaceCompressedStateCard(
                    title = "暂时无法获取结果",
                    message = "网络不可用",
                    status = SpaceCompressedState.Error,
                    actionLabel = "重试搜索",
                    onAction = { retryCount++ }
                )
            }
        }

        composeRule.onNodeWithText("暂时无法获取结果").assertExists()
        composeRule.onNodeWithText("重试搜索").performClick()
        assertEquals(1, retryCount)
        assertTrue(contrastRatio(soundwaveDesignLightScheme().onSurfaceVariant, soundwaveDesignLightScheme().surface) >= 4.5)
        assertTrue(contrastRatio(soundwaveDesignNightScheme().onSurfaceVariant, soundwaveDesignNightScheme().surface) >= 4.5)
    }

    @Test
    fun redesignedLibraryQuickCardStillPlaysTheSelectedCollection() {
        val song = Song(id = "library-test", name = "收藏歌曲", singer = "测试歌手")
        val liked = Playlist("liked", "喜欢", local = true, songs = mutableListOf(song))
        val local = Playlist("local", "本地歌单", local = true)
        var playCount = 0

        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                LibraryQuickPager(
                    liked = liked,
                    local = local,
                    favs = listOf(song),
                    onOpen = {},
                    onPlay = { _, songs, source -> if (songs.singleOrNull() == song && source == "liked") playCount++ },
                    onLikedMenu = {},
                    onLocalMenu = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("播放我的喜欢").performClick()
        assertEquals(1, playCount)
    }

    @Test
    fun redesignedSearchPageKeepsModeAndFocusCallbacks() {
        var selectedMode = "song"
        var searchFocused = false
        composeRule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                SearchPage(
                    songResults = emptyList(),
                    plResults = emptyList(),
                    loggedIn = false,
                    user = "",
                    onLogin = {},
                    onSearch = { _, _, _ -> },
                    onPlay = {},
                    onOpenPl = {},
                    onMode = { selectedMode = it },
                    onSearchFocus = { searchFocused = it }
                )
            }
        }

        // 设计稿 v2 .search-mode-button：类别为“全部”时只显示琥珀色下箭头，不显示类别名。
        val categorySelector = composeRule.onNodeWithContentDescription("选择搜索类别")
        categorySelector.assertIsDisplayed()
        categorySelector.assertHasClickAction()
        categorySelector.performClick()
        composeRule.mainClock.advanceTimeBy(500)
        val playlistMode = composeRule.onNodeWithText("搜索歌单")
        playlistMode.assertIsDisplayed()
        playlistMode.assertHasClickAction()
        playlistMode.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("pl", selectedMode)
        composeRule.onNodeWithText("搜索歌曲、专辑、艺人…").performClick()
        assertTrue(searchFocused)
    }

    private fun contrastRatio(foreground: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color): Double {
        val foregroundLuminance = foreground.luminance().toDouble()
        val backgroundLuminance = background.luminance().toDouble()
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
