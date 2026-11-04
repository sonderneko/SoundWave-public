package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.ui.common.*
import java.io.File

/** Executes the production modifier on the supported API31 fallback, not an imitation blur. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[31],qualifiers="w412dp-h800dp-xhdpi")
class Batch7DockFrostTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 @After fun reset(){FrostedPrefs.resetForTest()}
 @Test fun independentSwitchMatrixAndBackdrop(){
  var backdrop by mutableStateOf(Color(0xFF404040))
  FrostedPrefs.resetForTest();FrostedPrefs.tone=Color.Red
  rule.setContent{Box(Modifier.fillMaxSize().background(backdrop)){
   Box(Modifier.fillMaxWidth().height(300.dp).padding(20.dp).dockGlass(RoundedCornerShape(22.dp),Color.White,false))
  }}
  val records=ArrayList<String>()
  fun capture(name:String):Int{
   rule.waitForIdle();val v=rule.activity.window.decorView
   val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
   val pixel=b.getPixel(b.width/2,b.height/5)
   val dir=System.getenv("SW_SCREENSHOT_DIR")
   if(dir!=null)File(dir,"$name.png").apply{parentFile.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
   records.add("$name\t${Integer.toHexString(pixel)}")
   return pixel
  }
  for(page in listOf(false,true))for(tint in listOf(false,true))for(dock in listOf(false,true)){
   rule.runOnIdle{FrostedPrefs.pageFrosted=page;FrostedPrefs.coverTint=tint;FrostedPrefs.dockFrosted=dock}
   val pixel=capture("dock-p$page-t$tint-d$dock")
   val r=android.graphics.Color.red(pixel);val g=android.graphics.Color.green(pixel);val b=android.graphics.Color.blue(pixel)
   if(page&&tint&&dock){assertTrue("explicit cover tint must be visible",r>g&&r>b)}else{assertEquals(r,g);assertEquals(g,b)}
  }
  rule.runOnIdle{FrostedPrefs.pageFrosted=false;FrostedPrefs.coverTint=true;FrostedPrefs.dockFrosted=true;backdrop=Color.Red}
  val translucent=capture("dock-only-red-backdrop")
  assertTrue(android.graphics.Color.red(translucent)>android.graphics.Color.green(translucent))
  rule.runOnIdle{FrostedPrefs.dockFrosted=false}
  assertEquals(android.graphics.Color.WHITE,capture("dock-off-red-backdrop"))
  System.getenv("SW_SCREENSHOT_DIR")?.let{File(it,"dock-matrix.tsv").writeText(records.joinToString("\n"))}
 }
 @Test fun pageDisabledNeverInjectsCoverToneAcrossThemes(){
  for(dark in listOf(false,true))for(tone in listOf(Color.Red,Color.Blue,Color.Green))for(tint in listOf(false,true)){
   val base=if(dark)Color(0xFF191C19)else Color.White
   val allowed=dockCoverTint(false,tint,tone);assertNull(allowed)
   assertEquals(dockChromeFill(base,true,null,dark),dockChromeFill(base,true,allowed,dark))
   assertEquals(base,dockChromeFill(base,false,tone,dark))
   assertEquals(tone,dockCoverTint(true,true,tone));assertNull(dockCoverTint(true,false,tone))
  }
 }
 @Test fun dockPreferenceDoesNotMutatePageOrTint(){
  val prefs=rule.activity.getSharedPreferences("batch7-test",0);prefs.edit().clear().commit()
  for(page in listOf(false,true))for(tint in listOf(false,true)){
   FrostedPrefs.setPage(prefs,page);FrostedPrefs.setTint(prefs,tint)
   for(dock in listOf(true,false)){
    FrostedPrefs.setDock(prefs,dock)
    assertEquals(page,FrostedPrefs.pageFrosted);assertEquals(tint,FrostedPrefs.coverTint)
    assertEquals(page,prefs.getBoolean(FrostedPrefs.KEY_PAGE,!page));assertEquals(tint,prefs.getBoolean(FrostedPrefs.KEY_TINT,!tint));assertEquals(dock,prefs.getBoolean(FrostedPrefs.KEY_DOCK,!dock))
   }
  }
 }
 @Test fun platformBranchContractDoesNotClaimHardwareRendering(){
  for(sdk in listOf(28,31,32,34)){
   assertEquals(false to 1f,dockGlassPlan(false,sdk))
   assertEquals(if(sdk>=32)true to 0.82f else false to 0.94f,dockGlassPlan(true,sdk))
  }
 }
}
