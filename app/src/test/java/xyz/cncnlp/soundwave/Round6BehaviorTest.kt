package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.ui.common.*
import xyz.cncnlp.soundwave.probe.ScriptProbeStartupPolicy as P

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Round6BehaviorTest {
 @Test fun independentDockDoesNotUseDisabledPageTint() {
  for(dark in listOf(false,true)) {
   val base=if(dark) Color(0xFF171B19) else Color(0xFFF5F6F4)
   val neutral=dockChromeFill(base,true,null,dark)
   for(tint in listOf(Color.Red,Color.Blue,Color.Green)) {
    assertNull(dockCoverTint(false,true,tint))
    assertEquals(neutral,dockChromeFill(base,true,dockCoverTint(false,true,tint),dark))
    assertEquals(tint,dockCoverTint(true,true,tint))
    assertNotEquals(neutral,dockChromeFill(base,true,dockCoverTint(true,true,tint),dark))
    assertNull(dockCoverTint(true,false,tint))
    assertEquals(base,dockChromeFill(base,false,tint,dark))
   }
  }
 }
 @Test fun importGateIsPersistentExactlyOnceAndManualSuppresses() {
  val p=RuntimeEnvironment.getApplication().getSharedPreferences("round6-auto",0)
  p.edit().clear().commit()
  assertFalse(P.claimInitial(p,true))
  P.noteImport(p)
  assertFalse(P.claimInitial(p,false))
  assertTrue(P.claimInitial(p,true))
  assertFalse(P.claimInitial(p,true))
  P.noteImport(p);assertFalse(P.claimInitial(p,true))
  p.edit().clear().commit();P.noteManual(p);P.noteImport(p)
  assertFalse(P.claimInitial(p,true))
 }
 @Test fun releaseVersionAndLinkRules() {
  assertTrue(GitHubReleases.compare("v1.0.660","1.0.659")>0)
  assertTrue(GitHubReleases.compare("v1.0.660-beta","1.0.660")<0)
  val raw="""[{"tag_name":"v1.0.661","html_url":"https://evil.example/tag/v1.0.661"},{"tag_name":"v1.0.659","html_url":"https://github.com/projects258036-svg/soundwave-v2/releases/tag/v1.0.659"},{"tag_name":"v1.0.660-beta","prerelease":true,"html_url":"https://github.com/projects258036-svg/soundwave-v2/releases/tag/v1.0.660-beta"}]"""
  assertEquals("v1.0.659",GitHubReleases.parse(raw,false)?.tag)
  assertEquals("v1.0.660-beta",GitHubReleases.parse(raw,true)?.tag)
 }
}
