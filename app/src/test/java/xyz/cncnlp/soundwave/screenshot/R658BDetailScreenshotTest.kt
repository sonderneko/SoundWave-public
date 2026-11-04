package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.common.DesignSheetPanel
import xyz.cncnlp.soundwave.ui.common.DownloadQualitySheetContent
import java.io.File

/** r658b 详情流视觉核对：沉浸详情头（播放全部 + 白色收藏胶囊）、工具条、带音质标识的曲目行、歌单 ⋯ 抽屉、下载歌曲抽屉。仅 SW_SCREENSHOT_DIR 时运行。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class R658BDetailScreenshotTest {
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
        Song(id = "1", name = "独行", singer = "whtlook", source = "wy", duration = 166_000, maxQuality = "flac"),
        Song(id = "2", name = "て (天空の城ラピュタ)", singer = "久石譲", source = "wy", duration = 251_000, maxQuality = "hires"),
        Song(id = "3", name = "Let It Rain", singer = "Sofia Rain", source = "wy", duration = 201_000, maxQuality = "320k"),
        Song(id = "4", name = "信仰", singer = "张信哲", source = "wy", duration = 280_000, maxQuality = "master")
    )

    @Test fun detailLight() = detailPage(false)
    @Test fun detailDark() = detailPage(true)

    private fun detailPage(dark: Boolean) {
        Assume.assumeTrue(outDir() != null)
        run {
            rule.setContent {
                SoundwaveDesignTheme(isDark = dark) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
                        ImmersiveDetailHero(
                            title = "本地歌单", subtitle = "", meta = listOf("歌单", "150 首", "9 HR 1 MIN", "本地"), cover = "", coverModel = null,
                            tint = Color(0xFF3A5A6A), favorited = false, showFavorite = true, playEnabled = true,
                            onToggleFavorite = {}, onPlayAll = {}, onDownload = {}, onMore = {}
                        )
                        DetailDesignToolbar(
                            title = "曲目列表", countLabel = "150 首", multiMode = false, selectedCount = 0, allSelected = false, sortMode = "default",
                            sortMenuOpen = false, showDownload = false, onDownload = {}, onSortClick = {}, onSortPick = {}, onSortDismiss = {},
                            onEnterSelect = {}, onToggleAll = {}, onDone = {}, topPadding = 10.dp_()
                        )
                        songs.forEachIndexed { i, s ->
                            if (i == 0) TrackRowSkeleton(s, showCover = true)
                            TodayTrackRow(
                                song = s, index = i + 1, nowPlaying = i == 2, isPlayingNow = false, multiMode = false, selected = false, liked = false,
                                showDownload = true, onLike = {}, onAdd = {}, onAddNext = {}, onDownload = {}, onDelete = null, onDislike = {}, onSimilar = {},
                                onLongClick = {}, showIndex = false
                            ) {}
                        }
                    }
                }
            }
            save("r658b_detail_${if (dark) "dark" else "light"}")
        }
    }

    @Test
    fun sheets() {
        Assume.assumeTrue(outDir() != null)
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Box(Modifier.fillMaxSize().background(Color(0xFF333333)), contentAlignment = Alignment.BottomCenter) {
                    DesignSheetPanel {
                        DesignPlaylistActionSheetContent(
                            eyebrow = "Playlist actions", title = "歌单操作", cover = "", name = "本地歌单", subtitle = "150 首 · 9 hr 1 min",
                            favorited = false, showFavorite = true, showDownload = true, onToggleFavorite = {}, onAddNextAll = {}, onAddAll = {},
                            onDownloadAll = {}, onSearch = {}
                        )
                    }
                }
            }
        }
        save("r658b_playlist_sheet")
    }

    @Test
    fun downloadSheet() {
        Assume.assumeTrue(outDir() != null)
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Box(Modifier.fillMaxSize().background(Color(0xFF333333)), contentAlignment = Alignment.BottomCenter) {
                    DesignSheetPanel(scrollContent = true) {
                        DownloadQualitySheetContent(songs[0], emptyMap(), "flac", {}, {}, {})
                    }
                }
            }
        }
        save("r658b_download_sheet")
    }
}

private fun Int.dp_() = androidx.compose.ui.unit.Dp(this.toFloat())
