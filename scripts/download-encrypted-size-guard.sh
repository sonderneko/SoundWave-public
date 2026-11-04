#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
helper = (root / "app/src/main/java/xyz/cncnlp/soundwave/DownloadHelper.kt").read_text(encoding="utf-8")
scheduler = (root / "app/src/main/java/xyz/cncnlp/soundwave/DownloadScheduler.kt").read_text(encoding="utf-8")
policy = (root / "app/src/main/java/xyz/cncnlp/soundwave/download/DownloadDecryptLimitPolicy.kt").read_text(encoding="utf-8")
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/download/DownloadDecryptLimitPolicyTest.kt").read_text(encoding="utf-8")
workflow = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")

def fail(message: str) -> None:
    print(f"[download-encrypted-size-guard] FAIL: {message}")
    raise SystemExit(1)

if "sizeBytes > MAX_DECRYPT_BYTES" not in policy:
    fail("the rejection boundary must be strictly greater than the 200 MiB cap")
if '"ncm", limit)' not in test or '"ncm", limit + 1)' not in test:
    fail("the JVM test must cover the exact limit and the first byte above it")
check = "if (DownloadDecryptLimitPolicy.exceedsLimit(ext, downloadedBytes))"
start = helper.find(check)
end = helper.find("if (downloadedBytes in 1..DownloadDecryptLimitPolicy.MAX_DECRYPT_BYTES)", start)
if start < 0 or end < 0:
    fail("the size limit must be checked before entering the decrypt/rename path")
rejection = helper[start:end]
if "tmp.delete()" not in rejection or "throw EncryptedMediaTooLargeException()" not in rejection:
    fail("over-limit ciphertext must be discarded from staging and rejected")
catch = scheduler.find("catch (e: EncryptedMediaTooLargeException)")
next_catch = scheduler.find("catch (e: CancellationException)", catch)
if catch < 0 or next_catch < 0:
    fail("the Worker must handle size-limit failures separately from retryable errors")
terminal = scheduler[catch:next_catch]
if 'state("failed"' not in terminal or "Result.failure()" not in terminal:
    fail("size-limit failures must be surfaced as terminal failed tasks")
if "scripts/download-encrypted-size-guard.sh" not in workflow:
    fail("the new guard must remain wired into the manual CI quality gates")

print("[download-encrypted-size-guard] PASS: encrypted files above the cap cannot be finalized as completed downloads")
PY
