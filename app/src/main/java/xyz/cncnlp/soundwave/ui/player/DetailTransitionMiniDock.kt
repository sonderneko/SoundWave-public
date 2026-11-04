package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.MiniPlayer
import xyz.cncnlp.soundwave.Song

/** Stateless compact mini-player dock; playback, routing, and dock state remain owned by AppRoot. */
@Composable
internal fun DetailTransitionMiniDock(
    song: Song,
    playing: Boolean,
    progressState: State<Float>,
    // ACCEPT6-FREEZE：与 MiniPlayer 同纪律，解析状态以 State 透传、叶内读取。
    resolvingState: State<Boolean>,
    lyricLine: String,
    onOpenPlayer: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    style: String = "default"
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
        MiniPlayer(
            song = song,
            playing = playing,
            progressState = progressState,
            resolvingState = resolvingState,
            lyricLine = lyricLine,
            compact = true,
            fullWidth = true,
            showLyricsWhenCompact = false,
            style = style,
            onClick = onOpenPlayer,
            onToggle = onToggle,
            onNext = onNext,
            onSwipeUp = onSwipeUp,
            onSwipeDown = onSwipeDown
        )
    }
}
