package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnonymousResolutionPolicyTest {
    @Test
    fun `tx playback skips the structurally dead anonymous official stage`() {
        // F2/R1: the tx anonymous official path cannot succeed (anonymous vkey is
        // dead platform-side, no platform cookie is ever sent, and the server tx
        // lookup outlasts the call cap), so tx must not be admitted before scripts
        // at any quality the old rule used to admit.
        assertFalse(canAttemptAnonymousResolutionBeforeScripts("tx", "320k", false, false))
        assertFalse(canAttemptAnonymousResolutionBeforeScripts("tx", "128k", false, false))
    }

    @Test
    fun `tx without usable scripts rejects with the missing-script failure instead of burning the official stage`() {
        val anonymousEligible = canAttemptAnonymousResolutionBeforeScripts(
            source = "tx",
            quality = "320k",
            originOnly = false,
            forceCross = false,
        )

        assertFalse(anonymousEligible)
        // Scripts are the only tx path left, so their absence is fatal and must
        // surface the actionable missing-script failure immediately.
        assertTrue(shouldRejectForMissingScripts(usableScripts = false, anonymousResolutionEligible = anonymousEligible))
        assertFalse(shouldRejectForMissingScripts(usableScripts = true, anonymousResolutionEligible = anonymousEligible))
    }

    @Test
    fun `non-tx platforms keep anonymous resolution admitted before scripts`() {
        for (source in listOf("wy", "kg", "kw", "mg")) {
            assertTrue(
                "$source must stay anonymous-eligible",
                canAttemptAnonymousResolutionBeforeScripts(source, "320k", false, false)
            )
        }
        // With an anonymous path available, missing scripts are still not fatal there.
        assertFalse(shouldRejectForMissingScripts(usableScripts = false, anonymousResolutionEligible = true))
    }

    @Test
    fun `local origin-only cross-source and high-quality requests do not use the anonymous shortcut`() {
        assertFalse(canAttemptAnonymousResolutionBeforeScripts("local", "320k", false, false))
        assertFalse(canAttemptAnonymousResolutionBeforeScripts("tx", "320k", true, false))
        assertFalse(canAttemptAnonymousResolutionBeforeScripts("tx", "320k", false, true))
        assertFalse(canAttemptAnonymousResolutionBeforeScripts("tx", "flac", false, false))
        assertTrue(shouldRejectForMissingScripts(usableScripts = false, anonymousResolutionEligible = false))
    }

    @Test
    fun `failed anonymous attempt with no scripts retains actionable missing-script message`() {
        val attempts = listOf(
            ResolveAttempt(
                stage = "admission",
                platform = "tx",
                scriptId = "",
                quality = "320k",
                elapsedMs = 0L,
                outcome = ResolveAttempt.Outcome.SKIPPED,
                reasonCode = ResolveAttempt.Reason.NO_ENABLED_SCRIPT,
                reason = "no_enabled_script",
            ),
        )

        val failure = resolveFailureFromAttempts(attempts)
        assertEquals(ResolveFailure.NO_ENABLED_SCRIPT, failure)
        assertEquals("没有可用的洛雪脚本，请到设置→脚本管理导入或启用脚本", UrlResolver.failureMessage(failure))
    }
}
