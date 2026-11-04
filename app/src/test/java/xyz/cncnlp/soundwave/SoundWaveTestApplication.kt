package xyz.cncnlp.soundwave

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import org.robolectric.TestLifecycleApplication
import java.lang.reflect.Method

/**
 * Test-only application: identical to [SoundWaveApplication], plus a Robolectric
 * lifecycle hook that drains the paused main looper after every test method.
 *
 * Why: GlobalSnapshotManager (compose-ui) flushes global snapshot writes through a
 * coroutine on AndroidUiDispatcher.Main. That dispatcher's trampoline callback is
 * posted to the main looper; Robolectric discards queued main-looper messages when
 * a test method ends, which leaves the dispatcher believing a dispatch is still
 * scheduled forever. From then on, no global snapshot write is ever flushed in
 * that sandbox, and every later Compose test fails waitForIdle with
 * AppNotIdleException (a single InputMode state stuck in the global snapshot).
 * Draining the looper in afterTest lets the pending dispatch run before the
 * queue is discarded, mirroring what a real device's always-running main looper
 * would do.
 */
class SoundWaveTestApplication : SoundWaveApplication(), TestLifecycleApplication {
    override fun beforeTest(method: Method?) = drainMainLooper()
    override fun prepareTest(test: Any?) = Unit
    override fun afterTest(method: Method?) = drainMainLooper()

    private fun drainMainLooper() {
        try {
            shadowOf(Looper.getMainLooper()).idle()
        } catch (t: Throwable) {
            // Never let harness plumbing fail a test.
        }
    }
}
