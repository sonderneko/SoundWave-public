package xyz.cncnlp.soundwave

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PlayerPresentationPreferencesTest {
    @Test fun modeVolumeSwitchesStayIndependentAcrossRecreation() {
        val prefs=ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("presentation-test",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val first=PlayerPresentationPreferences(prefs)
        assertFalse(first.volumeVisible("default"))
        assertTrue(first.volumeVisible("apple"))
        first.setVolumeVisible("default",true)
        first.setVolumeVisible("apple",false)
        assertTrue(first.volumeVisible("default"))
        assertFalse(first.volumeVisible("apple"))
        val restarted=PlayerPresentationPreferences(prefs)
        assertTrue(restarted.volumeVisible("default"))
        assertFalse(restarted.volumeVisible("apple"))
        restarted.setVolumeVisible("apple",true)
        assertTrue(restarted.volumeVisible("default"))
    }
    @Test fun appleCoverChoicePersistsSeparatelyFromVolumeAndDefaultMode() {
        val prefs=ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("presentation-cover-test",Context.MODE_PRIVATE)
        prefs.edit().clear().putString("playerLayout","apple").putInt("lyricFontSp",24).commit()
        val first=PlayerPresentationPreferences(prefs)
        first.setImmersiveCover("apple",true)
        first.setVolumeVisible("apple",false)
        first.setVolumeVisible("default",true)
        assertEquals("immersive_cover",first.effectiveLayout("apple",first.immersiveCover("apple")))
        val restarted=PlayerPresentationPreferences(prefs)
        assertTrue(restarted.immersiveCover("apple"))
        assertFalse(restarted.immersiveCover("default"))
        assertFalse(restarted.volumeVisible("apple"))
        assertTrue(restarted.volumeVisible("default"))
        restarted.setImmersiveCover("apple",false)
        assertEquals("apple",restarted.effectiveLayout("apple",restarted.immersiveCover("apple")))
        assertFalse(restarted.volumeVisible("apple"))
        assertEquals("apple",prefs.getString("playerLayout",null))
        assertEquals(24,prefs.getInt("lyricFontSp",0))
    }
    @Test fun immersiveAndDefaultLyricsUseIdenticalEffectiveFontAtEverySliderValue() {
        for (font in 10..32) assertEquals(PlayerLyricsSettingsModel.defaultPageFontSp(font),PlayerLyricsSettingsModel.immersiveFontFor(font))
    }
}
