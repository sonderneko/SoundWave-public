package xyz.cncnlp.soundwave

import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 全局布局/行高 token：设置五大类/子类/孙类同一行高，防切换晃动。 */
object SettingsMetrics {
    const val RowH = 56
    const val HubRowH = 56
    const val NestedRowH = 56
    const val TitlePadV = 10
    const val SectionPadH = 14
    const val PageTop = 0
    const val HubTopExtra = 3 // 五大类相对原位再下 3dp（Spacer 11）
    const val SubTopExtra = 6 // 子类整体下 6dp
    const val SubTitleSp = 16
    const val SubHintSp = 12
    const val SubPadH = 14
    const val SubPadTop = 8
}

/** 列表/封面共用尺寸。 */
object LayoutTokens {
    const val SongCover = 58
    const val RankCover = 64.5f
    const val DetailCover = 156
    const val PageTitleSp = 20
    const val AvatarDp = 29.5f // 全页顶栏头像（用户要求再缩小2.5dp：32→29.5）
    const val TopBarH = 48
    /** 首页“更多”文字的实际右缘，也是全页顶栏图案/头像的视觉边缘基准。 */
    const val TopVisualEdge = 16
    /** 主页内容底部须避开迷你条并额外留 5dp。 */
    const val DockHostH = 64
    const val HomePlGridTopUp = -5
    const val LibListTopDown = 8
}

/**
 * 全局夜间 Material 色板：暖墨底色 + 珊瑚强调（验收二轮 E8：双色系取消，全 app 去蓝；
 * 原蓝灰底/浅蓝强调为老版本遗留，数值与设计夜间令牌 NightPaper/NightSurface/NightInk 等成对对齐）。
 */
object NightPalette {
    // 夜间强调取 AccentLight（深底上提亮一档，与日间 Accent 保持层级区分）。
    private const val AccentArgb = 0xFFFF8A73L
    val Background = Color(0xFF101210.toInt())
    val Surface = Color(0xFF191C19.toInt())
    val SurfaceVariant = Color(0xFF232722.toInt())
    val PrimaryText = Color(0xFFF1F2EC.toInt())
    val SecondaryText = Color(0xFFA5AAA3.toInt())
    val Accent = Color(AccentArgb.toInt())
    val OnAccent = Color(0xFF101210.toInt())
    val Outline = Color(0x66323730)
}

internal fun darkScheme() = darkColorScheme(
    primary = NightPalette.Accent,
    secondary = NightPalette.SecondaryText,
    background = NightPalette.Background,
    surface = NightPalette.Surface,
    surfaceVariant = NightPalette.SurfaceVariant,
    onPrimary = NightPalette.OnAccent,
    onBackground = NightPalette.PrimaryText,
    onSurface = NightPalette.PrimaryText,
    onSurfaceVariant = NightPalette.SecondaryText,
    outline = NightPalette.Outline
)

internal fun lightScheme() = lightColorScheme(
    // E8：根主题去蓝——primary 由老蓝 #2A5CAA 改珊瑚 Accent，secondary 由蓝灰改暖灰 Muted，
    // outline 由蓝 alpha 改墨 alpha；底色/表面本就是中性暖白，不动。
    primary = Color(0xFFFF5C45.toInt()),
    secondary = Color(0xFF686D68.toInt()),
    background = Color(0xFFFAFAFA.toInt()),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEEEEC.toInt()),
    // onPrimary 取墨字：白字在珊瑚上的对比度只有约 3.0，不满足全仓 ≥4.5 的对比契约；
    // 与设计色板 lightScheme 的 onPrimary=Ink 取向一致。
    onPrimary = Color(0xFF101010.toInt()),
    onBackground = Color(0xFF101010.toInt()),
    onSurface = Color(0xFF101010.toInt()),
    outline = Color(0x33161917)
)

/** 全 app 统一设计色板（E8 起不再有第二套色系；播放器同样走本色板/根主题的珊瑚映射）。 */
object SoundwaveDesignPalette {
    val Paper = Color(0xFFF4F4F0)
    val Surface = Color(0xFFFFFFFF)
    val Surface2 = Color(0xFFE9E9E3)
    val Ink = Color(0xFF161917)
    val Muted = Color(0xFF686D68)
    val Line = Color(0xFFD8D9D2)
    val Accent = Color(0xFFFF5C45)
    val AccentSoft = Color(0xFFFFE1DC)
    /** 原型珊瑚渐变高光 / 暗部（#FF8A73 → #FF5C45 → #E8452F）。 */
    val AccentLight = Color(0xFFFF8A73)
    val AccentDeep = Color(0xFFE8452F)
    /** SW-12-15（G 波，用户裁定）：浅色主题小号珊瑚文字专用深珊瑚 #C8381F（对纸面对比度 ≈4.6:1，达 WCAG AA 小字）；深色主题小字仍用 Accent。 */
    val AccentText = Color(0xFFC8381F)
    // E8：Blue 令牌（#2877FF）清退——原三处引用（唱片套光晕/维护页分类色/毛玻璃染色）
    // 已改指 Accent，本令牌删除，全仓不再有蓝色设计令牌。
    val Lime = Color(0xFFCCF35B)
    val NightPaper = Color(0xFF101210)
    val NightSurface = Color(0xFF191C19)
    val NightSurface2 = Color(0xFF232722)
    val NightInk = Color(0xFFF1F2EC)
    val NightMuted = Color(0xFFA5AAA3)
    val NightLine = Color(0xFF323730)
    val NightAccentSoft = Color(0xFF4A2823)

