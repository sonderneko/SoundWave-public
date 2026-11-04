package android.net
class Uri private constructor(val raw:String) {
 val parsed=java.net.URI(raw)
 val scheme:String? get()=parsed.scheme
 val host:String? get()=parsed.host
 fun getQueryParameter(k:String):String?=parsed.rawQuery?.split('&')?.map{it.split('=',limit=2)}?.firstOrNull{it[0]==k}?.getOrNull(1)
 companion object{fun parse(s:String)=Uri(s)}
}
