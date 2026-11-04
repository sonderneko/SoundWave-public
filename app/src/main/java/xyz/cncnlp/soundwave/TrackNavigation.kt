package xyz.cncnlp.soundwave

import android.content.Context
import android.content.Intent
import xyz.cncnlp.soundwave.ui.notice.AppNotice

/**
 * r657 第二轮：歌曲操作抽屉（原型 sheet-actions）里的「查看歌手 / 查看专辑 / 分享」。
 * 导航回调由 AppRoot 每次组合时绑定（复用首页歌手入口与专辑卡入口，不另起一套详情加载）；
 * 抽屉只调用这里，避免把两个回调穿过详情 / 搜索 / 今日推荐三处调用链。
 */
object TrackNavigation {
    @Volatile private var artistNav: ((Song, Tab) -> Unit)? = null
    @Volatile private var albumNav: ((DiscoverCard, Tab) -> Unit)? = null
    @Volatile private var playlistNav: ((Playlist, Tab) -> Unit)? = null
    @Volatile private var tabNow: (() -> Tab)? = null

    fun bind(
        artist: (Song, Tab) -> Unit,
        album: (DiscoverCard, Tab) -> Unit,
        currentTab: () -> Tab,
        /** M 波「出处」歌单行：与 artistNav/albumNav 同模式，绑定到既有歌单详情打开路径（plDetail 设置链）。 */
        playlist: ((Playlist, Tab) -> Unit)? = null
    ) {
        artistNav = artist; albumNav = album; tabNow = currentTab; playlistNav = playlist
    }

    /**
     * 多歌手（「A / B」「A、B」「A,B」「A & B」）取第一位；feat./ft. 合作者不算主歌手，
     * 尾部括号别名（「周杰伦 (Jay Chou)」）剥离——切分与归一化复用 SongMatcher 既有口径，
     * 保证拿去平台歌手索引检索的名字与跨源匹配所认的名字一致。
     */
    fun primaryArtist(singer: String): String = SongMatcher.primarySingerName(singer)

    /**
     * 按名兜底的命中挑选（纯函数）：只接受「带歌手 ID 且名字可信」的行。
     * 先原始名忽略大小写全等，再 SongMatcher.normalize 归一化等值（吸收括号/空白/大小写差异）；
     * 两级都不命中返回 null——宁可诚实报未找到，也不再盲抓第一条（旧实现会把歌曲行
     * 的 id 当歌手 id 误开详情）。rows 应为平台歌手实体搜索（searchTyped "artist"）的结果。
     */
    fun pickArtistHit(rows: List<Song>, name: String): Song? {
        val candidates = rows.filter { it.id.isNotBlank() }
        candidates.firstOrNull { it.name.equals(name, true) || it.singer.equals(name, true) }?.let { return it }
        val wanted = SongMatcher.normalize(name)
        if (wanted.isEmpty()) return null
        return candidates.firstOrNull {
            SongMatcher.normalize(it.name) == wanted || SongMatcher.normalize(it.singer) == wanted
        }
    }

    /** 抽屉右侧说明过长时截断（说明不参与权重，太长会挤掉标题）。 */
    fun shortHint(text: String, max: Int = 10): String = if (text.length > max) text.take(max - 1) + "…" else text

    fun canOpenArtist(song: Song): Boolean = primaryArtist(song.singer).isNotEmpty() && song.singer != "未知歌手"

    /** 只有带真实专辑 id 的歌曲才能打开专辑（不按专辑名伪造专辑）。 */
    fun canOpenAlbum(song: Song): Boolean = song.albumId.isNotBlank() && song.album.isNotBlank() && song.source != "local"

    fun albumCard(song: Song): DiscoverCard =
        DiscoverCard(song.albumId, song.album, song.cover, song.singer, "album", "专辑", source = song.source)

    fun openArtist(context: Context, song: Song) {
        val nav = artistNav
        val name = primaryArtist(song.singer)
        if (nav == null || name.isEmpty()) { AppNotice.attach(context.applicationContext); AppNotice.toast("暂无歌手信息"); return }
        nav(song.copy(singer = name), tabNow?.invoke() ?: Tab.HOME)
    }

