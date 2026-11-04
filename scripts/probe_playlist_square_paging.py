#!/usr/bin/env python3
"""Read-only anonymous QQ/Kuwo square probes. Saves raw responses; no login or app data."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import urllib.parse
import requests

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'

def qq_url(module, method, params, key='playlist'):
    data = {key: {'module': module, 'method': method, 'param': params}}
    return 'https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=' + urllib.parse.quote(json.dumps(data, separators=(',', ':')))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=Path)
    parser.add_argument('--boundaries-only', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    def get(label, url, source):
        meta = {'label': label, 'url': url}
        try:
            r = requests.get(url, headers={'User-Agent': UA, 'Referer': 'https://y.qq.com/' if source == 'tx' else 'https://www.kuwo.cn/', 'Accept': 'application/json,text/plain,*/*'}, timeout=15)
            (args.output / (label + '.json')).write_bytes(r.content)
            meta.update(status=r.status_code)
            root = r.json()
            meta['root_type'] = type(root).__name__
            meta['code'] = root.get('code') if isinstance(root, dict) else None
            if isinstance(root, list):
                def playlists(rows):
                    found = []
                    for item in rows:
                        if str(item.get('digest')) == '8':
                            found.append(str(item['id']))
                        for key in ('list', 'child', 'data'):
                            if isinstance(item.get(key), list):
                                found.extend(playlists(item[key]))
                    return found
                ids = playlists(root)
                meta.update(sections=len(root), rows=len(set(ids)), ids=list(dict.fromkeys(ids)))
            elif source == 'tx' and 'playlist' in root:
                payload = root['playlist'].get('data', {})
                data = payload.get('content', payload)
                rows = data.get('v_item', data.get('v_playlist', []))
                meta.update(data_keys=list(data), rows=len(rows), total=data.get('total_cnt', data.get('total')), ids=[str(i.get('basic', i).get('tid')) for i in rows])
            elif source == 'kw' and isinstance(root.get('data'), dict):
                data = root['data']; rows = data.get('data', [])
                meta.update(data_keys=list(data), rows=len(rows), total=data.get('total'), ids=[str(i.get('id')) for i in rows])
            (args.output / (label + '.meta.json')).write_text(json.dumps(meta, ensure_ascii=False, indent=2) + '\n')
            print(json.dumps(meta, ensure_ascii=False), flush=True)
            return root
        except Exception as e:
            meta['error'] = str(e)
            (args.output / (label + '.meta.json')).write_text(json.dumps(meta, ensure_ascii=False, indent=2) + '\n')
            print(json.dumps(meta, ensure_ascii=False), flush=True)
            return {}
    if args.boundaries_only:
        jobs = []
        for source in ('tx', 'kw'):
            first = json.loads((args.output / f'{source}-all-p1.meta.json').read_text())
            last = (int(first['total']) + 29) // 30
            for page in (last, last + 1):
                url = qq_url('playlist.PlayListPlazaServer', 'get_playlist_by_tag', {'id': 10000000, 'sin': (page-1)*30, 'size': 30, 'order': 5, 'cur_page': page}) if source == 'tx' else f'https://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?order=hot&loginUid=0&loginSid=0&appUid=76039576&pn={page}&rn=30'
                jobs.append((f'{source}-all-p{page}', url, source))
        jobs.append(('kw-zone-211', 'https://mobileinterfaces.kuwo.cn/er.s?type=get_pc_qz_data&f=web&id=211&prod=pc', 'kw'))
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            list(pool.map(lambda j: get(*j), jobs))
        return
    tx = get('tx-tags', qq_url('playlist.PlaylistAllCategoriesServer', 'get_all_categories', {'qq': ''}, 'tags'), 'tx')
    kw = get('kw-tags', 'https://wapi.kuwo.cn/api/pc/classify/playlist/getTagList?cmd=rcm_keyword_playlist&user=0&prod=kwplayer_pc_9.0.5.0&vipver=9.0.5.0&source=kwplayer_pc_9.0.5.0&loginUid=0&loginSid=0&appUid=76039576', 'kw')
    txrefs = [i for g in tx.get('tags', {}).get('data', {}).get('v_group', []) for i in g.get('v_item', []) if i.get('usable', 1) != 0]
    kwrefs = [i for g in kw.get('data', []) for i in g.get('data', []) if str(i.get('digest')) == '10000']
    jobs = []
    for page in (1, 2, 3):
        jobs.append((f'tx-all-p{page}', qq_url('playlist.PlayListPlazaServer', 'get_playlist_by_tag', {'id': 10000000, 'sin': (page-1)*30, 'size': 30, 'order': 5, 'cur_page': page}), 'tx'))
        jobs.append((f'kw-all-p{page}', f'https://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?order=hot&loginUid=0&loginSid=0&appUid=76039576&pn={page}&rn=30', 'kw'))
        for ref in txrefs[:2]:
            ident = int(ref['id'])
            jobs.append((f'tx-tag-{ident}-p{page}', qq_url('playlist.PlayListCategoryServer', 'get_category_content', {'titleid': ident, 'caller': '0', 'category_id': ident, 'size': 30, 'page': page-1, 'use_page': 1}), 'tx'))
        for ref in kwrefs[:2]:
            ident = ref['id']
            jobs.append((f'kw-tag-{ident}-p{page}', f'https://wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList?id={ident}&loginUid=0&loginSid=0&appUid=76039576&pn={page}&rn=30', 'kw'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(lambda j: get(*j), jobs))

if __name__ == '__main__':
    main()
