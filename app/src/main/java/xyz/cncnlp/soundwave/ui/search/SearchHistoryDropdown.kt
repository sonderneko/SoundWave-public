package xyz.cncnlp.soundwave.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.ui.common.scrolledHeaderGlass
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.settings.settingsLineColor

/** 下拉框最多显示的搜索记录条数（超出部分在框内滚动）。 */
internal const val SEARCH_HISTORY_DROPDOWN_LIMIT = 12

/**
 * r656：搜索栏获得焦点时显示的搜索记录。已输入关键词时只保留包含该关键词的记录（忽略大小写），
 * 与关键词完全相同的记录不再重复显示；未输入时显示全部（按最近顺序）。
 */
internal fun searchHistoryDropdownItems(history: List<String>, query: String, limit: Int = SEARCH_HISTORY_DROPDOWN_LIMIT): List<String> {
    val q = query.trim()
    val distinct = history.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val filtered = if (q.isEmpty()) distinct else distinct.filter { it.contains(q, ignoreCase = true) && !it.equals(q, ignoreCase = true) }
    return filtered.take(limit)
}

/**
 * r656 搜索记录小下拉框（替代原「最近搜索」区块）：与首页平台切换 / 搜索类别弹层同款——
 * 白色 r16 卡片、1dp 设计分隔线描边、柔和投影；左上 DM Mono「最近搜索」、右上珊瑚「清空」。
 * 每行：历史线性图标 + 关键词（不显示序号）+ 右侧 ✕ 单独删除。
 */
@Composable
internal fun SearchHistoryDropdown(
    items: List<String>,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    /** r657 第二轮：磨砂背景开时传入页面局部模糊源 → 面板为表面 64% + 22dp 模糊，分隔线隐藏。 */
    glass: xyz.cncnlp.soundwave.ui.common.LocalGlassState? = null
) {
    if (items.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val muted = colors.onSurfaceVariant
    Column(
        modifier
            .fillMaxWidth()
            .shadow(12.dp, shape, ambientColor = Color(0xFF131915).copy(alpha = 0.10f), spotColor = Color(0xFF131915).copy(alpha = 0.16f))
            .clip(shape)
            .then(if (glass != null) Modifier.scrolledHeaderGlass(glass, colors.surface, 1f) else Modifier.background(colors.surface))
            .border(1.dp, if (glass != null) settingsLineColor().copy(alpha = 0.5f) else settingsLineColor(), shape)
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("search_history_dropdown")
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "最近搜索", color = muted, fontSize = 10.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium,
                letterSpacing = 0.08.em, maxLines = 1, modifier = Modifier.weight(1f)
            )
            Text(
                "清空", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onClearAll)
                    .semantics { contentDescription = "清空搜索记录"; role = Role.Button }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            items.forEach { h ->
              androidx.compose.runtime.key(h) {
                // r657 修复：单条删除走粒子消散（原型 motion.js dissolve），结束后才真正删除。
                val dissolve = xyz.cncnlp.soundwave.ui.common.rememberParticleDissolveState()
                Row(
                    Modifier.fillMaxWidth().height(42.dp)
                        .then(xyz.cncnlp.soundwave.ui.common.particleDissolveModifier(dissolve, muted))
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPick(h) }
                        .padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(DesignLineGlyphs.History, null, tint = muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        h, color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable { dissolve.dissolve { onDelete(h) } }
                            .semantics { contentDescription = "删除搜索记录：$h"; role = Role.Button },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(DesignLineGlyphs.X, null, tint = muted, modifier = Modifier.size(14.dp))
                    }
                }
              }
            }
        }
    }
}
