#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
scheduler = (root / "app/src/main/java/xyz/cncnlp/soundwave/DownloadScheduler.kt").read_text(encoding="utf-8")
helper = (root / "app/src/main/java/xyz/cncnlp/soundwave/DownloadHelper.kt").read_text(encoding="utf-8")
worker = scheduler[scheduler.index("class DownloadWorker") :]
clear_protocol = (root / "app/src/main/java/xyz/cncnlp/soundwave/download/DownloadClearProtocol.kt").read_text(encoding="utf-8")
saf_protocol = (root / "app/src/main/java/xyz/cncnlp/soundwave/download/DownloadSafCommitProtocol.kt").read_text(encoding="utf-8")
clear_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/download/DownloadClearProtocolTest.kt").read_text(encoding="utf-8")
saf_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/download/DownloadSafCommitProtocolTest.kt").read_text(encoding="utf-8")
store = (root / "app/src/main/java/xyz/cncnlp/soundwave/LocalStore.kt").read_text(encoding="utf-8")
# 1.0.659: the 查看下载 page moved from SettingsHub.kt into DownloadsPage.kt.
settings = (root / "app/src/main/java/xyz/cncnlp/soundwave/DownloadsPage.kt").read_text(encoding="utf-8")
main = (root / "app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt").read_text(encoding="utf-8")
workflow = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")
build = (root / "app/build.gradle").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()

def fail(message: str) -> None:
    print(f"[download-clear-fence-guard] FAIL: {message}")
    raise SystemExit(1)

if version != "1.0.675" or 'versionName "1.0.675"' not in build or not re.search(r"versionCode\s+675\b", build):
    fail("VERSION, versionName, and versionCode must all be 1.0.675 / 675")
if "DownloadClearProtocol.clear(" not in scheduler:
    fail("download-history clearing must use the testable clear protocol")
for required in (
    "cancelAllWorkByTag(DOWNLOAD_WORK_TAG)",
    "cancelAllWorkByTag(DownloadWorker::class.java.name)",
    "cancelUniqueWork(id)",
    "DownloadHelper.cancelAll()",
    "persistGeneration = { next -> prefs.edit().putLong(GENERATION_KEY, next).commit() }",
    ".putLong(INPUT_GENERATION, generation)",
    "fun update(context: Context, item: DownloadItem, expectedGeneration: Long)",
):
    if required not in scheduler:
        fail(f"scheduler is missing required cancellation/fence contract: {required}")
for required in (
    "inputData.getLong(DownloadScheduler.INPUT_GENERATION, 0L)",
    "if (!DownloadScheduler.isCurrentGeneration(applicationContext, generation))",
    "requestStillCurrent = { !isCancelled() }",
    "expectedGeneration = generation",
    "shouldCancel = { isCancelled() }",
    "previousOutputPath = previousOutputPath",
    'if (progress >= 0.98f) "verifying" else "downloading"',
    "DownloadSafCommitProtocol.MetadataCommitException",
):
    if required not in worker:
        fail(f"Worker must preserve cancellation/commit semantics: {required}")
previous_capture = worker.find("val previousOutputPath = data.downloads()")
authorizing_write = worker.find('state("authorizing", 0.02f)')
if previous_capture < 0 or authorizing_write < 0 or previous_capture > authorizing_write:
    fail("the previous completed path must be captured before progress writes replace it with pending:")
if "DownloadScheduler.update(applicationContext, done.copy(taskId = taskId, status = \"done\"" in worker:
    fail("Worker must not asynchronously overwrite the synchronously committed final output record")
for required in (
    "DownloadScheduler.commitIfCurrent(ctx, generation, action)",
    "internal fun <T : Any> withCurrentTask(",
    "if (expectedTaskEpoch != null && !DownloadScheduler.isTaskCurrent(ctx, taskId, expectedTaskEpoch))",
    "withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { finalizeDownload() }",
    'withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { FileOutputStream(tmp, append) }',
    'withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { RandomAccessFile(tmp, "rw") }',
    "withCurrentTask(ctx, expectedGeneration, taskId, expectedTaskEpoch) { raf.write(buf, 0, n) }",
    "validateCompletedFile(tmp, total)",
    "DownloadSafCommitProtocol.commit(",
    "previousOutputPath.takeUnless",
    "ContextData(ctx, ownerContext, cancellationEpoch).addDownloadDurably(it)",
    "ContextData(ctx, ownerContext, cancellationEpoch).addDownloadDurably(item)",
    "copyAndVerify(",
    "cancellationEpoch: Long? = null",
    'if (tmp.exists() && !tmp.delete()) warnings += "内部临时文件清理失败"',
):
    if required not in helper:
        fail(f"download finalization is missing required transaction contract: {required}")
if 'root.findFile("${fileBase}.${ext}")?.delete()' in helper:
    fail("a legacy SAF target must never be deleted before the new document and record are committed")
if "fun addDownloadDurably(ctx: Context, item: DownloadItem): Boolean" not in store or ".commit()" not in store:
    fail("completed download metadata must use a checked synchronous SharedPreferences commit")
protocol_order = [
    saf_protocol.find("copyAndVerify(target, ensureActive)"),
    saf_protocol.find("metadataCommitStarted = true"),
    saf_protocol.find("persist(target, byteCount)"),
    saf_protocol.find("metadataCommitted = true"),
    saf_protocol.find("deletePreviousTarget()"),
]
if any(index < 0 for index in protocol_order) or protocol_order != sorted(protocol_order):
    fail("SAF order must be copy/verify, durable metadata, then previous-target deletion")
if "if (!metadataCommitted && !metadataCommitStarted)" not in saf_protocol:
    fail("cancel/copy failures must clean only the uncommitted new partial; uncertain metadata must retain it")
for required in (
    "successful replacement writes and commits unique target before deleting previous document",
    "cancellation during copy deletes only the new partial and preserves previous document",
    "copy failure removes partial target and never attempts to replace old document",
    "cancellation immediately before metadata commit removes new target and preserves previous document",
    "uncertain metadata persistence preserves both complete targets",
    "old document delete failure is reported while new committed document remains usable",
):
    if required not in saf_test:
        fail(f"fake-provider JVM tests are missing the SAF regression: {required}")
if '"已完成；${d.error}"' not in settings or "旧文件未能删除，已保留旧文件" not in helper:
    fail("a failure to remove the previous SAF document must be surfaced to the user")
clear_order = [clear_protocol.find("cancelAll()"), clear_protocol.find("val items = loadItems()"), clear_protocol.find("items.forEach(deleteArtifact)"), clear_protocol.find("clearRecords()")]
if any(index < 0 for index in clear_order) or clear_order != sorted(clear_order):
    fail("clear ordering must be generation fence, cancellation, snapshot, artifact deletion, then record clearing")
if "clear cancels active work then deletes completed artifacts before clearing records" not in clear_test:
    fail("JVM tests must cover active cancellation and cleanup ordering")
if "a final result that wins the race is included in the subsequent clear snapshot" not in clear_test or "a worker trying to commit during cancellation or after clear is rejected" not in clear_test:
    fail("JVM tests must cover both commit/clear race outcomes")
if "DownloadScheduler.clearDownloads(context, removeFiles)" not in main or "val snapshot = downloads" in main:
    fail("MainActivity must delegate clear work and avoid a stale download snapshot")
if "scripts/download-clear-fence-guard.sh" not in workflow:
    fail("the download-clear fence guard must remain wired into the manual quality gates")
print("[download-clear-fence-guard] PASS: clear fencing, SAF transaction/cancellation, fake-provider regressions, expanded warning visibility and 1.0.675 version are synchronized")
PY
