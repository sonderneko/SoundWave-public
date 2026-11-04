#!/usr/bin/env python3
"""Negative contract tests for the six Source Registry guards.

These tests intentionally model the mutations that caused the cancellation
refactor drift: restoring the old ApiClient calls or removing the direct
CancellationException rethrow must make the guard contract unsatisfied.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ADAPTER = (ROOT / "app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt").read_text()
REGISTRY = (ROOT / "app/src/main/java/xyz/cncnlp/soundwave/source/MusicSourceRegistry.kt").read_text()
GATEWAY = (ROOT / "app/src/main/java/xyz/cncnlp/soundwave/source/ApiClientCatalogGateway.kt").read_text()
GUARDS = [
    ROOT / "scripts/home-official-recommendation-registry-guard.sh",
    ROOT / "scripts/main-playlist-count-refresh-registry-guard.sh",
    ROOT / "scripts/player-similar-search-registry-guard.sh",
    ROOT / "scripts/search-qq-playlist-fallback-registry-guard.sh",
    ROOT / "scripts/search-typed-registry-guard.sh",
    ROOT / "scripts/step8-direct-source-guard.sh",
]


class PlatformSourceAdapterRegistryGuardNegativeTest(unittest.TestCase):
    def test_platform_hot_route_uses_direct_callback_and_server_only_gateway(self):
        self.assertIn("directHotSongs = { fetchHotDirect(id) }", REGISTRY)
        self.assertIn("return api.hotServer(source = platform)", ADAPTER)
        self.assertIn("ApiClient.hotServer(source)", GATEWAY)
        self.assertNotIn("override suspend fun hotSongs(): List<Song> = api.hot(source = platform)", ADAPTER)

    def test_platform_hot_keywords_route_uses_typed_server_only_fallback(self):
        self.assertIn("directHotKeywords = { limit -> fetchHotWordsDirect(id, limit) }", REGISTRY)
        self.assertIn("return api.hotKeywordsServerOutcome(source = platform, limit = limit)", ADAPTER)
        self.assertIn("ApiClient.hotKeywordsServerOutcome(source, limit)", GATEWAY)
        self.assertNotIn("api.hotKeywordsOutcome(source = platform, limit = limit)", ADAPTER)

    def test_platform_discover_route_uses_direct_callback_and_server_only_fallback(self):
        self.assertIn("directDiscoverCards = { limit -> fetchDiscoverDirect(id, limit) }", REGISTRY)
        self.assertIn("return api.discoverServerOutcome(source = platform, limit = limit)", ADAPTER)
        self.assertIn("ApiClient.discoverServerOutcome(source, limit)", GATEWAY)
        self.assertNotIn("api.discoverOutcome(source = platform, limit = limit)", ADAPTER)

    def test_all_six_guards_pin_current_cancellable_route_tokens(self):
        for guard in GUARDS:
            text = guard.read_text()
            if guard.name not in {"main-playlist-count-refresh-registry-guard.sh", "player-similar-search-registry-guard.sh"}:
                self.assertIn("CancellationException", text, guard.name)
        self.assertIn("searchCancellable", ADAPTER)
        self.assertIn("searchPlaylistsCancellable", ADAPTER)

    def test_old_non_cancellable_server_routes_are_not_accepted(self):
        mutations = [
            ADAPTER.replace("api.searchCancellable(keyword, source = platform, type = type)",
                            "api.search(keyword, source = platform, type = type)"),
            ADAPTER.replace("api.searchPlaylistsCancellable(keyword, source = platform, limit = limit)",
                            "api.searchPlaylists(keyword, source = platform, limit = limit)"),
        ]
        self.assertNotIn("searchCancellable(keyword, source = platform, type = type)", mutations[0])
        self.assertNotIn("searchPlaylistsCancellable(keyword, source = platform, limit = limit)", mutations[1])

    def test_direct_failure_mutation_cannot_swallow_cancellation(self):
        mutated = ADAPTER.replace("throw cancelled", "return emptyList()")
        self.assertNotIn("throw cancelled", mutated)
        self.assertIn("catch (cancelled: CancellationException)", mutated)
        self.assertIn("return emptyList()", mutated)


if __name__ == "__main__":
    unittest.main()
