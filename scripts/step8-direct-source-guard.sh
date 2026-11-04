#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import json, re, sys, textwrap, subprocess
root=Path(sys.argv[1]); base=root/'app/src/main/java/xyz/cncnlp/soundwave'
source=(root/'app/src/test/java/xyz/cncnlp/soundwave/source/direct/PlaylistDetailDirectTest.kt').read_text()
samples=re.findall(r'val raw = """\n(.*?)\n\s*"""\.trimIndent\(\)', source, re.S)
for name,index in [('kw',0),('tx',1),('mg',2),('kg',3)]:
    fixture=(root/f'app/src/test/resources/step8/{name}-playlist-reused.json').read_text()
    assert fixture.rstrip('\n') == textwrap.dedent(samples[index]), name+' fixture provenance drift'
    json.loads(fixture)
api=(base/'ApiClient.kt').read_text(); adapter=(base/'source/PlatformSourceAdapter.kt').read_text()
for symbol in ['fetchPlaylistCategoryGroupsDirect','fetchArtistDetailDirect','importOfficialPlaylistDirect','fetchRecommendPlaylistsDirect']:
    assert api.count('import xyz.cncnlp.soundwave.source.direct.'+symbol+'\n')==1, symbol+' import'
    assert re.search(r'\b'+symbol+r'\(',api),symbol+' production call'
assert 'searchOfficialEntitiesDirect(platform, keyword, type)' in adapter
assert 'if (it.available) return it.songs' in adapter
assert 'return api.searchCancellable(keyword, source = platform, type = type)' in adapter
assert 'catch (cancelled: CancellationException)' in adapter
assert 'throw cancelled' in adapter
for file in ['PlaylistCategoriesDirect.kt','PlaylistRecommendDirect.kt','AdditionalAlbumDirect.kt','AdditionalPlaylistMetadata.kt','ArtistDirectFetch.kt','TypedEntitiesDirect.kt','PlaylistImportDirect.kt']:
    text=(base/'source/direct'/file).read_text()
    for forbidden in ['PlatformAccountStore','CookieManager','auth(', '/music/http', 'ApiClient.get(', 'DEFAULT_SERVER_URL']:
        assert forbidden not in text, file+' credential/proxy coupling: '+forbidden
    assert 'Song(id = albumId' not in text
path='app/src/main/java/xyz/cncnlp/soundwave/source/direct/HttpPolicy.kt'
baseline=subprocess.check_output(['git','show','HEAD:'+path],cwd=root,text=True)
assert (root/path).read_text()==baseline,'HttpPolicy security policy changed'
import hashlib
transport=(base/'source/direct/PlatformHttp.kt').read_text()
assert hashlib.sha256(transport.encode()).hexdigest()=='1f969c16602fadc64144fa3a36ed5811e4e77a217fe292bc6ad2fb9091464571','reviewed cancellation/structured transport changed'
assert transport.count('val outcome = platformHttpRequestOutcome {')==2
assert 'platformHttpRequestOutcome' in transport
outcome=(base/'source/direct/PlatformTransportOutcome.kt').read_text()
assert 'throw cancellation' in outcome or 'CancellationException' in outcome
assert 'PlatformTransportCooldown' in outcome
recommend=(base/'source/direct/PlaylistRecommendDirect.kt').read_text()
assert hashlib.sha256(recommend.encode()).hexdigest()=='05ad2c9e970fc01d6a1684d346312a8e40bd9fe3f8969aaeacd393f5c7d057f0','reviewed recommendation mapping/parser changed'
assert 'cont=ninfo&node=${URLEncoder.encode(node, "UTF-8")}' in recommend
assert 'row.copy(id = detailId, remoteId = detailId)' in recommend
assert 'array.getJSONObject(it)' in recommend
followup=(root/'app/src/test/java/xyz/cncnlp/soundwave/source/direct/Step8FollowupRegressionTest.kt').read_text()
assert 'AUTHORED, NOT EXECUTED' in followup and followup.count('@Test')==5
imports=(base/'source/direct/OnlineImportReference.kt').read_text()
wrapper=(base/'source/direct/PlaylistImportDirect.kt').read_text()
assert 'parseOnlineImportReference(raw)' in wrapper
assert 'reference.kind != OnlineImportKind.PLAYLIST' in wrapper
for token in ['uri.rawUserInfo != null','uri.host?.lowercase()', 'domain("music.163.com")', 'values.size != 1', 'else -> return null']:
    assert token in imports,'strict share parser missing '+token
regression=(root/'app/src/test/java/xyz/cncnlp/soundwave/source/direct/Step8DirectRegressionTest.kt').read_text()
assert 'AUTHORED, NOT EXECUTED' in regression
assert regression.count('@Test') >= 7
print('Step8 direct source/fixture provenance guard PASS; JUnit NOT RUN; no network')
PY
