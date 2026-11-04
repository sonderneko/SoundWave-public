package xyz.cncnlp.soundwave

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF

/**
 * r656：通知栏 / 媒体控件右侧「词」悬浮歌词开关图标。
 *
 * - 关闭：灰色半透明「词」；开启：珊瑚主色实心「词」（与设计稿 accent 一致）。
 *   部分系统会把通知动作图标统一着色，因此开/关同时用透明度区分，着色后仍能看出状态。
 * - 锁定（长按锁定悬浮歌词）：缩小的实心挂锁以角标形式叠在「词」右下角；挂锁四周先挖出一圈透明描边，
 *   叠在字上也清晰可辨（被系统单色着色时同样成立）。
 */
internal object NotificationLyricGlyph {
    const val SIZE = 112
    const val ACCENT_ARGB = 0xFFFF5C45.toInt()
    const val OFF_ARGB = 0x998A8A8A.toInt()

    /** 「词」颜色：开启 = 珊瑚主色，关闭 = 60% 灰。 */
    fun wordColor(enabled: Boolean): Int = if (enabled) ACCENT_ARGB else OFF_ARGB

    /** 挂锁角标外接框（像素坐标，112×112 画布）：占画布约 22%，压在「词」右下角。 */
    val lockBodyRect = RectF(83f, 87f, 105f, 106f)
    val lockShackleRect = RectF(87f, 76f, 101f, 94f)

    fun render(enabled: Boolean, locked: Boolean): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = wordColor(enabled)
            textSize = 88f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("词", 53f, 84f, word)
        if (enabled && locked) drawLockBadge(canvas, ACCENT_ARGB)
        else if (enabled) drawCheckBadge(canvas)
        return out
    }

    private fun drawCheckBadge(canvas: Canvas) {
        val path = android.graphics.Path().apply { moveTo(85f, 97f); lineTo(92f, 104f); lineTo(106f, 87f) }
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 10f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        canvas.drawPath(path, clear)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT_ARGB; style = Paint.Style.STROKE; strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, ink)
    }

    private fun drawLockBadge(canvas: Canvas, color: Int) {
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val gap = 3f
        // 1) 挖出透明外圈：锁体 + 锁梁各外扩 gap。
        canvas.drawRoundRect(RectF(lockBodyRect.left - gap, lockBodyRect.top - gap, lockBodyRect.right + gap, lockBodyRect.bottom + gap), 5f, 5f, clear)
        clear.style = Paint.Style.STROKE
        clear.strokeWidth = 4f + gap * 2
        canvas.drawArc(lockShackleRect, 180f, 180f, false, clear)
        clear.style = Paint.Style.FILL
        // 2) 实心锁体 + 粗锁梁。
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(lockBodyRect, 4f, 4f, fill)
        val shackle = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND }
        canvas.drawArc(lockShackleRect, 180f, 180f, false, shackle)
        canvas.drawLine(lockShackleRect.left, (lockShackleRect.top + lockShackleRect.bottom) / 2f, lockShackleRect.left, lockBodyRect.top + 2f, shackle)
        canvas.drawLine(lockShackleRect.right, (lockShackleRect.top + lockShackleRect.bottom) / 2f, lockShackleRect.right, lockBodyRect.top + 2f, shackle)
        // 3) 镂空锁孔。
        val cx = lockBodyRect.centerX()
        canvas.drawCircle(cx, lockBodyRect.top + 7f, 2.5f, clear)
        canvas.drawRect(cx - 1f, lockBodyRect.top + 7f, cx + 1f, lockBodyRect.top + 14f, clear)
    }
}
