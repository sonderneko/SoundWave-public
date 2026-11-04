package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.common.DesignPlatformPickContent
import xyz.cncnlp.soundwave.ui.common.DesignSheetPanel
import xyz.cncnlp.soundwave.ui.common.PlatformPickOption
import xyz.cncnlp.soundwave.ui.search.SearchGenreRail
import java.io.File

/** r658b 首页流视觉核对：音乐库我的喜欢卡、平台切换抽屉、探索声音、搜索综合结果。仅 SW_SCREENSHOT_DIR 时运行。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h845dp-xhdpi")
class R658BHomeScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun outDir(): File? = System.getenv("SW_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }

    private fun save(name: String) {
        repeat(4) { rule.waitForIdle(); shadowOf(Looper.getMainLooper()).idle() }
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(outDir(), "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val songs = listOf(
        Song(id = "1", name = "Glass Horizon", singer = "Mira Vale", source = "wy", album = "Neon Current", duration = 258_000),
        Song(id = "2", name = "Glass Horizon (Live)", singer = "Mira Vale", source = "wy", album = "Live 2026", duration = 280_000),
        Song(id = "3", name = "Horizon Glass", singer = "North Cinema", source = "wy", album = "Paper Moon", duration = 231_000)
    )

    @Test
    fun libraryAndGenres() {
        Assume.assumeTrue(outDir() != null)
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 40.dp)) {
                    LibraryFavoritesSleeveCard(
                        title = "我的喜欢", eyebrow = "MY FAVORITES", subtitle = "128 首 · 8.7 小时", cover = null,
                        defaultRes = R.drawable.library_design_cover_1, enabled = true, spinning = true, reduceMotion = true,
                        onOpen = {}, onMore = {}, onPlay = {}, onShuffle = {}, modifier = Modifier.padding(horizontal = 14.dp)
                    )
                    Box(Modifier.padding(horizontal = 14.dp)) { LibraryDesignRow(title = "Night Drive: 02 AM", cover = "", badge = "网易云", count = "18 首 · 在线导入", onClick = {}) }
                    SearchGenreRail(onPick = {})
                }
            }
        }
        save("r658b_home_library_genres")
    }

    @Test
    fun pickSheet() {
        Assume.assumeTrue(outDir() != null)
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Box(Modifier.fillMaxSize().background(Color(0xFF333333)), contentAlignment = Alignment.BottomCenter) {
                    DesignSheetPanel {
                        DesignPlatformPickContent(
                            "Music source", "切换音源", "首页推荐、榜单与歌单广场使用的平台。",
                            listOf("wy", "tx", "kg", "kw", "mg").map { PlatformPickOption(it, xyz.cncnlp.soundwave.ui.common.platformFullName(it), if (it == "wy") "当前音源" else "") },
                            current = "wy"
                        ) {}
                    }
                }
            }
        }
        save("r658b_home_pick_sheet")
    }

    @Test
    fun searchAll() {
        Assume.assumeTrue(outDir() != null)
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
                    SearchAllSections(
                        query = "Glass", songs = songs,
                        playlists = listOf(Playlist("p1", "Glass 精选", source = "wy", count = 32), Playlist("p2", "地平线之外", source = "wy", count = 18)),
                        albums = songs, artists = songs,
                        songState = LoadableContent.Available(songs), playlistState = LoadableContent.Available(emptyList()),
                        albumState = LoadableContent.Available(songs), artistState = LoadableContent.Available(songs),
                        onShowAll = {}, onPlay = {}, onOpenPlaylist = {}, onOpenAlbum = {}, onOpenArtist = {}
                    )
                }
            }
        }
        save("r658b_home_search_all")
    }
}
