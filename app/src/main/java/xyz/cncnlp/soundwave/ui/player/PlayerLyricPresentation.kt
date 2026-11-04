package xyz.cncnlp.soundwave.ui.player

import xyz.cncnlp.soundwave.ThemeTokens.PlayerLyrics

/** Stateless presentation math only. No playback time, selection mutation, scrolling or seek decisions. */
/** 全页统一（用户 2026-11-04）：行距不再分沉浸/普通两套取值区间，统一行高原值走同一钳制。 */
internal fun playerLyricPresentationGap(preview: Boolean, immersive: Boolean, normal: Int, immersiveGap: Int): Int =
    if (preview) 0 else (if (immersive) immersiveGap else normal)
        .coerceIn(PlayerLyrics.Spacing.NormalMin, PlayerLyrics.Spacing.NormalMax)

internal fun playerLyricPresentationLineHeight(base: Float, gap: Int, preview: Boolean): Float =
    if (preview) base * PlayerLyrics.Type.PreviewLineHeight
    else (base * PlayerLyrics.Type.FullLineHeight + gap * PlayerLyrics.Type.GapLineHeight)
        .coerceAtLeast(base + gap * PlayerLyrics.Type.MinGapLineHeight)

/** 全页统一：行上下留白不再按沉浸模式随 gap 缩放，全模式同一固定值（紧凑预览除外）。 */
internal fun playerLyricPresentationVerticalInset(gap: Int, preview: Boolean, immersive: Boolean): Float =
    if (preview) PlayerLyrics.PreviewVerticalInset.value else PlayerLyrics.RowVerticalInset.value

internal fun playerLyricPresentationAlpha(focusIndex: Int, distance: Int, focused: Boolean, played: Boolean): Float = when {
    focusIndex < 0 -> PlayerLyrics.Emphasis.UnpositionedAlpha
    focused -> PlayerLyrics.Emphasis.CurrentAlpha
    distance == 1 -> PlayerLyrics.Emphasis.NearAlpha
    distance == 2 -> PlayerLyrics.Emphasis.MiddleAlpha
    played -> PlayerLyrics.Emphasis.PlayedAlpha
    else -> PlayerLyrics.Emphasis.UpcomingAlpha
}

/**
 * 当前行字号：用户 2026-11-04 裁定三样式有效字号完全同值——当前行不再放大（旧 J 波
 * ×[PlayerLyrics.Emphasis.CurrentLineScale] 仅非沉浸面生效，造成各面当前行大小不一，已摘除）；
 * 当前行区分靠既有 alpha / 粗体 / 模糊分级，不靠字号。纯呈现，不参与选中/seek/滚动判定。
 */
internal fun playerLyricPresentationLineFontSp(base: Float, focused: Boolean, immersive: Boolean, compactPreview: Boolean): Float = base

internal fun playerLyricPresentationBlur(blur: Int, near: Boolean): Float = if (near)
    (blur.coerceAtLeast(PlayerLyrics.Emphasis.BlurBaseMin) * PlayerLyrics.Emphasis.NearBlurScale)
        .coerceIn(PlayerLyrics.Emphasis.NearBlurMin, PlayerLyrics.Emphasis.NearBlurMax)
else (blur.coerceAtLeast(PlayerLyrics.Emphasis.BlurBaseMin) * PlayerLyrics.Emphasis.FarBlurScale)
    .coerceIn(PlayerLyrics.Emphasis.FarBlurMin, PlayerLyrics.Emphasis.FarBlurMax)

/** 全页统一：沉浸/普通同一取值区间——有效字号 = 统一字号原值 × scale（宿主缩放；恒 1 时即原值）。 */
internal fun playerLyricRenderFontSp(immersive: Boolean, normal: Int, immersiveSp: Int, scale: Float): Float =
    ((if (immersive) immersiveSp else normal).coerceIn(PlayerLyrics.Type.MinSp, PlayerLyrics.Type.NormalMaxSp) * scale)
        .coerceIn(PlayerLyrics.Type.MinSp.toFloat(), PlayerLyrics.Type.ScaledMaxSp)
