package android.content
import java.io.File
open class Context {
 companion object { const val MODE_PRIVATE=0 }
 val stores=mutableMapOf<String,SharedPreferences>()
 open val applicationContext:Context get()=this
 open val filesDir=java.nio.file.Files.createTempDirectory("privacy-test").toFile()
 val packageName="test"
 val packageManager=PackageManager()
 fun getSharedPreferences(name:String,mode:Int)=stores.getOrPut(name){SharedPreferences()}
}
class PackageManager { fun getPackageInfo(n:String,f:Int)=PackageInfo() }
class PackageInfo { val versionName="test" }
class SharedPreferences {
 val all=mutableMapOf<String,Any>()
 fun getBoolean(k:String,d:Boolean)=all[k] as? Boolean ?: d
 fun getString(k:String,d:String?)=all[k] as? String ?: d
 fun edit()=Editor(this)
 class Editor(val p:SharedPreferences) {
  val staged=mutableMapOf<String,Any?>()
  fun putBoolean(k:String,v:Boolean)=apply{staged[k]=v}
  fun putString(k:String,v:String?)=apply{staged[k]=v}
  fun putLong(k:String,v:Long)=apply{staged[k]=v}
  fun putInt(k:String,v:Int)=apply{staged[k]=v}
  fun putFloat(k:String,v:Float)=apply{staged[k]=v}
  fun remove(k:String)=apply{staged[k]=null}
  fun apply(){staged.forEach{(k,v)->if(v==null)p.all.remove(k) else p.all[k]=v}}
 }
}
class Intent(var action:String?=null,var data:android.net.Uri?=null) {
 var type:String?=null
 val extras=mutableMapOf<String,String>()
 fun getStringExtra(k:String)=extras[k]
 fun putExtra(k:String,v:String):Intent=apply{extras[k]=v}
 companion object {const val ACTION_SEND="send";const val EXTRA_TEXT="text";const val EXTRA_SUBJECT="subject"}
}
