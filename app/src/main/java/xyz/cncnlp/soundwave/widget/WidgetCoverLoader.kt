package xyz.cncnlp.soundwave.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import xyz.cncnlp.soundwave.CacheManager
import xyz.cncnlp.soundwave.NotificationCoverCodec
import java.io.File
import java.net.URI

/**
 * Widget artwork comes only from covers already cached on disk — the same local-first
 * rule as the notification stack. A remote cover that is not cached yet renders as the
 * app-icon placeholder; the widget never starts a download and never blocks the
 * launcher process: every caller runs this on a background executor.
 */
internal object WidgetCoverLoader {

    /** Center-cropped, corner-rounded artwork for [coverUrl], or null when not cached locally. */
    fun loadRounded(context: Context, coverUrl: String, sizePx: Int): Bitmap? {
        if (coverUrl.isBlank() || sizePx <= 0) return null
        val file = localFile(context, coverUrl) ?: return null
        val decoded = NotificationCoverCodec.decode(file) ?: return null
        return cropRound(decoded, sizePx)
    }

    /** Reuses an already-decoded bitmap (e.g. the service's notification cover). */
    fun roundedFrom(source: Bitmap, sizePx: Int): Bitmap? {
        if (sizePx <= 0) return null
        return cropRound(source, sizePx)
    }

    /**
     * CacheManager.coverModel is the app's cache-only lookup: it returns a File for
     * index hits, a "file:"/absolute string for local media, and the bare remote URL
     * when the cover is not on disk. Only the first two are widget-usable.
     */
    private fun localFile(context: Context, coverUrl: String): File? {
        return try {
            when (val model = CacheManager.coverModel(context, coverUrl)) {
                is File -> model.takeIf { it.isFile }
                is String -> when {
                    model.startsWith("file:") -> runCatching { File(URI(model)) }.getOrNull()?.takeIf { it.isFile }
                    model.startsWith("/") -> File(model).takeIf { it.isFile }
                    else -> null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cropRound(src: Bitmap, sizePx: Int): Bitmap {
        val side = minOf(src.width, src.height)
        if (side <= 0) return src
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        val radius = sizePx * 0.22f
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val dest = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        canvas.drawRoundRect(dest, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(src, Rect(left, top, left + side, top + side), dest, paint)
        return out
    }
}
