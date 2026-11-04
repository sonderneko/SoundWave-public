package xyz.cncnlp.soundwave

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.ui.notice.AppNotice

/**
 * 验收二轮 E2：下载删除的延迟落盘（deferred-commit）状态机。
 *
 * A 波当时以「删文件不可逆」为由没给撤销，本轮按用户裁定补齐且撤销必须真能恢复：
 *
 *   stage（点删除）→ 停批下载；文件先暂存到 cacheDir/download_delete_trash/<batchId>/
 *     （普通文件移动、跨卷回退复制+校验；content:// 只复制校验、原件不动；
 *     .part/.part.resume 片段按 DownloadHelper.deleteArtifact 的同一匹配规则一并暂存）；
 *     记录立即经 LocalStore.removeDownloads 隐藏；批次（记录快照 + 暂存映射 + 截止时间）
 *     落盘 filesDir/download_pending_delete.json；发 AppNotice.undo 胶囊（既有 4s 规格）。
 *   undo（点撤销）→ 取消定时提交；暂存文件原样移回（原件位置已有新文件时保留新文件、
 *     丢弃暂存副本）；记录经 LocalStore.addDownloads 回填；清批次与暂存区。
 *   commit（窗口到期，[WINDOW_MS]）→ content:// 原件此时才经 resolver 真删；
 *     暂存区整批删除；注册表摘除。
 *   sweep（进程重启，SoundWaveApplication.onCreate）→ 注册表里所有未决批次一律 commit
 *     （撤销入口已随进程消失，不能把文件永远留在暂存区），并清扫无注册的孤儿暂存目录。
 *
 * 边界：
 * - 暂存失败的条目整条中止（记录与文件都保留），不进入批次；全部失败时只发普通提示。
 * - 删除窗口内同一歌曲被重新下载：新文件落在原路径，undo 时保留新文件、丢暂存副本，
 *   记录照常恢复（指向同路径的新文件）；commit 只删暂存副本，绝不碰原路径现状。
 * - 删除时歌曲正在播放：暂存是移动/复制，已打开的 fd 不受影响；记录已移除，
 *   LocalStore.findReadableDownload 不会再把暂存文件作为可播源提供。
 * - 撤销时单个条目文件移回失败：该条目不恢复记录（等同该条已提交删除），其余条目照常恢复。
 * - commit 的内部窗口 [WINDOW_MS] 比 4s 胶囊长：胶囊可能被新提示顶替而提前消失，
 *   多出的时间只是暂存保留期，不产生可见差异；不新造通知规格。
 */
object DownloadPendingDelete {
    internal const val WINDOW_MS = 10_000L
    private const val REGISTRY_NAME = "download_pending_delete.json"
    private const val TRASH_DIR_NAME = "download_delete_trash"

    /** 暂存文件映射：originalPath 为原始绝对路径或 content:// URI；safCopy 表示原件未动、暂存区只是副本。 */
    data class StagedFile(val originalPath: String, val stagedName: String, val safCopy: Boolean = false)

    data class PendingEntry(val item: DownloadItem, val deleteFiles: Boolean, val staged: List<StagedFile>)

