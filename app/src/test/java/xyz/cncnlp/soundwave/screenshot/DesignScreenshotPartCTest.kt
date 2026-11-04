package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import xyz.cncnlp.soundwave.*
import java.io.File

/**
 * 设计稿对照截图 · 音乐库 / 搜索 / 设置（仅本地/手动）：未设置 SW_SCREENSHOT_DIR 时全部跳过。
 * 页面自带 LazyColumn，因此用高窗口一次渲染整页；浅色为主，另含设置深色。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class DesignScreenshotPartCTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun outDir(): File? = System.getenv("SW_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
    private val coverDir: String get() = System.getenv("SW_SCREENSHOT_COVERS") ?: "/workspace/work/compare/covers"
    private fun cover(name: String) = File(coverDir, "$name.png").absolutePath.takeIf { File(it).exists() }.orEmpty()
    private val only: String? get() = System.getenv("SW_SHOT_ONLY")?.takeIf { it.isNotBlank() }

    private fun begin(name: String) {
        Assume.assumeTrue(outDir() != null)
        only?.let { Assume.assumeTrue(it.split(',').contains(name)) }
        Coil.setImageLoader(
            ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build()
        )
    }

    private fun save(name: String) {
        repeat(6) { rule.waitForIdle(); Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var ok = false
        runCatching {
            PixelCopy.request(rule.activity.window, bmp, { r -> ok = r == PixelCopy.SUCCESS }, Handler(Looper.getMainLooper()))
            shadowOf(Looper.getMainLooper()).idle()
        }
        if (!ok) view.draw(Canvas(bmp))
        drawOtherWindows(bmp, view)
        File(outDir(), "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 把 Dialog / Popup 等独立窗口（PixelCopy 只拷活动窗口）按屏幕位置叠加，带 dim 遮罩。 */
    private fun drawOtherWindows(bmp: Bitmap, activityDecor: android.view.View) {
        val views = runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val f = wmg.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") (f.get(inst) as List<android.view.View>).toList()
        }.getOrDefault(emptyList())
        val canvas = Canvas(bmp)
        views.filter { it !== activityDecor && it.isShown && it.width > 0 }.forEach { v ->
            val lp = v.layoutParams as? android.view.WindowManager.LayoutParams
            if (lp != null && (lp.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                canvas.drawColor(android.graphics.Color.argb((lp.dimAmount * 255).toInt().coerceIn(0, 255), 0, 0, 0))
            }
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            val base = IntArray(2); activityDecor.getLocationOnScreen(base)
            canvas.save(); canvas.translate((loc[0] - base[0]).toFloat(), (loc[1] - base[1]).toFloat()); v.draw(canvas); canvas.restore()
        }
    }

    private fun page(dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
    }

    private val covers by lazy { (0..7).map { cover("det$it") } }
    private fun song(i: Int, name: String, singer: String, album: String = "", albumId: String = "") =
        Song(id = "s$i", name = name, singer = singer, source = "wy", cover = covers[i % 8], duration = 200_000, album = album, albumId = albumId)

    private fun bundle(): LocalStore.UserBundle {
        val favs = (0 until 128).map { song(it, "Liked $it", "Artist", album = "Album ${it % 9}", albumId = "a${it % 9}") }
        fun pl(id: String, name: String, n: Int, c: Int, fav: Boolean = false, author: String = "") = Playlist(
            id = id, name = name, cover = covers[c], songs = (0 until n).map { song(it, "T$it", "A") }.toMutableList(),
            source = "wy", count = n, favorited = fav, author = author, remoteId = if (fav) id.removePrefix("fav_wy_") else ""
        )
        return LocalStore.UserBundle(
            favs = favs.toMutableList(),
            playlists = mutableListOf(
                Playlist("liked", "我的喜欢", local = true, songs = favs.toMutableList()),
                Playlist("local", "本地歌单", local = true),
                pl("imp1", "Night Drive: 02 AM", 18, 3), pl("imp2", "雨落之后", 24, 1),
                pl("imp3", "电流经过夏天", 32, 0), pl("imp4", "柔软噪点", 16, 2),
                pl("fav_wy_111", "Night Drive: 02 AM", 18, 3, fav = true), pl("fav_wy_222", "柔软噪点", 16, 2, fav = true),
                pl("fav_wy_333", "Neon Current", 9, 0, fav = true, author = "Mira Vale"),
                pl("fav_wy_444", "After Rain", 12, 1, fav = true, author = "Low Season"),
            )
        )
    }

    @Composable
    private fun Library(b: LocalStore.UserBundle) {
        LibraryPage(
            bundle = b, history = emptyList(), downloads = emptyList(), loggedIn = false, user = "",
            detail = null, detailSongs = emptyList(), onOpen = {}, onBack = {}, onPlay = { _, _ -> },
            onLogin = {}, onImport = {}
        )
    }

    @Test @Config(qualifiers = "w412dp-h1500dp-xhdpi")
    fun library() {
        begin("library")
        val b = bundle()
        AlbumFavoriteMarks.setMarked(rule.activity, "fav_wy_333", true)
        AlbumFavoriteMarks.setMarked(rule.activity, "fav_wy_444", true)
        page { Library(b) }
        save("app-library")
    }

    @Test @Config(qualifiers = "w412dp-h1500dp-xhdpi")
    fun libraryDark() {
        begin("libraryDark")
        val b = bundle()
        page(dark = true) { Library(b) }
        save("app-library-dark")
    }

    private val hot = mapOf("wy" to listOf("失眠飞行", "City Pop", "雨天歌单", "Mira Vale", "深夜氛围", "新碟首发"))
    private val recSongs by lazy {
        listOf(song(0, "Glass Horizon", "Mira Vale"), song(1, "Streetlight Bloom", "Low Season"), song(2, "Velvet Signal", "June Arcade"))
    }

    @Test @Config(qualifiers = "w412dp-h960dp-xhdpi")
    fun search() {
        begin("search")
        page {
            SearchPage(
                songResults = emptyList(), plResults = emptyList(), loggedIn = false, user = "",
                onLogin = {}, onSearch = { _, _, _ -> }, onPlay = {}, onOpenPl = {},
                hotKeywords = hot, searchHistory = listOf("Glass Horizon", "Streetlight Bloom", "Velvet Signal"),
                recommendedSongs = recSongs
            )
        }
        save("app-search")
    }

    @Test @Config(qualifiers = "w412dp-h1400dp-xhdpi")
    fun searchResults() {
        begin("searchResults")
        val songs = listOf(
            song(0, "Glass Horizon", "Mira Vale", "Neon Current", "a1"), song(1, "Glass Horizon (Live)", "Mira Vale", "Live 2026", "a2"),
            song(2, "Horizon Glass", "Low Season", "After Rain", "a3"), song(3, "Glass", "June Arcade", "Signals", "a4"),
            song(4, "Horizon Line", "Mira Vale", "Neon Current", "a1"), song(5, "Glasshouse", "Low Season", "After Rain", "a3")
        )
        val pls = listOf(Playlist("p1", "Glass Horizon 精选", covers[2], source = "wy", count = 32, author = "云音乐"),
            Playlist("p2", "地平线之外", covers[4], source = "wy", count = 18, author = "Mira"))
        page {
            SearchPage(
                songResults = songs, plResults = pls, artistResults = songs.take(3), albumResults = songs.take(4),
                loggedIn = false, user = "", kw = "Glass Horizon", searched = true,
                onLogin = {}, onSearch = { _, _, _ -> }, onPlay = {}, onOpenPl = {},
                songContent = LoadableContent.Available(songs), playlistContent = LoadableContent.Available(pls),
                artistContent = LoadableContent.Available(songs.take(3)), albumContent = LoadableContent.Available(songs.take(4))
            )
        }
        save("app-search-results")
    }

    @Composable
    private fun Settings(sub: String? = null, loggedIn: Boolean = true) {
        MePage(
            serverUrl = "", preferQ = "无损", loggedIn = loggedIn, user = if (loggedIn) "Amanda" else "", status = "", sleepRemain = 0L,
            onUrl = {}, onQuality = {}, onSleep = {},
            themeMode = "light", settingsSub = sub
        )
    }

    @Test @Config(qualifiers = "w412dp-h2900dp-xhdpi")
    fun settings() { begin("settings"); page { Settings() }; save("app-settings") }

    @Test @Config(qualifiers = "w412dp-h2900dp-xhdpi")
    fun settingsDark() { begin("settingsDark"); page(dark = true) { Settings() }; save("app-settings-dark") }

    /** 账号中心底部面板（设计稿 #accountSheet，去掉重复资料卡）：叠在设置页上，左右贴边、延伸到底。 */
    private fun accountSheetShot(name: String, file: String, dark: Boolean) {
        begin(name)
        page(dark = dark) {
            Settings()
            xyz.cncnlp.soundwave.ui.common.ProfileSheet(
                userName = "Amanda", avatarPath = "",
                onDismiss = {}, onChangeAvatar = {}, onRenameUser = {}, onLogoutWebDav = {}
            )
        }
        save(file)
    }

    /** 未登录：设置页顶栏与账户卡均用设计稿默认头像（墨色圆 + 纸色字形）。 */
    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun settingsLoggedOut() { begin("settingsLoggedOut"); page { Settings(loggedIn = false) }; save("app-settings-logged-out") }

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun accountSheet() = accountSheetShot("accountSheet", "app-ov-account-sheet", dark = false)

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun accountSheetDark() = accountSheetShot("accountSheetDark", "app-ov-account-sheet-dark", dark = true)

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun settingsSources() { begin("settingsSources"); page { Settings("sources") }; save("app-settings-sources") }

    private val scripts = listOf(
        Script("sc1", "LX 音源 · 主线", "全平台 · 无损优先", "2.4.1", "lx", code = "x"),
        Script("sc2", "六音源", "wy / tx / kg", "1.2.0", "six", code = "x"),
        Script("sc3", "备用音源", "需重新导入", "0.9", "", code = "", enabled = false)
    )

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun settingsPressed() {
        begin("settingsPressed")
        page { Settings() }
        repeat(3) { rule.waitForIdle(); Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() }
        rule.onNodeWithText("在线播放音质").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(500)
        save("app-settings-pressed")
    }

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun settingsQualityDialog() {
        begin("settingsQualityDialog")
        page { Settings() }
        rule.onNodeWithText("在线播放音质").performClick()
        save("app-ov-settings-quality")
    }

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun searchModeMenu() {
        begin("searchModeMenu")
        page {
            SearchPage(songResults = emptyList(), plResults = emptyList(), loggedIn = false, user = "",
                onLogin = {}, onSearch = { _, _, _ -> }, onPlay = {}, onOpenPl = {}, hotKeywords = hot)
        }
        rule.onNodeWithContentDescription("选择搜索类别").performClick()
        save("app-ov-search-mode")
    }

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun libraryActions() {
        begin("libraryActions")
        val b = bundle()
        page { Library(b) }
        rule.onNodeWithContentDescription("歌单操作").performClick()
        save("app-ov-library-add")
    }

    @Test @Config(qualifiers = "w412dp-h1800dp-xhdpi")
    fun smart() { begin("smart"); page { SmartSourcePage(scripts = scripts) }; save("app-smart-switch") }

    @Test @Config(qualifiers = "w412dp-h1800dp-xhdpi")
    fun scriptsPage() {
        begin("scriptsPage")
        page { ScriptsPage(scripts = scripts, onChange = {}, onImportCode = {}, onImportUrl = {}, onImportFile = {}, onMove = { _, _ -> }) }
        save("app-source-scripts")
    }
}
