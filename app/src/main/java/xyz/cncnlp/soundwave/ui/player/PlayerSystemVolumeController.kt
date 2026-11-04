package xyz.cncnlp.soundwave.ui.player

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/** Narrow boundary around Android's music-stream volume operations. */
internal interface PlayerSystemVolumeGateway {
    fun maxMusicVolume(): Int
    fun currentMusicVolume(): Int
    fun writeMusicVolume(step: Int)
}

/** Owns system music-volume reads, normalized UI values, discrete mapping, and writes. */
internal class PlayerSystemVolumeController(private val gateway: PlayerSystemVolumeGateway) {
    private val maxMusicVolume = gateway.maxMusicVolume().coerceAtLeast(1)

    fun initialVolumeLevel(): Float = PlayerSystemVolumePolicy.initialLevel(
        currentStep = gateway.currentMusicVolume(),
        maxStep = maxMusicVolume
    )

    /** Returns the continuous UI value while writing its nearest supported system step. */
    fun setVolume(value: Float): Float {
        val normalized = PlayerSystemVolumePolicy.normalize(value)
        gateway.writeMusicVolume(PlayerSystemVolumePolicy.toStep(normalized, maxMusicVolume))
        return normalized
    }

    companion object {
        fun from(context: Context): PlayerSystemVolumeController =
            PlayerSystemVolumeController(AndroidPlayerSystemVolumeGateway(context))
    }
}

/** Pure conversion policy kept independent of Android for deterministic JVM tests. */
internal object PlayerSystemVolumePolicy {
    fun initialLevel(currentStep: Int, maxStep: Int): Float {
        val safeMax = maxStep.coerceAtLeast(1)
        return (currentStep.toFloat() / safeMax).coerceIn(0f, 1f)
    }

    fun normalize(value: Float): Float = value.coerceIn(0f, 1f)

    fun toStep(normalizedLevel: Float, maxStep: Int): Int {
        val safeMax = maxStep.coerceAtLeast(1)
        return (normalize(normalizedLevel) * safeMax).roundToInt().coerceIn(0, safeMax)
    }
}

private class AndroidPlayerSystemVolumeGateway(context: Context) : PlayerSystemVolumeGateway {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun maxMusicVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    override fun currentMusicVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun writeMusicVolume(step: Int) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
    }
}
