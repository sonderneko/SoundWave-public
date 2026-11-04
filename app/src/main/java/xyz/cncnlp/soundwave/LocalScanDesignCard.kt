package xyz.cncnlp.soundwave

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.designLineColor

/**
 * 1.0.655 本地歌单扫描引导（设计稿风格）：白色 r24 卡 + 设计线框；珊瑚等宽眉题、24sp 粗体标题、状态说明；
 * 主按钮为珊瑚胶囊（柔光晕），次按钮「添加文件夹」为线框胶囊；扫描位置逐行列出并以分隔线隔开。
 * 替代旧版 Material Button / TextButton（随旧主题显示为蓝色）。
 */
@Composable
internal fun LocalScanDesignCard(
    status: String,
    running: Boolean,
    folders: List<String>,
    onScan: () -> Unit,
    onAddFolder: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    val line = designLineColor()
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
            .shadow(14.dp, shape, ambientColor = Color(0xFF131915).copy(alpha = 0.06f), spotColor = Color(0xFF131915).copy(alpha = 0.10f))
            .clip(shape).background(colors.surface).border(1.dp, line, shape)
            .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 20.dp)
    ) {
        Text("ON THIS DEVICE · 本地音乐", color = accent, fontSize = 10.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.16.em, maxLines = 1)
        Text("扫描设备中的音乐", color = colors.onSurface, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope,
            modifier = Modifier.padding(top = 8.dp))
        Text(status, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = DesignFonts.Manrope, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.height(46.dp)
                    .accentGlow(alpha = if (running) 0.25f else 0.55f)
                    .shadow(12.dp, CircleShape, ambientColor = accent.copy(alpha = 0.32f), spotColor = accent.copy(alpha = 0.55f))
                    .clip(CircleShape).background(accent).alpha(if (running) 0.6f else 1f)
                    .clickable(enabled = !running, onClick = onScan)
                    .semantics { contentDescription = "开始扫描"; role = Role.Button }
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(DesignLineGlyphs.Disc, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (running) "正在扫描…" else "开始扫描", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier.height(46.dp).clip(CircleShape).background(colors.surface).border(1.dp, line, CircleShape)
                    .alpha(if (running) 0.5f else 1f)
                    .clickable(enabled = !running, onClick = onAddFolder)
                    .semantics { contentDescription = "添加文件夹"; role = Role.Button }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(DesignLineGlyphs.Plus, null, tint = colors.onSurface, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加文件夹", color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1)
            }
        }
        Text("扫描位置", color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
        if (folders.isEmpty()) Text("选择文件夹并授权后，在此手动开始扫描", color = colors.onSurfaceVariant, fontSize = 12.sp, fontFamily = DesignFonts.Manrope)
        folders.forEach { label ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(line)))
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(DesignLineGlyphs.Library, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(label, color = colors.onSurface, fontSize = 13.sp, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
