package xyz.cncnlp.soundwave

import org.junit.Test
import org.junit.Assert.*
import xyz.cncnlp.soundwave.ui.HomePagerSelection

/** Controlled adapter of the exact stage13 handlers. No Activity/account/player startup. */
internal class LegacyNavigationHarness {
    var tab = Tab.HOME
    var prevTab = Tab.PLAYLISTS
    var lastContentTab = Tab.SETTINGS
    var settingsSub: String? = null
    var searchResult: AppRoute.SearchResult? = null
    var downloadPickSong: Song? = null
    var downloadPickQuality = "320k"
    var searchKw = "keyword"
    var lastBackAt = 0L
    var now = 10000L
    var detailOpen = false
    var plDetailLoading = false
    var searchDockFocus = true
    val homePagerSelection = HomePagerSelection("pl")
    val events = mutableListOf<String>()
    val plDetail: Any? get() = if(detailOpen) this else null
    fun returnToTopLevelTab(target: Tab) {
        val section = homePagerSelection.onRouteReturn(target)
        if(target == Tab.HOME) events += "home:$section"
        events += "return:$target"
        tab = target
    }
    fun closeDetail() { events += "closeDetail"; detailOpen = false }
    fun legacyBack() {

        when {
            // 返回/返回手势先取消最上层临时操作，不能穿透到播放器或退出歌单详情。
            downloadPickSong != null -> { downloadPickSong = null; downloadPickQuality = "" }
            else -> when (AppRoute.backAction(
                tab = tab,
                detailOpen = plDetail != null,
                settingsSub = settingsSub,
                searchResult = searchResult
            )) {
                BackAction.ClosePlayer -> returnToTopLevelTab(prevTab)
                BackAction.CloseDetail -> closeDetail()
                // 冻结的 r655 历史 oracle：旧静态映射恒回根（accept6-backnav 后生产走 SettingsNavStack 弹栈，本复制体保持旧语义作对照）。
                BackAction.CloseSettingsSub -> settingsSub = null
                BackAction.ClearSearch -> { searchResult = null; searchKw = "" }
                BackAction.BackToHome -> when {
                    tab == Tab.SEARCH -> {
                        // 搜索页返回：未搜索时回到上次内容页（主页/歌单/设置），不再提示“再按一次退出”。
                        returnToTopLevelTab(AppRoute.searchBackTarget(lastContentTab))
                    }
                    else -> {
                        val now = this.now
                        if (now - lastBackAt < 1600L) {
                            events += "finish"
                        } else {
                            lastBackAt = now
                            events += "prompt"
                        }
                    }
                }
            }
        }
    }
    fun legacyBottom(nxt: Tab) {
                                if (nxt != Tab.SEARCH && nxt != Tab.PLAYER) lastContentTab = nxt
                                if (nxt == Tab.SEARCH) searchDockFocus = false
                                if (nxt == Tab.HOME) {
                                    val section = homePagerSelection.afterBottomNavigation(nxt, tab, plDetail != null)
                                    events += "home:$section"
                                }
                                tab = nxt
                                settingsSub = null
                                detailOpen = false
                                plDetailLoading = false
    }
    fun snapshot(): List<Any?> = listOf(tab,prevTab,lastContentTab,settingsSub,searchResult,downloadPickSong,downloadPickQuality,searchKw,lastBackAt,detailOpen,plDetailLoading,searchDockFocus,homePagerSelection.section,events.toList())
}

class Stage14NavigationBaselineTest {
    @Test fun originalBackPrioritiesAndEffects() {
        val h=LegacyNavigationHarness().apply { tab=Tab.PLAYER; detailOpen=true; settingsSub="theme"; downloadPickSong=Song(id="1",name="a",singer="s") }
        h.legacyBack(); assertNull(h.downloadPickSong);assertEquals("",h.downloadPickQuality);assertEquals(Tab.PLAYER,h.tab)
        h.legacyBack();assertEquals(Tab.PLAYLISTS,h.tab);assertTrue(h.detailOpen)
        h.legacyBack();assertFalse(h.detailOpen);assertEquals("theme",h.settingsSub)
        h.legacyBack();assertNull(h.settingsSub)
    }
    @Test fun originalSearchReturnsToSavedContentOnlyAfterClearingResult(){
        for(origin in Tab.values()) {
            val h=LegacyNavigationHarness().apply { tab=Tab.SEARCH;lastContentTab=origin; searchResult=AppRoute.SearchResult("q","song","wy") }
            h.legacyBack();assertNull(h.searchResult);assertEquals("",h.searchKw);assertEquals(Tab.SEARCH,h.tab)
            h.legacyBack();assertEquals(AppRoute.searchBackTarget(origin),h.tab);assertFalse(h.events.contains("prompt"))
        }
    }
    @Test fun originalExitUsesStrict1600MillisecondWindowAndOnlyRootBackUpdatesClock(){
        for(delta in listOf(0L,1599L,1600L,1601L)) {
            val h=LegacyNavigationHarness().apply { lastBackAt=10000L;now=10000L+delta }
            h.legacyBack();assertEquals(if(delta<1600L)"finish" else "prompt",h.events.last());assertEquals(if(delta<1600L)10000L else h.now,h.lastBackAt)
        }
    }
    @Test fun originalBottomSelectionPreservesHomeExceptActualRetap(){
        for(current in Tab.values()) for(target in Tab.values()) for(detail in listOf(false,true)) {
            val h=LegacyNavigationHarness().apply { tab=current;detailOpen=detail;plDetailLoading=true;settingsSub="theme" }
            h.legacyBottom(target);assertEquals(target,h.tab);assertNull(h.settingsSub);assertFalse(h.detailOpen);assertFalse(h.plDetailLoading)
            assertEquals(if(target==Tab.HOME&&current==Tab.HOME&&!detail)"hot" else "pl",h.homePagerSelection.section)
            assertEquals(if(target!=Tab.SEARCH&&target!=Tab.PLAYER)target else Tab.SETTINGS,h.lastContentTab)
            assertEquals(target!=Tab.SEARCH,h.searchDockFocus)
        }
    }
}
