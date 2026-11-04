package xyz.cncnlp.soundwave.source.direct

/** Provider interval/DURATION/albuminfo.duration fields documented and observed in seconds.
 * Song.duration is milliseconds. Unknown/negative/overflow inputs stay unknown, never wrap.
 */
internal fun secondsToDurationMs(seconds: Long): Long =
    if (seconds <= 0L || seconds > Long.MAX_VALUE / 1000L) 0L else seconds * 1000L
