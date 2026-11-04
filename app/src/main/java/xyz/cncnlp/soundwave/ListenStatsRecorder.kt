package xyz.cncnlp.soundwave

/**
 * r658c D#2：听歌统计改由播放服务计时。
 *
 * 根因：旧实现把统计计时放在 AppRoot 的 Compose LaunchedEffect 里（每 350ms 一次、每秒一次整份 365 天 JSON
 * 同步 commit）。界面被回收 / 划掉任务 / 熄屏后由 Service 自然续播时，这段循环根本不存在，而播放历史由
 * 另一条路径写入——于是「历史有记录、统计不计时、本周时长为 0」。另外写入失败时 statsWallMs 不前移、
 * 增量又被夹到 3 秒，失败期间的时长直接丢失；markPlay 失败会让同一首每 350ms 重试且期间不计时长。
 *
 * 新实现：PlayerService 的进度 tick（与 ExoPlayer 真实 isPlaying 同步，界面在不在都运行）驱动这个纯状态机，
 * 按真实墙钟累计，攒满 [flushEveryMs] 或暂停/切歌/服务销毁时一次性落盘；单次 tick 间隔超过
 * [maxTickGapMs] 视为进程被挂起，只计上限，避免熄屏休眠后补算出离谱时长。
 */
internal class ListenStatsAccumulator(
    private val flushEveryMs: Long = 10_000L,
    private val maxTickGapMs: Long = 3_000L
) {
    data class Flush(val song: Song, val seconds: Long)
    data class Step(val markPlay: Song? = null, val flushes: List<Flush> = emptyList())

    private var song: Song? = null
    private var songKey: String = ""
    private var lastTickMs: Long = -1L
    private var pendingMs: Long = 0L
    private var markedKey: String = ""

    @Synchronized
    fun onTick(nowMs: Long, current: Song?, playing: Boolean): Step {
        val flushes = ArrayList<Flush>(1)
        val key = current?.key().orEmpty()
        if (key != songKey) {
            drain(flushes, force = true)
            song = current
            songKey = key
            lastTickMs = -1L
            pendingMs = 0L
        }
        var markPlay: Song? = null
        if (current != null && key.isNotEmpty() && playing) {
            if (markedKey != key) {
                markedKey = key
                markPlay = current
            }
            if (lastTickMs >= 0L) {
                pendingMs += (nowMs - lastTickMs).coerceIn(0L, maxTickGapMs)
            }
            lastTickMs = nowMs
            if (pendingMs >= flushEveryMs) drain(flushes, force = false)
        } else {
            lastTickMs = -1L
            drain(flushes, force = true)
        }
        return Step(markPlay, flushes)
    }

    /** 服务销毁 / 停止时把未满一个批次的时长也落盘。 */
    @Synchronized
    fun flushAll(): List<Flush> = ArrayList<Flush>(1).also { drain(it, force = true) }

    /** A sync boundary is not a stop: export whole seconds without discarding the remainder. */
    @Synchronized
    fun flushForSnapshot(): List<Flush> {
        val current = song ?: return emptyList()
        val whole = pendingMs / 1000L
        if (whole <= 0L) return emptyList()
        pendingMs -= whole * 1000L
        return listOf(Flush(current, whole))
    }

    /** 让同一首歌在用户重新点播（新的播放意图）后再次计 1 次播放。 */
    @Synchronized
    fun resetPlayMark() { markedKey = "" }

    private fun drain(out: MutableList<Flush>, force: Boolean) {
        val s = song ?: run { pendingMs = 0L; return }
        val whole = pendingMs / 1000L
        if (whole <= 0L) { if (force) pendingMs = 0L; return }
        if (!force && pendingMs < flushEveryMs) return
        out.add(Flush(s, whole))
        pendingMs -= whole * 1000L
        if (force) pendingMs = 0L
    }
}
