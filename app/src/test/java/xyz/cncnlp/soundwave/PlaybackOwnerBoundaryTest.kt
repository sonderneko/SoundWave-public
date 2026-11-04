package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.migration.ProfileContext

class PlaybackOwnerBoundaryTest {
    @Test fun staleOwnerGenerationCannotCommitEvenMatchingSongAndQueue() {
        val gate = PlaybackTransactionGate()
        val ticket = gate.begin("wy:1", 4L, ProfileContext.legacy(7L))
        assertFalse(gate.isCurrent(ticket, "wy:1", 4L))
    }
    @Test fun inactiveProfileCannotBecomeRuntimeByTicketCreation() {
        val gate = PlaybackTransactionGate()
        val ticket = gate.begin("wy:1", 4L, ProfileContext.recorded(ProfileId.of("inactive"), 0L))
        assertFalse(gate.isCurrent(ticket, "wy:1", 4L))
    }
}
