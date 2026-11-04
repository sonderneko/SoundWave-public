#!/usr/bin/env python3
"""Host consumer regression using prepared /tmp/r657 JVM dependencies; no SDK/Gradle."""
from pathlib import Path
import shutil, subprocess, sys
root=Path(__file__).resolve().parents[1]
for name in ('FrontendCredentialTest.kt','FrontendPrefs.kt','FrontendFakePrefs.kt'):
    shutil.copyfile(root/'scripts/today-jvm'/name, Path('/tmp/r657-frontend-credential-jvm')/name)
shutil.copyfile(root/'scripts/today-jvm/FrontendTest.kt', '/tmp/r657-recommend-jvm/FrontendTest.kt')
for command in ([sys.executable, '/tmp/r657-frontend-credential-jvm/run.py'],
                [sys.executable, '/tmp/r657-recommend-jvm/run.py', 'xyz.cncnlp.soundwave.recommend.FrontendTest']):
    result=subprocess.run(command, cwd=root)
    if result.returncode: sys.exit(result.returncode)
