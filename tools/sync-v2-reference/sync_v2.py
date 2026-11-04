"""Offline executable protocol reference, NOT the deployed SoundWave server.
A production adapter must authenticate outside this module and bind `owner` from
its verified principal, never from a request body. No HTTP routes are defined.
"""
import copy
import json
import sqlite3
import math
import re

KINDS = {'favorite', 'playlist', 'album', 'song', 'avatar', 'playlist_order', 'preference'}
class Conflict(Exception): pass
class Invalid(Exception): pass

class Store:
    def __init__(self, path=':memory:'):
        self.db = sqlite3.connect(path)
        self.db.execute('CREATE TABLE IF NOT EXISTS state(owner TEXT PRIMARY KEY, rev INTEGER NOT NULL, body TEXT NOT NULL)')
        self.db.commit()

    def snapshot(self, owner):
        if not isinstance(owner, str) or not owner.strip(): raise Invalid('authenticated owner required')
        row = self.db.execute('SELECT rev,body FROM state WHERE owner=?', (owner,)).fetchone()
        return (row[0], json.loads(row[1])) if row else (0, {'records': {}, 'events': {}})

    @staticmethod
    def validate(payload):
        if not isinstance(payload, dict): raise Invalid('payload object required')
        if set(payload) != {'version', 'base_revision', 'records', 'events'}: raise Invalid('field allowlist')
        if type(payload['version']) is not int or payload['version'] != 2: raise Invalid('unsupported version; negotiate before sending')
        if type(payload['base_revision']) is not int or payload['base_revision'] < 0: raise Invalid('revision')
        if not isinstance(payload['records'], list) or len(payload['records']) > 500: raise Invalid('record batch')
        if not isinstance(payload['events'], list) or len(payload['events']) > 500: raise Invalid('event batch')
        def key(v):
            return isinstance(v,str) and 0 < len(v) <= 256 and all(ord(x)>=32 for x in v)
        for r in payload['records']:
            if not isinstance(r, dict): raise Invalid('record object required')
            if set(r) != {'kind','id','deleted','value'} or not isinstance(r['kind'], str) or r['kind'] not in KINDS or not key(r['id']) or type(r['deleted']) is not bool: raise Invalid('record shape')
            if r['deleted'] and r['value'] is not None: raise Invalid('tombstone must not retain value')
            if not r['deleted'] and not isinstance(r['value'], dict): raise Invalid('value object required')
            # This is a conservative offline contract, not a production field schema.
            # Inspect decoded keys/values, not JSON substrings (which miss refreshToken
            # while rejecting harmless titles such as "password"). Bound work first.
            pending = [(r['value'], 0)]
            nodes = 0
            forbidden = {'password', 'passwd', 'pwd', 'token', 'accesstoken',
                         'refreshtoken', 'apikey', 'secret', 'clientsecret',
                         'authorization', 'cookie', 'credentials', 'localuri',
                         'localpath', 'filepath'}
            while pending:
                value, depth = pending.pop()
                nodes += 1
                if depth > 16 or nodes > 10000: raise Invalid('record nesting/work limit')
                if isinstance(value, dict):
                    for name, child in value.items():
                        if not isinstance(name, str) or len(name) > 256: raise Invalid('object key')
                        if re.sub(r'[^a-z0-9]', '', name.lower()) in forbidden:
                            raise Invalid('private/device-only field')
                        pending.append((child, depth + 1))
                elif isinstance(value, list):
                    pending.extend((child, depth + 1) for child in value)
                elif isinstance(value, str):
                    lowered = value.lower()
                    if (lowered.startswith(('file:', 'content:', '/storage/', '/data/user/', '/sdcard/'))
                        or re.match(r'^[a-z]:[\\/]', lowered)):
                        raise Invalid('private/device-only path')
                elif value is not None and type(value) not in (bool, int, float):
                    raise Invalid('JSON value required')
                elif type(value) is float and not math.isfinite(value):
                    raise Invalid('finite JSON number required')
            raw = json.dumps(r['value'], ensure_ascii=False, allow_nan=False)
            if len(raw.encode()) > 1024*1024: raise Invalid('record too large; use verified blob adapter')
        seen=set()
        for e in payload['events']:
            if not isinstance(e, dict): raise Invalid('event object required')
            if set(e) != {'id','device','seq','song','artist','at','duration_ms','deleted'}: raise Invalid('event allowlist')
            if not all(key(e[k]) for k in ('id','device','song','artist')): raise Invalid('event identity')
            if type(e['seq']) is not int or e['seq'] < 1 or e['id'] != f"{e['device']}:{e['seq']}": raise Invalid('immutable install UUID + monotonic sequence required')
            if type(e['at']) is not int or e['at'] < 0 or type(e['duration_ms']) is not int or not 0 <= e['duration_ms'] <= 86400000 or type(e['deleted']) is not bool: raise Invalid('event values')
            if e['id'] in seen: raise Invalid('duplicate event in batch')
            seen.add(e['id'])

    def apply(self, owner, payload):
        self.validate(payload)
        # SQLite serializes CAS and mutation in a single transaction; no lost-update window.
        self.db.execute('BEGIN IMMEDIATE')
        try:
            rev, state = self.snapshot(owner)
            if payload['base_revision'] != rev: raise Conflict(rev)
            new = copy.deepcopy(state)
            for r in payload['records']:
                new['records'][r['kind']+':'+r['id']] = copy.deepcopy(r)
            for e in payload['events']:
                old = new['events'].get(e['id'])
                if old:
                    # Tombstones win replay, and retained identity/time/counter facts cannot change.
                    if any(old[k] != e[k] for k in old if k != 'deleted'): raise Invalid('event identity collision')
                    e = {**e, 'deleted': old['deleted'] or e['deleted']}
                new['events'][e['id']] = copy.deepcopy(e)
            next_rev=rev+1
            self.db.execute('INSERT INTO state VALUES(?,?,?) ON CONFLICT(owner) DO UPDATE SET rev=excluded.rev,body=excluded.body', (owner,next_rev,json.dumps(new,ensure_ascii=False)))
            self.db.commit()
            return next_rev
        except Exception:
            self.db.rollback()
            raise

    def page(self, owner, kind, cursor=None, limit=100):
        if kind not in ('records','events') or type(limit) is not int or not 1<=limit<=500: raise Invalid('page parameters')
        rev, state=self.snapshot(owner)
        after=''
        if cursor is not None:
            if not isinstance(cursor, dict): raise Invalid('cursor object required')
            if set(cursor) != {'revision','after'} or cursor['revision'] != rev: raise Conflict(rev)
            if not isinstance(cursor['after'],str): raise Invalid('cursor')
            after=cursor['after']
        keys=sorted(k for k in state[kind] if k>after)
        selected=keys[:limit]
        return {'version':2,'revision':rev,'items':[state[kind][k] for k in selected],
                'next':{'revision':rev,'after':selected[-1]} if len(keys)>limit else None}

    def counts(self, owner):
        # Derived from uniquely identified events, not additive uploads of ambiguous old totals.
        _,state=self.snapshot(owner); songs={}; artists={}; milliseconds=0
        for e in state['events'].values():
            if e['deleted']: continue
            songs[e['song']]=songs.get(e['song'],0)+1
            artists[e['artist']]=artists.get(e['artist'],0)+1
            milliseconds += e['duration_ms']
        return {'songs':songs,'artists':artists,'duration_ms':milliseconds}
