from pathlib import Path
import unittest
root=Path('/root/soundwave-r657-r3/app/src/main/java/xyz/cncnlp/soundwave')
class WebDavSavedUiTest(unittest.TestCase):
 def test_test_connection_refreshes_saved_state_from_store(self):
  t=(root/'ui/settings/WebDavSyncOverlay.kt').read_text()
  block=t[t.index('val saveStatus = try'):t.index('status = saveStatus ?:')]
  self.assertIn('savedState = vault.read()',block)
  self.assertIn('editing = savedState !is WebDavSavedCredentialState.Ready',block)
 def test_editing_never_prefills_old_password(self):
  t=(root/'ui/settings/WebDavSyncOverlay.kt').read_text()
  self.assertIn('initialPassword = ""',t)
  self.assertNotIn('initialPassword = formPassword',t)
 def test_saved_button_resolves_store_at_click(self):
  t=(root/'ui/settings/WebDavSyncOverlay.kt').read_text()
  self.assertIn('onSyncSaved = { if (!busy) runSync(null) }',t)
  self.assertIn('WebDavSyncRun(session.syncWithSaved())',t)
if __name__=='__main__': unittest.main()
