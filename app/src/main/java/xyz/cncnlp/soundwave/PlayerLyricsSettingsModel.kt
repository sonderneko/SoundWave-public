package xyz.cncnlp.soundwave

import kotlin.math.roundToInt

/**
 * r658c P（用户 10-20）：歌词设置统一为一组偏好——歌词字号（lyric_font_sp）、歌词行高（lyric_gap_dp）、
 * 距离模糊（lyric_blur_dp）、偏移（lyric_offset_ms）。播放器「⋯ → 歌词设置」、长按歌词「歌词操作」、设置页「歌词」
 * 三处读写的是 MainActivity 同一组状态与同一组键，任何一处改动其它处立即同步；所有播放器样式（默认 / Apple Music /
 * 歌词样式 / 沉浸封面 / 沉浸歌词模式）都由这一组值换算，不再有单独的「沉浸歌词字号 / 行高」。
 */
internal enum class PlayerLyricsSettingKey {
    FONT,
    LINE_HEIGHT,
}

/** UI-only description of one persisted lyric preference; slider positions map to the existing setting values. */
internal data class PlayerLyricsSettingSpec(
    val key: PlayerLyricsSettingKey,
    val title: String,
    val selectedValue: Int,
    val valueLabel: String,
    val values: List<Int>,
) {
    val sliderRange: ClosedFloatingPointRange<Float> = 0f..(values.lastIndex.coerceAtLeast(0).toFloat())
    val sliderValue: Float = values.indices.minByOrNull { index -> kotlin.math.abs(values[index] - selectedValue) }
        ?.toFloat() ?: 0f
    fun valueAt(position: Float): Int = values[java.lang.Math.round(position).coerceIn(0, values.lastIndex)]
}

internal object PlayerLyricsSettingsModel {
    const val DEFAULT_FONT_SP = 18
    const val DEFAULT_LINE_HEIGHT_DP = 9
    const val DEFAULT_DISTANCE_BLUR_DP = 2
    /** 旧「沉浸歌词」默认值（仅存量迁移换算用）；现行沉浸模式按 [immersiveFontFor] / [immersiveLineHeightFor] 直用统一值。 */
    const val LEGACY_IMMERSIVE_FONT_SP = 30
    const val LEGACY_IMMERSIVE_LINE_HEIGHT_DP = 14
    const val OFFSET_STEP_MS = 500
    const val OFFSET_LIMIT_MS = 30_000
    /** 一次性兼容迁移标记（旧沉浸歌词字号 / 行高 → 统一歌词字号 / 行高）。 */
    const val UNIFIED_MIGRATION_KEY = "lyric_unified_size_v658"

    private val fontValues = (10..32 step 2).toList()
    private val lineHeightValues = (6..36 step 3).toList()

    /**
     * 歌词设置核心条目（字号 / 行高）。距离模糊条目已删（用户 2026-11-04：设置项整个去掉）——
     * 渲染侧钉死 [DEFAULT_DISTANCE_BLUR_DP]，存储键与 WebDAV 投影保留惰性（见台账删除合同）。
     */
    fun menuSettings(fontSp: Int, lineHeightDp: Int): List<PlayerLyricsSettingSpec> = listOf(
        font(fontSp),
        lineHeight(lineHeightDp),
    )

    fun font(value: Int) = PlayerLyricsSettingSpec(
        PlayerLyricsSettingKey.FONT,
        "歌词字号",
        value,
        "${value}sp · 10–32",
        fontValues,
    )

    fun lineHeight(value: Int) = PlayerLyricsSettingSpec(
        PlayerLyricsSettingKey.LINE_HEIGHT,
        "歌词行高",
        value,
        "${value}dp · 6–36",
        lineHeightValues,
    )

    fun snapFont(value: Int): Int = fontValues.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_FONT_SP
    fun snapLineHeight(value: Int): Int = lineHeightValues.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_LINE_HEIGHT_DP

    /** 沉浸歌词模式字号：用户 2026-11-04 裁定三样式全页统一——统一字号原值直用（旧口径经默认页 +9 换算，已摘除）。 */
    fun immersiveFontFor(fontSp: Int): Int = fontSp

    /** 沉浸歌词模式行高：全页统一，统一行高原值直用（旧口径 ×14/9，已摘除）。 */
    fun immersiveLineHeightFor(lineHeightDp: Int): Int = lineHeightDp

