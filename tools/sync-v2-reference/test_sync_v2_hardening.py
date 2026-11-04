import unittest
from sync_v2 import Store, Invalid
from test_sync_v2 import batch

class PayloadBoundaryTests(unittest.TestCase):
    def record(self, value):
        return dict(kind='song', id='wy:123', deleted=False, value=value)

    def rejects(self, payload):
        s = Store()
        with self.assertRaises(Invalid): s.apply('alice', payload)
        self.assertEqual((0, {'records': {}, 'events': {}}), s.snapshot('alice'))

    def test_decoded_secret_keys_are_rejected_recursively(self):
        for key in ('refreshToken', 'access_token', 'API-Key', 'Authorization', 'localPath'):
            with self.subTest(key=key):
                self.rejects(batch(records=[self.record({'metadata': [{'nested': {key: 'sensitive'}}]})]))

    def test_private_paths_are_not_limited_to_storage_uri(self):
        for value in ('file:/data/user/a', 'CONTENT://media/a', '/data/user/0/a', '/sdcard/a', 'C:\\Users\\a'):
            with self.subTest(value=value): self.rejects(batch(records=[self.record({'path': value})]))

    def test_ordinary_secret_word_in_title_is_not_a_secret_field(self):
        s = Store(); s.apply('alice', batch(records=[self.record({'title': 'password', 'artist': 'Token'})]))
        self.assertEqual('password', s.page('alice', 'records')['items'][0]['value']['title'])

    def test_wrong_json_container_types_are_domain_errors(self):
        for payload in (None, [], batch(records=[None]), batch(records=[{'kind': []}]), batch(events=[[]])):
            with self.subTest(payload=payload): self.rejects(payload)

    def test_float_protocol_version_is_not_an_integer_version(self):
        self.rejects({**batch(), 'version': 2.0})

    def test_non_json_or_non_finite_values_are_rejected_before_transaction(self):
        for value in (float('nan'), float('inf'), b'bytes', {1, 2}):
            with self.subTest(value=repr(value)): self.rejects(batch(records=[self.record({'data': value})]))

    def test_nesting_and_node_budget(self):
        value = {}
        for _ in range(20): value = {'child': value}
        self.rejects(batch(records=[self.record(value)]))
        self.rejects(batch(records=[self.record({'values': [0] * 10001})]))

    def test_invalid_cursor_shape_is_a_domain_error(self):
        with self.assertRaises(Invalid): Store().page('alice', 'events', cursor=[])

if __name__ == '__main__': unittest.main()
