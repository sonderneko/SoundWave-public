package xyz.cncnlp.soundwave.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.SoundwaveDanger

/** r657（v3 #1）：歌曲 ⋯ 抽屉顶部快捷操作（M 波五钮后为五项，见 TrackActionsSheet）。 */
data class DesignQuickTile(
    val icon: ImageVector,
    val label: String,
    val description: String = label,
    val active: Boolean = false,
    /** M 波（ISSUE-SONGMENU §5）：危险语义钮（如「不喜欢」）——active 时不用珊瑚（正向强调、语义错位），改危险色图标/文字。 */
    val danger: Boolean = false,
    val onClick: () -> Unit
)

private val QuickCoral = Color(0xFFFF5C45)

/**
 * 每项 = 图标瓦片 + 下方文字。行本身透明，按压缩放沿用 [pressScale]。
 * 图标区统一「细环」规格（无填充、无投影）：图标直接落在弹层底色上，仅以 1dp 极淡暖灰细环
 * （家族发丝线色 [designLineColor]）圈出点击区；状态只由图标色/文字色/字形承载（已收藏 = 珊瑚
 * 图标 + 珊瑚字 + 实心心形；已不喜欢 = 危险色）。
 * 沿革：ACCEPT5（用户 2026-11-04）先裁定歌曲菜单五宫格（≥5 钮）去填色块；accept6-uipolish2
 * （用户 2026-11-04 22:23）把同一口径扩到歌单操作弹层的四钮——原 ≤4 钮的填色块规格
 * （渐变面 + 描边 + 投影、已收藏珊瑚实心）自此删除，全站快捷瓦片只有细环一种形态。
 * accept6-sheetheader（用户 2026-11-04 22:51 批准头部稿时修正）：形状不再限于圆形——改
 * **圆角矩形细环瓦片**（圆角 14dp），尺寸全家统一一档：宽 min(列宽, 58dp) × 高 52dp
 * （原圆环 44/48 两档废止）；填充禁令不变，仍为无填充细环。
 */
@Composable
fun DesignQuickTileRow(tiles: List<DesignQuickTile>, modifier: Modifier = Modifier) {
    // M 波 §5 紧凑规格：≥5 钮时行间距 6→5dp（瓦片尺寸已全家统一，不再随数量变）。
    val compact = tiles.size >= 5
    Row(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp)) {
        tiles.forEach { DesignQuickTileItem(it, Modifier.weight(1f)) }
    }
}

@Composable
private fun DesignQuickTileItem(tile: DesignQuickTile, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val dangerActive = tile.danger && tile.active
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .pressScale(PressMotion.SCALE_CARD)
            .rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.16f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = tile.onClick)
            .semantics { contentDescription = tile.description; role = Role.Button }
            .padding(top = 6.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.widthIn(max = 58.dp).fillMaxWidth().height(52.dp)
                .border(1.dp, designLineColor(), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(tile.icon, null, tint = when {
                dangerActive -> SoundwaveDanger
                tile.active -> QuickCoral
                else -> colors.onSurface
            }, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            tile.label, color = when {
                dangerActive -> SoundwaveDanger
                tile.active -> QuickCoral
                else -> colors.onSurface
            }, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
            fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
    }
}
