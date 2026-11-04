#!/usr/bin/env python3
"""Execute verbatim Service transport methods + Controller methods, without Android/Gradle."""
from pathlib import Path
import argparse, subprocess, tempfile
p=argparse.ArgumentParser(); p.add_argument('--output', type=Path); a=p.parse_args()
root=Path(__file__).resolve().parents[1]; base=root/'app/src/main/java/xyz/cncnlp/soundwave'
fixtures=root/'scripts/playback-races-jvm'; out=a.output or Path(tempfile.mkdtemp(prefix='sw-pause-replay-')); out.mkdir(parents=True,exist_ok=True)
def method(text, signature):
    start=text.index(signature); brace=text.index('{', start)
    newline=text.index('\n',start)
    if '=' in text[start:newline] and newline < brace: return text[start:newline]
    level=1; end=brace+1
    while level:
        if text[end]=='{': level+=1
        elif text[end]=='}': level-=1
        end+=1
    return text[start:end]
s=(base/'PlayerService.kt').read_text(); c=(base/'PlaybackController.kt').read_text()
f=(fixtures/'Facades.kt').read_text()
f=f.replace('fun pause() {} // Real pause does not advance service generation: deliberately faithful.', method(s,'        fun pause()'))
f=f.replace('fun stopPlayback() { state = state.copy(generation = state.generation + 1) }', method(s,'        fun stopPlayback()'))
extra='\n'.join(method(s, sig) for sig in ['        private fun pauseInternal()', '        fun resumeInternal(', '        fun resume()'])
for sig in ['        fun currentPlaybackIntent(', '        fun beginPlaybackIntent(', '        fun isPlaybackIntentCurrent(', '        private fun interruptPlayback(', '        fun isPlaybackInterrupted(']:
    if sig in s: extra+='\n'+method(s,sig)
extra+='''
        var interruptedIntent = -1
        val playbackIntent = java.util.concurrent.atomic.AtomicInteger()
        val playGen = java.util.concurrent.atomic.AtomicInteger()
        var isPlayingFlag = false
        var positionMs = 0L
        val currentSong get() = state.song
        var recoveryGen = 0; var recoveryStatePrepared = false; var recoveryExplicitResume = false
        val serviceOwner = ProfileWorkIdentity.runtime()
        var onNeedReplay: ((Int) -> Unit)? = null
        var onPlaybackInterrupted: (() -> Unit)? = null
        var onPlayState: ((Boolean) -> Unit)? = null
        var onSongChanged: ((Song?) -> Unit)? = null
        var onError: ((PlaybackFailure) -> Unit)? = null
        var onNeedNext: (() -> Unit)? = null; var onNeedPrev: (() -> Unit)? = null
        var onPreparedNext: (() -> Unit)? = null; var onLikeToggle: (() -> Unit)? = null
        var onLyricFloatToggle: (() -> Unit)? = null; var onSleepTick: ((Long) -> Unit)? = null
        var onModeChanged: ((PlayMode) -> Unit)? = null
        val mainHandler = TestHandler()
        val player = TestPlayer()
        val instance: TestInstance? = TestInstance()
        val audioFocusController: TestFocus? = TestFocus()
        fun currentKey() = state.song?.key().orEmpty()
        fun hasPlayableMedia() = player.playable
        fun clearPlayerMedia(p: TestPlayer?) { p?.playable = false; p?.playing = false }
'''
f=f.replace('        fun beginPlaybackIntent(): Int = 0', '').replace('        fun isPlaybackIntentCurrent(token: Int): Boolean = true', '').replace('        fun isPlaybackInterrupted(): Boolean = false', '')
f=f.replace('        data class PlaybackFailure',extra+'\n        data class PlaybackFailure')
methods=c[c.index('    suspend fun resolveAndPlay('):c.index('    /** 播放切歌调用点顶层异常')]
methods+=c[c.index('    fun handlePlaybackFailure('):c.index('    fun savePlaybackState(')]
methods+=c[c.index('    fun cancelPendingPlayback('):c.index('    /**\n     * 播放故障')]
if '    fun replay(' in c: methods+=method(c,'    fun replay(')
else:
    main=(base/'MainActivity.kt').read_text()
    block=main[main.index('                onNeedReplay = {'):main.index('                onLikeToggle = {')]
    body=block[block.index('{')+1:block.rindex('}')]
    methods+='\n    fun replay(intent: Int) {'+body+'\n    }'

recovery=c[c.index('    private val playbackFailureRecovery ='):c.index('    val playingState')]
f=f.replace('// RECOVERY',recovery).replace('// METHODS',methods)
(out/'Controller.kt').write_text(f)
models=(base/'Models.kt').read_text(); (out/'Song.kt').write_text('package xyz.cncnlp.soundwave\n'+models[models.index('data class Song('):models.index('    fun toJson(includeSyncMetadata:')]+'}\n')
jars=sorted(Path('/tmp/r657-security-jvm').glob('*.jar'))+[Path('/tmp/r657-migu-jvm/coroutines.jar')]; assert all(x.is_file() for x in jars); cp=':'.join(map(str,jars))
sources=[out/'Controller.kt',out/'Song.kt',fixtures/'PauseReplayTest.kt', fixtures/'TransportFacades.kt', fixtures/'Log.kt',root/'scripts/heart-jvm/ProfileFacade.kt',base/'PlaybackTransactionGate.kt',base/'PlaybackFailureRecovery.kt',base/'playback/PlaybackRecoveryPolicy.kt',base/'ui/player/PlayerServiceEventBridge.kt',root/'app/src/test/java/xyz/cncnlp/soundwave/ui/player/PlayerServiceEventBridgeTest.kt']
r=subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(out/'classes'),*map(str,sources)])
if r.returncode: raise SystemExit(r.returncode)
raise SystemExit(subprocess.run(['java','-cp',f'{out}/classes:{cp}','org.junit.runner.JUnitCore','xyz.cncnlp.soundwave.PauseReplayIntegrationTest','xyz.cncnlp.soundwave.ui.player.PlayerServiceEventBridgeTest']).returncode)
