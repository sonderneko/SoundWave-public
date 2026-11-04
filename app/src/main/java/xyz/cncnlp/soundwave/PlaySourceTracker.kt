package xyz.cncnlp.soundwave

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * r657 第二轮：播放来源（历史「来自 <来源>」、原型「正在播放 · 来自 今日推荐」）。
 *
 * - 页面用 [PlaySourceScope] 声明自己的来源名（我的喜欢 / 歌单名 / 专辑名 / 今日推荐 / 搜索 …），按进入顺序入栈，
 *   离开时出栈；栈顶 = 用户当前看到的页面（首页常驻层最早入栈，始终在底部）。
 * - 播放时 [noteQueue]：队列换成新的一组歌（前 3 首身份变化）时，把栈顶来源记为这条队列的来源；
 *   同一队列里自动下一首 / 心动续播追加不会改来源。
 * - 写历史时 [label] 给出当前队列来源；没有可判定来源时为空串（历史行不显示「来自」）。
 */
object PlaySourceTracker {
    // M 波：栈条目在来源名之外可携带来源歌单的完整身份（Playlist），供播放器菜单「出处」区的播放来源行 ID 化跳转。
    private val stack = ArrayList<Triple<Any, String, Playlist?>>()
    @Volatile private var queueSignature: List<String> = emptyList()
    @Volatile private var queueLabel: String = ""
    @Volatile private var queuePlaylist: Playlist? = null

    @Synchronized fun push(token: Any, label: String, playlist: Playlist? = null) { stack.removeAll { it.first === token }; stack.add(Triple(token, label, playlist)) }
    @Synchronized fun pop(token: Any) { stack.removeAll { it.first === token } }
    @Synchronized fun screen(): String = stack.lastOrNull()?.second.orEmpty()

    /** 栈顶页面声明的来源歌单身份（未声明身份时为 null）。 */
    @Synchronized fun screenPlaylist(): Playlist? = stack.lastOrNull()?.third

    /** 新队列开始播放时调用；返回该队列的来源。 */
    fun noteQueue(list: List<Song>): String {
        val sig = list.take(3).map { it.key() }
        if (sig != queueSignature) {
            queueSignature = sig
            queueLabel = screen()
            // 身份随 label 同步换绑（同一判定点），避免名与身份来自不同页面。
            queuePlaylist = screenPlaylist()
        }
        return queueLabel
    }

    /** 显式指定来源（例如心动模式）。显式来源只有名字、没有歌单身份。 */
    fun force(label: String, list: List<Song>) { queueSignature = list.take(3).map { it.key() }; queueLabel = label; queuePlaylist = null }

    fun label(): String = queueLabel.take(Song.PLAY_SOURCE_MAX)

    /** 当前队列的来源歌单身份；无身份时为 null（ACCEPT4 案 i：播放器菜单出处行整行不出，不回退候选列表）。 */
    fun originPlaylist(): Playlist? = queuePlaylist

    /** 测试用。 */
    @Synchronized internal fun reset() { stack.clear(); queueSignature = emptyList(); queueLabel = ""; queuePlaylist = null }
}

/** 声明当前页面的播放来源名；空串不入栈。[playlist] 可选携带来源歌单身份（详情页传自身 detail），供播放器出处行按 ID 跳转。 */
@Composable
fun PlaySourceScope(label: String, playlist: Playlist? = null) {
    val latestPlaylist = androidx.compose.runtime.rememberUpdatedState(playlist)
    DisposableEffect(label, playlist?.id) {
        val token = Any()
        if (label.isNotBlank()) PlaySourceTracker.push(token, label, latestPlaylist.value)
        onDispose { PlaySourceTracker.pop(token) }
    }
}

/** 历史行副标题：「歌手 · 来自 X」；无来源时回退「歌手 · 专辑」。 */
fun historySubtitle(song: Song): String =
    if (song.playSource.isNotBlank()) listOf(song.singer.ifBlank { "未知歌手" }, "来自 ${song.playSource}").joinToString(" · ")
    else listOf(song.singer, song.album).filter { it.isNotBlank() }.joinToString(" · ")
