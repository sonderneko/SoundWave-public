package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptProbeMatrixTest {
    @Test
    fun fullMatrixUsesTwoFixedSongsPerPlatformAcrossAllSevenTiers() {
        val samples = ScriptProbeMatrix.samples(maxPlats = 5)

        assertEquals(10, samples.size)
        assertEquals(listOf("wy", "kw", "tx", "kg", "mg"), ScriptProbeMatrix.platformOrder)
        ScriptProbeMatrix.platformOrder.forEach { platform ->
            assertEquals(2, samples.count { it.platform == platform })
        }
        assertEquals(
            listOf("master", "atmos", "hires", "flac24bit", "flac", "320k", "128k"),
            ScriptProbeMatrix.qualityTiers
        )

        val combinations = samples.flatMap { sample ->
            ScriptProbeMatrix.qualityTiers.map { "${sample.platform}/${sample.name}/$it" }
        }
        assertEquals(70, combinations.size)
        assertEquals(70, combinations.toSet().size)
        assertTrue(ScriptProbeMatrix.samples(maxPlats = 2).all { it.platform in listOf("wy", "kw") })
    }

    @Test
    fun undeclaredScriptFallsBackToEveryPlatformSampleAndTier() {
        listOf(null, "", ScriptProbeMatrix.NO_DECLARATION).forEach { raw ->
            assertNull(ScriptProbeMatrix.parseDeclaredSources(raw))
        }
        val plan = ScriptProbeMatrix.plan(null)

        assertFalse(plan.fromDeclaration)
        assertEquals(10, plan.samples.size)
        assertEquals(70, plan.totalItems)
        ScriptProbeMatrix.platformOrder.forEach { assertEquals(ScriptProbeMatrix.qualityTiers, plan.qualitiesFor(it)) }
    }

    @Test
    fun declaredSourcesDriveEveryPlatformAndEveryDeclaredQuality() {
        val declared = ScriptProbeMatrix.parseDeclaredSources(
            "kw=128k,320k,flac,flac24bit;wy=!;tx=*;kugou=320k,128k,320k;xm=128k;local=*"
        )!!
        val plan = ScriptProbeMatrix.plan(declared)

        assertTrue(plan.fromDeclaration)
        assertEquals(listOf("kw", "tx", "kg"), plan.qualitiesByPlatform.keys.toList())
        // Ordered by engine tier, de-duplicated, none dropped.
        assertEquals(listOf("flac24bit", "flac", "320k", "128k"), plan.qualitiesFor("kw"))
        assertEquals(ScriptProbeMatrix.qualityTiers, plan.qualitiesFor("tx"))
        assertEquals(listOf("320k", "128k"), plan.qualitiesFor("kg"))
        // Every fixed sample of every declared platform (complete traversal, no sampling).
        assertEquals(6, plan.samples.size)
        assertEquals(2 * 4 + 2 * 7 + 2 * 2, plan.totalItems)
        assertTrue("wy" in plan.skipped.keys)
        assertTrue("xm" in plan.skipped.keys)
        assertFalse("local" in plan.skipped.keys)
        assertEquals(listOf("mg"), plan.notDeclared)

        val coverage = ScriptProbeMatrix.coverageText(plan) { it }
        assertTrue(coverage.contains("按脚本声明完整覆盖"))
        assertTrue(coverage.contains("共 26 项"))
        assertTrue(coverage.contains("脚本未声明：mg"))
    }

    @Test
    fun platformAliasesMatchRuntimeLookup() {
        assertEquals("wy", ScriptProbeMatrix.normalizePlatform("NeteaseMusic"))
        assertEquals("tx", ScriptProbeMatrix.normalizePlatform("qq"))
        assertEquals("mg", ScriptProbeMatrix.normalizePlatform("咪咕"))
        assertEquals("kw", ScriptProbeMatrix.normalizePlatform("kw"))
    }

    @Test
    fun emptyDeclaredQualityListIsReportedNotSilentlyDropped() {
        val plan = ScriptProbeMatrix.plan(ScriptProbeMatrix.parseDeclaredSources("wy=;kw=128k"))
        assertEquals(listOf("kw"), plan.qualitiesByPlatform.keys.toList())
        assertTrue(plan.skipped["wy"].orEmpty().contains("音质列表为空"))
    }
}
