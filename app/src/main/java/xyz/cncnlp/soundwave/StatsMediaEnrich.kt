package xyz.cncnlp.soundwave

import java.text.Normalizer
import java.util.Locale
import xyz.cncnlp.soundwave.source.direct.HotArtist

/**
 * ACCEPT6-STATSMEDIA：听歌统计页的展示层补图（纯逻辑，不写回统计存储）。
 *
 * 背景：统计条目的封面只来自「记录当时 Song 自带的 cover」——某首歌历次播放都没带封面
 * （经历史/本地/跨源重建的 Song、被过滤的设备本地引用等）时条目永久空封面，而同一首歌
 * 在本地库（历史/下载/收藏/歌单）里往往有封面。页面快照组装时（IO）按稳定键回填一次即可，
 * 不改变统计口径、不落盘、不上云。
 */
internal object StatsMediaEnrich {

    /** 与 [Song.key] 的 meta 归一同口径：小写、去空白、分隔符转下划线。 */
    private fun norm(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), "")
        .replace(Regex("[|:#/\\\\]+"), "_")

    private fun metaIndex(source: String, name: String, singer: String): String =
        "${source.lowercase(Locale.ROOT)}|${norm(name)}|${norm(singer)}"

    private fun nameSingerIndex(name: String, singer: String): String =
        "${norm(name)}|${norm(singer)}"

    /**
     * 只给 cover 为空的 [topSongs] 回填封面；已有封面不覆盖，计数/顺序原样。
     * [candidates] 按优先级传入（历史 → 下载 → 收藏 → 歌单歌曲），同键先到先得。
     * 匹配三级：Song.key() 精确键 → 同源 name+singer → 任意源 name+singer（要求歌手非空）。
     */
    fun backfillSongCovers(
        topSongs: List<ListenStats.SongStat>,
        candidates: List<Song>
    ): List<ListenStats.SongStat> {
        if (topSongs.none { it.cover.isBlank() } || candidates.isEmpty()) return topSongs
        val byKey = HashMap<String, String>()
        val byMeta = HashMap<String, String>()
        val byNameSinger = HashMap<String, String>()
        for (song in candidates) {
            val cover = song.cover
            if (cover.isBlank()) continue
            byKey.putIfAbsent(song.key(), cover)
            if (song.name.isNotBlank()) {
                byMeta.putIfAbsent(metaIndex(song.source, song.name, song.singer), cover)
                if (song.singer.isNotBlank()) {
                    byNameSinger.putIfAbsent(nameSingerIndex(song.name, song.singer), cover)
                }
            }
        }
        if (byKey.isEmpty() && byMeta.isEmpty() && byNameSinger.isEmpty()) return topSongs
        return topSongs.map { stat ->
            if (stat.cover.isNotBlank()) return@map stat
            val source = stat.key.substringBefore(':', "").lowercase(Locale.ROOT)
            val found = byKey[stat.key]
                ?: byMeta[metaIndex(source, stat.name, stat.singer)]
                ?: if (stat.singer.isNotBlank()) byNameSinger[nameSingerIndex(stat.name, stat.singer)] else null
            if (found.isNullOrBlank()) stat else stat.copy(cover = found)
        }
    }

    /**
     * 首页「热门歌手」管线（fetchHotArtists + HomeSectionMemory）里的同名歌手头像：
     * 与 [ArtistAvatarRepository] 同口径的 NFKC 归一精确匹配；榜上没有或头像为空返回空串，
     * 调用方继续走仓库搜索，仍无果回退黑胶母题（不借歌曲封面冒充头像）。
     */
    fun chartAvatarFor(hotArtists: List<HotArtist>, name: String): String {
        if (hotArtists.isEmpty() || name.isBlank()) return ""
        val target = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return hotArtists.firstOrNull { artist ->
            artist.avatar.isNotBlank() &&
                Normalizer.normalize(artist.name.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT) == target
        }?.avatar.orEmpty()
    }
}
