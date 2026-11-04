#!/usr/bin/env python3
"""Guard accepts current listener; mutations must break actual recovery/error chain."""
from pathlib import Path
import subprocess,tempfile,unittest
ROOT=Path(__file__).resolve().parents[1]
class RecoveryGuardTest(unittest.TestCase):
 def run_guard(self, mutation=None):
  with tempfile.TemporaryDirectory() as d:
   r=Path(d);b=r/'app/src/main/java/xyz/cncnlp/soundwave';b.mkdir(parents=True);(r/'scripts').mkdir()
   guard=ROOT/'scripts/playback-recovery-guard.sh';(r/'scripts'/guard.name).write_text(guard.read_text())
   for name in ['PlayerService.kt','PlaybackController.kt','ApiClient.kt','DownloadHelper.kt','DownloadScheduler.kt']:
    text=(ROOT/'app/src/main/java/xyz/cncnlp/soundwave'/name).read_text()
    if mutation and mutation[0]==name:
     self.assertIn(mutation[1],text);text=text.replace(mutation[1],mutation[2])
    (b/name).write_text(text)
   return subprocess.run(['bash',str(r/'scripts'/guard.name)],capture_output=True,text=True)
 def test_current_listener(self):self.assertEqual(0,self.run_guard().returncode)
 def test_reject_missing_headless_consumer(self):self.assertNotEqual(0,self.run_guard(('PlayerService.kt','else recoverWithoutUi(failure)','else Unit')).returncode)
 def test_reject_missing_foreground_consumer(self):self.assertNotEqual(0,self.run_guard(('PlayerService.kt','handler.invoke(failure) else recoverWithoutUi(failure)','Unit else recoverWithoutUi(failure)')).returncode)
 def test_reject_missing_stale_error_check(self):self.assertNotEqual(0,self.run_guard(('PlayerService.kt','if (!isCurrentTransaction() || isPlaybackInterrupted()) return','if (isPlaybackInterrupted()) return')).returncode)
 def test_reject_missing_interruption_check(self):self.assertNotEqual(0,self.run_guard(('PlayerService.kt','if (!isCurrentTransaction() || isPlaybackInterrupted()) return','if (!isCurrentTransaction()) return')).returncode)
 def test_reject_missing_failure_progress(self):self.assertNotEqual(0,self.run_guard(('PlayerService.kt','error.message ?: "error", failedAtMs, failedDurationMs','error.message ?: "error"')).returncode)
 def test_reject_missing_policy_skip(self):self.assertNotEqual(0,self.run_guard(('PlayerService.kt','recoveryPolicy.onResolveFailure(song.key())','recoveryPolicy.onPlaybackError(song.key(), 0, 0)')).returncode)
 def test_reject_missing_refresh_resume(self):self.assertNotEqual(0,self.run_guard(('PlaybackController.kt','startMs = decision.resumeAtMs','startMs = 0L')).returncode)
if __name__=='__main__':unittest.main()
