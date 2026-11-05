package xyz.cncnlp.soundwave.source

import xyz.cncnlp.soundwave.source.direct.PlatformTransportErrorKind

/**
 * PB-F3 失败分型阶段标签（E-3）：tx 搜索最早把「空 ≠ 失败」分开（txavail 的
 * available 语义），但其余搜索回落腿与目录腿的失败只剩「空列表」一个形态，
 * 诊断里分不清是被上游拒绝、解析坏了、网络断了还是真的没有结果。本标签是
 * 全仓统一的四型口径，只做诊断标注，不改变任何回落顺序与行为：
 * - [REJECTED] 拒绝：上游明确拒绝（HTTP 状态/策略拦截/上游错误码非 0）；
 * - [PARSE] 解析：应答到达但结构缺失/解码失败；
 * - [NETWORK] 网络：传输失败（超时/断连/折叠成空串的传输错误）；
 * - [EMPTY] 真空：请求成功、结果确实为空——不是失败。
 * 标签经 [xyz.cncnlp.soundwave.PlaybackDiagnostics.noteFailureStage] 进诊断口径。
 */
enum class FailureStage(val label: String) {
    REJECTED("rejected"),
    PARSE("parse"),
    NETWORK("network"),
    EMPTY("empty"),
}

/** 传输层错误类型 → 阶段标签（HTTP/策略/远端拒绝归 REJECTED，超时与网络归 NETWORK）。 */
internal fun PlatformTransportErrorKind.toFailureStage(): FailureStage = when (this) {
    PlatformTransportErrorKind.HTTP_STATUS,
    PlatformTransportErrorKind.REMOTE_FAILURE,
    PlatformTransportErrorKind.POLICY,
    PlatformTransportErrorKind.SSRF -> FailureStage.REJECTED
    PlatformTransportErrorKind.PARSE -> FailureStage.PARSE
    PlatformTransportErrorKind.TIMEOUT,
    PlatformTransportErrorKind.NETWORK,
    PlatformTransportErrorKind.COOLDOWN,
    PlatformTransportErrorKind.CANCELLED -> FailureStage.NETWORK
}
