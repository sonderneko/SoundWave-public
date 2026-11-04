package xyz.cncnlp.soundwave

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class DlnaDevice(val name: String, val location: String, val controlUrl: String)

object DlnaController {
    /** 会话状态：本机把当前设备 + 遥测快照保存于此，供 UI 显示远端状态与断线重连。 */
    data class Session(
        val device: DlnaDevice,
        val songTitle: String = "",
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val isPlaying: Boolean = false,
        val lastOkAt: Long = System.currentTimeMillis()
    )

    @Volatile var session: Session? = null

    fun currentDevice(): DlnaDevice? = session?.device

    fun activeSessionTitle(): String = session?.songTitle.orEmpty()

    fun sessionPlaying(): Boolean = session?.isPlaying ?: false

    fun sessionPositionMs(): Long = session?.positionMs ?: 0L

    fun sessionDurationMs(): Long = session?.durationMs ?: 0L

    fun markDisconnected() {
        session = null
    }

    suspend fun scan(timeoutMs: Int = 1800): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val locations = linkedSetOf<String>()
        val request = ("M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n").toByteArray()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 250
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
                val until = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < until) {
                    try {
                        val buf = ByteArray(8192)
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val response = String(packet.data, 0, packet.length)
                        response.lineSequence().firstOrNull { it.trim().lowercase(Locale.ROOT).startsWith("location:") }
                            ?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http") }?.let(locations::add)
                    } catch (_: java.net.SocketTimeoutException) { }
                }
            }
        } catch (_: Exception) { }
        locations.mapNotNull { describe(it) }
    }

    private fun describe(location: String): DlnaDevice? {
        return try {
            val xml = http(location, "GET", null, null)
            val name = tag(xml, "friendlyName").ifBlank { URL(location).host }
            val serviceBlock = Regex("<service>.*?<serviceType>\\s*urn:schemas-upnp-org:service:AVTransport:[^<]+</serviceType>.*?</service>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(xml)?.value ?: return null
            val control = tag(serviceBlock, "controlURL")
            if (control.isBlank()) return null
            DlnaDevice(name, location, URL(URL(location), control).toString())
        } catch (_: Exception) { null }
    }

    suspend fun play(device: DlnaDevice, mediaUrl: String, title: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(mediaUrl.startsWith("http")) { "当前歌曲没有可投送链接" }
            soap(device.controlUrl, "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>${esc(mediaUrl)}</CurrentURI><CurrentURIMetaData>${esc(title)}</CurrentURIMetaData>")
            soap(device.controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            Unit
        }.onSuccess {
            session = Session(device, title, 0L, 0L, isPlaying = true)
        }
    }

    /** 远端暂停。 */
    suspend fun pause(device: DlnaDevice): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { soap(device.controlUrl, "Pause", "<InstanceID>0</InstanceID>"); Unit }
            .onSuccess { session = session?.copy(isPlaying = false) }
    }

    /** 远端继续。 */
    suspend fun resume(device: DlnaDevice): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { soap(device.controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>"); Unit }
            .onSuccess { session = session?.copy(isPlaying = true) }
    }

    /** 远端跳转（毫秒）。 */
    suspend fun seek(device: DlnaDevice, positionMs: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            soap(device.controlUrl, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${formatTime(positionMs)}</Target>")
            Unit
        }.onSuccess { session = session?.copy(positionMs = positionMs) }
    }

    /** 远端停止。 */
    suspend fun stop(device: DlnaDevice): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { soap(device.controlUrl, "Stop", "<InstanceID>0</InstanceID>"); Unit }
            .onSuccess { session = session?.copy(isPlaying = false, positionMs = 0L) }
    }

    /** 读取远端播放状态（GetTransportInfo / GetPositionInfo）。 */
    suspend fun poll(device: DlnaDevice): Session? = withContext(Dispatchers.IO) {
        runCatching {
            val transport = soap(device.controlUrl, "GetTransportInfo", "<InstanceID>0</InstanceID>")
            val state = tag(transport, "CurrentTransportState").uppercase(Locale.ROOT)
            val pos = soap(device.controlUrl, "GetPositionInfo", "<InstanceID>0</InstanceID>")
            val position = parseTime(tag(pos, "RelTime"))
            val duration = parseTime(tag(pos, "TrackDuration"))
            val playing = state == "PLAYING"
            Session(
                device = device,
                songTitle = session?.songTitle ?: "",
                positionMs = position,
                durationMs = duration,
                isPlaying = playing,
                lastOkAt = System.currentTimeMillis()
            ).also { session = it }
        }.onFailure { markDisconnected() }.getOrNull()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L) / 1000L)
        return "%d:%02d:%02d".format(totalSec / 3600L, (totalSec % 3600L) / 60L, totalSec % 60L)
    }

    private fun parseTime(t: String): Long {
        val parts = t.trim().split(':')
        if (parts.size != 3) return 0L
        return (parts[0].toLongOrNull() ?: 0L) * 3600_000L +
            (parts[1].toLongOrNull() ?: 0L) * 60_000L +
            (parts[2].toLongOrNull() ?: 0L) * 1000L
    }

    private fun soap(url: String, action: String, body: String): String {
        val envelope = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">$body</u:$action></s:Body></s:Envelope>"
        return http(url, "POST", envelope, "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
    }

    private val httpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(1800, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(2200, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    private fun http(url: String, method: String, body: String?, soapAction: String?): String {
        // 局域网 UPnP/SOAP 本质明文，走应用自控的 OkHttp：平台 HttpURLConnection 自
        // SW-03-10 明文收紧起会按 network_security_config 拦截局域网 http，而 DLNA 是
        // 用户在自有局域网内主动发起的设备协议（与 WebDAV per-host 例外同类）。
        val builder = okhttp3.Request.Builder().url(url)
            .header("Connection", "close")
        if (body != null) {
            builder.header("Content-Type", "text/xml; charset=utf-8")
            if (soapAction != null) builder.header("SOAPAction", soapAction)
            builder.method(
                method,
                body.toRequestBody("text/xml; charset=utf-8".toMediaType())
            )
        } else {
            builder.method(method, null)
        }
        httpClient.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("DLNA ${resp.code}")
            return text
        }
    }

    private fun tag(xml: String, name: String): String = Regex("<$name[^>]*>(.*?)</$name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(xml)?.groupValues?.get(1)?.trim().orEmpty()
    private fun esc(v: String): String = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
