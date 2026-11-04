package xyz.cncnlp.soundwave.recommend

internal object TodayDislikeQuota {
    const val LIMIT = 6
    fun rejected(saved: String, day: String): Set<String> {
        val lines = saved.split("\n")
        return if (lines.firstOrNull() == day) lines.drop(1).filter { it.isNotBlank() }.toSet() else emptySet()
    }
    fun reserve(saved: String, day: String, identity: String): String? {
        val old = rejected(saved, day)
        if (identity.isBlank() || identity in old || old.size >= LIMIT) return null
        return (listOf(day) + old + identity).joinToString("\n")
    }
}
