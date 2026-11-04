#!/usr/bin/env python3
"""Focused actual Kotlin callback/transport/recovery bodies; no SDK or Gradle."""
from pathlib import Path
import argparse, subprocess, tempfile
p=argparse.ArgumentParser(); p.add_argument('--output',type=Path); a=p.parse_args()
root=Path(__file__).resolve().parents[1]; base=root/'app/src/main/java/xyz/cncnlp/soundwave'
out=a.output or Path(tempfile.mkdtemp(prefix='sw-auto-recovery-')); out.mkdir(parents=True,exist_ok=True)
def method(text, sig):
    start=text.index(sig); brace=text.index('{',start); nl=text.index('\n',start)
    if '=' in text[start:nl] and nl<brace: return text[start:nl]
    depth=1; end=brace+1
    while depth:
        if text[end]=='{': depth+=1
        elif text[end]=='}': depth-=1
        end+=1
    return text[start:end]
s=(base/'PlayerService.kt').read_text(); c=(base/'PlaybackController.kt').read_text(); m=(base/'MainActivity.kt').read_text()
# Reuse the established real Controller + Service transport extraction, without executing its compiler.
runner=(root/'scripts/test_pause_replay_integration_jvm.py').read_text()
runner=runner[:runner.index('jars=sorted')]
runner=runner.replace("p=argparse.ArgumentParser(); p.add_argument('--output', type=Path); a=p.parse_args()", "a=type('Args',(),{'output':OUT})()")
ns={'__file__':str(root/'scripts/test_pause_replay_integration_jvm.py'),'OUT':out}; exec(compile(runner,'pause-extraction','exec'),ns)
f=(out/'Controller.kt').read_text()
# Compile callback closures verbatim, so Main wiring itself is exercised.
callbacks=''
for name in ['onNeedNext','onNeedPrev','onPreparedNext']:
    line=next(x.strip() for x in m.splitlines() if x.strip().startswith(name+' ='))
    body=line[line.index('{')+1:line.rindex('}')]
    callbacks+=f'\n    fun {name}Callback() {{{body}}}\n'
extra=method(c,'    fun enqueueQueueAdvance(') if '    fun enqueueQueueAdvance(' in c else ''
f=f.replace('    // METHODS', '    // METHODS')
f=f.replace('class RaceController(private val scope: CoroutineScope) {', 'class RaceController(private val scope: CoroutineScope) {\n val playback get() = this\n val playDeltaRef = arrayOf<suspend (Int)->Unit>({})\n'+callbacks+extra)
(out/'Controller.kt').write_text(f)
jars=sorted(Path('/tmp/r657-security-jvm').glob('*.jar'))+[Path('/tmp/r657-migu-jvm/coroutines.jar')]; assert all(x.is_file() for x in jars); cp=':'.join(map(str,jars))
fixtures=root/'scripts/playback-races-jvm'
sources=[out/'Controller.kt',out/'Song.kt',fixtures/'AutoAdvanceRecoveryTest.kt',fixtures/'TransportFacades.kt',fixtures/'Log.kt',root/'scripts/heart-jvm/ProfileFacade.kt',base/'PlaybackTransactionGate.kt',base/'PlaybackFailureRecovery.kt',base/'playback/PlaybackRecoveryPolicy.kt']
r=subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(out/'classes'),*map(str,sources)])
if r.returncode: raise SystemExit(r.returncode)
raise SystemExit(subprocess.run(['java','-cp',f'{out}/classes:{cp}','org.junit.runner.JUnitCore','xyz.cncnlp.soundwave.AutoAdvanceRecoveryTest']).returncode)
