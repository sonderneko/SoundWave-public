package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.toArgb
import xyz.cncnlp.soundwave.ui.player.CoverBackgroundTones

/**
 * 媒体通知着色口径：跟随当前歌曲封面的取色结果（[xyz.cncnlp.soundwave.ui.player.CoverToneCache]
 * 的 top 色调——与全屏播放器背景、磨砂面同一套聚类取色，不另起第二套提取器）；无封面或取色
 * 不可用时回落品牌珊瑚，即旧行为的写死值，呈现不比旧版差。
 *
 * 后续小组件 / 播放器氛围若要封面色，同样走 CoverToneCache + 本口径，不要再各写一份。
 */
internal object NotificationTint {

    /** 旧行为的写死珊瑚（ThemeTokens.PrimaryCoralDeep），保持为唯一回落值。 */
    val FallbackArgb: Int = ThemeTokens.PrimaryCoralDeep.toArgb()

    /** 取色结果 → 通知 accent；null（无封面 / 未算出 / 提取失败）回落珊瑚。 */
    fun accentArgb(tones: CoverBackgroundTones?): Int = tones?.top?.toArgb() ?: FallbackArgb
}
