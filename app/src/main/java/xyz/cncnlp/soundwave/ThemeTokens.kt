package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ColorScheme
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.motion.MotionTokens

/** Legacy palette plus semantic component specifications. Values preserve the current accepted UI. */
object ThemeTokens {

    /** Playlist editors share the DesignSheet shell and the SheetInputField / SheetActionButtons spec (user-pinned close-up). */
    object PlaylistDialog {
        val FieldGap = 28.dp
        val FieldRadius = 24.dp
        val FieldTextSize = 19.sp
        val ButtonGap = 12.dp
        val ButtonHeight = 52.dp
        val ButtonTextSize = 16.sp
        const val PrimaryWeight = 1.5f
        const val SecondaryWeight = 1f
    }

    /** Full-screen control chrome only; not lyric, mini-player or queue geometry. */
    object PlayerControls {
        val HorizontalPadding = 28.dp
        val Ink = Color.White
        object Progress {
            val OffsetY = (-5).dp
            val HitHeight = 20.dp
            val TrackHeight = 6.dp
            val DragTrackHeight = 8.dp
            val Thumb = 14.dp
            val DragThumb = 16.dp
            val Radius = 999.dp
            val TimeSize = 11.sp
            val QualityHorizontalPadding = 8.dp
            val QualityVerticalPadding = 2.dp
            const val VisibleFillFloor = 0.001f
            fun track(light: Boolean) = if (light) Ink.copy(alpha = 0.22f) else Color(0x22000000)
            fun time(light: Boolean) = if (light) Ink.copy(alpha = 0.85f) else Color(0xFF5A5A68)
        }
        object Transport {
            val TopPadding = 2.dp
            val BottomPadding = 12.dp
            val RowBottomPadding = 6.dp
            val HiddenVolumeOffset = 30.dp
            val SkipTarget = 52.dp
            val SkipIcon = 34.dp
            val PlayTarget = 64.dp
            val PlayIcon = 50.dp
            val ButtonGap = 30.dp
        }
        object Volume {
            val RowHeight = 30.dp
            val HitHeight = 24.dp
            val TrackHeight = 5.dp
            val PressedTrackHeight = 7.dp
            val Thumb = 10.dp
            val PressedThumb = 12.dp
            val LowIcon = 17.dp
            val HighIcon = 19.dp
            val IconGap = 10.dp
            val Radius = 999.dp
            const val LowIconAlpha = 0.62f
            const val HighIconAlpha = 0.82f
            const val TrackAlpha = 0.18f
            const val VisibleFillFloor = 0.001f
        }
        object BottomAction {
            val RowTopPadding = 2.dp
            val Target = 46.dp
            val Icon = 25.dp
            // Press physics and reduced-motion decisions remain PressMotion / MotionPolicy.
        }
    }

    /** Now-playing compact presentation. Host height remains DockMetrics; press physics remain PressMotion. */
    object MiniPlayer {
        val Radius = 22.dp
        val CapsuleRadius = 999.dp
        val Cover = 48.dp
        val CompactCover = 40.dp
        val CapsuleCoverInset = 10.dp
        val CoverRadius = 12.dp
        val CompactCoverRadius = 8.dp
        val TitleSize = 15.sp
        val TitleLine = 21.sp
        val CapsuleTitleSize = 14.sp
        val CapsuleTitleLine = 18.sp
        val CompactTitleSize = 13.sp
        val CompactTitleLine = 17.sp
        val SubtitleSize = 12.sp
        val SubtitleLine = 17.sp
        val CapsuleSubtitleSize = 11.sp
        val CapsuleSubtitleLine = 15.sp
        val CompactSubtitleSize = 11.sp
        val CompactSubtitleLine = 14.sp
        val VerticalPadding = 5.dp
        val TextGap = 3.dp
        val CompactTextGap = 1.dp
        val ProgressRing = 50.dp
        val CompactProgressRing = 44.dp
        val ProgressStroke = 2.dp
        val PlayTarget = 42.dp
        val CompactPlayTarget = 36.dp
        val PlayIcon = 23.dp
        val CompactPlayIcon = 19.dp
        const val CoverSpinMillis = 9000
        const val ContentPressedAlpha = 0.75f
        fun ink(colors: ColorScheme) = colors.onSurface
        fun muted(colors: ColorScheme) = colors.onSurfaceVariant
        fun progress(colors: ColorScheme) = colors.primary
        fun progressTrack(colors: ColorScheme) = colors.surfaceVariant
        fun playFill(colors: ColorScheme) = colors.onBackground
        fun playInk(colors: ColorScheme) = colors.background
        fun surface(colors: ColorScheme) = colors.surface
    }