    fun openAlbum(context: Context, song: Song) {
        val nav = albumNav
        if (nav == null || !canOpenAlbum(song)) { AppNotice.toast("暂无专辑信息"); return }
        nav(albumCard(song), tabNow?.invoke() ?: Tab.HOME)
    }

    /** M 波「出处」歌单行跳转：歌单身份由宿主（详情页容器 / 播放来源追踪）以完整 Playlist 传入，id 即详情键。 */
    fun openPlaylist(playlist: Playlist) {
        val nav = playlistNav
        if (nav == null || playlist.id.isBlank()) { AppNotice.toast("暂无歌单信息"); return }
        nav(playlist, tabNow?.invoke() ?: Tab.HOME)
    }

    fun shareText(song: Song): String {
        val who = song.singer.ifBlank { "未知歌手" }
        val album = song.album.takeIf { it.isNotBlank() }?.let { " · 《$it》" }.orEmpty()
        val source = canonicalSourceId(song.source)
        val platform = when (source) {
            "wy" -> "网易云音乐"
            "tx" -> "QQ 音乐"
            "kg" -> "酷狗音乐"
            "kw" -> "酷我音乐"
            "mg" -> "咪咕音乐"
            "local" -> "本地音乐"
            else -> "来源未确认"
        }
        // Never use key() (which defaults to wy), resolvedUrl, mediaMid, or device URIs.
        // These are public catalog identities, not playback credentials or a title-search guess.
        fun meaningful(value: String): Boolean = value.isNotBlank() && value.any { it != '0' } &&
            value.lowercase(java.util.Locale.ROOT) !in setOf("null", "undefined", "none", "unknown")
        fun numeric(value: String): Boolean = meaningful(value) && Regex("[0-9]{1,20}").matches(value)
        fun catalog(value: String): Boolean = meaningful(value) && Regex("[A-Za-z0-9]{1,64}").matches(value)
        val id = song.id.trim()
        val identities = mutableListOf<String>()
        if (source in setOf("wy", "tx", "kg", "kw", "mg") && catalog(id) &&
            !(source == "tx" && id == song.mediaMid.trim())) {
            identities += "歌曲 ID：$id"
        }
        val link = when (source) {
            "wy" -> id.takeIf(::numeric)?.let { "https://music.163.com/song?id=$it" }
            "kw" -> id.takeIf(::numeric)?.let { "https://www.kuwo.cn/play_detail/$it" }
            "tx" -> when {
                // Legacy responses may have put media_mid in id; do not share file identity as songmid.
                id == song.mediaMid.trim() -> null
                numeric(id) -> "https://i.y.qq.com/v8/playsong.html?songid=$id"
                meaningful(id) && Regex("[A-Za-z0-9]{6,32}").matches(id) && id.any { it.isLetter() } ->
                    "https://y.qq.com/n/ryqq/songDetail/$id"
                else -> null
            }
            "kg" -> {
                val hash = song.hash.trim().takeIf { meaningful(it) && Regex("[A-Fa-f0-9]{32}").matches(it) }
                    ?: id.takeIf { meaningful(it) && Regex("[A-Fa-f0-9]{32}").matches(it) }
                hash?.let {
                    identities += "歌曲 hash：$it"
                    "https://www.kugou.com/song/#hash=$it"
                }
            }
            "mg" -> song.copyrightId.trim().takeIf { meaningful(it) && Regex("[A-Za-z0-9]{6,32}").matches(it) }?.let {
                // Migu web pages use copyrightId, not contentId/id or miguSongId.
                identities += "版权 ID：$it"
                "https://music.migu.cn/v3/music/song/$it"
            }
            else -> null
        }
        return buildString {
            append("${song.name} - $who$album（来自 SoundWave）")
            append("\n平台：$platform")
            identities.forEach { append("\n$it") }
            append("\n${link ?: "暂无可用的原平台歌曲链接"}")
        }
    }

    fun share(context: Context, song: Song) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, song.name)
            putExtra(Intent.EXTRA_TEXT, shareText(song))
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, "分享歌曲").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { AppNotice.toast("没有可用的分享应用", AppNotice.Kind.FAILURE) }
    }
}
