package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.common.*
import java.io.File

/** Actual shared row/badge/nav components; no provider requests, frost not changed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34], qualifiers="w412dp-h1100dp-xhdpi")
class Batch6ListNavigationTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    @After fun reset() { FrostedPrefs.resetForTest(); MotionPolicy.setAppReduceMotion(rule.activity,false) }
    private val song=Song(id="b6",name="风经过这里",singer="测试歌手",source="wy",duration=180000)
    private fun setup(dark:Boolean, large:Boolean, style:String, clicks:IntArray) {
        MotionPolicy.setAppReduceMotion(rule.activity,large)
        FrostedPrefs.resetForTest(page=false,tint=false,dock=false)
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
        rule.setContent {
            SoundwaveDesignTheme(isDark=dark) {
                val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,if(large) 1.5f else 1f)) {
                    var tab by remember { mutableStateOf(Tab.HOME) }
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        FusedListCard {
                            SongRow(song= song,index=1,flat=true,showLike=true,onLike={clicks[1]++},onMore={clicks[2]++},onLongClick={clicks[3]++},onClick={clicks[0]++})
                            SongRow(song=song.copy(id="playing",name="正在播放"),index=2,compact=true,flat=true,nowPlaying=true,isPlayingNow=false,onClick={})
                            SongRow(song=song.copy(id="multi",name="选中的曲目"),flat=true,multiMode=true,selected=true,detailForeground=MaterialTheme.colorScheme.onSurface,onClick={})
                        }
                        Row(Modifier.padding(20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) { QualityBadge("无损"); NewReleaseBadge(); NowPlayingEqBars(false) }
                        SpaceCompressedStateCard("暂时不可用","保留错误与重试语义",SpaceCompressedState.Error,"重试",{clicks[4]++})
                        Spacer(Modifier.weight(1f))
                        BottomNav(tab=tab,dark=dark,style=style) { tab=it; clicks[5]++ }
                    }
                }
            }
        }
        rule.waitForIdle()
    }
    private fun capture(name:String) {
        val dir=System.getenv("SW_SCREENSHOT_DIR"); Assume.assumeTrue(dir!=null)
        repeat(3) { rule.waitForIdle(); shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(80) }
        val view=rule.activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        File(dir,"$name.png").apply{parentFile.mkdirs()}.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun sample(dark:Boolean,large:Boolean,style:String) {
        val counts=IntArray(6); setup(dark,large,style,counts)
        rule.onNodeWithText("风经过这里").assertIsDisplayed()
        rule.onNodeWithContentDescription("首页").assertIsDisplayed()
        capture("list-nav-$style-${if(dark) "dark" else "light"}-${if(large) "large-reduced" else "normal"}")
        rule.onNodeWithText("风经过这里").performClick()
        rule.onNodeWithContentDescription("喜欢：风经过这里").performClick()
        rule.onNodeWithContentDescription("更多：风经过这里").performClick()
        rule.onNodeWithText("风经过这里").performTouchInput { longClick() }
        rule.onNodeWithText("重试").performClick()
        rule.runOnIdle { assertArrayEquals(intArrayOf(1,1,1,1,1,0),counts) }
    }
    @Test fun classicLight()=sample(false,false,"default")
    @Test fun classicDarkLargeReduced()=sample(true,true,"default")
    @Test fun capsuleDark()=sample(true,false,"capsule")
    @Test fun capsuleLightLargeReduced()=sample(false,true,"capsule")

    private fun cancellation(style:String,reduce:Boolean) {
        val counts=IntArray(6);setup(false,reduce,style,counts)
        capture("cancel-$style-before")
        rule.onNodeWithContentDescription("首页").performTouchInput { down(center); advanceEventTime(30); cancel() }
        rule.mainClock.advanceTimeBy(1000);rule.waitForIdle()
        rule.runOnIdle {assertEquals(0,counts[5])}
        capture("cancel-$style-after")
        // Interrupt the active selection animation with another selection. The final route must win.
        rule.mainClock.autoAdvance=false
        rule.onNodeWithContentDescription("音乐库").performClick();rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithContentDescription("设置").performClick();rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithContentDescription("搜索").performClick();rule.mainClock.advanceTimeBy(1200)
        rule.mainClock.autoAdvance=true;rule.waitForIdle()
        rule.runOnIdle {assertEquals(3,counts[5])}
        capture("rapid-$style-final")
        rule.onNodeWithContentDescription("首页").performClick();rule.waitForIdle()
        rule.runOnIdle {assertEquals(4,counts[5])}
    }
    @Test fun classicPressCancelAndRapidSwitch()=cancellation("default",false)
    @Test fun capsuleReducedPressCancelAndRapidSwitch()=cancellation("capsule",true)
}
