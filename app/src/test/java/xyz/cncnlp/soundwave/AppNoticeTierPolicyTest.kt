package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.ui.notice.AppNotice

/**
 * ACCEPT5 通知分类（WAVE-ACCEPT5-NOTICE A）：Tier 默认归类、NoticePolicy 时长表、
 * 横幅整条点击落点（显式 onClick / 消息中心横幅走宿主 handler / 无落点不可点）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppNoticeTierPolicyTest {

    @Before
    fun resetNotice() {
        AppNotice.dismissCapsule()
        AppNotice.dismissBanner()
        AppNotice.bannerViewHandler = null
        AppNotice.clearInbox()
    }

    @Test
    fun policyDurationsMatchClassificationTable() {
        val policy = AppNotice.NoticePolicy
        // FEEDBACK 胶囊（2026-11-04 19:50 二轮收紧，压幅最大）：短 1.5s、长文案 2.2s。
        assertEquals(1_500L, policy.capsuleDurationMillis(AppNotice.Tier.FEEDBACK, AppNotice.Kind.SUCCESS, false, 4))
        assertEquals(2_200L, policy.capsuleDurationMillis(AppNotice.Tier.FEEDBACK, AppNotice.Kind.NEUTRAL, false, 20))
        // ALERT 胶囊：纯报错 3s（单行可读完）；带撤销动作 4s（显示=操作窗口，不缩）。
        assertEquals(3_000L, policy.capsuleDurationMillis(AppNotice.Tier.ALERT, AppNotice.Kind.FAILURE, false, 4))
        assertEquals(3_000L, policy.capsuleDurationMillis(AppNotice.Tier.FEEDBACK, AppNotice.Kind.FAILURE, false, 4))
        assertEquals(4_000L, policy.capsuleDurationMillis(AppNotice.Tier.ALERT, AppNotice.Kind.NEUTRAL, true, 4))
        // 横幅：ALERT 无动作 3s、带动作 5s（均不动）；FEEDBACK 2s。
        assertEquals(3_000L, policy.bannerDurationMillis(AppNotice.Tier.ALERT, false))
        assertEquals(5_000L, policy.bannerDurationMillis(AppNotice.Tier.ALERT, true))
        assertEquals(2_000L, policy.bannerDurationMillis(AppNotice.Tier.FEEDBACK, false))
    }

    @Test
    fun toastDefaultsClassifyByKind() {
        AppNotice.toast("策略测试成功回执", AppNotice.Kind.SUCCESS)
        var capsule = AppNotice.capsuleState.value
        assertEquals(AppNotice.Tier.FEEDBACK, capsule?.tier)
        assertEquals(1_500L, capsule?.durationMillis)

        AppNotice.toast("策略测试失败报错", AppNotice.Kind.FAILURE)
        capsule = AppNotice.capsuleState.value
        assertEquals(AppNotice.Tier.ALERT, capsule?.tier)
        assertEquals(3_000L, capsule?.durationMillis)
    }

    @Test
    fun undoIsAlwaysAlertWithFourSecondWindow() {
        AppNotice.undo("策略测试已删除歌单") {}
        val capsule = AppNotice.capsuleState.value
        assertEquals(AppNotice.Tier.ALERT, capsule?.tier)
        assertEquals(4_000L, capsule?.durationMillis)
        assertEquals("撤销", capsule?.actionLabel)
    }

    @Test
    fun bannerClickPrefersExplicitOnClick() {
        var clicked = false
        AppNotice.banner("策略测试权限横幅", onClick = { clicked = true })
        val banner = AppNotice.bannerState.value
        assertTrue(AppNotice.bannerHasClick(banner!!))
        AppNotice.triggerBannerClick(banner.id)
        assertTrue(clicked)
        assertNull(AppNotice.bannerState.value)
    }

    @Test
    fun inboxBannerClickFallsBackToHostHandler() {
        var viewed = false
        AppNotice.bannerViewHandler = { viewed = true }
        AppNotice.inbox(AppNotice.Category.PLAYBACK, "策略测试播放失败", alsoBanner = true)
        val banner = AppNotice.bannerState.value
        assertTrue(banner!!.fromInbox)
        assertTrue(AppNotice.bannerHasClick(banner))
        AppNotice.triggerBannerClick(banner.id)
        assertTrue(viewed)
        assertNull(AppNotice.bannerState.value)
    }

    @Test
    fun plainBannerWithoutDestinationIsNotClickable() {
        AppNotice.banner("策略测试无落点横幅")
        val banner = AppNotice.bannerState.value
        assertFalse(AppNotice.bannerHasClick(banner!!))
        AppNotice.triggerBannerClick(banner.id)
        // 无落点点击不吞横幅：状态保持，仍等手势/计时关闭。
        assertEquals(banner.id, AppNotice.bannerState.value?.id)
    }
}
