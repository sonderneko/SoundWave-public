package xyz.cncnlp.soundwave

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.ui.player.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], qualifiers="w412dp-h800dp-xhdpi")
class Round5WordLiftTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val timed = LyricLine(1000,"甲乙",words=listOf(LyricWord(1000,2000,"甲"),LyricWord(2000,3000,"乙")))
    private fun verify(default:Boolean) {
        val time=mutableStateOf(1000L)
        val lift=mutableStateOf(true)
        val karaoke=mutableStateOf(false)
        val reduced=mutableStateOf(false)
        rule.setContent {
            Box(Modifier.padding(30.dp)) {
                if(default) DefaultPlayerLyricText(timed,null,time.value,true,lift.value,karaoke.value,reduced.value,Color.Black,27)
                else PlayerLyricRow(timed,null,0,0,-1,PlayerLyricBrowseState(),time.value,10f,0,true,false,1f,18,28,9,18,2,"#000000","#000000",true,false,false,karaoke.value,lift.value,reduced.value,Alignment.Start,TextAlign.Start,seekAvailable=true,onItemSizeChanged={},onSelect={},onPlay={},onLongPress={})
            }
        }
        rule.waitForIdle()
        fun y(i:Int)=rule.onNodeWithTag("lyric-word-$i",useUnmergedTree=true).fetchSemanticsNode().positionInRoot.y
        val y0=y(0);val y1=y(1)
        rule.runOnIdle { time.value=2000 }
        rule.mainClock.advanceTimeBy(400);rule.waitForIdle()
        assertTrue("first timed word must rise while highlight is off", y(0)<y0-1f)
        assertEquals("second word has not started",y1,y(1),0.2f)
        rule.runOnIdle { reduced.value=true;karaoke.value=true }
        rule.waitForIdle()
        assertEquals("reduced motion disables lift",y0,y(0),0.2f)
        rule.runOnIdle { reduced.value=false;lift.value=false }
        rule.waitForIdle()
        assertEquals("lift switch is independent of highlight",y0,y(0),0.2f)
        rule.runOnIdle { lift.value=true;karaoke.value=false;time.value=3000 }
        rule.mainClock.advanceTimeBy(400);rule.waitForIdle()
        assertTrue(y(1)<y1-1f)
    }
    @Test fun defaultTimedWordsLiftWithoutHighlight()=verify(true)
    @Test fun standardTimedWordsLiftWithoutHighlight()=verify(false)
    @Test fun noWordTimingKeepsPlainFallback() {
        rule.setContent { DefaultPlayerLyricText(LyricLine(1000,"普通歌词"),null,1500,true,true,false,false,Color.Black,27) }
        rule.onNodeWithText("普通歌词").assertExists()
        rule.onNodeWithTag("lyric-word-0").assertDoesNotExist()
        assertFalse(playerShouldLiftCurrentWords(true,false,false))
    }
}
