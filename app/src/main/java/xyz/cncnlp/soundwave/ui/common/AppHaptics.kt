package xyz.cncnlp.soundwave.ui.common

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * P1 / SW-12-02：全仓触感唯一出口（规范 B.3）。minSdk 26。
 *
 * 只用 [View.performHapticFeedback]（自动跟随系统触觉开关，系统关闭时不震）。
 * 分级原则：状态变了才震、按下本身不震；滚动中不震；一个手势最多 1 次阈值震动（由调用方保证）。
 *
 * 高 API 常量（CONFIRM/REJECT 为 API 30+；TOGGLE_ON/OFF、SEGMENT_TICK、SEGMENT_FREQUENT_TICK、
 * DRAG_START、GESTURE_END、GESTURE_THRESHOLD_ACTIVATE/DEACTIVATE 为 API 34+）只在
 * `SDK_INT` 判断分支内引用——这些常量是 int 内联值，分支隔离在旧系统上安全。
 */
object AppHaptics {

    /** [segmentTick] 节流下限（B.3：≥40ms）。 */
    private const val SEGMENT_THROTTLE_MS = 40L

    @Volatile
    private var lastSegmentTickAt = 0L

    /** 播放/暂停、上一首/下一首等已确认点击（现状常量，原样保留）。 */
    fun tap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** 肯定确认：喜欢/收藏由未选变已选、操作成功。API 30+ CONFIRM，降级 CONTEXT_CLICK。 */
    fun confirm(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    /** 操作成功（导入、删除完成等），与 [confirm] 同级。 */
    fun success(view: View) = confirm(view)

    /** 否定/拒绝反馈。API 30+ REJECT，降级 LONG_PRESS。 */
    fun reject(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** 操作失败，与 [reject] 同级。 */
    fun failure(view: View) = reject(view)

    /** 开关切换到 [on] 后的状态。API 34+ TOGGLE_ON/TOGGLE_OFF，降级 CLOCK_TICK。 */
    fun toggle(view: View, on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(
                if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            )
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** 长按识别成功（出菜单 / 进入多选）。 */
    fun longPress(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** 拖动排序：拿起一项。API 34+ DRAG_START，降级 LONG_PRESS。 */
    fun dragStart(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.DRAG_START)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** 拖动排序：跨过一格（每次交换位置调用一次）。API 34+ SEGMENT_TICK，降级 CLOCK_TICK。 */
    fun dragTick(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** 拖动排序：放下。API 34+ GESTURE_END；低版本按 B.3 降级列为「无」，不发反馈。 */
    fun dragEnd(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        }
    }

    /**
     * 滑杆 / 步进吸附：每跨一个刻度调用，内置 ≥40ms 节流，节流窗口内的调用直接丢弃。
     * API 34+ SEGMENT_FREQUENT_TICK，降级 CLOCK_TICK。
     */
    fun segmentTick(view: View) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSegmentTickAt < SEGMENT_THROTTLE_MS) return
        lastSegmentTickAt = now
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * 越过 / 退回阈值（播放器下拉、抽屉下拉、下拉刷新）：[activated] = true 为越过阈值，
     * false 为退回阈值以内。API 34+ GESTURE_THRESHOLD_ACTIVATE/DEACTIVATE，降级 CONTEXT_CLICK。
     */
    fun thresholdCrossed(view: View, activated: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(
                if (activated) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
            )
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }
}
