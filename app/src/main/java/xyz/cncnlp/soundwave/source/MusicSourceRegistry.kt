package xyz.cncnlp.soundwave.source

import android.content.Context
import xyz.cncnlp.soundwave.ScriptRuntimeHolder
import xyz.cncnlp.soundwave.ScriptStorage
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.source.direct.TxTypedSearchResult
import xyz.cncnlp.soundwave.source.direct.fetchDiscoverDirect
import xyz.cncnlp.soundwave.source.direct.fetchHotDirect
import xyz.cncnlp.soundwave.source.direct.fetchHotWordsDirect
import xyz.cncnlp.soundwave.source.direct.searchKg
import xyz.cncnlp.soundwave.source.direct.searchKw
import xyz.cncnlp.soundwave.source.direct.searchMg
import xyz.cncnlp.soundwave.source.direct.searchPlaylistDirect
import xyz.cncnlp.soundwave.source.direct.searchPlaylistDirectOutcome
import xyz.cncnlp.soundwave.source.direct.searchTx
import xyz.cncnlp.soundwave.source.direct.searchTxTypedResult
import xyz.cncnlp.soundwave.source.direct.searchWy

internal enum class SourceKind { PLATFORM, SCRIPT }

internal object MusicSourceRegistry {
    const val SCRIPT_PREFIX = "script:"

    val platformIds: List<String> = listOf("wy", "kg", "kw", "tx", "mg")

    /** 仅用于选择匿名平台歌词 adapter；不得改写 Song.source 或其缓存身份。 */
    fun lyricPlatformId(source: String): String? = when (source.lowercase()) {
        "wy", "netease" -> "wy"
        "kg", "kugou" -> "kg"
        "kw", "kuwo" -> "kw"
        "tx", "qq", "tencent" -> "tx"
        "mg", "migu" -> "mg"
        else -> null
    }

    /**
     * id 约定：平台 id 命中 [platformIds]；脚本源 id 为 `script:<Script.id>`（脚本动态，见 `Script.id`）。
     * 其余 id 一律视为未知来源，返回 null。
     */
    fun kindOf(id: String): SourceKind? = when {
        id in platformIds -> SourceKind.PLATFORM
        id.startsWith(SCRIPT_PREFIX) -> SourceKind.SCRIPT
        else -> null
    }

    fun availableIds(): List<String> = platformIds + SCRIPT_PREFIX

    // 批21（GPT v4 P1）：五平台静态能力矩阵升级三态（常量数据，不触发网络）。
    // 能力语义 = 该平台该能力是否有客户端直连实现（匿名可用）：
    // DIRECT / 无直连但服务端可用 SERVER_FALLBACK / 两者皆无 UNSUPPORTED。
    // 逐格证据（direct* 直连实现，客户端可验证）：
    // - search：directSearchByPlatform 五平台直连（批1-5）→ DIRECT
    // - playlist：searchPlaylistDirect / PlaylistDetailDirect.fetchPlaylistDetailDirect
    //   五平台直连（批13/17，SearchPlayList Direct 已接）→ DIRECT
    // - rank：RankDirectFetch.fetchRankBoardsDirect 五平台（含 mg，批15）→ DIRECT
    // - lyric：LyricDirectFetch.fetchLyricDirect 五平台（批14，匿名直连）→ DIRECT
    // - album/artist：step8 adds anonymous album routes kg/kw/mg and artist detail
    //   wy/tx/kg plus typed entity search. Existing conservative matrix is retained
    //   until authorized runtime acceptance; source implementation is not live availability.
    //   kw/mg artist detail stays legacy fallback; known-empty mg album stays unavailable.
    // - resolve：UrlResolver 客户端直连取链 wy/kg/kw/mg（官方取链直连）→ DIRECT；
    //   tx 未实现匿名直连取链，直接走服务端回退 → SERVER_FALLBACK
    // 五平台账号登录/登录态能力已从客户端移除，不再作为独立功能声明。
    private val platformCapabilities: Map<String, SourceCapabilities> = mapOf(
        "wy" to SourceCapabilities(
            platform = "wy", supportsSearch = CapabilityLevel.DIRECT, supportsPlaylist = CapabilityLevel.DIRECT,
            supportsLyrics = CapabilityLevel.DIRECT, supportsRank = CapabilityLevel.DIRECT,
            supportsAlbum = CapabilityLevel.DIRECT, supportsArtist = CapabilityLevel.SERVER_FALLBACK,
            supportsResolve = CapabilityLevel.DIRECT, legacySinglePlatformFeed = true, previewClipRisk = true,
        ),
        "kg" to SourceCapabilities(
            platform = "kg", supportsSearch = CapabilityLevel.DIRECT, supportsPlaylist = CapabilityLevel.DIRECT,
            supportsLyrics = CapabilityLevel.DIRECT, supportsRank = CapabilityLevel.DIRECT,
            supportsAlbum = CapabilityLevel.SERVER_FALLBACK, supportsArtist = CapabilityLevel.SERVER_FALLBACK,
            supportsResolve = CapabilityLevel.DIRECT,
        ),
        "kw" to SourceCapabilities(
            platform = "kw", supportsSearch = CapabilityLevel.DIRECT, supportsPlaylist = CapabilityLevel.DIRECT,
            supportsLyrics = CapabilityLevel.DIRECT, supportsRank = CapabilityLevel.DIRECT,
            supportsAlbum = CapabilityLevel.SERVER_FALLBACK, supportsArtist = CapabilityLevel.SERVER_FALLBACK,
            supportsResolve = CapabilityLevel.DIRECT,
        ),
        "tx" to SourceCapabilities(
            platform = "tx", supportsSearch = CapabilityLevel.DIRECT, supportsPlaylist = CapabilityLevel.DIRECT,
            supportsLyrics = CapabilityLevel.DIRECT, supportsRank = CapabilityLevel.DIRECT,
            supportsAlbum = CapabilityLevel.DIRECT, supportsArtist = CapabilityLevel.SERVER_FALLBACK,
            supportsResolve = CapabilityLevel.SERVER_FALLBACK,
        ),
        "mg" to SourceCapabilities(
            platform = "mg", supportsSearch = CapabilityLevel.DIRECT, supportsPlaylist = CapabilityLevel.DIRECT,
            supportsLyrics = CapabilityLevel.DIRECT, supportsRank = CapabilityLevel.DIRECT,
            supportsAlbum = CapabilityLevel.SERVER_FALLBACK, supportsArtist = CapabilityLevel.SERVER_FALLBACK,
            supportsResolve = CapabilityLevel.DIRECT,
        ),
    )

