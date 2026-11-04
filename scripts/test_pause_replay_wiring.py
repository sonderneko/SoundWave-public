#!/usr/bin/env python3
"""Supplemental source integration contracts, NOT Android behavior tests."""
from pathlib import Path
import unittest
B=Path(__file__).resolve().parents[1]/'app/src/main/java/xyz/cncnlp/soundwave'
class WiringContract(unittest.TestCase):
    def test_main_installs_explicit_cancel_and_token_replay(self):
        s=(B/'MainActivity.kt').read_text()
        self.assertIn('onPlaybackInterrupted = { playback.cancelPendingPlayback() }',s)
        self.assertIn('onNeedReplay = { intent -> playback.replay(intent) }',s)
    def test_commit_and_pending_play_keep_entry_intent(self):
        s=(B/'PlayerService.kt').read_text()
        commit=s[s.index('val commit: () -> Unit = commit@{'):s.index('fun playNextPrepared()')]
        self.assertIn('if (!isPlaybackIntentCurrent(intent))',commit)
        self.assertIn('val intent: Int',s[s.index('private data class PendingPlay('):s.index('@Volatile private var pendingPlay')])
        self.assertIn('intent = pending.intent',s)
    def test_sleep_pause_uses_same_explicit_intent(self):
        s=(B/'PlayerService.kt').read_text()
        block=s[s.index('if (sleepStopAfterCurrent) {'):s.index('when (playMode)',s.index('if (sleepStopAfterCurrent) {'))]
        self.assertIn('\n                pause()\n',block)
    def test_restore_local_and_headless_keep_entry_intent(self):
        s=(B/'PlayerService.kt').read_text()
        restore=s[s.index('private fun restoreFromStore('):s.index('private fun recoverWithoutUi(')]
        self.assertIn('val intent = playbackIntent.get()',restore)
        self.assertIn('if (!isPlaybackIntentCurrent(intent)) return@launch',restore)
        self.assertIn('intent = intent',restore)
        headless=s[s.index('private fun resolveInService('):s.index('private fun prefetchNextWithoutUi(')]
        self.assertIn('isPlaybackIntentCurrent(intent)',headless)
        self.assertIn('intent = intent',headless)
if __name__=='__main__': unittest.main()
