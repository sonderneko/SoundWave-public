package xyz.cncnlp.soundwave.ui.search

import xyz.cncnlp.soundwave.ui.common.homeCoverShadow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.CacheManager
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs

/**
 * r657 第三轮 b（用户：搜索各分类「查看全部」后的结果页与设计稿不符，需统一）：按 v5 pResults 的视觉语汇统一歌曲 / 歌单 / 专辑 / 歌手全部结果页：
 *  - 顶部 .seg.rseg 分段：综合 / 歌曲 N / 歌单 N / 专辑 N / 歌手 N（34dp 胶囊，选中表面色 + 投影），点按直接切换分类；
 *  - .sec 标题：700 20sp + DM Mono 11sp「· N」计数；
 *  - .row 行：72dp、左右 14dp、间距 14dp；56dp r14 封面（歌手为圆形）+ 0 8 18 投影；700 15/20 标题、12/16 灰副标题；右侧 DM Mono / 箭头。
 * 行作为 LazyColumn 的独立 item 渲染（旧实现把整页结果放进一个 item 内 forEach，长列表一次性全部组合，滚动卡顿）。
 */
internal data class SearchSegItem(val mode: String, val label: String)

@Composable
internal fun SearchResultSeg(items: List<SearchSegItem>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(CircleShape)
            .background(if (dark) Color(0xFF232722) else Color(0xFFECECE6)).padding(4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { item ->
            val on = item.mode == selected || (selected == "singer" && item.mode == "artist")
            Box(
                Modifier.height(34.dp)
                    .then(if (on) Modifier.shadow(6.dp, CircleShape, ambientColor = Color(0xFF131915).copy(alpha = 0.10f), spotColor = Color(0xFF131915).copy(alpha = 0.10f)) else Modifier)
                    .clip(CircleShape)
                    .background(if (on) (if (dark) Color(0xFF2E332D) else colors.surface) else Color.Transparent)
                    .clickable { if (!on) onSelect(item.mode) }
                    .semantics { role = Role.Tab; this.selected = on }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    item.label, color = if (on) colors.onBackground else colors.onSurfaceVariant, fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1, softWrap = false
                )
            }
        }
    }
}

@Composable
internal fun SearchSecHeader(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 8.dp).height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
            fontFamily = DesignFonts.Manrope, letterSpacing = (-0.03).em, maxLines = 1)
        if (count > 0) Text(
            "  · $count", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.DmMono,
            fontWeight = FontWeight.Medium, letterSpacing = 0.04.em, maxLines = 1
        )
    }
}

@Composable
internal fun SearchResultRow(
    cover: String,
    title: String,
    subtitle: String,
    placeholder: ImageVector,
    /** r658b #15：带搜索词高亮的标题（原型 hl()）；为空时用 [title]。 */
    titleText: androidx.compose.ui.text.AnnotatedString? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    circle: Boolean = false,
    coverModifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {
        Icon(DesignLineGlyphs.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val shape = if (circle) CircleShape else RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().height(72.dp).clickable(onClick = onClick)
            .semantics { contentDescription = title; role = Role.Button }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            coverModifier.size(56.dp)
                // r673（用户 10-22）：搜索结果 / 各分类「查看全部」封面统一用应用封面阴影（首页同款 homeCoverShadow）。
                .homeCoverShadow(shape)
                .clip(shape).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            // ACCEPT5 收编：占位统一黑胶母题——此前把行类型字形（placeholder 参数）转发给 DefaultCoverPlaceholder，
            // 走的是字形圆盘分支而非母题；参数保留兼容既有调用点，但不再转发。非空封面失败重试一次。
            xyz.cncnlp.soundwave.DefaultCoverPlaceholder(Modifier.fillMaxSize())
            if (cover.isNotBlank()) {
                val model = remember(cover) { CacheManager.coverModel(context, cover) ?: cover }
                var coverRetry by remember(cover) { androidx.compose.runtime.mutableIntStateOf(0) }
                androidx.compose.runtime.key(coverRetry) {
                    AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        onError = { if (coverRetry == 0) coverRetry = 1 })
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(titleText ?: androidx.compose.ui.text.AnnotatedString(title), color = colors.onBackground, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                fontFamily = DesignFonts.Manrope, letterSpacing = (-0.01).em, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp,
                fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        trailing()
    }
}
