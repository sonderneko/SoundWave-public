package xyz.cncnlp.soundwave.perf

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Display
import java.lang.ref.WeakReference

/**
 * r656-G：前台窗口请求屏幕支持的最高刷新率（≤144Hz），让 Compose 滚动/动画跟随 90/120/144Hz。
 *
 * - 只按“当前分辨率”挑选最高刷新率的 Display.Mode，不切换分辨率（避免黑屏/闪烁）。
 * - 省电模式下撤销偏好（preferredDisplayModeId = 0），交还系统自适应刷新率。
 * - 通过 ActivityLifecycleCallbacks 在每次 onResume 重新应用，因此 MainActivity 无需改动；
 *   窗口属性只作用于本应用前台窗口，回到后台系统自动恢复其它应用的刷新率。
 */
internal object HighRefreshRate : Application.ActivityLifecycleCallbacks {
    /** 用户需求：最高到 144Hz；更高的 165/240Hz 面板不必追（功耗高、收益小）。 */
    internal const val MAX_REFRESH_HZ = 144.5f

    private var resumed: WeakReference<Activity>? = null
    @Volatile private var installed = false

    /** 一个显示模式的最小描述，便于在 JVM 单测中验证挑选逻辑。 */
    internal data class ModeSpec(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    /**
     * 在与当前模式同分辨率的模式中挑出刷新率最高者（不超过 [maxHz]；若全部超过则取最高者）。
     * 即使当前已是最高档也显式钉住，避免 OEM“智能刷新率”在滚动间隙把本应用降回 60Hz。
     * 没有同分辨率模式时返回 null（不修改窗口属性）。
     */
    internal fun pickHighestMode(current: ModeSpec, supported: List<ModeSpec>, maxHz: Float = MAX_REFRESH_HZ): ModeSpec? {
        val sameResolution = supported.filter { it.width == current.width && it.height == current.height }
        val capped = sameResolution.filter { it.refreshRate <= maxHz }
        return capped.ifEmpty { sameResolution }.maxByOrNull { it.refreshRate }
    }

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    resumed?.get()?.let { activity -> activity.runOnUiThread { apply(activity) } }
                }
            }
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
        } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    fun apply(activity: Activity) {
        try {
            val window = activity.window ?: return
            val powerSave = (activity.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
            val display: Display = (if (Build.VERSION.SDK_INT >= 30) activity.display else null)
                ?: activity.windowManager.defaultDisplay ?: return
            val attrs = window.attributes
            val targetId = if (powerSave) 0 else {
                val current = display.mode
                val pick = pickHighestMode(
                    ModeSpec(current.modeId, current.physicalWidth, current.physicalHeight, current.refreshRate),
                    display.supportedModes.map { ModeSpec(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
                )
                pick?.id ?: 0
            }
            if (attrs.preferredDisplayModeId != targetId) {
                attrs.preferredDisplayModeId = targetId
                // 部分 OEM（老 ColorOS/MIUI）只看 preferredRefreshRate；同时写入，取同一模式的刷新率。
                attrs.preferredRefreshRate = if (targetId == 0) 0f
                    else display.supportedModes.firstOrNull { it.modeId == targetId }?.refreshRate ?: 0f
                window.attributes = attrs
            }
        } catch (_: Throwable) {
            // 任何 OEM 显示 API 异常都不能影响启动。
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        apply(activity)
        AppForegroundHooks.onActivityCreated(activity)
    }
    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        apply(activity)
    }
    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
        AppForegroundHooks.onActivityPaused(activity)
    }
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
