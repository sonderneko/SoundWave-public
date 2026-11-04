#!/usr/bin/env bash
# 1.0.656: the default server sits behind Cloudflare, whose edge certificate rotates keys and possibly CAs
# (from ~2026-10-25). Certificate pinning must not come back; TLS relies on system CAs + HTTPS-only policy.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
src = root / "app/src/main"
offenders = []
for f in list(src.rglob("*.kt")) + list(src.rglob("*.java")):
    t = f.read_text(encoding="utf-8")
    if "CertificatePinner" in t or ".certificatePinner(" in t or '"sha256/' in t:
        offenders.append(str(f.relative_to(root)))
nsc = (src / "res/xml/network_security_config.xml").read_text(encoding="utf-8")
api = (src / "java/xyz/cncnlp/soundwave/ApiClient.kt").read_text(encoding="utf-8")
main = (src / "java/xyz/cncnlp/soundwave/MainActivity.kt").read_text(encoding="utf-8")
checks = {
    "no certificate pinning in app sources": not offenders,
    "network security config has no <pin-set>": "pin-set" not in nsc and '<certificates src="system"/>' in nsc,
    "API client never follows HTTPS->HTTP redirects": ".followSslRedirects(false)" in api,
    "default server stays HTTPS": 'const val DEFAULT_SERVER_URL = "https://' in main,
    # Account-removal wave: the derived auth/snapshot-export clients were
    # deleted with the account API; pin the single remaining base client and
    # the absence of the derived account clients.
    "API traffic uses the single unpinned base client (account clients retired)": "private val client = OkHttpClient.Builder()" in api
        and "authClient" not in api and "snapshotExportClient" not in api,
    "nonexistent */detail server paths are not probed": all(p not in api for p in (
        '"/music/album/detail?', '"/music/rank/detail?', '"/music/playlist/detail?')),
}
for k, ok in checks.items():
    print(f"[default-server-no-pinning-guard] {'PASS' if ok else 'FAIL'}: {k}")
if offenders:
    print("  pinning found in:", ", ".join(offenders))
if not all(checks.values()):
    raise SystemExit(1)
PY
