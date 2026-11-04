package xyz.cncnlp.soundwave.ui.player

import androidx.compose.runtime.Composable
import xyz.cncnlp.soundwave.PlaybackPathSheet
import xyz.cncnlp.soundwave.Song

/**
 * r657 第三轮 b：播放器 ⋯ →「播放取链链路」与歌曲操作抽屉统一为同一个时间轴抽屉（[PlaybackPathSheet]）。
 * 仍只复用设置报告的真实内存 / 持久化记录，不为此 UI 额外取链。
 */
@Composable
internal fun PlayerPlaybackLinkSheet(song: Song, onDismiss: () -> Unit) {
    PlaybackPathSheet(song, onDismiss)
}
