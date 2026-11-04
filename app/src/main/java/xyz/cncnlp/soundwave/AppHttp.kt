package xyz.cncnlp.soundwave

import okhttp3.OkHttpClient
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

/**
 * 进程级共享 OkHttpClient（P2-1 收口）：全 App 只建一个连接池/dispatcher，
 * 各业务用 newBuilder() 覆写超时/重定向等差异项。
 *
 * 安全例外：ApiClient 的登录/API 主 client 保留独立实例——它禁止 HTTPS→HTTP 重定向、
 * 带 TLS 兼容配置与 HTTP/1.1 限制，属于安全敏感通道，不与通用池混用。
 * （1.0.656 起不再做证书固定：默认服务端在 Cloudflare 之后，边缘证书会换钥换 CA；只用系统 CA 校验。）
 */
object AppHttp {
    /** 通用默认：中等超时 + 跟随重定向。绝大多数业务直接复用或在其上覆写差异项。 */
    val shared: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Async script calls do not consume the general AppHttp dispatcher slots. */
    val scriptDispatcher: Dispatcher = Dispatcher().apply {
        maxRequests = 8
        maxRequestsPerHost = 4
    }
}
