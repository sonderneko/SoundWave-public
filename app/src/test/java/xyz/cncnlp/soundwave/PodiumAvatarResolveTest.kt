package xyz.cncnlp.soundwave

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** ACCEPT6-PODIUMAVATAR：领奖台按需解析口径（matchResolved / evidenceIdsBySource / loadOnDemand）。
 * Round3 的 match()/load() 契约由 Round3RecommendationAndAvatarTest 继续钉住，本文件只测新增层。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PodiumAvatarResolveTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    @Before fun clearCache() { ctx.getSharedPreferences("artist-avatar-cache-v1", 0).edit().clear().commit() }

    private fun artist(id: String, name: String = "昼夜", source: String = "wy", cover: String = "https://example.com/$id.jpg") =
        Song(singer = "", id = id, name = name, source = source, cover = cover)

    @Test fun resolvedSingleExactMatch() {
        assertEquals("12052034", ArtistAvatarRepository.matchResolved("wy", "昼夜", listOf(artist("12052034")))?.artistId)
    }

    @Test fun resolvedDuplicateNamesakeFallsToPlatformOrderFirst() {
        // wy 实测：「昼夜」真身（先行）与同名小号两条精确同名——match 恰一门判死，领奖台口径取平台序第一条。
        val rows = listOf(artist("12052034"), artist("37586278"))
        assertNull(ArtistAvatarRepository.match("wy", "昼夜", rows))
        assertEquals("12052034", ArtistAvatarRepository.matchResolved("wy", "昼夜", rows)?.artistId)
    }

    @Test fun resolvedEvidenceBeatsPlatformOrder() {
        val rows = listOf(artist("37586278"), artist("12052034"))
        assertEquals("12052034", ArtistAvatarRepository.matchResolved("wy", "昼夜", rows, evidenceIds = setOf("12052034"))?.artistId)
    }

    @Test fun resolvedBrokenCoverRowDroppedBeforeCounting() {
        val rows = listOf(artist("37586278", cover = "file:///tmp/a.jpg"), artist("12052034"))
        assertEquals("12052034", ArtistAvatarRepository.matchResolved("wy", "昼夜", rows)?.artistId)
    }

    @Test fun resolvedRejectsNonExactMultiArtistAndForeignRows() {
        assertNull(ArtistAvatarRepository.matchResolved("wy", "昼夜", listOf(artist("1", name = "昼夜乐队"))))
        assertNull(ArtistAvatarRepository.matchResolved("wy", "昼夜/薛之谦", listOf(artist("1", name = "昼夜/薛之谦"))))
        assertNull(ArtistAvatarRepository.matchResolved("wy", "昼夜", listOf(artist("1", source = "kg"))))
        assertNull(ArtistAvatarRepository.matchResolved("wy", "昼夜", listOf(artist("1", cover = ""))))
        assertNull(ArtistAvatarRepository.matchResolved("local", "昼夜", listOf(artist("1", source = "local"))))
    }

    @Test fun evidenceGroupsSingerIdsByPlatform() {
        val songs = listOf(
            Song(id = "s1", name = "a", singer = "昼夜", singerId = "12052034", source = "wy"),
            Song(id = "s2", name = "b", singer = "昼夜", singerId = "12052034", source = "wy"),
            Song(id = "s3", name = "c", singer = "昼夜", singerId = "999", source = "kg"),
            Song(id = "s4", name = "d", singer = "昼夜", singerId = "", source = "tx"),
            Song(id = "s5", name = "e", singer = "别人", singerId = "555", source = "wy"),
            Song(id = "s6", name = "f", singer = "昼夜", singerId = "777", source = "local")
        )
        val evidence = ArtistAvatarRepository.evidenceIdsBySource(songs, "昼夜")
        assertEquals(mapOf("wy" to setOf("12052034"), "kg" to setOf("999")), evidence)
    }

    @Test fun onDemandSkipsDominantAndStopsAtFirstHit() = runBlocking {
        val searched = mutableListOf<String>()
        val search: suspend (String, String) -> List<Song> = { src, _ ->
            searched.add(src)
            if (src == "kg") listOf(artist("42", source = "kg")) else emptyList()
        }
        val hit = ArtistAvatarRepository.loadOnDemand(
            ctx, dominantSource = "wy", name = "昼夜",
            platformOrder = listOf("wy", "tx", "kg", "kw"), now = 1_000L, search = search
        )
        assertEquals("kg", hit?.source)
        assertEquals("42", hit?.artistId)
        assertEquals(listOf("tx", "kg"), searched)
        // 命中已回填缓存：同平台 load 直接命中，不再触发搜索。
        var loadCalls = 0
        val viaLoad = ArtistAvatarRepository.load(ctx, "kg", "昼夜", 1_001L) { _, _ -> loadCalls++; emptyList() }
        assertEquals("42", viaLoad?.artistId)
        assertEquals(0, loadCalls)
    }

    @Test fun onDemandUsesEvidenceAcrossPlatforms() = runBlocking {
        val search: suspend (String, String) -> List<Song> = { src, _ ->
            if (src == "wy") listOf(artist("37586278"), artist("12052034")) else emptyList()
        }
        val hit = ArtistAvatarRepository.loadOnDemand(
            ctx, dominantSource = "", name = "昼夜",
            evidence = mapOf("wy" to setOf("12052034")),
            platformOrder = listOf("wy"), now = 2_000L, search = search
        )
        assertEquals("12052034", hit?.artistId)
    }

    @Test fun onDemandAllMissReturnsNullAndNegativeCacheSuppressesRepeat() = runBlocking {
        var calls = 0
        val search: suspend (String, String) -> List<Song> = { _, _ -> calls++; emptyList() }
        assertNull(ArtistAvatarRepository.loadOnDemand(ctx, "wy", "昼夜", platformOrder = listOf("tx", "kg"), now = 3_000L, search = search))
        assertEquals(2, calls)
        assertNull(ArtistAvatarRepository.loadOnDemand(ctx, "wy", "昼夜", platformOrder = listOf("tx", "kg"), now = 3_001L, search = search))
        assertEquals(2, calls)
    }
}
