package xyz.cncnlp.soundwave

import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import java.time.Duration
import java.io.File
import android.graphics.Bitmap

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Round5FloatingTest {
    private val ctx get()=RuntimeEnvironment.getApplication()
    @After fun cleanup() { LyricFloat.hide(); ShadowSettings.setCanDrawOverlays(false) }
    @Test fun notificationCyclesOpenLockCloseAndReopenUnlocked() {
        LyricFloat.setAppForeground(ctx,true)
        LyricFloat.hide()
        ShadowSettings.setCanDrawOverlays(false)
        assertTrue(LyricFloat.handleNotifWord(ctx).contains("权限"))
        assertFalse(LyricFloat.isUserEnabled(ctx))
        ShadowSettings.setCanDrawOverlays(true)
        LyricFloat.setLocked(ctx,true,false) // old/restored lock must not leak into a new open
        LyricFloat.handleNotifWord(ctx,"sample")
        assertTrue(LyricFloat.isUserEnabled(ctx));assertFalse(LyricFloat.isLocked(ctx));assertFalse(LyricFloat.isVisible())
        LyricFloat.handleNotifWord(ctx)
        assertTrue(LyricFloat.isUserEnabled(ctx));assertTrue(LyricFloat.isLocked(ctx))
        LyricFloat.setLocked(ctx,false,false) // long-press uses the same persisted lock and refresh path
        assertFalse(LyricFloat.isLocked(ctx));assertTrue(LyricFloat.isUserEnabled(ctx))
        LyricFloat.handleNotifWord(ctx);assertTrue(LyricFloat.isLocked(ctx))
        LyricFloat.handleNotifWord(ctx)
        assertFalse(LyricFloat.isUserEnabled(ctx));assertFalse(LyricFloat.isLocked(ctx))
        LyricFloat.handleNotifWord(ctx)
        assertTrue(LyricFloat.isUserEnabled(ctx));assertFalse(LyricFloat.isLocked(ctx))
    }
    @Test fun lockedTouchRequiresLongPressNeverTapDragOrCancel() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val view=View(controller.get());controller.get().setContentView(view)
        var unlocks=0
        val listener=LyricFloatUnlockTouch(view) { unlocks++ }
        fun event(action:Int,x:Float=10f,y:Float=10f) {
            val e=MotionEvent.obtain(0,0,action,x,y,0);assertTrue(listener.onTouch(view,e));e.recycle()
        }
        fun delay()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout()+50L))
        event(MotionEvent.ACTION_DOWN);event(MotionEvent.ACTION_UP);delay();assertEquals(0,unlocks)
        event(MotionEvent.ACTION_DOWN);event(MotionEvent.ACTION_MOVE,200f,200f);delay();assertEquals(0,unlocks)
        event(MotionEvent.ACTION_DOWN);event(MotionEvent.ACTION_CANCEL);delay();assertEquals(0,unlocks)
        event(MotionEvent.ACTION_DOWN);delay();assertEquals(1,unlocks)
        event(MotionEvent.ACTION_UP);delay();assertEquals(1,unlocks)
        controller.pause().stop().destroy()
    }
    @Test fun menuHintNoLongerOffersBlur() { assertEquals("字号 · 行高 · 偏移",playerMenuSecondarySubtitle("menu_lyrics",0)) }
    @Test fun glyphStatesAreDistinctAndBadgesAreSmall() {
        val off=NotificationLyricGlyph.render(false,false)
        val on=NotificationLyricGlyph.render(true,false)
        val lock=NotificationLyricGlyph.render(true,true)
        assertFalse(off.sameAs(on));assertFalse(on.sameAs(lock))
        assertTrue(NotificationLyricGlyph.lockBodyRect.width()<26f)
        System.getenv("SW_SCREENSHOT_DIR")?.let { dir ->
            listOf("off" to off,"on-check" to on,"locked" to lock).forEach { (name,bmp)->
                File(dir,"notification-lyrics-$name.png").apply { parentFile.mkdirs() }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        }
    }
}
