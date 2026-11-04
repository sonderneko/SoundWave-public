package xyz.cncnlp.soundwave.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * SW-12-13 统一状态组件：空 / 错 / 加载三态共用一套视觉（审计 B 节与 §5 改法）——
 * 48dp 线性图标 + 15sp SemiBold 主句 + 13sp Muted 副句 + 可选 40dp 胶囊按钮，上下间距 32dp。
 * 文案规则：主句「暂无 X」、副句说「能做什么」，不加句号；网络错误统一「网络不可用」+「重试」。
 */

/** 统一空 / 错误状态块。[actionLabel] 与 [onAction] 同时给出时才显示按钮。 */
@Composable
fun StateBlock(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            title, color = colors.onSurface, fontSize = 15.sp, lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold, fontFamily = DesignFonts.Manrope, textAlign = TextAlign.Center
        )
        if (!subtitle.isNullOrBlank()) Text(
            subtitle, color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp,
            fontWeight = FontWeight.Medium, fontFamily = DesignFonts.Manrope, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            val shape = RoundedCornerShape(999.dp)
            Box(
                Modifier.height(40.dp).pressScale(PressMotion.SCALE_ROW).clip(shape)
                    .border(1.dp, designLineColor(), shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onAction)
                    .semantics { role = Role.Button }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(actionLabel, color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope)
            }
        }
    }
}

/** 加载变体：转圈 + 文案，与 [StateBlock] 同间距规格。 */
@Composable
fun StateLoadingBlock(text: String = "加载中…", modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ACCEPT4：加载图形全仓统一为旋转唱片（v8 稿加载语言），Material 旋转圈退场。
        VinylDiscSpinner(34.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            text, color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp,
            fontWeight = FontWeight.Medium, fontFamily = DesignFonts.Manrope, textAlign = TextAlign.Center
        )
    }
}
