#!/usr/bin/env python3
"""Focused source-contract guards; no Android build or device is required."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/xyz/cncnlp/soundwave"
VINYL = (SOURCE / "ui/common/VinylRefreshIndicator.kt").read_text(encoding="utf-8")
HOME = (SOURCE / "HomePage.kt").read_text(encoding="utf-8")
VIEWS = (SOURCE / "ui/home/HomeFeedStateViews.kt").read_text(encoding="utf-8")


def has_visible_text(source):
    return bool(re.search(r"\b(?:Text|BasicText|TextView|AndroidView)\s*\(", source))


class HomeRefreshFeedbackGuardTest(unittest.TestCase):
    def test_indicator_has_no_visible_text_renderer(self):
        self.assertFalse(has_visible_text(VINYL))
        self.assertNotIn("import androidx.compose.material3.Text", VINYL)

    def test_guard_rejects_reintroduced_caption(self):
        for renderer in ("Text", "BasicText", "TextView", "AndroidView"):
            with self.subTest(renderer=renderer):
                self.assertTrue(has_visible_text(VINYL + f'\n{renderer}("刷新完成")'))

    def test_refresh_status_remains_accessibility_only(self):
        self.assertIn('1 -> "正在刷新…"; 2 -> "已是最新内容"; else -> "下拉刷新"', VINYL)
        self.assertIn(".semantics { contentDescription = accessibilityLabel }", VINYL)
        # One definition and one semantics use; never feed this status into visible UI.
        self.assertEqual(len(re.findall(r"\baccessibilityLabel\b", VINYL)), 2)

    def test_non_text_progress_and_layout_remain(self):
        for contract in (
            "Canvas(", "drawArc(", "rotate(spin, c)", "drawCircle(accent,",
            "val sweep = if (phase == 0) 360f * pull.coerceIn(0f, 1f) else 90f",
            "if (phase == 1 && loops)", "rememberInfiniteTransition",
            "height(VINYL_CANVAS_HEIGHT_DP.dp).alpha(fade.value)",
            "internal const val VINYL_SIZE_DP = 34f",
            "internal const val VINYL_REST_DP = 52f",
            "internal const val VINYL_CANVAS_HEIGHT_DP = 92f",
        ):
            with self.subTest(contract=contract):
                self.assertIn(contract, VINYL)

    def test_refresh_lifecycle_and_reduced_motion_remain(self):
        for contract in (
            "LaunchedEffect(refreshing)", "seen = true; phase = 1; fade.snapTo(1f)",
            "if (reduceMotion) { drop.snapTo(VINYL_REST_DP); return@LaunchedEffect }",
            "} else if (seen) {", "seen = false; phase = 2",
            "if (reduceMotion) fade.animateTo(0f, tween(200))",
            "kotlinx.coroutines.delay(350)", "drop.animateTo(0f, tween(420,",
            "phase = 0; fade.snapTo(1f); drop.snapTo(0f)",
            "if (phase == 0 && pull <= 0.01f) return",
            "MotionPolicy.allowDecorativeLoops(reduceMotion)",
        ):
            with self.subTest(contract=contract):
                self.assertIn(contract, VINYL)

    def test_all_four_home_refresh_routes_keep_indicator_state(self):
        self.assertIn("xyz.cncnlp.soundwave.ui.common.VinylRefreshIndicator(", VIEWS)
        self.assertIn("pull = state.distanceFraction, refreshing = isRefreshing", VIEWS)
        self.assertEqual(HOME.count("PullToRefreshBox("), 4)
        self.assertEqual(HOME.count("HomePullRefreshIndicator("), 4)
        for state, refreshing in (
            ("hotRefreshState", "hotRefreshing"),
            ("hotRankPullState", "hotRankRefreshing"),
            ("rankPullState", "rankRefreshing"),
            ("playlistPullState", "playlistRefreshing"),
        ):
            with self.subTest(state=state):
                self.assertIn(f"HomePullRefreshIndicator({state}, {refreshing},", HOME)
                self.assertIn(f"isRefreshing = {refreshing}", HOME)
                self.assertIn(f"state = {state}", HOME)
        self.assertIn("onRefresh = onRefreshHome", HOME)
        self.assertIn("onRefresh = { hotChartRetryTick++; onRefreshHome() }", HOME)

    def test_error_retry_and_empty_refresh_actions_remain_visible(self):
        for contract in ('error.isNotEmpty()', 'EmptyHint(error)', '"重试"', '"刷新"',
                         'Modifier.clickable(onClick = onRetry)'):
            with self.subTest(contract=contract):
                self.assertIn(contract, VIEWS)


if __name__ == "__main__":
    unittest.main(verbosity=2)
