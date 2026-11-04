package xyz.cncnlp.soundwave

import androidx.compose.runtime.Composable
import xyz.cncnlp.soundwave.ui.settings.SleepTimerSheet

/** Compatibility entry: player and Settings share one picker and service-owned timer. */
@Composable
internal fun SleepPlayerTimerDialog(remainMs: Long, onMinutes: (Int) -> Unit, onDismiss: () -> Unit) {
    SleepTimerSheet(remainMs = remainMs, onApply = onMinutes, onDismiss = onDismiss)
}
