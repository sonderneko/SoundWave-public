package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.home.ChartCatalogStrip
import xyz.cncnlp.soundwave.ui.home.ChartHeroCard
import xyz.cncnlp.soundwave.ui.home.ChartPickerGrid
import xyz.cncnlp.soundwave.ui.home.ChartRankRow
import xyz.cncnlp.soundwave.ui.search.SearchResultRow
import java.io.File

/**
 * r673 shadow pass (user 10-22): renders the real row / card composables touched by the change with a local artwork
 * fixture so the cover shadows (homeCoverShadow), the de-clipped grids/rows and the best-match card without edge line
 * can be inspected. Robolectric native graphics, not a handset. Only runs with SW_SCREENSHOT_DIR set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h900dp-xhdpi")
class R673ShadowScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun fixture(): String {
        val image = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(image); val p = android.graphics.Paint()
        p.shader = android.graphics.LinearGradient(0f, 0f, 400f, 400f, intArrayOf(0xFFEBD7C1.toInt(), 0xFF5A9E99.toInt(), 0xFF2B3A55.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 400f, 400f, p)
        p.shader = null; p.color = 0xFFE2A75E.toInt(); c.drawCircle(220f, 170f, 90f, p)
        val file = File(rule.activity.cacheDir, "r673-shadow-fixture.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
        return file.toURI().toString()
    }

    private fun render(dark: Boolean, group: Int) {
        val output = System.getenv("SW_SCREENSHOT_DIR"); Assume.assumeTrue(output != null)
        val uri = fixture()
        val song = Song("fx673", "Glass Horizon", "Mira Vale", "local", cover = uri, duration = 201000)
        val song2 = Song("fx673b", "Northern Lights", "Mira Vale", "local", cover = uri, duration = 187000)
        val boards = (1..6).map { RankBoard("榜单 $it", "wy", listOf(song, song2), "rank_$it", uri, 100) }
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when (group) {
                        0 -> {
                            // 搜索：顶部歌曲大卡（无边缘线）+ 歌曲行 + 「查看全部」行。
                            SearchAllSections(
                                query = "Glass", songs = listOf(song, song2), playlists = emptyList(), albums = emptyList(), artists = emptyList(),
                                songState = LoadableContent.Available(listOf(song, song2)), playlistState = LoadableContent.Loading,
                                albumState = LoadableContent.Loading, artistState = LoadableContent.Loading,
                                onShowAll = {}, onPlay = {}, onOpenPlaylist = {}, onOpenAlbum = {}, onOpenArtist = {}
                            )
                            SearchResultRow(cover = uri, title = "Fixture playlist", subtitle = "查看全部 · 歌单", placeholder = Icons.Outlined.MusicNote, onClick = {})
                            TodayTrackRow(song = song, index = 1, nowPlaying = false, isPlayingNow = false, multiMode = false, selected = false, liked = false,
                                showDownload = true, onLike = {}, onAdd = {}, onAddNext = {}, onDownload = {}, onDelete = null, onDislike = {}, onSimilar = {},
                                onLongClick = {}, showIndex = false) {}
                            SongRow(song2, showIndex = false, flat = true, tight = true, flatOuterHorizontal = 0.dp, flatContentHorizontal = 14.dp, coverShadow = true) {}
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DetailAccentFab(Icons.Outlined.KeyboardArrowUp, "回到顶部", 22.dp) {}
                                    DetailAccentFab(Icons.Outlined.MyLocation, "定位当前歌曲", 20.dp) {}
                                }
                            }
                        }
                        1 -> {
                            // 排行榜：顶部目录封面 + 榜单大卡 + 榜单歌曲；歌手页专辑横排。
                            ChartCatalogStrip(boards, boards.first().id, rememberLazyListState()) {}
                            ChartHeroCard(boards.first(), "网易云", onPlayAll = {}) {}
                            ChartRankRow(song, 1, nowPlaying = false) {}
                            ChartRankRow(song2, 4, nowPlaying = false) {}
                            ChartDetailTrackRow(song = song2, rank = 5, nowPlaying = false, isPlayingNow = false, multiMode = false, selected = false, liked = false,
                                showDownload = true, onLike = {}, onAdd = {}, onAddNext = {}, onDownload = {}, onDelete = null, onDislike = {}, onSimilar = {}, onLongClick = {}) {}
                            SingerAlbumsRow((1..4).map { ArtistAlbum("a$it", "专辑 $it", uri, "2026-10-0$it", 10) }, {})
                        }
                        else -> {
                            // 榜单切换弹窗网格（同 DesignSheetDialog 两侧内边距）。
                            Box(Modifier.padding(horizontal = 22.dp, vertical = 24.dp)) { ChartPickerGrid(boards, boards[1].id) {} }
                        }
                    }
                }
            }
        }
        settle()
        save(output!!, "673-shadow-${if (dark) "dark" else "light"}-$group.png")
    }

    private fun settle() { repeat(6) { rule.waitForIdle(); Thread.sleep(120); shadowOf(Looper.getMainLooper()).idle() } }
    private fun save(output: String, n: String) {
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(output).mkdirs(); File(output, n).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun searchLight() = render(false, 0)
    @Test fun searchDark() = render(true, 0)
    @Test fun chartsLight() = render(false, 1)
    @Test fun chartsDark() = render(true, 1)
    @Test fun pickerLight() = render(false, 2)
}
