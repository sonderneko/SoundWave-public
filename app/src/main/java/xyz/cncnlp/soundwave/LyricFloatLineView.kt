package xyz.cncnlp.soundwave

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * r656：悬浮歌词绘制视图（替代两个 TextView），解决卡拉 OK 配色与抖动问题：
 *
 * - 配色：底字固定白色（未唱），已唱部分按词轴进度裁剪后用用户歌词色覆盖；无词轴时按整句时长线性填充。
 *   不再用同一颜色画两层。
 * - 不抖动：主句单行、固定行高、固定视图尺寸（宽度由窗口给定，不随文本 re-layout）；
 *   不再给「正在唱的词」加粗（加粗会改变文本宽度，居中文本因此左右跳）。
 * - 平滑：播放位置以「锚点位置 + 自锚点起经过的时间」在每一帧插值（postInvalidateOnAnimation，跟随屏幕刷新），
 *   而不是只在 400ms 一次的服务 tick 上跳变；暂停时停止外推并停止重绘。
 * - 长句：宽于视图时按已唱位置平滑水平滚动（保持已唱前沿在视图约 60% 处），短句固定居中、偏移恒定。
 * - 换句：主句上移淡出、下一句从下方滑入淡入（约 300ms），行高不变，窗口尺寸不变。
 */
