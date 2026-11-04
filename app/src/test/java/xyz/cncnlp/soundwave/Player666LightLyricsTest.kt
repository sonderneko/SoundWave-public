package xyz.cncnlp.soundwave
import org.junit.Test
import org.junit.Assert.*
class Player666LightLyricsTest {
 @Test fun lightDefaultsKeepContrast(){
  assertEquals("#151C18",playerThemeLyricHex("#FFFFFF",true,true))
  assertEquals("#646C68",playerThemeLyricHex("#A8A8B3",true,false))
 }
 @Test fun customAndNightStayUnchanged(){
  assertEquals("#12ABEF",playerThemeLyricHex("#12ABEF",true,true))
  assertEquals("#FFFFFF",playerThemeLyricHex("#FFFFFF",false,true))
  assertEquals("#A8A8B3",playerThemeLyricHex("#A8A8B3",false,false))
 }
}
