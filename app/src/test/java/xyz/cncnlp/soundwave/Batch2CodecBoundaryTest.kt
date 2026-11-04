package xyz.cncnlp.soundwave

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import okhttp3.Headers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34])
class Batch2CodecBoundaryTest {
    private fun unsafeObject(type:Class<*>):Any {
        val unsafe=Class.forName("sun.misc.Unsafe"); val f=unsafe.getDeclaredField("theUnsafe");f.isAccessible=true
        return unsafe.getMethod("allocateInstance",Class::class.java).invoke(f.get(null),type)
    }
    // Stage 13 moved the HTTP adapter out of Bridge; assert the same production codec directly.
    private fun headers(input:String?):Map<*,*> = ScriptFetchCodec.requestHeaders(input)
    private fun response(input:Headers):String = ScriptFetchCodec.responseHeaders(input)
    private fun decode(bytes:ByteArray):Bitmap? = PlayerService::class.java.getDeclaredMethod("decodeNotificationCover",ByteArray::class.java).apply{isAccessible=true}.invoke(unsafeObject(PlayerService::class.java),bytes) as Bitmap?
    private fun png(width:Int,height:Int):ByteArray { val b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);return ByteArrayOutputStream().apply{b.compress(Bitmap.CompressFormat.PNG,100,this);b.recycle()}.toByteArray() }
    @Test fun malformedAndMissingRequestHeadersAreEmpty(){assertTrue(headers("{").isEmpty());assertTrue(headers(null).isEmpty())}
    @Test fun requestHeadersRetainAllowedValuesAndRejectTransportHeaders(){val h=headers("""{"Accept":"application/json","Host":"private.invalid","Connection":"close"}""");assertEquals("application/json",h["Accept"]);assertFalse(h.keys.any{it.toString().equals("Host",true)||it.toString().equals("Connection",true)})}
    @Test fun responseHeadersDropCookiesAndKeepLastValue(){val o=JSONObject(response(Headers.Builder().add("Content-Type","text/plain").add("Set-Cookie","secret").add("ETag","first").add("ETag","last").build()));assertFalse(o.has("Set-Cookie"));assertEquals("text/plain",o.getString("Content-Type"));assertEquals("last",o.getString("ETag"))}
    @Test fun emptyAndMalformedArtReturnNull(){assertNull(decode(byteArrayOf()));assertNull(decode("not an image".toByteArray()))}
    @Test fun smallArtPreservesDimensions(){val b=decode(png(100,80))!!;assertEquals(100,b.width);assertEquals(80,b.height);b.recycle()}
    @Test fun existingPowerOfTwoSamplingIsPreserved(){val b=decode(png(1600,800))!!;assertEquals(800,b.width);assertEquals(400,b.height);b.recycle()}
}
