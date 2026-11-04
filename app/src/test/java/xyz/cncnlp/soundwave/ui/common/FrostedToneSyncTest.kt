package xyz.cncnlp.soundwave.ui.common

import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.ui.player.CoverToneCache

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrostedToneSyncTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val originalLoader = CoverToneCache.loader

    @Before
    fun reset() {
        CoverToneCache.clearForTest()
        FrostedPrefs.resetForTest()
    }

    @After
    fun restore() {
        CoverToneCache.loader = originalLoader
        CoverToneCache.clearForTest()
        FrostedPrefs.resetForTest()
    }

    @Test
    fun switchingCoverDropsOldToneAndRejectsLateResult() {
        FrostedPrefs.selectCoverForTone("cover-old")
        assertTrue(FrostedPrefs.applyToneForCover("cover-old", Color.Magenta))
        assertEquals(Color.Magenta, FrostedPrefs.tone)

        FrostedPrefs.selectCoverForTone("cover-current")
        assertNull("a previous cover tone must not tint the new cover while sampling", FrostedPrefs.tone)
        assertFalse(FrostedPrefs.applyToneForCover("cover-old", Color.Red))
        assertNull("late result from the previous cover must be ignored", FrostedPrefs.tone)

        assertTrue(FrostedPrefs.applyToneForCover("cover-current", Color.Cyan))
        assertEquals(Color.Cyan, FrostedPrefs.tone)
    }

    @Test
    fun switchingToCachedCoverReusesItsExistingTone() = runBlocking {
        CoverToneCache.loader = { _, _ -> ColorDrawable(0xFF538D70.toInt()) }
        val expected = CoverToneCache.tonesFor(context, "cover-cached")!!.top

        FrostedPrefs.selectCoverForTone("cover-old")
        assertTrue(FrostedPrefs.applyToneForCover("cover-old", Color.Red))
        FrostedPrefs.selectCoverForTone("cover-cached")

        assertEquals(expected, FrostedPrefs.tone)
    }
}
