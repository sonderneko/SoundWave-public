package xyz.cncnlp.soundwave

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.app.NotificationCompat.MediaStyle

/** Values captured by the playback owner; presentation never reads or mutates player state. */
internal data class NotificationPresentation(
    val channelId: String,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    val lyricEnabled: Boolean,
    val lyricLocked: Boolean,
    val cover: Bitmap?,
    val accentColor: Int,
)

/** The service creates these intents, retaining profile/session ownership and action routing. */
internal data class NotificationControls(
    val content: PendingIntent,
    val like: PendingIntent,
    val previous: PendingIntent,
    val playPause: PendingIntent,
    val next: PendingIntent,
    val lyric: PendingIntent,
)

/**
 * Service-scoped Android notification presentation and artwork cache.
 * No networking, playback, profile, coroutine or service lifecycle dependencies.
 * Returned/source bitmaps may still be used by MediaSession/Binder: do not recycle them here.
 */
internal class NotificationPresenter(private val context: Context) {
    private val notificationArtLock = Any()
    private var cachedAppIcon: Bitmap? = null
    private var badgedCoverSource: Bitmap? = null
    private var badgedCover: Bitmap? = null
    private val lyricGlyphCache = HashMap<Int, Bitmap>(4)

    fun build(
        state: NotificationPresentation,
        controls: NotificationControls,
        sessionToken: MediaSessionCompat.Token?,
    ): Notification {
        val builder = NotificationCompat.Builder(context, state.channelId)
            .setContentTitle(state.title.ifBlank { "SoundWave" })
            .setContentText(state.artist.ifBlank { " " })
            .setSmallIcon(R.drawable.ic_notif_play_v12)
            .setLargeIcon(coverWithAppBadge(state.cover))
            .setContentIntent(controls.content)
            .apply {
                val d = state.durationMs
                if (d > 0L) setProgress(d.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), state.positionMs.coerceIn(0L, d).toInt(), false)
            }
            .setOngoing(false)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(state.accentColor)
            .addAction(R.drawable.ic_notif_like_v12, "喜欢", controls.like)
            .addAction(R.drawable.ic_notif_prev_v12, "上一首", controls.previous)
            .addAction(
                if (state.playing) R.drawable.ic_notif_pause_v12 else R.drawable.ic_notif_play_v12,
                if (state.playing) "暂停" else "播放",
                controls.playPause,
            )
            .addAction(R.drawable.ic_notif_next_v12, "下一首", controls.next)
            .addAction(NotificationCompat.Action.Builder(
                IconCompat.createWithBitmap(lyricWordBitmap(state.lyricEnabled, state.lyricLocked)),
                when {
                    !state.lyricEnabled -> "开启悬浮歌词"
                    state.lyricLocked -> "关闭悬浮歌词"
                    else -> "锁定悬浮歌词"
                },
                controls.lyric,
            ).build())
        // 紧凑视图固定露 上一首(1) / 播放暂停(2) / 下一首(3)：与大厂播放通知一致，
        // 喜欢(0) 与悬浮歌词(4) 只在展开视图出现（SW 通知规范，P3）。
        val style = MediaStyle().setShowActionsInCompactView(1, 2, 3)
        if (sessionToken != null) style.setMediaSession(sessionToken)
        return builder.setStyle(style).build()
    }

    /** 桌面 APP 图标（用于封面右下角角标叠图） */
    private fun appIconBitmap(): Bitmap? = synchronized(notificationArtLock) {
        cachedAppIcon ?: renderAppIconBitmap().also { cachedAppIcon = it }
    }

    private fun renderAppIconBitmap(): Bitmap? {
        // ColorOS/iQOO：decodeResource(mipmap adaptive) 会出安卓机器人。必须走桌面 ApplicationIcon 或实心 PNG。
        return try {
            val d = context.packageManager.getApplicationIcon(context.packageName)
            val w = d.intrinsicWidth.takeIf { it > 0 } ?: 192
            val h = d.intrinsicHeight.takeIf { it > 0 } ?: 192
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            bmp
        } catch (_: Exception) {
            try {
                BitmapFactory.decodeResource(context.resources, R.drawable.ic_app_icon)
                    ?: BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_fg_bitmap)
            } catch (_: Exception) { null }
        }
    }

    /**
     * 把桌面 APP 图标画到封面右下角（汽水角标效果）。
     * 状态栏仍用纯白音符 smallIcon，避免系统再叠一层机器人/默认图。
     */
    private fun coverWithAppBadge(cover: Bitmap?): Bitmap? = synchronized(notificationArtLock) {
        // 同一张封面（同一 Bitmap 实例）只合成一次角标；切歌换封面才重算。
        if (badgedCover != null && badgedCoverSource === cover) return@synchronized badgedCover
        renderCoverWithAppBadge(cover).also { badgedCoverSource = cover; badgedCover = it }
    }

    private fun renderCoverWithAppBadge(cover: Bitmap?): Bitmap? {
        val app = appIconBitmap()
        val base = when {
            cover != null -> cover
            app != null -> app
            else -> return null
        }
        val out = try {
            base.copy(Bitmap.Config.ARGB_8888, true) ?: base
        } catch (_: Exception) {
            base
        }
        if (app == null || cover == null) return out // 无封面时 largeIcon 直接用桌面图，不再叠
        return try {
            val c = Canvas(out)
            val side = (minOf(out.width, out.height) * 0.28f).toInt().coerceIn(48, 168)
            val badge = Bitmap.createScaledBitmap(app, side, side, true)
            val pad = (side * 0.12f).toInt().coerceAtLeast(4)
            val left = out.width - side - pad
            val top = out.height - side - pad
            // 轻微圆形阴影底，保证角标可读
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0x66000000
            val r = side / 2f
            c.drawCircle(left + r, top + r, r + pad * 0.15f, paint)
            c.drawBitmap(badge, left.toFloat(), top.toFloat(), null)
            if (badge !== app) badge.recycle()
            out
        } catch (_: Exception) {
            out
        }
    }

    // r656：图标绘制移至 NotificationLyricGlyph（开启珊瑚色 / 关闭灰；开启 = 小勾角标，锁定 = 缩小挂锁角标）。
    private fun lyricWordBitmap(enabled: Boolean, locked: Boolean): Bitmap = synchronized(notificationArtLock) {
        lyricGlyphCache.getOrPut((if (enabled) 1 else 0) or (if (locked) 2 else 0)) { NotificationLyricGlyph.render(enabled, locked) }
    }

}
