package xyz.cncnlp.soundwave

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 用户可控的脚本健康巡检。
 * 周期任务只执行本地已导入脚本，所有 WebView 调用仍由 ScriptRuntime 的 Mutex 串行化。
 * WorkManager 的周期任务是尽力调度，不承诺精确时间；产品最低周期固定为 30 分钟。
 */
object ScriptProbeScheduler {
    private const val PREF = "soundwave"
    private const val KEY_ENABLED = "script_probe_schedule_enabled"
    private const val KEY_WIFI = "script_probe_schedule_wifi"
    private const val KEY_INTERVAL_MINUTES = "script_probe_schedule_interval_minutes"
    const val MIN_INTERVAL_MINUTES = 30
    const val MAX_INTERVAL_MINUTES = 24 * 60
    private const val UNIQUE_WORK = "soundwave_script_probe_periodic"
    private const val UNIQUE_NOW = "soundwave_script_probe_now"
    private const val INTERVAL_WRITE_DEBOUNCE_MS = 200L

    // Keep pure interval normalization callable from local JVM tests; Android Looper is only needed for writes.
    private val intervalWriteHandler by lazy { Handler(Looper.getMainLooper()) }
    private var pendingIntervalWrite: Runnable? = null

    /**
     * 统一周期边界：UI、持久化和 WorkManager 必须使用同一套范围。
     */
    internal fun normalizeIntervalMinutes(minutes: Int): Int =
        minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

    /** 手动与周期任务共用互斥标识；前台可据此避免重复跑同一批 WebView 探测。 */
    private fun ownerInput(context: Context): androidx.work.Data {
        val owner = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.persistentWork()
        return androidx.work.Data.Builder()
            .putString(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.INPUT_OWNER, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.encodeOwner(owner))
            .putLong(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.INPUT_CANCELLATION_EPOCH, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.cancellationEpoch(context))
            .putLong(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.INPUT_SESSION_GENERATION, owner.generation).build()
    }

