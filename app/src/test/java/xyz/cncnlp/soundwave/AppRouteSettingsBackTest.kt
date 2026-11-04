package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 设置子页返回映射回归：与 MainActivity 原分支语义逐条对齐。
 */
class AppRouteSettingsBackTest {

    @Test
    fun themeGoesBackToLook() {
        // r655：设置扁平化，深层页直接返回设置主页。
        assertNull(AppRoute.settingsBackTarget("theme"))
    }

    @Test
    fun downloadsGoesBackToDownload() {
        // r655：设置扁平化，深层页直接返回设置主页。
        assertNull(AppRoute.settingsBackTarget("downloads"))
    }

    @Test
    fun cacheGoesBackToData() {
        // r655：设置扁平化，深层页直接返回设置主页。
        assertNull(AppRoute.settingsBackTarget("cache"))
    }

    @Test
    fun crashGoesBackToSupport() {
        // r655：设置扁平化，深层页直接返回设置主页。
        assertNull(AppRoute.settingsBackTarget("crash"))
    }

    @Test
    fun statsGoesBackToData() {
        // r655：设置扁平化，深层页直接返回设置主页。
        assertNull(AppRoute.settingsBackTarget("stats"))
    }

    @Test
    fun historyGoesBackToData() {
        // r655：设置扁平化，深层页直接返回设置主页。
        assertNull(AppRoute.settingsBackTarget("history"))
    }

    @Test
    fun backupGoesBackToData() {
        // r655：设置扁平化，深层页直接返回设置主页。
        assertNull(AppRoute.settingsBackTarget("backup"))
    }

    @Test
    fun aboutGoesBackToSupport() {
        // r655：设置扁平化，深层页直接返回设置主页。
        assertNull(AppRoute.settingsBackTarget("about"))
    }

    @Test
    fun nullGoesBackToRoot() {
        assertNull(AppRoute.settingsBackTarget(null))
    }

    @Test
    fun scriptsGoesBackToSources() {
        // r655：设置扁平化，深层页直接返回设置主页。
        assertNull(AppRoute.settingsBackTarget("scripts"))
    }

    @Test
    fun smartSourceGoesBackToSources() {
        // r655：设置扁平化，深层页直接返回设置主页。
        assertNull(AppRoute.settingsBackTarget("smart"))
    }

    @Test
    fun rootCategoriesGoBackToRoot() {
        assertNull(AppRoute.settingsBackTarget("account"))
        assertNull(AppRoute.settingsBackTarget("audio"))
        assertNull(AppRoute.settingsBackTarget("lyrics"))
        assertNull(AppRoute.settingsBackTarget("sources"))
        assertNull(AppRoute.settingsBackTarget("play"))
        assertNull(AppRoute.settingsBackTarget("look"))
        assertNull(AppRoute.settingsBackTarget("download"))
        assertNull(AppRoute.settingsBackTarget("support"))
    }

    @Test
    fun flattenedSettingsOptionsDoNotCreateChildRoutes() {
        assertNull(AppRoute.settingsBackTarget("audio_from_play"))
        assertNull(AppRoute.settingsBackTarget("privacy"))
        assertNull(AppRoute.settingsBackTarget("legacy_lyrics_display"))
    }

    @Test
    fun dataGoesBackToRoot() {
        assertNull(AppRoute.settingsBackTarget("data"))
    }
}
