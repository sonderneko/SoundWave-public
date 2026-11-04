"""Source wiring regression checks; not a substitute for Robolectric/device tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/xyz/cncnlp/soundwave'


class AvatarRevisionContract(unittest.TestCase):
    def test_avatar_writers_use_immutable_publication(self):
        store = (SRC / 'AvatarStore.kt').read_text()
        self.assertIn('private fun writeVersion(', store)
        self.assertNotIn('FileOutputStream(out)', store)
        self.assertIn('pruneVersions(', store)
        self.assertIn('pinnedVersions', store)

    def test_each_controller_cloud_read_captures_avatar_generation(self):
        source = (SRC / 'CloudSyncController.kt').read_text()
        self.assertIn('private var avatarMutationGeneration = 0L', source)
        for start, end in [
            ('private fun restoreCloudSnapshotFromStoredSession()', '/** A fresh authenticated'),
            ('suspend fun syncAccountDataExplicitly(', '/** Logout must wait'),
            ('suspend fun pullCloudUserIfAllowed()', '/** Apply an authenticated'),
        ]:
            body = source[source.index(start):source.index(end, source.index(start))]
            self.assertIn('val requestAvatarGeneration = avatarMutationGeneration', body)
            self.assertIn('requestAvatarGeneration', body)
        self.assertIn('cloudForAvatarGeneration(cloud, requestAvatarGeneration)', source)

    def test_startup_does_not_authorize_username_cached_avatar(self):
        source = (SRC / 'CloudSyncController.kt').read_text()
        self.assertIn('val avatarPathState = mutableStateOf("")', source)
        init = source[source.index('init {'):source.index('/** A fresh authenticated')]
        self.assertNotIn('AvatarStore.path(', init)
        self.assertNotIn('AvatarStore.accountSpecificPath(', init)


if __name__ == '__main__':
    unittest.main()
