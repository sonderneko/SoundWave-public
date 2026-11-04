package xyz.cncnlp.soundwave

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** A locked overlay must retain a bounded long-press target, never unlock on a tap or drag. */
internal class LyricFloatUnlockTouch(private val host: View, private val unlock: () -> Unit) : View.OnTouchListener {
    private var armed = false
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val fire = Runnable { if (armed) { armed = false; unlock() } }
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                host.removeCallbacks(fire)
                downX = event.rawX; downY = event.rawY; armed = true
                host.postDelayed(fire, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.rawX-downX)+abs(event.rawY-downY)>slop) cancel()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancel()
        }
        return true
    }
    private fun cancel() { armed = false; host.removeCallbacks(fire) }
}
