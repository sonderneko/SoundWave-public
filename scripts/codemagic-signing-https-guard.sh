#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import os
import re
import subprocess
import sys
import tempfile
import textwrap

root = Path(sys.argv[1])
config = (root / "codemagic.yaml").read_text(encoding="utf-8")
actions = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")

def fail(message: str) -> None:
    print(f"[codemagic-signing-https-guard] FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)

# Verify the documentation describes the actual storage arrangement and all six
# Secure variables without embedding any configured values.
if "Secure 勾选 6 个变量" not in config:
    fail("Codemagic signing group documentation must identify six Secure variables")
if "/signing/" not in config or "Basic Auth" not in config:
    fail("documentation must state that the server-side JKS is under Basic Auth")
if "服务器不再存放任何密钥材料" in config:
    fail("stale comment incorrectly claims the server stores no key material")
required_variables = (
    "ANDROID_RELEASE_KEYSTORE_URL",
    "ANDROID_RELEASE_KEYSTORE_AUTH",
    "ANDROID_RELEASE_STORE_PASSWORD",
    "ANDROID_RELEASE_KEY_ALIAS",
    "ANDROID_RELEASE_KEY_PASSWORD",
    "ANDROID_RELEASE_CERT_SHA256",
)
for name in required_variables:
    if name not in config:
        fail(f"missing documented or consumed environment variable name: {name}")

# Execute the exact URL-validation block from the build YAML in an isolated
# shell. Only synthetic URLs are supplied; no credentials or network requests.
validation_start = config.find(
    '          KEYSTORE_URL="${ANDROID_RELEASE_KEYSTORE_URL:?missing ANDROID_RELEASE_KEYSTORE_URL}"'
)
auth_check = config.find(
    '          test -n "${ANDROID_RELEASE_KEYSTORE_AUTH:?missing ANDROID_RELEASE_KEYSTORE_AUTH}"',
    validation_start,
)
if validation_start < 0 or auth_check < 0:
    fail("cannot locate the HTTPS validation block before the Authorization request")
validation_block = textwrap.dedent(config[validation_start:auth_check])
if "https://*)" not in validation_block or "[^/?#[:space:]]+" not in validation_block:
    fail("URL validation must require the lowercase HTTPS scheme and a nonempty authority")
if validation_block.find("case \"$KEYSTORE_URL\"") < 0 or "[[ ! \"$KEYSTORE_URL\" =~" not in validation_block:
    fail("URL validation must use shell case and a strict whitespace-rejecting regexp")

accepted_urls = (
    "https://signing.example.invalid/signing/release.jks",
    "https://signing.example.invalid/release.jks?token=fake&expires=1",
)
rejected_urls = (
    "",
    "http://signing.example.invalid/release.jks",
    "//signing.example.invalid/release.jks",
    "HTTPS://signing.example.invalid/release.jks",
    "HtTpS://signing.example.invalid/release.jks",
    " https://signing.example.invalid/release.jks",
    "https://signing.example.invalid/release.jks ",
    "https://signing.example.invalid/re lease.jks",
    "https://signing.example.invalid/re\tlease.jks",
    "https:///release.jks",
    "https://signing.example.invalid/release.jks\n",
)
with tempfile.TemporaryDirectory(prefix="codemagic-url-guard-") as temp_dir:
    harness = Path(temp_dir) / "validate-url.sh"
    harness.write_text("set -euo pipefail\n" + validation_block + "\n", encoding="utf-8")
    for url in accepted_urls:
        env = os.environ.copy()
        env["ANDROID_RELEASE_KEYSTORE_URL"] = url
        result = subprocess.run(
            ["bash", str(harness)], env=env, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
        )
        if result.returncode != 0:
            fail("a valid synthetic HTTPS URL was rejected")
    for url in rejected_urls:
        env = os.environ.copy()
        env["ANDROID_RELEASE_KEYSTORE_URL"] = url
        result = subprocess.run(
            ["bash", str(harness)], env=env, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
        )
        if result.returncode == 0:
            fail("a noncanonical or non-HTTPS synthetic URL was accepted")

# The authenticated curl must enforce HTTPS, explicitly disable redirect
# following, and gate successful downloads on an HTTP 2xx status.
curl_start = config.find('          HTTP_STATUS="$(curl', auth_check)
curl_end_token = ' "$KEYSTORE_URL")"'
curl_end = config.find(curl_end_token, curl_start)
if curl_start < 0 or curl_end < 0:
    fail("cannot locate the authenticated curl command")
curl_command = config[curl_start:curl_end + len(curl_end_token)]
if "--no-location" not in curl_command:
    fail("curl must explicitly disable all redirect following")
if "--proto '=https'" not in curl_command:
    fail("curl must independently restrict the initial transfer protocol to HTTPS")
if "--write-out '%{http_code}'" not in curl_command:
    fail("curl must capture the HTTP status for explicit 2xx validation")
if '-H "Authorization: Basic ${ANDROID_RELEASE_KEYSTORE_AUTH}"' not in curl_command:
    fail("the existing Basic Auth header flow must be preserved")
if '"$KEYSTORE_URL"' not in curl_command:
    fail("curl must use the validated URL variable")
if re.search(r"(?<!\S)(?:-L|--location|--location-trusted|--follow)(?:\s|=|$)", curl_command):
    fail("curl must not enable redirect following or trusted cross-host auth")
if "--insecure" in curl_command or re.search(r"(?<!\S)-k(?:\s|$)", curl_command):
    fail("curl must retain TLS certificate verification")
status_gate = config.find('          case "$HTTP_STATUS" in', curl_end)
if status_gate < 0 or "2??)" not in config[status_gate:] or "redirects are not followed" not in config[status_gate:]:
    fail("non-2xx responses, including redirects, must be rejected and the partial file removed")
if not (validation_start < auth_check < curl_start):
    fail("URL validation must occur before any request carrying Authorization")

# Keep both quality-gate integrations manual-only; adding this guard cannot
# turn a source push into a GitHub Actions or Codemagic build.
workflow_match = re.search(r"(?ms)^on:\s*\n(?P<body>.*?)(?=^permissions:)", actions)
if not workflow_match:
    fail("cannot locate GitHub Actions event configuration")
events = workflow_match.group("body")
if not re.search(r"(?m)^\s*workflow_dispatch\s*:", events):
    fail("GitHub Actions quality workflow must remain manually dispatchable")
if re.search(r"(?m)^\s*(?:push|pull_request|pull_request_target|workflow_run)\s*:", events):
    fail("GitHub Actions quality workflow must not add automatic repository-event triggers")
if "scripts/codemagic-signing-https-guard.sh" not in actions:
    fail("the signing URL guard must remain wired into the manual quality workflow")
triggering = re.search(r"(?ms)^triggering:\s*\n(?P<body>.*?)(?=^\S|\Z)", config)
if triggering and re.search(
    r"(?m)^\s*-\s*(?:push|pull_request|pull_request_labeled|tag)\s*$",
    triggering.group("body"),
):
    fail("Codemagic automatic build events must remain disabled for this manual-only workflow")

# Preserve the pre-existing build and certificate fingerprint verification.
if "test \"$ACTUAL\" = \"$WANT\"" not in config:
    fail("certificate fingerprint verification must remain in place")
if "./gradlew :app:assembleRelease --no-daemon --stacktrace" not in config:
    fail("the release build command must remain unchanged")

print(
    "[codemagic-signing-https-guard] PASS: HTTPS-only validation, no redirect following, "
    "2xx response gate, six Secure variables, and manual-only CI triggers"
)
PY
