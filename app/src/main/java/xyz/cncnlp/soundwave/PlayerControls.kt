package xyz.cncnlp.soundwave
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import xyz.cncnlp.soundwave.ThemeTokens.PlayerControls as ControlTokens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.pressScale
import xyz.cncnlp.soundwave.ui.common.PressMotion

@Composable
internal fun PlayerProgressBlock(
    ratio: Float,
    displayTime: Float,
    duration: Float,
    actualQuality: String = "",
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onSeekTap: (Float) -> Unit,
    onQuality: () -> Unit,
    light: Boolean,
    /** Fullscreen callers supply preview-only cancellation; default preserves the legacy callback. */
    onDragCancel: () -> Unit = onDragEnd,
    foreground: Color = ControlTokens.Ink
) {
    val track = ControlTokens.Progress.track(light)
    val fillBrush = SolidColor(foreground)
    val tcol = ControlTokens.Progress.time(light)
    val barH = if (dragging) ControlTokens.Progress.DragTrackHeight else ControlTokens.Progress.TrackHeight
    val thumbS = if (dragging) ControlTokens.Progress.DragThumb else ControlTokens.Progress.Thumb
    // 手势协程不能把实时 ratio 作为 key；否则 onDrag 更新状态会重启手势，表现为进度条“一卡一卡”。
    val latestRatio by rememberUpdatedState(ratio)
    val latestStart by rememberUpdatedState(onDragStart)
    val latestDrag by rememberUpdatedState(onDrag)
    val latestEnd by rememberUpdatedState(onDragEnd)
    val latestCancel by rememberUpdatedState(onDragCancel)
    Column(Modifier.fillMaxWidth().padding(horizontal = ControlTokens.HorizontalPadding).offset(y = ControlTokens.Progress.OffsetY)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(ControlTokens.Progress.HitHeight)
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        val r = PlayerSliderMath.tapFraction(offset.x, size.width.toFloat())
                        onSeekTap(r)
                    }
                }
                .pointerInput(duration) {
                    var local = latestRatio
                    var active = false
                    try {
                    detectHorizontalDragGestures(
                        onDragStart = { active = true; latestStart(); local = latestRatio },
                        onDragEnd = { active = false; latestEnd() },
                        onDragCancel = { active = false; latestCancel() },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            if (size.width > 0) {
                                local = PlayerSliderMath.dragFraction(local, dragAmount, size.width.toFloat())
                                latestDrag(local)
                            }
                        }
                    )
                    } finally {
                        // Key changes and removal cancel the pointer coroutine without a normal up.
                        if (active) latestCancel()
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val thumbX = (maxWidth - thumbS) * ratio
            Box(Modifier.fillMaxWidth().height(barH).clip(RoundedCornerShape(ControlTokens.Progress.Radius)).background(track))
            Box(Modifier.fillMaxWidth(ratio.coerceAtLeast(ControlTokens.Progress.VisibleFillFloor)).height(barH).clip(RoundedCornerShape(ControlTokens.Progress.Radius)).background(fillBrush))
            Box(
                Modifier
                    .offset(x = thumbX)
                    .size(thumbS)
                    .clip(CircleShape)
                    .background(foreground)
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(displayTime), color = tcol, fontSize = ControlTokens.Progress.TimeSize, modifier = Modifier.weight(1f))
            Text(actualQuality, color = foreground, fontSize = ControlTokens.Progress.TimeSize, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onQuality() }.padding(horizontal = ControlTokens.Progress.QualityHorizontalPadding, vertical = ControlTokens.Progress.QualityVerticalPadding))
            Text(fmt(duration), color = tcol, fontSize = ControlTokens.Progress.TimeSize, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

internal fun playModeLabel(mode: PlayMode): String = when (mode) {
    PlayMode.SEQUENCE -> "顺序播放"
    PlayMode.SHUFFLE -> "随机播放"
    PlayMode.LOOP_ONE -> "单曲循环"
    PlayMode.HEART -> "心动模式"
}

internal fun playModeGlyph(mode: PlayMode): String = when (mode) {
    PlayMode.SEQUENCE -> "mode_loop"
    PlayMode.SHUFFLE -> "mode_shuffle"
    PlayMode.LOOP_ONE -> "mode_one"
    PlayMode.HEART -> "sparkles_filled"
}

@Composable
internal fun AppleMusicControls(
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
    modeAsBottomAction: Boolean = false,
    onMode: () -> Unit = {},
    immersiveSelected: Boolean,
    deviceOn: Boolean = false,
    queueOn: Boolean = false,
    showDeviceAndQueueActions: Boolean = true,
    foreground: Color = ControlTokens.Ink
) {
    val milk = foreground
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    val volH = if (volumePressed) ControlTokens.Volume.PressedTrackHeight else ControlTokens.Volume.TrackHeight
    val volThumb = if (volumePressed) ControlTokens.Volume.PressedThumb else ControlTokens.Volume.Thumb
    // 音量与进度条一样：不能以实时 value 作为 pointerInput key，否则拖动会被重启。
    val latestVolume by rememberUpdatedState(volume)
    Column(Modifier.fillMaxWidth().padding(horizontal = ControlTokens.HorizontalPadding).padding(top = ControlTokens.Transport.TopPadding, bottom = ControlTokens.Transport.BottomPadding)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = ControlTokens.Transport.RowBottomPadding)
                // 音量隐藏时将播放/上一首/下一首移入其 30dp 槽位；下方动作行和命中区保持原位。
                .offset(y = if (showVolume) 0.dp else ControlTokens.Transport.HiddenVolumeOffset),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(ControlTokens.Transport.SkipTarget).pressScale(PressMotion.SCALE_ICON).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(pressHapticView); onPrev() }, contentAlignment = Alignment.Center) { WhiteGlyph("prev_solid", ControlTokens.Transport.SkipIcon, milk) }
            Spacer(Modifier.width(ControlTokens.Transport.ButtonGap))
            Box(
                Modifier.size(ControlTokens.Transport.PlayTarget).pressScale(PressMotion.SCALE_ICON).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(pressHapticView); onToggle() }.semantics {
                    contentDescription = if (playing) "暂停" else "播放"
                    stateDescription = if (playing) "正在播放" else "已暂停"
                    role = Role.Button
                },
                contentAlignment = Alignment.Center
            ) { WhiteGlyph(if (playing) "pause_solid" else "play_round", ControlTokens.Transport.PlayIcon, milk) }
            Spacer(Modifier.width(ControlTokens.Transport.ButtonGap))
            Box(Modifier.size(ControlTokens.Transport.SkipTarget).pressScale(PressMotion.SCALE_ICON).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(pressHapticView); onNext() }, contentAlignment = Alignment.Center) { WhiteGlyph("next_solid", ControlTokens.Transport.SkipIcon, milk) }
        }
        if (showVolume) Row(Modifier.fillMaxWidth().height(ControlTokens.Volume.RowHeight), verticalAlignment = Alignment.CenterVertically) {
            WhiteGlyph("volume_low", ControlTokens.Volume.LowIcon, milk.copy(alpha = ControlTokens.Volume.LowIconAlpha))
            Spacer(Modifier.width(ControlTokens.Volume.IconGap))
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .height(ControlTokens.Volume.HitHeight)
                    .pointerInput(Unit) {
                        detectTapGestures { o -> onVolume(PlayerSliderMath.tapFraction(o.x, size.width.toFloat())) }
                    }
                    .pointerInput(Unit) {
                        var local = latestVolume
                        detectHorizontalDragGestures(
                            onDragStart = { onVolumePressed(true); local = latestVolume },
                            onDragEnd = { onVolumePressed(false) },
                            onDragCancel = { onVolumePressed(false) },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                if (size.width > 0) {
                                    local = PlayerSliderMath.dragFraction(local, dragAmount, size.width.toFloat())
                                    onVolume(local)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(Modifier.fillMaxWidth().height(volH).clip(RoundedCornerShape(ControlTokens.Volume.Radius)).background(milk.copy(alpha = ControlTokens.Volume.TrackAlpha)))
                Box(Modifier.fillMaxWidth(volume.coerceAtLeast(ControlTokens.Volume.VisibleFillFloor)).height(volH).clip(RoundedCornerShape(ControlTokens.Volume.Radius)).background(milk))
                Box(Modifier.offset(x = (maxWidth - volThumb) * volume.coerceIn(0f, 1f)).size(volThumb).clip(CircleShape).background(milk))
            }
            Spacer(Modifier.width(ControlTokens.Volume.IconGap))
            WhiteGlyph("volume_high", ControlTokens.Volume.HighIcon, milk.copy(alpha = ControlTokens.Volume.HighIconAlpha))
        } else Spacer(Modifier.height(ControlTokens.Volume.RowHeight))
        Row(Modifier.fillMaxWidth().padding(top = ControlTokens.BottomAction.RowTopPadding), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            // 三枚图案统一使用 25dp 画布；不随启用状态变化，不加底、不改尺寸。
            if (modeAsBottomAction) {
                val modeLabel = playModeLabel(playMode)
                Box(
                    Modifier.size(ControlTokens.BottomAction.Target).clickable(onClick = onMode).semantics {
                        contentDescription = "播放顺序"
                        stateDescription = modeLabel
                    },
                    contentAlignment = Alignment.Center
                ) {
                    WhiteGlyph(playModeGlyph(playMode), ControlTokens.BottomAction.Icon, milk)
                }
            } else {
                AppleMusicBottomAction(R.drawable.ic_player_action_immersive_lyrics, "沉浸歌词", selected = immersiveSelected, iconSize = ControlTokens.BottomAction.Icon, tint = foreground, onClick = onLyrics)
            }
            if (showDeviceAndQueueActions) {
                AppleMusicBottomAction(R.drawable.ic_player_action_playback_device, "播放设备", selected = deviceOn, iconSize = ControlTokens.BottomAction.Icon, tint = foreground, onClick = onDevice)
            } else Spacer(Modifier.size(ControlTokens.BottomAction.Target))
            if (showDeviceAndQueueActions) {
                AppleMusicBottomAction(R.drawable.ic_player_action_play_queue, "播放队列", selected = queueOn, iconSize = ControlTokens.BottomAction.Icon, tint = foreground, onClick = onQueue)
            } else Spacer(Modifier.size(ControlTokens.BottomAction.Target))
        }
    }
}

@Composable
internal fun AppleMusicBottomAction(iconRes: Int, label: String, selected: Boolean = false, stateful: Boolean = true, iconSize: Dp = ControlTokens.BottomAction.Icon, tint: Color = ControlTokens.Ink, onClick: () -> Unit) {
    // 播放器底部按钮只保留图案本身：无底、无描边；按压只做轻微缩放，不改变布局。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = MotionPolicy.rememberReduceMotion()
    val scale by animateFloatAsState(PressMotion.target(pressed, PressMotion.SCALE_DEFAULT, reduceMotion), PressMotion.stateSpec, label = "playerActionPress")
    Box(
        Modifier.size(ControlTokens.BottomAction.Target)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(indication = null, interactionSource = interaction, role = Role.Button) { onClick() }
            .semantics {
                contentDescription = label
                if (stateful) stateDescription = if (selected) "已开启" else "未开启"
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            colorFilter = ColorFilter.tint(tint)
        )
    }
}
