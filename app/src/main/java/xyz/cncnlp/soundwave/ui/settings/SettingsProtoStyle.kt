package xyz.cncnlp.soundwave.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ThemeTokens
import xyz.cncnlp.soundwave.ui.common.DesignFonts

/**
 * r657 第三轮（用户 10-18：「设置页整体照原型重做」）：设置首页分组 = 原型 app.css 基础样式。
 *  - .glabel：一行「中文 13sp 700」+ 8dp +「英文 DM Mono 9.5sp .16em 灰」，左右 18dp，上 28 下 10；
 *  - .grp：左右 14dp 白色（夜间 surface）r24 卡 + 1dp 线框 + shadow-sm（0 10 26 rgba(19,25,21,.07)）；
 *  - .srow .ti：36dp r12 圆角方块，c = 珊瑚浅底 #FFE4DF + 珊瑚图标；b = 蓝；l = 青柠；无 = surface2 + 墨色；
 *  - .srow+.srow::before：分割线从文字处（左 63dp）开始到卡片右缘。
 */
internal enum class SettingsTileTone { Coral, Blue, Lime, Neutral }

internal fun settingsToneForMark(mark: Color): SettingsTileTone = when (mark) {
    SettingsGroupMarks.Playback, SettingsGroupMarks.Audio -> SettingsTileTone.Coral
    SettingsGroupMarks.Appearance -> SettingsTileTone.Blue
    SettingsGroupMarks.Sources -> SettingsTileTone.Lime
    else -> SettingsTileTone.Neutral
}

/**
 * r658b 16（用户 10-19：「选项背景改回之前，图标不要背景」）：30dp 宽图标区 + 线性图标，无色块底。
 * ACCEPT5（用户 2026-11-04 V3 裁定）：图标颜色改逐行配色——调用方按行（SettingGlyphSpec）传 [tint]，
 * 不传时回退墨色；tone 参数保留以兼容调用方，但不影响颜色。
 */
@Composable
internal fun SettingsIconTile(icon: ImageVector, @Suppress("UNUSED_PARAMETER") tone: SettingsTileTone, tint: Color? = null) {
    Box(Modifier.width(ThemeTokens.Settings.IconWidth).height(ThemeTokens.Settings.IconHeight), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(ThemeTokens.Settings.IconSize))
    }
}

@Composable
internal fun SettingsGroupLabel(title: String, eyebrow: String, modifier: Modifier = Modifier, eyebrowColor: Color? = null) {
    // r658c #9（用户 10-19）：分组标题放大（13→16sp）；英文眉题用分组色，与该组行的按压底色同色。
    // 列表已有 14dp 页边距 → 实际左距 18dp
    // r658d（用户 10-20）：英文副标题放到中文标题下方。
    Column(modifier.fillMaxWidth().padding(start = ThemeTokens.Settings.LabelSidePadding, end = ThemeTokens.Settings.LabelSidePadding, bottom = ThemeTokens.Settings.LabelBottomPadding)) {
        Text(
            title, color = MaterialTheme.colorScheme.onBackground, fontSize = ThemeTokens.Settings.LabelFontSize, lineHeight = ThemeTokens.Settings.LabelLineHeight, fontWeight = FontWeight.Bold,
            fontFamily = DesignFonts.Manrope, letterSpacing = (-0.01).em, maxLines = 1
        )
        Text(
            eyebrow.uppercase(), color = eyebrowColor ?: settingsMutedColor(), fontSize = ThemeTokens.Settings.EyebrowFontSize, lineHeight = ThemeTokens.Settings.EyebrowLineHeight, fontWeight = FontWeight.Medium,
            fontFamily = DesignFonts.DmMono, letterSpacing = 0.16.em, maxLines = 1, modifier = Modifier.padding(top = ThemeTokens.Settings.EyebrowGap)
        )
    }
}

/**
 * r658b 16：分组不再有白色圆角卡片底/线框/阴影，回到之前的通栏样式——首行上线、末行下线、行间 1dp 线。
 */
@Composable
internal fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SettingsHairline()
        Column(Modifier.fillMaxWidth(), content = content)
        SettingsHairline()
    }
}

/** 行间分割线：通栏 1dp（r658b 16 回到卡片化之前的样式）。 */
@Composable
internal fun SettingsRowDivider() {
    SettingsHairline()
}
