package xyz.cncnlp.soundwave.source

import xyz.cncnlp.soundwave.ResolveResult
import xyz.cncnlp.soundwave.ResolveTicket
import xyz.cncnlp.soundwave.Song

/**
 * 取链能力分级：声明该源在 MusicSource 边界内可独立完成的取链形态。
 */
internal enum class ResolveKind { CLIENT_OFFICIAL, SCRIPT, CLOUD, NONE }

/**
 * 取链能力契约：返回可播放URL；null=本源在当前条件下无法解析。
 *
 * 跨源编排仍在 UrlResolver，勿在源头直调 withProbeLane/withProbeRuntime：
 * UrlResolver 的并发护栏（playResolveLane / downloadResolveLane / probeLane
 * 三个 Mutex + withLock）属编排层所有，源头自行取链会绕过限流并破坏
 * 播放/下载/探测三条泳道的互斥语义。
 */
internal interface ResolveCapability {
    val resolveKind: ResolveKind

    suspend fun resolve(song: Song, quality: String?, ticket: ResolveTicket?): ResolveResult?
}
