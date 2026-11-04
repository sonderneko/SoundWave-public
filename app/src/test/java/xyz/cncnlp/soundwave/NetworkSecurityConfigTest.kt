package xyz.cncnlp.soundwave

import android.content.pm.ApplicationInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkSecurityConfigTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun manifestDoesNotEnableTheLegacyGlobalCleartextSwitch() {
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertFalse(
            "Manifest must not opt the whole application into cleartext traffic",
            appInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0
        )
    }

    @Test
    fun networkConfigDeniesByDefaultAndAllowsOnlyExactRequiredHosts() {
        val parser = context.resources.getXml(R.xml.network_security_config)
        val cleartextHosts = linkedSetOf<String>()
        var baseCleartext: String? = null
        var domainConfigCount = 0
        var inCleartextDomainConfig = false

        try {
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (event) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                        "base-config" -> {
                            baseCleartext = parser.getAttributeValue(null, "cleartextTrafficPermitted")
                        }
                        "domain-config" -> {
                            domainConfigCount++
                            inCleartextDomainConfig =
                                parser.getAttributeValue(null, "cleartextTrafficPermitted") == "true"
                        }
                        "domain" -> if (inCleartextDomainConfig) {
                            assertEquals("false", parser.getAttributeValue(null, "includeSubdomains"))
                            cleartextHosts += parser.nextText().trim()
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.name == "domain-config") {
                        inCleartextDomainConfig = false
                    }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }

        assertEquals("false", baseCleartext)
        assertEquals(1, domainConfigCount)
        assertEquals(
            setOf("mobilecdn.kugou.com", "mobilecdnbj.kugou.com", "img1.kwcdn.kuwo.cn"),
            cleartextHosts
        )
    }
}
