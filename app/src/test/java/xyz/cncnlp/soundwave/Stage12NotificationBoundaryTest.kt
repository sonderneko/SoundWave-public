package xyz.cncnlp.soundwave

import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Stage12NotificationBoundaryTest {
    private fun service() = Robolectric.buildService(PlayerService::class.java).get()
    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.single { it.name == name }
        method.isAccessible = true
        return method.invoke(target, *args)
    }
    // Baseline invokes the real old service methods; after extraction invokes the same renderer role.
    private fun artwork(s: PlayerService): Any = runCatching {
        invoke(s, "getNotificationPresenter")!!
    }.getOrElse { s }
    private fun bitmap(color: Int) = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun save(name: String, b: Bitmap) {
        val dir = System.getenv("SW_STAGE12_IMAGES") ?: return
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun coverBadgeCachesByIdentityWithoutChangingSource() {
        val s = service(); val art = artwork(s); val cover = bitmap(Color.BLUE)
        val first = invoke(art, "coverWithAppBadge", cover) as Bitmap
        assertNotSame(cover, first)
        assertSame(first, invoke(art, "coverWithAppBadge", cover))
        assertEquals(Color.BLUE, cover.getPixel(450, 450))
        assertEquals(Color.BLUE, first.getPixel(20, 20))
        val replacement = bitmap(Color.RED)
        assertNotSame(first, invoke(art, "coverWithAppBadge", replacement))
        assertFalse(cover.isRecycled); assertFalse(first.isRecycled)
        save("badged-blue", first)
    }
    @Test fun noCoverUsesApplicationIconAndRetriesRemainBoundedToOneEntry() {
        val art = artwork(service())
        val icon = invoke(art, "appIconBitmap") as Bitmap
        val fallback = invoke(art, "coverWithAppBadge", null) as Bitmap
        assertEquals(icon.width, fallback.width); assertEquals(icon.height, fallback.height)
        assertTrue(icon.sameAs(fallback))
        assertSame(fallback, invoke(art, "coverWithAppBadge", null))
        save("fallback", fallback)
    }
    @Test fun lyricGlyphPreservesAllStatesAndCachesEachState() {
        val art = artwork(service())
        for (enabled in listOf(false, true)) for (locked in listOf(false, true)) {
            val b = invoke(art, "lyricWordBitmap", enabled, locked) as Bitmap
            assertSame(b, invoke(art, "lyricWordBitmap", enabled, locked))
            assertEquals(112, b.width); assertEquals(112, b.height)
            assertTrue(NotificationLyricGlyph.render(enabled, locked).sameAs(b))
            save("lyric-$enabled-$locked", b)
        }
    }
    @Test fun serviceAdapterKeepsActionsFlagsAndFallbackText() {
        val s = service()
        val n = invoke(s, "buildNotification", "", "", bitmap(Color.BLUE)) as Notification
        assertEquals("SoundWave", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(" ", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("喜欢", "上一首", "播放", "下一首", "开启悬浮歌词"), n.actions.map { it.title.toString() })
        assertEquals(5, n.actions.size)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals(0, n.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals(0, n.flags and Notification.FLAG_ONGOING_EVENT)
        assertArrayEquals(intArrayOf(1, 2, 3), n.extras.getIntArray("android.compactActions"))
        assertEquals("music_playback_v12", n.channelId)
        assertNotNull(n.contentIntent)
        for (action in n.actions) assertNotNull(action.actionIntent)
        assertEquals(listOf(PlayerService.ACTION_LIKE, PlayerService.ACTION_PREV, PlayerService.ACTION_RESUME, PlayerService.ACTION_NEXT, PlayerService.ACTION_LYRIC_FLOAT), n.actions.map { shadowOf(it.actionIntent).savedIntent.action })
        for (action in n.actions) {
            val intent = shadowOf(action.actionIntent).savedIntent
            assertEquals(PlayerService::class.java.name, intent.component!!.className)
            assertTrue(intent.hasExtra(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.INPUT_OWNER))
            assertTrue(intent.hasExtra(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.INPUT_SESSION_GENERATION))
        }
        assertEquals(MainActivity::class.java.name, shadowOf(n.contentIntent).savedIntent.component!!.className)
        val large = (n.getLargeIcon().loadDrawable(s) as BitmapDrawable).bitmap
        save("service-large-icon", large)
    }
    @Test fun separateServicesDoNotShareArtworkCaches() {
        val cover = bitmap(Color.GREEN)
        val a = invoke(artwork(service()), "coverWithAppBadge", cover) as Bitmap
        val b = invoke(artwork(service()), "coverWithAppBadge", cover) as Bitmap
        assertNotSame(a, b); assertTrue(a.sameAs(b))
    }
}
