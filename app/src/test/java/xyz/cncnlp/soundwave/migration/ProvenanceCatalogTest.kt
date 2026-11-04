package xyz.cncnlp.soundwave.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceCatalogTest {
    private val epoch = 7L
    private fun inventory(
        domain: ProvenanceDomain,
        entries: Map<String, InventoryPresence> = domain.requiredLocations.associateWith { InventoryPresence.ABSENT },
        scannerVersion: Int = domain.scannerVersion,
        scannedAtMillis: Long? = 123L,
        source: InventorySource = InventorySource.LEGACY_SCAN,
        scanEpoch: Long = epoch,
        scanComplete: Boolean = true,
        importComplete: Boolean = true,
        readSucceeded: Boolean = true,
        writeSucceeded: Boolean = true,
        invalidationReason: String? = null
    ) = DomainInventory(domain, scannerVersion, scannedAtMillis, source, scanEpoch, entries,
        scanComplete, importComplete, readSucceeded, writeSucceeded, invalidationReason)

    private fun catalog(
        records: Map<ProvenanceDomain, DomainInventory> = ProvenanceDomain.values().associateWith { inventory(it) },
        version: Int = ProvenanceCatalog.VERSION,
        catalogEpoch: Long = epoch,
        readSucceeded: Boolean = true,
        writeSucceeded: Boolean = true
    ) = ProvenanceCatalog(version, catalogEpoch, records, readSucceeded, writeSucceeded)

    @Test fun completeCurrentAbsenceIsEvidenceOnly() {
        val value = catalog()
        assertTrue(value.allRequiredDomainsVerifiedAbsent(epoch))
        ProvenanceDomain.values().forEach {
            assertEquals(ProvenanceState.VERIFIED_ABSENT, value.state(it, epoch))
        }
        // No activation, ownership, upload permission or consent API exists on this evidence model.
    }

    @Test fun missingRequiredDomainIsNotScanned() {
        val records = ProvenanceDomain.values().associateWith { inventory(it) }.toMutableMap()
        records.remove(ProvenanceDomain.DOWNLOAD_RECORDS)
        val value = catalog(records)
        assertEquals(ProvenanceState.NOT_SCANNED, value.state(ProvenanceDomain.DOWNLOAD_RECORDS, epoch))
        assertFalse(value.allRequiredDomainsVerifiedAbsent(epoch))
    }

    @Test fun emptyCatalogIsNeverGloballyAbsent() {
        assertFalse(catalog(emptyMap()).allRequiredDomainsVerifiedAbsent(epoch))
    }

    @Test fun unknownCatalogVersionsFailClosed() {
        listOf(0, ProvenanceCatalog.VERSION + 1).forEach {
            val value = catalog(version = it)
            assertEquals(ProvenanceState.UNKNOWN, value.state(ProvenanceDomain.CORE_BUNDLE, epoch))
            assertFalse(value.allRequiredDomainsVerifiedAbsent(epoch))
        }
    }

    @Test fun staleScannerVersionsFailClosed() {
        val domain = ProvenanceDomain.CORE_BUNDLE
        listOf(0, domain.scannerVersion + 1).forEach {
            assertEquals(ProvenanceState.UNKNOWN, catalog(mapOf(domain to inventory(domain, scannerVersion = it))).state(domain, epoch))
        }
    }

    @Test fun restoreInvalidatesCatalogAndDomainEpochs() {
        assertFalse(catalog().allRequiredDomainsVerifiedAbsent(epoch + 1))
        assertFalse(catalog(catalogEpoch = epoch - 1).allRequiredDomainsVerifiedAbsent(epoch))
        val domain = ProvenanceDomain.CORE_BUNDLE
        assertEquals(ProvenanceState.UNKNOWN, catalog(mapOf(domain to inventory(domain, scanEpoch = epoch - 1))).state(domain, epoch))
    }

    @Test fun catalogReadOrWriteFailureInvalidatesEvidence() {
        listOf(catalog(readSucceeded = false), catalog(writeSucceeded = false)).forEach {
            assertEquals(ProvenanceState.UNKNOWN, it.state(ProvenanceDomain.CORE_BUNDLE, epoch))
            assertFalse(it.allRequiredDomainsVerifiedAbsent(epoch))
        }
    }

    @Test fun domainReadOrWriteFailureInvalidatesEvidence() {
        val domain = ProvenanceDomain.CORE_BUNDLE
        listOf(inventory(domain, readSucceeded = false), inventory(domain, writeSucceeded = false)).forEach {
            assertEquals(ProvenanceState.UNKNOWN, catalog(mapOf(domain to it)).state(domain, epoch))
        }
    }

    @Test fun keyExistsEvenWithEmptyValueIsPresent() {
        val domain = ProvenanceDomain.CORE_BUNDLE
        val record = inventory(domain, mapOf("soundwave_userdata/user_data_v1" to InventoryPresence.PRESENT))
        assertEquals(ProvenanceState.PRESENT_UNASSIGNED, catalog(mapOf(domain to record)).state(domain, epoch))
    }

    @Test fun downloadsOnlyIsNotGlobalAbsence() {
        assertOnlyKeyPresent(ProvenanceDomain.DOWNLOAD_RECORDS, "soundwave_userdata/downloads_v1")
    }

    @Test fun libraryOrderOnlyIsNotGlobalAbsence() {
        assertOnlyKeyPresent(ProvenanceDomain.LIBRARY_ORDER, "soundwave/lib_order_v1")
    }

    private fun assertOnlyKeyPresent(domain: ProvenanceDomain, key: String) {
        val records = ProvenanceDomain.values().associateWith { inventory(it) }.toMutableMap()
        records[domain] = inventory(domain, mapOf(key to InventoryPresence.PRESENT))
        val value = catalog(records)
        assertEquals(ProvenanceState.PRESENT_UNASSIGNED, value.state(domain, epoch))
        assertFalse(value.allRequiredDomainsVerifiedAbsent(epoch))
    }

    @Test fun incompleteScanCannotProveAbsenceOrAssignment() {
        val domain = ProvenanceDomain.CORE_BUNDLE
        val value = catalog(mapOf(domain to inventory(domain, scanComplete = false)))
        assertEquals(ProvenanceState.UNKNOWN, value.state(domain, epoch))
        assertFalse(value.allRequiredDomainsVerifiedAbsent(epoch))
    }

    @Test fun missingOrUnexpectedLocationCannotProveAbsence() {
        val domain = ProvenanceDomain.CORE_BUNDLE
        listOf(emptyMap(), mapOf("unexpected" to InventoryPresence.ABSENT)).forEach {
            assertEquals(ProvenanceState.UNKNOWN, catalog(mapOf(domain to inventory(domain, it))).state(domain, epoch))
        }
    }

    @Test fun unreadableLocationIsUnknown() {
        val domain = ProvenanceDomain.CORE_BUNDLE
        val entries = domain.requiredLocations.associateWith { InventoryPresence.UNREADABLE }
        assertEquals(ProvenanceState.UNKNOWN, catalog(mapOf(domain to inventory(domain, entries))).state(domain, epoch))
    }

    @Test fun absentTimestampMeansNotScanned() {
        val domain = ProvenanceDomain.CORE_BUNDLE
        assertEquals(ProvenanceState.NOT_SCANNED, catalog(mapOf(domain to inventory(domain, scannedAtMillis = null))).state(domain, epoch))
    }

    @Test fun invalidTimeOrEpochIsUnknown() {
        val domain = ProvenanceDomain.CORE_BUNDLE
        assertEquals(ProvenanceState.UNKNOWN, catalog(mapOf(domain to inventory(domain, scannedAtMillis = -1))).state(domain, epoch))
        assertFalse(catalog(catalogEpoch = -1).allRequiredDomainsVerifiedAbsent(-1))
    }

    @Test fun explicitInvalidationReasonFailsClosed() {
        val domain = ProvenanceDomain.CORE_BUNDLE
        assertEquals(ProvenanceState.UNKNOWN, catalog(mapOf(domain to inventory(domain, invalidationReason = "restore"))).state(domain, epoch))
    }

    @Test fun unknownSourceCannotProveAbsence() {
        val domain = ProvenanceDomain.CORE_BUNDLE
        assertEquals(ProvenanceState.UNKNOWN, catalog(mapOf(domain to inventory(domain, source = InventorySource.UNKNOWN))).state(domain, epoch))
    }

    @Test fun completedLocalImportRemainsInactiveEvenWhenEmpty() {
        val records = ProvenanceDomain.values().associateWith { inventory(it, source = InventorySource.LOCAL_IMPORT) }
        val value = catalog(records)
        assertEquals(ProvenanceState.IMPORTED_LOCAL_NOT_ACTIVE, value.state(ProvenanceDomain.CORE_BUNDLE, epoch))
        assertFalse(value.allRequiredDomainsVerifiedAbsent(epoch))
    }

    @Test fun incompleteImportIsUnknown() {
        val domain = ProvenanceDomain.CORE_BUNDLE
        val value = catalog(mapOf(domain to inventory(domain, source = InventorySource.LOCAL_IMPORT, importComplete = false)))
        assertEquals(ProvenanceState.UNKNOWN, value.state(domain, epoch))
        assertFalse(value.allRequiredDomainsVerifiedAbsent(epoch))
    }

    @Test fun mismatchedDomainCannotBeUsedAsEvidence() {
        val value = catalog(mapOf(ProvenanceDomain.CORE_BUNDLE to inventory(ProvenanceDomain.DOWNLOAD_RECORDS)))
        assertEquals(ProvenanceState.UNKNOWN, value.state(ProvenanceDomain.CORE_BUNDLE, epoch))
    }

    @Test fun defensiveCopiesProtectInputsAndExposedCollections() {
        val domain = ProvenanceDomain.CORE_BUNDLE
        val entries = domain.requiredLocations.associateWith { InventoryPresence.ABSENT }.toMutableMap()
        val record = inventory(domain, entries)
        val records = ProvenanceDomain.values().associateWith { inventory(it) }.toMutableMap()
        records[domain] = record
        val value = catalog(records)
        entries.clear()
        records.clear()
        assertTrue(value.allRequiredDomainsVerifiedAbsent(epoch))
        expectUnmodifiable { (record.entries as MutableMap<String, InventoryPresence>).clear() }
        expectUnmodifiable { (value.inventories as MutableMap<ProvenanceDomain, DomainInventory>).clear() }
        expectUnmodifiable { (domain.requiredLocations as MutableSet<String>).clear() }
        assertTrue(value.allRequiredDomainsVerifiedAbsent(epoch))
    }

    private fun expectUnmodifiable(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Collection must be unmodifiable")
        } catch (_: UnsupportedOperationException) {
            // java.util.Collections wrapper protects even callers using a mutable cast.
        }
    }
}
