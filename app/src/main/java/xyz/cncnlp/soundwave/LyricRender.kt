package xyz.cncnlp.soundwave

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val KARAOKE_WORD_LIFT_MAX_DP = 2.5f
internal const val KARAOKE_WORD_ANIMATION_MS = 180

internal fun karaokeWordLiftDp(progress: Float): Float =
    KARAOKE_WORD_LIFT_MAX_DP * progress.coerceIn(0f, 1f)

@Composable
private fun lyricWordVisualProgress(progress: Float, animate: Boolean): Float {
    if (!animate) return progress
    return animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = KARAOKE_WORD_ANIMATION_MS, easing = LinearEasing),
        label = "lyricWordProgress"
    ).value
}

/** 当前句高亮：真实 provider 词轴逐字推进；普通带时间戳歌词整行高亮。 */
fun karaokeProgress(line: LyricLine, next: LyricLine?, curMs: Long): Float =
    LyricParser.lineProgress(line, next, curMs)

/** 兼容旧调用：未提供行结束时间时只表达整行开始与否，不合成逐字时间轴。 */
fun karaokeProgress(lineTimeMs: Long, curMs: Long): Float {
    return if (lineTimeMs >= 0L && curMs >= lineTimeMs) 1f else 0f
}

/**
 * 真·逐字卡拉OK：
 * - 仅有 provider YRC/QRC/增强 LRC 词轴 → 按真实字时间刷亮
 * - 普通 LRC → 整行按 provider 行时间高亮，不推断逐字时间
 * - 每个真实时间单元独立渲染，换行后不会两行被同一条横向 clip 一起刷
 * - 真实词轴上的轻微上浮由调用方统一开关控制；无词轴时不做逐字动画
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KaraokeLine(
    line: LyricLine,
    next: LyricLine?,
    curMs: Long,
    color: Color,
    fontSize: TextUnit,
    lineHeightSp: Float,
    textAlign: TextAlign,
    startAlign: Boolean,
    liftWords: Boolean = false,
    highlightWords: Boolean = true
) {
    val words = LyricParser.effectiveWords(line, next)
    val text = line.text
    val fg = color
    val dim = if (highlightWords) color.copy(alpha = 0.34f) else color

    if (words.isEmpty() || text.isEmpty()) {
        val clipFraction = LyricParser.lineProgress(line, next, curMs)
        Box(
            modifier = if (startAlign) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(),
            contentAlignment = if (startAlign) Alignment.CenterStart else Alignment.Center
        ) {
            Text(
                text, color = dim, fontSize = fontSize, lineHeight = lineHeightSp.sp,
                textAlign = textAlign, fontFamily = LocalLyricFontFamily.current,
                fontWeight = FontWeight.Bold, maxLines = 8, softWrap = true, overflow = TextOverflow.Visible,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text, color = fg, fontSize = fontSize, lineHeight = lineHeightSp.sp,
                textAlign = textAlign, fontFamily = LocalLyricFontFamily.current,
                fontWeight = FontWeight.Bold, maxLines = 8, softWrap = true, overflow = TextOverflow.Visible,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        val w = size.width * clipFraction.coerceIn(0f, 1f)
                        clipRect(left = 0f, top = 0f, right = w, bottom = size.height) {
                            this@drawWithContent.drawContent()
                        }
                    }
            )
        }
        return
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (startAlign) Arrangement.Start else Arrangement.Center,
        verticalArrangement = Arrangement.Center
    ) {
        words.forEachIndexed { index, w ->
            val frac = when {
                curMs >= w.endMs -> 1f
                curMs >= w.startMs -> {
                    val span = (w.endMs - w.startMs).coerceAtLeast(1L).toFloat()
                    ((curMs - w.startMs) / span).coerceIn(0f, 1f)
                }
                else -> 0f
            }
            val visualFraction = lyricWordVisualProgress(frac, animate = liftWords)
            val wordLiftDp = if (liftWords) karaokeWordLiftDp(visualFraction).dp else 0.dp
            Box(
                modifier = (if (wordLiftDp > 0.dp) Modifier.offset(y = -wordLiftDp) else Modifier).testTag("lyric-word-$index"),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    w.text,
                    color = dim,
                    fontSize = fontSize,
                    lineHeight = lineHeightSp.sp,
                    fontFamily = LocalLyricFontFamily.current,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                if (visualFraction > 0f) {
                    Text(
                        w.text,
                        color = fg,
                        fontSize = fontSize,
                        lineHeight = lineHeightSp.sp,
                        fontFamily = LocalLyricFontFamily.current,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.drawWithContent {
                            val ww = size.width * visualFraction.coerceIn(0f, 1f)
                            clipRect(left = 0f, top = 0f, right = ww, bottom = size.height) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 旧签名兼容：无词轴时按 progress 裁剪整句。 */
@Composable
fun KaraokeLine(
    text: String,
    progress: Float,
    color: Color,
    fontSize: TextUnit,
    lineHeightSp: Float,
    textAlign: TextAlign,
    startAlign: Boolean
) {
    val fg = color
    val dim = color.copy(alpha = 0.34f)
    val clipFraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = if (startAlign) Modifier.wrapContentWidth() else Modifier.fillMaxWidth(),
        contentAlignment = if (startAlign) Alignment.CenterStart else Alignment.Center
    ) {
        Text(
            text, color = dim, fontSize = fontSize, lineHeight = lineHeightSp.sp,
            textAlign = textAlign, fontFamily = LocalLyricFontFamily.current,
            fontWeight = FontWeight.Bold, maxLines = 8, softWrap = true, overflow = TextOverflow.Visible
        )
        Text(
            text, color = fg, fontSize = fontSize, lineHeight = lineHeightSp.sp,
            textAlign = textAlign, fontFamily = LocalLyricFontFamily.current,
            fontWeight = FontWeight.Bold, maxLines = 8, softWrap = true, overflow = TextOverflow.Visible,
            modifier = Modifier.drawWithContent {
                val w = size.width * clipFraction
                clipRect(left = 0f, top = 0f, right = w, bottom = size.height) {
                    this@drawWithContent.drawContent()
                }
            }
        )
    }
}
