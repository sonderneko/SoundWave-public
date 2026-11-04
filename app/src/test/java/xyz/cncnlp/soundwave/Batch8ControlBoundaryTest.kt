package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Test
import org.junit.Assert.*

class Batch8ControlBoundaryTest {
 @Test fun tapClampsAtHitAreaBoundaries(){
  assertEquals(0f,PlayerSliderMath.tapFraction(-20f,200f),0f)
  assertEquals(0.5f,PlayerSliderMath.tapFraction(100f,200f),0f)
  assertEquals(1f,PlayerSliderMath.tapFraction(240f,200f),0f)
 }
 @Test fun incrementalDragUsesLatestLocalFraction(){
  val first=PlayerSliderMath.dragFraction(0.2f,40f,200f)
  assertEquals(0.4f,first,0.00001f)
  assertEquals(0.7f,PlayerSliderMath.dragFraction(first,60f,200f),0.00001f)
  assertEquals(0f,PlayerSliderMath.dragFraction(first,-300f,200f),0f)
  assertEquals(1f,PlayerSliderMath.dragFraction(first,300f,200f),0f)
 }
 @Test fun nonpositiveWidthRetainsDragFraction(){
  assertEquals(0.4f,PlayerSliderMath.dragFraction(0.4f,40f,0f),0f)
  assertEquals(0.4f,PlayerSliderMath.dragFraction(0.4f,40f,-1f),0f)
 }
 @Test fun existingEmptyTapMathIsNotNewInputPolicy(){
  // Real hit areas have positive width. Extraction preserves old IEEE result, not a new seek decision.
  assertTrue(PlayerSliderMath.tapFraction(0f,0f).isNaN())
  assertEquals(1f,PlayerSliderMath.tapFraction(1f,0f),0f)
 }
 @Test fun currentGeometryAndTypefaceUnitsArePreserved(){
  assertEquals(28.dp,ThemeTokens.PlayerControls.HorizontalPadding);assertEquals(20.dp,ThemeTokens.PlayerControls.Progress.HitHeight)
  assertEquals(6.dp,ThemeTokens.PlayerControls.Progress.TrackHeight);assertEquals(8.dp,ThemeTokens.PlayerControls.Progress.DragTrackHeight)
  assertEquals(14.dp,ThemeTokens.PlayerControls.Progress.Thumb);assertEquals(16.dp,ThemeTokens.PlayerControls.Progress.DragThumb);assertEquals(11.sp,ThemeTokens.PlayerControls.Progress.TimeSize)
  assertEquals(52.dp,ThemeTokens.PlayerControls.Transport.SkipTarget);assertEquals(64.dp,ThemeTokens.PlayerControls.Transport.PlayTarget);assertEquals(30.dp,ThemeTokens.PlayerControls.Transport.ButtonGap)
  assertEquals(30.dp,ThemeTokens.PlayerControls.Volume.RowHeight);assertEquals(24.dp,ThemeTokens.PlayerControls.Volume.HitHeight);assertEquals(10.dp,ThemeTokens.PlayerControls.Volume.Thumb);assertEquals(12.dp,ThemeTokens.PlayerControls.Volume.PressedThumb)
  assertEquals(46.dp,ThemeTokens.PlayerControls.BottomAction.Target);assertEquals(25.dp,ThemeTokens.PlayerControls.BottomAction.Icon)
 }
 @Test fun stageColorsRemainDistinctFromMiniAndLyricSchemes(){
  assertEquals(Color.White,ThemeTokens.PlayerControls.Ink)
  assertEquals(Color.White.copy(alpha=0.22f),ThemeTokens.PlayerControls.Progress.track(true));assertEquals(Color(0x22000000),ThemeTokens.PlayerControls.Progress.track(false))
  assertEquals(Color.White.copy(alpha=0.85f),ThemeTokens.PlayerControls.Progress.time(true));assertEquals(Color(0xFF5A5A68),ThemeTokens.PlayerControls.Progress.time(false))
  assertEquals(0.18f,ThemeTokens.PlayerControls.Volume.TrackAlpha,0f);assertEquals(0.62f,ThemeTokens.PlayerControls.Volume.LowIconAlpha,0f);assertEquals(0.82f,ThemeTokens.PlayerControls.Volume.HighIconAlpha,0f)
 }
}
