package xyz.cncnlp.soundwave

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ApiResponseReaderBoundaryTest {
    private class Tracked(val data:ByteArray, val fail:Boolean=false):InputStream() {
        private val delegate=ByteArrayInputStream(data)
        var closed=false
        override fun read():Int { if(fail) throw java.io.IOException("fixture");return delegate.read() }
        override fun close(){ closed=true;delegate.close() }
    }
    private fun read(input:InputStream,limit:Int):ByteArray = ApiResponseReader.readBytes(input,limit)
    @Test fun exactLimitAcceptedAndClosed(){val s=Tracked(byteArrayOf(1,2,3));assertArrayEquals(s.data,read(s,3));assertTrue(s.closed)}
    @Test fun overLimitThrowsAndCloses(){val s=Tracked(byteArrayOf(1,2,3,4));try{read(s,3);fail()}catch(e:java.io.IOException){assertTrue(e.message!!.contains("响应过大"))};assertTrue(s.closed)}
    @Test fun emptyBodyIsEmptyAndClosed(){val s=Tracked(byteArrayOf());assertTrue(read(s,0).isEmpty());assertTrue(s.closed)}
    @Test fun readFailureStillCloses(){val s=Tracked(byteArrayOf(),true);try{read(s,3);fail()}catch(e:java.io.IOException){assertEquals("fixture",e.message)};assertTrue(s.closed)}
    @Test fun limitsAreBytesNotCharacters(){val bytes="旋律".toByteArray(Charsets.UTF_8);val s=Tracked(bytes);assertEquals("旋律",read(s,bytes.size).toString(Charsets.UTF_8));try{read(Tracked(bytes),bytes.size-1);fail()}catch(_:java.io.IOException){}}
}
