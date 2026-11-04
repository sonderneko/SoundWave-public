package xyz.cncnlp.soundwave.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import xyz.cncnlp.soundwave.Tab

/** Root-owned Home selection shared by the retained HomePage and app navigation handlers. */
internal object HomeSectionNavigation {
    private val pageSections = listOf("hot", "rank", "pl")

    fun normalize(section: String): String = section.takeIf(pageSections::contains) ?: "hot"

    fun pageForSection(section: String): Int = pageSections.indexOf(normalize(section))

    fun externalPage(selectedPage: Int, pageCount: Int): Int =
        if (pageCount <= 0) 0 else selectedPage.coerceIn(0, pageCount - 1)

    suspend fun syncExternalPager(
        selectedPage: Int,
        pageCount: Int,
        currentPage: Int,
        settledPage: Int,
        isScrollInProgress: Boolean,
        scrollToPage: suspend (Int) -> Unit
    ) {
        val targetPage = externalPage(selectedPage, pageCount)
        if (currentPage != targetPage || settledPage != targetPage || isScrollInProgress) {
            scrollToPage(targetPage)
        }
    }

    fun onPagerSettled(section: String): String = normalize(section)

    /** A route return keeps the last Home browse choice; invalid saved data falls back to Hot. */
    fun onRouteReturn(target: Tab, currentSection: String): String =
        if (target == Tab.HOME) normalize(currentSection) else currentSection

    /** Only selecting Home while already on Home resets the pager; cross-route entries restore it. */
    fun afterBottomNavigation(
        target: Tab,
        currentTab: Tab,
        currentSection: String,
        detailOpen: Boolean
    ): String = when {
        target != Tab.HOME -> currentSection
        currentTab == Tab.HOME && !detailOpen -> "hot"
        else -> onRouteReturn(target, currentSection)
    }
}

/** Single root-owned Home selection used both to seed HomePager and to retain it across routes. */
internal class HomePagerSelection(initialSection: String) {
    var section by mutableStateOf(HomeSectionNavigation.normalize(initialSection))
        private set

    /** Bumped on every Home route entry so the retained composable re-applies the root page. */
    var pagerSyncRevision by mutableIntStateOf(0)
        private set

    /** Root-authoritative page index supplied to HomePage; its PagerState is only a rendered projection. */
    val renderedPageIndex: Int
        get() = HomeSectionNavigation.pageForSection(section)

    fun onSectionSettled(rawSection: String): String {
        section = HomeSectionNavigation.onPagerSettled(rawSection)
        return section
    }

    fun onRouteReturn(target: Tab): String {
        section = HomeSectionNavigation.onRouteReturn(target, section)
        if (target == Tab.HOME) pagerSyncRevision++
        return section
    }

    fun afterBottomNavigation(target: Tab, currentTab: Tab, detailOpen: Boolean): String {
        section = HomeSectionNavigation.afterBottomNavigation(target, currentTab, section, detailOpen)
        if (target == Tab.HOME) pagerSyncRevision++
        return section
    }

    companion object {
        val saveableSaver: Saver<HomePagerSelection, String> = Saver(
            save = { it.section },
            restore = { HomePagerSelection(it) }
        )
    }
}
