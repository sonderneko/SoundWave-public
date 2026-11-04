#!/usr/bin/env python3
"""About update animation boundaries; runs without Android packaging or a device."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/java/xyz/cncnlp/soundwave"
ACTION = (BASE / "AboutUpdateAction.kt").read_text()
PAGE = (BASE / "SettingsSupportPages.kt").read_text()
MAIN = (BASE / "MainActivity.kt").read_text()


class AboutUpdateAnimationGuardTest(unittest.TestCase):
    def test_real_status_and_single_callback_are_preserved(self):
        self.assertIn('status.startsWith("正在检查 GitHub")', ACTION)
        self.assertIn('val checking = aboutUpdateChecking(updateStatus)', ACTION)
        self.assertIn('AboutUpdateAction(updateStatus = updateStatus, onCheckUpdate = onCheckUpdate)', PAGE)
        self.assertEqual(ACTION.count('onClick = onCheckUpdate'), 1)
        self.assertIn('clickable(enabled = !checking', ACTION)
        self.assertIn('GitHubReleases.check(BuildConfig.VERSION_NAME)', MAIN)
        for forbidden in ('delay(', 'GitHubReleases.check(', 'onCheckUpdate()', 'Dispatchers.IO'):
            self.assertNotIn(forbidden, ACTION)

    def test_motion_is_gated_and_stops_with_lifecycle(self):
        for contract in (
            'MotionPolicy.rememberReduceMotion()',
            'if (pressed && !reduceMotion) 0.97f else 1f',
            'tween(if (reduceMotion) 0 else 150)',
            'checking && started && LocalDecorativeActive.current && MotionPolicy.allowDecorativeLoops(reduceMotion)',
            'val angle = if (rotate)',
            'lifecycle.addObserver(observer)',
            'onDispose { lifecycle.removeObserver(observer) }',
            'Lifecycle.State.STARTED',
            'if (reduceMotion || checking || updateStatus.isBlank()) resultAlpha.snapTo(1f)',
        ):
            with self.subTest(contract=contract):
                self.assertIn(contract, ACTION)

    def test_accessible_static_busy_fallback_and_stable_button_size(self):
        for contract in ('role = Role.Button', 'stateDescription = if (checking)',
                         'liveRegion = LiveRegionMode.Polite', 'height(50.dp)',
                         'if (checking) "检查中…" else "检查更新"', 'Canvas(Modifier.size(16.dp))'):
            self.assertIn(contract, ACTION)
        self.assertNotIn('contentDescription', ACTION)  # Decorative spinner has no duplicate announcement.

    def test_navigation_download_and_status_filter_remain(self):
        for contract in ('val updateStatus = aboutUpdateStatus(status)', 'GitHubReleases.downloadPage()',
                         'Intent(Intent.ACTION_VIEW, Uri.parse(url))', 'onClick = onOpenDataFlow',
                         'onClick = { showLicenses = !showLicenses }'):
            self.assertIn(contract, PAGE)
        self.assertIn('"about" -> AboutAppPage(status = status, serverUrl = serverUrl, onCheckUpdate = onCheckUpdate',
                      (BASE / 'SettingsHub.kt').read_text())


if __name__ == '__main__':
    unittest.main()
