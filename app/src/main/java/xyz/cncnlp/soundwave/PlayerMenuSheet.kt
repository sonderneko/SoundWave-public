package xyz.cncnlp.soundwave

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetHeader
import xyz.cncnlp.soundwave.ui.common.DesignSheetRow
import xyz.cncnlp.soundwave.ui.common.designLineColor
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs
import xyz.cncnlp.soundwave.ui.settings.SleepTimerSheetContent
import xyz.cncnlp.soundwave.ui.settings.sleepMinutesFromRemain
import xyz.cncnlp.soundwave.ui.settings.sleepValueLabel

/**
 * 播放器「⋯」抽屉（r658c P）：
 *  - 外壳 = 公共 [DesignSheetDialog]（贴屏幕底边、自底部 translateY 滑入、不淡入不缩放；旧版自建 Dialog 走平台缩放 / 淡入动画、底部留缝）。
 *  - 第 0 级 = 全 App 共用的 [TrackActionsSheetContent]（顶部封面 + 五个快捷钮 收藏 / 下一首 / 下载 / 相似歌曲 / 不喜欢（M 波四改五）→ 列表），
 *    播放器专属项（投屏与播放设备、心动模式、睡眠定时、歌词设置、音量条）作为列表项插在危险区（从歌单删除）之前。
 *  - 第 2 级「歌词设置」= 统一的歌词字号 / 行高 / 偏移（与长按歌词、设置页共用同一组偏好，对所有播放器样式生效）。
 *  - M 波（ISSUE-SONGMENU §4）：全菜单封顶 0.70、面板不再自滚——各级内容共用同一内滚
 *    ScrollState（切级归零），该 state 同时 hoist 给面板的拖拽关闭连接。
 *  - 层级切换走 AnimatedContent 方向滑移 + 淡变（2026-11-04 补，原为 when 硬切无动画）。
 *  - ACCEPT4：第 0 级改整块一区滚动（只固定拖柄 + 标题头）；出处只一行「播放来源」
 *    （PlaySourceTracker 的 label + 身份，有身份直达歌单详情、无身份整行不出——旧候选列表已删除）。
 */
