package xyz.cncnlp.soundwave.migration

import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.StrictJsonDuplicateKeyScanner

/** Readonly adapter boundary. No business parsing: an existing empty key is still present. */
internal class ProvenanceScanner(private val inspect: (String) -> InventoryPresence) {
    fun scan(epoch: Long, scannedAtMillis: Long): ProvenanceCatalog {
        val results = linkedMapOf<ProvenanceDomain, DomainInventory>()
        val locations = linkedMapOf<String, InventoryPresence>()
        ProvenanceDomain.values().forEach { domain ->
            val entries = domain.requiredLocations.associateWith { location ->
                locations.getOrPut(location) {
                    try { inspect(location) } catch (_: Exception) { InventoryPresence.UNREADABLE }
                }
            }
            results[domain] = DomainInventory(
                domain = domain, scannerVersion = domain.scannerVersion,
                scannedAtMillis = scannedAtMillis, scanSource = InventorySource.LEGACY_SCAN,
                invalidationEpoch = epoch, entries = entries,
                scanComplete = true, importComplete = true, readSucceeded = true, writeSucceeded = true
            )
        }
        return ProvenanceCatalog(ProvenanceCatalog.VERSION, epoch, results, true, true)
    }
}

/** Catalog contains only selector presence metadata, never preference values, credentials or URLs. */
internal object ProvenanceCatalogCodec {
    private const val SCHEMA = "soundwave-provenance"

    fun encode(catalog: ProvenanceCatalog): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("version", catalog.catalogVersion)
        put("epoch", catalog.invalidationEpoch)
        put("domains", JSONArray().apply {
            catalog.inventories.forEach { (domain, inventory) ->
                put(JSONObject().apply {
                    put("domain", domain.name)
                    put("scannerVersion", inventory.scannerVersion)
                    put("epoch", inventory.invalidationEpoch)
                    put("at", inventory.scannedAtMillis ?: JSONObject.NULL)
                    put("source", inventory.scanSource.name)
                    put("complete", inventory.scanComplete)
                    put("importComplete", inventory.importComplete)
                    put("read", inventory.readSucceeded)
                    put("write", inventory.writeSucceeded)
                    put("reason", inventory.invalidationReason ?: JSONObject.NULL)
                    put("entries", JSONObject().apply {
                        inventory.entries.forEach { (location, presence) -> put(location, presence.name) }
                    })
                })
            }
        })
    }.toString()

    private fun strictLong(value: JSONObject, key: String): Long {
        val number = value.get(key)
        require(number is Int || number is Long)
        return (number as Number).toLong()
    }

    private fun strictBoolean(value: JSONObject, key: String): Boolean =
        value.get(key) as? Boolean ?: error("invalid boolean")

    fun decode(raw: String): ProvenanceCatalog? = try {
        require(raw.length <= 262_144)
        StrictJsonDuplicateKeyScanner.validate(raw, 4096)
        val root = JSONObject(raw)
        require(root.keys().asSequence().toSet() == setOf("schema", "version", "epoch", "domains"))
        require(root.getString("schema") == SCHEMA)
        require(strictLong(root, "version") == ProvenanceCatalog.VERSION.toLong())
        val epoch = strictLong(root, "epoch")
        require(epoch >= 0L)
        val domains = root.getJSONArray("domains")
        val inventories = linkedMapOf<ProvenanceDomain, DomainInventory>()
        for (index in 0 until domains.length()) {
            val item = domains.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf(
                "domain", "scannerVersion", "epoch", "at", "source", "complete",
                "importComplete", "read", "write", "reason", "entries"
            ))
            val domain = ProvenanceDomain.valueOf(item.getString("domain"))
            require(domain !in inventories)
            val entries = item.getJSONObject("entries")
            require(entries.keys().asSequence().toSet() == domain.requiredLocations)
            val decodedEntries = linkedMapOf<String, InventoryPresence>()
            entries.keys().forEach { location ->
                decodedEntries[location] = InventoryPresence.valueOf(entries.getString(location))
            }
            inventories[domain] = DomainInventory(
                domain = domain, scannerVersion = Math.toIntExact(strictLong(item, "scannerVersion")),
                scannedAtMillis = if (item.isNull("at")) null else strictLong(item, "at"),
                scanSource = InventorySource.valueOf(item.getString("source")),
                invalidationEpoch = strictLong(item, "epoch"), entries = decodedEntries,
                scanComplete = strictBoolean(item, "complete"), importComplete = strictBoolean(item, "importComplete"),
                readSucceeded = strictBoolean(item, "read"), writeSucceeded = strictBoolean(item, "write"),
                invalidationReason = if (item.isNull("reason")) null else item.getString("reason")
            )
        }
        ProvenanceCatalog(ProvenanceCatalog.VERSION, epoch, inventories, true, true)
    } catch (_: Exception) {
        null
    }
}
