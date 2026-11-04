package xyz.cncnlp.soundwave.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/**
 * 长文本单行跑马灯：超出宽度时循环横向滚动（替代省略号）；未超出时保持静止。
 * 用于首页歌名/歌手名、播放器标题等单行文本：Text(maxLines = 1, modifier = Modifier.swMarquee())。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.swMarquee(): Modifier =
    if (xyz.cncnlp.soundwave.MotionPolicy.decorativeLoopsFrozen) this // 截图 / 单测停在首帧，真机不变
    else composed {
        // r657 第三轮 b（卡顿）：遵循减少动效（宿主 CompositionLocal，无逐行订阅）；被遮住的常驻首页不再滚动。
        if (xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion() || !xyz.cncnlp.soundwave.LocalDecorativeActive.current) this
        else this.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1600)
    }
