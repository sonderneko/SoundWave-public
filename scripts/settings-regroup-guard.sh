#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
me = (base / "MePage.kt").read_text()
kit = (base / "ui/settings/SettingsDetailKit.kt").read_text()
sheets = (base / "ui/settings/SettingsValueSheets.kt").read_text()
smart = (base / "SmartSourcePage.kt").read_text()
glyphs = (base / "ui/settings/SettingsLineGlyphs.kt").read_text()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[settings-regroup-guard] FAIL: {message}")


# ACCEPT5 regroup（用户 2026-11-04 14:18 定案）：主页恰六组、顺序固定、无「常用」组、无旧组名。
groups = ["播放与音质", "音源与脚本", "下载", "外观", "同步与数据", "系统与实验"]
positions = [me.index(f'EditorialGroup("{g}"') for g in groups]
require(positions == sorted(positions), "the six settings groups must appear in the approved order")
require(me.count("EditorialGroup(") >= 6 and 'EditorialGroup("常用"' not in me,
        "no 常用 group may exist on the settings home")
for retired in ("过渡效果", "外观与歌词", "音源与搜索", "数据与同步", "服务与支持"):
    require(f'EditorialGroup("{retired}"' not in me, f"retired group returned: {retired}")

# R2 折叠：智能过渡伪行不得在主页出现（其说明面在过渡弹层内）；R3：H3 已转默认毕业，实验行与开关在整个设置面退役。
require('Row("智能过渡"' not in me and 'ValueRow("智能过渡"' not in me and 'EditorialValueRow("智能过渡"' not in me
        and 'ToggleRow("智能过渡"' not in me and 'NavRow("智能过渡"' not in me,
        "the folded 智能过渡 pseudo-row must not reappear on the settings home")
require("脚本请求系统直连" not in me and "script_placeholder_direct_experiment" not in me,
        "the graduated H3 experiment row and setting must not exist anywhere in settings")

# 下载独立成组：下载设置/查看下载两行在「下载」组段内；缓存管理留在同步与数据组段内。
download_group = me[me.index('EditorialGroup("下载"'):me.index('EditorialGroup("外观"')]
require('NavRow("下载设置"' in download_group and 'NavRow("查看下载"' in download_group,
        "download rows must live in the download group")
data_group = me[me.index('EditorialGroup("同步与数据"'):me.index('EditorialGroup("系统与实验"')]
require('NavRow("缓存管理"' in data_group, "cache management must stay in the data group")
require('NavRow("备份与恢复"' in data_group and data_group.index('NavRow("备份与恢复"') < data_group.index('NavRow("缓存管理"'),
        "backup must head the data group")

# V3：逐行配色表存在且按 spec 取色；旧 role 制单色函数不得回归。
require("internal fun settingGlyphColor(spec: SettingGlyphSpec): Color" in me,
        "the per-spec glyph color table must exist")
require("SettingsGlyphRole" not in me and "settingsGlyphColor(" not in me,
        "the retired monochrome role coloring must not return")
for key in ("frosted_bg", "frosted_tint"):
    require(f'"{key}"' in glyphs, f"new glyph path missing from the line set: {key}")
require('"script_direct"' not in glyphs, "the retired H3 glyph must not return to the line set")

# V1：标准选择器与智能换源页不得再有序号呈现。
require('"%02d"' not in kit, "the picker sheet must not render serial numbers")
require('"%02d".format(index + 1)' not in smart, "the smart-source page must not render stage serial numbers")

# V4 缺三：音质上下文函数存在且被选项引用。
require("internal fun qualityContextLine(key: String): String" in sheets and "qualityContextLine(k)" in sheets,
        "quality options must carry bitrate/size context")

print("[settings-regroup-guard] PASS: six groups in order, R2 fold + R3 graduated (row retired), download group split, V1 serials removed, V3 per-row color table, quality context lines")
PY
