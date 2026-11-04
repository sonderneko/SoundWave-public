package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ui.HomeSectionNavigation

/**
 * AppRoot 单一返回语义（任务 8）：页面返回不靠多个 Boolean 竞争，历史优先级明确。
 *
 * 当前阶段只定义路线模型与「返回优先级」契约，作为现有 tab/plDetail/settingsSub/
 * show* 状态桥接的目标形态。每次只迁移一个 route，保留旧状态桥接，不进行全量重写。
 *
 * BackHandler 先在父层关闭下载选择器；其余返回优先级（从高到低）是：
 * 1. 播放器全屏（tab == PLAYER）→ 回 detailOriginTab / prevTab
 * 2. 歌单/榜单/专辑详情（plDetail != null）→ 关详情，回 detailOriginTab
 * 3. 设置子页（settingsSub != null）→ 回父级分类；顶层分类回设置主页
 * 4. 搜索结果态（搜索页已搜索）→ 清空结果回搜索首页
 * 5. 主 Tab → 再按一次退出
 *
 * 禁止出现：设置子页抢走「关闭歌单详情」的返回；弹层打开时返回先关弹层。
 */
sealed interface AppRoute {

    /** 主 Tab：首页 / 搜索 / 歌单库 / 设置。 */
    data class MainTab(val tab: Tab) : AppRoute

    /** 播放器全屏（独立于主 Tab 的顶层路由）。 */
    data object Player : AppRoute

    /** 歌单/榜单/专辑详情。origin 记录来源 Tab，返回时回到该 Tab。 */
    data class Detail(
        val playlistId: String,
        val originTab: Tab
    ) : AppRoute

    /** 设置分类及深层子页；具体父级由 settingsBackTarget 映射。 */
    data class SettingsSub(val sub: String) : AppRoute

    /** 搜索已提交（结果态）。 */
    data class SearchResult(val kw: String, val mode: String, val source: String) : AppRoute

    companion object {
        /**
         * 返回优先级表：给出当前所有导航状态，返回「下一步应该关掉什么」。
         * 这是纯函数，便于写回归测试；现有 AppRoot BackHandler 应逐步改为调用它。
         *
         * @return 需要执行的动作描述：null 表示返回主 Tab 退出语义。
         */
        fun backAction(
            tab: Tab,
            detailOpen: Boolean,
            settingsSub: String?,
            searchResult: AppRoute.SearchResult?
        ): BackAction = when {
            tab == Tab.PLAYER -> BackAction.ClosePlayer
            detailOpen -> BackAction.CloseDetail
            settingsSub != null -> BackAction.CloseSettingsSub
            tab == Tab.SEARCH && searchResult != null -> BackAction.ClearSearch
            else -> BackAction.BackToHome
        }

        /**
         * 设置深层页返回目标：返回「父级分类」或 null（回到设置根）。纯映射，便于回归测试。
         */
        fun settingsBackTarget(sub: String?): String? = when (sub) {
            // r655：设置按设计稿扁平化——所有详情页与深层页都直接从设置主页进入，返回即回设置主页。
            else -> null
        }

        /** Returning from an idle search route restores a content tab, never a transient route. */
        fun searchBackTarget(lastContentTab: Tab): Tab = when (lastContentTab) {
            Tab.HOME, Tab.PLAYLISTS, Tab.SETTINGS -> lastContentTab
            else -> Tab.HOME
        }

        /** Search deep links from the player restore its saved non-transient content route. */
        internal fun searchEntryDecision(
            currentTab: Tab,
            previousTab: Tab,
            lastContentTab: Tab
        ): SearchEntryDecision {
            if (currentTab != Tab.PLAYER) {
                return SearchEntryDecision(
                    previousTab = currentTab,
                    lastContentTab = if (currentTab == Tab.SEARCH) lastContentTab else currentTab
                )
            }

            val returnTab = previousTab.takeIf(::isSearchReturnTab)
                ?: lastContentTab.takeIf(::isSearchReturnTab)
                ?: Tab.HOME
            return SearchEntryDecision(previousTab = returnTab, lastContentTab = returnTab)
        }

        private fun isSearchReturnTab(tab: Tab): Boolean = when (tab) {
            Tab.HOME, Tab.PLAYLISTS, Tab.SETTINGS -> true
            else -> false
        }
    }
}

internal data class SearchEntryDecision(
    val previousTab: Tab,
    val lastContentTab: Tab
)

/** A player entry keeps its current route underneath; re-entry preserves the saved non-player route. */
internal data class PlayerEntryState(
    val previousTab: Tab,
    val showLyrics: Boolean
)

internal fun playerEntryState(
    currentTab: Tab,
    savedPreviousTab: Tab = currentTab
): PlayerEntryState = PlayerEntryState(
    previousTab = if (currentTab == Tab.PLAYER) {
        savedPreviousTab.takeIf { it != Tab.PLAYER } ?: Tab.HOME
    } else currentTab,
    showLyrics = false
)

/** 返回动作：AppRoot 据此执行对应状态变更，不再靠多个 if 竞争。 */
enum class BackAction {
    ClosePlayer,      // 关播放器全屏 → 回 detailOriginTab/prevTab
    CloseDetail,      // 关歌单/榜单/专辑详情 → 回 detailOriginTab
    CloseSettingsSub, // 退回设置父级分类/根页
    ClearSearch,      // 清空搜索结果 → 搜索首页
    BackToHome        // 主 Tab 内：再按一次退出 / 回 lastContentTab
}

/** AppRoot 与 HomePage 共用的根分区 reducer；Pager 手势和 route handlers 必须写入同一状态。 */
internal fun homeSectionOnRouteReturn(target: Tab, currentSection: String): String =
    HomeSectionNavigation.onRouteReturn(target, currentSection)

/** Cross-route entry restores the saved section; only a re-tap while already on Home returns to Hot. */
internal fun homeSectionAfterBottomNavigation(
    target: Tab,
    currentTab: Tab,
    currentSection: String,
    detailOpen: Boolean
): String = HomeSectionNavigation.afterBottomNavigation(target, currentTab, currentSection, detailOpen)
