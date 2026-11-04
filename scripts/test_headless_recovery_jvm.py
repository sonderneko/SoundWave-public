#!/usr/bin/env python3
"""Compile exact Service restore/resolve/transport bodies against explicit JVM facades."""
from pathlib import Path
import argparse, subprocess, tempfile
p=argparse.ArgumentParser();p.add_argument('--output',type=Path);a=p.parse_args()
root=Path(__file__).resolve().parents[1];base=root/'app/src/main/java/xyz/cncnlp/soundwave';fixtures=root/'scripts/playback-races-jvm'
out=a.output or Path(tempfile.mkdtemp(prefix='sw-headless-'));out.mkdir(parents=True,exist_ok=True)
def method(text,sig):
 start=text.index(sig);brace=text.index('{',start);nl=text.index('\n',start)
 if '=' in text[start:nl] and nl<brace:return text[start:nl]
 depth=1;end=brace+1
 while depth:
  if text[end]=='{':depth+=1
  elif text[end]=='}':depth-=1
  end+=1
 return text[start:end]
s=(base/'PlayerService.kt').read_text()
transport='\n'.join(method(s,x) for x in ['        fun beginPlaybackIntent(', '        fun isPlaybackIntentCurrent(', '        fun currentPlaybackIntent(', '        fun isPlaybackInterrupted(', '        private fun interruptPlayback(', '        fun pause()', '        private fun pauseInternal()', '        fun resumeInternal(', '        fun resume()', '        fun handleTrackEnded()'])
recovery='\n'.join(method(s,x) for x in ['    private fun restoreFromStore(', '    private fun resolveInService('])
# Visibility only is widened for direct test invocation; bodies remain unchanged.
recovery=recovery.replace('private fun restoreFromStore','fun restoreFromStore').replace('private fun resolveInService','fun resolveInService')
for sig in ['    private fun cancelPlaybackRecovery(', '    private fun launchPlaybackRecovery(']:
 if sig in s:recovery+='\n'+method(s,sig)
if 'private val playbackRecoveryLock' in s:
 start=s.index('    private val playbackRecoveryLock');end=s.index('    private fun ',start)
 recovery=s[start:end]+recovery
f=(fixtures/'HeadlessFacades.kt').read_text().replace('// TRANSPORT',transport).replace('// RECOVERY',recovery)
(out/'Service.kt').write_text(f)
models=(base/'Models.kt').read_text();(out/'Song.kt').write_text('package xyz.cncnlp.soundwave\n'+models[models.index('data class Song('):models.index('    fun toJson(includeSyncMetadata:')]+'}\n')
jars=sorted(Path('/tmp/r657-security-jvm').glob('*.jar'))+[Path('/tmp/r657-migu-jvm/coroutines.jar')];cp=':'.join(map(str,jars))
sources=[out/'Service.kt',out/'Song.kt',fixtures/'HeadlessRecoveryTest.kt',fixtures/'Log.kt',root/'scripts/heart-jvm/ProfileFacade.kt',base/'playback/PlaybackRecoveryPolicy.kt']
r=subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(out/'classes'),*map(str,sources)])
if r.returncode:raise SystemExit(r.returncode)
raise SystemExit(subprocess.run(['java','-cp',f'{out}/classes:{cp}','org.junit.runner.JUnitCore','xyz.cncnlp.soundwave.HeadlessRecoveryTest']).returncode)
