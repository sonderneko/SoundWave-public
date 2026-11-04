#!/usr/bin/env python3
"""Focused JVM behavior tests of real heart methods (NOT an Android build).
Uses existing compiler/JUnit jars; no SDK/Gradle/install/network. Explicit facades
replace media service, network resolver, prefs, lyrics and profile runtime.
"""
from pathlib import Path
import argparse
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--jars', type=Path, default=Path('/tmp/r657-security-jvm'))
parser.add_argument('--coroutines', type=Path, default=Path('/tmp/r657-migu-jvm/coroutines.jar'))
parser.add_argument('--output', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
fixtures = root / 'scripts/heart-jvm'
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
home = args.output or Path(tempfile.mkdtemp(prefix='soundwave-heart-jvm-'))
home.mkdir(parents=True, exist_ok=True)
jars = sorted(args.jars.glob('*.jar')) + [args.coroutines]
if not args.coroutines.is_file() or not any('kotlin-compiler-embeddable' in p.name for p in jars):
    parser.error('Pass existing Kotlin compiler/JUnit jar directory and kotlinx-coroutines jar')
cp = ':'.join(map(str, jars))
models = (base / 'Models.kt').read_text()
# Production fields/key verbatim; JSON/Android serialization outside this test.
(home / 'Song.kt').write_text('package xyz.cncnlp.soundwave\n' + models[models.index('data class Song('):models.index('    fun toJson(includeSyncMetadata:')] + '}\n')
(home / 'Playlist.kt').write_text('package xyz.cncnlp.soundwave\n' + models[models.index('data class Playlist('):models.index('/** 远程平台歌单只能')])
controller = (base/'PlaybackController.kt').read_text()
start = controller.index('    fun toggleHeartMode(')
end = controller.index('    suspend fun playDelta(', start)
heart_start = controller.index('    suspend fun playHeartSimilar(')
heart_end = controller.index('    /** 播放器的相似歌曲也走 Gate', heart_start)
methods = controller[start:end] + controller[heart_start:heart_end]
(home/'ExtractedHeartController.kt').write_text((fixtures/'ControllerFacades.kt').read_text().replace('// METHODS_INSERTED_HERE', methods))
main = (base/'MainActivity.kt').read_text()
entry_start = main.index('    fun togglePlaylistHeartMode(')
entry_end = main.index('    suspend fun playDelta(', entry_start)
(home/'ExtractedPlaylistEntry.kt').write_text((fixtures/'PlaylistEntryFacades.kt').read_text().replace('// METHODS_INSERTED_HERE', main[entry_start:entry_end]))
sources = [home/'Song.kt', home/'Playlist.kt', base/'HeartPlaylistContext.kt', base/'recommend/TasteProfile.kt', fixtures/'ProfileFacade.kt', home/'ExtractedHeartController.kt',
    home/'ExtractedPlaylistEntry.kt', fixtures/'DigitalAlbumFacade.kt', base/'DetailCachePolicy.kt',
    base/'PlaybackTransactionGate.kt', base/'HeartRecommendationPolicy.kt', fixtures/'PlaylistEntryBehaviorTest.kt',
    root/'app/src/test/java/xyz/cncnlp/soundwave/HeartRecommendationPolicyTest.kt',
    root/'app/src/test/java/xyz/cncnlp/soundwave/HeartPlaylistContextTest.kt',
    fixtures/'HeartControllerBehaviorTest.kt']
compile_result = subprocess.run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(home/'classes'), *map(str,sources)])
if compile_result.returncode:
    raise SystemExit(compile_result.returncode)
raise SystemExit(subprocess.run(['java', '-cp', f'{home}/classes:{cp}', 'org.junit.runner.JUnitCore',
    'xyz.cncnlp.soundwave.HeartRecommendationPolicyTest',
    'xyz.cncnlp.soundwave.HeartPlaylistContextTest',
    'xyz.cncnlp.soundwave.PlaylistEntryBehaviorTest',
    'xyz.cncnlp.soundwave.HeartControllerBehaviorTest']).returncode)