    fun enqueueNow(context: Context) {
        val request = androidx.work.OneTimeWorkRequestBuilder<ScriptProbeWorker>()
            .setInputData(ownerInput(context))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun enabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        xyz.cncnlp.soundwave.ContextPreferences.open(context.applicationContext, PREF, Context.MODE_PRIVATE, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        reconcile(context)
    }

    fun setWifiOnly(context: Context, wifiOnly: Boolean) {
        xyz.cncnlp.soundwave.ContextPreferences.open(context.applicationContext, PREF, Context.MODE_PRIVATE, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
            .edit().putBoolean(KEY_WIFI, wifiOnly).apply()
        reconcile(context)
    }

    fun intervalMinutes(context: Context): Int = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getInt(KEY_INTERVAL_MINUTES, MIN_INTERVAL_MINUTES)
        .let(::normalizeIntervalMinutes)

    /**
     * UI 滚轮会在短时间内连续产生多个值。这里只让最后一个值落盘并触发一次
     * WorkManager reconcile，避免 60/61/62/… 的连续 SharedPreferences + WorkManager 写入。
     * 200ms 后没有新的值才真正提交。
     */
    fun setIntervalMinutes(context: Context, minutes: Int) {
        val app = context.applicationContext
        val normalized = normalizeIntervalMinutes(minutes)
        pendingIntervalWrite?.let(intervalWriteHandler::removeCallbacks)
        pendingIntervalWrite = Runnable {
            xyz.cncnlp.soundwave.ContextPreferences.open(app, PREF, Context.MODE_PRIVATE, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
                .edit().putInt(KEY_INTERVAL_MINUTES, normalized).apply()
            reconcile(app)
            pendingIntervalWrite = null
        }.also { intervalWriteHandler.postDelayed(it, INTERVAL_WRITE_DEBOUNCE_MS) }
    }

    fun reconcile(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        if (!enabled(app)) {
            wm.cancelUniqueWork(UNIQUE_WORK)
            return
        }
        val prefs = xyz.cncnlp.soundwave.ContextPreferences.open(app, PREF, Context.MODE_PRIVATE, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
        val wifiOnly = prefs.getBoolean(KEY_WIFI, true)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<ScriptProbeWorker>(intervalMinutes(app).toLong(), TimeUnit.MINUTES)
            .setInputData(ownerInput(context))
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class ScriptProbeWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val encodedOwner = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.decode(
            inputData.getString(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.INPUT_OWNER),
            inputData.getLong(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.INPUT_SESSION_GENERATION, 0L)
        ) ?: return@withContext Result.failure()
        val owner = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.persistentWork()
        val epoch = inputData.getLong(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.INPUT_CANCELLATION_EPOCH, 0L)
        fun ownerCurrent() = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isPersistentWork(applicationContext, encodedOwner, epoch)
        if (!ownerCurrent()) return@withContext Result.success()
        try {
            // WorkManager 可能在冷启动/独立进程执行；ApiClient.baseUrl 是进程内变量，
            // 不能假定前台 MainActivity 已经给它赋过值。否则 search() 直接返回空，
            // 探测器会把网络/配置失败误报成“未找到匹配歌曲”。
            ApiClient.setBase(DownloadScheduler.restoreServiceBase(applicationContext))
            SmartSourceStore.init(applicationContext)
            ScriptProbeCache.init(applicationContext)
            PlaylistImportChecker.init(applicationContext)
            val prefs = xyz.cncnlp.soundwave.ContextPreferences.open(applicationContext, "soundwave_scripts", Context.MODE_PRIVATE, owner)
            val scripts = ScriptStorage.loadScripts(prefs).filter { it.enabled && it.code.isNotBlank() }
            if (scripts.isEmpty()) {
                if (!isStopped) PersistentProbeEffects.commitSummary(applicationContext, owner, epoch, "定时检测未执行：没有已启用脚本")
                return@withContext Result.success()
            }
            val playQ = xyz.cncnlp.soundwave.ContextPreferences.open(applicationContext, "soundwave", Context.MODE_PRIVATE, owner)
                .getString("play_br", "320k").orEmpty().ifBlank { "320k" }
            val runtime = ScriptRuntime(applicationContext, owner, probeOnly = true)
            var usable = 0
            var unusable = 0
            var failed = 0
            try {
                runtime.init()
                for (script in scripts) {
                    if (isStopped || !ownerCurrent()) return@withContext Result.success()
                    val result = try {
                        UrlResolver.withProbeLane { gate ->
                            UrlResolver.probeScriptScored(script, runtime, maxPlats = 5, preferQ = playQ, yieldGate = gate)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    when {
                        result == null -> failed++
                        result.report.contains("搜索失败") -> failed++
                        result.score > 0 -> usable++
                        else -> unusable++
                    }
                }
            } finally {
                runtime.cancelSpeculativeUrlRequests()
                withContext(NonCancellable) { runtime.closeAndWait() }
            }
            val summary = "定时脚本检测已结束：${scripts.size} 个脚本，可用 $usable，无有效链 $unusable，执行失败 $failed；完整检测已覆盖各脚本声明支持的全部平台与音质，完整性逐项见「检测报告」，被抢占、超时或熔断的检测在报告中标为非完整、不冒充完整。"
            if (isStopped || !ownerCurrent()) return@withContext Result.success()
            // Summary and notification submission share the captured epoch permit.
            PersistentProbeEffects.commitSummary(applicationContext, owner, epoch, summary) {
                if (!isStopped) notifyResult(applicationContext, summary)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    /** 检测完成系统通知；通知权限未授予时静默失败，不影响检测结果落盘。 */
    private fun notifyResult(context: Context, summary: String) {
        runCatching {
            val app = context.applicationContext
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel("script_probe", "脚本检测", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "脚本检测完成通知"
                    }
                )
            }
            val n = NotificationCompat.Builder(app, "script_probe")
                .setSmallIcon(R.drawable.ic_stat_wave)
                .setContentTitle("脚本检测完成")
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(0x53F9, n)
        }
    }


}
