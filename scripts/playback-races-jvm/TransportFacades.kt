package xyz.cncnlp.soundwave
class TestHandler {
    val pending = java.util.ArrayDeque<() -> Unit>()
    fun post(block: () -> Unit): Boolean { pending.add(block); return true }
    fun drain() { while (pending.isNotEmpty()) pending.removeFirst()() }
}
object Looper { fun myLooper() = 1; fun getMainLooper() = 1 }
class TestPlayer { var playing = true; var playable = true; fun pause() { playing = false }; fun play() { playing = true } }
class TestInstance {
    fun releaseTransitionWake() {}; fun updateSessionAndNotify() {}; fun requestAudioFocus() {}
    fun restoreFromStore(explicitResume: Boolean) {}
    fun cancelPlaybackRecovery(keepIntent: Int) {}
    fun resolveInService(song: Song, startMs: Long, autoSkip: Boolean) {}
}
class TestFocus { fun onUserPause() {} }
object PlaybackStore { fun setExplicitResume(context: Any, explicit: Boolean, owner: Any) {} }
