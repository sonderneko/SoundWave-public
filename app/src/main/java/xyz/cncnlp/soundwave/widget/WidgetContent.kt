package xyz.cncnlp.soundwave.widget

/**
 * What a home-screen widget should show, already resolved to plain values so the
 * presentation rules stay unit-testable without Android types.
 *
 * State resolution contract (see WAVE-ACCEPT6-WIDGET ledger):
 * live service snapshot first, then the persisted PlaybackStore checkpoint rendered
 * paused, then the honest empty state. The widget never invents playback state.
 */
internal data class WidgetContent(
    val hasSong: Boolean,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val playing: Boolean,
) {
    /**
     * Change key for the push gate: only these fields justify a RemoteViews re-push.
     * Playback position is deliberately absent — the widget carries no progress
     * surface, so per-second ticks collapse into no-ops here.
     */
    val pushKey: String
        get() = listOf(hasSong, title, artist, coverUrl, playing).joinToString("|")

    companion object {
        val EMPTY = WidgetContent(
            hasSong = false,
            title = "",
            artist = "",
            coverUrl = "",
            playing = false,
        )

        /** Live state from PlayerService.snapshot(); a blank title means "no live song". */
        fun live(title: String?, artist: String?, coverUrl: String?, playing: Boolean): WidgetContent {
            val safeTitle = title?.trim().orEmpty()
            if (safeTitle.isEmpty()) return EMPTY
            return WidgetContent(
                hasSong = true,
                title = safeTitle,
                artist = artist?.trim().orEmpty(),
                coverUrl = coverUrl?.trim().orEmpty(),
                playing = playing,
            )
        }

        /** A persisted checkpoint is history, not live playback: always rendered paused. */
        fun checkpoint(title: String?, artist: String?, coverUrl: String?): WidgetContent {
            val safeTitle = title?.trim().orEmpty()
            if (safeTitle.isEmpty()) return EMPTY
            return WidgetContent(
                hasSong = true,
                title = safeTitle,
                artist = artist?.trim().orEmpty(),
                coverUrl = coverUrl?.trim().orEmpty(),
                playing = false,
            )
        }

        /** Precedence: live service state, then the persisted checkpoint, then empty. */
        fun resolve(live: WidgetContent, checkpoint: WidgetContent): WidgetContent = when {
            live.hasSong -> live
            checkpoint.hasSong -> checkpoint
            else -> EMPTY
        }

        /** 4x1 single-line form; the artist is dropped before the title when space is tight. */
        fun compactLine(content: WidgetContent): String = when {
            !content.hasSong -> ""
            content.artist.isBlank() -> content.title
            else -> "${content.title} · ${content.artist}"
        }
    }
}
