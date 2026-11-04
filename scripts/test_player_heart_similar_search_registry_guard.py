#!/usr/bin/env python3
"""Exercise the real source guard in isolated fixtures; never mutate the worktree."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = Path("app/src/main/java/xyz/cncnlp/soundwave")
CONTROLLER = BASE / "PlaybackController.kt"
CONTEXT = BASE / "HeartPlaylistContext.kt"
POLICY = BASE / "HeartRecommendationPolicy.kt"
ROUTING = BASE / "source/PlaybackSearchRouting.kt"
REGISTRY = BASE / "source/MusicSourceRegistry.kt"
GUARD = Path("scripts/player-heart-similar-search-registry-guard.sh")
FILES = [GUARD, CONTROLLER, CONTEXT, POLICY, ROUTING, REGISTRY,
         Path("app/src/test/java/xyz/cncnlp/soundwave/source/PlaybackSearchRoutingTest.kt"),
         Path("docs/source-capability-matrix.md"), Path("VERSION"), Path("app/build.gradle")]


class HeartSearchRegistryGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="heart-search-guard-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for relative in FILES:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)

    def run_guard(self):
        return subprocess.run(["bash", str(self.root / GUARD)], text=True,
                              capture_output=True, timeout=20)

    def reject(self, relative, before, after, reason):
        target = self.root / relative
        source = target.read_text()
        self.assertEqual(source.count(before), 1, "mutation anchor must be unique")
        target.write_text(source.replace(before, after, 1))
        result = self.run_guard()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("FAIL: " + reason, result.stdout + result.stderr)

    def test_current_playlist_context_consumer_passes(self):
        result = self.run_guard()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("contract guard passed", result.stdout)

    def test_direct_platform_search_in_canonical_helper_is_rejected(self):
        self.reject(CONTROLLER, ".searchServer(keyword)", ".search(keyword)",
                    "canonical sources use server-only search and other sources keep the exact ApiClient fallback")

    def test_noncanonical_fallback_is_preserved(self):
        self.reject(CONTROLLER, "ApiClient.search(keyword, source)", 'ApiClient.search(keyword, "wy")',
                    "canonical sources use server-only search and other sources keep the exact ApiClient fallback")

    def test_queue_cannot_replace_playlist_or_legacy_like_profile(self):
        self.reject(CONTROLLER,
                    "val profile = playlistContext?.profile ?: HeartRecommendationPolicy.profile(getUserBundle().favs.toList())",
                    "val profile = HeartRecommendationPolicy.profile(PlayerService.snapshot().queue)",
                    "playlist profile or legacy likes teach; queue and current item only exclude")

    def test_playlist_sources_reject_noncanonical_fake_provider(self):
        self.reject(CONTEXT, 'listOf("wy", "kg", "kw", "tx", "mg")',
                    'listOf("wy", "kg", "kw", "tx", "mg", "script:test")',
                    "playlist search sources are exact-canonical, whitelisted, deduplicated and capped at two")

    def test_legacy_global_fallback_stays_two_sources(self):
        self.reject(CONTROLLER, 'listOf(preferredSource) + listOf("wy", "kg")',
                    'listOf(preferredSource) + listOf("wy", "kg", "tx")',
                    "playlist sources or the two-source legacy fallback run at most two artist queries concurrently with a 6s per-request timeout, then filter candidates")

    def test_artist_fanout_and_request_timeout_are_bounded(self):
        self.reject(CONTROLLER, "queryArtists.flatMap { singer -> plats.map { plat ->",
                    "queryArtists.take(8).flatMap { singer -> plats.map { plat ->",
                    "playlist sources or the two-source legacy fallback run at most two artist queries concurrently with a 6s per-request timeout, then filter candidates")

    def test_unweighted_shuffle_sampling_is_rejected(self):
        self.reject(CONTROLLER, "val queryArtists = TasteProfileV2.heartQueryPlan(profile.queries, taste, item, Random)",
                    "val queryArtists = profile.queries.shuffled().take(2)",
                    "heart artist sampling is affinity-weighted via TasteProfileV2, never unweighted shuffle")

    def test_taste_ranking_of_candidates_is_required(self):
        self.reject(CONTROLLER, "val rankedSimilar = if (taste != null) similar.sortedByDescending { taste.songScore(it) } else similar",
                    "val rankedSimilar = similar",
                    "heart continuation ranks eligible candidates by unified taste score and plays the highest")

    def test_per_request_timeout_is_preserved(self):
        self.reject(CONTROLLER, "withTimeoutOrNull(6000L)", "withTimeoutOrNull(8000L)",
                    "playlist sources or the two-source legacy fallback run at most two artist queries concurrently with a 6s per-request timeout, then filter candidates")

    def test_cancellation_cannot_be_swallowed(self):
        self.reject(CONTROLLER, "catch (e: CancellationException) { throw e }",
                    "catch (e: CancellationException) { emptyList() }",
                    "cancellation propagates and only identity-valid, nonexcluded same-artist candidates survive")

    def test_stale_session_revision_guard_is_preserved(self):
        self.reject(CONTROLLER, "fun searchIsCurrent(): Boolean =\n                heartSessionRevision == sessionRevision",
                    "fun searchIsCurrent(): Boolean =\n                heartSessionRevision != sessionRevision",
                    "session revision, transaction gate and playback-intent guards reject stale search and playback results")

    def test_candidates_must_have_valid_identity_and_not_be_excluded(self):
        self.reject(POLICY, "id != null && song.key() !in excludedKeys && id !in excludedIdentities",
                    "id != null && true",
                    "cancellation propagates and only identity-valid, nonexcluded same-artist candidates survive")

    def test_title_queries_cannot_replace_liked_artist_queries(self):
        self.reject(POLICY, "seeds.flatMap { artistParts(it.singer) }", "seeds.map { it.name }",
                    "heart search queries derive from artist names, never song titles")

    def test_exact_canonical_route_normalization_is_rejected(self):
        self.reject(ROUTING, "source.takeIf { it in MusicSourceRegistry.platformIds }",
                    "source.trim().lowercase().takeIf { it in MusicSourceRegistry.platformIds }",
                    "route selector accepts only exact canonical platform IDs")

    def test_canonical_platform_removal_is_rejected(self):
        self.reject(REGISTRY, 'listOf("wy", "kg", "kw", "tx", "mg")',
                    'listOf("wy", "kg", "kw", "tx")',
                    "registry retains all five canonical platform IDs")

    def test_version_contract_is_not_weakened(self):
        original = (self.root / "VERSION").read_text()
        self.reject(Path("VERSION"), original, "1.0.999999\n",
                    "VERSION and Gradle version declarations remain synchronized")

    def test_documented_capability_boundary_is_required(self):
        self.reject(Path("docs/source-capability-matrix.md"), "本能力不是推荐接口", "本能力是推荐接口",
                    "source matrix documents playlist-context search capability and its non-recommendation limits")


if __name__ == "__main__":
    unittest.main(verbosity=2)
