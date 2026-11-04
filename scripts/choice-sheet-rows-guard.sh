#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
sheet = (base / "SettingsSheets.kt").read_text(encoding="utf-8")
glyph_source = (base / "WhiteGlyph.kt").read_text(encoding="utf-8")
call_files = [base / name for name in ("MainActivity.kt", "LibraryPage.kt", "MePage.kt")]

def fail(message: str) -> None:
    raise SystemExit(f"[choice-sheet-rows-guard] FAIL: {message}")

def matching_end(source: str, start: int, opening: str, closing: str) -> int:
    depth = 0
    i = start
    quote = None
    triple = False
    line_comment = False
    block_comment = 0
    while i < len(source):
        if line_comment:
            if source[i] == "\n":
                line_comment = False
            i += 1
            continue
        if block_comment:
            if source.startswith("/*", i):
                block_comment += 1
                i += 2
            elif source.startswith("*/", i):
                block_comment -= 1
                i += 2
            else:
                i += 1
            continue
        if quote is not None:
            if triple:
                if source.startswith('"""', i):
                    quote = None
                    triple = False
                    i += 3
                else:
                    i += 1
            elif source[i] == "\\":
                i += 2
            elif source[i] == quote:
                quote = None
                i += 1
            else:
                i += 1
            continue
        if source.startswith("//", i):
            line_comment = True
            i += 2
        elif source.startswith("/*", i):
            block_comment = 1
            i += 2
        elif source.startswith('"""', i):
            quote = '"'
            triple = True
            i += 3
        elif source[i] in ('"', "'"):
            quote = source[i]
            i += 1
        elif source[i] == opening:
            depth += 1
            i += 1
        elif source[i] == closing:
            depth -= 1
            if depth == 0:
                return i
            i += 1
        else:
            i += 1
    fail(f"unclosed Kotlin {opening} starting near offset {start}")
    return -1

def split_top_level(source: str, delimiter: str = ",") -> list[str]:
    parts = []
    begin = 0
    stack = []
    i = 0
    quote = None
    triple = False
    line_comment = False
    block_comment = 0
    pairs = {"(": ")", "{": "}", "[": "]"}
    while i < len(source):
        if line_comment:
            if source[i] == "\n":
                line_comment = False
            i += 1
            continue
        if block_comment:
            if source.startswith("/*", i):
                block_comment += 1
                i += 2
            elif source.startswith("*/", i):
                block_comment -= 1
                i += 2
            else:
                i += 1
            continue
        if quote is not None:
            if triple:
                if source.startswith('"""', i):
                    quote = None
                    triple = False
                    i += 3
                else:
                    i += 1
            elif source[i] == "\\":
                i += 2
            elif source[i] == quote:
                quote = None
                i += 1
            else:
                i += 1
            continue
        if source.startswith("//", i):
            line_comment = True
            i += 2
        elif source.startswith("/*", i):
            block_comment = 1
            i += 2
        elif source.startswith('"""', i):
            quote = '"'
            triple = True
            i += 3
        elif source[i] in ('"', "'"):
            quote = source[i]
            i += 1
        elif source[i] in pairs:
            stack.append(pairs[source[i]])
            i += 1
        elif stack and source[i] == stack[-1]:
            stack.pop()
            i += 1
        elif source[i] == delimiter and not stack:
            parts.append(source[begin:i].strip())
            begin = i + 1
            i += 1
        else:
            i += 1
    parts.append(source[begin:].strip())
    return parts

def extract_expression(source: str, start: int) -> str:
    stack = []
    pairs = {"(": ")", "{": "}", "[": "]"}
    i = start
    quote = None
    triple = False
    line_comment = False
    block_comment = 0
    while i < len(source):
        if line_comment:
            if source[i] == "\n":
                if not stack:
                    return source[start:i].strip()
                line_comment = False
            i += 1
            continue
        if block_comment:
            if source.startswith("/*", i):
                block_comment += 1
                i += 2
            elif source.startswith("*/", i):
                block_comment -= 1
                i += 2
            else:
                i += 1
            continue
        if quote is not None:
            if triple:
                if source.startswith('"""', i):
                    quote = None
                    triple = False
                    i += 3
                else:
                    i += 1
            elif source[i] == "\\":
                i += 2
            elif source[i] == quote:
                quote = None
                i += 1
            else:
                i += 1
            continue
        if source.startswith("//", i):
            line_comment = True
            i += 2
        elif source.startswith("/*", i):
            block_comment = 1
            i += 2
        elif source.startswith('"""', i):
            quote = '"'
            triple = True
            i += 3
        elif source[i] in ('"', "'"):
            quote = source[i]
            i += 1
        elif source[i] in pairs:
            stack.append(pairs[source[i]])
            i += 1
        elif stack and source[i] == stack[-1]:
            stack.pop()
            i += 1
        elif source[i] == "\n" and not stack:
            return source[start:i].strip()
        else:
            i += 1
    return source[start:].strip()

