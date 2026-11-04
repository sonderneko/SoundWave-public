#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])


def fail(message: str) -> None:
    print(f"[codemagic-signing-https-guard] FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


# RETIRED as a deletion contract (user ruling 2026-11-05): codemagic.yaml was
# deleted because Codemagic is long unused. This guard previously validated
# that file's signing-download hygiene (HTTPS-only keystore URL validation
# block executed against synthetic URLs, no-redirect curl rules, 2xx response
# gate, six documented Secure variables, Codemagic automatic triggers off,
# in-file certificate/build verification). Those assertions guarded only the
# deleted file and are retired with it; the itemized before/after record is
# in the PUBLIC-GUARDBASE ledger. What remains enforced here:
#   1. the deletion itself: codemagic.yaml must stay absent;
#   2. the GitHub quality workflow stays manual-only (this assertion never
#      depended on Codemagic and is retained verbatim).
if (root / "codemagic.yaml").exists():
    fail("codemagic.yaml must stay deleted (Codemagic retired by owner ruling 2026-11-05)")

actions = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")
workflow_match = re.search(r"(?ms)^on:\s*\n(?P<body>.*?)(?=^permissions:)", actions)
if not workflow_match:
    fail("cannot locate GitHub Actions event configuration")
events = workflow_match.group("body")
if not re.search(r"(?m)^\s*workflow_dispatch\s*:", events):
    fail("GitHub Actions quality workflow must remain manually dispatchable")
if re.search(r"(?m)^\s*(?:push|pull_request|pull_request_target|workflow_run)\s*:", events):
    fail("GitHub Actions quality workflow must not add automatic repository-event triggers")

print(
    "[codemagic-signing-https-guard] PASS (retired 2026-11-05): codemagic.yaml deleted "
    "and stays absent; GitHub quality workflow remains manual-only"
)
PY
