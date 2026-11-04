package xyz.cncnlp.soundwave

/** 播放器「⋯」专属列表项的展示规格（标题 + 图标键）。 */
internal data class PlayerMenuPrimaryActionSpec(
    val key: String,
    val title: String,
    val glyph: String,
    val weight: Float = 1f
)

/*
 * r658c P：播放器「⋯」顶部四个快捷钮改由共用 TrackActionsSheetContent 提供（trackActionQuickTileKeys：收藏 / 下一首 / 下载 / 相似歌曲，
 * 「收藏」= 喜欢该歌曲，「添加到歌单」移到下方列表），本文件只保留播放器专属项的展示规格。
 */

internal fun heartModeMenuActionSpec(isActive: Boolean) = PlayerMenuPrimaryActionSpec(
    key = "heart_mode",
    title = if (isActive) "关闭心动模式" else "心动模式",
    glyph = if (isActive) "sparkles_filled" else "sparkles_outline"
)

internal enum class HeartModeToggleAction { START, STOP }

internal fun heartModeToggleAction(mode: PlayMode): HeartModeToggleAction =
    if (mode == PlayMode.HEART) HeartModeToggleAction.STOP else HeartModeToggleAction.START
