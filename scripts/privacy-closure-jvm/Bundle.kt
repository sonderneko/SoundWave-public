package android.os
class Bundle {val map=mutableMapOf<String,Boolean>();fun putBoolean(k:String,v:Boolean){map[k]=v};fun getBoolean(k:String,d:Boolean=false)=map[k]?:d}
