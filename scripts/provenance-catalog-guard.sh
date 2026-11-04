#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys
root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
source = base / "migration/ProvenanceCatalog.kt"
test = root / "app/src/test/java/xyz/cncnlp/soundwave/migration/ProvenanceCatalogTest.kt"
doc = root / "docs/architecture-provenance-catalog.md"
def require(condition, message):
    if not condition:
        raise SystemExit("FAIL: " + message)
require(source.is_file(), "provenance catalog implementation missing")
text = source.read_text(encoding="utf-8")
# Preserve strings first: selector strings contain /* and /**, not Kotlin comments.
lexemes = re.compile(r'"(?:\\.|[^"\\])*"|/\*.*?\*/|//[^\n]*', re.S)
code = lexemes.sub(lambda m: m.group(0) if m.group(0).startswith('"') else '', text)
compact = re.sub(r'\s+', '', code)
for token in (
    "enum class ProvenanceState", "NOT_SCANNED", "UNKNOWN", "PRESENT_UNASSIGNED",
    "VERIFIED_ABSENT", "IMPORTED_LOCAL_NOT_ACTIVE", "class DomainInventory", "class ProvenanceCatalog",
    "val scannedAtMillis", "val scanSource", "val invalidationEpoch", "val invalidationReason",
    "Collections.unmodifiableSet(LinkedHashSet(locations.toList()))",
    "Collections.unmodifiableMap(LinkedHashMap(entries))",
    "Collections.unmodifiableMap(LinkedHashMap(inventories))",
):
    require(token in code, f"catalog contract missing {token}")
for token in (
    "catalogVersion!=VERSION", "!readSucceeded", "!writeSucceeded", "currentInvalidationEpoch<0",
    "invalidationEpoch!=currentInvalidationEpoch", "inventories[domain]?:returnProvenanceState.NOT_SCANNED",
    "inventory.domain!=domain", "inventory.scannerVersion!=domain.scannerVersion",
    "inventory.invalidationEpoch!=currentInvalidationEpoch", "!inventory.readSucceeded",
    "!inventory.writeSucceeded", "inventory.invalidationReason!=null",
    "inventory.scannedAtMillis?:returnProvenanceState.NOT_SCANNED", "scannedAt<0",
    "!inventory.scanComplete", "!inventory.importComplete", "inventory.scanSource==InventorySource.UNKNOWN",
    "inventory.entries.keys!=domain.requiredLocations",
    "inventory.entries.values.any{it==InventoryPresence.UNREADABLE}",
    "inventory.scanSource==InventorySource.LOCAL_IMPORT",
    "returnProvenanceState.IMPORTED_LOCAL_NOT_ACTIVE",
    "inventory.entries.values.any{it==InventoryPresence.PRESENT}",
    "ProvenanceDomain.values().all{state(it,currentInvalidationEpoch)==ProvenanceState.VERIFIED_ABSENT}",
):
    require(token in compact, f"fail-closed/immutable predicate missing {token}")
require(code.index("return ProvenanceState.IMPORTED_LOCAL_NOT_ACTIVE") <
        code.index("inventory.entries.values.any { it == InventoryPresence.PRESENT }"),
        "local import must be classified before presence/absence")
require(not re.search(r'\bvar\b|\bdata\s+class\b', code), "catalog must expose no mutable property/copy API")
for token in ("android.", "androidx.", "org.json", "java.io", "java.net", "kotlinx.", "LocalStore",
              "LegacyUnassignedSnapshot", "CloudSyncController", "WebDav", "ProfileId", "OkHttp",
              "System.currentTimeMillis", "SharedPreferences", "CoroutineScope"):
    require(token not in code, f"pure evidence model must not depend on {token}")
runtime = (base / "migration/RuntimeProvenanceInventory.kt").read_text()
scanner = (base / "migration/ProvenanceScanner.kt").read_text()
require("ProvenanceDomain.values()" in scanner and "requiredLocations" in scanner,
        "runtime scan must visit fixed full manifest")
require("ProvenanceCatalogCodec.decode(readback)" in runtime and "readEpoch(app) != epoch" in runtime,
        "runtime persisted evidence must verify exact readback and independent epoch")
require("RuntimeProvenanceInventory.cachedState(context)" in (base / "migration/LegacyUnassignedSnapshot.kt").read_text(),
        "gate must use current runtime inventory, not detached absence")
require(test.is_file(), "JUnit regression file missing")
t = test.read_text(encoding="utf-8")
required_tests = (
    "completeCurrentAbsenceIsEvidenceOnly", "missingRequiredDomainIsNotScanned", "emptyCatalogIsNeverGloballyAbsent",
    "unknownCatalogVersionsFailClosed", "staleScannerVersionsFailClosed", "restoreInvalidatesCatalogAndDomainEpochs",
    "catalogReadOrWriteFailureInvalidatesEvidence", "domainReadOrWriteFailureInvalidatesEvidence",
    "keyExistsEvenWithEmptyValueIsPresent", "downloadsOnlyIsNotGlobalAbsence", "libraryOrderOnlyIsNotGlobalAbsence",
    "incompleteScanCannotProveAbsenceOrAssignment", "missingOrUnexpectedLocationCannotProveAbsence",
    "unreadableLocationIsUnknown", "absentTimestampMeansNotScanned", "invalidTimeOrEpochIsUnknown",
    "explicitInvalidationReasonFailsClosed", "unknownSourceCannotProveAbsence",
    "completedLocalImportRemainsInactiveEvenWhenEmpty", "incompleteImportIsUnknown",
    "mismatchedDomainCannotBeUsedAsEvidence", "defensiveCopiesProtectInputsAndExposedCollections",
)
names = re.findall(r'@Test\s+fun\s+(\w+)\(', t)
for name in required_tests:
    require(name in names, f"regression coverage missing {name}")
require(doc.is_file(), "provenance inventory documentation missing")
d = doc.read_text(encoding="utf-8")
manifest = code[code.index("enum class ProvenanceDomain"):code.index("val scannerVersion: Int = 2")]
domains = re.findall(r'^\s+([A-Z][A-Z_]+)\(', manifest, re.M)
require(domains and len(domains) == len(set(domains)), "fixed domain manifest missing or duplicated")
for domain in domains:
    require(f"| {domain} |" in d, f"inventory documentation missing {domain}")
for location in ("soundwave_userdata/downloads_v1", "soundwave/lib_order_v1"):
    require(location in code and location in t, f"legacy-only regression missing {location}")
print(f"Provenance catalog guard passed: {len(domains)} required domains, {len(required_tests)} regression anchors, fail-closed predicates, defensive copies, runtime scanner/gate hookup (structural only; JVM NOT RUN).")
PY
