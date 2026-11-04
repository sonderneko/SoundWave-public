package android.content
interface SharedPreferences { fun getString(k:String,d:String):String?; fun edit():Editor
 interface Editor { fun putString(k:String,v:String):Editor; fun putBoolean(k:String,v:Boolean):Editor; fun remove(k:String):Editor; fun commit():Boolean }
}
