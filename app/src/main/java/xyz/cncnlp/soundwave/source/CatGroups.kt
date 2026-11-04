package xyz.cncnlp.soundwave.source

/** Typed category taxonomy shared by source adapters, UI state and local cache. */
data class CatGroups(val groups: List<Pair<String, List<String>>>)
