package xyz.cncnlp.soundwave.perf

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.LibraryPage
import xyz.cncnlp.soundwave.LocalStore
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.SoundwaveDesignTheme
import java.io.File

/**
 * r657 第二轮性能对照（仅 SW_PERF_OUT 设置时运行）：真实 LibraryPage 详情（我的喜欢 600 首 / 在线歌单 600 首）
 *  - 首帧：setContent → 空闲 的墙钟时间；
 *  - 滚动：每帧 performTouchInput 上滑，统计 90 帧墙钟总时长 → 平均每帧 ms；
 *  - 回顶：滚到第 500 首后点「回到顶部」，统计回到 index 0 所需帧数与墙钟时间。
 * Robolectric 下的绝对值不代表真机帧时间，只用于同一机器上修改前后的相对比较。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h860dp-xhdpi")
class R657R2DetailPerfTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun out(): File? = System.getenv("SW_PERF_OUT")?.let { File(it) }

    private fun songs(n: Int) = (0 until n).map { i ->
        Song(id = "p$i", name = "Track number $i with a reasonably long title", singer = "Artist ${i % 17}", source = "wy", duration = 200_000L + i, album = "Album ${i % 9}")
    }

    private fun idle() { rule.waitForIdle(); shadowOf(Looper.getMainLooper()).idle() }

    private fun run(tag: String, pl: Playlist, list: List<Song>, favs: List<Song>) {
        val o = out(); Assume.assumeTrue(o != null)
        rule.mainClock.autoAdvance = false
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
        val bundle = LocalStore.UserBundle(favs = favs.toMutableList())
        val t0 = System.nanoTime()
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Box(Modifier.fillMaxSize()) {
                    LibraryPage(
                        bundle = bundle, history = emptyList(), downloads = emptyList(), loggedIn = false, user = "",
                        detail = pl, detailSongs = list, onOpen = {}, onBack = {}, onPlay = { _, _ -> }, onLogin = {}, onImport = {},
                        showPlaylistFavorite = true
                    )
                }
            }
        }
        repeat(3) { rule.mainClock.advanceTimeByFrame(); idle() }
        val firstMs = (System.nanoTime() - t0) / 1e6
        // 预热一次滚动，避免类加载计入
        val node = rule.onNode(hasScrollAction())
        node.performTouchInput { swipeUp(durationMillis = 200) }; repeat(10) { rule.mainClock.advanceTimeByFrame(); idle() }
        val frames = 90
        val t1 = System.nanoTime()
        repeat(frames / 10) {
            node.performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.5f, durationMillis = 160) }
            repeat(10) { rule.mainClock.advanceTimeByFrame(); idle() }
        }
        val scrollMs = (System.nanoTime() - t1) / 1e6
        node.performScrollToIndex(500); repeat(4) { rule.mainClock.advanceTimeByFrame(); idle() }
        val t2 = System.nanoTime()
        var topFrames = -1
        runCatching {
            rule.onAllNodesWithContentDescription("回到顶部")[0].performClick()
            for (f in 1..240) {
                rule.mainClock.advanceTimeByFrame(); idle()
                val atTop = runCatching { rule.onNode(hasScrollAction()).fetchSemanticsNode().config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) { null }?.value?.invoke() ?: 1f }.getOrDefault(1f) <= 0.5f
                if (atTop) { topFrames = f; break }
            }
        }
        val topMs = (System.nanoTime() - t2) / 1e6
        o!!.parentFile?.mkdirs()
        o.appendText("%s first=%.0fms scroll=%.1fms/frame (90f) top=%d frames %.0fms\n".format(tag, firstMs, scrollMs / frames, topFrames, topMs))
    }

    @Test fun likedDetail() {
        val s = songs(600)
        run("liked600", Playlist("liked", "我的喜欢", local = true, songs = s.toMutableList()), s, s)
    }

    @Test fun onlineDetail() {
        val s = songs(600)
        run("online600", Playlist(id = "pl_wy_perf", name = "悠悠国风|传世之音 不绝于耳", source = "wy", author = "云音乐", count = 600), s, emptyList())
    }
}