    // ACCEPT4（v8 稿 v8phSVG）：默认封面占位——中性暖灰底 + 黑胶唱片（沟纹 + 珊瑚芯标）。
    // 底渐变 浅 #ECECE6→#F8F8F4 / 夜 #272B26→#161916；唱片盘面径向 浅 #2B302C→#3D433E /
    // 夜 #0C0E0C→#1F231F；芯标与中心孔色随稿（芯标 = Accent，孔 = 底渐变终点）。
    val CoverBgStart = Color(0xFFECECE6)
    val CoverBgEnd = Color(0xFFF8F8F4)
    val NightCoverBgStart = Color(0xFF272B26)
    val NightCoverBgEnd = Color(0xFF161916)
    val VinylCore = Color(0xFF2B302C)
    val VinylEdge = Color(0xFF3D433E)
    val NightVinylCore = Color(0xFF0C0E0C)
    val NightVinylEdge = Color(0xFF1F231F)
}

/** 全仓唯一危险色：删除 / 退出 / 清空等危险操作一律指向它，不再散用 colorScheme.error 或其它红。 */
val SoundwaveDanger = Color(0xFFB84F43)

internal fun soundwaveDesignLightScheme() = lightColorScheme(
    primary = SoundwaveDesignPalette.Accent,
    onPrimary = SoundwaveDesignPalette.Ink,
    primaryContainer = SoundwaveDesignPalette.AccentSoft,
    onPrimaryContainer = SoundwaveDesignPalette.Ink,
    secondary = SoundwaveDesignPalette.Muted,
    onSecondary = SoundwaveDesignPalette.Surface,
    secondaryContainer = SoundwaveDesignPalette.AccentSoft,
    onSecondaryContainer = SoundwaveDesignPalette.Ink,
    // ACCEPT5（用户 2026-11-04 终局定版）：全 app 页面背景统一偏白——设计主题 background 与
    // 根主题 lightScheme 的 background 同值 #FAFAFA。此前用暖纸 Paper #F4F4F0：顶栏
    // （AppPageHeader 自画 background）与详情页整页是暖的，内容区透出根底板 #FAFAFA，
    // 同页两色出接缝。Paper 令牌保留供组件纸面填充（空态艺术等），不再作页面底。
    // 夜间两侧同为 #101210（NightPaper == NightPalette.Background），本就一致，不动。
    background = Color(0xFFFAFAFA.toInt()),
    surface = SoundwaveDesignPalette.Surface,
    surfaceVariant = SoundwaveDesignPalette.Surface2,
    onBackground = SoundwaveDesignPalette.Ink,
    onSurface = SoundwaveDesignPalette.Ink,
    onSurfaceVariant = SoundwaveDesignPalette.Muted,
    outline = SoundwaveDesignPalette.Line,
    outlineVariant = SoundwaveDesignPalette.Line,
    surfaceTint = SoundwaveDesignPalette.Accent
)

internal fun soundwaveDesignNightScheme() = darkColorScheme(
    primary = SoundwaveDesignPalette.Accent,
    onPrimary = SoundwaveDesignPalette.NightPaper,
    primaryContainer = SoundwaveDesignPalette.NightAccentSoft,
    onPrimaryContainer = SoundwaveDesignPalette.NightInk,
    secondary = SoundwaveDesignPalette.NightMuted,
    onSecondary = SoundwaveDesignPalette.NightPaper,
    secondaryContainer = SoundwaveDesignPalette.NightAccentSoft,
    onSecondaryContainer = SoundwaveDesignPalette.NightInk,
    background = SoundwaveDesignPalette.NightPaper,
    surface = SoundwaveDesignPalette.NightSurface,
    surfaceVariant = SoundwaveDesignPalette.NightSurface2,
    onBackground = SoundwaveDesignPalette.NightInk,
    onSurface = SoundwaveDesignPalette.NightInk,
    onSurfaceVariant = SoundwaveDesignPalette.NightMuted,
    outline = SoundwaveDesignPalette.NightLine,
    outlineVariant = SoundwaveDesignPalette.NightLine,
    surfaceTint = SoundwaveDesignPalette.Accent
)

internal fun soundwaveDesignScheme(isDark: Boolean): ColorScheme =
    if (isDark) soundwaveDesignNightScheme() else soundwaveDesignLightScheme()

/**
 * SW-12-15（G 波）：小号珊瑚「文字」颜色——浅色主题返回加深的 [SoundwaveDesignPalette.AccentText]，
 * 深色主题返回原 [SoundwaveDesignPalette.Accent]。仅用于文字（眉题/踢脚线/状态/趋势等小字号），
 * 填充、图标、选中态与操作标签仍用 Accent/primary（站规：选中 = ✓ + 珊瑚字保持不变）。
 * 深浅判别：根主题与设计主题的深色 scheme surface 均为 NightSurface(0xFF191C19)。
 */
