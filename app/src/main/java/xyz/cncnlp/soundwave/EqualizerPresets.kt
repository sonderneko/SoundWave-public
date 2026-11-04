package xyz.cncnlp.soundwave

/**
 * 均衡器预设的单一事实源：PlayerService 应用增益与设置页（曲线呈现/预设说明）共用同一张表，
 * 避免呈现口径与实际增益漂移。增益单位为毫贝尔（mB），5 段，与 android.media.audiofx.Equalizer 口径一致。
 * 索引与 EQ_NAMES / eq_preset 偏好一致：0 关闭、1 流行、2 摇滚、3 爵士、4 古典、5 人声。
 */
internal object EqualizerPresets {
    val GAINS_MB: List<IntArray> = listOf(
        intArrayOf(0, 0, 0, 0, 0), // 关闭：PlayerService 对 0 直接释放 EQ，此行仅作呈现口径（平直）
        intArrayOf(500, 400, 300, 400, 500), // 流行：中频微抬
        intArrayOf(700, 500, 300, 500, 700), // 摇滚：高低频抬
        intArrayOf(400, 300, 200, 300, 400), // 爵士：温和
        intArrayOf(200, 300, 400, 500, 600), // 古典：高频渐抬
        intArrayOf(300, 300, 300, 300, 300) // 人声/语音
    )

    /** 设置页预设说明（缺三补上下文：不只给预设名，给声音风格说明）。 */
    val DESCRIPTIONS: List<String> = listOf(
        "原始音色，不做任何增益",
        "中频微抬，人声更靠前",
        "高低两端增强，律动更强",
        "整体温和，听感松弛",
        "高频渐抬，细节与空间感更好",
        "全频段均匀抬升，人声突出"
    )

    fun gains(preset: Int): IntArray = GAINS_MB[preset.coerceIn(0, GAINS_MB.lastIndex)]

    fun description(preset: Int): String = DESCRIPTIONS.getOrElse(preset) { "" }
}
