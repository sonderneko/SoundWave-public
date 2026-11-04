package xyz.cncnlp.soundwave.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * Home-screen widget providers. Rendering is delegated to [WidgetUpdater], which
 * works on its own background executor; goAsync keeps the process alive until the
 * (cache-only, fast) render has been pushed.
 */
abstract class BaseSoundWaveWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        WidgetUpdater.refresh(context) { pending.finish() }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pending = goAsync()
        WidgetUpdater.refresh(context) { pending.finish() }
    }
}

/** 4x2 surface: cover, title, artist and prev / play-pause / next. */
class SoundWaveWidgetProvider : BaseSoundWaveWidgetProvider()

/** 4x1 surface: one combined title line and compact controls (see ledger trade-offs). */
class SoundWaveCompactWidgetProvider : BaseSoundWaveWidgetProvider()