@Composable
internal fun designAccentTextColor(): Color =
    if (MaterialTheme.colorScheme.surface == SoundwaveDesignPalette.NightSurface) SoundwaveDesignPalette.Accent
    else SoundwaveDesignPalette.AccentText

@Composable
internal fun SoundwaveDesignTheme(isDark: Boolean, content: @Composable () -> Unit) {
    val typography = MaterialTheme.typography
    // r656-G：按压反馈等共享组件从这里读“减少动效”，整页只注册一次系统设置监听。
    val reduceMotion = MotionPolicy.rememberReduceMotion()
    androidx.compose.runtime.CompositionLocalProvider(xyz.cncnlp.soundwave.ui.common.LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = soundwaveDesignScheme(isDark), typography = typography) {
            // r657 第二轮：按压反馈改为原型 .press 缩放 + 珊瑚光晕（替代 Material 灰色水波纹）。
            val indication = androidx.compose.runtime.remember(reduceMotion, isDark) {
                xyz.cncnlp.soundwave.ui.common.PressGlowIndication(
                    glow = SoundwaveDesignPalette.Accent.copy(alpha = if (isDark) 0.22f else 0.16f), reduceMotion = { reduceMotion }
                )
            }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides indication, content = content)
        }
    }
}

/** 播放器沿用根主题角色色（E8 后根主题已去蓝，播放器随之统一珊瑚体系，无独立色板）。 */
internal fun playerScheme(mode: String, isDark: Boolean): ColorScheme =
    if (isDark || mode == "night" || mode == "dark") darkScheme() else lightScheme()

/** Flat surface without a hard outline or shadow; elevation stays for call-site compatibility. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun Modifier.neumorphicSurface(
    shape: Shape,
    elevation: Dp = 8.dp,
    fill: Color? = null
): Modifier {
    return this
        .clip(shape)
        .background(fill ?: MaterialTheme.colorScheme.surface)
}

// 壳强调 token（E8 后指向珊瑚值，见 ThemeTokens.PrimaryCoralDeep 的映射说明）
internal val Accent = ThemeTokens.PrimaryCoralDeep
internal val Accent2 = ThemeTokens.AccentCoral
internal val Muted = Color(0xFF8A8A96.toInt())
internal val CardDark = Color(0x0DFFFFFF)
internal val LineDark = Color(0x17FFFFFF)

/** 列表/设置卡片：亮色纯白，暗色中性蓝灰 */
internal fun cardBrush(isDark: Boolean) = Brush.horizontalGradient(
    if (isDark) listOf(
        Color(0xFF3A3A44.toInt()),
        Color(0xFF3A3A44.toInt()),
        Color(0xFF3A3A44.toInt())
    ) else listOf(
        Color.White,
        Color.White,
        Color.White
    )
)

internal fun miniPlayerBrush(isDark: Boolean) = Brush.horizontalGradient(
    if (isDark) listOf(
        Color(0xFF3A3A42.toInt()),
        Color(0xFF42424C.toInt()),
        Color(0xFF3A3A42.toInt())
    ) else listOf(
        Color.White,
        Color.White,
        Color.White
    )
)

/** 将历史琉璃模式规范为系统自适应，夜间别名规范为统一夜间模式。 */
internal fun normalizeThemeMode(mode: String): String = when (mode) {
    "light" -> "light"
    "night", "dark" -> "night"
    "auto", "glass" -> "auto"
    else -> "light"
}

/** 纯白=亮；夜间=暗；自适应跟随系统。 */
fun resolveIsDark(mode: String, systemDark: Boolean): Boolean = when (mode) {
    "light" -> false
    "night", "dark" -> true
    else -> systemDark
}

internal fun themeColor(value: String, fallback: Color): Color = try {
    val raw = value.trim().removePrefix("#")
    when (raw.length) {
        6 -> Color((0xFF000000L or raw.toLong(16)).toInt())
        8 -> Color(raw.toLong(16).toInt())
        else -> fallback
    }
} catch (_: Exception) { fallback }

internal val FONT_FAMILY_CHOICES = listOf("system", "sans", "serif", "mono")
internal fun nextFontFamily(current: String): String {
    val at = FONT_FAMILY_CHOICES.indexOf(current)
    return FONT_FAMILY_CHOICES[(if (at < 0) 0 else at + 1) % FONT_FAMILY_CHOICES.size]
}
internal fun fontFamilyFromPref(value: String): FontFamily = when (value) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "sans" -> FontFamily.SansSerif
    else -> FontFamily.Default
}
internal fun fontLabel(value: String): String = when (value) {
    "serif" -> "衬线"
    "mono" -> "等宽"
    "sans" -> "无衬线"
    else -> "系统"
}

data class ThemeVisual(
    val menuAlpha: Float = 0.90f,
    val menuIconWhite: Boolean = false
)

val LocalThemeVisual = staticCompositionLocalOf { ThemeVisual() }
val LocalGlobalFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }
val LocalLyricFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Default }
