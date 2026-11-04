package xyz.cncnlp.soundwave.playback

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * r656（设置第 7 项）：「和其他 APP 同时播放」= 音频焦点策略 + 通话打断。
 *
 * - [AudioFocusMode.PAUSE]：其他应用出声（含导航/语音短提示）时暂停，对方结束后自动继续；
 * - [AudioFocusMode.DUCK]：短提示时压低音量、对方结束后恢复；其他应用持续播放时暂停（旧版默认行为）；
 * - [AudioFocusMode.MIX]：不申请音频焦点，与其他应用同时播放；来电仍按「通话打断」暂停。
 *
 * 「通话打断」决定来电/通话造成的暂停结束后是否自动继续。通话判定只读 [AudioManager.getMode]
 * （RINGTONE / IN_CALL / IN_COMMUNICATION），不需要电话权限。
 */
enum class AudioFocusMode(val key: String, val label: String, val hint: String) {
    PAUSE("pause", "自动暂停", "其他应用出声时暂停，结束后继续"),
    DUCK("duck", "降低音量", "短提示时压低音量，其他播放时暂停"),
    MIX("mix", "同时播放", "不抢占焦点，与其他应用混音");

    companion object {
        fun of(key: String?): AudioFocusMode? = values().firstOrNull { it.key == key }
    }
}

data class AudioFocusSettings(
    val mode: AudioFocusMode = AudioFocusMode.DUCK,
    val resumeAfterCall: Boolean = true
) {
    val callLabel: String get() = if (resumeAfterCall) "自动继续" else "保持暂停"
    val summary: String get() = "${mode.label} · 通话后${callLabel}"
}

/** 与其它播放偏好同存于 `soundwave` 偏好；旧版 `allow_mix` 开关迁移为 [AudioFocusMode.MIX]。 */
object AudioFocusSettingsStore {
    const val KEY_MODE = "audio_focus_mode"
    const val KEY_CALL_RESUME = "audio_call_resume"
    private const val LEGACY_ALLOW_MIX = "allow_mix"

    fun read(prefs: SharedPreferences): AudioFocusSettings {
        val mode = AudioFocusMode.of(prefs.getString(KEY_MODE, null))
            ?: if (prefs.getBoolean(LEGACY_ALLOW_MIX, false)) AudioFocusMode.MIX else AudioFocusMode.DUCK
        return AudioFocusSettings(mode, prefs.getBoolean(KEY_CALL_RESUME, true))
    }

    fun write(prefs: SharedPreferences, settings: AudioFocusSettings) {
        prefs.edit()
            .putString(KEY_MODE, settings.mode.key)
            .putBoolean(KEY_CALL_RESUME, settings.resumeAfterCall)
            // 兼容旧读取方（备份/旧界面）：仅 MIX 视为“允许同时播放”。
            .putBoolean(LEGACY_ALLOW_MIX, settings.mode == AudioFocusMode.MIX)
            .apply()
    }
}

internal enum class FocusCommand { PAUSE, DUCK, RESTORE_VOLUME, RESUME }

internal data class FocusState(
    val pausedByFocus: Boolean = false,
    val pausedForCall: Boolean = false,
    val ducked: Boolean = false
)

internal data class FocusDecision(val commands: List<FocusCommand>, val state: FocusState)

/** 纯决策：AudioManager 焦点变化 / 通话状态 → 播放命令。JVM 单测直接覆盖。 */
internal object AudioFocusPolicy {
    const val GAIN = AudioManager.AUDIOFOCUS_GAIN
    const val LOSS = AudioManager.AUDIOFOCUS_LOSS
    const val LOSS_TRANSIENT = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    const val LOSS_TRANSIENT_CAN_DUCK = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK

