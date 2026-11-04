package xyz.cncnlp.soundwave

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Event-time projection only. AppRoot remains the navigation/state and lifecycle owner. */
internal data class MainNavigationSnapshot(
    val tab: Tab,
    val previousTab: Tab,
    val lastContentTab: Tab,
    val detailOpen: Boolean,
    val settingsSub: String?,
    val searchResult: AppRoute.SearchResult?,
    val downloadPickerOpen: Boolean,
    val lastBackAt: Long
)

/** AppRoot applies the writes; this boundary owns neither Compose state nor preferences. */
internal class MainNavigationEffects(
    val dismissDownloadPicker: () -> Unit,
    val returnToTopLevelTab: (Tab) -> Unit,
    val closeDetail: () -> Unit,
    val popSettingsSub: () -> Unit,
    val clearSearch: () -> Unit,
    val finishActivity: () -> Unit,
    val showExitPrompt: (Long) -> Unit,
    val setLastContentTab: (Tab) -> Unit,
    val clearSearchDockFocus: () -> Unit,
    val restoreHomeSection: (Tab, Tab, Boolean) -> Unit,
    val applyMainTab: (Tab) -> Unit
)

/**
 * Shared root back/tab event orchestration, independent of Activity startup and account/player work.
 * Read once at event time, never retain a second route or change the existing return/restore policy.
 */
internal class MainNavigationEvents(
    private val snapshot: () -> MainNavigationSnapshot,
    private val effects: MainNavigationEffects
) {
    fun back(nowMillis: Long) {
        val state = snapshot()
        if (state.downloadPickerOpen) {
            effects.dismissDownloadPicker()
            return
        }
        when (AppRoute.backAction(
            tab = state.tab,
            detailOpen = state.detailOpen,
            settingsSub = state.settingsSub,
            searchResult = state.searchResult
        )) {
            BackAction.ClosePlayer -> effects.returnToTopLevelTab(state.previousTab)
            BackAction.CloseDetail -> effects.closeDetail()
            // accept6-backnav：弹一层设置栈（深度-1 回根、深度-2 起回直接父页），目标由栈决定。
            BackAction.CloseSettingsSub -> effects.popSettingsSub()
            BackAction.ClearSearch -> effects.clearSearch()
            BackAction.BackToHome -> when {
                state.tab == Tab.SEARCH -> effects.returnToTopLevelTab(AppRoute.searchBackTarget(state.lastContentTab))
                nowMillis - state.lastBackAt < 1600L -> effects.finishActivity()
                else -> effects.showExitPrompt(nowMillis)
            }
        }
    }

    fun selectMainTab(target: Tab) {
        val state = snapshot()
        if (target != Tab.SEARCH && target != Tab.PLAYER) effects.setLastContentTab(target)
        if (target == Tab.SEARCH) effects.clearSearchDockFocus()
        if (target == Tab.HOME) effects.restoreHomeSection(target, state.tab, state.detailOpen)
        effects.applyMainTab(target)
    }
}

/** The real Compose BackHandler used by AppRoot; no separate navigation host/state. */
@Composable
internal fun AppRootNavigationBackHandler(events: MainNavigationEvents) {
    BackHandler { events.back(System.currentTimeMillis()) }
}
