import copy
import os
import tempfile
import unittest
from sync_v2 import Store, Conflict, Invalid

def event(i=1): return dict(id=f'installA:{i}',device='installA',seq=i,song='wy:123',artist='wy:artist:7',at=1700000000000+i,duration_ms=10000,deleted=False)
def batch(rev=0,records=None,events=None): return dict(version=2,base_revision=rev,records=records or [],events=events or [])
class Tests(unittest.TestCase):
    def setUp(self): self.s=Store()
    def test_owner_isolation(self):
        self.s.apply('alice',batch(events=[event()])); self.assertEqual({},self.s.counts('bob')['songs'])
    def test_retry_deduplicates_counter(self):
        self.s.apply('alice',batch(events=[event()])); self.s.apply('alice',batch(1,events=[event()])); self.assertEqual(1,self.s.counts('alice')['songs']['wy:123'])
    def test_multidevice_counts_and_duration(self):
        e=event(); b={**e,'id':'installB:1','device':'installB'}
        self.s.apply('alice',batch(events=[e,b])); self.assertEqual(20000,self.s.counts('alice')['duration_ms'])
    def test_stale_revision_rejects_entire_write(self):
        self.s.apply('alice',batch(events=[event()]));
        with self.assertRaises(Conflict): self.s.apply('alice',batch(events=[event(2)]))
        self.assertEqual(1,len(self.s.snapshot('alice')[1]['events']))
    def test_tombstone_prevents_event_resurrection(self):
        e=event(); self.s.apply('alice',batch(events=[e])); self.s.apply('alice',batch(1,events=[{**e,'deleted':True}]))
        self.s.apply('alice',batch(2,events=[e])); self.assertEqual({},self.s.counts('alice')['songs'])
    def test_history_over_300_pages_without_retention_cutoff(self):
        self.s.apply('alice',batch(events=[event(i) for i in range(1,401)]))
        rows=[]; cursor=None
        while True:
            p=self.s.page('alice','events',cursor,37); rows+=p['items']; cursor=p['next']
            if cursor is None: break
        self.assertEqual(400,len(rows)); self.assertEqual(400,len({e['id'] for e in rows}))
    def test_changed_revision_requires_restart_pagination(self):
        self.s.apply('alice',batch(events=[event(),event(2)])); c=self.s.page('alice','events',limit=1)['next']
        self.s.apply('alice',batch(1,events=[event(3)]))
        with self.assertRaises(Conflict): self.s.page('alice','events',c)
    def test_record_field_coverage(self):
        kinds=['favorite','playlist','album','song','avatar','playlist_order','preference']
        rows=[dict(kind=k,id='x',deleted=False,value={'name':'fixture','cover':'https://example.com/a.jpg','songs':['wy:1'],'order':[2,1]}) for k in kinds]
        self.s.apply('alice',batch(records=rows)); self.assertEqual(set(kinds),{r['kind'] for r in self.s.page('alice','records')['items']})
    def test_record_delete_and_explicit_recreate_require_current_revision(self):
        r=dict(kind='playlist',id='x',deleted=True,value=None); self.s.apply('alice',batch(records=[r]))
        with self.assertRaises(Conflict): self.s.apply('alice',batch(records=[{**r,'deleted':False,'value':{}}]))
        self.s.apply('alice',batch(1,records=[{**r,'deleted':False,'value':{}}])); self.assertFalse(self.s.page('alice','records')['items'][0]['deleted'])
    def test_rejects_local_path_and_unknown_fields(self):
        for val in ({'uri':'file:///tmp/song'},{'password':'fake'}):
            with self.assertRaises(Invalid): self.s.apply('alice',batch(records=[dict(kind='song',id='x',deleted=False,value=val)]))
        with self.assertRaises(Invalid): self.s.apply('alice',{**batch(),'owner':'bob'})
    def test_immutable_event_identity_collision_rolls_back(self):
        self.s.apply('alice',batch(events=[event()]))
        with self.assertRaises(Invalid): self.s.apply('alice',batch(1,events=[{**event(),'song':'wy:other'}]))
        self.assertEqual(1,self.s.snapshot('alice')[0])
    def test_persist_and_reopen(self):
        with tempfile.TemporaryDirectory() as d:
            path=os.path.join(d,'state.sqlite'); a=Store(path); a.apply('alice',batch(events=[event()])); a.db.close()
            b=Store(path); self.assertEqual(1,b.counts('alice')['songs']['wy:123']); b.db.close()
    def test_unsupported_version_no_write(self):
        with self.assertRaises(Invalid): self.s.apply('alice',{**batch(),'version':3})
        self.assertEqual(0,self.s.snapshot('alice')[0])
if __name__=='__main__': unittest.main(verbosity=2)
