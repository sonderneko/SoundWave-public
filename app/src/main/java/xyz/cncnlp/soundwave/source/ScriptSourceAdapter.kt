package xyz.cncnlp.soundwave.source

import android.util.Log
import xyz.cncnlp.soundwave.BuildConfig
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.ResolveFailure
import xyz.cncnlp.soundwave.ResolveResult
import xyz.cncnlp.soundwave.ResolveTicket
import xyz.cncnlp.soundwave.Script
import xyz.cncnlp.soundwave.ScriptRuntime
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.scriptMetaJson
import kotlinx.coroutines.CancellationException

/**
 * 脚本音源统一 [MusicSource] 适配（批20 ②）。
 *
 * LX 脚本协议只有取链（getMusicUrl / probe），没有 search——脚本在 Source 体系
 * 是 resolve 角色：能力仅 supportsResolve=DIRECT；search/playlist/rank/lyric 显式
 * 空实现（脚本无搜索协议入口，不做搜索兜底）。
 *
 * resolve 单脚本直调 [ScriptRuntime.getMusicUrl]（suspend；WebView 启动与代理基址
 * 由既有 ScriptRuntime 使用方负责，getMusicUrl 内部 ensureStarted）。播放/下载
 * 主链路仍走 UrlResolver 编排直调 ScriptRuntime（泳道护栏在编排层），本适配器只是
 * 注册表接入点就绪，现有消费方不切换，避免行为回归。
 */
internal class ScriptSourceAdapter(
    private val runtime: ScriptRuntime,
    private val script: Script,
    private val resolveUrl: suspend (Song, String, ResolveTicket) -> String = { requestSong, requestedQuality, requestTicket ->
        runtime.getMusicUrl(
            script.id,
            script.code,
            requestSong.toJson(),
            requestSong.source.ifEmpty { "wy" },
            requestedQuality,
            purpose = requestTicket.purpose,
            scriptMeta = scriptMetaJson(script),
        )
    },
) : MusicSource {

    override val id: String = MusicSourceRegistry.SCRIPT_PREFIX + script.id

    override val name: String = script.name

    override val capabilities: SourceCapabilities = SourceCapabilities(
        platform = "script",
        supportsSearch = CapabilityLevel.UNSUPPORTED,
        supportsPlaylist = CapabilityLevel.UNSUPPORTED,
        supportsRank = CapabilityLevel.UNSUPPORTED,
        supportsLyrics = CapabilityLevel.UNSUPPORTED,
        supportsArtist = CapabilityLevel.UNSUPPORTED,
        supportsResolve = CapabilityLevel.DIRECT,
        isScript = true,
    )

    /**
     * 取链委托：禁用/空码按 NO_ENABLED_SCRIPT 失败，空 URL 按 NO_PLAYABLE_URL
     * 失败——均为显式失败语义，不是「未接线」的 null。
     */
    override val resolveCapability: ResolveCapability = object : ResolveCapability {
        override val resolveKind = ResolveKind.SCRIPT

        override suspend fun resolve(song: Song, quality: String?, ticket: ResolveTicket?): ResolveResult? {
            val t = ticket ?: ResolveTicket(songKey = song.key(), purpose = ResolveTicket.Purpose.PLAY)
            if (!script.enabled || script.code.isBlank()) {
                return ResolveResult(ticket = t, platform = song.source, scriptId = script.id, failure = ResolveFailure.NO_ENABLED_SCRIPT)
            }
            val q = quality?.takeIf { it.isNotBlank() } ?: "128k"
            return try {
                val url = resolveUrl(song, q, t)
                if (url.startsWith("http")) {
                    ResolveResult(ticket = t, url = url, quality = q, platform = song.source.ifEmpty { "wy" }, scriptId = script.id)
                } else {
                    ResolveResult(ticket = t, platform = song.source, scriptId = script.id, failure = ResolveFailure.NO_PLAYABLE_URL)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w("SWSrc", "script ${script.id} resolve failed: ${e.message}") else Log.w("SWSrc", "script ${script.id} resolve failed: ${e.javaClass.simpleName}")
                ResolveResult(ticket = t, platform = song.source, scriptId = script.id, failure = ResolveFailure.NO_PLAYABLE_URL)
            }
        }
    }

    // LX 脚本协议无 search：脚本在 Source 体系只承担 resolve 角色。
    override suspend fun search(keyword: String, page: Int, limit: Int): List<Song> = emptyList()

    override suspend fun albumDetail(id: String, page: Int, limit: Int): Playlist? = null

    override suspend fun artistDetail(artistId: String, singerName: String): Playlist? = null

    override suspend fun rank(id: String): List<Song> = emptyList()

    override suspend fun lyric(song: Song): String? = null
}