    fun onFocusChange(change: Int, settings: AudioFocusSettings, playing: Boolean, inCall: Boolean, state: FocusState): FocusDecision {
        if (settings.mode == AudioFocusMode.MIX) return FocusDecision(emptyList(), state)
        return when (change) {
            LOSS -> when {
                // 少数拨号器以永久丢失抢焦点；按通话处理，避免挂断后永远停住。
                inCall && playing -> FocusDecision(listOf(FocusCommand.PAUSE), FocusState(pausedByFocus = true, pausedForCall = true))
                playing -> FocusDecision(listOf(FocusCommand.PAUSE), FocusState())
                else -> FocusDecision(emptyList(), FocusState(pausedByFocus = state.pausedByFocus, pausedForCall = state.pausedForCall))
            }
            LOSS_TRANSIENT -> if (playing) {
                FocusDecision(listOf(FocusCommand.PAUSE), FocusState(pausedByFocus = true, pausedForCall = inCall))
            } else FocusDecision(emptyList(), state)
            LOSS_TRANSIENT_CAN_DUCK -> when {
                !playing -> FocusDecision(emptyList(), state)
                inCall -> FocusDecision(listOf(FocusCommand.PAUSE), FocusState(pausedByFocus = true, pausedForCall = true))
                settings.mode == AudioFocusMode.DUCK -> FocusDecision(listOf(FocusCommand.DUCK), state.copy(ducked = true))
                else -> FocusDecision(listOf(FocusCommand.PAUSE), FocusState(pausedByFocus = true, pausedForCall = false))
            }
            GAIN -> {
                val commands = mutableListOf<FocusCommand>()
                if (state.ducked) commands += FocusCommand.RESTORE_VOLUME
                val resume = state.pausedByFocus && !playing && (!state.pausedForCall || settings.resumeAfterCall)
                if (resume) commands += FocusCommand.RESUME
                FocusDecision(commands, FocusState())
            }
            else -> FocusDecision(emptyList(), state)
        }
    }

    /** MIX 模式没有焦点回调，只能靠通话状态：来电暂停，挂断后按设置继续。 */
    fun onCallStateChange(inCall: Boolean, settings: AudioFocusSettings, playing: Boolean, state: FocusState): FocusDecision {
        if (settings.mode != AudioFocusMode.MIX) return FocusDecision(emptyList(), state)
        return if (inCall) {
            if (playing) FocusDecision(listOf(FocusCommand.PAUSE), FocusState(pausedByFocus = true, pausedForCall = true))
            else FocusDecision(emptyList(), state)
        } else {
            val resume = state.pausedForCall && !playing && settings.resumeAfterCall
            FocusDecision(if (resume) listOf(FocusCommand.RESUME) else emptyList(), FocusState())
        }
    }

    fun isCallMode(audioMode: Int): Boolean =
        audioMode == AudioManager.MODE_RINGTONE || audioMode == AudioManager.MODE_IN_CALL ||
            audioMode == AudioManager.MODE_IN_COMMUNICATION

    /** 用户主动暂停/播放会清掉“由焦点暂停”的记忆，避免对方结束后违背用户意愿自动开播。 */
    fun onUserAction(state: FocusState): FocusState = FocusState(ducked = state.ducked)

    const val DUCK_VOLUME = 0.2f
}

/** 延迟到主线程的系统回调必须在持有者释放后失效。 */
internal class AudioFocusCallbackGate {
    @Volatile private var active = true

    fun isActive(): Boolean = active

    fun close(): Boolean {
        if (!active) return false
        active = false
        return true
    }

    fun dispatch(action: () -> Unit): Boolean {
        if (!active) return false
        action()
        return true
    }
}

/**
 * PlayerService 持有的焦点控制器。Media3 的 handleAudioFocus 只有“申请并自动处理”一种策略，
 * 无法表达“自动暂停 / 同时播放 / 通话后保持暂停”，所以播放器关闭自动焦点，由这里统一申请与响应。
 */
