#!/usr/bin/env python3
"""Real four-guard positive/negative integration in isolated source fixtures.

Mutations never touch the shared worktree. These are guard sensitivity tests,
not Android/WebView or live platform behavior tests.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = Path('app/src/main/java/xyz/cncnlp/soundwave')
TEST = Path('app/src/test/java/xyz/cncnlp/soundwave')
PLAY = BASE / 'PlaybackController.kt'
RUNTIME = BASE / 'ScriptRuntime.kt'
RESOLVER = BASE / 'UrlResolver.kt'
BUDGET = BASE / 'ResolveBudget.kt'
GUARDS = [Path('scripts') / (name + '-guard.sh') for name in (
    'playback-transaction', 'script-runtime-webview',
    'urlresolver-cross-platform-search-registry',
    'urlresolver-probe-sample-search-registry')]
UI_GUARDS = [Path('scripts') / (name + '-guard.sh') for name in (
    'player-transition', 'settings-page-cleanup')]
FILES = GUARDS + [BASE / name for name in (
    'PlaybackController.kt', 'PlaybackTransactionGate.kt',
    'playback/PlaybackSnapshotPolicy.kt', 'ScriptRuntime.kt',
    'ScriptRequestScheduler.kt', 'ScriptNetworkPolicy.kt',
    'source/ScriptHttpCredentialGuardSetting.kt', 'MePage.kt',
    'UrlResolver.kt', 'SmartSourceStore.kt', 'source/MusicSourceRegistry.kt',
    'source/PlatformSourceAdapter.kt', 'source/MusicSource.kt', 'ApiClient.kt',
    'ScriptProbeCache.kt', 'ScriptProbeMatrix.kt', 'ResolveBudget.kt')]
FILES += [TEST / name for name in (
    'PlaybackSnapshotPolicyTest.kt', 'ScriptRuntimeWebViewIsolationTest.kt',
    'ScriptRequestSchedulerTest.kt', 'ScriptNetworkPolicyTest.kt',
    'ScriptHttpCredentialGuardSettingTest.kt')]
FILES += [Path(name) for name in ('docs/source-capability-matrix.md',
    'app/build.gradle', 'VERSION', '.github/workflows/build-apk.yml')]

UI_FILES = [UI_GUARDS[0], UI_GUARDS[1]] + [BASE / name for name in (
    'FullPlayer.kt', 'MainActivity.kt', 'PlayerLayout.kt', 'LibraryPage.kt',
    'PlaybackController.kt', 'MePage.kt', 'SettingsHub.kt', 'AppRoute.kt',
    'PageChrome.kt', 'SettingsSupportPages.kt', 'ui/common/AppDialogs.kt',
    'ui/player/PlayerTransitionBackdrop.kt', 'ui/player/PlayerImmersiveCoverHero.kt',
    'ui/player/PlayerOverlayVisibility.kt', 'ui/player/PlayerDismissGestureModifier.kt',
    'ui/motion/SharedCoverOverlay.kt', 'ui/settings/SettingsRowInteraction.kt',
)]
UI_FILES += [Path(name) for name in ('app/build.gradle', 'VERSION', '.github/workflows/build-apk.yml')]


class ResolveGuardIntegrationTest(unittest.TestCase):
    def exercise(self, guard, mutation=None):
        with tempfile.TemporaryDirectory(prefix='resolve-guard-mutation-') as temp:
            root = Path(temp)
            for relative in FILES:
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / relative, destination)
            if mutation:
                relative, before, after = mutation
                target = root / relative
                source = target.read_text()
                self.assertIn(before, source, 'mutation anchor missing')
                # One mutation site per test; repeated anchors select the first
                # actual consumer, with surrounding context where necessary.
                target.write_text(source.replace(before, after, 1))
            return subprocess.run(['bash', str(root / GUARDS[guard])],
                                  text=True, capture_output=True, timeout=30)

    def assert_rejected(self, guard, relative, before, after):
        result = self.exercise(guard, (relative, before, after))
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('FAIL:', result.stdout + result.stderr,
                      'must fail an explicit contract, not fixture setup')

    def exercise_ui(self, guard, mutation=None):
        with tempfile.TemporaryDirectory(prefix='ui-guard-mutation-') as temp:
            root = Path(temp)
            for relative in UI_FILES:
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / relative, destination)
            if mutation:
                relative, before, after = mutation
                target = root / relative
                source = target.read_text()
                self.assertIn(before, source, 'mutation anchor missing')
                target.write_text(source.replace(before, after, 1))
            return subprocess.run(['bash', str(root / UI_GUARDS[guard])],
                                  text=True, capture_output=True, timeout=30)

    def assert_ui_rejected(self, guard, relative, before, after):
        result = self.exercise_ui(guard, (relative, before, after))
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('FAIL:', result.stdout + result.stderr)

    def test_all_current_production_consumers_pass(self):
        for guard in range(len(GUARDS)):
            with self.subTest(guard=GUARDS[guard]):
                result = self.exercise(guard)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_playback_disconnected_currentness_callback(self):
        self.assert_rejected(0, PLAY, 'requestStillCurrent = ::requestIsCurrent,',
            'requestStillCurrent = { true }, // requestStillCurrent = ::requestIsCurrent,')

    def test_playback_stale_must_remain_terminal(self):
        self.assert_rejected(0, PLAY,
            'if (result.failure == ResolveFailure.STALE_REQUEST) return@resolve',
            '// if (result.failure == ResolveFailure.STALE_REQUEST) return@resolve')

    def test_playback_service_generation_cannot_be_disconnected(self):
        self.assert_rejected(0, PLAY,
            'PlayerService.currentGeneration() == serviceGeneration', 'true')

    def test_playback_decrypt_commit_cannot_lose_guard(self):
        self.assert_rejected(0, PLAY,
            'if (!requestIsCurrent()) return@resolve\n                    song.resolvedUrl = plainPath',
            '// if (!requestIsCurrent()) return@resolve\n                    song.resolvedUrl = plainPath')

    def test_playback_cancellation_cannot_drop_io_lease(self):
        self.assert_rejected(0, PLAY, '.also { pendingCacheLease = it }',
            '.also { /* pendingCacheLease = it */ }')

    def test_playback_finally_must_release_hold(self):
        self.assert_rejected(0, PLAY, 'PlayerService.releaseNaturalEndHold(naturalEndHoldToken)',
            '// PlayerService.releaseNaturalEndHold(naturalEndHoldToken)')

    def test_playback_normal_commit_must_recheck_after_suspend(self):
        self.assert_rejected(0, PLAY,
            'if (!requestIsCurrent()) return@resolve\n                val playUrl = ApiClient.playableUrl(raw)',
            '// if (!requestIsCurrent()) return@resolve\n                val playUrl = ApiClient.playableUrl(raw)')

    def test_playback_local_commit_cannot_bypass_identity(self):
        self.assert_rejected(0, PLAY,
            'if (localFile != null && requestIsCurrent()) {',
            'if (localFile != null) {')

    def test_playback_progress_must_not_update_completed_request(self):
        self.assert_rejected(0, PLAY,
            'thisResolveJob?.isActive == true && resolving && requestIsCurrent()',
            'requestIsCurrent()')

    def test_runtime_url_capability_must_cancel_real_cids(self):
        self.assert_rejected(1, RUNTIME,
            'ScriptRequestScheduler.processWide.cancel(fetchOwner, cid)',
            'ScriptRequestScheduler.processWide.cancel(fetchOwner, capability)\n                // ScriptRequestScheduler.processWide.cancel(fetchOwner, cid)')

    def test_runtime_actual_child_call_cannot_be_disconnected(self):
        self.assert_rejected(1, RUNTIME, 'activeCalls.remove(cid)?.cancel()',
            '// activeCalls.remove(cid)?.cancel()')

    def test_runtime_native_parent_must_be_registered_atomically(self):
        self.assert_rejected(1, RUNTIME,
            'if (!pendingUrl.containsKey(parent) || fetchParents.putIfAbsent(capability, parent) != null) return',
            'if (false) return')

    def test_runtime_js_must_forward_url_parent(self):
        self.assert_rejected(1, RUNTIME, 'cid, parentCap\n            );',
            'cid, null\n            );')

    def test_runtime_play_preemption_must_not_cancel_download(self):
        self.assert_rejected(1, RUNTIME,
            'cancelUrlRequests { it.purpose == ResolveTicket.Purpose.PLAY }',
            'cancelUrlRequests { true }')

    def test_runtime_local_cleanup_must_not_cancel_owner_globally(self):
        self.assert_rejected(1, RUNTIME,
            'activeCalls.remove(cid)?.cancel()',
            'activeCalls.remove(cid)?.cancel()\n                ScriptRequestScheduler.processWide.cancelOwner(fetchOwner)')

    def test_runtime_cancelled_continuation_cannot_open_network(self):
        self.assert_rejected(1, RUNTIME,
            "if(!scope || scope.closed) throw new Error('request owner closed');",
            "// if(!scope || scope.closed) throw new Error('request owner closed');")

    def test_cross_search_must_not_switch_to_direct_first(self):
        self.assert_rejected(2, RESOLVER,
            'ResolveBlockingStage.run { MusicSourceRegistry.platformSource(plat).searchServer(kw) }',
            'ResolveBlockingStage.run { MusicSourceRegistry.platformSource(plat).search(kw) }')

    def test_cross_custom_source_cannot_be_normalized(self):
        self.assert_rejected(2, RESOLVER,
            'ApiClient.searchCancellable(kw, plat)',
            'ApiClient.searchCancellable(kw, plat.trim().lowercase())')

    def test_cross_search_cancellation_must_not_be_swallowed(self):
        self.assert_rejected(2, RESOLVER,
            '} catch (e: Exception) {\n                                if (e is kotlinx.coroutines.CancellationException) throw e\n                                emptyList<Song>()',
            '} catch (e: Exception) {\n                                emptyList<Song>()')

    def test_cross_origin_only_isolation_cannot_be_disconnected(self):
        self.assert_rejected(2, RESOLVER, 'if (originOnly) continue',
            '// if (originOnly) continue')

    def test_cross_actual_candidate_consumer_must_use_target_source(self):
        self.assert_rejected(2, RESOLVER, 'val hit = tryScript(sc, plat, cand, qs, true)',
            'val hit = tryScript(sc, src, song, qs, true) // val hit = tryScript(sc, plat, cand, qs, true)')

    def test_probe_selected_song_consumer_cannot_be_disconnected(self):
        self.assert_rejected(3, RESOLVER,
            'runtime.getMusicUrl(sc.id, sc.code, buildMusicInfo(song), plat, q, ResolveTicket.Purpose.PROBE, scriptMeta = scriptMetaJson(sc))',
            'runtime.getMusicUrl(sc.id, sc.code, JSONObject(), plat, q, ResolveTicket.Purpose.PROBE, scriptMeta = scriptMetaJson(sc))')

    def test_probe_search_cannot_bypass_registry(self):
        text = (ROOT / RESOLVER).read_text()
        start = text.index('    suspend fun probeScriptScored(')
        before = text[start:text.index('                searchMs = (System.currentTimeMillis()', start)]
        after = before.replace('ResolveBlockingStage.run { MusicSourceRegistry.platformSource(plat).searchServer(kw) }',
                               'ResolveBlockingStage.run { ApiClient.search(kw, plat) }')
        self.assert_rejected(3, RESOLVER, before, after)

    def test_probe_search_cancellation_must_not_be_swallowed(self):
        self.assert_rejected(3, RESOLVER,
            'if (e is kotlinx.coroutines.CancellationException) throw e\n                if (searchStarted > 0L)',
            '// if (e is kotlinx.coroutines.CancellationException) throw e\n                if (searchStarted > 0L)')

    def test_probe_quality_source_must_remain_isolated(self):
        self.assert_rejected(3, RESOLVER,
            'validateProbeMedia(plat, q, mediaUrl, dt)', 'validateProbeMedia("wy", q, mediaUrl, dt)')

    def test_both_registry_guards_require_actual_blocking_cancellation(self):
        for guard in (2, 3):
            with self.subTest(guard=GUARDS[guard]):
                self.assert_rejected(guard, BUDGET,
                    'continuation.invokeOnCancellation { job.cancel() }',
                    '// caller cancellation disconnected')

    def test_both_registry_guards_require_canonical_registry(self):
        for guard in (2, 3):
            with self.subTest(guard=GUARDS[guard]):
                self.assert_rejected(guard, BASE / 'source/MusicSourceRegistry.kt',
                    'listOf("wy", "kg", "kw", "tx", "mg")',
                    'listOf("wy", "kg", "kw", "tx")')

    def test_player_guard_requires_source_background_behind_overlay(self):
        self.assert_ui_rejected(0, BASE / 'MainActivity.kt',
            'Box(Modifier.fillMaxSize().background(bg))',
            'Box(Modifier.fillMaxSize())')

    def test_player_guard_requires_shared_cover_overlay_sibling(self):
        self.assert_ui_rejected(0, BASE / 'MainActivity.kt',
            'xyz.cncnlp.soundwave.ui.motion.SharedCoverOverlayRoot(activeKey = plDetail?.id) {',
            '// overlay removed')

    def test_settings_guard_requires_source_background_behind_overlay(self):
        self.assert_ui_rejected(1, BASE / 'MainActivity.kt',
            'Box(Modifier.fillMaxSize().background(bg))',
            'Box(Modifier.fillMaxSize())')

    def test_settings_guard_requires_shared_cover_overlay_sibling(self):
        self.assert_ui_rejected(1, BASE / 'MainActivity.kt',
            'xyz.cncnlp.soundwave.ui.motion.SharedCoverOverlayRoot(activeKey = plDetail?.id) {',
            '// overlay removed')


if __name__ == '__main__':
    unittest.main(verbosity=2)
