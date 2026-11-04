#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
sheet = (base / "ui/settings/WebDavLoginSheet.kt").read_text(encoding="utf-8")
overlay = (base / "ui/settings/WebDavSyncOverlay.kt").read_text(encoding="utf-8")
shell = (base / "ui/common/DesignSheet.kt").read_text(encoding="utf-8")
client = (base / "sync/WebDavSync.kt").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[webdav-login-sheet-theme-guard] FAIL: {message}")


# r658b 23：用户要求 WebDAV 抽屉与其他设置抽屉统一——强调色为设计珊瑚 SoundwaveDesignPalette.Accent，线框为 designLineColor()。
roles = (
    "colorScheme.surface", "colorScheme.onSurface", "colorScheme.onSurfaceVariant",
    "designLineColor()", "colorScheme.outlineVariant", "SoundwaveDesignPalette.Accent",
    "colorScheme.surfaceVariant", "colorScheme.error",

)
require(all(role in sheet for role in roles),
        "sheet surfaces, text, field chrome, buttons, errors and scrim must use semantic theme roles")
# ACCEPT6 dialogfrost（用户 2026-11-04 批准形态变更）等严改钉：家族面板绘制已由 frostedSheetFill
# 改为 sheetPanelGlass 磨砂玻璃，仍以 colors.surface 为罩色源随主题（浅白透/夜黑透）——钉点随之更新。
require("DesignSheetDialog(" in sheet and "sheetPanelGlass(shape, colors.surface)" in shell,
        "the sheet surface must follow the active light/dark theme")
require("focusedTextColor = colorScheme.onSurface" in sheet and
        "unfocusedTextColor = colorScheme.onSurface" in sheet and
        "focusedPlaceholderColor = colorScheme.onSurfaceVariant" in sheet and
        "unfocusedPlaceholderColor = colorScheme.onSurfaceVariant" in sheet and
        "unfocusedBorderColor = designLineColor()" in sheet and
        "focusedBorderColor = accent" in sheet and
        "cursorColor = accent" in sheet,
        "input text, placeholder, border and cursor must adapt to the active theme")
require("background(if (enabled || busy) accent else colorScheme.surfaceVariant)" in sheet and
        "colorScheme.onSurfaceVariant) else colorScheme.onSurface" in sheet,
        "primary action and disabled state must use theme-aware button colors")
# ACCEPT6（用户 2026-11-04 晚裁定，规格重钉）：状态面非错误态改走 designStatus* 设计色——
# 根主题未定义 primaryContainer、落 Material 默认薰衣草紫 #EADDFF（用户真机截图点名删除该块）。
# 新钉比旧钉更严：除新表达式外，钉死 primaryContainer/onPrimaryContainer 不得在本文件回归。
require("color = designStatusContainerColor(statusIsError)" in sheet and
        "color = designStatusContentColor(statusIsError)" in sheet and
        "colorScheme.primaryContainer" not in sheet and "colorScheme.onPrimaryContainer" not in sheet and
        "formError, color = colorScheme.error" in sheet,
        "form errors use the semantic error role; connection status cards use design status colors, never the undefined primaryContainer (Material lavender)")
import re
require(re.search(r"(?<![A-Za-z])Color\(", sheet) is None and "Color.White" not in sheet and "Color.Black" not in sheet,
        "the login sheet must not retain fixed light/dark colors")

require("PasswordVisualTransformation()" in sheet and
        "visualTransformation = PasswordVisualTransformation()" in sheet,
        "the password field must remain masked")
require("WebDavCredentialStore.save(context, url, username, password)" in overlay and
        "SecretStore.encrypt(context, password)" in client and
        "password_encrypted" in client,
        "credentials must keep using the existing secure WebDAV save/encryption path")
for secret_reference in (
    "$password", "${password}", "$username", "${username}", "$url", "${url}",
):
    require(secret_reference not in sheet and secret_reference not in overlay,
            f"login/status UI must not interpolate a credential or URL into visible copy: {secret_reference}")
require("statusIsError = true" in overlay and
        "status = result.message" in overlay and
        "Never include exception text, request headers, URL, username or password in diagnostics." in client and
        "response.body" not in client[client.find("private fun connectionFailure"):],
        "error state must only show sanitized diagnostics, never request secrets or response bodies")

require("PasswordVisualTransformation()" in sheet and "scrollContent = true" in sheet and
        ".imePadding()" not in sheet and ".imePadding()" in shell and
        ".navigationBarsPadding()" in shell and "verticalScroll(rememberScrollState())" in shell and
        ".heightIn(min = 48.dp)" in sheet and "RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)" in shell,
        "password masking, keyboard/navigation handling, scrolling, touch sizes and sheet shape must remain")
print("[webdav-login-sheet-theme-guard] PASS: theme-aware sheet and fields, preserved geometry/IME, masked password, secure credential storage, and sanitized errors")
PY
