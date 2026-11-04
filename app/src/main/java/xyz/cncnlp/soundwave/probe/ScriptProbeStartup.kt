package xyz.cncnlp.soundwave.probe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.Script
import xyz.cncnlp.soundwave.ScriptProbeCache
import xyz.cncnlp.soundwave.ScriptRuntime
import xyz.cncnlp.soundwave.UrlResolver

/** Startup policy is deliberately based only on current, complete full reports. */
internal object ScriptProbeStartupPolicy {
    data class InitialState(val imported: Boolean = false, val manual: Boolean = false, val consumed: Boolean = false)
    fun afterImport(state: InitialState) = state.copy(imported = true)
    fun afterManual(state: InitialState) = state.copy(manual = true, consumed = true)
    fun shouldClaim(state: InitialState, hasRunnable: Boolean) = state.imported && !state.manual && !state.consumed && hasRunnable
    private fun state(p: android.content.SharedPreferences) = InitialState(
        p.getBoolean("probe_initial_imported_v1", false), p.getBoolean("probe_manual_v1", false), p.getBoolean("probe_initial_consumed_v1", false))
    fun noteImport(p: android.content.SharedPreferences) { p.edit().putBoolean("probe_initial_imported_v1", true).commit() }
    fun noteManual(p: android.content.SharedPreferences) { p.edit().putBoolean("probe_manual_v1", true).putBoolean("probe_initial_consumed_v1", true).commit() }
    @Synchronized fun claimInitial(p: android.content.SharedPreferences, hasRunnable: Boolean): Boolean {
        if (!shouldClaim(state(p), hasRunnable)) return false
        return p.edit().putBoolean("probe_initial_consumed_v1", true).commit()
    }
    fun missingFullReportTargets(
        scripts: List<Script>,
        fullReport: (Script) -> ScriptProbeCache.Report?
    ): List<Script> = scripts
        .asSequence()
        .filter { it.enabled && it.code.isNotBlank() }
        .distinctBy { it.id }
        .filter { script ->
            fullReport(script)?.let { ScriptProbeCache.isValidFullReport(it, script) } != true
        }
        .toList()

    /** Null means there is no verified playable evidence, so changing order would be speculative. */
    fun sortWithVerifiedReports(
        scripts: List<Script>,
        mode: ScriptProbeCache.SortMode,
        fullReport: (Script) -> ScriptProbeCache.Report?,
        score: (Script, ScriptProbeCache.SortMode) -> Long
    ): List<Script>? {
        val hasVerifiedSuccess = scripts.any { script ->
            fullReport(script)?.let {
                ScriptProbeCache.isValidFullReport(it, script) && it.valid.isNotEmpty()
            } == true
        }
        if (!hasVerifiedSuccess) return null

        val withCode = scripts.filter { it.code.isNotBlank() }
        return withCode.sortedWith(
            compareByDescending<Script> { score(it, mode) }
                .thenBy { it.name.ifEmpty { it.id } }
        ) + scripts.filter { it.code.isBlank() }
    }
}

/** Shared full-matrix batch path for startup detection and the existing manual batch action. */
internal object ScriptProbeBatchRunner {
    suspend fun runFull(
        targets: List<Script>,
        runtime: ScriptRuntime,
        preferQ: String,
        onProgress: ((Float) -> Unit)? = null
    ): List<String> {
        if (targets.isEmpty()) return emptyList()
        // ACCEPT5：以调用方 Job 区分「批量被整体取消」（页面销毁等，向上抛收尾）与
        // 「单个脚本被播放抢占」（探测通道的设计内取消，只终止该脚本并落定论记录）。
        val batchJob = currentCoroutineContext()[Job]
        return withContext(Dispatchers.IO) {
            val sourceGate = Semaphore(1)
            val isolatedRuntime = runtime.forkForProbe()
            try {
            val jobs = targets.map { script ->
                async {
                    sourceGate.withPermit {
                        val displayName = script.name.ifBlank { script.id }
                        try {
                            UrlResolver.withProbeLane { gate ->
                                UrlResolver.probeScriptScored(
                                    script,
                                    isolatedRuntime,
                                    maxPlats = 5,
                                    preferQ = preferQ,
                                    yieldGate = gate
                                ).report
                            }
                        } catch (cancelled: CancellationException) {
                            if (batchJob?.isCancelled == true) throw cancelled
                            // 播放抢占只掐当前脚本：落「未执行」定论记录，批量继续其余脚本。
                            // 此前此处原样重抛，一个脚本被抢占会整队崩掉且不落任何状态，
                            // 没轮到的脚本在列表里继续显示「未检测」。
                            ScriptProbeCache.saveProbeSkipped(script, "批量检测被播放请求抢占，本轮未执行")
                            "⊘ $displayName：本轮跳过（播放请求抢占），未形成检测结论；可单独重新检测"
                        } catch (e: Exception) {
                            ScriptProbeCache.saveProbeSkipped(script, "检测异常中断（${e.javaClass.simpleName}），本轮未形成结论")
                            "✗ $displayName：检测异常中断（${e.javaClass.simpleName}），未完成完整矩阵；请重试"
                        }
                    }
                }
            }
            val reports = ArrayList<String>(targets.size)
            jobs.forEachIndexed { index, job ->
                val report = try {
                    job.await()
                } catch (cancelled: CancellationException) {
                    if (batchJob?.isCancelled == true) throw cancelled
                    // 防御：job 内部已把抢占转成跳过行，走到这里说明取消来自 job 之外，
                    // 同样给该脚本落定论，不让它以「未检测」收场。
                    val script = targets[index]
                    ScriptProbeCache.saveProbeSkipped(script, "批量检测被播放请求抢占，本轮未执行")
                    "⊘ ${script.name.ifBlank { script.id }}：本轮跳过（播放请求抢占），未形成检测结论；可单独重新检测"
                } catch (_: Exception) {
                    null
                }
                if (report != null) reports.add(report)
                if (onProgress != null) {
                    withContext(Dispatchers.Main) {
                        onProgress((index + 1).toFloat() / targets.size)
                    }
                }
            }
            reports
            } finally {
                isolatedRuntime.cancelSpeculativeUrlRequests()
                withContext(NonCancellable) { isolatedRuntime.closeAndWait() }
            }
        }
    }
}