    /**
     * 平台能力只读查询入口（批19 #5，批21 三态升级，additive）：返回 [id] 的常量能力矩阵，
     * 不构造 Adapter、不触发网络。非固定平台 id（script:/local/未知）能力位全
     * [CapabilityLevel.UNSUPPORTED]：脚本源不在静态矩阵（能力见 ScriptSourceAdapter：
     * 仅 resolve=DIRECT）。
     */
    internal fun capabilitiesOf(id: String): SourceCapabilities =
        platformCapabilities[id] ?: SourceCapabilities(platform = id)

    // Local-first 批1-批5 全平台直连：mg、wy、kw、kg、tx 走客户端直连搜索（直连失败自行返回空列表）。
    private val directSearchByPlatform: Map<String, suspend (String, Int, Int) -> List<Song>> = mapOf(
        "mg" to ::searchMg,
        "wy" to ::searchWy,
        "kw" to ::searchKw,
        "kg" to ::searchKg,
        "tx" to ::searchTx,
    )

    private val directTypedSearchResultByPlatform: Map<String, suspend (String, String) -> TxTypedSearchResult> = mapOf(
        "tx" to { keyword, type -> searchTxTypedResult(keyword, type) },
    )

    /**
     * I 波阶段 1（2d）：注册表平台的直连歌曲搜索（第一页、无服务端回落），供
     * UrlResolver 的跨源候选腿与脚本探测样本腿替代原 `searchServer` 专用腿。
     * ACCEPT6 跨源腿退役棒：这两处曾保留的 /music/search 服务端回落腿已整条
     * 删除（覆盖验证见 WAVE-ACCEPT6-CROSSLEGS §1），本函数即唯一搜索来源。
     * 未知平台返回空列表。
     */
    internal suspend fun directSearchSongs(platform: String, keyword: String, limit: Int = 30): List<Song> =
        directSearchByPlatform[platform]?.invoke(keyword, 1, limit) ?: emptyList()

    /**
     * 构造固定平台源适配器。id 必须是 [platformIds] 中的合法平台 id。
     */
    fun platformSource(id: String): MusicSource {
        require(id in platformIds) { "unknown platform source id: $id" }
        return PlatformSourceAdapter(
            api = ApiClientCatalogGateway,
            platform = id,
            directSearch = directSearchByPlatform[id],
            directTypedSearchResult = directTypedSearchResultByPlatform[id],
            // 批13：歌单搜索直连统一入口（searchPlaylistDirect 按 id 自派发 wy/kg/kw/tx/mg，
            // 直连失败/空结果由 adapter 回落服务端；分页只走匿名原生端点。
            directPlaylistSearch = { keyword, page, lim -> searchPlaylistDirect(id, keyword, lim, page) },
            directPlaylistSearchOutcome = { keyword, page, lim -> searchPlaylistDirectOutcome(id, keyword, lim, page) },
            directHotSongs = { fetchHotDirect(id) },
            directHotKeywords = { limit -> fetchHotWordsDirect(id, limit) },
            directDiscoverCards = { limit -> fetchDiscoverDirect(id, limit) },
        )
    }

    /**
     * 构造脚本源适配器（批20 ②）：`script:<Script.id>` → 从脚本仓库按 id 取
     * [Script]，挂接进程级共享 [ScriptRuntimeHolder]；脚本不存在 / id 非法返回 null。
     * 接入点就绪：现有取链消费方（UrlResolver / PlaybackController）仍直调
     * ScriptRuntime，不经本适配器，避免行为回归。
     */
    fun scriptSource(id: String, context: Context, owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()): MusicSource? {
        if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(owner)) return null
        val scriptId = id.removePrefix(SCRIPT_PREFIX)
        if (scriptId.isBlank() || scriptId == id) return null
        val prefs = xyz.cncnlp.soundwave.ContextPreferences.open(context, "soundwave_scripts", Context.MODE_PRIVATE, owner)
        val script = ScriptStorage.loadScripts(prefs).firstOrNull { it.id == scriptId } ?: return null
        return ScriptSourceAdapter(ScriptRuntimeHolder.get(context.applicationContext, owner), script)
    }

    fun resolveSource(id: String, context: Context): MusicSource? = when (kindOf(id)) {
        SourceKind.PLATFORM -> platformSource(id)
        // 批20 ②：脚本仅 resolve 角色（LX 协议无 search）；消费方仍走 ScriptRuntime 直调。
        SourceKind.SCRIPT -> scriptSource(id, context)
        null -> null
    }
}
