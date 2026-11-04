#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
reader = (base / "script/BoundedScriptImportReader.kt").read_text(encoding="utf-8")
storage = (base / "ScriptStorage.kt").read_text(encoding="utf-8")
main = (base / "MainActivity.kt").read_text(encoding="utf-8")
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/BoundedScriptImportReaderTest.kt").read_text(encoding="utf-8")
storage_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/ScriptStorageImportTest.kt").read_text(encoding="utf-8")
workflow = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[script-import-bounded-read-guard] FAIL: {message}")

require("internal const val MAX_SCRIPT_IMPORT_BYTES = 8 * 1024 * 1024" in reader,
        "the local import limit must remain 8 MiB")
require("minOf(buffer.size, remaining)" in reader and "input.read() == -1" in reader,
        "the stream reader must cap each read and use one sentinel byte for overflow detection")
require("readBytes(" not in reader, "the bounded helper must not use unbounded readBytes")
require("bytes.size <= MAX_SCRIPT_IMPORT_BYTES" in storage,
        "byte-array imports must enforce the same shared size cap")

# r657：回调体抽成 importScriptUri，供文件选择器与外部 ACTION_VIEW 打开 .js 共用。
start = main.find("val importScriptUri: (Uri?) -> Unit = importScriptUri@{")
end = main.find("var searchMode", start)
require(start >= 0 and end > start, "the local-script picker callback must remain discoverable")
callback = main[start:end]
require("rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), importScriptUri)" in callback,
        "the existing document picker and its URI grant semantics must remain unchanged")
require("scope.launch" in callback and "withContext(Dispatchers.IO)" in callback,
        "script file I/O and parsing must run off the UI thread")
require("BoundedScriptImportReader.read(it)" in callback and "readBytes(" not in callback,
        "the activity result callback must use bounded reading instead of unbounded allocation")
read_at = callback.find("BoundedScriptImportReader.read(it)")
import_at = callback.find("ScriptStorage.importFromBytes")
state_at = callback.find("scripts = pendingScripts.toList()")
invalidate_at = callback.find("scriptRuntime.invalidate()")
require(0 <= read_at < import_at < state_at < invalidate_at,
        "read and import must complete before main-thread state/runtime updates")
for feedback in ("已取消导入", "正在读取脚本", "脚本文件超过 8MB", "读取文件失败，请重试", "已导入"):
    require(feedback in callback, f"visible import feedback is missing: {feedback}")
require("ScriptImportTooLargeException" in callback and "ScriptImportReadException" in callback,
        "oversize and read failures must be distinguishable")
require("scripts.addAll(out)" in storage and "saveScripts(prefs, scripts)" in storage,
        "file imports must commit staged scripts only after successful decoding/validation")
for test_name in (
    "readsFileExactlyAtEightMegabyteLimit",
    "rejectsOversizedFileAfterReadingOnlyOneBytePastLimit",
    "propagatesStreamReadFailure",
):
    require(test_name in test, f"a bounded-reader regression is missing: {test_name}")
require("failedFileImportLeavesExistingScriptsAndPreferencesUntouched" in storage_test,
        "failed script imports must preserve the existing local state")
require("scripts/script-import-bounded-read-guard.sh" in workflow,
        "the bounded-import static guard must run in portable quality gates")
print("[script-import-bounded-read-guard] PASS: IO-only bounded file reading, staged imports, visible feedback, and regression coverage")
PY
