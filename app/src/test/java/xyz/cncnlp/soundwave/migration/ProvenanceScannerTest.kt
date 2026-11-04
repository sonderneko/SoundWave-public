package xyz.cncnlp.soundwave.migration

import org.junit.Assert.*
import org.junit.Test

class ProvenanceScannerTest {
    @Test fun keyPresenceIncludesEmptyValues() {
        val scanner = ProvenanceScanner { location ->
            if (location == "soundwave_userdata/downloads_v1") InventoryPresence.PRESENT else InventoryPresence.ABSENT
        }
        assertEquals(ProvenanceState.PRESENT_UNASSIGNED, scanner.scan(0L, 12L).state(ProvenanceDomain.DOWNLOAD_RECORDS, 0L))
    }

    @Test fun oneFailedSelectorInvalidatesItsDomain() {
        val scanner = ProvenanceScanner { location ->
            if (location == "soundwave/lib_order_v1") error("denied") else InventoryPresence.ABSENT
        }
        assertEquals(ProvenanceState.UNKNOWN, scanner.scan(0L, 12L).state(ProvenanceDomain.LIBRARY_ORDER, 0L))
    }

    @Test fun everyManifestSelectorIsVisitedExactlyOnce() {
        val seen = mutableSetOf<String>()
        val scanner = ProvenanceScanner { seen.add(it); InventoryPresence.ABSENT }
        val catalog = scanner.scan(3L, 12L)
        assertEquals(ProvenanceDomain.values().flatMap { it.requiredLocations }.toSet(), seen)
        assertTrue(catalog.allRequiredDomainsVerifiedAbsent(3L))
    }

    @Test fun codecRoundTripPreservesPresentAndUnassigned() {
        val catalog = ProvenanceScanner { InventoryPresence.PRESENT }.scan(7L, 12L)
        val decoded = ProvenanceCatalogCodec.decode(ProvenanceCatalogCodec.encode(catalog))!!
        ProvenanceDomain.values().forEach {
            assertEquals(ProvenanceState.PRESENT_UNASSIGNED, decoded.state(it, 7L))
        }
    }

    private fun mutateVersion(encoded: String, replacement: String): String {
        val current = org.json.JSONObject(encoded).getInt("version")
        return encoded.replace("\"version\":$current", replacement).also { assertNotEquals(encoded, it) }
    }

    @Test fun unknownVersionAndMalformedCatalogFailClosed() {
        assertNull(ProvenanceCatalogCodec.decode("{}"))
        val encoded = ProvenanceCatalogCodec.encode(ProvenanceScanner { InventoryPresence.ABSENT }.scan(7L, 12L))
        assertNull(ProvenanceCatalogCodec.decode(mutateVersion(encoded, "\"version\":999")))
        assertNull(ProvenanceCatalogCodec.decode("not json"))
    }

    @Test fun coercibleNumbersAndBooleansAndDuplicateKeysAreRejected() {
        val encoded = ProvenanceCatalogCodec.encode(ProvenanceScanner { InventoryPresence.ABSENT }.scan(7L, 12L))
        assertNull(ProvenanceCatalogCodec.decode(mutateVersion(encoded, "\"version\":1.5")))
        assertNull(ProvenanceCatalogCodec.decode(encoded.replace("\"epoch\":7", "\"epoch\":\"7\"")))
        assertNull(ProvenanceCatalogCodec.decode(encoded.replace("\"complete\":true", "\"complete\":\"true\"")))
        assertNull(ProvenanceCatalogCodec.decode(mutateVersion(encoded, "\"version\":${org.json.JSONObject(encoded).getInt("version")},\"version\":${org.json.JSONObject(encoded).getInt("version")}")))
    }

    @Test fun restoredEpochCannotReuseCatalogAbsence() {
        val catalog = ProvenanceScanner { InventoryPresence.ABSENT }.scan(7L, 12L)
        val decoded = ProvenanceCatalogCodec.decode(ProvenanceCatalogCodec.encode(catalog))!!
        assertFalse(decoded.allRequiredDomainsVerifiedAbsent(8L))
    }
}
