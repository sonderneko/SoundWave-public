package xyz.cncnlp.soundwave

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.support.v4.media.session.MediaSessionCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Stage12NotificationPresenterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun pending(name: String, code: Int) = PendingIntent.getService(context, code, Intent(name).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun controls() = NotificationControls(pending("content", 101), pending("like", 102), pending("previous", 103), pending("toggle", 104), pending("next", 105), pending("lyric", 106))
    private fun state() = NotificationPresentation("stage12", "曲名", "歌手", false, 1000, 500, false, false, null, Color.RED)
    @Test fun immutableSnapshotDrivesPlayingLyricsAndExactControls() {
        val presenter = NotificationPresenter(context)
        val c = controls()
        for (playing in listOf(false, true)) for (lyric in 0..2) {
            val state = state().copy(playing = playing, lyricEnabled = lyric > 0, lyricLocked = lyric == 2)
            val n = presenter.build(state, c, null)
            assertEquals("曲名", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("歌手", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(if (playing) "暂停" else "播放", n.actions[2].title)
            assertEquals(listOf("开启悬浮歌词", "锁定悬浮歌词", "关闭悬浮歌词")[lyric], n.actions[4].title)
            assertEquals(listOf(c.like, c.previous, c.playPause, c.next, c.lyric), n.actions.map { it.actionIntent })
            assertEquals(c.content, n.contentIntent)
            assertEquals(Color.RED, n.color)
            assertEquals(R.drawable.ic_notif_play_v12, n.smallIcon.resId)
            assertArrayEquals(intArrayOf(1, 2, 3), n.extras.getIntArray("android.compactActions"))
            assertEquals("toggle", shadowOf(n.actions[2].actionIntent).savedIntent.action)
        }
    }
    @Test fun progressBoundsAndUnknownDurationKeepOriginalSemantics() {
        val p = NotificationPresenter(context); val c = controls()
        for (position in listOf(-50L, 0L, 500L, 2000L)) {
            val n = p.build(state().copy(positionMs = position), c, null)
            assertEquals(1000, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertEquals(position.coerceIn(0L, 1000L).toInt(), n.extras.getInt(Notification.EXTRA_PROGRESS))
        }
        for (duration in listOf(0L, -1L)) {
            val n = p.build(state().copy(durationMs = duration), c, null)
            // Android populates progress extras with zeros even without setProgress; absence is not the contract.
            val baseline = androidx.core.app.NotificationCompat.Builder(context, "stage12").build()
            assertEquals(baseline.extras.getInt(Notification.EXTRA_PROGRESS_MAX), n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertEquals(0, n.extras.getInt(Notification.EXTRA_PROGRESS))
            assertFalse(n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        }
    }
    @Test fun mediaSessionTokenIsSuppliedNotCreatedOrOwned() {
        val session = MediaSessionCompat(context, "stage12-presentation")
        try {
            val n = NotificationPresenter(context).build(state(), controls(), session.sessionToken)
            assertEquals(session.sessionToken.token, n.extras.getParcelable<android.os.Parcelable>("android.mediaSession"))
            assertArrayEquals(intArrayOf(1, 2, 3), n.extras.getIntArray("android.compactActions"))
            // Presenter must not release the caller-owned session or change its active state.
            session.isActive = true
            assertTrue(session.isActive)
        } finally { session.release() }
    }
    @Test fun concurrentRepeatedBuildsShareArtworkWithoutRecyclingInputs() {
        val p = NotificationPresenter(context); val c = controls()
        val cover = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 16).map { Callable {
                val n = p.build(state().copy(cover = cover, lyricEnabled = true), c, null)
                n.getLargeIcon().loadDrawable(context) as android.graphics.drawable.BitmapDrawable
            } }
            val images = pool.invokeAll(tasks, 20, TimeUnit.SECONDS).map { it.get().bitmap }
            assertEquals(16, images.size)
            assertTrue(images.all { it.sameAs(images.first()) })
            assertFalse(cover.isRecycled)
            assertEquals(Color.BLUE, cover.getPixel(450, 450))
        } finally { pool.shutdownNow() }
    }
}