    /** Shared song list presentation. Row geometry is distinct from detail hero/lyric cards. */
    object TrackRow {
        val Height = 80.dp
        val CompactHeight = 70.dp
        val Cover = 58.dp
        val CompactCover = 46.dp
        val CoverRadius = 10.dp
        val CompactStartPadding = 6.dp
        val DefaultOuterHorizontal = 6.dp
        val DefaultContentHorizontal = 10.dp
        val LooseFlatVertical = 2.dp
        val CardHorizontal = 10.dp
        val CardVertical = 3.dp
        val CardOffset = 5.dp
        val CoverGap = 8.dp
        val CompactCoverGap = 6.dp
        val TextEndPadding = 2.dp
        val MetaGap = 6.dp
        val SingerMaxWidth = 180.dp
        val TitleSize = 15.sp
        val SlimTitleSize = 13.sp
        val MetaSize = 13.sp
        val SlimMetaSize = 11.sp
        val IndexSize = 13.sp
        val IndexGap = 6.dp
        val ActionTarget = 48.dp
        val ActionIcon = 22.dp
        val SelectionSize = 20.dp
        val SelectionCheck = 12.dp
        val SelectionRadius = 12.dp
        val SelectionBorder = 1.25.dp
        val SelectionGap = 4.dp
        const val PlayingAlpha = 0.12f
        const val PlayingGradientEnd = 0.85f
        const val DetailSelectionAlpha = 0.08f
        const val SelectionOutlineAlpha = 0.74f
        const val IdleSelectionAlpha = 0.15f
        // PressMotion remains the shared interaction authority; no second press implementation here.
    }

    /** List/now-playing metadata badges; semantic accent, not the old blue palette. */
    object TrackBadge {
        val Height = 16.dp
        val Radius = 5.dp
        val HorizontalPadding = 5.dp
        val FontSize = 9.sp
        val LineHeight = 10.sp
        /**
         * NEW 徽字色：蓝色 #2877FF。J 波例外登记——E8 去蓝时曾连带改为暖琥珀，用户点名
         * 「NEW 之前是蓝色」裁定恢复蓝：NEW 属内容标记，与平台品牌色同逻辑，不受全站去蓝约束。
         */
        val NewInk = Color(0xFF2877FF)
        fun qualityFill(dark: Boolean) = if (dark) Color(0xFF4A2823) else Color(0xFFFFE4DF)
        fun newFill(dark: Boolean) = if (dark) Color(0xFF1C2A44) else Color(0xFFDFE9FF)
    }

    /** Navigation content specs. Existing DockMetrics owns host/inset geometry; dockGlass owns frost. */
    object Navigation {
        val Glyph = 22.dp
        object Classic {
            val HorizontalPadding = 8.dp
            val TopPadding = 13.dp
            val GlyphWidth = 20.dp
            val GlyphHeight = 21.dp
            val LabelGap = 10.dp
            val LabelSize = 9.sp
            val LabelLineHeight = 10.sp
        }
        object Capsule {
            val BottomPadding = 8.dp
            val ContentPadding = 5.dp
            val ItemGap = 2.dp
            val LabelGap = 6.dp
            val LabelSize = 13.sp
            val SearchGap = 10.dp
            const val SelectedWeight = 1.25f
        }
        object Motion {
            const val PressedScale = 0.97f
            const val PressMs = 100
            // SW-12-07：520ms 假弹簧切换已被 MotionTokens.Spring.Expressive（0.8f/380f）替代（见 BottomNav.rememberNavSwitchPop）；本常量仅保留历史契约值，不再用于切换动画。
            const val SwitchMs = 520
            const val IconMs = 420
            const val ReducedMs = 200
            const val ReducedIconMs = 160
            val Spring = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
            val Out = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
        }
    }

    /** Shared settings row/header specs; not the sheet or dock blur semantics. */
    object Settings {
        val RowMinHeight = 64.dp
        val RowHorizontalPadding = 7.dp
        val RowVerticalPadding = 12.dp
        val Hairline = 1.dp
        val DetailCardRadius = 16.dp
        val IconWidth = 30.dp
        val IconHeight = 34.dp
        val IconSize = 21.dp
        val LabelSidePadding = 4.dp
        val LabelBottomPadding = 10.dp
        val LabelFontSize = 16.sp
        val LabelLineHeight = 21.sp
        val EyebrowFontSize = 10.sp
        val EyebrowLineHeight = 12.sp
        val EyebrowGap = 2.dp
        const val PressDeepenMs = 220
        const val TintedPressDeepenMs = 650
        const val ReleaseMs = 180
        fun muted(dark: Boolean): Color = if (dark) Color(0xFFA6ADA7) else Color(0xFF5F645F)
        fun line(dark: Boolean): Color = if (dark) SoundwaveDesignPalette.NightLine else SoundwaveDesignPalette.Line
        fun pressInk(dark: Boolean): Color = if (dark) Color.White else Color(0xFF131915)
        fun initialPressAlpha(dark: Boolean, tinted: Boolean): Float = if (tinted || dark) 0.06f else 0.04f
        fun heldPressAlpha(dark: Boolean, tinted: Boolean): Float = if (tinted) (if (dark) 0.24f else 0.18f) else if (dark) 0.14f else 0.10f
    }