    /** 「默认」播放器歌词页字号：全页统一，统一字号原值直用（旧口径 +9，已摘除）。 */
    fun defaultPageFontSp(fontSp: Int): Int = fontSp

    /** 「默认」播放器歌词页行上下留白：全页统一，统一行高原值（旧口径 ×10/9，已摘除；钳制仅防存量异常值）。 */
    fun defaultPageRowPaddingDp(lineHeightDp: Int): Float = lineHeightDp.toFloat().coerceIn(4f, 40f)

    /** 「默认」播放器歌词页距离模糊：每行 0.375×设置值（默认 2 → 0.75dp/行），上限 1.3×设置值；0 = 关闭。 */
    fun defaultPageBlurDp(distance: Int, distanceBlurDp: Int): Float =
        if (distanceBlurDp <= 0 || distance <= 0) 0f else (distance * distanceBlurDp * 0.375f).coerceAtMost(distanceBlurDp * 1.3f)

    fun stepOffset(currentMs: Int, deltaMs: Int): Int = (currentMs + deltaMs).coerceIn(-OFFSET_LIMIT_MS, OFFSET_LIMIT_MS)

    /**
     * 旧沉浸歌词偏好兼容迁移（纯函数）：只调过「沉浸歌词字号 / 行高」而没调过普通值的用户，把沉浸值按比例换算为统一值；
     * 普通值已存在时以普通值为准（它原本就与设置页共用）。返回 null 表示该项无需写入。
     */
    fun migrateLegacyImmersive(
        storedFontSp: Int?,
        storedLineHeightDp: Int?,
        storedImmersiveFontSp: Int?,
        storedImmersiveLineHeightDp: Int?,
    ): Pair<Int?, Int?> {
        val font = if (storedFontSp == null && storedImmersiveFontSp != null && storedImmersiveFontSp != LEGACY_IMMERSIVE_FONT_SP)
            snapFont((storedImmersiveFontSp * DEFAULT_FONT_SP / LEGACY_IMMERSIVE_FONT_SP.toFloat()).roundToInt()) else null
        val gap = if (storedLineHeightDp == null && storedImmersiveLineHeightDp != null && storedImmersiveLineHeightDp != LEGACY_IMMERSIVE_LINE_HEIGHT_DP)
            snapLineHeight((storedImmersiveLineHeightDp * DEFAULT_LINE_HEIGHT_DP / LEGACY_IMMERSIVE_LINE_HEIGHT_DP.toFloat()).roundToInt()) else null
        return font to gap
    }
}

/** 在一个 profile 的 SharedPreferences 上执行一次旧沉浸歌词偏好迁移（幂等，已迁移则不动）。 */
internal fun migrateLegacyImmersiveLyricPrefs(prefs: android.content.SharedPreferences) {
    if (prefs.getBoolean(PlayerLyricsSettingsModel.UNIFIED_MIGRATION_KEY, false)) return
    fun intOrNull(key: String): Int? = if (prefs.contains(key)) runCatching { prefs.getInt(key, 0) }.getOrNull() else null
    val (font, gap) = PlayerLyricsSettingsModel.migrateLegacyImmersive(
        storedFontSp = intOrNull("lyric_font_sp"),
        storedLineHeightDp = intOrNull("lyric_gap_dp"),
        storedImmersiveFontSp = intOrNull("lyric_immersive_sp"),
        storedImmersiveLineHeightDp = intOrNull("lyric_immersive_gap_dp"),
    )
    prefs.edit().apply {
        if (font != null) putInt("lyric_font_sp", font)
        if (gap != null) putInt("lyric_gap_dp", gap)
        putBoolean(PlayerLyricsSettingsModel.UNIFIED_MIGRATION_KEY, true)
    }.apply()
}

/** Default lyric ink follows the bright Apple backdrop; explicitly selected custom colors are preserved. */
internal fun playerThemeLyricHex(value: String, lightApple: Boolean, focused: Boolean): String {
    if (!lightApple) return value
    val default = if (focused) "#FFFFFF" else "#A8A8B3"
    return if (value.equals(default, ignoreCase = true)) {
        if (focused) "#151C18" else "#646C68"
    } else value
}
