package xyz.cncnlp.soundwave
class FakePrefs(val values:MutableMap<String,Any> = mutableMapOf()):android.content.SharedPreferences {
 override fun getString(k:String,d:String)=values[k] as? String ?: d
 var commits=0;var ok=true
 override fun edit():android.content.SharedPreferences.Editor=object:android.content.SharedPreferences.Editor {
  val staged=values.toMutableMap()
  override fun putString(k:String,v:String)=apply { staged[k]=v }
  override fun putBoolean(k:String,v:Boolean)=apply { staged[k]=v }
  override fun remove(k:String)=apply {staged.remove(k)}
  override fun commit():Boolean {commits++;if(ok){values.clear();values.putAll(staged)};return ok}
 }
}