    // ===== 主题强调色（E8 去蓝映射：令牌名保留为历史引用，值已统一珊瑚/暖色） =====
    /** 主强调（原主蓝 #2A5CAA → 深珊瑚 AccentDeep）：底栏选中态、主要操作、通知强调色。 */
    val PrimaryCoralDeep = Color(0xFFE8452F.toInt())

    /** 次色（原蓝灰 #7595BE → 暖灰）：辅助图标与弱强调。 */
    val SecondaryWarm = Color(0xFF8F8A82.toInt())

    /** 亮强调（原亮蓝 #4A7BD8 → 珊瑚 Accent）：进度条与次级交互。 */
    val AccentCoral = Color(0xFFFF5C45.toInt())

    /** 播放页渐变暗端：设计稿中的低饱和绿黑，让封面成为主角。 */
    val StageDeep = Color(0xFF1A231B.toInt())

    /** 播放页渐变最暗端：中性近黑。 */
    val StageDeepest = Color(0xFF101613.toInt())

    // ===== 播放器深色舞台 =====
    val PlayerBackground = Color(0xFF101613.toInt())

    /** 播放页遮罩渐变底端（与 PlayerBackground 一致）。 */
    val PlayerMaskEnd = Color(0xCC101613.toInt())

    // ===== 蓝灰 / 纯白表面 =====
    val DarkSurfaceNeutral = Color(0xFF3A3A44.toInt())

    val LightSurfaceNeutral = Color(0xFFF2F2F5.toInt())

    val DarkCardNeutral = Color(0xFF3A3A44.toInt())

    // SW-08-16（G 波）：ProgressBlue 已删除（零引用；进度色直接用 Accent）。

    /** Shared bottom-sheet shell; callers retain lifecycle, gestures and reduced-motion handling. */
    object Sheet {
        val Radius = 28.dp
        val Shadow = 30.dp
        const val ShadowAlpha = 0.22f
        val Border = 1.dp
        const val FrostedBorderAlpha = 0.6f
        val HorizontalPadding = 26.dp
        val TopPadding = 16.dp
        val BottomPadding = 14.dp
        val HandleWidth = 46.dp
        val HandleHeight = 4.dp
        val HandleRadius = 4.dp
        val HeaderGap = 30.dp
        val EyebrowSize = 11.sp
        val EyebrowLineHeight = 14.sp
        val TitleSize = 24.sp
        val TitleLineHeight = 30.sp
        val TitleGap = 8.dp
        const val EnterMs = 420
        const val ExitMs = 220
        const val ScrimAlpha = 0.38f
        val Emphasized = MotionTokens.Easing.EmphasizedDecelerate
        /** ACCEPT6 dialogfrost：动作表危险区留白（v8 稿面比例——空带 ≈ 行高 1/3、区内行距更松，只留白不加色）。 */
        val DangerZoneTopGap = 16.dp
        val DangerZoneRowGap = 8.dp
    }

    /** Compact lyric action cards, not general settings cards. Do not restore older large sizes. */
    object LyricAction {
        val RowGap = 8.dp
        val ColumnGap = 10.dp
        val Radius = 18.dp
        val MinHeight = 98.dp
        val IconContainer = 28.dp
        val IconSize = 22.dp
        val HorizontalPadding = 12.dp
        val VerticalPadding = 9.dp
        val ContentGap = 4.dp
        val Border = 1.dp
        val RaisedShadow = 3.dp
        val PressedShadow = 0.dp
        val TitleSize = 14.sp
        val DetailSize = 11.sp
        val DetailLineHeight = 16.sp
        const val PressedScale = 0.975f
        const val PressMs = 140
        const val DisabledAlpha = 0.5f
        const val ShadowAmbientAlpha = 0.035f
        const val ShadowSpotAlpha = 0.05f
        fun ink(colors: ColorScheme, selected: Boolean) = if (selected) colors.primary else colors.onSurface
        fun fill(colors: ColorScheme, selected: Boolean) = if (selected) colors.primary.copy(alpha = 0.085f) else colors.background
        fun border(colors: ColorScheme, selected: Boolean) = if (selected) colors.primary.copy(alpha = 0.22f) else colors.outlineVariant.copy(alpha = 0.25f)
    }

