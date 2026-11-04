package xyz.cncnlp.soundwave.sync

/** Per-song G-counter merge: each installation advances only its own monotonic component. */
internal object SongPlayCountCounterMerge {
    const val MAX_TRACKS = 500

    fun merge(
        first: Map<String, Map<String, Long>>,
        second: Map<String, Map<String, Long>>
    ): MutableMap<String, MutableMap<String, Long>> {
        val merged = linkedMapOf<String, MutableMap<String, Long>>()
        (first.keys + second.keys).forEach { songKey ->
            val firstDevices = first[songKey].orEmpty()
            val secondDevices = second[songKey].orEmpty()
            val devices = linkedMapOf<String, Long>()
            (firstDevices.keys + secondDevices.keys).forEach { deviceId ->
                val count = maxOf(firstDevices[deviceId] ?: 0L, secondDevices[deviceId] ?: 0L)
                    .coerceAtLeast(0L)
                if (count > 0L) devices[deviceId] = count
            }
            if (devices.isNotEmpty()) merged[songKey] = devices
        }
        return trim(merged)
    }

    fun total(devices: Map<String, Long>): Long = devices.values.fold(0L) { total, count ->
        val safe = count.coerceAtLeast(0L)
        if (Long.MAX_VALUE - total < safe) Long.MAX_VALUE else total + safe
    }

    fun trim(
        counters: Map<String, Map<String, Long>>,
        limit: Int = MAX_TRACKS
    ): MutableMap<String, MutableMap<String, Long>> {
        if (limit <= 0) return linkedMapOf()
        return counters.asSequence()
            .filter { (_, devices) -> devices.values.any { it > 0L } }
            .sortedWith(
                compareByDescending<Map.Entry<String, Map<String, Long>>> { total(it.value) }
                    .thenBy { it.key }
            )
            .take(limit)
            .associateTo(linkedMapOf()) { (songKey, devices) ->
                songKey to devices.filterValues { it > 0L }.toMutableMap()
            }
    }
}
