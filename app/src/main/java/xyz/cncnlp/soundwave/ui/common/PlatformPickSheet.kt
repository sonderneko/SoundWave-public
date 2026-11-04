package xyz.cncnlp.soundwave.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.SoundwaveDesignPalette

/** 平台选择项：key（空串 = 全部）、主标题、可选说明。 */
data class PlatformPickOption(val key: String, val title: String, val sub: String = "")

/** 平台全称（原型 OPTS.platform / libplat：网易云音乐、QQ 音乐…）。 */
fun platformFullName(key: String): String = when (key.lowercase()) {
    "wy", "netease" -> "网易云音乐"
    "tx", "qq" -> "QQ 音乐"
    "kg", "kugou" -> "酷狗音乐"
    "kw", "kuwo" -> "酷我音乐"
    "mg", "migu" -> "咪咕音乐"
    else -> key
}

/**
 * r658b #19：全部页面的平台切换统一为原型「pick」抽屉（SH + .og/.oc）——
 * 珊瑚 DM Mono 眉题 → 24sp 粗体标题 → 12sp 说明 → 选项卡网格（1 / 3 列；r16、1dp 线框、选中珊瑚 60% 描边 + 9% 珊瑚底 + 珊瑚字 + 右侧 ✓ 弹出）。
 * 点选即应用并关闭（原型 pickset）。首页 / 排行榜 / 歌单广场（切换音源）、音乐库（筛选歌单平台）、搜索 / 搜索结果（切换搜索平台）共用。
 *
 * E6（验收二轮）：单列形态改为用户 v8 设计稿「筛选歌单平台」单选行（见 [PlatformPickRow]）；
 * 多列网格（搜索页 3 列短名）仍用 [PlatformPickCard]。行为（options/current/onPick/关闭接线）不变。
 */
@Composable
fun DesignPlatformPickSheet(
    eyebrow: String,
    title: String,
    subtitle: String,
    options: List<PlatformPickOption>,
    current: String,
    columns: Int = 1,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        // J 波：选项点击改走动画关闭（先执行 onPick 再退场，退场后 onDismiss 清状态）；
        // 此前是 onDismiss() 同步卸载、退场被跳过。三处调用方（首页/音乐库/搜索）均不在 onPick 清状态，成对成立。
        val dismiss = rememberDesignSheetDismiss(onDismiss)
        DesignPlatformPickContent(eyebrow, title, subtitle, options, current, columns) { key -> onPick(key); dismiss() }
    }
}

/** 抽屉内容（可单独放进 [DesignSheetPanel] 供截图测试）。 */
@Composable
fun androidx.compose.foundation.layout.ColumnScope.DesignPlatformPickContent(
    eyebrow: String,
    title: String,
    subtitle: String,
    options: List<PlatformPickOption>,
    current: String,
    columns: Int = 1,
    onPick: (String) -> Unit
) {
    DesignSheetHeader(eyebrow, title)
    if (subtitle.isNotBlank()) Text(
        subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp,
        modifier = Modifier.padding(top = 4.dp)
    )
    Spacer(Modifier.height(18.dp))
    val cols = columns.coerceIn(1, 4)
    if (cols == 1) {
        // E6（验收二轮，用户点名）：单列改为用户 v8 设计稿「音乐库 · 筛选歌单平台」的单选行——
        // 无背景平台图案（本对话框专属新图案，冻结区例外）+ 15sp 粗标题 + 右侧灰色说明 + 发丝线分隔 +
        // 选中行图案/标题转珊瑚、尾部裸 ✓ 150ms 弹入；无卡片边框、无底色填充。
        Column {
            options.forEachIndexed { index, opt ->
                PlatformPickRow(opt, opt.key == current, showDivider = index > 0) { onPick(opt.key) }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            options.chunked(cols).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems.forEach { opt ->
                        PlatformPickCard(opt, opt.key == current, centered = true, modifier = Modifier.weight(1f)) { onPick(opt.key) }
                    }
                    repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * E6 平台图案（本对话框专属，24 网格 / 描边 1.8 / 圆端点，无背景无底色）：
 * 全部 = 四宫格；网易云 = 云；QQ = 对话气泡；酷狗 = 爪印；酷我 = 耳机；咪咕 = 广播波纹；未知 = 音符。
 */
object PlatformMarks {
    private fun mark(name: String, vararg paths: String): androidx.compose.ui.graphics.vector.ImageVector =
        androidx.compose.ui.graphics.vector.ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = androidx.compose.ui.graphics.vector.addPathNodes(d),
                    stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
                )
            }
        }.build()

    val All: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        mark("swp_all", "M5.2 5.2h4.6v4.6H5.2z", "M14.2 5.2h4.6v4.6h-4.6z", "M5.2 14.2h4.6v4.6H5.2z", "M14.2 14.2h4.6v4.6h-4.6z")
    }
    val Netease: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        mark("swp_netease", "M18 10h-1.3A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z")
    }
    val Qq: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        mark("swp_qq", "M20.5 11.5a8.5 8.5 0 0 1-12.4 7.1L3.5 20.5l1.9-4.6A8.5 8.5 0 1 1 20.5 11.5z", "M8.5 10.2v.1M12 10.2v.1M15.5 10.2v.1")
    }
    val Kugou: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        mark(
            "swp_kugou",
            "M12 12.6c2.7 0 4.9 2.2 4.9 4.3 0 1.5-1.2 2.6-2.8 2.6-1.2 0-1.6-.5-2.1-.5s-.9.5-2.1.5c-1.6 0-2.8-1.1-2.8-2.6 0-2.1 2.2-4.3 4.9-4.3z",
            "M6.6 8.2a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 0 0 0-2.8z",
            "M11 6.2a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 0 0 0-2.8z",
            "M15.6 6.6a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 0 0 0-2.8z",
            "M18.2 9.4a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 0 0 0-2.8z"
        )
    }
    val Kuwo: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        mark("swp_kuwo", "M4 18v-5.5a8 8 0 0 1 16 0V18", "M4 13.5h2.2v5H4.6A.6.6 0 0 1 4 17.9z", "M20 13.5h-2.2v5h1.6a.6.6 0 0 0 .6-.6z")
    }
    val Migu: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        mark(
            "swp_migu",
            "M12 10.6a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 0 0 0-2.8z",
            "M8.2 8.2a5.4 5.4 0 0 0 0 7.6M15.8 8.2a5.4 5.4 0 0 1 0 7.6",
            "M5.6 5.6a9.2 9.2 0 0 0 0 12.8M18.4 5.6a9.2 9.2 0 0 1 0 12.8"
        )
    }
    val Note: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        mark(
            "swp_note", "M9.5 18.3V6L19 4.2v11.3",
            "M7.2 15.9a2.3 2.3 0 1 0 0 4.6 2.3 2.3 0 0 0 0-4.6z",
            "M16.7 13.1a2.3 2.3 0 1 0 0 4.6 2.3 2.3 0 0 0 0-4.6z"
        )
    }

    fun forKey(key: String): androidx.compose.ui.graphics.vector.ImageVector = when (key.lowercase()) {
        "" -> All
        "wy", "netease" -> Netease
        "tx", "qq" -> Qq
        "kg", "kugou" -> Kugou
        "kw", "kuwo" -> Kuwo
        "mg", "migu" -> Migu
        else -> Note
    }
}

