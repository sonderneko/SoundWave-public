#!/usr/bin/env python3
"""Compile the production blocking stage and JUnit tests using cached dependencies only."""
from pathlib import Path
import argparse
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--output', type=Path)
p.add_argument('--stage', type=Path, help='Optional mutated stage for negative-control testing')
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
w = a.output or Path(tempfile.mkdtemp(prefix='soundwave-blocking-isolation-'))
w.mkdir(parents=True, exist_ok=True)
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
modules = {
    'org.jetbrains.kotlin': ['kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-reflect', 'kotlin-script-runtime'],
    'org.jetbrains.intellij.deps': ['trove4j'], 'org.jetbrains': ['annotations'],
    'org.jetbrains.kotlinx': ['kotlinx-coroutines-core-jvm'],
    'com.squareup.okhttp3': ['okhttp'], 'com.squareup.okio': ['okio-jvm'],
    'junit': ['junit'], 'org.hamcrest': ['hamcrest-core'],
}
jars = []
for group, names in modules.items():
    for name in names:
        candidates = sorted((cache / group / name).glob('*/*/*.jar'))
        if not candidates:
            raise SystemExit(f'Missing cached dependency: {group}:{name}')
        jars.append(candidates[-1])
cp = ':'.join(map(str, jars))
# The stage only needs the real purpose enum, not Android-dependent ticket data.
text = (base / 'ResolutionModels.kt').read_text()
start = text.index('enum class Purpose')
end = text.index('}', start) + 1
(w / 'Ticket.kt').write_text('package xyz.cncnlp.soundwave\ndata class ResolveTicket(val unused: Int = 0) {\n' + text[start:end] + '\n}\n')
sources = [a.stage or base / 'ResolveBudget.kt', w / 'Ticket.kt',
           root / 'app/src/test/java/xyz/cncnlp/soundwave/ResolveBlockingIsolationTest.kt']
r = subprocess.run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-nowarn', '-no-stdlib', '-no-reflect', '-classpath', cp,
                    '-d', str(w / 'classes'), *map(str, sources)])
if r.returncode:
    raise SystemExit(r.returncode)
raise SystemExit(subprocess.run(['java', '-cp', str(w / 'classes') + ':' + cp,
                                'org.junit.runner.JUnitCore',
                                'xyz.cncnlp.soundwave.ResolveBlockingIsolationTest'], timeout=60).returncode)
