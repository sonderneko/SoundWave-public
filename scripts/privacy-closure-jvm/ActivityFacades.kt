package xyz.cncnlp.soundwave
import android.content.Intent
import android.content.Context
import android.os.Bundle
class ScriptRuntime
object ScriptRuntimeHolder{fun get(c:Context)=ScriptRuntime()}
open class ComponentActivity:Context() {
 @JvmField var intent=Intent()
 val window=Window()
 open fun onCreate(b:Bundle?){}
 open fun onNewIntent(i:Intent){}
 open fun onSaveInstanceState(b:Bundle){}
 fun setIntent(i:Intent){intent=i}
 fun setContent(block:()->Unit){}
}
class Window{fun setSoftInputMode(i:Int){}}
fun requestNotificationPermissionIfNeeded(c:Context){}
fun AppRoot(r:ScriptRuntime){}
