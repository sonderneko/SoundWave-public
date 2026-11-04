package xyz.cncnlp.soundwave

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EqualizerPresetsTest {
    @Test
    fun gainsMatchTheLegacyInlineTableVerbatim() {
        // ACCEPT5：增益表从 PlayerService 内联 when 抽到 EqualizerPresets 单一事实源——数值必须逐项不变。
        val legacy = listOf(
            intArrayOf(0, 0, 0, 0, 0),
            intArrayOf(500, 400, 300, 400, 500),
            intArrayOf(700, 500, 300, 500, 700),
            intArrayOf(400, 300, 200, 300, 400),
            intArrayOf(200, 300, 400, 500, 600),
            intArrayOf(300, 300, 300, 300, 300)
        )
        assertEquals(legacy.size, EqualizerPresets.GAINS_MB.size)
        legacy.forEachIndexed { i, gains -> assertArrayEquals("preset $i", gains, EqualizerPresets.gains(i)) }
    }

    @Test
    fun everyPresetHasADescriptionAndClamps() {
        assertEquals(EqualizerPresets.GAINS_MB.size, EqualizerPresets.DESCRIPTIONS.size)
        EqualizerPresets.DESCRIPTIONS.forEach { assert(it.isNotBlank()) }
        assertArrayEquals(EqualizerPresets.GAINS_MB.first(), EqualizerPresets.gains(-1))
        assertArrayEquals(EqualizerPresets.GAINS_MB.last(), EqualizerPresets.gains(99))
    }
}
