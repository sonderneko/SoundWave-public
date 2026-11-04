"""Execute the actual Runtime request-selection JavaScript with Node, no WebView mocks of selection logic."""
from pathlib import Path
import subprocess,json
s=(Path(__file__).resolve().parents[1]/'app/src/main/java/xyz/cncnlp/soundwave/ScriptRuntime.kt').read_text()
block=s[s.index('                var sources = entry.sources || {};'):s.index('                Promise.resolve(ret)',s.index('                var sources = entry.sources || {};'))]
cases=[
 ({'kg':{'actions':['musicUrl'],'qualitys':['128k','320k','flac24bit']}},'kg','hires','flac24bit'),
 ({'kg':{'actions':['musicUrl'],'qualitys':['128k']}},'kg','flac',None),
 ({'kg':{'actions':['pic'],'qualitys':['flac']}},'kg','flac',None),
 ({'wy':{'actions':['musicUrl'],'qualitys':['flac']}},'kg','flac',None),
 ({},'kg','flac','flac'),
 ({'kg':{'actions':['musicUrl'],'qualitys':[]}},'kg','128k',None),
 ({'kugou':{'actions':['musicUrl'],'qualitys':['flac24bit']}},'kg','hires','flac24bit'),
 ({'kg':{'actions':['musicUrl']}},'kg','320k','320k'),
]
for i,(sources,source,q,want) in enumerate(cases):
 js='let called=null; function run(){ let entry={sources:'+json.dumps(sources)+',handlers:{request:x=>{called=x;return "";}}};let __src='+json.dumps(source)+';let __q='+json.dumps(q)+';let __info={id:"stable",albumId:"album"}; function done(){};'+block+'};run(); console.log(JSON.stringify(called));'
 r=subprocess.run(['node','-e',js],capture_output=True,text=True);assert r.returncode==0,r.stderr
 got=json.loads(r.stdout);actual=got['info']['type'] if got else None
 assert actual==want,f'case {i}: wanted {want}, got {actual}'
 if got: assert got['info']['musicInfo']['albumId']=='album'
 print('PASS',i,actual)
print('OK: 8 Runtime capability/quality request-envelope cases')
