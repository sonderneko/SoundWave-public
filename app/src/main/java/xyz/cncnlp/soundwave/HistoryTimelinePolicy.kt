package xyz.cncnlp.soundwave

/**
 * r656 item 12：播放历史时间轴几何与左滑删除判定。
 * 时间列在竖线左侧（[TIME_COLUMN_WIDTH_DP]），竖线 + 圆环占 [RAIL_WIDTH_DP]；
 * 歌曲卡独立左滑：拖过卡宽 [DELETE_THRESHOLD] 或快速左甩即删除，否则回弹。右侧不再露出「删除」文字或图标。
 */
internal object HistoryTimelinePolicy {
    const val TIME_COLUMN_WIDTH_DP = 40f
    const val RAIL_WIDTH_DP = 24f
    const val TIME_RAIL_WIDTH_DP = TIME_COLUMN_WIDTH_DP + RAIL_WIDTH_DP
    const val DELETE_THRESHOLD = 0.35f
    const val DELETE_FLING_VELOCITY = 1800f
    private const val FLING_MIN_TRAVEL = 0.08f

    /** 只允许向左（负方向），最多拖出整张卡宽。 */
    fun clampSwipeOffset(startPx: Float, deltaPx: Float, widthPx: Float): Float =
        (startPx + deltaPx).coerceIn(-widthPx.coerceAtLeast(0f), 0f)

    /** 松手时是否删除：越过阈值，或有一定位移的快速左甩。 */
    fun shouldDelete(offsetPx: Float, widthPx: Float, velocityPx: Float = 0f): Boolean {
        if (widthPx <= 0f) return false
        if (offsetPx <= -widthPx * DELETE_THRESHOLD) return true
        return velocityPx <= -DELETE_FLING_VELOCITY && offsetPx <= -widthPx * FLING_MIN_TRAVEL
    }

    /** 背景危险色的显隐进度（0..1），用于左滑时底色渐显。 */
    fun swipeProgress(offsetPx: Float, widthPx: Float): Float =
        if (widthPx <= 0f) 0f else (-offsetPx / (widthPx * DELETE_THRESHOLD)).coerceIn(0f, 1f)
}
