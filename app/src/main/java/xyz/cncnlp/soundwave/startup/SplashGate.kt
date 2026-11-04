package xyz.cncnlp.soundwave.startup

import android.os.SystemClock
import android.view.ViewTreeObserver
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ACCEPT6 启动页保持策略（纯判定，单测钉死）：内容就绪即放行；未就绪也只留到
 * [MAX_HOLD_MS]。启动页硬上限 1.5s 是用户裁定（2026-11-04）——到时无条件退场，
 * 由应用自身的加载态接棒，绝不把用户堵在启动页外，也不做任何人为延迟。
 */
internal object SplashGatePolicy {
    const val MAX_HOLD_MS: Long = 1_500L

    fun shouldKeepOnScreen(ready: Boolean, elapsedMs: Long): Boolean =
        !ready && elapsedMs < MAX_HOLD_MS
}

/**
 * 启动页闸门。[install] 必须在 Activity.onCreate 里、super 与 setContent 之前调用。
 * 退出时机 = 首帧就绪（[releaseOnFirstContentFrame]）或 1.5s 保险丝，先到先退：
 * keep 条件由 SplashScreen 库逐帧评估，条件里的 elapsed 项即保险丝本体，
 * 不依赖额外计时器回调（主线程被初始化占用时同样按帧龄放行）。
 */
internal class SplashGate private constructor(
    private val ready: AtomicBoolean,
    private val installedAtMs: Long,
) {
    fun release() {
        ready.set(true)
    }

    /** 内容首帧绘出（decorView 首次 onPreDraw）即放行；监听器一次性自摘。 */
    fun releaseOnFirstContentFrame(window: Window) {
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (decor.viewTreeObserver.isAlive) {
                    decor.viewTreeObserver.removeOnPreDrawListener(this)
                }
                release()
                return true
            }
        })
    }

    companion object {
        private const val EXIT_FADE_MS = 200L

        fun install(activity: ComponentActivity): SplashGate {
            val gate = SplashGate(AtomicBoolean(false), SystemClock.uptimeMillis())
            val splash = activity.installSplashScreen()
            splash.setKeepOnScreenCondition {
                SplashGatePolicy.shouldKeepOnScreen(
                    ready = gate.ready.get(),
                    elapsedMs = SystemClock.uptimeMillis() - gate.installedAtMs,
                )
            }
            splash.setOnExitAnimationListener { provider ->
                provider.view.animate()
                    .alpha(0f)
                    .setDuration(EXIT_FADE_MS)
                    .withEndAction { provider.remove() }
                    .start()
            }
            return gate
        }
    }
}
