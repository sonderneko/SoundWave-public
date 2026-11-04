package xyz.cncnlp.soundwave.ui.player

import xyz.cncnlp.soundwave.PlayMode
import xyz.cncnlp.soundwave.PlayerService
import xyz.cncnlp.soundwave.Song

/** AppRoot-owned event handlers installed on the foreground playback service. */
internal class PlayerServiceEventCallbacks(
    val onSongChanged: (Song?) -> Unit,
    val onError: (PlayerService.Companion.PlaybackFailure) -> Unit,
    val onNeedNext: () -> Unit,
    val onNeedPrev: () -> Unit,
    val onPreparedNext: () -> Unit,
    val onNeedReplay: (Int) -> Unit,
    val onPlaybackInterrupted: () -> Unit = {},
    val onLikeToggle: () -> Unit,
    val onLyricFloatToggle: () -> Unit,
    val onSleepTick: (Long) -> Unit,
    val onModeChanged: (PlayMode) -> Unit
) {
    // Per-registration wrappers give each Activity a distinct ownership identity,
    // even when a handler is a reusable, non-capturing lambda.
    internal val boundOnSongChanged: (Song?) -> Unit = { song -> onSongChanged(song) }
    internal val boundOnError: (PlayerService.Companion.PlaybackFailure) -> Unit = { failure -> onError(failure) }
    internal val boundOnNeedNext: () -> Unit = { onNeedNext() }
    internal val boundOnNeedPrev: () -> Unit = { onNeedPrev() }
    internal val boundOnPreparedNext: () -> Unit = { onPreparedNext() }
    internal val boundOnNeedReplay: (Int) -> Unit = { token -> onNeedReplay(token) }
    internal val boundOnPlaybackInterrupted: () -> Unit = { onPlaybackInterrupted() }
    internal val boundOnLikeToggle: () -> Unit = { onLikeToggle() }
    internal val boundOnLyricFloatToggle: () -> Unit = { onLyricFloatToggle() }
    internal val boundOnSleepTick: (Long) -> Unit = { remain -> onSleepTick(remain) }
    internal val boundOnModeChanged: (PlayMode) -> Unit = { mode -> onModeChanged(mode) }
}

/** Pairs one AppRoot registration with its cleanup without owning playback/service lifetime. */
internal class PlayerServiceEventBridge(
    private val attach: (PlayerServiceEventCallbacks) -> Unit = ::attachPlayerServiceEventCallbacks,
    private val detach: (PlayerServiceEventCallbacks) -> Unit = ::detachPlayerServiceEventCallbacks
) {
    private var registered: PlayerServiceEventCallbacks? = null

    @Synchronized
    fun register(callbacks: PlayerServiceEventCallbacks) {
        if (registered === callbacks) return
        dispose()
        attach(callbacks)
        registered = callbacks
    }

    @Synchronized
    fun dispose() {
        val callbacks = registered ?: return
        registered = null
        detach(callbacks)
    }
}

private fun attachPlayerServiceEventCallbacks(callbacks: PlayerServiceEventCallbacks) {
    PlayerService.onSongChanged = callbacks.boundOnSongChanged
    PlayerService.onError = callbacks.boundOnError
    PlayerService.onNeedNext = callbacks.boundOnNeedNext
    PlayerService.onNeedPrev = callbacks.boundOnNeedPrev
    PlayerService.onPreparedNext = callbacks.boundOnPreparedNext
    PlayerService.onNeedReplay = callbacks.boundOnNeedReplay
    PlayerService.onPlaybackInterrupted = callbacks.boundOnPlaybackInterrupted
    PlayerService.onLikeToggle = callbacks.boundOnLikeToggle
    PlayerService.onLyricFloatToggle = callbacks.boundOnLyricFloatToggle
    PlayerService.onSleepTick = callbacks.boundOnSleepTick
    PlayerService.onModeChanged = callbacks.boundOnModeChanged
}

/** A stale Activity may dispose after a replacement has registered; clear only callbacks still owned here. */
private fun detachPlayerServiceEventCallbacks(callbacks: PlayerServiceEventCallbacks) {
    if (PlayerService.onSongChanged === callbacks.boundOnSongChanged) PlayerService.onSongChanged = null
    if (PlayerService.onError === callbacks.boundOnError) PlayerService.onError = null
    if (PlayerService.onNeedNext === callbacks.boundOnNeedNext) PlayerService.onNeedNext = null
    if (PlayerService.onNeedPrev === callbacks.boundOnNeedPrev) PlayerService.onNeedPrev = null
    if (PlayerService.onPreparedNext === callbacks.boundOnPreparedNext) PlayerService.onPreparedNext = null
    if (PlayerService.onNeedReplay === callbacks.boundOnNeedReplay) PlayerService.onNeedReplay = null
    if (PlayerService.onPlaybackInterrupted === callbacks.boundOnPlaybackInterrupted) PlayerService.onPlaybackInterrupted = null
    if (PlayerService.onLikeToggle === callbacks.boundOnLikeToggle) PlayerService.onLikeToggle = null
    if (PlayerService.onLyricFloatToggle === callbacks.boundOnLyricFloatToggle) PlayerService.onLyricFloatToggle = null
    if (PlayerService.onSleepTick === callbacks.boundOnSleepTick) PlayerService.onSleepTick = null
    if (PlayerService.onModeChanged === callbacks.boundOnModeChanged) PlayerService.onModeChanged = null
}
