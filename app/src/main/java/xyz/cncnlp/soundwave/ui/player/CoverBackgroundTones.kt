package xyz.cncnlp.soundwave.ui.player

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.Color

internal data class CoverBackgroundTones(val top: Color, val lower: Color, val artworkLuminance: Float = 0f)

/**
 * 组合期回调（Coil onSuccess，主线程）用的同步取色。
 *
 * r656-G：Coil 默认解出 HARDWARE 位图，旧实现对它 toBitmap(32,32) 会在软件 Canvas 上绘制硬件位图并抛异常、
 * 被 runCatching 吞成 null（生产环境背景永远是默认深蓝）。现在硬件位图直接返回 null —— 不在主线程做 GPU 回读，
 * 真正的取色由 [CoverToneCache.tonesFor] 在后台用 allowHardware(false) 的 64px 小图完成；软件位图（截图测试、
 * 旧设备）仍走原同步路径，结果不变。
 */
internal fun sampleCoverBackground(drawable: Drawable): CoverBackgroundTones? {
    if (drawable.isHardwareBitmap()) return null
    return sampleCoverBackgroundAnyThread(drawable)
}

internal fun Drawable.isHardwareBitmap(): Boolean =
    android.os.Build.VERSION.SDK_INT >= 26 &&
        (this as? android.graphics.drawable.BitmapDrawable)?.bitmap?.config == Bitmap.Config.HARDWARE

/** 把任意 Drawable 缩成 32×32 软件位图；硬件位图先 copy 成 ARGB_8888（仅限后台线程调用）。 */
internal fun coverSampleBitmap(drawable: Drawable): Bitmap {
    val bitmapDrawable = drawable as? android.graphics.drawable.BitmapDrawable
    val source = bitmapDrawable?.bitmap
    if (source != null && android.os.Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE) {
        val software = source.copy(Bitmap.Config.ARGB_8888, false)
        return if (software.width == 32 && software.height == 32) software
        else Bitmap.createScaledBitmap(software, 32, 32, true)
    }
    return drawable.toBitmap(32, 32, Bitmap.Config.ARGB_8888)
}

/** Downsample the decoded album art and choose two real, non-extreme color clusters for a restrained tint. */
internal fun sampleCoverBackgroundAnyThread(drawable: Drawable): CoverBackgroundTones? = runCatching {
    data class Bucket(var count: Int = 0, var red: Int = 0, var green: Int = 0, var blue: Int = 0) {
        fun average(): Color = Color(
            red = red.toFloat() / count.coerceAtLeast(1) / 255f,
            green = green.toFloat() / count.coerceAtLeast(1) / 255f,
            blue = blue.toFloat() / count.coerceAtLeast(1) / 255f
        )
    }
    val bitmap = coverSampleBitmap(drawable)
    val buckets = HashMap<Int, Bucket>()
    var sourceLuminanceSum = 0f
    var sourcePixelCount = 0
    var sourceRed = 0L
    var sourceGreen = 0L
    var sourceBlue = 0L
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        val pixel = bitmap.getPixel(x, y)
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha < 150) continue
        val red = (pixel ushr 16) and 0xFF
        val green = (pixel ushr 8) and 0xFF
        val blue = pixel and 0xFF
        val luminance = (0.2126f * red + 0.7152f * green + 0.0722f * blue) / 255f
        sourceLuminanceSum += luminance
        sourcePixelCount++
        sourceRed += red
        sourceGreen += green
        sourceBlue += blue
        if (luminance < 0.055f || luminance > 0.94f) continue
        val key = ((red shr 5) shl 6) or ((green shr 5) shl 3) or (blue shr 5)
        val bucket = buckets.getOrPut(key) { Bucket() }
        bucket.count++
        bucket.red += red
        bucket.green += green
        bucket.blue += blue
    }
    val ranked = buckets.values.sortedByDescending { it.count }
    if (sourcePixelCount == 0) return@runCatching null
    val sourceAverage = Color(
        red = sourceRed.toFloat() / sourcePixelCount / 255f,
        green = sourceGreen.toFloat() / sourcePixelCount / 255f,
        blue = sourceBlue.toFloat() / sourcePixelCount / 255f
    )
    val dominant = ranked.firstOrNull()?.average() ?: sourceAverage
    val second = ranked.drop(1).firstOrNull { candidate ->
        val c = candidate.average()
        kotlin.math.abs(c.red - dominant.red) + kotlin.math.abs(c.green - dominant.green) + kotlin.math.abs(c.blue - dominant.blue) > 0.42f
    }?.average() ?: dominant
    fun muted(sample: Color, base: Color, amount: Float) = Color(
        red = base.red * (1f - amount) + sample.red * amount,
        green = base.green * (1f - amount) + sample.green * amount,
        blue = base.blue * (1f - amount) + sample.blue * amount
    )
    CoverBackgroundTones(
        top = muted(dominant, Color(0xFF111927.toInt()), 0.78f),
        lower = muted(second, Color(0xFF0B1018.toInt()), 0.72f),
        artworkLuminance = (sourceLuminanceSum / sourcePixelCount).coerceIn(0f, 1f)
    )
}.getOrNull()
