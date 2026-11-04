package xyz.cncnlp.soundwave.ui.common

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/*
 * r673 release crash（1.0.672，vivo V2453A / Android 16，打开播放器即闪退）：
 * R8 把 FullPlayer 的整个函数体（所有 inline Box/Column 内容 + 只有一个调用点的局部函数）合成了一个
 * 18,563 code units / 331 寄存器的 dex 方法（混淆名 A6.P1），ART 校验器拒绝整个类：
 *   java.lang.VerifyError … [0x2C12] copy-cat2 v6<-v39 type=Long (Low Half)/PositiveByteConstant
 *
 * 下面三个宿主刻意不是 inline：传入的内容会被 Compose 编译成独立的 composable lambda 类（经 ComposableLambda
 * 接口调用），R8 无法把它们内联回调用方，于是一个超大 @Composable 可以拆成若干个各自独立校验的 dex 方法，
 * 行为与组合语义不变（lambda 读取的状态仍按原样重组）。
 *
 * 规则：不要把它们改成 inline，也不要把内容挪回调用方主体。
 * 回归门禁：scripts/release-dex-method-size-guard.sh（发布 APK 里任何 app 方法寄存器 / 体积越线即失败）。
 */

@Composable
internal fun IsolatedSection(content: @Composable () -> Unit) {
    content()
}

@Composable
internal fun BoxScope.IsolatedBoxSection(content: @Composable BoxScope.() -> Unit) {
    content()
}

@Composable
internal fun ColumnScope.IsolatedColumnSection(content: @Composable ColumnScope.() -> Unit) {
    content()
}
