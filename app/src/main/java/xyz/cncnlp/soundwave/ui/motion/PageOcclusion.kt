package xyz.cncnlp.soundwave.ui.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import xyz.cncnlp.soundwave.LocalDecorativeActive

/**
 * r657 第三轮 b（卡顿）：首页是「常驻下层」——切到其它 Tab、推入详情、打开全屏播放器后它仍在组合树里，
 * 旧实现继续每帧绘制它（含封面、跑马灯、律动条），等于每帧多画一整页。这里集中记录遮挡状态：
 * - 不透明 Tab 页（搜索 / 音乐库 / 我的）入场完成、或详情完全推入（覆盖度 1）→ 首页跳过绘制且停止装饰循环；
 * - 全屏播放器打开 → 只停止装饰循环（下拉收起时需要露出底页，因此不跳过绘制）。
 * 任一转场进行中都照常绘制，返回动画 / 共享封面反向飞行不受影响。
 */
object PageOcclusion {
    internal val tabCovers = mutableStateOf(false)
    internal val playerCovers = mutableStateOf(false)

    /** 纯函数（单测）：首页是否可以跳过绘制。 */
    fun homeDrawSkippable(tabCovers: Boolean, detailCoverage: Float): Boolean = tabCovers || detailCoverage >= 1f

    /** 纯函数（单测）：首页装饰循环是否应运行。 */
    fun homeDecorativeActive(tabCovers: Boolean, detailCoverage: Float, playerCovers: Boolean): Boolean =
        !homeDrawSkippable(tabCovers, detailCoverage) && !playerCovers

    fun setTabCovers(covers: Boolean) { if (tabCovers.value != covers) tabCovers.value = covers }
    fun setPlayerCovers(covers: Boolean) { if (playerCovers.value != covers) playerCovers.value = covers }
}

@Composable
fun OccludableHomeLayer(content: @Composable () -> Unit) {
    val active by remember {
        derivedStateOf {
            PageOcclusion.homeDecorativeActive(PageOcclusion.tabCovers.value, DetailPush.coverage.floatValue, PageOcclusion.playerCovers.value)
        }
    }
    CompositionLocalProvider(LocalDecorativeActive provides active) {
        Box(Modifier.fillMaxSize().drawWithContent {
            if (!PageOcclusion.homeDrawSkippable(PageOcclusion.tabCovers.value, DetailPush.coverage.floatValue)) drawContent()
        }) { content() }
    }
}
