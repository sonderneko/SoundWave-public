package xyz.cncnlp.soundwave.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import xyz.cncnlp.soundwave.MainActivity
import xyz.cncnlp.soundwave.PlaybackStore
import xyz.cncnlp.soundwave.PlayerService
import xyz.cncnlp.soundwave.R
import java.util.EnumMap
import java.util.concurrent.Executors

/** The two home-screen surfaces. One provider class each, so the system keeps
 *  independent id sets, sizes and previews per size. */
internal enum class WidgetKind(
    val layoutRes: Int,
    val providerClass: Class<*>,
    val coverPx: Int,
    val requestCodeBase: Int,
) {
    STANDARD(R.layout.widget_player_4x2, SoundWaveWidgetProvider::class.java, 288, 4100),
    COMPACT(R.layout.widget_player_4x1, SoundWaveCompactWidgetProvider::class.java, 120, 4200),
}

/** Reads the same playback facts the in-app UI reads — no widget-private state store. */
internal object WidgetStateSource {

    fun currentContent(context: Context): WidgetContent {
        val live = runCatching {
            val snap = PlayerService.snapshot()
            WidgetContent.live(snap.song?.name, snap.song?.singer, snap.song?.cover, snap.isPlaying)
        }.getOrDefault(WidgetContent.EMPTY)
        if (live.hasSong) return live
        val checkpoint = runCatching {
            val state = PlaybackStore.load(context)
            WidgetContent.checkpoint(state?.song?.name, state?.song?.singer, state?.song?.cover)
        }.getOrDefault(WidgetContent.EMPTY)
        return WidgetContent.resolve(live, checkpoint)
    }
}

/** Builds the RemoteViews for one surface from resolved content + optional artwork. */
internal object WidgetRemoteViewsFactory {

    fun build(context: Context, kind: WidgetKind, content: WidgetContent, cover: Bitmap?): RemoteViews {
        val views = RemoteViews(context.packageName, kind.layoutRes)
        // Artwork: decoded cached cover when we have one, otherwise the app icon —
        // the honest placeholder, never a blank plate.
        if (cover != null) {
            views.setImageViewBitmap(R.id.widget_cover, cover)
        } else {
            views.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
        }
        // Body tap opens the app exactly like the notification content intent does
        // (plain MainActivity launch; there is no full-player deep link in the app).
        val openApp = openAppIntent(context, kind)
        views.setOnClickPendingIntent(R.id.widget_root, openApp)
        views.setOnClickPendingIntent(R.id.widget_cover, openApp)
        if (!content.hasSong) {
            // Honest empty state: app icon (set above), app name and an open-the-app
            // line instead of a blank board. Controls are hidden — there is nothing
            // to control yet — and the whole card stays tappable via the root intent.
            when (kind) {
                WidgetKind.STANDARD -> {
                    views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_empty_title))
                    views.setTextViewText(R.id.widget_artist, context.getString(R.string.widget_empty_subtitle))
                }
                WidgetKind.COMPACT -> views.setTextViewText(
                    R.id.widget_line,
                    context.getString(R.string.widget_empty_subtitle),
                )
            }
            views.setViewVisibility(R.id.widget_controls, View.GONE)
            return views
        }
        when (kind) {
            WidgetKind.STANDARD -> {
                views.setTextViewText(R.id.widget_title, content.title)
                views.setTextViewText(R.id.widget_artist, content.artist)
                views.setOnClickPendingIntent(R.id.widget_title, openApp)
                views.setOnClickPendingIntent(R.id.widget_artist, openApp)
            }
            WidgetKind.COMPACT -> {
                views.setTextViewText(R.id.widget_line, WidgetContent.compactLine(content))
                views.setOnClickPendingIntent(R.id.widget_line, openApp)
            }
        }
        views.setViewVisibility(R.id.widget_controls, View.VISIBLE)
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (content.playing) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
        )
        // Control buttons reuse PlayerService's existing intent-action protocol
        // verbatim. The intents are deliberately unticketed: a widget cannot know the
        // current service generation, and PlayerService treats unticketed action
        // intents as its supported legacy path (same as external callers).
        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            serviceIntent(context, kind, PlayerService.ACTION_TOGGLE, 1),
        )
        views.setOnClickPendingIntent(
            R.id.widget_prev,
            serviceIntent(context, kind, PlayerService.ACTION_PREV, 2),
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            serviceIntent(context, kind, PlayerService.ACTION_NEXT, 3),
        )
        return views
    }

    private fun openAppIntent(context: Context, kind: WidgetKind): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            kind.requestCodeBase,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun serviceIntent(context: Context, kind: WidgetKind, action: String, offset: Int): PendingIntent {
        val intent = Intent(context, PlayerService::class.java).setAction(action)
        // getForegroundService (not the notification's getService) so a tap can also
        // cold-start playback: PlayerService.onStartCommand's ACTION_RESUME path
        // already restores the persisted checkpoint for explicit resume intents.
        return PendingIntent.getForegroundService(
            context,
            kind.requestCodeBase + offset,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * Push-only widget updates. PlayerService pushes on song/play-state changes (its
 * updateSessionAndNotify funnel plus the async cover-commit point); the system
 * pushes on widget add/resize. A content gate collapses repeat pushes — notably
 * the per-second session ticks — so nothing here polls and nothing refreshes on a
 * timer. All decoding happens on this single background executor, never on the
 * launcher's or the service's main thread.
 */
internal object WidgetUpdater {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sw-widget-updater").apply { isDaemon = true }
    }
    private val lastPushKey = EnumMap<WidgetKind, String>(WidgetKind::class.java)

    /** System-initiated render (widget added / resized / host restart). */
    fun refresh(context: Context, onDone: () -> Unit = {}) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                render(appContext, serviceCover = null, force = true)
            } catch (_: Exception) {
                // A widget render failure must never take the host or service down.
            } finally {
                onDone()
            }
        }
    }

    /** Service-initiated push; [serviceCover] is the notification stack's decoded cover. */
    fun refreshFromService(context: Context, serviceCover: Bitmap?) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                render(appContext, serviceCover = serviceCover, force = false)
            } catch (_: Exception) {
            }
        }
    }

    private fun render(context: Context, serviceCover: Bitmap?, force: Boolean) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val idsByKind = WidgetKind.entries.mapNotNull { kind ->
            val ids = manager.getAppWidgetIds(ComponentName(context, kind.providerClass))
            if (ids.isEmpty()) null else kind to ids
        }
        if (idsByKind.isEmpty()) return
        val content = WidgetStateSource.currentContent(context)
        for ((kind, ids) in idsByKind) {
            val cover = when {
                !content.hasSong -> null
                serviceCover != null -> WidgetCoverLoader.roundedFrom(serviceCover, kind.coverPx)
                else -> WidgetCoverLoader.loadRounded(context, content.coverUrl, kind.coverPx)
            }
            // The artwork flag is part of the gate key so the first push (cover still
            // loading) is followed by exactly one more push once the cover lands.
            val gateKey = content.pushKey + "|art=" + (cover != null)
            if (!force && lastPushKey[kind] == gateKey) continue
            val views = WidgetRemoteViewsFactory.build(context, kind, content, cover)
            manager.updateAppWidget(ids, views)
            lastPushKey[kind] = gateKey
        }
    }
}
