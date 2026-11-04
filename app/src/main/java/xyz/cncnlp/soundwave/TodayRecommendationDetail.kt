package xyz.cncnlp.soundwave

/** The same semantic detail identity and hero policy on every provider, not a WY-specific route. */
internal fun isTodayRecommendationDetail(detail: Playlist): Boolean =
    detail.id == xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher.TODAY_DETAIL_ID ||
        (detail.local && detail.remoteId == xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher.TODAY_DETAIL_ID)

/** A provider may omit the first track's image while later recommended tracks have real artwork. */
internal fun todayRecommendationCover(songs: List<Song>): String = songs.asSequence()
    .map { normalizeCoverUrl(it.cover) }.firstOrNull { it.isNotBlank() }.orEmpty()

internal fun todayRecommendationDetail(platform: String, songs: List<Song>): Playlist = Playlist(
    id = xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher.TODAY_DETAIL_ID,
    name = "今日推荐", cover = todayRecommendationCover(songs), songs = songs.toMutableList(),
    local = true, source = platform, count = songs.size
)
