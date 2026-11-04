package xyz.cncnlp.soundwave

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * H3（已转默认）策略边界：
 * - fake-IP 占位集（198.18/15 ∪ 锚定 fc00::/18）→ 系统栈直发许可（唯一出口，服务端中转已退役）；
 * - 真实私网/v6-only 占位/混合应答 → 恒拒，SSRF 边界不放松；
 * - 真实公网应答 → pinned 直连，与占位路径互斥。
 */
class ScriptNetworkPolicyH3Test {
    private fun ip(value: String): InetAddress = InetAddress.getByName(value)

    @Test
    fun placeholderAnswersAllowSystemStackDirect() {
        val result = ScriptNetworkPolicy.inspectScriptUrl("https://api.example.com/music") {
            arrayOf(ip("198.18.0.23"))
        }
        assertTrue(result.systemStackDirect)
        assertNull(result.validatedTarget)
    }

    @Test
    fun anchoredMixedPlaceholderSetAlsoAllowsSystemStackDirect() {
        val mixed = ScriptNetworkPolicy.inspectScriptUrl("https://api.example.com/x") {
            arrayOf(ip("198.18.1.5"), ip("fc00::9"))
        }
        assertTrue(mixed.systemStackDirect)
        assertNull(mixed.validatedTarget)
    }

    @Test
    fun realPrivateAnswersAreAlwaysRejected() {
        listOf("192.168.1.2", "10.0.0.5", "127.0.0.1", "169.254.169.254").forEach { address ->
            val result = ScriptNetworkPolicy.inspectScriptUrl("https://cdn.example.com/file") {
                arrayOf(ip(address))
            }
            assertFalse("真实私网恒拒: $address", result.systemStackDirect)
            assertNull(result.validatedTarget)
        }
    }

    @Test
    fun v6OnlyPlaceholderWithoutV4AnchorIsRejected() {
        val result = ScriptNetworkPolicy.inspectScriptUrl("https://cdn.example.com/file") {
            arrayOf(ip("fc00::1"))
        }
        assertFalse(result.systemStackDirect)
        assertNull(result.validatedTarget)
    }

    @Test
    fun mixedPlaceholderAndPublicAnswersAreRejected() {
        val result = ScriptNetworkPolicy.inspectScriptUrl("https://cdn.example.com/file") {
            arrayOf(ip("198.18.0.7"), ip("93.184.216.34"))
        }
        assertFalse(result.systemStackDirect)
        assertNull(result.validatedTarget)
    }

    @Test
    fun publicAnswersKeepPinnedDirect() {
        val result = ScriptNetworkPolicy.inspectScriptUrl("https://cdn.example.com/file") {
            arrayOf(ip("93.184.216.34"))
        }
        assertFalse(result.systemStackDirect)
        assertTrue(result.validatedTarget != null)
    }
}
