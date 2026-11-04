package xyz.cncnlp.soundwave.perf

/**
 * r658c F：媒体会话 Metadata / 前台通知的去重规则（纯函数，可单测）。
 *
 * PlayerService 的进度 tick 每秒调用一次 updateSessionAndNotify。旧实现每次都：
 * - 重设整份 Metadata（含 ≤512px 封面，ALBUM_ART + ART 两份位图经 Binder 传给 system_server）；
 * - 重建并 notify 通知（PackageManager 取桌面图标、复制封面画角标、重绘「词」图标，再传一份位图）。
 * 播放期间主线程每秒被占用数十毫秒并产生 MB 级垃圾，点播放/暂停、滑动列表都要排在它后面。
 *
 * 现在：进度只由 PlaybackState 携带（系统媒体控件据此自走进度条）；Metadata 仅在内容键变化时重设；
 * 通知在内容键变化时立即重发，否则按 [HEARTBEAT_MS] 心跳重发（保持“暂停时被划掉的控制通知会回来”的旧行为）。
 */
internal object NotificationRefreshPolicy {
    const val HEARTBEAT_MS = 10_000L

    /** 封面以实例身份区分：同一首歌的封面 Bitmap 加载后不会再被替换为等价新实例。 */
    fun metadataKey(title: String, artist: String, durationMs: Long, cover: Any?): String =
        "$title\u0001$artist\u0001$durationMs\u0001${cover?.let { System.identityHashCode(it) } ?: 0}"

    /**
     * @param progressSecond API 29 以下 MediaStyle 不读会话进度，需要把秒级进度纳入键以维持通知进度条；
     *   API 29+ 传 -1。
     */
    fun notificationKey(
        title: String,
        artist: String,
        playing: Boolean,
        lyricEnabled: Boolean,
        lyricLocked: Boolean,
        cover: Any?,
        progressSecond: Long = -1L
    ): String = buildString {
        append(title).append('\u0001').append(artist).append('\u0001')
        append(if (playing) 'P' else 'p').append(if (lyricEnabled) 'L' else 'l').append(if (lyricLocked) 'K' else 'k')
        append('\u0001').append(cover?.let { System.identityHashCode(it) } ?: 0)
        append('\u0001').append(progressSecond)
    }

    /** 结果落到主线程时重验服务、歌曲和代次，拒绝排队期间变旧的封面提交。 */
    fun mayCommitCover(
        serviceIsCurrent: Boolean,
        requestToken: String,
        currentToken: String,
        requestSongKey: String?,
        currentSongKey: String?,
        requestGeneration: Int,
        currentGeneration: Int
    ): Boolean = serviceIsCurrent &&
        requestToken == currentToken &&
        requestSongKey == currentSongKey &&
        requestGeneration == currentGeneration

    fun shouldNotify(key: String, lastKey: String, nowMs: Long, lastAtMs: Long, heartbeatMs: Long = HEARTBEAT_MS): Boolean =
        key != lastKey || lastAtMs <= 0L || nowMs - lastAtMs >= heartbeatMs
}
