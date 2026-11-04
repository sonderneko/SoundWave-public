#!/usr/bin/env python3
"""Portable source-level regression checks; not Android/JVM behavior tests."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/java/xyz/cncnlp/soundwave"
CONTROLLER = (BASE / "PlaybackController.kt").read_text()
RESOLVE = CONTROLLER.split("    suspend fun resolveAndPlay(", 1)[1].split(
    "    /** 播放切歌调用点顶层异常", 1
)[0]
MAIN = (BASE / "MainActivity.kt").read_text()
COLLECTOR = MAIN.split("SchemeActionHolder.actions.collect { action ->", 1)[1].split(
    "    // 断点续播", 1
)[0]


class ResolveOwnershipTest(unittest.TestCase):
    def test_resolve_has_its_own_structured_child_job(self):
        self.assertRegex(RESOLVE, r"\)\s*:\s*Unit\s*=\s*coroutineScope\s+resolve@\s*\{")
        self.assertIn("val thisResolveJob = currentCoroutineContext()[Job]", RESOLVE)
        self.assertIn("activeResolveJob = thisResolveJob", RESOLVE)
        # A bare return is illegal after moving into the coroutineScope lambda.
        self.assertIsNone(re.search(r"\breturn\b(?!@)", RESOLVE))

    def test_finally_only_clears_its_own_job(self):
        self.assertRegex(RESOLVE, r"finally\s*\{\s*if \(activeResolveJob === thisResolveJob\) activeResolveJob = null\s*\}")

    def test_scheme_collector_dispatches_play_without_waiting(self):
        self.assertRegex(COLLECTOR, r"scope\.launch\s*\{\s*resolveAndPlay\(song, list = emptyList\(\), index = -1\)\s*\}")


class ResolveCleanupTest(unittest.TestCase):
    def test_hold_is_immediately_followed_by_resource_try(self):
        self.assertRegex(RESOLVE, r"val naturalEndHoldToken = [^\n]+\n\s*try \{")
        self.assertLess(RESOLVE.index("var pendingCacheLease:"), RESOLVE.index("val naturalEndHoldToken ="))

    def test_local_lookup_and_init_share_the_resource_finally(self):
        hold = RESOLVE.index("val naturalEndHoldToken =")
        resource_try = RESOLVE.index("try {", hold)
        local = RESOLVE.index("LocalFirst.findLocal")
        init = RESOLVE.index("scriptRuntime.init()")
        self.assertLess(resource_try, local)
        self.assertLess(local, init)
        self.assertEqual(1, RESOLVE[resource_try:local].count("try {"))
        self.assertEqual(1, RESOLVE[resource_try:init].count("try {"))
        self.assertIn("pendingCacheLease?.close()", RESOLVE)
        self.assertIn("PlayerService.releaseNaturalEndHold(naturalEndHoldToken)", RESOLVE)
        self.assertRegex(RESOLVE, r"(?s)finally\s*\{\s*pendingCacheLease\?\.close\(\)\s*PlayerService\.releaseNaturalEndHold\(naturalEndHoldToken\)\s*finishIfCurrent\(\)\s*\}")
        self.assertIn('if (song.source == "local") {', RESOLVE)
        self.assertIn('if (localHit != null && requestIsCurrent()) {', RESOLVE)
        # Cancellation must propagate, but both resource and identity finalizers still run.
        self.assertIn("if (e is CancellationException) throw e", RESOLVE)


if __name__ == "__main__":
    unittest.main(verbosity=2)
