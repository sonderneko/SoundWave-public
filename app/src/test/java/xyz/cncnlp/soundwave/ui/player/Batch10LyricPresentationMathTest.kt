package xyz.cncnlp.soundwave.ui.player

import org.junit.Assert.*
import org.junit.Test

/** Rendering calculations only. Expectations are frozen from the batch9 row, not token references. */
class Batch10LyricPresentationMathTest {
 @Test fun normalFontRetainsTenSpFloorAndUserScale(){
  for(n in listOf(-1,0,9,10,18,32,34,70)) for(s in listOf(.25f,.7f,1f,1.5f,3f)){
   assertEquals((n.coerceIn(10,34)*s).coerceIn(10f,64f),playerLyricRenderFontSp(false,n,30,s),0f)
  }
  assertEquals(10f,playerLyricRenderFontSp(false,10,30,1f),0f)
 }
 @Test fun immersiveUsesUnifiedFontBounds(){
  // 全页统一（2026-11-04）：沉浸与普通同一字号区间（旧沉浸独立区间 14–56 已摘除）。
  for(n in listOf(0,10,14,30,56,100)) for(s in listOf(.25f,1f,1.5f,3f))
   assertEquals((n.coerceIn(10,34)*s).coerceIn(10f,64f),playerLyricRenderFontSp(true,10,n,s),0f)
 }
 @Test fun gapLineHeightAndInsetsStayEquivalent(){
  for(preview in listOf(false,true)) for(immersive in listOf(false,true)) for(g in listOf(-1,4,8,36,64,100)) for(base in listOf(10f,18f,32f,56f)){
   val gap=if(preview)0 else g.coerceIn(4,36)
   assertEquals(gap,playerLyricPresentationGap(preview,immersive,g,g))
   assertEquals(if(preview)base*1.18f else (base*1.22f+gap*.85f).coerceAtLeast(base+gap*.5f),playerLyricPresentationLineHeight(base,gap,preview),0f)
   assertEquals(if(preview)2f else 4f,playerLyricPresentationVerticalInset(gap,preview,immersive),0f)
  }
 }
 @Test fun emphasisKeepsPlaybackHighlightSeparateFromBrowseFocus(){
  for(focus in listOf(-1,1)) for(distance in listOf(0,1,2,3,Int.MAX_VALUE)) for(focused in listOf(false,true)) for(played in listOf(false,true)){
   val expected=if(focus<0).78f else when{focused->1f;distance==1->.86f;distance==2->.62f;played->.42f;else->.36f}
   assertEquals(expected,playerLyricPresentationAlpha(focus,distance,focused,played),0f)
  }
 }
 @Test fun blurKeepsOriginalNearAndFarBounds(){
  for(b in listOf(-1,0,1,2,4,8,32,100)){
   assertEquals((b.coerceAtLeast(2)*.45f).coerceIn(.8f,3.2f),playerLyricPresentationBlur(b,true),0f)
   assertEquals((b.coerceAtLeast(2)*1.15f).coerceIn(2f,10f),playerLyricPresentationBlur(b,false),0f)
  }
 }
}
