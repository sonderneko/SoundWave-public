package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Test

/** r656：取链顺序只由平台脚本链路 + 平台顺序 + 脚本页顺序推导，不依赖已移除的「激活脚本 / 跨脚本备用顺序」。 */
class SmartSourcePlatformChainOrderTest {
    private fun sc(id: String, enabled: Boolean = true, code: String = "x") = Script(id = id, name = id, code = code, enabled = enabled)

    @Test
    fun sourceChainFirstThenOtherPlatformChainsThenScriptPageOrder() {
        val scripts = listOf(sc("a"), sc("b"), sc("c"), sc("d"), sc("e"))
        val ordered = SmartSourceStore.orderScriptsByPlatformChains(
            sourceChain = listOf("c", "a"),
            otherPlatformChains = listOf(listOf("e", "c"), listOf("b")),
            scripts = scripts
        )
        assertEquals(listOf("c", "a", "e", "b", "d"), ordered.map { it.id })
    }

    @Test
    fun disabledAndCodelessScriptsNeverJoinTheChain() {
        val scripts = listOf(sc("a", enabled = false), sc("b", code = ""), sc("c"))
        val ordered = SmartSourceStore.orderScriptsByPlatformChains(listOf("a", "b", "c"), emptyList(), scripts)
        assertEquals(listOf("c"), ordered.map { it.id })
    }

    @Test
    fun withoutAnyChainTheScriptPageOrderIsUsed() {
        val scripts = listOf(sc("z"), sc("y"), sc("x"))
        assertEquals(listOf("z", "y", "x"), SmartSourceStore.orderScriptsByPlatformChains(emptyList(), emptyList(), scripts).map { it.id })
    }
}