    data class PendingBatch(val id: String, val deadlineMs: Long, val entries: List<PendingEntry>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()

    /** MainActivity.deleteDownloads 的唯一入口：暂存 → 隐藏记录 → 发撤销胶囊 → 定时提交。 */
    fun requestDelete(ctx: Context, items: List<DownloadItem>, deleteFiles: Boolean, onChanged: () -> Unit) {
        if (items.isEmpty()) return
        val app = ctx.applicationContext ?: ctx
        scope.launch {
            val batch = stage(app, items, deleteFiles)
            if (batch == null) {
                AppNotice.toast("未能删除所选下载")
                return@launch
            }
            withContext(Dispatchers.Main) { onChanged() }
            val n = batch.entries.size
            val message = when {
                deleteFiles && n == 1 -> "已删除 1 项下载"
                deleteFiles -> "已删除 $n 项下载"
                n == 1 -> "已删除 1 条下载记录"
                else -> "已删除 $n 条下载记录"
            }
            AppNotice.undo(message) {
                scope.launch {
                    undo(app, batch.id)
                    withContext(Dispatchers.Main) { onChanged() }
                }
            }
            scheduleCommit(app, batch.id)
        }
    }

    /** 进程启动清理：未决批次全部提交 + 孤儿暂存目录清扫（见类头状态机 sweep）。 */
    fun sweepOnStartup(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        scope.launch {
            val batches = synchronized(lock) { loadRegistry(app) }
            batches.forEach { commit(app, it.id) }
            val known = batches.mapTo(HashSet()) { it.id }
            try {
                File(app.cacheDir, TRASH_DIR_NAME).listFiles()?.forEach {
                    if (it.isDirectory && it.name !in known) it.deleteRecursively()
                }
            } catch (_: Exception) {}
        }
    }

    private fun stage(ctx: Context, items: List<DownloadItem>, deleteFiles: Boolean): PendingBatch? {
        // SW-02-03：stopBatch 返回围栏内的权威行；调用方快照可能仍是 pending: 路径，
        // 而 worker 已落盘成品——按返回行暂存才不会漏掉刚完成的文件。
        val stoppedItems = try { DownloadScheduler.stopBatch(ctx, items, paused = false) } catch (_: Exception) { items }
        val batchId = "b" + System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().take(6)
        val trashRoot = File(ctx.cacheDir, TRASH_DIR_NAME)
        val trashDir = File(trashRoot, batchId).apply { mkdirs() }
        val entries = mutableListOf<PendingEntry>()
        var seq = 0
        for (item in stoppedItems) {
            if (!deleteFiles) {
                entries += PendingEntry(item, deleteFiles = false, staged = emptyList())
                continue
            }
            val staged = mutableListOf<StagedFile>()
            var ok = true
            val path = item.path
            when {
                path.startsWith("content://") -> {
                    // SAF 原件不动：先复制进暂存区并校验，commit 时才真删原件，undo 直接弃副本。
                    val name = "f${seq++}_saf"
                    val dest = File(trashDir, name)
                    if (copyFromUri(ctx, Uri.parse(path), dest)) {
                        staged += StagedFile(path, name, safCopy = true)
                    } else {
                        dest.delete()
                        ok = false
                    }
                }
                path.isNotBlank() && !path.startsWith("pending:") -> {
                    val src = File(path.removePrefix("file://"))
                    if (src.isFile) {
                        val name = "f${seq++}_main"
                        if (moveFile(src, File(trashDir, name))) {
                            staged += StagedFile(src.absolutePath, name)
                        } else {
                            ok = false
                        }
                    }
                    // 主文件已不存在：无可暂存，按已删处理（记录仍进批次，可撤销恢复记录）。
                }
            }
            if (ok) {
                fragmentFiles(ctx, item).forEach { frag ->
                    val name = "f${seq++}_part"
                    if (moveFile(frag, File(trashDir, name))) {
                        staged += StagedFile(frag.absolutePath, name)
                    }
                }
                entries += PendingEntry(item, deleteFiles = true, staged = staged)
            } else {
                // 本条暂存失败：已移入的文件原样移回，记录与文件都不动。
                staged.forEach { sf ->
                    val stagedFile = File(trashDir, sf.stagedName)
                    if (sf.safCopy) stagedFile.delete()
                    else if (stagedFile.isFile) moveFile(stagedFile, File(sf.originalPath))
                }
            }
        }
        if (entries.isEmpty()) {
            trashDir.deleteRecursively()
            return null
        }
        LocalStore.removeDownloads(ctx, entries.map { it.item })
        val batch = PendingBatch(batchId, System.currentTimeMillis() + WINDOW_MS, entries)
        synchronized(lock) {
            val all = loadRegistry(ctx).toMutableList()
            all += batch
            saveRegistry(ctx, all)
        }
        return batch
    }

    private fun undo(ctx: Context, batchId: String) {
        val batch = synchronized(lock) {
            jobs.remove(batchId)?.cancel()
            val all = loadRegistry(ctx)
            val found = all.firstOrNull { it.id == batchId }
            if (found != null) saveRegistry(ctx, all.filterNot { it.id == batchId })
            found
        } ?: return
        val trashDir = File(File(ctx.cacheDir, TRASH_DIR_NAME), batchId)
        val restored = mutableListOf<DownloadItem>()
        for (entry in batch.entries) {
            var entryOk = true
            for (sf in entry.staged) {
                val stagedFile = File(trashDir, sf.stagedName)
                if (sf.safCopy) {
                    stagedFile.delete() // 原件从未移动，弃副本即可。
                } else if (stagedFile.isFile) {
                    val dest = File(sf.originalPath)
                    if (dest.exists()) {
                        // 窗口内已重新下载：保留新文件，丢弃暂存副本。
                        stagedFile.delete()
                    } else {
                        dest.parentFile?.mkdirs()
                        if (!moveFile(stagedFile, dest)) entryOk = false
                    }
                }
            }
            // 文件没能移回的条目不恢复记录（等同该条已提交），避免留下指向缺失文件的记录。
            if (entryOk) restored += entry.item
        }
        trashDir.deleteRecursively()
        if (restored.isNotEmpty()) LocalStore.addDownloads(ctx, restored)
    }

    private fun commit(ctx: Context, batchId: String) {
        val batch = synchronized(lock) {
            jobs.remove(batchId)
            val all = loadRegistry(ctx)
            val found = all.firstOrNull { it.id == batchId }
            if (found != null) saveRegistry(ctx, all.filterNot { it.id == batchId })
            found
        } ?: return
        // content:// 原件直到提交这一刻才真删（暂存期间原件一直在，undo 零成本）。
        for (entry in batch.entries) {
            for (sf in entry.staged) {
                if (sf.safCopy) {
                    try { ctx.contentResolver.delete(Uri.parse(sf.originalPath), null, null) } catch (_: Exception) {}
                }
            }
        }
        File(File(ctx.cacheDir, TRASH_DIR_NAME), batchId).deleteRecursively()
    }

    private fun scheduleCommit(ctx: Context, batchId: String) {
        val job = scope.launch {
            delay(WINDOW_MS)
            commit(ctx, batchId)
        }
        synchronized(lock) { jobs[batchId] = job }
    }

    /** 与 DownloadHelper.deleteArtifact 完全相同的片段匹配：按稳定 taskId 后缀找 .part 与 .part.resume。 */
    private fun fragmentFiles(ctx: Context, item: DownloadItem): List<File> {
        val id = item.taskId.ifBlank { DownloadScheduler.taskId(item.song, item.quality) }
        val suffix = id.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(48)
        val dir = try { DownloadHelper.dir(ctx) } catch (_: Exception) { return emptyList() }
        val files = try { dir.listFiles() } catch (_: Exception) { null } ?: return emptyList()
        val exts = listOf("mp3", "flac", "m4a", "ogg", "ape", "mflac", "mgg", "ncm", "tkm")
        return files.filter { f ->
            exts.any { ext -> f.name.endsWith("-$suffix.$ext.part") } ||
                (f.name.endsWith(".part.resume") && f.name.contains("-$suffix."))
        }
    }

    /** 移动文件：同卷 rename 优先；跨卷回退复制 + 长度校验 + 删源。 */
    private fun moveFile(src: File, dest: File): Boolean {
        if (src.renameTo(dest)) return true
        return try {
            src.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            if (dest.length() == src.length()) {
                src.delete()
                true
            } else {
                dest.delete()
                false
            }
        } catch (_: Exception) {
            dest.delete()
            false
        }
    }

    private fun copyFromUri(ctx: Context, uri: Uri, dest: File): Boolean = try {
        val input = ctx.contentResolver.openInputStream(uri) ?: return false
        input.use { stream -> dest.outputStream().use { output -> stream.copyTo(output) } }
        dest.isFile && dest.length() > 0L
    } catch (_: Exception) {
        false
    }

    // ---- 注册表（filesDir JSON，原子换名写入）；编解码为纯函数，可单测。 ----

    private fun registryFile(ctx: Context): File = File(ctx.filesDir, REGISTRY_NAME)

    private fun loadRegistry(ctx: Context): List<PendingBatch> = try {
        val f = registryFile(ctx)
        if (f.isFile) decodeBatches(f.readText(Charsets.UTF_8)) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun saveRegistry(ctx: Context, batches: List<PendingBatch>) {
        try {
            val f = registryFile(ctx)
            val tmp = File(ctx.filesDir, "$REGISTRY_NAME.tmp")
            tmp.writeText(encodeBatches(batches), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(encodeBatches(batches), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {}
    }

    internal fun encodeBatches(batches: List<PendingBatch>): String {
        val arr = JSONArray()
        batches.forEach { batch ->
            val entries = JSONArray()
            batch.entries.forEach { entry ->
                val staged = JSONArray()
                entry.staged.forEach { sf ->
                    staged.put(JSONObject().apply {
                        put("orig", sf.originalPath)
                        put("name", sf.stagedName)
                        put("saf", sf.safCopy)
                    })
                }
                entries.put(JSONObject().apply {
                    put("item", downloadItemToJson(entry.item))
                    put("deleteFiles", entry.deleteFiles)
                    put("staged", staged)
                })
            }
            arr.put(JSONObject().apply {
                put("id", batch.id)
                put("deadline", batch.deadlineMs)
                put("entries", entries)
            })
        }
        return arr.toString()
    }

    internal fun decodeBatches(raw: String): List<PendingBatch> {
        if (raw.isBlank()) return emptyList()
        val arr = try { JSONArray(raw) } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<PendingBatch>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            val entries = mutableListOf<PendingEntry>()
            val entriesArr = obj.optJSONArray("entries") ?: JSONArray()
            for (j in 0 until entriesArr.length()) {
                val e = entriesArr.optJSONObject(j) ?: continue
                val item = e.optJSONObject("item")?.let { downloadItemFromJson(it) } ?: continue
                val staged = mutableListOf<StagedFile>()
                val stagedArr = e.optJSONArray("staged") ?: JSONArray()
                for (k in 0 until stagedArr.length()) {
                    val s = stagedArr.optJSONObject(k) ?: continue
                    staged += StagedFile(
                        originalPath = s.optString("orig"),
                        stagedName = s.optString("name"),
                        safCopy = s.optBoolean("saf", false)
                    )
                }
                entries += PendingEntry(item, e.optBoolean("deleteFiles", true), staged)
            }
            out += PendingBatch(id, obj.optLong("deadline", 0L), entries)
        }
        return out
    }

    /** 字段与 LocalStore 的 DownloadItem 私有编解码逐一对齐，保证快照可无损还原。 */
    private fun downloadItemToJson(item: DownloadItem): JSONObject = JSONObject().apply {
        put("song", item.song.toJson())
        put("path", item.path)
        put("size", item.size)
        put("time", item.time)
        put("status", item.status)
        put("progress", item.progress.toDouble())
        put("error", item.error)
        put("quality", item.quality)
        put("platform", item.platform)
        put("scriptId", item.scriptId)
        put("resolveFailure", item.resolveFailure)
        put("taskId", item.taskId)
    }

    private fun downloadItemFromJson(obj: JSONObject): DownloadItem? {
        val path = obj.optString("path")
        if (path.isEmpty()) return null
        return DownloadItem(
            song = Song.fromJson(obj.optJSONObject("song") ?: obj),
            path = path,
            size = obj.optLong("size"),
            time = obj.optLong("time"),
            status = obj.optString("status", "done"),
            progress = obj.optDouble("progress", 1.0).toFloat(),
            error = obj.optString("error"),
            quality = obj.optString("quality"),
            platform = obj.optString("platform"),
            scriptId = obj.optString("scriptId"),
            resolveFailure = obj.optString("resolveFailure"),
            taskId = obj.optString("taskId")
        )
    }
}
