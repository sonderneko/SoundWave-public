package xyz.cncnlp.soundwave

import org.junit.Test
import org.junit.Assert.*
import xyz.cncnlp.soundwave.sync.CloudUserMerge

class ComprehensiveSyncRegressionTest {
    @Test fun deletedNewerEventCannotHideOlderSurvivingEventOfSameSong() {
        val now=1700000000000L
        val older=Song(id="same",name="same",singer="artist",source="wy",playedAt=now-2000)
        val newer=older.copy(playedAt=now-1000)
        val merged=CloudUserMerge.merge(
            LocalStore.UserBundle(history=mutableListOf(older),historyDeletionTombstones=linkedMapOf(PlaybackHistoryPolicy.historyEventKey(newer) to now)),
            LocalStore.UserBundle(history=mutableListOf(newer)),now)
        assertEquals(listOf(older.playedAt),merged.history.map{it.playedAt})
    }
    @Test fun deletedTop300CannotConsumeQuotaAndDropTheSurvivor() {
        val now=1700000000000L
        val newest=(0 until 300).map {Song(id="deleted-$it",name="song",singer="artist",source="wy",playedAt=now-it-1)}
        val survivor=Song(id="survivor",name="song",singer="artist",source="wy",playedAt=now-1000)
        val tombstones=newest.associateTo(linkedMapOf()){PlaybackHistoryPolicy.historyEventKey(it) to now}
        val merged=CloudUserMerge.merge(LocalStore.UserBundle(history=mutableListOf(survivor),historyDeletionTombstones=tombstones),LocalStore.UserBundle(history=newest.toMutableList()),now)
        assertEquals(listOf(survivor.key()),merged.history.map{it.key()})
        assertEquals(merged.history,CloudUserMerge.merge(merged,LocalStore.UserBundle(history=newest.toMutableList()),now).history)
    }
}
