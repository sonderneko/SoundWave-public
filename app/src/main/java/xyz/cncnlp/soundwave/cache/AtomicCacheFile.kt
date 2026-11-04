package xyz.cncnlp.soundwave.cache

import java.io.File
import java.io.IOException

/** Stages a cache replacement beside its destination and commits only with a same-directory rename. */
internal object AtomicCacheFile {
    fun isStagingName(file: File): Boolean =
        file.name.startsWith("sw-cache-") && file.name.endsWith(".part")

    fun replace(target: File, writeStaging: (File) -> Unit) {
        val parent = target.parentFile ?: throw IOException("缓存目标没有父目录")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建缓存目录")
        val staging = File.createTempFile("sw-cache-", ".part", parent)
        try {
            writeStaging(staging)
            if (!staging.isFile) throw IOException("缓存暂存文件不存在")
            if (!staging.renameTo(target)) throw IOException("无法原子提交缓存文件")
        } finally {
            try { staging.delete() } catch (_: Exception) {}
        }
    }
}
