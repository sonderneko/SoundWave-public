#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
# r656 item 11/14：缓存管理 / 取链报告 / 崩溃诊断迁入 SettingsMaintenancePages.kt 并按设计稿重做。
source = (root / "app/src/main/java/xyz/cncnlp/soundwave/SettingsMaintenancePages.kt").read_text(encoding="utf-8")
theme_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/MaintenancePageThemeColorsTest.kt").read_text(encoding="utf-8")
crash_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/CrashGuardTest.kt").read_text(encoding="utf-8")
cache_clear_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/MediaCacheClearTest.kt").read_text(encoding="utf-8")
cache_lease_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/MediaCacheLeaseTest.kt").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[maintenance-pages-theme-guard] FAIL: {message}")


def section(start_marker: str, end_marker: str, label: str) -> str:
    start = source.find(start_marker)
    end = source.find(end_marker, start + len(start_marker)) if start >= 0 else -1
    require(start >= 0 and end > start, f"{label} boundary missing")
    return source[start:end]


colors = section("internal data class MaintenancePageThemeColors(", "@Composable\nfun CacheManagePage(", "semantic color mapping")
cache = section("@Composable\nfun CacheManagePage(", "/** 启动/崩溃诊断", "cache-management page")
crash = source[source.index("@Composable\nfun StartupCrashPage()"):]

checks = {
    "maintenance text, neutral actions, and destructive actions map to Material semantic roles": all(
        token in colors
        for token in (
            "onSurface = colorScheme.onSurface",
            "onSurfaceVariant = colorScheme.onSurfaceVariant",
            "primary = colorScheme.primary",
            "error = colorScheme.error",
        )
    ) and all(
        token in cache and token in crash
        for token in (
            "val themeColors = maintenancePageThemeColors(MaterialTheme.colorScheme)",
            "val onBg = themeColors.onSurface",
            "themeColors.onSurfaceVariant.copy(alpha =",
        )
    ) and "val primary = themeColors.primary" in cache,
    # 注：crash 页的 primary 令牌原由「立即重传」主按钮消费；上传机制删除后（用户 2026-11-04
    # 裁定，删除合同）该页不再有 primary 动作，故 primary 只在 cache 页与 colors 映射本体钉。
    "cache clear is theme-error colored, confirmed in the unified design sheet, and keeps its clear outcomes": all(
        token in cache
        for token in (
            'SettingsDetailRow("清空播放缓存"',
            'Text("清空", color = themeColors.error,',
            "showClearConfirm = true",
            "SettingsConfirmSheet(",
            "showClearConfirm = false; clearNow()",
            "val result = CacheManager.clearAll(ctx)",
            "onClear(); tick++",
            "result.skippedActiveFiles",
            "result.skippedActiveMediaResources",
            '"已清空播放、歌词和临时缓存（保留封面与头像）"',
            '"缓存清理失败，请稍后重试"',
        )
    ) and "AlertDialog" not in cache,
    "crash-record clear asks in the design sheet then clears both local records": all(
        token in crash
        for token in (
            'SettingsActionButton("清除"',
            "kind = SettingsButtonKind.Danger",
            "SettingsConfirmSheet(",
            "CrashGuard.clear(ctx); CrashGuard.clearPending(ctx); tick++",
            'AppNotice.toast("已清除本机崩溃记录", AppNotice.Kind.SUCCESS)',
        )
    ) and "AlertDialog" not in crash,
    "copy, source data, cache statistics, and diagnostic output remain unchanged": all(
        token in cache + crash
        for token in (
            'trailing = "复制诊断"',
            'SettingsActionButton("复制"',
            "PlaybackDiagnostics.diagnosticsText()",
            "PlaybackDiagnostics.recent(50)",
            "PlaybackDiagnostics.persistedRecent(50)",
            "CacheManager.usageParts(ctx)",
            "CacheManager.maxBytes(ctx)",

            "CrashGuard.last(ctx)",
            "报告包含时间、版本、机型/API、线程和异常堆栈",
        )
    ) and all(
        # 删除合同：手动发送/自动上传 UI 与状态文案不得回潮。
        token not in crash
        for token in ("CrashGuard.submitNow", "crash_upload_status", "立即重传", "自动上传崩溃报告")
    ) and "最近缓存歌曲" not in cache and "CacheManager.audioIndexSummary" not in cache
      and "titleColor = SoundwaveDesignPalette.Accent" in cache
      and "PlaybackLinkReport(tick = tick, primary = SoundwaveDesignPalette.Accent)" in cache
      and ("查看最近 ${shown.size} 条" in cache or "查看全部 ${shown.size} 条" in cache),
    "these two maintenance pages no longer contain fixed black/white content colors": all(
        literal not in cache + crash for literal in ("Color.Black", "Color.White", "Color(0x")
    ),
    "JUnit covers both theme roles and independent crash/pending cleanup while cache tests retain playback protections": all(
        token in theme_test
        for token in (
            "maintenanceControlsFollowMaterialRolesInLightAndNightThemes",
            "scheme.onSurfaceVariant, colors.onSurfaceVariant",
            "scheme.error, colors.error",
            "assertNotEquals(light.error, night.error)",
        )
    ) and all(
        token in crash_test
        for token in (
            "clearingLocalCrashRecordAndPendingCopyRemainsExplicitAndIndependent",
            "CrashGuard.clear(context)",
            "CrashGuard.clearPending(context)",
            '"queued diagnostic"',
            "uploadSurfaceStaysDeleted",
        )
    ) and all(
        token in cache_clear_test
        for token in (
            "cold start clear opens persistent cache and preserves covers",
            "cache initialization failure propagates before reporting all caches cleared",
        )
    ) and "an attached playback file must survive manual clear" in cache_lease_test,
}

for label, ok in checks.items():
    print(f"[maintenance-pages-theme-guard] {'PASS' if ok else 'FAIL'}: {label}")
if not all(checks.values()):
    raise SystemExit(1)
print("[maintenance-pages-theme-guard] PASS: semantic theme roles, immediate destructive flows, source-of-truth data, and cleanup safeguards")
PY
