package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.common.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w412dp-h1400dp-xhdpi")
class Stage11MiniTypographyTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 @After fun reset(){FrostedPrefs.resetForTest();MotionPolicy.setAppReduceMotion(rule.activity,false)}
 private fun check(width:Int,dark:Boolean,compact:Boolean,fullWidth:Boolean=compact){
  MotionPolicy.setAppReduceMotion(rule.activity,true)
  FrostedPrefs.resetForTest(page=false,tint=false,dock=false)
  val scales=listOf(1f,1.5f,2f)
  rule.setContent{SoundwaveDesignTheme(isDark=dark){
   Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top=28.dp)){
    for(style in listOf("default","capsule")) for(scale in scales){
     val density=LocalDensity.current
     CompositionLocalProvider(LocalDensity provides Density(density.density,scale)){
      Box(Modifier.width(width.dp)){
       MiniPlayer(song=Song(id="$style-$scale",name="字形${style.take(1)}$scale",singer="歌手$style$scale",source="wy"),playing=false,progressState=remember{mutableStateOf(0.3f)},resolvingState=remember{mutableStateOf(false)},compact=compact,fullWidth=fullWidth,style=style,onClick={},onToggle={})
      }
     }
     Spacer(Modifier.height(10.dp))
    }
   }
  }}
  rule.waitForIdle()
  val errors=mutableListOf<String>();val rows=mutableListOf<String>()
  for(style in listOf("default","capsule")) for(scale in scales){
   for(text in listOf("字形${style.take(1)}$scale","歌手$style$scale")){
    val layouts=mutableListOf<TextLayoutResult>()
    rule.onNodeWithText(text,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
    assertEquals(1,layouts.size);val r=layouts.single();val visibleHeight=rule.onNodeWithText(text,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.height
    rows+="$width,$dark,$compact,$style,$scale,$text,${r.size.height},${r.getLineBottom(0)},${r.didOverflowHeight},$visibleHeight"
    // didOverflowHeight also signals horizontal maxLines overflow (marquee); compare actual vertical metrics instead.
    if(r.getLineBottom(0)>r.size.height+1f || visibleHeight+1f<r.size.height)errors+="$text height=${r.size.height} lineBottom=${r.getLineBottom(0)} visible=$visibleHeight"
   }
  }
  System.getenv("SW_SCREENSHOT_DIR")?.let{dir->
   val f=File(dir);f.mkdirs();File(f,"matrix-$width-$dark-$compact-$fullWidth.csv").writeText(rows.joinToString("\n"))
   val v=rule.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File(f,"matrix-$width-$dark-$compact-$fullWidth.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
  }
  assertTrue("clipped text: $errors",errors.isEmpty())
 }
 @Test fun embedded320Light()=check(320,false,true,false)
 @Test fun embedded412Dark()=check(412,true,true,false)
 @Test fun width320Light()=check(320,false,false)
 @Test fun width412Dark()=check(412,true,false)
 @Test fun detail320Light()=check(320,false,true)
 @Test fun detail412Dark()=check(412,true,true)
}
