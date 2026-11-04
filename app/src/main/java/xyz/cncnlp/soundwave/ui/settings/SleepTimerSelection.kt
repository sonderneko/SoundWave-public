package xyz.cncnlp.soundwave.ui.settings

/** UI selection only; PlayerService remains the sole countdown/deadline owner. */
/** r658c #13（用户 10-19）：睡眠定时最长 24 小时，滑杆 / 预设直接可选到 24h。 */
internal const val SLEEP_MAX_MINUTES = 1440

/** -1 = finish current track, 0 = off, positive = ceiling minutes remaining. */
internal fun sleepMinutesFromRemain(remainMs: Long): Int = when {
    remainMs >= Long.MAX_VALUE / 8 -> -1
    remainMs > 0 -> ((remainMs + 59_999L) / 60_000L).coerceIn(1L, SLEEP_MAX_MINUTES.toLong()).toInt()
    else -> 0
}

internal fun sleepCustomMinutes(input: String): Int? =
    input.toIntOrNull()?.takeIf { it in 1..SLEEP_MAX_MINUTES }

internal fun sleepValueLabel(minutes: Int): String = when {
    minutes < 0 -> "播完当前"
    minutes == 0 -> "未开启"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    minutes > 60 -> "${minutes / 60} 小时 ${minutes % 60} 分"
    else -> "$minutes 分钟"
}
internal const val SLEEP_MIN_MINUTES = 1
