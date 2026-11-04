#!/usr/bin/env python3
"""Source-contract checks only: no Android/Kotlin compilation or live requests."""
from pathlib import Path
import re
import unittest

BASE = Path(__file__).resolve().parents[1] / 'app/src/main/java/xyz/cncnlp/soundwave'


class OnlineImportContractTest(unittest.TestCase):
    def test_typed_album_import_uses_existing_album_detail_signature(self):
        api = (BASE / 'ApiClient.kt').read_text()
        body = api.split('suspend fun importPlaylist(raw: String): Playlist? {', 1)[1].split('\n    /**', 1)[0]
        self.assertIn('parseOnlineImportReference(raw)', body)
        self.assertIn('OnlineImportKind.ALBUM -> albumDetail(reference.id, reference.source)', body)
        self.assertRegex(api, r'suspend fun albumDetail\(id: String, source: String = "wy"\): Playlist\?')
        self.assertIn('playlistDetailSnapshot(reference.id, reference.source, seed)', body)
        self.assertNotIn('action=import', body)
        self.assertNotIn('idGuess', body)
        self.assertIn('song.id.isNotBlank()', body)

    def test_share_album_and_unknown_link_policy(self):
        share = (BASE / 'ShareLinkParser.kt').read_text()
        self.assertIn('parseOnlineImportReference(raw)', share)
        self.assertIn('短链或未识别的链接', share)
        self.assertNotIn('return Action.ImportPlaylist(link, source)', share)
        self.assertNotIn('Regex("https?://', share)  # use raw Kotlin literal for correct whitespace
        dialog = (BASE / 'ui/common/AppDialogs.kt').read_text()
        # Deletion contract (user ruling 2026-11-04): the ImportSourceDialog chooser was
        # retired; its row label '导入在线歌单 / 专辑' was the previous anchor here. The
        # surviving import surface is OnlineImportDialog — pin its title instead. Album
        # import capability itself stays pinned by test_typed_album_import_* above.
        self.assertIn('title = "导入在线歌单"', dialog)
        self.assertNotIn('短链暂不支持', dialog)  # r658c：短链已支持，提示删除

    def test_production_route_regexes_cover_five_platforms(self):
        # Exercise regex text extracted from production Kotlin, not a copied parser.
        # This validates route matching only, NOT execution of Kotlin/URI/network dispatch.
        src = (BASE / 'source/direct/OnlineImportReference.kt').read_text()
        playlist = src.split('val playlistId = when (source) {', 1)[1].split('if (playlistId != null)', 1)[0]
        album = src.split('val albumId = when (source) {', 1)[1].split('val valid =', 1)[0]
        for body, source, path, expected in [
            (playlist, 'tx', '/n/yqq/playlist/7217720898.html', '7217720898'),
            (playlist, 'kg', '/yy/special/single/1067062.html', '1067062'),
            (playlist, 'kw', '/h5app/playlist/2736267853', '2736267853'),
            (playlist, 'mg', '/v3/music/playlist/169206251', '169206251'),
            (album, 'tx', '/n/ryqq/albumDetail/003DFRzD192KKD', '003DFRzD192KKD'),
            (album, 'tx', '/n/yqq/album/003DFRzD192KKD.html', '003DFRzD192KKD'),
            (album, 'kg', '/album/210575933.html', '210575933'),
            (album, 'kw', '/album_detail/123456', '123456'),
            (album, 'mg', '/v3/music/album/123456', '123456'),
        ]:
            key = 'else' if source == 'mg' else f'"{source}"'
            branch = body.split(key + ' ->', 1)[1].split('\n            ', 1)[0]
            pattern = re.search(r'group\("""(.*?)"""\)', branch).group(1)
            match = re.fullmatch(pattern, path)
            self.assertIsNotNone(match, (source, path))
            self.assertEqual(expected, match.group(1))
        self.assertIn('"wy" -> if (path == "/playlist") param("id") else null', playlist)
        self.assertIn('"wy" -> if (path == "/album") param("id") else null', album)
        self.assertIn('values.size != 1', src)
        self.assertIn('links.size != 1', src)
        self.assertIn('uri.rawUserInfo != null', src)
        self.assertNotIn('URLDecoder', src)
        self.assertNotIn('group("""/songlist/', src)
        self.assertIn('albumId.any { it.isLetter() }', src)

    def test_all_target_signatures_and_models_exist(self):
        api = (BASE / 'ApiClient.kt').read_text()
        self.assertIn('suspend fun playlistDetailSnapshot(id: String, source: String, seed: Playlist): Playlist?', api)
        direct = (BASE / 'source/direct/PlaylistImportDirect.kt').read_text()
        self.assertIn('suspend fun importOfficialPlaylistDirect(raw: String): Playlist?', direct)
        self.assertIn('reference.kind != OnlineImportKind.PLAYLIST', direct)
        self.assertIn('suspend fun fetchPlaylistDetailPayloadDirect(source: String, rid: String)',
                      (BASE / 'source/direct/PlaylistDetailDirect.kt').read_text())
        models = (BASE / 'Models.kt').read_text()
        for field in ['remoteId', 'source', 'count', 'songs']:
            self.assertIn('val ' + field + ':', models)


if __name__ == '__main__':
    unittest.main(verbosity=2)
