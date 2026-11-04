package xyz.cncnlp.soundwave

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class Stage11PresentationContractTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 @Test fun normalGeometryAndExpandedHostShareTheSameDelta(){
  val samples=mutableMapOf<String,MiniPlayerPresentation>()
  rule.setContent{SoundwaveDesignTheme(isDark=false){
   for(scale in listOf(1f,1.5f,2f)) for(style in listOf("default","capsule")) for(compact in listOf(false,true)){
    val d=LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density,scale)){
     val p=rememberMiniPlayerPresentation(style,compact)
     SideEffect{samples["$scale-$style-$compact"]=p}
    }
   }
  }}
  rule.waitForIdle()
  for((key,p) in samples){
   assertTrue(key,p.height>=p.baseHeight)
   assertEquals(150.dp+(p.height-62.dp).coerceAtLeast(0.dp),miniPlayerDockHostHeight(p))
   if(key.startsWith("1.0-"))assertEquals(key,p.baseHeight,p.height)
   if(key.startsWith("2.0-"))assertTrue(key,p.height>p.baseHeight)
  }
  assertEquals(12,samples.size)
 }
 @Test fun subtitleKeepsPriorityFallbackAndAlbumRules(){
  val s=Song(id="1",name="曲名",singer="歌手",album=" 专辑 ",source="wy")
  assertEquals("缓冲中…",miniPlayerSubtitle(s,true,true,true,"歌词"))
  assertEquals("歌词",miniPlayerSubtitle(s,false,true,true,"歌词"))
  assertEquals("歌手 · 专辑",miniPlayerSubtitle(s,false,false,true,"歌词"))
  assertEquals("歌手 · 专辑",miniPlayerSubtitle(s,false,true,false,"歌词"))
  assertEquals("歌手 · 专辑",miniPlayerSubtitle(s,false,true,true," "))
  assertEquals("未知歌手",miniPlayerSubtitle(s.copy(singer="",album=" "),false,false,false,""))
 }
}
