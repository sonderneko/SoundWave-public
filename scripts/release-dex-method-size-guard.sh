#!/usr/bin/env bash
# r673 release-crash guard（1.0.672 在 vivo V2453A / Android 16 打开播放器即闪退）：
#   java.lang.VerifyError: Verifier rejected class xyz.cncnlp.soundwave.A6 … A6.P1(…) failed to verify:
#   [0x2C12] copy-cat2 v6<-v39 type=Long (Low Half)/PositiveByteConstant
# retrace：A6.P1 = FullPlayerKt.FullPlayer —— R8 把整个 @Composable 函数体合成了一个
# 18,563 code units / 331 寄存器的 dex 方法（唯一超过 256 寄存器的 app 方法），ART 校验器拒绝了整个类。
# 修复：ui/common/IsolatedSection.kt 把大段 UI 拆进独立 composable lambda（FullPlayer、LibraryPage）。
#
# 本门禁两部分：
#   1. 源码：拆分宿主必须保持非 inline，FullPlayer / LibraryPage 必须继续使用它们。
#   2. 发布 APK（传入路径或自动找 app/build/outputs/apk/release/*.apk）：用 build-tools 的 dexdump 统计每个方法，
#      任何 xyz.cncnlp.soundwave 方法 registers ≥ MAX_REGS 或 insns ≥ MAX_CU 即失败（并打印 Top 列表）。
#      没有 APK 时只跑源码部分（本地 / CI 早期步骤）；REQUIRE_APK=1 时缺 APK 直接失败。
#
# 阈值说明：dex 指令的 8 位寄存器操作数只能寻址 v0–v255，方法寄存器 ≥ 256 时 D8 需要大量 move/16 搬运，
# 1.0.672 的崩溃正落在这种搬运代码上。r673 修复后最大值：寄存器 239（PlaylistFavorites）、
# 体积 9,736 cu（PlaylistCatalogPayloads 解析函数）；FullPlayer 7,275 cu / 218 寄存器，LibraryPage 9,371 cu / 215 寄存器。
set -euo pipefail
cd "$(dirname "$0")/.."

MAX_REGS="${MAX_REGS:-256}"
MAX_CU="${MAX_CU:-12000}"
fail=0

# ---------- 1. 源码 ----------
SPLIT=app/src/main/java/xyz/cncnlp/soundwave/ui/common/IsolatedSection.kt
if [ ! -f "$SPLIT" ]; then echo "FAIL: $SPLIT missing"; exit 1; fi
if grep -nE '^\s*(internal |private |public )?inline\s+fun' "$SPLIT"; then
  echo "FAIL: IsolatedSection hosts must NOT be inline (inline puts the content back into the caller's dex method)"; fail=1
fi
for fn in 'fun IsolatedSection(' 'fun BoxScope.IsolatedBoxSection(' 'fun ColumnScope.IsolatedColumnSection('; do
  grep -qF "$fn" "$SPLIT" || { echo "FAIL: $SPLIT lost '$fn'"; fail=1; }
done
fp_uses=$(grep -cE 'Isolated(Box|Column)?Section \{' app/src/main/java/xyz/cncnlp/soundwave/FullPlayer.kt || true)
lp_uses=$(grep -cE 'Isolated(Box|Column)?Section \{' app/src/main/java/xyz/cncnlp/soundwave/LibraryPage.kt || true)
[ "$fp_uses" -ge 6 ] || { echo "FAIL: FullPlayer.kt must keep its render tree split into Isolated*Section lambdas (found $fp_uses, need ≥6)"; fail=1; }
[ "$lp_uses" -ge 2 ] || { echo "FAIL: LibraryPage.kt must keep detail/library trees in IsolatedSection (found $lp_uses, need ≥2)"; fail=1; }

# ---------- 2. 发布 APK ----------
APK="${1:-}"
if [ -z "$APK" ]; then
  for c in app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk; do
    [ -s "$c" ] && { APK="$c"; break; }
  done
fi
if [ -z "$APK" ] || [ ! -s "$APK" ]; then
  if [ "${REQUIRE_APK:-0}" = "1" ]; then echo "FAIL: release APK not found"; exit 1; fi
  echo "release-dex-method-size-guard: source checks $( [ $fail -eq 0 ] && echo OK || echo FAILED ); no release APK, dex check skipped"
  exit $fail
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
DEXDUMP="$(find "$SDK/build-tools" -name dexdump -type f 2>/dev/null | sort -V | tail -1 || true)"
if [ -z "$DEXDUMP" ]; then echo "FAIL: dexdump not found under \$ANDROID_HOME/build-tools"; exit 1; fi

python3 - "$APK" "$DEXDUMP" "$MAX_REGS" "$MAX_CU" <<'PY' || fail=1
import os, re, subprocess, sys, tempfile, zipfile
apk, dexdump, max_regs, max_cu = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
rows = []
with tempfile.TemporaryDirectory() as d:
    z = zipfile.ZipFile(apk)
    for n in z.namelist():
        if not re.fullmatch(r'classes\d*\.dex', n):
            continue
        p = os.path.join(d, n)
        with open(p, 'wb') as f:
            f.write(z.read(n))
        out = subprocess.run([dexdump, p], capture_output=True, text=True, check=True).stdout
        cls = name = sig = None; regs = 0
        for line in out.splitlines():
            m = re.match(r"\s*Class descriptor\s*:\s*'(.*)'", line)
            if m: cls = m.group(1); continue
            m = re.match(r"\s*name\s*:\s*'(.*)'", line)
            if m: name = m.group(1); continue
            m = re.match(r"\s*type\s*:\s*'(.*)'", line)
            if m: sig = m.group(1); continue
            m = re.match(r"\s*registers\s*:\s*(\d+)", line)
            if m: regs = int(m.group(1)); continue
            m = re.match(r"\s*insns size\s*:\s*(\d+)", line)
            if m: rows.append((int(m.group(1)), regs, n, cls or '?', name or '?', sig or ''))
app = [r for r in rows if r[3].startswith('Lxyz/cncnlp/soundwave/')]
if not app:
    print("FAIL: no xyz.cncnlp.soundwave methods found in", apk); sys.exit(1)
bad = [r for r in app if r[1] >= max_regs or r[0] >= max_cu]
print(f"release-dex-method-size-guard: {len(rows)} methods, {len(app)} app methods; limits registers<{max_regs}, insns<{max_cu} cu")
print("  largest by insns:")
for r in sorted(app, reverse=True)[:5]:
    print(f"    {r[0]:6d} cu {r[1]:4d} regs  {r[3]}->{r[4]}")
print("  largest by registers:")
for r in sorted(app, key=lambda r: (-r[1], -r[0]))[:5]:
    print(f"    {r[1]:4d} regs {r[0]:6d} cu  {r[3]}->{r[4]}")
if bad:
    print("FAIL: method(s) over the verifier-safe size (retrace with build/outputs/mapping/release/mapping.txt, then split the composable with ui/common/IsolatedSection.kt):")
    for r in bad:
        print(f"    {r[0]:6d} cu {r[1]:4d} regs  {r[2]} {r[3]}->{r[4]}{r[5][:160]}")
    sys.exit(1)
PY

if [ $fail -ne 0 ]; then echo "release-dex-method-size-guard: FAILED"; exit 1; fi
echo "release-dex-method-size-guard: OK"
