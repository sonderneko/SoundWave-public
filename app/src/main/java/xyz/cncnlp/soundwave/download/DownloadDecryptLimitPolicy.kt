package xyz.cncnlp.soundwave.download

import java.util.Locale

/** Pure policy boundary for encrypted downloads; kept Android-free for lightweight JVM tests. */
internal object DownloadDecryptLimitPolicy {
    const val MAX_DECRYPT_BYTES = 200L * 1024L * 1024L

    private val supportedEncryptedExtensions = setOf(
        "mflac", "qmcflac", "bkcflac", "mgg", "qmcogg", "ncm", "tkm"
    )

    fun exceedsLimit(extension: String, sizeBytes: Long): Boolean =
        extension.lowercase(Locale.ROOT) in supportedEncryptedExtensions && sizeBytes > MAX_DECRYPT_BYTES
}

internal class EncryptedMediaTooLargeException : Exception(
    "加密媒体超过 200 MiB 解密上限，未保存为完成文件"
)
