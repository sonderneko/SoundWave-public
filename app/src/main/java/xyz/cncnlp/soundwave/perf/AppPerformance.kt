package xyz.cncnlp.soundwave.perf

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.StrictMode
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import xyz.cncnlp.soundwave.BuildConfig
import xyz.cncnlp.soundwave.CacheManager
import xyz.cncnlp.soundwave.LocalStore
import xyz.cncnlp.soundwave.MainActivity

/**
 * r656-G 进程级性能初始化（全部从 SoundWaveApplication 调用，MainActivity 零改动）：
 * 高刷新率、debug StrictMode、封面索引预热、统一 Coil ImageLoader。
 */
internal object AppPerformance {
    fun install(app: Application) {
        if (BuildConfig.DEBUG) enableDebugStrictMode()
        HighRefreshRate.install(app)
        // 封面本地索引在后台一次性建好，组合期 coverModel() 只查内存。
        CacheManager.warmCoverIndexAsync(app)
    }

    private fun enableDebugStrictMode() {
        try {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        } catch (_: Throwable) {}
    }

    /**
     * 统一 ImageLoader：
     * - 硬件位图（API 26+ 默认开启，显式保留）：上传 GPU 一次，滚动时零拷贝绘制；
     * - 不允许 RGB_565，封面渐变不出色带；
     * - 内存缓存取 25% 可用堆，滚动回看封面直接命中；
     * - 磁盘缓存沿用 Coil 默认目录 cacheDir/image_cache（不丢已有缓存），上限 256MB；
     * - 忽略图床 no-cache 头：封面 URL 本身带指纹，重复校验只会让列表回看时重新下载。
     */
    fun buildImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .allowHardware(true)
        .allowRgb565(false)
        .bitmapFactoryMaxParallelism(2)
        .crossfade(false)
        .respectCacheHeaders(false)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        // User factories precede Coil defaults: File, data:, and HTTP(S) models are bounded;
        // content/resource and other schemes retain their existing default fetchers.
        .components {
            add(ValidatedCoverFileFetcherFactory())
            add(ValidatedCoverFetcherFactory())
        }
        .build()
}

/** 与 Activity 生命周期挂钩的数据层开关。 */
internal object AppForegroundHooks {
    fun onActivityCreated(activity: Activity) {
        // 只有真正的主界面才开启整包写入的后台合并；Worker/单测/其它 Activity 保持同步语义。
        if (activity is MainActivity) LocalStore.deferMainThreadSaves = true
    }

    fun onActivityPaused(@Suppress("UNUSED_PARAMETER") activity: Activity) {
        // 离开前台前把合并中的快照落盘，进程随后被杀也不丢最后一次修改。
        try { LocalStore.flushPendingWrites() } catch (_: Throwable) {}
    }
}
