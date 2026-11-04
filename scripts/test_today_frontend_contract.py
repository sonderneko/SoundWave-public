#!/usr/bin/env python3
"""Ad-hoc live-consumer wiring guards; not Compose compilation/visual proof."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/java/xyz/cncnlp/soundwave'

class TodayFrontendContract(unittest.TestCase):
    def test_home_uses_fingerprint_session_and_no_hot_fallback(self):
        home = (BASE / 'HomePage.kt').read_text()
        self.assertIn('TodayRecommendationFrontend.load(', home)
        self.assertIn('LaunchedEffect(platform, recommendationSlot, dailyFingerprint)', home)
        self.assertIn('val heroList = dailySongs', home)
        self.assertNotIn('val profileLikedPool', home)
        self.assertIn('librarySongs = tastePoolSongs', home)
    def test_real_library_and_detail_update_are_connected(self):
        main = (BASE / 'MainActivity.kt').read_text()
        session = (BASE / 'ui/home/HomeFeedSession.kt').read_text()
        self.assertIn('AlbumFavoriteMarks.marks(context)', session)
        self.assertIn('rememberRecommendationLibrary(context, user, userBundle.playlists, history, userBundle.favs)', main)
        self.assertIn('favoritePlaylists = recommendationLibrary.favoritePlaylists', main)
        self.assertIn('favoriteAlbums = recommendationLibrary.favoriteAlbums', main)
        self.assertIn('importedPlaylists = recommendationLibrary.importedPlaylists', main)
        self.assertIn('BindTodayRecommendationDetail(context, plDetail, recommendationLibrary.profile)', main)
        self.assertIn('TodayRecommendationFrontend.acceptsUpdate', session)
        self.assertIn('tastePoolSongs =', main)
        self.assertIn('if (pl.id == "today_recommendation")', main)
        self.assertIn('rememberFrontendLoginCredentials(context, prefs, r.user, p)', main)
        self.assertIn('persistFrontendAccountCredentials(prefs, newUser, encryptedPassword, true)', main)
    def test_platform_switch_preserves_tags_without_auto_enabling(self):
        sheet = (BASE / 'ui/today/StylePickerSheet.kt').read_text()
        self.assertIn('StylePreferenceStore.loadEnabled(context, platform)', sheet)
        self.assertIn('StylePreferenceStore.saveEnabled(context, platform, enabled)', sheet)
        self.assertIn('Switch(', sheet)
        self.assertIn('onEnabledChange = { enabled = it }', sheet)
        self.assertIn('catch (cancelled: CancellationException)', sheet)

if __name__ == '__main__':
    unittest.main()