internal class PlaybackAudioFocusController(
    context: Context,
    private val isPlaying: () -> Boolean,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    private val setVolumeScale: (Float) -> Unit
) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val callbackGate = AudioFocusCallbackGate()
    @Volatile var settings: AudioFocusSettings = AudioFocusSettings()
        private set
    private var state = FocusState()
    private var held = false
    private var request: AudioFocusRequest? = null
    private var lastInCall = false
    private var modeListener: Any? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        postIfActive { applyDecision(AudioFocusPolicy.onFocusChange(change, settings, isPlaying(), inCall(), state)) }
        if (callbackGate.isActive() && change == AudioManager.AUDIOFOCUS_LOSS) held = false
    }

    private fun postIfActive(action: () -> Unit) {
        if (!callbackGate.isActive()) return
        handler.post { callbackGate.dispatch(action) }
    }

    private fun inCall(): Boolean = try { AudioFocusPolicy.isCallMode(audioManager.mode) } catch (_: Exception) { false }

    private fun applyDecision(decision: FocusDecision) {
        if (!callbackGate.isActive()) return
        state = decision.state
        decision.commands.forEach { command ->
            when (command) {
                FocusCommand.PAUSE -> pause()
                FocusCommand.DUCK -> setVolumeScale(AudioFocusPolicy.DUCK_VOLUME)
                FocusCommand.RESTORE_VOLUME -> setVolumeScale(1f)
                FocusCommand.RESUME -> resume()
            }
        }
    }

    fun update(next: AudioFocusSettings) {
        if (!callbackGate.isActive()) return
        val previous = settings
        settings = next
        if (previous.mode != next.mode) {
            if (state.ducked) setVolumeScale(1f)
            state = FocusState()
            if (next.mode == AudioFocusMode.MIX) abandon()
            else if (isPlaying()) request()
        }
        updateCallWatch()
    }

    /** 开播前调用；返回 false 表示系统拒绝（例如通话中），调用方随后会被暂停。重复申请同一请求是幂等的。 */
    fun request(): Boolean {
        if (!callbackGate.isActive()) return false
        updateCallWatch()
        if (settings.mode == AudioFocusMode.MIX) return true
        val granted = try {
            val req = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                // 收到 CAN_DUCK 回调后由本控制器决定压低音量（DUCK）还是暂停（PAUSE）。
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(listener, handler)
                .build().also { request = it }
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) { true }
        held = granted
        if (granted) {
            // 用户（或恢复逻辑）主动开播：清掉焦点暂停记忆并恢复音量。
            if (state.ducked) setVolumeScale(1f)
            state = FocusState()
        } else {
            // 通话中等系统拒绝：不在通话上叠放音乐。
            postIfActive { pause() }
        }
        return granted
    }

    fun onUserPause() {
        if (callbackGate.isActive()) state = AudioFocusPolicy.onUserAction(state)
    }

    fun abandon() {
        if (!held) return
        held = false
        try { request?.let { audioManager.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
    }

    /** MIX 模式需要独立监听通话；API 31+ 用模式回调，更早系统在播放期间轻量轮询。 */
    private val pollCall = object : Runnable {
        override fun run() {
            if (!callbackGate.isActive() || settings.mode != AudioFocusMode.MIX) return
            onCallMaybeChanged()
            if (callbackGate.isActive() && (isPlaying() || state.pausedForCall)) handler.postDelayed(this, 1500L)
        }
    }

    private fun onCallMaybeChanged() {
        if (!callbackGate.isActive()) return
        val now = inCall()
        if (now == lastInCall) return
        lastInCall = now
        applyDecision(AudioFocusPolicy.onCallStateChange(now, settings, isPlaying(), state))
    }

    private fun updateCallWatch() {
        if (!callbackGate.isActive()) return
        val needWatch = settings.mode == AudioFocusMode.MIX
        if (Build.VERSION.SDK_INT >= 31) {
            if (needWatch && modeListener == null) {
                val l = AudioManager.OnModeChangedListener { postIfActive { onCallMaybeChanged() } }
                try { audioManager.addOnModeChangedListener({ r -> postIfActive { r.run() } }, l); modeListener = l } catch (_: Exception) {}
            } else if (!needWatch && modeListener != null) {
                try { audioManager.removeOnModeChangedListener(modeListener as AudioManager.OnModeChangedListener) } catch (_: Exception) {}
                modeListener = null
            }
        } else {
            handler.removeCallbacks(pollCall)
            if (needWatch) handler.postDelayed(pollCall, 1500L)
        }
    }

    fun release() {
        if (!callbackGate.close()) return
        abandon()
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= 31) {
            try { (modeListener as? AudioManager.OnModeChangedListener)?.let { audioManager.removeOnModeChangedListener(it) } } catch (_: Exception) {}
            modeListener = null
        }
    }
}
