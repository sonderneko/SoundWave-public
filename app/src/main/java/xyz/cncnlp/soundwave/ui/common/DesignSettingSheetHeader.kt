package xyz.cncnlp.soundwave.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.SoundwaveDesignPalette

/** Reference-inspired light orb, scoped to value sheets rather than changing every sheet.
 * Pure decoration: no focus target, hit target, perpetual animation or bitmap allocation.
 * The existing sheet owns its background, typography, insets and motion policy.
 */
@Composable
fun DesignSettingSheetHeader(eyebrow: String, title: String) {
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { DesignSheetHeader(eyebrow, title) }
        Spacer(Modifier.width(12.dp))
        Canvas(Modifier.size(48.dp).clearAndSetSemantics {}) {
            val radius = size.minDimension / 2f
            drawCircle(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.22f), colors.primary.copy(alpha = 0.08f), Color.Transparent),
                    center = center, radius = radius
                )
            )
            drawCircle(
                Brush.radialGradient(
                    listOf(colors.surface, accent.copy(alpha = 0.72f), colors.primary.copy(alpha = 0.48f)),
                    center = Offset(size.width * 0.37f, size.height * 0.32f), radius = radius * 1.1f
                ), radius = radius * 0.64f
            )
        }
    }
}
