package xyz.cncnlp.soundwave.ui.home

import java.util.concurrent.ConcurrentHashMap
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.source.direct.HotArtist

/**
 * 1.0.655：首页「新歌速递」「热门歌手」等异步区块的进程内缓存。
 * 根因：这些数据此前保存在 HomePage 精选页内的 remember(platform, ranks) 中——切换底栏 / 首页分区（页面离开组合）
 * 或 ranks 列表实例变化时状态被重置为空列表，区块先消失、重新加载完再弹出，导致页面跳动。
 * 现在初值直接取缓存（同步、首帧即有数据），后台刷新只在拿到非空结果时替换，不再出现「空 → 有」的闪烁。
 */
internal object HomeSectionMemory {
    private val newSongs = ConcurrentHashMap<String, List<Song>>()
    private val hotArtists = ConcurrentHashMap<String, List<HotArtist>>()

    fun newSongs(platform: String): List<Song> = newSongs[platform].orEmpty()
    fun putNewSongs(platform: String, songs: List<Song>) { if (songs.isNotEmpty()) newSongs[platform] = songs }

    fun hotArtists(platform: String): List<HotArtist> = hotArtists[platform].orEmpty()
    fun putHotArtists(platform: String, artists: List<HotArtist>) { if (artists.isNotEmpty()) hotArtists[platform] = artists }
}
