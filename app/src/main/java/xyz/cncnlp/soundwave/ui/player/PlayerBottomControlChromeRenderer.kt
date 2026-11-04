package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.AppleMusicControls
import xyz.cncnlp.soundwave.PlayMode
import xyz.cncnlp.soundwave.PlayerProgressBlock
import xyz.cncnlp.soundwave.playback.PlaybackAudioFormat
import xyz.cncnlp.soundwave.playback.playbackAudioFormatLabel

/** Pure control-area renderer; FullPlayer retains mutable playback, volume, routing, and gesture state. */
@Composable
internal fun PlayerBottomControlChromeRenderer(
    modifier: Modifier = Modifier,
    verticalOffset: Dp = (-14).dp,
    hiddenVolumeShift: Dp,
    progress: Float,
    dragRatio: Float,
    displayTime: Float,
    duration: Float,
    playbackAudioFormat: PlaybackAudioFormat?,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onSeekTap: (Float) -> Unit,
    onQuality: () -> Unit,
    playing: Boolean,
    playMode: PlayMode,
    volume: Float,
    onVolume: (Float) -> Unit,
    showVolume: Boolean,
    volumePressed: Boolean,
    onVolumePressed: (Boolean) -> Unit,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onLyrics: () -> Unit,
    onDevice: () -> Unit,
    onQueue: () -> Unit,
    modeAsBottomAction: Boolean,
    onMode: () -> Unit,
    immersiveSelected: Boolean,
    deviceOn: Boolean,
    queueOn: Boolean,
    showDeviceAndQueueActions: Boolean,
    onDragCancel: () -> Unit = {},
    foreground: Color = Color.White,
    lightControls: Boolean = true
) {
    val ratio = if (dragging) dragRatio else progress.coerceIn(0f, 1f)
    val actualQuality = playbackAudioFormatLabel(playbackAudioFormat)
    Column(modifier.offset(y = verticalOffset)) {
        Box(Modifier.offset(y = hiddenVolumeShift)) {
            PlayerProgressBlock(
                ratio = ratio,
                displayTime = displayTime,
                duration = duration,
                actualQuality = actualQuality,
                dragging = dragging,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                onDragCancel = onDragCancel,
                onSeekTap = onSeekTap,
                onQuality = onQuality,
                light = lightControls,
                foreground = foreground
            )
        }
        AppleMusicControls(
            playing = playing,
            playMode = playMode,
            volume = volume,
            onVolume = onVolume,
            showVolume = showVolume,
            volumePressed = volumePressed,
            onVolumePressed = onVolumePressed,
            onPrev = onPrev,
            onToggle = onToggle,
            onNext = onNext,
            onLyrics = onLyrics,
            onDevice = onDevice,
            onQueue = onQueue,
            modeAsBottomAction = modeAsBottomAction,
            onMode = onMode,
            immersiveSelected = immersiveSelected,
            deviceOn = deviceOn,
            queueOn = queueOn,
            showDeviceAndQueueActions = showDeviceAndQueueActions,
            foreground = foreground
        )
    }
}
