package xyz.cncnlp.soundwave.playback

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** r656 设置第 7 项：音频焦点策略与通话打断的真实行为决策。 */
class AudioFocusPolicyTest {
    private val duck = AudioFocusSettings(AudioFocusMode.DUCK, resumeAfterCall = true)
    private val pause = AudioFocusSettings(AudioFocusMode.PAUSE, resumeAfterCall = true)
    private val mix = AudioFocusSettings(AudioFocusMode.MIX, resumeAfterCall = true)

    @Test
    fun duckModeLowersVolumeOnShortPromptAndRestoresOnGain() {
        val d = AudioFocusPolicy.onFocusChange(AudioFocusPolicy.LOSS_TRANSIENT_CAN_DUCK, duck, playing = true, inCall = false, state = FocusState())
        assertEquals(listOf(FocusCommand.DUCK), d.commands)
        val g = AudioFocusPolicy.onFocusChange(AudioFocusPolicy.GAIN, duck, playing = true, inCall = false, state = d.state)
        assertEquals(listOf(FocusCommand.RESTORE_VOLUME), g.commands)
    }

    @Test
    fun pauseModePausesOnShortPromptAndResumesAfterIt() {
        val d = AudioFocusPolicy.onFocusChange(AudioFocusPolicy.LOSS_TRANSIENT_CAN_DUCK, pause, playing = true, inCall = false, state = FocusState())
        assertEquals(listOf(FocusCommand.PAUSE), d.commands)
        val g = AudioFocusPolicy.onFocusChange(AudioFocusPolicy.GAIN, pause, playing = false, inCall = false, state = d.state)
        assertEquals(listOf(FocusCommand.RESUME), g.commands)
    }

    @Test
    fun permanentLossPausesWithoutAutoResume() {
        val d = AudioFocusPolicy.onFocusChange(AudioFocusPolicy.LOSS, duck, playing = true, inCall = false, state = FocusState())
        assertEquals(listOf(FocusCommand.PAUSE), d.commands)
        val g = AudioFocusPolicy.onFocusChange(AudioFocusPolicy.GAIN, duck, playing = false, inCall = false, state = d.state)
        assertTrue(g.commands.isEmpty())
    }

    @Test
    fun callInterruptionHonoursKeepPausedAndAutoResume() {
        val keep = duck.copy(resumeAfterCall = false)
        val lossKeep = AudioFocusPolicy.onFocusChange(AudioFocusPolicy.LOSS_TRANSIENT, keep, playing = true, inCall = true, state = FocusState())
        assertTrue(AudioFocusPolicy.onFocusChange(AudioFocusPolicy.GAIN, keep, false, false, lossKeep.state).commands.isEmpty())
        val lossResume = AudioFocusPolicy.onFocusChange(AudioFocusPolicy.LOSS_TRANSIENT, duck, playing = true, inCall = true, state = FocusState())
        assertEquals(listOf(FocusCommand.RESUME), AudioFocusPolicy.onFocusChange(AudioFocusPolicy.GAIN, duck, false, false, lossResume.state).commands)
    }

    @Test
    fun userPauseDuringTransientLossCancelsAutoResume() {
        val loss = AudioFocusPolicy.onFocusChange(AudioFocusPolicy.LOSS_TRANSIENT, duck, playing = true, inCall = false, state = FocusState())
        val afterUser = AudioFocusPolicy.onUserAction(loss.state)
        assertTrue(AudioFocusPolicy.onFocusChange(AudioFocusPolicy.GAIN, duck, false, false, afterUser).commands.isEmpty())
    }

    @Test
    fun mixModeIgnoresFocusButStillPausesForCalls() {
        assertTrue(AudioFocusPolicy.onFocusChange(AudioFocusPolicy.LOSS, mix, true, false, FocusState()).commands.isEmpty())
        val call = AudioFocusPolicy.onCallStateChange(true, mix, playing = true, state = FocusState())
        assertEquals(listOf(FocusCommand.PAUSE), call.commands)
        assertEquals(listOf(FocusCommand.RESUME), AudioFocusPolicy.onCallStateChange(false, mix, playing = false, state = call.state).commands)
        val keep = mix.copy(resumeAfterCall = false)
        val c2 = AudioFocusPolicy.onCallStateChange(true, keep, playing = true, state = FocusState())
        assertTrue(AudioFocusPolicy.onCallStateChange(false, keep, playing = false, state = c2.state).commands.isEmpty())
    }

    @Test
    fun legacyAllowMixMigratesToMixMode() {
        val prefs = MapPrefs(mutableMapOf("allow_mix" to true))
        assertEquals(AudioFocusMode.MIX, AudioFocusSettingsStore.read(prefs).mode)
        assertEquals(AudioFocusMode.DUCK, AudioFocusSettingsStore.read(MapPrefs(mutableMapOf())).mode)
        AudioFocusSettingsStore.write(prefs, AudioFocusSettings(AudioFocusMode.PAUSE, false))
        val back = AudioFocusSettingsStore.read(prefs)
        assertEquals(AudioFocusMode.PAUSE, back.mode)
        assertEquals(false, back.resumeAfterCall)
        assertEquals(false, prefs.getBoolean("allow_mix", true))
    }

    /** 最小 SharedPreferences 内存实现（纯 JVM）。 */
    private class MapPrefs(val m: MutableMap<String, Any?>) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = m
        override fun getString(k: String?, d: String?) = m[k] as? String ?: d
        override fun getStringSet(k: String?, d: MutableSet<String>?) = d
        override fun getInt(k: String?, d: Int) = m[k] as? Int ?: d
        override fun getLong(k: String?, d: Long) = m[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float) = m[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean) = m[k] as? Boolean ?: d
        override fun contains(k: String?) = m.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(k: String?, v: String?) = apply { m[k!!] = v }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { m[k!!] = v }
            override fun putInt(k: String?, v: Int) = apply { m[k!!] = v }
            override fun putLong(k: String?, v: Long) = apply { m[k!!] = v }
            override fun putFloat(k: String?, v: Float) = apply { m[k!!] = v }
            override fun putBoolean(k: String?, v: Boolean) = apply { m[k!!] = v }
            override fun remove(k: String?) = apply { m.remove(k) }
            override fun clear() = apply { m.clear() }
            override fun commit() = true
            override fun apply() {}
        }
    }
}
