package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * I 波阶段 1（原 ServerFallbackPolicyTest：服务端末位兜底的准入谓词与开关
 * 存储）→ ACCEPT5 删除兜底棒（用户裁定 2026-11-04「兜底可以删就删」）：
 *
 * - /music/url 服务端兜底取链整条删除：原 `canAttemptServerUrlFallback` 的
 *   三组谓词测试与 ServerFallbackSetting 开关存储测试、兜底 stage 标签测试
 *   随功能一并退役，改为删除合同——该类必须不存在（防功能复活）。
 * - 跨源服务端搜索腿（/music/search）曾作为跨源逃逸保留、门控与被删开关
 *   解耦；ACCEPT6 跨源腿退役棒（2026-11-04）在覆盖验证通过后整条删除
 *   （五平台直连搜索 = 服务端搜索函数的逐行移植，匹配字段口径直连 ≥
 *   服务端，见 WAVE-ACCEPT6-CROSSLEGS §1）：两谓词的准入行为测试随本体
 *   退役，改为删除合同（反射断言两谓词不存在，防功能复活）。注：
 *   `MusicSource.searchServer` 本身保留——心动/相似与搜索页另有消费方。
 * - oiapi 保持不动：仍为 wy 全链末位本机段（删除后即链尾）；其准入、标签、
 *   跨平台顺序、阶段计划、未匹配原因五组测试与基线逐字一致，未作任何改动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerLegRetirementPolicyTest {

    @Test
    fun `server fallback setting class is deleted`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("xyz.cncnlp.soundwave.ServerFallbackSetting")
        }
    }

    // ACCEPT6 跨源腿退役棒删除合同：两准入谓词（serverSearchFallbackAdmitted
    // 与 …AfterMismatch）必须不存在。反射口径经删前树负对照验证（谓词在场
    // 时本测试失败、删后通过），非恒真断言。
    @Test
    fun `server search fallback predicates are deleted`() {
        val facade = Class.forName("xyz.cncnlp.soundwave.ResolutionModelsKt")
        val names = facade.declaredMethods.map { it.name }
        assertFalse(
            "server search fallback predicates must stay deleted",
            names.any { it.startsWith("serverSearchFallbackAdmitted") }
        )
    }

    @Test
    fun `oiapi stage has a distinct diagnostics label`() {
        // I 波 2b：本机 oiapi 段命中必须与 outer 直连命中可辨（原「三者可辨」
        // 中的服务端兜底段已随 /music/url 兜底整条删除）。
        assertEquals("official-oiapi", STAGE_OFFICIAL_OIAPI)
        assertEquals("oiapi 取链", playbackAttemptStageLabel(STAGE_OFFICIAL_OIAPI))
    }

    // ACCEPT4（修4）的「跨源全不匹配回落谓词放行」测试已随该腿退役：
    // serverSearchFallbackAdmittedAfterMismatch 与其准入测试一并删除，
    // 由上方 `server search fallback predicates are deleted` 删除合同覆盖。

    // ACCEPT4（修6）→ ACCEPT5（裁定一，用户 2026-11-04）：oiapi 位置从「官方
    // 阶段 pre-script」改为「脚本链之后兜底」。准入随之改口径：它是 wy 的
    // 本机兜底段，与请求档无关（无音质参数，命中以实测定档）——全档 wy 可达，
    // 仍排除其它平台与 originOnly/forceCross 模式。低档 wy 在官方阶段先有
    // outer，不冲突。本测试随用户裁定改钉（规格变更，非放松：链位置约束改由
    // UrlResolver 的调用点顺序承担，谓词仍是唯一的准入判定）。
    @Test
    fun `oiapi admitted for wangyi at all tiers after scripts`() {
        for (quality in listOf("flac", "hires", "master", "320k", "128k")) {
            assertTrue(
                "wy/$quality must reach oiapi fallback",
                canAttemptOiapiResolution("wy", quality, originOnly = false, forceCross = false)
            )
        }
        assertFalse(canAttemptOiapiResolution("tx", "flac", originOnly = false, forceCross = false))
        assertFalse(canAttemptOiapiResolution("kg", "flac", originOnly = false, forceCross = false))
        assertFalse(canAttemptOiapiResolution("wy", "flac", originOnly = true, forceCross = false))
        assertFalse(canAttemptOiapiResolution("wy", "flac", originOnly = false, forceCross = true))
    }

    // ACCEPT5（裁定二）：跨源候选平台迭代序必须逐字等于用户排定的平台顺序，
    // 仅剔除原平台自身；存储序里没有写死序的位置。
    @Test
    fun `cross platform targets follow user configured order exactly`() {
        val userOrder = listOf("wy", "kg", "kw", "mg", "tx")
        assertEquals(listOf("wy", "kg", "kw", "mg"), crossPlatTargetsInUserOrder(userOrder, "tx"))
        assertEquals(listOf("kg", "kw", "mg", "tx"), crossPlatTargetsInUserOrder(userOrder, "wy"))
        // 用户原例：QQ 原曲按其设置 网易云→酷狗→酷我 逐个试（咪咕未在其启用序内则不出现）。
        assertEquals(
            listOf("wy", "kg", "kw"),
            crossPlatTargetsInUserOrder(listOf("wy", "kg", "kw"), "tx")
        )
        // 去重保序 + 上限 4。
        assertEquals(
            listOf("mg", "wy", "kg", "kw"),
            crossPlatTargetsInUserOrder(listOf("mg", "mg", "wy", "kg", "kw", "tx"), "tx")
        )
    }

    // ACCEPT5（策略 conformance）：阶段计划只由模式与用户设置决定——默认序即
    // 用户肯定的 降音质（ORIGIN→QUALITY）→ 跨脚本 → 跨平台；任何记忆机制
    // （裁决/超时/探针）都不参与构造，故某脚本首档超时后，同平台降级与跨
    // 脚本的相对顺序不可能被记忆改写（计划在解析开始时一次定死）。
    @Test
    fun `stage plan follows affirmed order and modes`() {
        assertEquals(
            listOf("origin", "quality", "cross_script", "cross_plat"),
            SmartSourceStore.DEFAULT_STAGES
        )
        val stored = listOf("origin", "quality", "cross_script", "cross_plat")
        // 智能换源开：存储序原样即计划（用户阶段序也是设置）。
        assertEquals(stored, resolveStagePlan(originOnly = false, forceCross = false, smartOn = true, storedOrder = stored))
        // originOnly：跨平台腿不在计划内（原平台可播性检测语义）。
        assertEquals(
            listOf("origin", "quality", "cross_script"),
            resolveStagePlan(originOnly = true, forceCross = false, smartOn = true, storedOrder = stored)
        )
        // forceCross：原平台已确认失效——先跨脚本、后跨平台。
        assertEquals(
            listOf("cross_script", "cross_plat"),
            resolveStagePlan(originOnly = false, forceCross = true, smartOn = true, storedOrder = stored)
        )
        // 智能换源关：只剩原平台降级两段。
        assertEquals(
            listOf("origin", "quality"),
            resolveStagePlan(originOnly = false, forceCross = false, smartOn = false, storedOrder = stored)
        )
        // 计划函数无记忆入参：同一输入恒同一计划（顺序不被运行期状态改写）。
        assertEquals(
            resolveStagePlan(false, false, true, stored),
            resolveStagePlan(false, false, true, stored)
        )
    }

    // ACCEPT5（E3）：跨源未匹配的字段级原因人话化（含汇总行形态）。
    // 令牌以 SongMatcher.rejectReason 的真实返回为准（title/artist/version/
    // duration/duration_missing），不得自创 *_mismatch 形态。
    @Test
    fun `match reject reasons are humanized for diagnostics`() {
        assertEquals("缺时长", matchRejectReasonLabel("duration_missing"))
        assertEquals("时长不符", matchRejectReasonLabel("duration"))
        assertEquals("版本不符", matchRejectReasonLabel("version"))
        assertEquals("标题不符", matchRejectReasonLabel("title"))
        assertEquals("歌手不符", matchRejectReasonLabel("artist"))
        assertEquals("另有 9 个候选被拒，主因：缺时长", matchRejectReasonLabel("reject_summary:duration_missing:9"))
        assertEquals("", matchRejectReasonLabel(""))
    }
}
