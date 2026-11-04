package xyz.cncnlp.soundwave.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * P1 / SW-12-05：全仓动效唯一出口（规范 B.1）。
 *
 * 已被用户确认过的数值（详情推入 360/300、抽屉 420/220、错峰 620/60、按压弹簧 1.0/1800 与
 * 0.55/400 等）原样迁入本文件，只是收口为单一来源，数值本身不变。新代码禁止再写裸时长 /
 * 裸曲线 / 裸弹簧，一律引用这里。
 */
object MotionTokens {

    /** 时长（ms）。 */
    object Duration {
        /** 颜色、透明度微变化；主 Tab 出场。 */
        const val Micro = 90
        /** 图标替换、小元素淡入。 */
        const val Short = 150
        /** 卡片展开、行高变化。 */
        const val Medium = 250
        /** 详情推入、设置子页推入、播放器打开（tween 兜底）。已确认值。 */
        const val Enter = 360
        /** 抽屉进场。已确认值。 */
        const val SheetEnter = 420
        /** 抽屉与浮层退场（退场 ≤ 进场的 60%）。已确认值。 */
        const val Exit = 220
        /** 详情返回。已确认值。 */
        const val PageExit = 300
        /** 首屏错峰单项时长。已确认值。 */
        const val Stagger = 620
        /** 首屏错峰间隔（最多 8 级）。已确认值。 */
        const val StaggerStep = 60
        /** B.1 表中的别名，与 [StaggerStep] 同值。 */
        const val Step = StaggerStep
    }

    /** 缓动曲线。属性类型分别为 CubicBezierEasing / LinearEasing，均实现 Easing。 */
    object Easing {
        /** 在屏内移动 (0.2, 0, 0, 1)。 */
        val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        /** 进入屏幕 (0.05, 0.7, 0.1, 1)。已确认值（原型 emphasized-decelerate）。 */
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        /** 离开屏幕 (0.3, 0, 0.8, 0.15)。已确认值（原型 emphasized-accelerate）。 */
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        /** 只用于进度、循环、光晕扩散。 */
        val Linear = LinearEasing
    }

    /**
     * 弹簧参数，统一以 (dampingRatio, stiffness) 对提供；需要 SpringSpec 时用 [spec]
     * 或对应的 `XxxSpec` 属性。已确认值原样迁入。
     */
    object Spring {
        /** 按下。已确认值（原 PressMotion.pressSpec）。 */
        val Press: Pair<Float, Float> = 1.0f to 1800f
        /** 松开回弹。已确认值（原 PressMotion.releaseSpec，stiffness 即 StiffnessMediumLow=400）。 */
        val Release: Pair<Float, Float> = 0.55f to 400f
        /** 开关滑块、芯片、小图标位移。 */
        val FastSpatial: Pair<Float, Float> = 0.9f to 1400f
        /** 抽屉回弹、列表项让位、指示块。 */
        val DefaultSpatial: Pair<Float, Float> = 0.85f to 500f
        /** 全屏播放器开合（带松手速度）。 */
        val SlowSpatial: Pair<Float, Float> = 0.9f to 380f
        /** 底栏指示块（替代 520ms 假弹簧曲线）。 */
        val Expressive: Pair<Float, Float> = 0.8f to 380f
        /** 喜欢心形放大回弹。 */
        val Pop: Pair<Float, Float> = 0.5f to 600f
        /** 透明度、颜色、遮罩。 */
        val Effects: Pair<Float, Float> = 1.0f to 1600f

        /** (dampingRatio, stiffness) → SpringSpec 工厂。 */
        fun spec(pair: Pair<Float, Float>): SpringSpec<Float> =
            spring(dampingRatio = pair.first, stiffness = pair.second)

        val PressSpec: SpringSpec<Float> get() = spec(Press)
        val ReleaseSpec: SpringSpec<Float> get() = spec(Release)
        val FastSpatialSpec: SpringSpec<Float> get() = spec(FastSpatial)
        val DefaultSpatialSpec: SpringSpec<Float> get() = spec(DefaultSpatial)
        val SlowSpatialSpec: SpringSpec<Float> get() = spec(SlowSpatial)
        val ExpressiveSpec: SpringSpec<Float> get() = spec(Expressive)
        val PopSpec: SpringSpec<Float> get() = spec(Pop)
        val EffectsSpec: SpringSpec<Float> get() = spec(Effects)
    }
}
