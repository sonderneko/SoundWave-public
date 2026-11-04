package xyz.cncnlp.soundwave

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import xyz.cncnlp.soundwave.migration.LegacyPlatformAuthCleanup
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.startup.StartupTempFileCleaner

/**
 * 崩溃报告先脱敏（[CrashReportRedactor]）再保存在本机；此流程不读取 Android ID，也不会把报告上传到任何服务器。
 * 用户可在“崩溃诊断”页查看、复制或清除；CrashExport 另把报告存一份到 下载/SoundWave 便于导出。
 */
object CrashGuard {
    private const val FILE_NAME = "last_startup_crash.txt"
    private const val PENDING_NAME = "pending_bug_report.txt"

    private val privacyLock = Any()

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val text = CrashReportRedactor.redact(buildReport(app, thread, throwable))
                synchronized(privacyLock) {
                    File(app.filesDir, FILE_NAME).writeText(text.take(24_000), Charsets.UTF_8)
                    File(app.filesDir, PENDING_NAME).writeText(text.take(24_000), Charsets.UTF_8)
                }
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 读取时也脱敏：升级前写入的旧报告在查看/复制时同样不暴露敏感信息。 */
    fun last(context: Context): String = CrashReportRedactor.redact(readFile(context, FILE_NAME))

    fun pending(context: Context): String = CrashReportRedactor.redact(readFile(context, PENDING_NAME))

    fun clear(context: Context) {
        synchronized(privacyLock) {
            deleteFile(context, FILE_NAME)
        }
    }

    fun clearPending(context: Context) {
        synchronized(privacyLock) {
            deleteFile(context, PENDING_NAME)
        }
    }

    private fun buildReport(app: Context, thread: Thread, throwable: Throwable): String {
        val out = StringWriter()
        PrintWriter(out).use { w ->
            w.println("时间: ${System.currentTimeMillis()}")
            w.println("版本: ${appVersion(app)}")
            w.println("机型: ${Build.MANUFACTURER} ${Build.MODEL} API${Build.VERSION.SDK_INT}")
            w.println("线程: ${thread.name}")
            throwable.printStackTrace(w)
        }
        return out.toString()
    }

    private fun appVersion(app: Context): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
    } catch (_: Throwable) { "unknown" }

    private fun readFile(context: Context, name: String): String = try {
        File(context.filesDir, name).takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty()
    } catch (_: Throwable) { "" }

    private fun deleteFile(context: Context, name: String) {
        try { File(context.filesDir, name).delete() } catch (_: Throwable) {}
    }
}

open class SoundWaveApplication : Application(), coil.ImageLoaderFactory {
    // r656-G：统一 Coil 配置（硬件位图、25% 内存缓存、忽略图床 no-cache 头），见 perf/AppPerformance。
    override fun newImageLoader(): coil.ImageLoader = xyz.cncnlp.soundwave.perf.AppPerformance.buildImageLoader(this)

    override fun onCreate() {
        super.onCreate()
        try { BackupRestoreRecovery.enforcePendingGate(this) } catch (_: Throwable) {}
        // E2：下载删除的未决批次在进程重启后一律提交并清扫暂存区（撤销入口已随进程消失）。
        try { DownloadPendingDelete.sweepOnStartup(this) } catch (_: Throwable) {}
        try { LegacyUnassignedSnapshot.capture(this) } catch (_: Throwable) {}
        try {
            val musicRoot = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(filesDir, "music")
            StartupTempFileCleaner.cleanup(
                File(cacheDir, "media_decrypt"),
                File(musicRoot, "SoundWave"),
                File(filesDir, "covers"),
                File(filesDir, "lyrics")
            )
        } catch (_: Throwable) {}
        CrashGuard.install(this)
        try { CrashExport.install(this) } catch (_: Throwable) {} // r658e：崩溃报告另存到 下载/SoundWave
        try { LegacyPlatformAuthCleanup.clearLegacyThirdPartyMusicAuth(this) } catch (_: Throwable) {}
        // Worker/冷启动都必须先初始化非 UI store；周期脚本检测不依赖 MainActivity。
        try {
            SmartSourceStore.init(this)
            ScriptProbeCache.init(this)
            ScriptVerdictStore.init(this)
            UrlResolver.init(this)
            PlaylistImportChecker.init(this)
            PlaybackDiagnostics.init(this)
            ScriptProbeScheduler.reconcile(this)
        } catch (_: Throwable) {}
        // r656-G：高刷新率、debug StrictMode、封面索引预热（均不阻塞主线程）。
        try { xyz.cncnlp.soundwave.perf.AppPerformance.install(this) } catch (_: Throwable) {}
    }
}
