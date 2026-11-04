#!/usr/bin/env python3
"""Execute production runtime boot/build JS. Android bridge is a recording seam only."""
from pathlib import Path
import json, re, subprocess
BASE = Path(__file__).resolve().parents[1] / 'app/src/main/java/xyz/cncnlp/soundwave'
s = (BASE / 'ScriptRuntime.kt').read_text()
boot = s.split('private fun runtimeBootJs(): String = """', 1)[1].split('""".trimIndent()', 1)[0]
build = s.split('// musicInfoStr is raw JSON object literal', 1)[1].split('return """', 1)[1].split('""".trimIndent()', 1)[0]
script = "lx.on('request',async function(){globalThis.handle=lx.request('https://example.com/audio');return (await globalThis.handle).body.url;});lx.send('inited',{});"
def invocation(cap, sid):
    import base64
    replacements = {'${JSONObject.quote(capability)}': json.dumps(cap), '${JSONObject.quote(scriptId)}':json.dumps(sid), '${JSONObject.quote(source)}':'"kg"', '${JSONObject.quote(quality)}':'"320k"', '${JSONObject.quote(scriptMeta)}':'"{}"', '$musicInfoStr':'{}', '${JSONObject.quote(b64)}': json.dumps(base64.b64encode(script.encode()).decode()), '${runtimeBootJs()}':boot}
    out=build
    for a,b in replacements.items(): out=out.replace(a,b)
    return out
harness = '''
const assert=require('assert');global.window=global;let calls=[],cancelled=[],results=[];
let live=new Set(['play','download']);
global.NativeBridge={nativeFetch(...a){calls.push(a)},nativeCancel(id){cancelled.push(id)},onUrlResult(cap,url){results.push([cap,url])}};
process.on('unhandledRejection',()=>{});
'''+boot+invocation('play','shared')+'''
assert.equal(calls.length,1);
assert.equal(calls[0][5],'play','native network request must carry its URL request owner');
assert.equal(typeof window.__swCancelRequest,'function','request-level JS cancellation missing');
window.__swCancelRequest('play');
assert.deepEqual(cancelled,[calls[0][4]],'cancel exact child transport');
'''+invocation('download','shared')+'''
assert.equal(calls.length,2);
assert.equal(calls[1][5],'download');
window.__swDispatch(calls[0][4],200,'{"url":"https://example.com/stale.mp3"}','{}');
window.__swDispatch(calls[1][4],200,'{"url":"https://example.com/new.mp3"}','{}');
'''+invocation('http-error','error-script')+'''
assert.equal(calls.length,3);
const failedCid=calls[2][4];
window.__swDispatch(failedCid,500,'controlled error','{"Location":"https://fixture.invalid/private"}');
setImmediate(()=>{assert(!results.some(r=>r[0]==='play' && r[1]),'stale play must not produce URL');assert(results.some(r=>r[0]==='download' && r[1].endsWith('new.mp3')),'download survives play cancellation');assert(!Object.prototype.hasOwnProperty.call(window.__swResponseHeaders,failedCid),'HTTP error callback must release its response-header slot');console.log('PASS production JS owner propagation, exact child abort, stale result, error-header cleanup, same-script download survival');process.exit(0)});
'''
r=subprocess.run(['node'],input=harness,capture_output=True,text=True)
print(r.stdout, end='');print(r.stderr,end='');raise SystemExit(r.returncode)
