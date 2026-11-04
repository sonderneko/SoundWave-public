package xyz.cncnlp.soundwave

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P2-② Android E2E：加密音频播放闭环。
 *
 * 验证项目独特能力链：加密样本 → MediaDecryptCache.prepare（下载+解密）→ Media3 ExoPlayer 可播放。
 *
 * 链路说明：
 * - 真实 FLAC 样本（assets/sample.flac）用与 QmcDecrypt 同款的 DEFAULT mask128 XOR 逻辑"加密"成密文
 *   （XOR 对合：解密器 detect 失败回退默认掩码 → 正确还原 fLaC 头），模拟线上 .mflac 加密文件。
 * - 通过本地 ServerSocket HTTP 提供密文（MediaDecryptCache 走 http 下载路径，真实模拟线上取链）。
 * - 断言：prepare 返回明文路径 + 明文文件头是 fLaC + ExoPlayer 能 prepare 到 READY 并播放。
 *
 * 纯本地闭环：不依赖网络、平台账号、真实 CDN；CI 模拟器可直接跑。
 * 注：Android 无 com.sun.net.httpserver，用 ServerSocket 手写最小 HTTP 200 响应。
 */
@RunWith(AndroidJUnit4::class)
class PlaybackDecryptE2ETest {

    private lateinit var context: Context
    private lateinit var player: ExoPlayer
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        player = ExoPlayer.Builder(context).build()
    }

    @After
    fun tearDown() {
        player.release()
        serverSocket?.close()
        serverThread?.interrupt()
    }

    // 与 QmcDecrypt.DEFAULT_MASK_58 完全一致（源码私有常量，测试内复制以构造同款掩码）
    private val defaultMask58 = intArrayOf(
        74, 214, 202, 144, 103, 247, 82,
        94, 149, 35, 159, 19, 17, 126,
        71, 116, 61, 144, 170, 63, 81,
        198, 9, 213, 159, 250, 102, 249,
        243, 214, 161, 144, 160, 247, 240,
        29, 149, 222, 159, 132, 17, 244,
        14, 116, 187, 144, 188, 63, 146,
        0, 9, 91, 159, 98, 102, 161
    ).map { it.toByte() }.toByteArray()

    // 与 QmcDecrypt.generateMask128From58 同构（superA=195, superB=216）
    private fun generateMask128From58(matrix58: ByteArray): ByteArray {
        val superA = 195.toByte()
        val superB = 216.toByte()
        val out = ByteArray(128)
        var idx = 0
        for (i in 0 until 8) {
            out[idx] = superA
            idx++
            for (j in 0 until 7) {
                out[idx] = matrix58[7 * i + j]
                idx++
            }
            out[idx] = superB
            idx++
            for (j in 6 downTo 0) {
                out[idx] = matrix58[49 - 7 * i + j]
                idx++
            }
        }
        return out
    }

    /** 用与解密器相同的 mask128 XOR 逻辑"加密"明文（对合，解密即还原）。 */
    private fun qmcEncrypt(plain: ByteArray): ByteArray {
        val matrix = generateMask128From58(defaultMask58)
        val out = plain.copyOf()
        var r = -1
        var n = -1
        for (i in out.indices) {
            r++
            n++
            if (r == 32768 || (r > 32768 && (r + 1) % 32768 == 0)) {
                r++
                n++
            }
            if (n >= 128) n -= 128
            out[i] = (out[i].toInt() xor matrix[n].toInt()).toByte()
        }
        return out
    }

    /** 最小 HTTP 200 文件响应：单连接、读请求行后回 body，服务一次即停。 */
    private fun startServer(cipher: ByteArray): String {
        val server = ServerSocket(0)
        serverSocket = server
        serverThread = Thread {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val buf = ByteArray(2048)
                    val n = input.read(buf) // 读请求头（GET 行 + headers）
                    if (n <= 0) return@Thread
                    val response = StringBuilder()
                        .append("HTTP/1.1 200 OK\r\n")
                        .append("Content-Type: application/octet-stream\r\n")
                        .append("Content-Length: ").append(cipher.size).append("\r\n")
                        .append("Connection: close\r\n")
                        .append("\r\n")
                        .toString()
                    val out = socket.getOutputStream()
                    out.write(response.toByteArray(Charsets.US_ASCII))
                    out.write(cipher)
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        return "http://127.0.0.1:${server.localPort}/enc.mflac"
    }

    @Test
    fun `encrypted flac downloads decrypts and plays via media3`() {
        val plain = context.assets.open("sample.flac").use { it.readBytes() }
        assertTrue(plain.size > 1000)

        // 加密明文 → 密文（与解密器同款掩码，模拟线上 .mflac）
        val cipher = qmcEncrypt(plain)
        val url = startServer(cipher)

        // 核心链路：MediaDecryptCache.prepare（下载 + 解密成明文）
        val prepared = runBlocking {
            MediaDecryptCache.prepare(context, url, "e2e-test-song", "mflac")
        }
        assertNotNull("解密缓存应返回明文路径", prepared)
        val plainPath = prepared!!

        // 明文文件存在、非空、头是 fLaC
        val f = File(plainPath)
        assertTrue("明文文件应存在", f.exists())
        assertTrue("明文文件应非空", f.length() > 1000)
        val head = f.readBytes().copyOfRange(0, 4)
        assertEquals("明文头应为 fLaC", "fLaC", String(head))

        // Media3 ExoPlayer 端到端：能 prepare 到 READY 并进入播放
        val latch = CountDownLatch(1)
        var state = -1
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state = playbackState
                if (playbackState == Player.STATE_READY) latch.countDown()
            }
        })
        player.setMediaItem(MediaItem.fromUri(File(plainPath).toURI().toString()))
        player.prepare()
        player.play()

        val ready = latch.await(10, TimeUnit.SECONDS)
        assertTrue("ExoPlayer 应在 10s 内进入 READY（实际 state=$state）", ready)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue("进入播放后 position 应 >= 0", player.currentPosition >= 0L)
    }

    @Test
    fun `prepare rejects non http urls without side effects`() {
        val prepared = runBlocking {
            MediaDecryptCache.prepare(context, "file:///tmp/nonexistent.mflac", "e2e-bad", "mflac")
        }
        assertTrue("非 http 输入应返回 null", prepared == null)
    }
}
