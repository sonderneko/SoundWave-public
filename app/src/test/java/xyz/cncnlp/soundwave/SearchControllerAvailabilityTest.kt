package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * accept6-txavail：单平台 tx 歌曲搜索的「失败」与「真无结果」呈现分离。
 * 路由层保证 tx 歌曲走带可用性契约的 typed 结果（不再被 List 契约硬编码
 * available=true 吞掉）；呈现层保证失败批次落 Unavailable、真空批次落 Empty。
 * typed 结果本身的 available 语义由 TxDirectSearchTest 钉住。
 */
class SearchControllerAvailabilityTest {
    private val platformIds = listOf("wy", "kg", "kw", "tx", "mg")

    @Test
    fun txSongSearchUsesTypedResultRouteSoFailureStaysVisible() {
        assertEquals(
            TypedSearchRoute.TYPED_RESULT,
            typedSearchRoute("tx", "song", platformIds)
        )
    }

    @Test
    fun txTypedTabsKeepTypedResultRoute() {
        assertEquals(TypedSearchRoute.TYPED_RESULT, typedSearchRoute("tx", "album", platformIds))
        assertEquals(TypedSearchRoute.TYPED_RESULT, typedSearchRoute("tx", "artist", platformIds))
    }

    @Test
    fun otherPlatformsKeepLegacyRoutes() {
        assertEquals(TypedSearchRoute.SONG_LIST, typedSearchRoute("wy", "song", platformIds))
        assertEquals(TypedSearchRoute.TYPED_LIST, typedSearchRoute("wy", "album", platformIds))
        assertEquals(TypedSearchRoute.SONG_LIST, typedSearchRoute("unknown", "song", platformIds))
        assertEquals(TypedSearchRoute.SERVER, typedSearchRoute("unknown", "album", platformIds))
    }

    @Test
    fun failedTxSongBatchPresentsUnavailableNotEmpty() {
        val content = searchBatchContent(
            listOf(SearchBatch(emptyList<String>(), available = false, failed = false)),
            "歌曲搜索暂不可用；可以重试"
        )

        assertTrue(content is LoadableContent.Unavailable)
        assertEquals("歌曲搜索暂不可用；可以重试", (content as LoadableContent.Unavailable).error)
    }

    @Test
    fun genuinelyEmptyTxSongBatchPresentsEmptyNotUnavailable() {
        val content = searchBatchContent(
            listOf(SearchBatch(emptyList<String>(), available = true, failed = false)),
            "歌曲搜索暂不可用；可以重试"
        )

        assertTrue(content is LoadableContent.Empty)
    }

    @Test
    fun crossPlatformTxFailureAloneDoesNotBecomeUnavailable() {
        val content = searchBatchContent(
            listOf(
                SearchBatch(emptyList<String>(), available = false, failed = false),
                SearchBatch(emptyList<String>(), available = true, failed = false)
            ),
            "歌曲搜索暂不可用；可以重试"
        )

        assertTrue(content is LoadableContent.Empty)
    }

    @Test
    fun rowsPresentPresentsAvailable() {
        val content = searchBatchContent(
            listOf(SearchBatch(listOf("song"), available = false, failed = false)),
            "歌曲搜索暂不可用；可以重试"
        )

        assertTrue(content is LoadableContent.Available)
        assertEquals(listOf("song"), (content as LoadableContent.Available).value)
    }
}