def resolve_option_expression(source: str, expression: str, before: int, seen=None) -> str:
    expression = expression.strip()
    if re.search(r'\b(?:listOf|buildList)\s*[({]', expression):
        return expression
    name_match = re.fullmatch(r"[A-Za-z_]\w*", expression)
    if not name_match:
        return expression
    if seen is None:
        seen = set()
    name = name_match.group(0)
    if name in seen:
        fail(f"cyclic option-list reference: {name}")
    declarations = list(re.finditer(rf'\b(?:val|var)\s+{re.escape(name)}\s*=', source[:before]))
    if not declarations:
        shared_source = (base / "SmartSourcePresentation.kt").read_text(encoding="utf-8")
        if re.search(rf'\b(?:val|var)\s+{re.escape(name)}\s*=', shared_source):
            return resolve_option_expression(shared_source, name, len(shared_source), seen)
        fail(f"cannot resolve ChoiceSheet option list '{name}'")
    declaration = declarations[-1]
    rhs_start = declaration.end()
    rhs = extract_expression(source, rhs_start)
    return resolve_option_expression(source, rhs, declaration.start(), seen | {name})

def choice_calls(source: str):
    for match in re.finditer(r"\bChoiceSheet\s*\(", source):
        if re.search(r"\bfun\s*$", source[max(0, match.start() - 24):match.start()]):
            continue
        opening = source.find("(", match.start())
        closing = matching_end(source, opening, "(", ")")
        args = split_top_level(source[opening + 1:closing])
        named = next((arg.split("=", 1)[1].strip() for arg in args if re.match(r"opts\s*=", arg)), None)
        if named is None:
            if len(args) < 2:
                fail("ChoiceSheet call has no recognizable options argument")
            named = args[1]
        resolved = resolve_option_expression(source, named, match.start())
        keys = re.findall(r'"((?:\\.|[^"\\])*)"\s+to\b', resolved)
        if not keys:
            fail(f"ChoiceSheet option expression has no statically discoverable key pairs: {named[:100]}")
        line = source.count("\n", 0, match.start()) + 1
        yield line, keys

mapping_block = sheet[sheet.index("internal fun choiceOptionStyle("):sheet.index("\ninternal fun choiceOptionTint(")]
style_map = {}
for key, glyph, role in re.findall(
    r'"((?:\\.|[^"\\])*)"\s*->\s*ChoiceOptionStyle\("([^"]+)",\s*ChoiceOptionColorRole\.(\w+)\)',
    mapping_block,
):
    if key in style_map:
        fail(f"duplicate explicit mapping for key '{key}'")
    style_map[key] = (glyph, role)

supported_glyphs = set()
for match in re.finditer(r'"([A-Za-z0-9_]+)"\s*(?:,\s*"[A-Za-z0-9_]+"\s*)*->', glyph_source):
    supported_glyphs.add(match.group(1))
# Grouped WhiteGlyph branches include several quoted names on one line.
for line in glyph_source.splitlines():
    if "->" in line:
        supported_glyphs.update(re.findall(r'"([A-Za-z0-9_]+)"', line.split("->", 1)[0]))

actual_groups = []
for path in call_files:
    source = path.read_text(encoding="utf-8")
    for line, keys in choice_calls(source):
        actual_groups.append((path.name, line, keys))
        if len(keys) != len(set(keys)):
            fail(f"duplicate option key in {path.name}:{line}: {keys}")
        missing = [key for key in keys if key not in style_map]
        if missing:
            fail(f"missing explicit icon/color mapping for {path.name}:{line}: {missing}")
        glyphs = [style_map[key][0] for key in keys]
        if len(glyphs) != len(set(glyphs)):
            fail(f"repeated icon glyph in one ChoiceSheet list at {path.name}:{line}: {list(zip(keys, glyphs))}")

if not actual_groups:
    fail("no actual ChoiceSheet call sites were discovered")
missing_glyphs = sorted({glyph for glyph, _ in style_map.values()} - supported_glyphs)
if missing_glyphs:
    fail(f"explicit map references unsupported WhiteGlyph names: {missing_glyphs}")
used_keys = {key for _, _, keys in actual_groups for key in keys}
# 1.0.655: library/search platform pickers moved to the home-style DesignPlatformPopover, so the
# platform keys ('' / wy / kg / kw / tx / mg) may legitimately stay mapped without a ChoiceSheet call site.
unused_keys = set(style_map) - used_keys - {"", "wy", "kg", "kw", "tx", "mg"}
if unused_keys:
    fail(f"visual map contains keys not used by current ChoiceSheet call sites: {sorted(unused_keys)}")
if style_map.get("remove_sel") != ("menu_delete", "ERROR"):
    fail("remove_sel must keep its destructive glyph and Material error role")

choice_start = sheet.index("opts.chunked(2).forEach")
choice_end = sheet.index("if (extraActionLabel.isNotEmpty())", choice_start)
rows = sheet[choice_start:choice_end]
for token, description in (
    ("opts.chunked(2)", "two-column option ordering"),
    ("heightIn(min = 54.dp)", "minimum 54dp touch height"),
    (".clickable {", "whole-row ripple click target"),
    ("DesignLineGlyphs.Check, \"当前选项\"", "non-color selected indicator"),
    ("FontWeight.Bold", "selected label emphasis"),
):
    if token not in rows:
        fail(f"ChoiceSheet row lost {description}")
if ".background(colors.surfaceVariant)" in rows or ".background(sheetBg)" in rows:
    fail("choice option rows must not paint individual card backgrounds")
if "delay(220)" not in sheet or "onPick(k)" not in rows:
    fail("existing choice dismissal/selection timing must remain intact")
print(f"[choice-sheet-rows-guard] PASS: {len(actual_groups)} actual choice lists, {len(used_keys)} explicit keys, complete supported glyph mapping, unique per-list icons, transparent 54dp+ rows, selected indicator, and destructive error tint")
PY
