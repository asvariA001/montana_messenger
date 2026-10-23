import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from snapshot import AUDIT_SOURCE, FOLDERS, LOCKS, inventory


class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Audit fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'commit.gpgsign', 'false')
        self.git('config', 'core.hooksPath', '/dev/null')
        for path in list(LOCKS) + [f + '/fixture.txt' for f in FOLDERS]:
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('synthetic fixture\n')
        self.commit()
        self.rows = [f'| `{f}` | fixture | `abcdef` | `{self.git("rev-parse", "HEAD:" + f)}` | fixture |'
                     for f in FOLDERS]
        self.write_releases(self.rows)
        self.commit()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], stderr=subprocess.PIPE).decode().strip()

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-qm', 'synthetic audit fixture')

    def write_releases(self, rows):
        (self.root / 'RELEASES.md').write_text('<!-- sources:start -->\n' + '\n'.join(rows) + '\n<!-- sources:end -->\n')

    def test_reproducible_inventory_uses_public_commit_and_all_trees(self):
        result = inventory(self.root, 'HEAD')
        self.assertEqual(result, inventory(self.root, 'HEAD'))
        self.assertEqual(result['public_commit'], self.git('rev-parse', 'HEAD'))
        self.assertEqual(result['protocol_audit_source_tree'], self.git('rev-parse', 'HEAD:' + AUDIT_SOURCE))
        self.assertEqual(set(result['sources']), set(FOLDERS))
        self.assertEqual(set(result['lock_sha256']), set(LOCKS))
        json.dumps(result, allow_nan=False)

    def test_local_edits_are_not_misrepresented_as_committed_inputs(self):
        original = inventory(self.root, 'HEAD')
        (self.root / LOCKS[0]).write_text('local changes\n')
        self.assertEqual(inventory(self.root, 'HEAD'), original)
        self.assertIn('working-tree edits', original['excludes'])

    def test_stale_tree_fails(self):
        (self.root / LOCKS[0]).write_text('new published bytes\n')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'tree mismatch'):
            inventory(self.root, 'HEAD')

    def test_missing_row_fails(self):
        self.write_releases(self.rows[:-1])
        self.commit()
        with self.assertRaisesRegex(ValueError, 'all three'):
            inventory(self.root, 'HEAD')

    def test_duplicate_row_fails(self):
        self.write_releases(self.rows + [self.rows[0]])
        self.commit()
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            inventory(self.root, 'HEAD')

    def test_unknown_folder_fails(self):
        self.write_releases(self.rows + ['| `other` | x | x | `' + 'a'*40 + '` | x |'])
        self.commit()
        with self.assertRaisesRegex(ValueError, 'unexpected'):
            inventory(self.root, 'HEAD')

    def test_invalid_hash_fails(self):
        self.write_releases([self.rows[0].replace(self.git('rev-parse', 'HEAD:core'), 'wrong')] + self.rows[1:])
        self.commit()
        with self.assertRaisesRegex(ValueError, 'malformed folder tree'):
            inventory(self.root, 'HEAD')

    def test_duplicate_markers_fail(self):
        with (self.root / 'RELEASES.md').open('a') as stream:
            stream.write('<!-- sources:start -->\n')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'one Sources'):
            inventory(self.root, 'HEAD')

    def test_reversed_markers_fail(self):
        (self.root / 'RELEASES.md').write_text('<!-- sources:end -->\n<!-- sources:start -->')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'reversed'):
            inventory(self.root, 'HEAD')

    def test_missing_revision_fails(self):
        with self.assertRaises(ValueError):
            inventory(self.root, 'does-not-exist')

    def test_option_is_not_executed_as_revision(self):
        with self.assertRaises(ValueError):
            inventory(self.root, '--help')

    def test_missing_lock_fails(self):
        # A rename models a committed missing artifact without altering the fixture's history.
        path = self.root / LOCKS[2]
        path.rename(path.with_suffix('.missing'))
        self.commit()
        with self.assertRaises(ValueError):
            inventory(self.root, 'HEAD')


if __name__ == '__main__':
    unittest.main()
