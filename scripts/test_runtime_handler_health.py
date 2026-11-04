#!/usr/bin/env python3
"""Run production boot/build JS; NativeBridge only records health and URL events.

No Android SDK required. Node does not enforce browser CSP; the existing
Robolectric isolation suite and WebView guard retain that separate coverage.
"""
import base64
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / 'app/src/main/java/xyz/cncnlp/soundwave/ScriptRuntime.kt').read_text()
boot = source.split('private fun runtimeBootJs(): String = """', 1)[1].split('""".trimIndent()', 1)[0]
build = source.split('// musicInfoStr is raw JSON object literal', 1)[1].split('return """', 1)[1].split('""".trimIndent()', 1)[0]
# Minimal reproduction of the exact CI failure (isolation test lines 80-81).
for hook in ('window.__swHandlerStart(CAP)', 'window.__swHandlerReturned(CAP)'):
    assert hook in boot, f'runtimeBootJs missing tracked invocation: {hook}'


def invocation(cap, handler):
    script = "lx.on('request'," + handler + ");lx.send('inited',{});"
    values = {
        '${JSONObject.quote(capability)}': json.dumps(cap),
        '${JSONObject.quote(scriptId)}': json.dumps(cap),
        '${JSONObject.quote(source)}': '"kg"',
        '${JSONObject.quote(quality)}': '"320k"',
        '${JSONObject.quote(scriptMeta)}': '"{}"',
        '$musicInfoStr': '{}',
        '${JSONObject.quote(b64)}': json.dumps(base64.b64encode(script.encode()).decode()),
        '${runtimeBootJs()}': boot,
    }
    js = build
    for key, value in values.items():
        js = js.replace(key, value)
    assert '${' not in js
    return js


harness = """
const assert = require('assert'); global.window = global;
const events = [], active = new Set(), results = [];
global.NativeBridge = {
  handlerStart(cap) { events.push(['start', cap]); active.add(cap); },
  handlerReturned(cap) { events.push(['returned', cap]); active.delete(cap); },
  onUrlResult(cap, url) { results.push([cap, url]); }
};
""" + boot + invocation('sync', "function(){assert(active.has('sync'));return 'https://example.com/sync.mp3';}") + invocation('async', "function(){assert(active.has('async'));return new Promise(resolve=>{global.finish=resolve;});}") + invocation('throw', "function(){assert(active.has('throw'));throw new Error('handler failed');}") + """
assert.deepStrictEqual(events, [
  ['start','sync'], ['returned','sync'],
  ['start','async'], ['returned','async'],
  ['start','throw'], ['returned','throw']
], 'each synchronous handler extent must close, including throws');
assert.strictEqual(active.size, 0, 'a pending Promise is not a blocked synchronous handler');
assert.deepStrictEqual(results, [['throw','']], 'sync throw retains empty URL failure result');
finish('https://example.com/async.mp3');
setImmediate(() => {
  assert(results.some(r => r[0] === 'sync' && r[1].endsWith('/sync.mp3')));
  assert(results.some(r => r[0] === 'async' && r[1].endsWith('/async.mp3')));
  assert.strictEqual(results.length, 3);
  console.log('PASS production boot/build JS: synchronous success, pending Promise, synchronous throw, paired health notifications and URL results');
});
"""
subprocess.run(['node'], input=harness, text=True, check=True)
