package xyz.cncnlp.soundwave

/**
 * accept6-backnav（用户 2026-11-04 23:17）：多级导航的共享栈逻辑（纯函数，可单测）。
 *
 * 背景：详情与设置此前都是单槽状态——详情只有一格 detailParent、且仅歌手页专辑横排
 * 一处写入，其余打开路径替换详情时不留父级，返回只能整栈关闭；设置 settingsSub
 * 单槽 + r655 恒 null 的返回映射，使 regroup 后出现的深度-2 子页链（统计→历史、
 * 关于→数据流向、通知→脚本）返回一律跳过中间层落到设置主页。本文件的两个栈把
 * 「父级是谁」改为由进入路径动态记录，AppRoot 的打开/返回统一走这里，不再逐页打补丁。
 */

/** 详情帧快照：压栈时冻结的父级详情，返回时逐项还原（含页头标题覆盖与加载失败标记）。 */
data class DetailFrame(
    val detail: Playlist,
    val songs: List<Song>,
    val headerOverride: Pair<String, String>,
    val loadFailedId: String?
)

/** 详情导航栈：打开新详情压栈、返回弹栈。同 id 刷新（加载回写/收藏切换）永不压栈。 */
object DetailNavStack {

    /**
     * 计算打开 [newDetailId] 后的栈：当前有详情且 id 不同 → 当前帧压栈；
     * 无当前详情、或打开的就是当前详情本身 → 栈不变。
     */
    fun pushed(stack: List<DetailFrame>, current: DetailFrame?, newDetailId: String): List<DetailFrame> =
        if (current == null || current.detail.id == newDetailId) stack else stack + current

    /** 弹栈后的栈（空栈弹栈仍为空）。 */
    fun popped(stack: List<DetailFrame>): List<DetailFrame> = stack.dropLast(1)

    /** 返回应恢复的帧（栈顶），null 表示栈空——返回落点由调用方按压播放器/来源 tab 决定。 */
    fun backFrame(stack: List<DetailFrame>): DetailFrame? = stack.lastOrNull()
}

/** 设置导航栈：根页 = 空栈，当前页 = 栈顶。深度-1 页返回根、深度-2 起返回直接父页。 */
object SettingsNavStack {

    /** 当前页（栈顶），null 为设置根。 */
    fun current(stack: List<String>): String? = stack.lastOrNull()

    /**
     * 进入 [sub] 后的栈：已是当前页 → 不变；已在栈中（回跳到祖先页）→ 截断到该页；
     * 否则压栈。多父级页（history/scripts/data_flow）的父级由此按实际进入路径决定。
     */
    fun pushed(stack: List<String>, sub: String): List<String> = when {
        stack.lastOrNull() == sub -> stack
        sub in stack -> stack.take(stack.indexOf(sub) + 1)
        else -> stack + sub
    }

    /** 返回一层后的栈。 */
    fun popped(stack: List<String>): List<String> = stack.dropLast(1)

    /** 返回目标（弹栈后的栈顶），null 为设置根。 */
    fun backTarget(stack: List<String>): String? = stack.dropLast(1).lastOrNull()

    /** 深度：根 0；栈内页 = 下标+1；正在离场的已弹栈页 = 当前栈深+1。 */
    fun depth(stack: List<String>, sub: String?): Int = when {
        sub == null -> 0
        sub in stack -> stack.indexOf(sub) + 1
        else -> stack.size + 1
    }

    /**
     * 纯函数：状态从 [from] 变为 [to]（栈已是变更后状态）是否为前进（推入）。
     * 推入的 [to] 必为栈顶且 [from] 是根或栈内页；弹栈的 [from] 已不在栈中 → 返回。
     */
    fun isForward(stack: List<String>, from: String?, to: String?): Boolean =
        to != null && to != from && (from == null || from in stack)
}
