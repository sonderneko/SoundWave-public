package xyz.cncnlp.soundwave.ui.player

/** A filtered system-route view plus the selected and local-default route identities. */
internal data class PlayerSystemOutputSnapshot<T>(
    val routes: List<T>,
    val selectedRoute: T?,
    val defaultRoute: T?
) {
    fun isSelected(route: T): Boolean = route == selectedRoute

    fun isDefaultRouteSelected(): Boolean = selectedRoute == defaultRoute
}

/** Pure policy boundary so route filtering and selected markers can be tested without Android services. */
internal object PlayerSystemOutputSnapshotPolicy {
    fun <T> create(
        routes: Iterable<T>,
        selectedRoute: T?,
        defaultRoute: T?,
        isEnabled: (T) -> Boolean,
        supportedTypes: (T) -> Int,
        liveAudioType: Int
    ): PlayerSystemOutputSnapshot<T> = PlayerSystemOutputSnapshot(
        routes = routes.filter { route ->
            isEnabled(route) && (supportedTypes(route) and liveAudioType) != 0
        },
        selectedRoute = selectedRoute,
        defaultRoute = defaultRoute
    )
}
