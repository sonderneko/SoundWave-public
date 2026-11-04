#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
read = lambda p: (root / p).read_text(encoding="utf-8")
api = read("app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt")
controller = read("app/src/main/java/xyz/cncnlp/soundwave/DownloadController.kt")
resolver = read("app/src/main/java/xyz/cncnlp/soundwave/UrlResolver.kt")
cache = read("app/src/main/java/xyz/cncnlp/soundwave/ScriptProbeCache.kt")
dialog = read("app/src/main/java/xyz/cncnlp/soundwave/ui/common/DownloadQualityDialog.kt")
quality_row = dialog[dialog.index("private fun QualityRow("):dialog.index("private fun downloadPlatformName(")]
quality_accent = dialog[dialog.index("internal fun downloadQualityAccent("):dialog.index("private fun downloadPlatformName(")]
accent_pairs = re.findall(r'"([^\"]+)"\s*->\s*Color\(0x([0-9A-Fa-f]+)\.toInt\(\)\)', quality_accent)
expected_qualities = {"master", "atmos", "hires", "flac24bit", "flac", "320k", "128k"}
if set(dict(accent_pairs)) != expected_qualities or len({color for _, color in accent_pairs}) != len(expected_qualities):
    raise SystemExit("[download-quality-evidence-guard] FAIL: each quality tier must map to its own explicit semantic accent")
evidence = read("app/src/main/java/xyz/cncnlp/soundwave/download/AudioQualityEvidence.kt")
test = read("app/src/test/java/xyz/cncnlp/soundwave/download/AudioQualityEvidenceTest.kt")
presentation_test = read("app/src/test/java/xyz/cncnlp/soundwave/download/DownloadQualityPresentationTest.kt")
workflow = read(".github/workflows/build-apk.yml")
scheduler = read("app/src/main/java/xyz/cncnlp/soundwave/ScriptProbeScheduler.kt")
build = read("app/build.gradle")
version = read("VERSION").strip()

def fail(message: str) -> None:
    print(f"[download-quality-evidence-guard] FAIL: {message}")
    raise SystemExit(1)

checks = {
    "download uses rich HEAD/Range metadata": "ApiClient.headContentInfo(result.url)" in controller and "fun headContentInfo(url: String)" in api,
    "206 size comes only from Content-Range total": "if (status == 206) return -1L to \"\"" in evidence,
    "probe validation uses shared safe size semantics": "AudioQualityEvidence.measuredSize(resp.code, contentRange" in resolver,
    "unknown lengths are not replaced with first-packet bytes": "bytes = n.toLong().coerceAtLeast(1L)" not in resolver,
    "reports include query/matched sample, request tier and size provenance": all(token in cache for token in ("样本查询:", "匹配到:", "请求档位 ", "请求说明:", "大小来源")) and "Content-Range 总长度" in evidence,
    "legacy partial-size reports are isolated and quick coverage is explicit": "script_probe_cache_v3" in cache and "快速抽检只覆盖报告标出的本轮平台与档位" in cache and "已覆盖各脚本声明支持的全部平台与音质" in scheduler and "quick = true" not in scheduler,
    "response wait is distinguished from a received first byte": "firstByteReceived" in cache and "媒体响应等待" in cache,
    "dialog separates suspected downgrade options": all(token in dialog for token in ("疑似降级", "AudioQualityEvidence.suspectedDowngrade")),
    # r658f（用户 10-20：下载音质弹窗与设计抽屉统一）：选项行 = 设计抽屉行（分隔线 + 56dp + Manrope 粗体 + 右侧大小 + 珊瑚勾），降级原因单独一行。
    "quality rows use the design sheet row: divider, 56dp, size at title end, downgrade line": all(token in quality_row for token in ("trailingStatus(probe)", "compactDowngradeReason(warning)", "FrostDivider(designLineColor())", "heightIn(min = 56.dp)", "selectable(selected = selected, interactionSource = qualityInteractionSource, indication = null, role = Role.RadioButton, onClick = onClick)", "if (warningText != null)", "Text(warningText", "textAlign = TextAlign.End", "DesignLineGlyphs.Check")) and "selectableGroup()" in dialog and quality_row.index("label, color") < quality_row.index("sizeText, color") and all(token not in quality_row for token in ("取链档位 ", "格式 ", "HTTP ", "statusLine")),
    "quality accents stay defined and tested": "qualityOptionsUseDistinctSemanticAccents" in presentation_test,
    "selector explanations are removed while suspected-downgrade grouping stays explicit": "仅在档位、格式或同曲对比有明显证据时标记" in dialog and "未发现有充分证据的明显降级" not in dialog and "大小取自 HEAD / Content-Range" not in dialog,
    "cancel and download are side-by-side, ordered, and use Material theme roles": all(token in dialog for token in ("horizontalArrangement = Arrangement.spacedBy(10.dp)", "MaterialTheme.colorScheme.surface", "designLineColor()", "MaterialTheme.colorScheme.onSurface", "SoundwaveDesignPalette.Accent", "MaterialTheme.colorScheme.surfaceVariant", "DownloadConfirmInk", "MaterialTheme.colorScheme.onSurfaceVariant", "Role.Button", "onClick = dismiss", "rememberDesignSheetDismiss(onDismiss)", "onConfirm(selectedQuality)")) and dialog.index('Text("取消"') < dialog.index('"下载"'),
    "option selection dispatches the original requested tier": "selectRequestedQuality(quality, onQualityChange)" in dialog and "onConfirm(selectedQuality)" in dialog,
    "script diagnostics retain detailed request, format, HTTP and size evidence": all(token in cache for token in ("sampleQueryName", "append(a.quality)", "请求档位 ", "实测总大小", "疑似格式", "HTTP ", "大小来源 ", "请求说明:", "格式依据:", "a.requestDescription")),
    "JVM tests cover presentation states, stable size rows, unique accents, selection and evidence": all(token in presentation_test for token in ("successfulProbeShowsConfirmedWholeFileSize", "successfulProbeWithoutWholeFileLengthShowsUnknownSize", "failureReplacesSizeWithShortOutcome", "sizeStatusRemainsSingleLineBeforeAndAfterProbeCompletes", "downgradeReasonIsConciseAndBoundedToOneLine", "selectingOptionDispatchesTheOriginalRequestedTier", "qualityOptionsUseDistinctSemanticAccents")) and all(token in test for token in ("report.contains(\"HTTP 206\")", "report.contains(\"格式依据: URL 扩展名 .flac\")", "206", "smallFileByItselfDoesNotTriggerDowngrade", "crossTierSizeWarningRequiresSameSongEvidenceAndLargeDifference")),
    "VERSION, Gradle versionName and versionCode remain synchronized": re.fullmatch(r"\d+\.\d+\.(\d+)", version) is not None and f'versionName "{version}"' in build and re.search(rf"versionCode\s+{version.rsplit('.', 1)[1]}\b", build) is not None,
    "new guard runs in manual quality workflow": "scripts/download-quality-evidence-guard.sh" in workflow,
}
for name, ok in checks.items():
    if not ok:
        fail(name)

print("[download-quality-evidence-guard] PASS: compact quality-card layout with stable downgrade line, simplified selector copy, accessible side-by-side actions, request-tier selection, and conservative downgrade grouping")
PY
