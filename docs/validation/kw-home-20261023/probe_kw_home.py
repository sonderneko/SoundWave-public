#!/usr/bin/env python3
"""Read-only evidence capture for the existing Kuwo home source and official digital topic."""
import concurrent.futures
import datetime
import json
from pathlib import Path
import requests

OUT = Path(__file__).resolve().parent
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
BASE = 'https://wapi.kuwo.cn/openapi/v1/pc/disc/shelves'
CASES = [
    ('shelves-p1-r20-home', BASE + '?pn=1&rn=20', 'https://www.kuwo.cn/'),
    ('shelves-p1-r12-exact', BASE + '?pn=1&rn=12', 'https://www.kuwo.cn/'),
    ('shelves-p1-r30', BASE + '?pn=1&rn=30', 'https://www.kuwo.cn/'),
    ('shelves-p2-r12', BASE + '?pn=2&rn=12', 'https://www.kuwo.cn/'),
    ('shelves-p0-r12', BASE + '?pn=0&rn=12', 'https://www.kuwo.cn/'),
    ('digital-official-data', 'https://mobile.kuwo.cn/mpage/fspage/zhuanqu/getdata.jsp?mainid=48', 'https://mobile.kuwo.cn/mpage/fspage/zhuanqu/index.html?mainid=48'),
]

def capture(case):
    name, url, referer = case
    meta = {'name': name, 'url': url, 'time_utc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    try:
        r = requests.get(url, headers={'User-Agent': UA, 'Referer': referer, 'Accept': 'application/json,text/plain,*/*'}, timeout=20)
        (OUT / (name + '.body')).write_bytes(r.content)
        meta.update(status=r.status_code, content_type=r.headers.get('Content-Type'), bytes=len(r.content), final_url=r.url)
        try:
            root = r.json()
            meta.update(code=root.get('code'), success=root.get('success'))
            data = root.get('data') or {}
            if isinstance(data, dict) and isinstance(data.get('albumList'), list):
                rows = data['albumList']
                meta.update(size=data.get('size'), raw_count=len(rows), ids=[x.get('albumid') for x in rows], names=[x.get('album') for x in rows])
            if name == 'digital-official-data':
                meta['root_keys'] = list(root)
                meta['column_count'] = len(root.get('column') or [])
                meta['content'] = root.get('content')
        except (ValueError, TypeError) as e:
            meta['parse_error'] = type(e).__name__
    except requests.RequestException as e:
        meta['request_error'] = type(e).__name__
        meta['message'] = str(e)
    (OUT / (name + '.meta.json')).write_text(json.dumps(meta, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(meta, ensure_ascii=False), flush=True)
    return meta

if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(capture, CASES))
    (OUT / 'probe-summary.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