/** E6 单选行（设计稿 .opt/.v7l-radio）：56dp 高、图案 21dp、标题 15sp 粗、说明 12sp 灰、发丝线分隔、选中珊瑚 + 裸 ✓。 */
@Composable
private fun PlatformPickRow(opt: PlatformPickOption, selected: Boolean, showDivider: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    val ink = if (selected) accent else colors.onSurface
    val check by animateFloatAsState(
        if (selected) 1f else 0f,
        androidx.compose.animation.core.tween(150), label = "pickRowCheck"
    )
    Column {
        if (showDivider) Box(Modifier.fillMaxWidth().height(1.dp).background(designLineColor()))
        Row(
            Modifier.fillMaxWidth().height(56.dp)
                .pressScale(PressMotion.SCALE_ROW)
                .clickable(onClick = onClick)
                .semantics { this.selected = selected; role = Role.RadioButton }
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(PlatformMarks.forKey(opt.key), null, tint = ink, modifier = Modifier.size(21.dp))
            Spacer(Modifier.size(16.dp))
            Text(
                opt.title, color = ink, fontSize = 15.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (opt.sub.isNotBlank()) Text(
                opt.sub, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp).widthIn(max = 150.dp), textAlign = TextAlign.End
            )
            // 勾选槽位恒在（稿子 .v7l-ck opacity 0 占位），避免选中切换时说明文字跳位。
            Icon(
                DesignChromeGlyphs.Check, "当前", tint = accent,
                modifier = Modifier.padding(start = 8.dp).size(20.dp)
                    .graphicsLayer { alpha = check.coerceIn(0f, 1f); scaleX = 0.6f + 0.4f * check; scaleY = 0.6f + 0.4f * check }
            )
        }
    }
}

@Composable
private fun PlatformPickCard(opt: PlatformPickOption, selected: Boolean, centered: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    val line = designLineColor()
    val shape = RoundedCornerShape(16.dp)
    val check by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "pickCheck")
    Box(
        modifier.heightIn(min = if (centered) 56.dp else 58.dp).clip(shape)
            .background(if (selected) accent.copy(alpha = 0.09f).compositeOver(colors.surface) else colors.surface)
            .border(1.dp, if (selected) accent.copy(alpha = 0.6f) else line, shape)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .padding(start = if (centered) 10.dp else 12.dp, end = if (centered) 10.dp else 30.dp, top = 10.dp, bottom = 10.dp),
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart
    ) {
        Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
            Text(
                opt.title, color = if (selected) accent else colors.onSurface, fontSize = 14.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start
            )
            if (opt.sub.isNotBlank()) Text(
                opt.sub, color = colors.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
            )
        }
        // 原型 .ock：右 10dp、垂直居中，18dp 珊瑚 ✓（选中时从 0.4 弹到 1）。
        if (check > 0.01f) Icon(
            DesignChromeGlyphs.Check, "当前", tint = accent,
            modifier = Modifier.align(Alignment.CenterEnd)
                .offset(x = if (centered) 0.dp else 20.dp)
                .size(18.dp)
                .graphicsLayer { alpha = check.coerceIn(0f, 1f); scaleX = 0.4f + 0.6f * check; scaleY = 0.4f + 0.6f * check }
        )
    }
}