internal class LyricFloatLineView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val scaled = context.resources.displayMetrics.scaledDensity

    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        // J 波（对齐设计稿 .flyric）：文字带轻阴影，压在任意壁纸上可读。
        setShadowLayer(2f * context.resources.displayMetrics.density, 0f, 1f * context.resources.displayMetrics.density, AColor.argb(130, 0, 0, 0))
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(1.5f * context.resources.displayMetrics.density, 0f, 1f * context.resources.displayMetrics.density, AColor.argb(110, 0, 0, 0))
    }

    private var fontSp = 16f
    private var playedColor = AColor.rgb(42, 120, 255)
    private var textAlphaF = 1f
    var showSub: Boolean = true
        private set

    // 当前句与上一句（换句过渡用）
    private var text: String = ""
    private var sub: String = ""
    private var words: List<LyricWord> = emptyList()
    private var lineStartMs = 0L
    private var lineEndMs = 0L
    private var karaoke = false
    private var prevText: String = ""
    private var prevSub: String = ""
    private var prevFill = 0f

    // 位置插值
    private var anchorPosMs = 0L
    private var anchorUptime = 0L
    private var playing = false

    // 换句过渡
    private var transitionStart = 0L
    private val transitionMs = 300L
    private val ease = DecelerateInterpolator(1.6f)

    fun applyStyle(fontSp: Float, playedColor: Int, textAlpha: Float, showSub: Boolean) {
        val changedSize = this.fontSp != fontSp || this.showSub != showSub
        this.fontSp = fontSp
        this.playedColor = playedColor
        this.textAlphaF = textAlpha.coerceIn(0f, 1f)
        this.showSub = showSub
        mainPaint.textSize = fontSp * scaled
        subPaint.textSize = fontSp * 0.82f * scaled
        if (changedSize) requestLayout()
        invalidate()
    }

    /** 无词轴的纯文本（歌名 / 歌手占位）：整句白色，不做卡拉 OK。 */
    fun setStatic(main: String, sub: String) {
        setContent(main, sub, emptyList(), 0L, 0L, karaoke = false)
    }

    /**
     * 歌词句：[posMs] 为服务给出的真实播放位置（锚点），[nextStartMs] 为下一句起点（无词轴时线性填充用）。
     */
    fun setLine(line: LyricLine?, sub: String, posMs: Long, nextStartMs: Long, playing: Boolean) {
        val t = line?.text.orEmpty().ifBlank { "♪ SoundWave" }
        val w = line?.let { LyricParser.effectiveWords(it, null) }.orEmpty()
        val start = line?.timeMs ?: 0L
        val end = when {
            w.isNotEmpty() -> w.last().endMs
            nextStartMs > start -> nextStartMs
            else -> start + 4000L
        }
        anchorPosMs = posMs
        anchorUptime = SystemClock.uptimeMillis()
        this.playing = playing
        setContent(t, sub, w, start, end, karaoke = line != null)
        kick()
    }

    private fun setContent(main: String, sub: String, words: List<LyricWord>, start: Long, end: Long, karaoke: Boolean) {
        if (main != text) {
            if (text.isNotEmpty()) {
                prevText = text
                prevSub = this.sub
                prevFill = if (this.karaoke) 1f else 0f
                transitionStart = SystemClock.uptimeMillis()
            }
            text = main
        }
        this.sub = sub
        this.words = words
        this.lineStartMs = start
        this.lineEndMs = end
        this.karaoke = karaoke
        kick()
    }

    private fun kick() { postInvalidateOnAnimation() }

    private fun currentPosMs(now: Long): Long {
        if (!playing) return anchorPosMs
        // 服务约每 400ms 更新一次锚点；最多外推 1.2s，避免锚点停更（暂停 / 卡顿）后继续走。
        return anchorPosMs + (now - anchorUptime).coerceIn(0L, 1200L)
    }

    private fun fillFraction(pos: Long): Float {
        if (!karaoke) return 0f
        if (words.isNotEmpty()) return LyricParser.wordClipFraction(words, pos)
        if (lineEndMs <= lineStartMs) return 1f
        return ((pos - lineStartMs).toFloat() / (lineEndMs - lineStartMs)).coerceIn(0f, 1f)
    }

    private fun rowMain(): Float = mainPaint.fontMetrics.let { it.descent - it.ascent } + 4f * density
    private fun rowSub(): Float = subPaint.fontMetrics.let { it.descent - it.ascent } + 2f * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (rowMain() + if (showSub) rowSub() else 0f).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(w, h)
    }

    /** 未唱底色：白色；若用户歌词色本身接近白色，底字降为 55% 白，保证已唱 / 未唱可区分。 */
    private fun baseColor(): Int {
        val lum = (0.2126f * AColor.red(playedColor) + 0.7152f * AColor.green(playedColor) + 0.0722f * AColor.blue(playedColor)) / 255f
        return if (lum > 0.86f) AColor.argb(140, 255, 255, 255) else AColor.WHITE
    }

    /**
     * J 波（对齐设计稿 .flyric「整行取已唱色」）：卡拉 OK 行的未唱部分不再用白色，
     * 改已唱色 50%——整行同一色系、进度仍可辨。已唱色接近白色时回退原白色逻辑
     * （否则已唱/未唱无从区分）；无词轴的静态行（歌名占位）保持白色。
     */
    private fun unsungPaintColor(alpha: Float): Int {
        if (!karaoke) return withAlpha(baseColor(), alpha)
        val lum = (0.2126f * AColor.red(playedColor) + 0.7152f * AColor.green(playedColor) + 0.0722f * AColor.blue(playedColor)) / 255f
        return if (lum > 0.86f) withAlpha(baseColor(), alpha) else withAlpha(playedColor, alpha * 0.5f)
    }

    private fun withAlpha(color: Int, a: Float): Int =
        AColor.argb((AColor.alpha(color) * a * textAlphaF).toInt().coerceIn(0, 255), AColor.red(color), AColor.green(color), AColor.blue(color))

    private fun drawKaraokeLine(canvas: Canvas, s: String, baselineY: Float, fill: Float, alpha: Float, rowTop: Float, rowBottom: Float) {
        if (s.isEmpty() || alpha <= 0f) return
        val avail = (width - paddingLeft - paddingRight).toFloat()
        val textW = mainPaint.measureText(s)
        val sungX = textW * fill
        // J 波（对齐设计稿 .flyric 左对齐）：放得下时左锚；溢出时保留原「已唱锚点 60%」跟随滚动。
        val x0 = if (textW <= avail) paddingLeft.toFloat()
        else paddingLeft - (sungX - avail * 0.6f).coerceIn(0f, textW - avail)
        canvas.save()
        canvas.clipRect(paddingLeft.toFloat(), rowTop, (width - paddingRight).toFloat(), rowBottom)
        // 底字只画未唱区间（已唱区间不叠底，避免已唱字边缘透出底色描边）。
        canvas.save()
        canvas.clipRect(x0 + sungX, rowTop, (width - paddingRight).toFloat().coerceAtLeast(x0 + sungX), rowBottom)
        mainPaint.color = unsungPaintColor(alpha)
        canvas.drawText(s, x0, baselineY, mainPaint)
        canvas.restore()
        if (fill > 0f) {
            canvas.save()
            canvas.clipRect(x0, rowTop, x0 + sungX, rowBottom)
            mainPaint.color = withAlpha(playedColor, alpha)
            canvas.drawText(s, x0, baselineY, mainPaint)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawSubLine(canvas: Canvas, s: String, baselineY: Float, alpha: Float) {
        if (s.isEmpty() || alpha <= 0f) return
        val avail = (width - paddingLeft - paddingRight).toFloat()
        var shown = s
        if (subPaint.measureText(shown) > avail) {
            val n = subPaint.breakText(s, true, avail - subPaint.measureText("…"), null)
            shown = s.take(n.coerceAtLeast(0)) + "…"
        }
        val w = subPaint.measureText(shown)
        subPaint.color = withAlpha(AColor.argb(190, 255, 255, 255), alpha)
        // J 波：次行同主行左对齐（原居中）。
        canvas.drawText(shown, paddingLeft.toFloat(), baselineY, subPaint)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val rowM = rowMain()
        val rowS = rowSub()
        val top = paddingTop.toFloat()
        val mainBaseline = top + 2f * density - mainPaint.fontMetrics.ascent
        val subBaseline = top + rowM + 1f * density - subPaint.fontMetrics.ascent
        val tRaw = if (transitionStart == 0L) 1f else ((now - transitionStart).toFloat() / transitionMs).coerceIn(0f, 1f)
        val t = ease.getInterpolation(tRaw)
        val shift = rowM * 0.75f
        val fill = fillFraction(currentPosMs(now))
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), height - paddingBottom.toFloat())
        if (tRaw < 1f && prevText.isNotEmpty()) {
            // 旧主句上移淡出；新主句自下方滑入淡入；副句同步淡换。
            drawKaraokeLine(canvas, prevText, mainBaseline - shift * t, prevFill, 1f - t, top, top + rowM)
            drawKaraokeLine(canvas, text, mainBaseline + shift * (1f - t), fill, t, top, top + rowM)
            if (showSub) {
                drawSubLine(canvas, prevSub, subBaseline - rowS * 0.5f * t, 1f - t)
                drawSubLine(canvas, sub, subBaseline + rowS * 0.5f * (1f - t), t)
            }
        } else {
            if (tRaw >= 1f) { transitionStart = 0L; prevText = "" }
            drawKaraokeLine(canvas, text, mainBaseline, fill, 1f, top, top + rowM)
            if (showSub) drawSubLine(canvas, sub, subBaseline, 1f)
        }
        canvas.restore()
        val animatingFill = karaoke && playing && fill < 1f && now - anchorUptime < 1200L
        if (tRaw < 1f || animatingFill) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        transitionStart = 0L
        prevText = ""
    }
}
