#!/usr/bin/env python3
"""Structural regression only; never a substitute for Kotlin/JVM execution."""
from pathlib import Path
import sys
base = Path(__file__).resolve().parents[1] / "app/src/main/java/xyz/cncnlp/soundwave"
checks = {
    "migration/ProvenanceScanner.kt": ["class ProvenanceScanner", "InventoryPresence.UNREADABLE", "requiredLocations", "ProvenanceCatalogCodec"],
    "migration/RuntimeProvenanceInventory.kt": ["object RuntimeProvenanceInventory", "fun scan", "persistedUriPermissions", "listFiles()", "getDatabasePath", "readEpoch", "commit()"],
    "migration/LegacyUnassignedSnapshot.kt": ["RuntimeProvenanceInventory.cachedState(context)", "allRequiredDomainsVerifiedAbsent"],
    "LocalStore.kt": ["RuntimeProvenanceInventory.EPOCH_KEY", "Math.addExact"],
}
errors = []
for file, tokens in checks.items():
    text = (base / file).read_text() if (base / file).exists() else ""
    for token in tokens:
        if token not in text:
            errors.append(f"{file}: missing {token}")
if errors:
    print("\n".join(errors)); sys.exit(1)
print("Runtime provenance structural contracts passed; JVM NOT RUN")
