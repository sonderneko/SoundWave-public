#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
api = (base / 'ApiClient.kt').read_text()
main = (base / 'MainActivity.kt').read_text()
source = (base / 'source/MusicSource.kt').read_text()
adapter = (base / 'source/PlatformSourceAdapter.kt').read_text()
registry = (base / 'source/MusicSourceRegistry.kt').read_text()
matrix = (root / 'docs/source-capability-matrix.md').read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()
compact = lambda text: ' '.join(re.sub(r'//[^\n]*', '', text).split())

import_start = api.find('suspend fun importPlaylist(raw: String): Playlist? {')
import_end = api.find('\n    /**\n     * 平台歌单分类', import_start)
import_method = api[import_start:import_end] if import_start >= 0 and import_end >= 0 else ''

parser = (base / 'source/direct/OnlineImportReference.kt').read_text()
snapshot_start = api.index('suspend fun playlistDetailSnapshot(')
snapshot_end = api.index('\n    /**', snapshot_start)
snapshot = api[snapshot_start:snapshot_end]
album_start = api.index('suspend fun albumDetail(')
album_end = api.index('\n    /**', album_start)
album = api[album_start:album_end]

playlist_start = api.find('suspend fun playlistDetail(id: String, source: String = "wy"): List<Song> {')
playlist_end = api.find('\n    /** Same-provider metadata', playlist_start)
playlist_method = api[playlist_start:playlist_end] if playlist_start >= 0 and playlist_end >= 0 else ''
expected_playlist_method = compact('''
    suspend fun playlistDetail(id: String, source: String = "wy"): List<Song> {
        runDirectFallback { fetchPlaylistDetailDirect(source, id.removePrefix("reco_").trim()) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return playlistDetailServer(id, source)
    }
''')

caller_start = main.find('if (showOnlineImportDlg) OnlineImportDialog(')
caller_end = main.find('// ===== 添加到歌单（支持批量） =====', caller_start)
caller = main[caller_start:caller_end] if caller_start >= 0 and caller_end >= 0 else ''
caller_compact = compact(caller)

ids_match = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
platform_ids = re.findall(r'"([^"]+)"', ids_match.group(1)) if ids_match else []
version_match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)', version)
gradle_name = re.search(r'versionName\s+"([^"]+)"', build)
gradle_code = re.search(r'versionCode\s+(\d+)', build)

checks = {
    'typed input validates before any detail request and never guesses URL digits or text songs':
        'parseOnlineImportReference(raw) ?: return null' in import_method
        and all(token not in import_method for token in ('action=import', 'idGuess', 'Regex(', 'realSrc', 'q.contains('))
        and 'val text = raw.trim()' in parser and 'if (text.isEmpty() || text.length > 2000) return null' in parser,
    'typed source is allowlisted with strict ID gates rather than prose defaulting to WY':
        all(token in parser for token in ('domain("music.163.com") -> "wy"', 'domain("y.qq.com") -> "tx"',
            'domain("kugou.com") -> "kg"', 'domain("kuwo.cn") -> "kw"', 'domain("migu.cn") -> "mg"',
            'else -> return null', 'values.size != 1', 'links.size != 1', 'uri.rawUserInfo != null')),
    'playlist direct detail remains preferred and same-ID/source snapshot is its fallback':
        'importOfficialPlaylistDirect(raw) ?: run {' in import_method
        and 'playlistDetailSnapshot(reference.id, reference.source, seed)' in import_method
        and 'val songs = direct?.songs?.takeIf { it.isNotEmpty() } ?: playlistDetailServer(id, source)' in snapshot,
    'albums route only to album detail with retained direct/server fallback':
        'OnlineImportKind.ALBUM -> albumDetail(reference.id, reference.source)' in import_method
        and 'fetchAlbumDetailDirect(source, id)' in album and 'return albumDetailServer(id, source)' in album,
    'canonical source IDs are the exact five platform values and Registry adapter uses ApiClient singleton':
        platform_ids == ['wy', 'kg', 'kw', 'tx', 'mg'] and 'api = ApiClientCatalogGateway' in registry,
    'platform playlist-detail method is a pure same-ID/same-source delegate':
        'override suspend fun playlistDetail(id: String): List<Song> =\n'
        '        api.playlistDetail(id, source = platform)' in adapter
        and 'suspend fun playlistDetail(id: String): List<Song> = emptyList()' in source,
    'ApiClient playlist-detail direct-first and server-fallback behavior is unchanged':
        bool(playlist_method) and compact(playlist_method) == expected_playlist_method,
    'null, empty-ID and cross-provider results fail while real detail metadata survives copy':
        'if (detail.source != reference.source) return null' in import_method
        and 'song.id.isNotBlank() && song.source == reference.source' in import_method
        and 'if (songs.isEmpty()) return null' in import_method
        and 'return detail.copy(id = reference.id, remoteId = reference.id, source = reference.source,' in import_method
        and 'songs = songs.toMutableList()' in import_method,
    'MainActivity still persists only a non-empty import before asynchronous playability checking':
        'ApiClient.importPlaylist(raw)' in caller_compact
        and caller_compact.find('if (pl != null && pl.songs.isNotEmpty())') < caller_compact.find('persistBundle(notify = true)')
        < caller_compact.find('PlaylistImportChecker.checkPlaylistAsync(')
        and 'else { (context as? android.app.Activity)?.window?.decorView?.let { xyz.cncnlp.soundwave.ui.common.AppHaptics.failure(it) } AppNotice.toast("导入失败", AppNotice.Kind.FAILURE) }' in caller_compact,
    'matrix records source inference, empty-ID gate, no-cache order, and unchanged failure contract':
        '## ApiClient 在线导入歌单空列表补详情的 Source Registry 迁移点' in matrix
        and 'id.isNotEmpty() && realSrc.isNotBlank()' in matrix
        and '此导入路径没有业务缓存' in matrix
        and '仍只持久化非空歌单' in matrix,
    'VERSION and Gradle declarations remain synchronized':
        version_match is not None and gradle_name is not None and gradle_code is not None
        and gradle_name.group(1) == version
        and gradle_code.group(1) == version_match.group(3),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('MainActivity online-playlist-import Registry contract guard passed')
PY
