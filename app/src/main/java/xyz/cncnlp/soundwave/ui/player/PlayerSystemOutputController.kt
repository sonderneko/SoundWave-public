package xyz.cncnlp.soundwave.ui.player

import android.content.Context
import android.content.Intent
import android.media.MediaRouter
import android.provider.Settings

/** Owns only Android system media-output routing; Compose state and device-sheet UI stay with FullPlayer. */
internal class PlayerSystemOutputController(context: Context) {
    private val mediaRouter = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
    private val activityContext = context
    private var callback: MediaRouter.SimpleCallback? = null
    private var onSnapshotChanged: ((PlayerSystemOutputSnapshot<MediaRouter.RouteInfo>) -> Unit)? = null

    fun emptySnapshot(): PlayerSystemOutputSnapshot<MediaRouter.RouteInfo> =
        PlayerSystemOutputSnapshot(emptyList(), selectedRoute = null, defaultRoute = null)

    /** Register active scanning only while the caller's output-device sheet is visible. */
    fun start(onSnapshotChanged: (PlayerSystemOutputSnapshot<MediaRouter.RouteInfo>) -> Unit) {
        this.onSnapshotChanged = onSnapshotChanged
        if (callback != null) {
            refreshSnapshot()
            return
        }
        val routeCallback = object : MediaRouter.SimpleCallback() {
            override fun onRouteAdded(router: MediaRouter, info: MediaRouter.RouteInfo) = refreshSnapshot()
            override fun onRouteRemoved(router: MediaRouter, info: MediaRouter.RouteInfo) = refreshSnapshot()
            override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) = refreshSnapshot()
            override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = refreshSnapshot()
        }
        mediaRouter.addCallback(
            MediaRouter.ROUTE_TYPE_LIVE_AUDIO,
            routeCallback,
            MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
        )
        callback = routeCallback
        refreshSnapshot()
    }

    /** Removing the callback stops active scans as soon as the sheet closes. */
    fun stop() {
        callback?.let(mediaRouter::removeCallback)
        callback = null
        onSnapshotChanged = null
    }

    fun selectRoute(route: MediaRouter.RouteInfo): String = try {
        mediaRouter.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, route)
        refreshSnapshot()
        "已连接 ${route.name}"
    } catch (error: Exception) {
        "连接失败：${error.message ?: "设备不可用"}"
    }

    /** Return a message only for the existing Bluetooth fallback/error cases. */
    fun openSystemOutput(): String? = try {
        activityContext.startActivity(Intent("android.settings.panel.action.MEDIA_OUTPUT"))
        null
    } catch (_: Exception) {
        try {
            activityContext.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "系统未提供媒体输出弹窗，已打开蓝牙设备连接页"
        } catch (_: Exception) {
            "无法打开系统蓝牙设备连接页"
        }
    }

    private fun refreshSnapshot() {
        onSnapshotChanged?.invoke(
            PlayerSystemOutputSnapshotPolicy.create(
                routes = (0 until mediaRouter.routeCount).map(mediaRouter::getRouteAt),
                selectedRoute = mediaRouter.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO),
                defaultRoute = mediaRouter.defaultRoute,
                isEnabled = { route -> route.isEnabled },
                supportedTypes = { route -> route.supportedTypes },
                liveAudioType = MediaRouter.ROUTE_TYPE_LIVE_AUDIO
            )
        )
    }
}
