package xyz.cncnlp.soundwave
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
object ApiClient {
 var baseUrl="https://example.invalid"
 var entered=CountDownLatch(1);var release=CountDownLatch(1);var calls=0
 var preparing=CountDownLatch(1);var prepared=CountDownLatch(1)
 fun normalizeBase(s:String):String {preparing.countDown();check(prepared.await(4,TimeUnit.SECONDS));return s}
 fun reset(){entered=CountDownLatch(1);release=CountDownLatch(1);calls=0}
}
object ShareLinkParser {fun parse(a:String?,b:String?)=SchemeActionHolder.Action.None}
object ExternalFileImport {
 fun actionFor(u:android.net.Uri,t:String?,n:String?)=SchemeActionHolder.Action.None
 fun displayName(c:android.content.Context,u:android.net.Uri)=""
}
