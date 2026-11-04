package xyz.cncnlp.soundwave.perf

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.util.Base64
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.CacheManager
import xyz.cncnlp.soundwave.LocalStore
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.ui.player.CoverToneCache
import xyz.cncnlp.soundwave.ui.player.sampleCoverBackground

/** r656-G：性能基础设施的可验证行为（刷新率挑选、合并写、封面模型记忆、后台取色、按压令牌）。 */
@RunWith(RobolectricTestRunner::class)
// This tests the writer, not app startup. SoundWaveApplication starts provenance scans
// which deliberately flush pending writes; the captured diagnostic stack confirms that
// external flush can interrupt this synthetic 100-save coalescing window.
// Keep both serialization bounds and latest-value (fav99) assertions unchanged.
@Config(sdk = [34], application = Application::class)
class R656PerfInfrastructureTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        LocalStore.deferMainThreadSaves = false
        LocalStore.isMainThread = { Looper.myLooper() == Looper.getMainLooper() }
        LocalStore.flushPendingWrites()
        LocalStore.deferredWriter = defaultWriter
        CoverToneCache.clearForTest()
    }

    private val defaultWriter = LocalStore.deferredWriter

    @Test
    fun `highest refresh mode at current resolution is chosen and capped at 144Hz`() {
        val current = HighRefreshRate.ModeSpec(1, 1080, 2400, 60f)
        val modes = listOf(
            current,
            HighRefreshRate.ModeSpec(2, 1080, 2400, 90f),
            HighRefreshRate.ModeSpec(3, 1080, 2400, 120f),
            HighRefreshRate.ModeSpec(4, 1080, 2400, 144f),
            HighRefreshRate.ModeSpec(5, 1440, 3200, 165f), // 其他分辨率：绝不切换分辨率
            HighRefreshRate.ModeSpec(6, 1080, 2400, 165f)  // 超过 144Hz 上限
        )
        assertEquals(4, HighRefreshRate.pickHighestMode(current, modes)?.id)
        // 只有 60Hz 的屏幕：钉住当前模式（no-op）
        assertEquals(1, HighRefreshRate.pickHighestMode(current, listOf(current))?.id)
        // 同分辨率全部超过上限时取最高者，而不是放弃
        val only165 = listOf(HighRefreshRate.ModeSpec(7, 1080, 2400, 165f))
        assertEquals(7, HighRefreshRate.pickHighestMode(current, only165)?.id)
        // 没有同分辨率模式：不改窗口
        assertNull(HighRefreshRate.pickHighestMode(current, listOf(HighRefreshRate.ModeSpec(8, 720, 1600, 120f))))
    }

    @Test
    fun `deferred writer coalesces a burst into one serialization and flush is read-after-write consistent`() {
        val prefs = context.getSharedPreferences("r656g_deferred_test", 0)
        val writer = DeferredPrefsWriter(delayMs = 60_000L)
        repeat(100) { i -> writer.enqueue(prefs, "k") { "v$i" } }
        assertTrue(writer.hasPending(prefs))
        assertEquals(0, writer.serializations)
        writer.flush(prefs)
        assertEquals(1, writer.serializations)
        assertEquals("v99", prefs.getString("k", null))
        writer.flush(prefs) // nothing pending
        assertEquals(1, writer.serializations)
    }

    @Test
    fun `LocalStore main-thread saves are deferred but every LocalStore read sees the latest bundle`() {
        LocalStore.isMainThread = { true }
        LocalStore.deferMainThreadSaves = true
        // 合并窗口拉长到 60s：断言只关心“合并 + 读时 flush”，不依赖 100 次保存能否在 250ms 内跑完（qemu/慢 CI 会超时）。
        LocalStore.deferredWriter = DeferredPrefsWriter(delayMs = 60_000L)
        val before = LocalStore.deferredWriter.serializations
        repeat(100) { i ->
            val bundle = LocalStore.UserBundle()
            bundle.favs.add(Song("fav$i", "Song $i", "Artist", "wy"))
            LocalStore.save(context, bundle)
        }
        // 100 次保存在合并窗口内：尚未序列化
        assertTrue(LocalStore.deferredWriter.serializations - before <= 1)
        val loaded = LocalStore.load(context)
        assertEquals(listOf("fav99"), loaded.favs.map { it.id })
        assertTrue("≤2 serializations for 100 saves", LocalStore.deferredWriter.serializations - before <= 2)
    }

    @Test
    fun `LocalStore saves stay synchronous when deferral is off (workers, tests)`() {
        LocalStore.deferMainThreadSaves = false
        val bundle = LocalStore.UserBundle()
        bundle.favs.add(Song("sync-fav", "Sync", "Artist", "wy"))
        LocalStore.save(context, bundle)
        assertFalse(LocalStore.deferredWriter.hasPending(LocalStore.prefs(context)))
        val raw = context.getSharedPreferences("soundwave_userdata", 0).getString("user_data_v1", null).orEmpty()
        assertTrue(raw.contains("sync-fav"))
    }

    @Test
    fun `coverModel memoizes resolution so repeated recompositions do no disk reads`() {
        CacheManager.resetCoverModelCacheForTest()
        val url = "https://y.gtimg.cn/music/photo_new/T002R300x300M000r656g.jpg"
        val first = CacheManager.coverModel(context, url)
        repeat(1000) { assertEquals(first, CacheManager.coverModel(context, url)) }
        assertTrue("header reads=${CacheManager.coverModelHeaderReads}", CacheManager.coverModelHeaderReads <= 1)

        // 索引建好后：未命中直接返回 URL，不再读文件头
        CacheManager.resetCoverModelCacheForTest()
        CacheManager.buildCoverIndex(context)
        repeat(1000) { CacheManager.coverModel(context, "$url?n=2") }
        assertEquals(0L, CacheManager.coverModelHeaderReads)
    }

    @Test
    fun `data url covers are decoded once per session`() {
        CacheManager.resetCoverModelCacheForTest()
        val jpeg = ByteArray(256) { 0x11 }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        val url = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val model = CacheManager.coverModel(context, url)
        assertTrue(model is File)
        (model as File).delete() // 第二次调用若再落盘就会重新创建文件
        repeat(50) { assertEquals(model, CacheManager.coverModel(context, url)) }
        assertFalse(model.exists())
    }

    @Test
    fun `background cover tones are computed once per cover and software drawables still sample synchronously`() = runBlocking {
        CoverToneCache.clearForTest()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 64) for (x in 0 until 64) setPixel(x, y, if (x < 32) AndroidColor.rgb(200, 40, 40) else AndroidColor.rgb(30, 90, 200))
        }
        val drawable = BitmapDrawable(context.resources, bitmap)
        var loads = 0
        CoverToneCache.loader = { _, _ -> loads++; drawable }
        val cover = "https://example.invalid/cover-r656g.jpg"
        val first = CoverToneCache.tonesFor(context, cover)
        val second = CoverToneCache.tonesFor(context, cover)
        assertNotNull(first)
        assertEquals(first, second)
        assertEquals(1, CoverToneCache.computations)
        assertEquals(1, loads)
        assertEquals(first, CoverToneCache.cached(cover))
        assertNotNull(sampleCoverBackground(drawable))
    }

    @Test
    fun `press scale target respects reduce motion`() {
        assertEquals(PressMotion.SCALE_DEFAULT, PressMotion.target(pressed = true, scale = PressMotion.SCALE_DEFAULT, reduceMotion = false), 0f)
        assertEquals(1f, PressMotion.target(pressed = true, scale = PressMotion.SCALE_DEFAULT, reduceMotion = true), 0f)
        assertEquals(1f, PressMotion.target(pressed = false, scale = PressMotion.SCALE_ICON, reduceMotion = false), 0f)
        assertTrue(PressMotion.SCALE_ICON < PressMotion.SCALE_DEFAULT && PressMotion.SCALE_DEFAULT < PressMotion.SCALE_ROW)
    }
}