@Composable
internal fun FullPlayerMenuSheet(
    song: Song,
    liked: Boolean,
    playMode: PlayMode,
    sleepRemain: Long,
    lyricGapDp: Int,
    lyricBlurDp: Int,
    lyricOffset: Int,
    lyricFontSp: Int,
    playerLayout: String,
    menuLevel: Int,
    transitionSeconds: Int,
    showVolumeInMenu: Boolean,
    isDisliked: () -> Boolean,
    onMode: () -> Unit,
    onDismiss: () -> Unit,
    onSetMenuLevel: (Int) -> Unit,
    onLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddNext: () -> Unit,
    onDownload: () -> Unit,
    onOpenDevice: () -> Unit,
    onPlaybackLink: () -> Unit,
    onSimilar: () -> Unit,
    onHeartMode: () -> Unit,
    onToggleDislike: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenTransitionSettings: () -> Unit,
    onToggleVolume: () -> Unit,
    onSleepSelected: (Int) -> Unit,
    onLyricGap: (Int) -> Unit,
    onLyricBlur: (Int) -> Unit,
    onLyricOffset: (Int) -> Unit,
    onLyricFont: (Int) -> Unit,
    onSelectPlayerLayout: (String) -> Unit
) {
    val menuScrollState = rememberScrollState()
    LaunchedEffect(menuLevel) { menuScrollState.scrollTo(0) }
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = false, maxHeightFraction = TRACK_ACTIONS_SHEET_MAX_HEIGHT_FRACTION, contentScrollState = menuScrollState) {
        val menuFg = MaterialTheme.colorScheme.onSurface
        val reduceMenuMotion = xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion()
        // 层级转场（用户 2026-11-04：原 when 硬切、点「歌词设置」无动画）：包 AnimatedContent——去子级时
        // 新级自右 1/3 滑入 + 淡入、旧级向左滑出 + 淡出，返回反向（内容切换口径参 HomeDesignSections
        // 260 入 / 200 出、方向语义参 SettingsPush）；高度走默认 SizeTransform 平滑伸缩，面板不再跳高；
        // 简洁动效时时长归零直切。内层 Column 恢复 ColumnScope，各级 weight / 内滚语义不变。
        AnimatedContent(
            targetState = menuLevel,
            transitionSpec = {
                val inMs = if (reduceMenuMotion) 0 else 260
                val outMs = if (reduceMenuMotion) 0 else 200
                if (targetState > initialState) {
                    (slideInHorizontally(animationSpec = tween(inMs, easing = xyz.cncnlp.soundwave.ui.motion.MotionTokens.Easing.EmphasizedDecelerate)) { it / 3 } + fadeIn(tween(inMs))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(outMs, easing = xyz.cncnlp.soundwave.ui.motion.MotionTokens.Easing.EmphasizedAccelerate)) { -it / 3 } + fadeOut(tween(outMs)))
                } else {
                    (slideInHorizontally(animationSpec = tween(inMs, easing = xyz.cncnlp.soundwave.ui.motion.MotionTokens.Easing.EmphasizedDecelerate)) { -it / 3 } + fadeIn(tween(inMs))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(outMs, easing = xyz.cncnlp.soundwave.ui.motion.MotionTokens.Easing.EmphasizedAccelerate)) { it / 3 } + fadeOut(tween(outMs)))
                }
            },
            label = "playerMenuLevel"
        ) { level ->
            Column(Modifier.fillMaxWidth()) {
                when (level) {
            0 -> {
                val disliked = isDisliked()
                TrackActionsSheetContent(
                    song = song, liked = liked, showDownload = true, canDelete = false,
                    onSimilar = onSimilar, onLike = onLike, onAddNext = onAddNext, onAdd = onAddToPlaylist,
                    onDownload = onDownload, onDelete = {}, onDislike = onToggleDislike, onNavigate = onDismiss,
                    disliked = disliked, onPlaybackLink = onPlaybackLink,
                    playbackSourceLabel = PlaySourceTracker.label(), playbackSourcePlaylist = PlaySourceTracker.originPlaylist(),
                    listScrollState = menuScrollState,
                    headerTrailing = {
                        val line = designLineColor()
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).border(1.dp, line, CircleShape)
                                .clickable(onClick = onMode)
                                .semantics {
                                    contentDescription = "播放顺序：${playModeLabel(playMode)}"
                                    role = Role.Button
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(DefaultPlayerGlyphs.playMode(playMode), null, Modifier.size(21.dp), tint = SoundwaveDesignPalette.Accent)
                        }
                    },
                    extraRows = {
                    // ACCEPT5（退出动画普查 P2）：跨表面行（投屏/心动/睡眠/淡入淡出）统一「落目标
                    // 状态→退场句柄」，宿主已不再同步清菜单显隐；切级（歌词设置）与开关（音量条）
                    // 为层内语义，保持不退场。
                    val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
                    val heartAction = heartModeMenuActionSpec(playMode == PlayMode.HEART)
                    val sleepMinutes = sleepMinutesFromRemain(sleepRemain)
                    val actions = listOf<Triple<String, String, () -> Unit>>(
                        Triple("投屏与播放设备", "menu_cast") { onOpenDevice(); dismiss() },
                        Triple(heartAction.title, heartAction.glyph) { onHeartMode(); dismiss() },
                        Triple("睡眠定时", "menu_timer") { onOpenSleepTimer(); dismiss() },
                        Triple("淡入淡出", "menu_transition") { onOpenTransitionSettings(); dismiss() },
                        Triple("智能过渡 · 尚未启用", "menu_smart_transition") { onOpenTransitionSettings(); dismiss() },
                        Triple("歌词设置", "menu_lyrics") { onSetMenuLevel(2) },
                        Triple("音量条", if (showVolumeInMenu) "volume_on" else "volume_off", onToggleVolume)
                    )
                    actions.forEach { (title, glyph, action) ->
                        if (glyph == "menu_transition" || glyph == "menu_smart_transition") {
                            DesignSheetRow(DefaultPlayerGlyphs.forMenuKey(glyph), title, playerMenuSecondarySubtitle(glyph, sleepMinutes, transitionSeconds), height = TRACK_ACTION_ROW_HEIGHT, onClick = action)
                        } else {
                            DesignSheetRow(DefaultPlayerGlyphs.forMenuKey(glyph), title, playerMenuSecondarySubtitle(glyph, sleepMinutes), height = TRACK_ACTION_ROW_HEIGHT, onClick = action)
                        }
                    }
                    }
                )
            }
            // M 波：1~3 级内容改走与第 0 级共用的内滚 state（面板自身不再滚动），封顶后小屏亦可达底部项。
            1 -> Column(Modifier.weight(1f, fill = false).verticalScroll(menuScrollState)) {
                SleepTimerSheetContent(remainMs = sleepRemain, onApply = onSleepSelected, onDismiss = onDismiss)
            }
            2 -> Column(Modifier.weight(1f, fill = false).verticalScroll(menuScrollState)) {
                DesignSheetHeader("Lyrics", "歌词设置")
                Spacer(Modifier.height(14.dp))
                // 设置核心段与长按「歌词操作」表共用 PlayerLyricsSettingsCore（2026-11-04 合并，只此一份）。
                PlayerLyricsSettingsCore(
                    lyricFontSp = lyricFontSp, lyricGapDp = lyricGapDp, lyricOffset = lyricOffset, fg = menuFg,
                    onLyricFont = onLyricFont, onLyricGap = onLyricGap, onLyricOffset = onLyricOffset
                )
            }
            else -> Column(Modifier.weight(1f, fill = false).verticalScroll(menuScrollState)) {
                // ACCEPT5（退出动画普查 P5）：样式选项行「落样式→退场句柄」，清状态归 onDismiss。
                val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
                DesignSheetHeader("Player style", "播放器样式")
                Spacer(Modifier.height(14.dp))
                PLAYER_LAYOUT_CHOICES.forEach { (key, label) ->
                    PlayerMenuChoiceRow(label, if (key == PLAYER_LAYOUT_DEFAULT) "推荐" else "", normalizePlayerLayout(playerLayout) == key, menuFg) {
                        onSelectPlayerLayout(key); dismiss()
                    }
                }
            }
                }
            }
        }
    }
}

/** 播放器专属列表项右侧说明。 */
internal fun playerMenuSecondarySubtitle(glyph: String, sleepMinutes: Int, transitionSeconds: Int = 0): String = when (glyph) {
    "menu_cast" -> "本机 · 蓝牙 · DLNA"
    "sparkles_outline" -> "智能续播相似歌曲"
    "sparkles_filled" -> "恢复原播放顺序"
    "menu_timer" -> if (sleepMinutes > 0) "剩余 $sleepMinutes 分钟" else sleepValueLabel(sleepMinutes)
    "menu_transition" -> if (transitionSeconds == 0) "关闭" else "固定 $transitionSeconds 秒"
    "menu_smart_transition" -> "当前不可用 · 不是开关"
    "menu_lyrics" -> "字号 · 行高 · 偏移"
    "volume_on" -> "已显示"
    "volume_off" -> "已隐藏"
    else -> ""
}
