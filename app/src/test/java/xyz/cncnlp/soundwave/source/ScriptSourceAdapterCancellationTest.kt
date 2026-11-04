package xyz.cncnlp.soundwave.source

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.Script
import xyz.cncnlp.soundwave.ScriptRuntime
import xyz.cncnlp.soundwave.Song

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScriptSourceAdapterCancellationTest {
    @Test
    fun resolveRethrowsCallerCancellationInsteadOfReturningNoPlayableUrl() = runTest {
        val runtime = ScriptRuntime(RuntimeEnvironment.getApplication() as Context)
        val expected = CancellationException("fixture cancellation")
        val adapter = ScriptSourceAdapter(
            runtime = runtime,
            script = Script(id = "fixture", name = "Fixture", code = "local-only"),
            resolveUrl = { _, _, _ -> throw expected },
        )

        try {
            adapter.resolveCapability.resolve(
                Song(id = "song", name = "Title", singer = "Artist", source = "wy"),
                quality = "320k",
                ticket = null,
            )
            fail("resolve must propagate cancellation")
        } catch (cancelled: CancellationException) {
            assertSame(expected, cancelled)
        } finally {
            runtime.destroy()
        }
    }
}