    /** Full-player queue header and track content. Alignment/gestures/animation retain their owners. */
    object PlayerQueue {
        val ContentStart = 28.dp
        val SelectedInset = 16.dp
        val HeaderBottomGap = 10.dp
        val HeaderSize = 18.sp
        val HeaderIcon = 20.dp
        val EmptySize = 14.sp
        val ListBottomPadding = 72.dp
        val SectionTopGap = 12.dp
        val SectionBottomGap = 6.dp
        val SelectedRowPadding = 2.dp
        val RowPadding = 5.dp
        val ArtworkSize = 49.dp
        val ArtworkRadius = 8.dp
        val ArtworkGap = 10.dp
        val TextEndGap = 12.dp
        val DetailSize = 12.sp
        val Ink = Color.White
        val MutedInk = Ink.copy(alpha = 0.62f)
        val ArtworkFill = Ink.copy(alpha = 0.10f)
    }

    /** Lyric timeline/preview presentation only; scrolling, selection, karaoke and seeking keep their owners. */
    object PlayerLyrics {
        val RowRadius = 16.dp
        val PreviewRadius = 10.dp
        val TextInset = 6.dp
        val ActionGutter = 48.dp
        val RowVerticalInset = 4.dp
        val PreviewVerticalInset = 2.dp
        val TimestampWidth = 44.dp
        val TimestampInset = 7.dp
        val TimestampSize = 11.sp
        val PlayTarget = 48.dp
        val PlayGlyph = 22.dp
        val Ink = Color.White
        val InactiveFallback = Ink.copy(alpha = 0.62f)
        val SelectedFill = Ink.copy(alpha = 0.13f)
        val PreviewSelectedFill = Ink.copy(alpha = 0.11f)
        val TimestampInk = Ink.copy(alpha = 0.92f)
        const val TranslationAlpha = 0.72f
        const val RomanizationAlpha = 0.58f
        const val TranslationScale = 0.78f
        const val RomanizationScale = 0.68f
        const val TranslationLineHeightScale = 0.86f
        const val RomanizationLineHeightScale = 0.78f
        const val StandardMaxLines = 8
        const val AuxiliaryMaxLines = 4
        object Type {
            const val MinSp = 10
            const val NormalMaxSp = 34
            const val ImmersiveMinSp = 14
            const val ImmersiveMaxSp = 56
            const val ScaledMaxSp = 64f
            const val PreviewLineHeight = 1.18f
            const val FullLineHeight = 1.22f
            const val GapLineHeight = 0.85f
            const val MinGapLineHeight = 0.5f
        }
        object Spacing {
            const val NormalMin = 4
            const val NormalMax = 36
            const val ImmersiveMin = 6
            const val ImmersiveMax = 64
            const val ImmersivePaddingScale = 0.42f
            const val ImmersiveMinPadding = 4f
            const val ImmersiveMaxPadding = 36f
        }
        object Emphasis {
            const val UnpositionedAlpha = 0.78f
            const val CurrentAlpha = 1f
            /** J 波：非默认歌词页当前行字号放大档（设计稿基础套 28/21 ≈ 1.33）——此前全行同字号只靠 alpha 分级，当前行不放大。 */
            const val CurrentLineScale = 1.33f
            const val NearAlpha = 0.86f
            const val MiddleAlpha = 0.62f
            const val PlayedAlpha = 0.42f
            const val UpcomingAlpha = 0.36f
            const val BlurBaseMin = 2
            const val NearBlurScale = 0.45f
            const val NearBlurMin = 0.8f
            const val NearBlurMax = 3.2f
            const val FarBlurScale = 1.15f
            const val FarBlurMin = 2f
            const val FarBlurMax = 10f
        }
    }

    /** Page-sheet frost only. Dock/mini-player frost and cover tint remain independent. */
    object SheetFrost {
        const val SurfaceAlpha = 0.96f
        const val WindowBlurDp = 36
        const val DisabledWindowBlurDp = 3
        /** ACCEPT6 dialogfrost（用户 2026-11-04 定版）：对话框/表磨砂常开、与页面磨砂开关解耦——真模糊时面板罩不透明度（稿面：暖白半透、轮廓可辨、文字不可读）。 */
        const val GlassTintAlpha = 0.82f
        /** 真模糊半径（dp）：haze 面板 / 全屏背板 / pinned 顶栏带与 Dialog 窗口 blurBehind 半径共用同一值。 */
        const val GlassBlurDp = 32
    }

}
