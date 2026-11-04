"""Exercise the real guard in an isolated file-symlink fixture; never edit production."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
API = Path("app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt")
GUARD = "scripts/home-official-recommendation-registry-guard.sh"


class HomeImportProjectionTest(unittest.TestCase):
    def run_guard(self, old=None, new=None):
        with tempfile.TemporaryDirectory(prefix="r657-home-guard-") as directory:
            fixture = Path(directory)
            for entry in ROOT.iterdir():
                if entry.name in ("app", "scripts"):
                    shutil.copytree(entry, fixture / entry.name, symlinks=True,
                                    copy_function=os.symlink,
                                    ignore=shutil.ignore_patterns("build", ".gradle", "__pycache__"))
                else:
                    (fixture / entry.name).symlink_to(entry, target_is_directory=entry.is_dir())
            text = (ROOT / API).read_text()
            if old is not None:
                self.assertEqual(text.count(old), 1, "mutation must hit exactly once")
                text = text.replace(old, new, 1)
            (fixture / API).unlink()
            (fixture / API).write_text(text)
            env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
            return subprocess.run(["bash", str(fixture / GUARD)], cwd=fixture,
                                  env=env, text=True, capture_output=True)

    def test_reviewed_import_runs_all_historical_assertions(self):
        result = self.run_guard()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Home official-recommendation typed-outcome contract guard passed", result.stdout)

    def test_typed_source_gate_mutation_fails(self):
        result = self.run_guard("if (detail.source != reference.source) return null",
                                "if (detail.source == reference.source) return null")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("reviewed r657 typed import", result.stdout + result.stderr)

    def test_recommendation_call_mutation_fails(self):
        result = self.run_guard(
            "fetchRecommendPlaylistsDirect(source, limit)?.let { return FeedListOutcome.Success(it) }",
            "fetchRecommendPlaylistsDirect(source, 1)?.let { return FeedListOutcome.Success(it) }")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("recommendation", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
