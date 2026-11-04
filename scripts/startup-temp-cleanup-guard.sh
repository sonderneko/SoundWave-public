#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
cleaner = (root / "app/src/main/java/xyz/cncnlp/soundwave/startup/StartupTempFileCleaner.kt").read_text(encoding="utf-8")
application = (root / "app/src/main/java/xyz/cncnlp/soundwave/CrashGuard.kt").read_text(encoding="utf-8")
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/StartupTempFileCleanerTest.kt").read_text(encoding="utf-8")
workflow = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[startup-temp-cleanup-guard] FAIL: {message}")

require(r"^dec_(?:[A-Za-z0-9._-]{1,60}|[a-f0-9]{64})\.(?:mflac|mgg|ncm|tkm)\.(?:part|plain\.part)$" in cleaner,
        "media-decrypt cleanup must match legacy sanitized and full SHA-256 keys, supported extensions, and staging suffixes")
require(r"^sw-[A-Za-z0-9_-]{1,32}-[A-Za-z0-9_-]+\.(?:mflac|mgg|ncm|tkm|flac|m4a|ape|ogg|mp3)\.plain\.part$" in cleaner,
        "download cleanup must match only File.createTempFile staging names and extensions emitted by DownloadHelper")
require("directory.listFiles()" in cleaner and "entry.isFile && pattern.matches(entry.name) && MediaCacheLeaseManager.deleteIfUnleased(entry)" in cleaner,
        "cleanup must inspect only immediate regular files with a full-name match and respect active media leases")
require("walkTopDown" not in cleaner and "deleteRecursively" not in cleaner and "listFiles()?.forEach" not in cleaner,
        "cleanup must not recurse or broadly delete directory contents")
startup = application[application.index("class SoundWaveApplication") :]
on_create = startup[startup.index("override fun onCreate()") :]
cleanup_at = on_create.find("StartupTempFileCleaner.cleanup")
crash_install_at = on_create.find("CrashGuard.install")
worker_init_at = on_create.find("SmartSourceStore.init")
require(cleanup_at >= 0 and crash_install_at > cleanup_at and worker_init_at > cleanup_at,
        "cleanup must run at the start of Application.onCreate before other startup work")
for token in (
    "startup cleanup removes only abandoned decrypt staging files",
    "hashedEncryptedPart",
    "hashedPlaintextPart",
    "File.createTempFile(\"sw-task_123-\", \".mflac.plain.part\", downloadDir)",
    "File.createTempFile(\"sw-task_123-\", \".mp3.plain.part\", downloadDir)",
    'File(downloadDir, ".Song-Singer-task_123.mflac.part")',
    "assertTrue(resumablePart.isFile)",
    "assertTrue(completedAudio.isFile)",
    "assertTrue(matchingDirectory.isDirectory)",
    "assertTrue(nestedTemp.isFile)",
):
    require(token in test, f"deterministic JVM regression is missing: {token}")
require("scripts/startup-temp-cleanup-guard.sh" in workflow,
        "the startup cleanup guard must run in portable quality gates")
print("[startup-temp-cleanup-guard] PASS: narrow temp-file patterns, immediate regular-file scan, startup ordering, and preservation regressions")
PY
