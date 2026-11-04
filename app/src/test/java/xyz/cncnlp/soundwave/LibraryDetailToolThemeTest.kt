package xyz.cncnlp.soundwave

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.ui.common.FrostedPrefs

/** The first imported online playlist, including its per-playlist persisted sort. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h1100dp-xhdpi")
class LibraryDetailToolThemeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val playlist = Playlist("imported_first_tool_theme", "首个在线歌单", source = "wy", remoteId = "123", count = 2)
    private val songs = listOf(Song("tool_b", "B track", "Artist", "wy"), Song("tool_a", "A track", "Artist", "wy"))
    private var dark by mutableStateOf(false)
    private var visible by mutableStateOf(true)
    private var reordered by mutableStateOf(false)

    private fun open(savedSort: String = "default") {
        FrostedPrefs.resetForTest()
        val owner = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()
        ContextPreferences.open(rule.activity, "soundwave_sort", android.content.Context.MODE_PRIVATE, owner)
            .edit().putString("mode_${playlist.id}", savedSort).commit()
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (visible) LibraryPage(
                        bundle = LocalStore.UserBundle(playlists = if (reordered)
                            mutableListOf(playlist.copy(id = "second_import", name = "另一个歌单"), playlist)
                        else mutableListOf(playlist)),
                        history = emptyList(), downloads = emptyList(), loggedIn = false, user = "",
                        detail = playlist, detailSongs = songs, onOpen = {}, onBack = {},
                        onPlay = { _, _ -> }, onLogin = {}, onImport = {},
                        detailCoverStyle = DetailCoverStyles.DEFAULT
                    )
                }
            }
        }
        settle()
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
    }

    /** Sample the fill left of the glyph, away from the border and shadow. */
    private fun assertSurface(description: String, expected: Color) {
        val bounds = rule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val actual = bitmap.getPixel((bounds.left + bounds.width * 0.20f).toInt(), bounds.center.y.toInt())
                assertEquals("$description button fill must follow the current surface", expected.toArgb(), actual)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Sample an arbitrary pixel inside a tool button (fractions of its bounds). */
    private fun sampleButtonPixel(description: String, xFraction: Float, yFraction: Float): Int {
        val bounds = rule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        var pixel = 0
        rule.runOnIdle {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                pixel = bitmap.getPixel(
                    (bounds.left + bounds.width * xFraction).toInt(),
                    (bounds.top + bounds.height * yFraction).toInt()
                )
            } finally {
                bitmap.recycle()
            }
        }
        return pixel
    }

    private fun assertTools() {
        val surface = if (dark) SoundwaveDesignPalette.NightSurface else SoundwaveDesignPalette.Surface
        assertSurface("排序", surface)
        assertSurface("多选", surface)
    }

    /**
     * User ruling 2026-11-04 (supersedes SW-09-16's neutral style): a persisted non-default
     * sort shows the CORAL active style in both themes — surface fill stays, glyph and border
     * use the coral primary (SoundwaveDesignPalette.Accent resolves as colorScheme.primary
     * in both themes). Glyph sample sits on the sort glyph's top stroke.
     */
    private fun assertSortActiveCoral() {
        val surface = if (dark) SoundwaveDesignPalette.NightSurface else SoundwaveDesignPalette.Surface
        val accent = SoundwaveDesignPalette.Accent
        assertSurface("排序", surface)
        val glyph = sampleButtonPixel("排序", 0.42f, 0.36f)
        assertEquals("active sort glyph must be coral primary on surface fill", accent.toArgb(), glyph)
        assertSurface("多选", surface)
    }

    @After fun reset() { FrostedPrefs.resetForTest() }

    @Test fun savedSortShowsCoralIndicatorOnFirstImportedPlaylist() {
        open("name")
        assertSortActiveCoral()
        assertTrue(rule.onNodeWithText("A track").fetchSemanticsNode().boundsInRoot.top <
            rule.onNodeWithText("B track").fetchSemanticsNode().boundsInRoot.top)
        rule.runOnIdle { dark = true }; settle()
        assertSortActiveCoral()
        rule.runOnIdle { dark = false }; settle()
        assertSortActiveCoral()
        rule.runOnIdle { visible = false }; settle()
        rule.runOnIdle { visible = true }; settle()
        assertSortActiveCoral()
    }

    @Test fun movingImportedPlaylistToSecondPositionKeepsCoralIndicator() {
        open("name")
        rule.runOnIdle { visible = false; reordered = true }; settle()
        rule.runOnIdle { visible = true }; settle()
        assertSortActiveCoral()
    }

    @Test fun sortingAndDismissingMenuKeepsSurfaceButtons() {
        open()
        assertTools()
        rule.onNodeWithContentDescription("排序").performClick()
        rule.onNodeWithText("按标题").performClick()
        settle()
        // User ruling 2026-11-04: non-default sort -> coral indicator (not ink, not plain surface).
        assertSortActiveCoral()
        assertTrue(rule.onNodeWithText("A track").fetchSemanticsNode().boundsInRoot.top <
            rule.onNodeWithText("B track").fetchSemanticsNode().boundsInRoot.top)
        rule.onNodeWithContentDescription("排序").performClick()
        rule.onNodeWithText("默认排序").performClick()
        settle()
        // One-tap restore: back to default sort, button returns to plain surface style.
        assertTools()
        assertTrue(rule.onNodeWithText("B track").fetchSemanticsNode().boundsInRoot.top <
            rule.onNodeWithText("A track").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun themeTransitionsAndLeavingMultiSelectDoNotLeaveDarkButtons() {
        open()
        rule.runOnIdle { dark = true }; settle()
        assertTools()
        rule.runOnIdle { dark = false }; settle()
        assertTools()
        rule.onNodeWithContentDescription("多选").performClick()
        rule.onNodeWithText("全选").performClick()
        rule.onNodeWithText("已选 2 首").assertExists()
        rule.runOnIdle { dark = true }; settle()
        rule.onNodeWithText("完成").performClick()
        settle()
        assertTools()
        rule.runOnIdle { dark = false }; settle()
        assertTools()
    }
}
