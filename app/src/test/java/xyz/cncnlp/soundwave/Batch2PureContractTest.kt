package xyz.cncnlp.soundwave

import java.io.ByteArrayInputStream
import java.io.IOException
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.ui.settings.SettingsPressDeepenMs
import xyz.cncnlp.soundwave.ui.settings.SettingsPressDeepenTintMs

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Batch2PureContractTest {
    private class Tracked(bytes:ByteArray):ByteArrayInputStream(bytes) {
        var closed=false
        override fun close(){closed=true;super.close()}
    }
    @Test fun exactByteLimitIsAcceptedAndStreamClosed(){val s=Tracked(ByteArray(2*1024*1024){65});assertEquals(2*1024*1024,ScriptFetchCodec.readLimited(s).length);assertTrue(s.closed)}
    @Test fun excessiveUtf8BytesAreRejectedAndClosed(){val s=Tracked("中".repeat(699051).toByteArray());try{ScriptFetchCodec.readLimited(s);fail("must reject")}catch(e:IOException){assertEquals("脚本响应超过 2MB",e.message)};assertTrue(s.closed)}
    @Test fun emptyStreamAndReadFailureClose(){val s=Tracked(byteArrayOf());assertEquals("",ScriptFetchCodec.readLimited(s));assertTrue(s.closed);var closed=false;val broken=object:java.io.InputStream(){override fun read():Int=throw IOException("read");override fun close(){closed=true}};try{ScriptFetchCodec.readLimited(broken);fail("expected")}catch(e:IOException){assertEquals("read",e.message)};assertTrue(closed)}
    @Test fun callbackQuotesCapabilityBodyAndHeadersWithoutChangingContract(){val cap="key\"\\\n";val text="a\"\\\n雪</script>";val headers="{\"ETag\":\"x\"}";val js=ScriptFetchCodec.callback(cap,503,text,headers);val args=JSONArray("["+js.substringAfter("__swDispatch(").substringBeforeLast(");}catch")+"]");assertEquals(cap,args.getString(0));assertEquals(503,args.getInt(1));assertEquals(text,args.getString(2));assertEquals(headers,args.getString(3))}
    @Test fun settingsLayoutRemainsSame(){assertEquals(64.dp,ThemeTokens.Settings.RowMinHeight);assertEquals(7.dp,ThemeTokens.Settings.RowHorizontalPadding);assertEquals(12.dp,ThemeTokens.Settings.RowVerticalPadding);assertEquals(30.dp,ThemeTokens.Settings.IconWidth);assertEquals(34.dp,ThemeTokens.Settings.IconHeight);assertEquals(21.dp,ThemeTokens.Settings.IconSize)}
    @Test fun settingsPressContractsKeepModeDistinctions(){assertEquals(220,SettingsPressDeepenMs);assertEquals(650,SettingsPressDeepenTintMs);assertEquals(180,ThemeTokens.Settings.ReleaseMs);assertEquals(.04f,ThemeTokens.Settings.initialPressAlpha(false,false));assertEquals(.06f,ThemeTokens.Settings.initialPressAlpha(true,false));assertEquals(.10f,ThemeTokens.Settings.heldPressAlpha(false,false));assertEquals(.24f,ThemeTokens.Settings.heldPressAlpha(true,true));assertNotEquals(ThemeTokens.Settings.line(false),ThemeTokens.Settings.line(true))}
}
