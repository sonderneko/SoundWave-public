package xyz.cncnlp.soundwave

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.ui.common.AppOverlayHostLayer
import xyz.cncnlp.soundwave.ui.common.FrostedPrefs
import xyz.cncnlp.soundwave.ui.common.ProvideAppOverlayHost

/** Real LibraryPage + production overlay host. Semantics/fixture reads measure work,
 * not handset frame times. All songs, preferences and callbacks are isolated fixtures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h860dp-xhdpi")
class LibraryPlaylistPerformanceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val artworkReads = IntArray(120)
    private val playlists = (0 until 120).map { index ->
        Playlist(
            "perf_$index", "Fixture playlist %03d".format(index),
            source = if (index % 2 == 0) "wy" else "tx", local = true,
            songs = ObservedSongs { artworkReads[index]++ }
        )
    }
    private var shown by mutableStateOf(true)
    private var detail by mutableStateOf<Playlist?>(null)
    private var filter by mutableStateOf("")
    private var order by mutableStateOf(playlists.map { it.id })
    private val completed = mutableListOf<List<String>>()
    private var opened: Playlist? = null
    private var plays = 0

    private fun render(saved: Boolean = false, fixture: List<Playlist> = playlists) {
        FrostedPrefs.resetForTest()
        FrostedPrefs.themeMode = "light"
        val bundle = LocalStore.UserBundle(playlists = fixture.map { it.copy(favorited = saved || it.favorited) }.toMutableList())
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ProvideAppOverlayHost {
                    BackHandler(enabled = detail != null) { detail = null }
                    Box(Modifier.fillMaxSize()) {
                        if (shown) LibraryPage(
                            bundle = bundle, history = emptyList(), downloads = emptyList(), loggedIn = false, user = "",
                            detail = detail, detailSongs = detail?.songs.orEmpty(),
                            onOpen = { opened = it; detail = it }, onBack = { detail = null },
                            onPlay = { _, _ -> plays++ }, onLogin = {}, onImport = {}, libOrder = order,
                            platFilter = filter, onPlatFilter = { filter = it },
                            onReorderPlaylists = { completed += it.toList(); order = it.toList() }
                        )
                        AppOverlayHostLayer()
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun renderedPlaylistCount(): Int = rule.onAllNodes(hasText("Fixture playlist", substring = true))
        .fetchSemanticsNodes().size

    private fun assertViewportOnly(phase: String) {
        val count = renderedPlaylistCount()
        println("library-$phase renderedPlaylistTitles=$count tailArtworkReads=${artworkReads.last()}")
        assertTrue("$phase must render some rows, not an empty fixture", count > 0)
        assertTrue("$phase must not render all 120 offscreen rows: $count", count < 20)
        assertEquals("$phase must not inspect offscreen track artwork", 0, artworkReads.last())
    }

    @After fun cleanup() { FrostedPrefs.resetForTest() }

    @Test fun importedRowsAreLazyOnEntryAndReturn() {
        render()
        assertViewportOnly("entry")
        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        rule.runOnIdle { shown = true }
        rule.waitForIdle()
        assertViewportOnly("remount")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Fixture playlist 119"))
        rule.onNodeWithText("Fixture playlist 119").performClick()
        assertEquals("perf_119", opened?.id)
        assertEquals(0, plays)
        assertTrue(completed.isEmpty())
    }

    @Test fun actualDetailBackDoesNotExpandOffscreenLibraryRows() {
        render()
        rule.onNodeWithText("Fixture playlist 000").performClick()
        back()
        rule.waitForIdle()
        assertNull(detail)
        assertViewportOnly("detail-back")
        assertEquals(0, plays)
        assertTrue(completed.isEmpty())
    }

    @Test fun savedRowsAreLazyAndKeepTheirOwnSection() {
        render(saved = true)
        switchToFavoritesView()
        assertViewportOnly("saved-entry")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Fixture playlist 119"))
        rule.onNodeWithText("Fixture playlist 119").performClick()
        assertEquals("perf_119", opened?.id)
    }

    // ACCEPT5: 排序弹层已删除——长按拖动第一行下移一步，松手落盘恰好一次，行序随之更新。
    @Test fun longPressDragReordersOnlineRowsAndSavesOnceOnRelease() {
        render()
        dragFirstRowDownOneStep()
        assertEquals(1, completed.size)
        val expected = listOf("perf_1", "perf_0") + playlists.drop(2).map { it.id }
        assertEquals(expected, completed.single())
        assertEquals(expected, order)
        assertBefore("Fixture playlist 001", "Fixture playlist 000")
        assertEquals("drag must not touch playback", 0, plays)
    }

    // ACCEPT5: 长按未拖动松手 = 原管理抽屉入口（不落盘、不改序）。
    @Test fun longPressWithoutDragOpensPlaylistMenuAndSavesNothing() {
        render()
        rule.onNodeWithText("Fixture playlist 000").performTouchInput {
            down(center)
            advanceEventTime(700)
            up()
        }
        rule.waitForIdle()
        rule.onNodeWithText("编辑名称").assertIsDisplayed()
        assertTrue(completed.isEmpty())
        assertEquals(playlists.map { it.id }, order)
    }

    @Test fun platformFilterPreservesRelativeOrderAndOpenIdentity() {
        render()
        rule.runOnIdle { filter = "tx"; order = playlists.map { it.id }.reversed() }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Fixture playlist 119"))
        assertBefore("Fixture playlist 119", "Fixture playlist 117")
        assertBefore("Fixture playlist 117", "Fixture playlist 115")
        rule.onNodeWithText("Fixture playlist 118").assertDoesNotExist()
        rule.onNodeWithText("Fixture playlist 119").performClick()
        assertEquals("perf_119", opened?.id)
        assertTrue(completed.isEmpty())
    }

    @Test fun mixedCollectionsKeepImportedSavedAndAlbumOrderSeparate() {
        val fixture = listOf(
            Playlist("m1", "Imported one", source = "wy"),
            Playlist("f1", "Saved one", source = "wy", favorited = true),
            Playlist("a1", "Album one", source = "wy", favorited = true),
            Playlist("m2", "Imported two", source = "wy"),
            Playlist("f2", "Saved two", source = "wy", favorited = true),
            Playlist("a2", "Album two", source = "wy", favorited = true)
        )
        rule.activity.getSharedPreferences("soundwave", 0).edit()
            .putStringSet("album_favorite_ids", setOf("a1", "a2")).commit()
        order = listOf("f2", "a2", "m2", "f1", "m1", "a1")
        render(fixture = fixture)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Imported two"))
        assertBefore("Imported two", "Imported one")
        switchToFavoritesView()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Saved two"))
        assertBefore("Saved two", "Saved one")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Album two"))
        assertBefore("Album one", "Album two") // Album favorites retain bundle order, not libOrder.
        rule.onNodeWithText("Album two").performClick()
        assertEquals("a2", opened?.id)
        assertTrue(completed.isEmpty())
    }

    private fun assertBefore(first: String, second: String) {
        fun y(text: String): Float {
            val nodes = rule.onAllNodesWithText(text).fetchSemanticsNodes()
            assertTrue("missing row $text", nodes.isNotEmpty())
            val result = nodes.first().boundsInRoot.top
            println("row-order $text y=$result allYs=${nodes.map { it.boundsInRoot.top }}")
            return result
        }
        assertTrue("$first must precede $second", y(first) < y(second))
    }

    /** 长按第一行后向下拖 60px：越过 pitch×0.27 的一步阈值（xhdpi 行高 144px → ≈39px）、不到两步。
     *  本版 TouchInjectionScope 无 longPress，手动 down + 推进事件时间越过长按超时（400ms）。 */
    private fun dragFirstRowDownOneStep() {
        rule.onNodeWithText("Fixture playlist 000").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, 60f))
            up()
        }
        rule.waitForIdle()
    }

    private fun openLibraryMenu() {
        rule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("歌单操作"))
        rule.onNodeWithContentDescription("歌单操作").performClick()
        rule.waitForIdle()
    }

    private fun switchToFavoritesView() {
        openLibraryMenu()
        rule.onNodeWithText("切换到收藏").performClick()
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private class ObservedSongs(private val onRead: () -> Unit) : AbstractMutableList<Song>() {
        private val values = mutableListOf(Song("fixture_track", "Fixture track", "Fixture artist", "local"))
        override val size: Int get() = values.size
        override fun get(index: Int): Song { onRead(); return values[index] }
        override fun add(index: Int, element: Song) { values.add(index, element) }
        override fun removeAt(index: Int): Song = values.removeAt(index)
        override fun set(index: Int, element: Song): Song = values.set(index, element)
    }
}
