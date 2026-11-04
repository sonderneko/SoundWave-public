#!/usr/bin/env python3
"""Compile exact current controller methods with explicit JVM facades; no SDK/Gradle.
Facades: Android log/toast, service, resolver/cache, local lookup, lyrics/history,
profile runtime. Real coroutine scheduling, production gate/recovery policy, exact
resolveAndPlay and failure entry methods execute; not source-string assertions.
"""
from pathlib import Path
import argparse
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--output', type=Path)
p.add_argument('--controller', type=Path)
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
fixtures = root / 'scripts/playback-races-jvm'
out = a.output or Path(tempfile.mkdtemp(prefix='soundwave-playback-races-'))
out.mkdir(parents=True, exist_ok=True)
jars = sorted(Path('/tmp/r657-security-jvm').glob('*.jar')) + [Path('/tmp/r657-migu-jvm/coroutines.jar')]
assert all(x.is_file() for x in jars)
cp = ':'.join(map(str, jars))
text = (a.controller or base / 'PlaybackController.kt').read_text()
methods = text[text.index('    suspend fun resolveAndPlay('):text.index('    /** 播放切歌调用点顶层异常')]
methods += text[text.index('    fun handlePlaybackFailure('):text.index('    fun savePlaybackState(')]
if '    fun cancelPendingPlayback(' in text:
    methods += text[text.index('    fun cancelPendingPlayback('):text.index('    /**\n     * 播放故障')]
methods += text[text.index('    fun replaceQueue('):text.index('    /** Reorder against one service snapshot;')]
recovery = text[text.index('    private val playbackFailureRecovery ='):text.index('    val playingState')]
service = (base / 'PlayerService.kt').read_text()
service_method = service[service.index('        fun handleTrackEnded() {'):service.index('\n    }\n\n    private var mediaSession', service.index('        fun handleTrackEnded() {'))]
facades = (fixtures / 'Facades.kt').read_text().replace('// RECOVERY', recovery).replace('// METHODS', methods)
(out / 'Controller.kt').write_text(facades.replace('// SERVICE_METHOD', service_method))
models = (base / 'Models.kt').read_text()
(out / 'Song.kt').write_text('package xyz.cncnlp.soundwave\n' + models[models.index('data class Song('):models.index('    fun toJson(includeSyncMetadata:')] + '}\n')
sources = [out/'Controller.kt', out/'Song.kt', fixtures/'BehaviorTest.kt', fixtures/'Log.kt', fixtures/'MetadataFacade.kt',
           root/'scripts/heart-jvm/ProfileFacade.kt', base/'PlaybackTransactionGate.kt',
           root/'app/src/test/java/xyz/cncnlp/soundwave/PlaybackTransactionGateTest.kt',
           base/'PlaybackFailureRecovery.kt', base/'playback/PlaybackRecoveryPolicy.kt']
r = subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect',
                    '-classpath',cp,'-d',str(out/'classes'),*map(str,sources)])
if r.returncode: raise SystemExit(r.returncode)
raise SystemExit(subprocess.run(['java','-cp',f'{out}/classes:{cp}','org.junit.runner.JUnitCore',
                               'xyz.cncnlp.soundwave.PlaybackRaceBehaviorTest',
                               'xyz.cncnlp.soundwave.PlaybackTransactionGateTest']).returncode)
