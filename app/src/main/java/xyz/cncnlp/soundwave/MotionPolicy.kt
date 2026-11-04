package xyz.cncnlp.soundwave

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.composed

/**
 * 统一动效预算与低性能降级（任务 7）。
 *
 * 规则：
 * 1. 交互反馈 ≤ 200ms：优先 opacity/scale/translation；
 * 2. 不持续动画大面积 blur；非播放、后台、离屏时停止循环动画；
 * 3. 系统级减少动效或应用内开关开启时，关闭装饰性无限动画。
 *
 * 任何装饰性无限动画都必须先查询 [rememberReduceMotion] 与播放/可见状态，
 * 禁止把无限循环动画直接挂在 Composable 上无条件执行。
 */
object MotionPolicy {
    private const val PREFS_NAME = "soundwave_prefs"
    private const val PREF_REDUCE_MOTION = "reduce_motion"

    /** 系统动画倍率为 0 表示用户已要求系统减少动效。 */
    internal fun systemReduceMotion(ctx: Context): Boolean = try {
        Settings.Global.getFloat(
            ctx.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    } catch (_: Exception) {
        false
    }

    /** 应用内“减少动效”开关。 */
    fun appReduceMotion(ctx: Context): Boolean = try {
        ctx.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_REDUCE_MOTION, false)
    } catch (_: Exception) {
        false
    }

    /**
     * r657 修复：无限循环的装饰动效（播放器光尘 / 背景漂移 / 扫描光球）是否冻结在首帧。
     * Robolectric 单测（测试时钟自动推进时，无限帧循环会让 waitForIdle 永远等不到空闲）与「简洁动效」下冻结；真机正常播放。
     */
    val decorativeLoopsFrozen: Boolean by lazy { android.os.Build.FINGERPRINT == "robolectric" }

    fun allowDecorativeLoops(reduceMotion: Boolean): Boolean = !reduceMotion && !decorativeLoopsFrozen

    internal fun shouldReduceMotion(systemEnabled: Boolean, appEnabled: Boolean): Boolean =
        systemEnabled || appEnabled

    internal fun isReduceMotionEnabled(ctx: Context): Boolean =
        shouldReduceMotion(systemReduceMotion(ctx), appReduceMotion(ctx))

    fun setAppReduceMotion(ctx: Context, on: Boolean) {
        try {
            ctx.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_REDUCE_MOTION, on)
                .apply()
        } catch (_: Exception) {
            // Keep motion policy fail-safe without making settings changes crash the app.
        }
    }

    // One observer pair per application, not one pair per Text/marquee. The retained
    // application context is process-scoped; no Activity or Composition is retained.
    private var reduceMotionObservation: ReduceMotionObservation? = null

    @Synchronized
    private fun sharedReduceMotionObservation(context: Context): ReduceMotionObservation {
        val current = reduceMotionObservation
        if (current != null && current.context === context) return current
        return ReduceMotionObservation(context).also { reduceMotionObservation = it }
    }

    private class ReduceMotionObservation(val context: Context) {
        val state = mutableStateOf(isReduceMotionEnabled(context))
        private var subscribers = 0
        private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PREF_REDUCE_MOTION) state.value = isReduceMotionEnabled(context)
        }
        private val systemSettingObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = isReduceMotionEnabled(context)
            }
        }

        // DisposableEffect retain/release and observer callbacks run on the UI thread.
        fun retain() {
            if (subscribers++ != 0) return
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, systemSettingObserver
            )
            // Catch changes made while no compositions were subscribed.
            state.value = isReduceMotionEnabled(context)
        }

        fun release() {
            if (--subscribers != 0) return
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            context.contentResolver.unregisterContentObserver(systemSettingObserver)
        }
    }

    /**
     * 组合系统与应用偏好；所有调用方共享实时状态与一对外部监听。
     * r657 第三轮 b（卡顿）：宿主（[ProvideMotionPolicy]）观察一次并经 ui.common.LocalReduceMotion 下发；
     * 树内调用直接读 CompositionLocal，不再每个 Text / 列表行各自 remember + DisposableEffect 订阅。
     */
    @Composable
    fun rememberReduceMotion(): Boolean {
        xyz.cncnlp.soundwave.ui.common.LocalReduceMotion.current?.let { return it }
        return observeReduceMotion()
    }

    @Composable
    internal fun observeReduceMotion(): Boolean {
        val context = LocalContext.current.applicationContext
        val observation = remember(context) { sharedReduceMotionObservation(context) }
        DisposableEffect(observation) {
            observation.retain()
            onDispose { observation.release() }
        }
        return observation.state.value
    }

    /** 装饰性动画仅在未减少动效、正在播放且页面可见时运行。 */
    fun allowDecorative(isPlaying: Boolean, visible: Boolean = true, reduce: Boolean = false): Boolean =
        !reduce && isPlaying && visible

    /** 减少动效时使用静态目标，而不是无限循环 spec。 */
    fun decorativeCycle(reduce: Boolean): Boolean = !reduce
}

/**
 * 装饰性循环（跑马灯、律动条、唱片转动等）是否应运行：被不透明页面完全遮住的常驻层（首页在 Tab / 详情 / 全屏播放器下方）
 * 提供 false，循环停止，不再每帧失效重绘。
 */
val LocalDecorativeActive = androidx.compose.runtime.compositionLocalOf { true }

/** 在 Activity 根部调用一次：一对系统 / 偏好监听，整棵树共享。 */
@Composable
fun ProvideMotionPolicy(content: @Composable () -> Unit) {
    val reduce = MotionPolicy.observeReduceMotion()
    androidx.compose.runtime.CompositionLocalProvider(xyz.cncnlp.soundwave.ui.common.LocalReduceMotion provides reduce, content = content)
}

/** 跑马灯遵循实时减少动效设置与可见性；截图 / 单测同样停在首帧。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun androidx.compose.ui.Modifier.loopMarquee(): androidx.compose.ui.Modifier =
    this.composed {
        val reduce = MotionPolicy.rememberReduceMotion()
        if (MotionPolicy.decorativeLoopsFrozen || reduce || !LocalDecorativeActive.current) this else this.basicMarquee()
    }
