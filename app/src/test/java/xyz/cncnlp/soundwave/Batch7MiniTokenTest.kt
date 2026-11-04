package xyz.cncnlp.soundwave
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Test
import org.junit.Assert.*
class Batch7MiniTokenTest {
 @Test fun compactGeometryAndTypographyPreserved(){
  val t=ThemeTokens.MiniPlayer
  assertEquals(22.dp,t.Radius);assertEquals(999.dp,t.CapsuleRadius)
  assertEquals(48.dp,t.Cover);assertEquals(40.dp,t.CompactCover);assertEquals(10.dp,t.CapsuleCoverInset)
  assertEquals(13.sp,t.CompactTitleSize);assertEquals(17.sp,t.CompactTitleLine)
  assertEquals(14.sp,t.CapsuleTitleSize);assertEquals(18.sp,t.CapsuleTitleLine)
  assertEquals(15.sp,t.TitleSize);assertEquals(21.sp,t.TitleLine)
  assertEquals(36.dp,t.CompactPlayTarget);assertEquals(42.dp,t.PlayTarget)
  assertEquals(2.dp,t.ProgressStroke);assertEquals(9000,t.CoverSpinMillis);assertEquals(0.75f,t.ContentPressedAlpha)
 }
 @Test fun semanticRolesFollowTheGivenScheme(){
  for(dark in listOf(false,true)){
   val c=soundwaveDesignScheme(dark);val t=ThemeTokens.MiniPlayer
   assertEquals(c.onSurface,t.ink(c));assertEquals(c.onSurfaceVariant,t.muted(c));assertEquals(c.surface,t.surface(c))
   assertEquals(c.primary,t.progress(c));assertEquals(c.surfaceVariant,t.progressTrack(c));assertEquals(c.onBackground,t.playFill(c));assertEquals(c.background,t.playInk(c))
   val changed=c.copy(primary=androidx.compose.ui.graphics.Color.Blue,onSurface=androidx.compose.ui.graphics.Color.Green)
   assertEquals(changed.primary,t.progress(changed));assertEquals(changed.onSurface,t.ink(changed))
  }
 }
}
