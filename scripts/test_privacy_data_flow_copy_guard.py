#!/usr/bin/env python3
"""Mutation-test the real privacy guard in temp fixtures, never production files."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = Path('app/src/main/java/xyz/cncnlp/soundwave')
GUARD = Path('scripts/privacy-data-flow-copy-guard.sh')
LOGIN = BASE / 'ui/account/FrontendLoginCredentials.kt'
ACCOUNT = BASE / 'ui/account/FrontendAccountCredentials.kt'
MAIN = BASE / 'MainActivity.kt'
SECRET = BASE / 'SecretStore.kt'
LOCAL = BASE / 'LocalStore.kt'
CRASH = BASE / 'CrashGuard.kt'
ME = BASE / 'MePage.kt'
SUPPORT = BASE / 'SettingsSupportPages.kt'
AUTO = BASE / 'CrashReportAutoUpload.kt'
FILES = [GUARD, Path('VERSION'), Path('app/build.gradle')] + [BASE / name for name in (
    'MePage.kt', 'SettingsSupportPages.kt', 'ApiClient.kt', 'UserDataSaveClient.kt',
    'Models.kt', 'sync/WebDavSync.kt', 'CrashGuard.kt', 'LocalStore.kt',
    'CloudSyncController.kt', 'SecretStore.kt', 'MainActivity.kt', 'ScriptRuntime.kt',
    'ScriptNetworkPolicy.kt', 'PlaybackHistoryPolicy.kt', 'CrashReportRedactor.kt',
    'ContextData.kt', 'ContextPreferences.kt',
    'ui/account/FrontendLoginCredentials.kt', 'ui/account/FrontendAccountCredentials.kt')]


class PrivacyGuardTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='privacy-flow-guard-')
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for relative in FILES:
            dest = self.root / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, dest)

    def run_guard(self):
        return subprocess.run(['bash', str(self.root / GUARD)], capture_output=True,
                              text=True, timeout=20)

    def reject(self, relative, before, after, count=1):
        path = self.root / relative
        text = path.read_text()
        self.assertEqual(text.count(before), count, 'mutation anchor changed')
        path.write_text(text.replace(before, after, 1))
        result = self.run_guard()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('FAIL: client/source boundary mismatch:', result.stdout + result.stderr)
        if relative == CRASH:
            self.assertIn('crash reports stay on-device only', result.stdout + result.stderr)

    def test_extracted_secure_helpers_pass(self):
        result = self.run_guard()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('privacy data-flow copy guard passed', result.stdout)

    def test_first_login_call_disconnected_with_comment_decoy(self):
        self.reject(MAIN, 'rememberFrontendLoginCredentials(context, prefs, r.user, p)',
                    '// rememberFrontendLoginCredentials(context, prefs, r.user, p)', 2)

    def test_second_login_call_disconnected(self):
        path = self.root / MAIN
        text = path.read_text()
        call = 'rememberFrontendLoginCredentials(context, prefs, r.user, p)'
        self.assertEqual(text.count(call), 2)
        first, last = text.rsplit(call, 1)
        path.write_text(first + '// ' + call + last)
        self.assertNotEqual(self.run_guard().returncode, 0)

    def test_plaintext_encryption_bypass(self):
        self.reject(LOGIN, 'SecretStore.encrypt(context, password)', 'password')

    def test_plaintext_fallback(self):
        self.reject(LOGIN, '}.getOrNull()', '}.getOrNull() ?: password')

    def test_persistence_call_disconnected(self):
        self.reject(LOGIN, 'persistFrontendAccountCredentials(prefs, user, encrypted, true)',
                    'false /* persistFrontendAccountCredentials(prefs, user, encrypted, true) */')

    def test_wrong_persisted_value(self):
        self.reject(ACCOUNT, 'putString("pass", encrypted!!)', 'putString("pass", user)')

    def test_removed_encryption_failure_gate(self):
        self.reject(ACCOUNT, 'loggedIn && user.isNotBlank() && !encrypted.isNullOrBlank()', 'loggedIn')

    def test_removed_failure_cleanup(self):
        self.reject(ACCOUNT, 'else editor.remove("pass").remove("pass_owner")', 'else Unit')

    def test_plaintext_log_in_login_helper(self):
        self.reject(LOGIN, 'val encrypted =', 'android.util.Log.d("creds", password)\n    val encrypted =')

    def test_ciphertext_log_in_persistence_helper(self):
        self.reject(ACCOUNT, 'val canRemember =', 'println(encrypted)\n    val canRemember =')

    def test_crypto_plaintext_return(self):
        self.reject(SECRET, 'return PACKED_PREFIX + Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)',
                    'return plain // return PACKED_PREFIX + Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)')

    def test_crypto_mode_changed(self):
        self.reject(SECRET, 'cipher.init(Cipher.ENCRYPT_MODE, keyForEncrypt())',
                    'cipher.init(Cipher.DECRYPT_MODE, keyForEncrypt())')

    def test_gcm_removed(self):
        self.reject(SECRET, 'KeyProperties.BLOCK_MODE_GCM', 'KeyProperties.BLOCK_MODE_CBC')

    def test_key_store_removed(self):
        self.reject(SECRET, 'KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")',
                    'KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES)')

    def test_local_preferences_public(self):
        self.reject(LOCAL, 'getSharedPreferences(PREF, 0)', 'getSharedPreferences(PREF, 1)')

    def test_local_json_writer_disconnected(self):
        path = self.root / LOCAL
        text = path.read_text()
        before = 'putString(KEY_DOWNLOADS, encodeDownloads(list))'
        self.assertEqual(text.count(before), 4)
        path.write_text(text.replace(before, 'putString(KEY_DOWNLOADS, "") // ' + before))
        result = self.run_guard()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('FAIL: client/source boundary mismatch:', result.stdout + result.stderr)

    def test_frontend_preferences_public(self):
        self.reject(MAIN, 'ContextPreferences.open(context, "soundwave", 0,',
                    'ContextPreferences.open(context, "soundwave", 1,')

    def test_owner_binding_removed(self):
        self.reject(ACCOUNT, 'putString("pass_owner", user)', 'putString("pass_owner", "")')

    def test_disk_failure_can_not_claim_saved(self):
        self.reject(ACCOUNT, 'return committed && canRemember', 'return canRemember')

    def test_logout_must_still_use_gated_controller(self):
        self.reject(MAIN, 'account.saveAccountDataOnLogoutWhileTransitionLocked(logoutUser, logoutPass, logoutBundle)',
                    'account.saveAccountDataOnLogout(logoutUser, logoutPass, logoutBundle)')

    def test_privacy_copy_is_not_relaxed(self):
        path = self.root / BASE / 'SettingsSupportPages.kt'
        text = path.read_text()
        fragment = '没有对这些用户数据使用 Android Keystore AES-GCM 加密'
        self.assertIn(fragment, text)
        path.write_text(text.replace(fragment, '这些用户数据均已加密'))
        result = self.run_guard()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('FAIL: 本机存储 row is missing boundary:', result.stdout + result.stderr)

    # Deletion contract (user ruling 2026-11-04): the upload machinery is gone.
    # These mutations resurrect pieces of it; the guard must reject every one.
    def test_upload_call_resurrected_in_crash_guard(self):
        self.reject(CRASH, 'deleteFile(context, FILE_NAME)',
                    'deleteFile(context, FILE_NAME); ApiClient.reportBug("x", "m", "d", "1")', 1)

    def test_startup_flush_resurrected(self):
        self.reject(CRASH, 'CrashGuard.install(this)',
                    'CrashGuard.install(this); CrashGuard.flushPending(this)', 1)

    def test_auto_upload_file_resurrected(self):
        (self.root / AUTO).write_text('internal class CrashReportAutoUpload\n')
        result = self.run_guard()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('crash reports stay on-device only', result.stdout + result.stderr)

    def test_feedback_row_resurrected(self):
        self.reject(ME, 'NavRow("崩溃诊断"',
                    'NavRow("一键反馈", "x", SettingGlyphSpec.BugReport, mark) { onSettingsSub("feedback") }\n'
                    '                        NavRow("崩溃诊断"', 1)

    def test_crash_copy_upload_claim_resurrected(self):
        path = self.root / SUPPORT
        text = path.read_text()
        fragment = '不会上传到任何服务器'
        self.assertIn(fragment, text)
        path.write_text(text.replace(fragment, '会上传到服务器'))
        result = self.run_guard()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('FAIL: 崩溃报告 row is missing boundary:', result.stdout + result.stderr)

    def test_preferences_wrapper_ignores_private_mode(self):
        self.reject(BASE / 'ContextPreferences.kt', 'app.getSharedPreferences(target, mode)',
                    'app.getSharedPreferences(target, 1)')


if __name__ == '__main__':
    unittest.main(verbosity=2)
