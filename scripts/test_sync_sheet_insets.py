from pathlib import Path
import unittest
root=Path('/root/soundwave-r657-r3/app/src/main/java/xyz/cncnlp/soundwave')
class SyncSheetInsetsTest(unittest.TestCase):
 def test_password_field_does_not_consume_ime(self):
  t=(root/'AccountPasswordReentry.kt').read_text()
  self.assertNotIn('.imePadding()',t)
  self.assertIn('scrollContent = true',t)
 def test_dialog_owns_available_viewport_not_panel(self):
  t=(root/'ui/common/DesignSheet.kt').read_text()
  self.assertIn('.imePadding()',t)
  self.assertIn('.statusBarsPadding()',t)
  scrollContract = 'DesignSheetDialog(onDismiss = onDismiss, scrollContent = false, content = content)'
  self.assertIn(scrollContract,t)
  self.assertIn('.verticalScroll(rememberScrollState())',t)
 def test_webdav_uses_same_window_inset_contract(self):
  t=(root/'ui/settings/WebDavLoginSheet.kt').read_text()
  self.assertIn('DesignSheetDialog(',t)
  self.assertIn('scrollContent = true',t)
  self.assertNotIn('screenHeight *',t)
  self.assertNotIn('.imePadding()',t)
if __name__=='__main__': unittest.main()
