package xyz.cncnlp.soundwave.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StylePreferenceSyncTest {
    @Test
    fun canonicalRoundTripNormalizesTagsAndPreservesMultiplePlatforms() {
        val raw = """
            {
              "WY": {"tags": [" 华语 ", "摇滚", "华语", "", "民谣"], "enabled": false, "modifiedAt": 120, "version": 3},
              "kg": {"tags": ["电子"], "enabled": true, "modifiedAt": 240, "version": 7}
            }
        """.trimIndent()

        val canonical = StylePreferenceSync.canonical(raw)
        val decoded = StylePreferenceSync.decode(canonical)

        assertEquals(
            mapOf(
                "wy" to StylePreferenceSync.Selection(listOf("华语", "摇滚", "民谣"), false, 120L, 3L),
                "kg" to StylePreferenceSync.Selection(listOf("电子"), true, 240L, 7L)
            ),
            decoded
        )
        assertEquals(canonical, StylePreferenceSync.canonical(canonical))
    }

    @Test
    fun mergeUsesNewerTimestampRegardlessOfWhichSideWins() {
        val local = """{"wy":{"tags":["local"],"enabled":true,"modifiedAt":200,"version":2}}"""
        val remoteNewer = """{"wy":{"tags":["remote"],"enabled":false,"modifiedAt":300,"version":9}}"""
        val localNewer = """{"wy":{"tags":["local-newer"],"enabled":true,"modifiedAt":400,"version":1}}"""
        val remoteOlder = """{"wy":{"tags":["remote-older"],"enabled":false,"modifiedAt":300,"version":99}}"""

        assertEquals(
            StylePreferenceSync.Selection(listOf("remote"), false, 300L, 9L),
            StylePreferenceSync.decode(StylePreferenceSync.merge(local, remoteNewer))["wy"]
        )
        assertEquals(
            StylePreferenceSync.Selection(listOf("local-newer"), true, 400L, 1L),
            StylePreferenceSync.decode(StylePreferenceSync.merge(localNewer, remoteOlder))["wy"]
        )
    }

    @Test
    fun equalTimestampFavorsInitiatingLocalEvenWhenRemoteVersionIsHigher() {
        val local = """{"wy":{"tags":["local"],"enabled":true,"modifiedAt":500,"version":1}}"""
        val remote = """{"wy":{"tags":["remote"],"enabled":false,"modifiedAt":500,"version":999}}"""

        assertEquals(
            StylePreferenceSync.Selection(listOf("local"), true, 500L, 1L),
            StylePreferenceSync.decode(StylePreferenceSync.merge(local, remote))["wy"]
        )
    }

    @Test
    fun eitherLegacySelectionWithoutTimestampFavorsInitiatingLocal() {
        val timestampedLocal = """{"wy":{"tags":["local"],"enabled":false,"modifiedAt":800,"version":8}}"""
        val legacyRemote = """{"wy":{"tags":["legacy-remote"],"enabled":true,"version":99}}"""
        val legacyLocal = """{"wy":{"tags":["legacy-local"],"enabled":true,"version":88}}"""
        val timestampedRemote = """{"wy":{"tags":["remote"],"enabled":false,"modifiedAt":900,"version":9}}"""

        assertEquals(
            StylePreferenceSync.Selection(listOf("local"), false, 800L, 8L),
            StylePreferenceSync.decode(StylePreferenceSync.merge(timestampedLocal, legacyRemote))["wy"]
        )
        assertEquals(
            StylePreferenceSync.Selection(listOf("legacy-local"), true, 0L, 88L),
            StylePreferenceSync.decode(StylePreferenceSync.merge(legacyLocal, timestampedRemote))["wy"]
        )
    }

    @Test
    fun absentPlatformIsAddedWithoutOverwritingOtherPlatform() {
        val local = """{"wy":{"tags":["华语"],"enabled":true,"modifiedAt":100,"version":1}}"""
        val remote = """{"kg":{"tags":["电子"],"enabled":false,"modifiedAt":200,"version":2}}"""

        assertEquals(
            mapOf(
                "wy" to StylePreferenceSync.Selection(listOf("华语"), true, 100L, 1L),
                "kg" to StylePreferenceSync.Selection(listOf("电子"), false, 200L, 2L)
            ),
            StylePreferenceSync.decode(StylePreferenceSync.merge(local, remote))
        )
    }

    @Test
    fun clearAndDisableAreExplicitSelectionsNotMissingValues() {
        val previous = StylePreferenceSync.Selection(listOf("华语", "摇滚"), enabled = true, modifiedAt = 100L, version = 4L)

        val clearedAndDisabled = StylePreferenceSync.edited(previous, emptyList(), enabled = false, nowMillis = 150L)

        assertEquals(StylePreferenceSync.Selection(emptyList(), enabled = false, modifiedAt = 150L, version = 5L), clearedAndDisabled)
        assertEquals(
            clearedAndDisabled,
            StylePreferenceSync.decode(StylePreferenceSync.encode(mapOf("wy" to clearedAndDisabled)))["wy"]
        )
        assertEquals(
            StylePreferenceSync.Selection(emptyList(), enabled = false, modifiedAt = 150L, version = 5L),
            StylePreferenceSync.decode(StylePreferenceSync.merge(
                StylePreferenceSync.encode(mapOf("wy" to clearedAndDisabled)),
                "{}"
            ))["wy"]
        )
    }

    @Test
    fun editedStaysMonotonicWhenClockMovesBackward() {
        val previous = StylePreferenceSync.Selection(listOf("华语"), enabled = true, modifiedAt = 10_000L, version = 7L)

        val updated = StylePreferenceSync.edited(previous, listOf("摇滚"), enabled = true, nowMillis = 1L)

        assertEquals(10_001L, updated.modifiedAt)
        assertEquals(8L, updated.version)
    }

    @Test
    fun editsHaveNoDailyLimitAndUnchangedNoOpPreservesRevision() {
        var selection: StylePreferenceSync.Selection? = null
        repeat(7) { index ->
            selection = StylePreferenceSync.edited(
                selection,
                listOf("tag-$index"),
                enabled = true,
                nowMillis = 1_000L
            )
        }
        val afterManyEdits = requireNotNull(selection)
        assertEquals(listOf("tag-6"), afterManyEdits.tags)
        assertEquals(7L, afterManyEdits.version)
        assertTrue("same-millisecond edits must each advance the monotonic timestamp", afterManyEdits.modifiedAt > 1_000L)

        val noOp = StylePreferenceSync.edited(afterManyEdits, afterManyEdits.tags, afterManyEdits.enabled, nowMillis = 99_000L)
        assertSame(afterManyEdits, noOp)
        assertEquals(afterManyEdits, noOp)
    }
}
