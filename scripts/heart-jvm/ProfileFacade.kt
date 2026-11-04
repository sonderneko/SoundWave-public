package xyz.cncnlp.soundwave.migration
class ProfileContext { fun matches(other: ProfileContext) = this === other }
object ProfileWorkIdentity {
    private val owner = ProfileContext()
    fun runtime() = owner
    fun isRuntime(context: ProfileContext) = context === owner
}
