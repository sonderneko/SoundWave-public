package xyz.cncnlp.soundwave.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual LazyListState positions, not a count of calls to an animation function. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h850dp-mdpi")
class ReturnToTopTrajectoryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun sixHundredRowsReturnWithoutHiddenIndexTeleport() {
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        rule.setContent {
            state = rememberLazyListState()
            scope = rememberCoroutineScope()
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(600) { Text("Row $it", Modifier.height(56.dp)) }
            }
        }
        rule.runOnIdle { scope.launch { state.scrollToItem(599) } }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        val trajectory = mutableListOf<Pair<Int, Int>>()
        fun record() { trajectory.add(state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset) }
        rule.runOnIdle { record() }
        lateinit var job: Job
        rule.runOnIdle { job = scope.launch { state.smoothScrollToTop() } }
        repeat(300) {
            if (!job.isCompleted) {
                rule.mainClock.advanceTimeByFrame()
                rule.runOnIdle { record() }
            }
        }
        val output = System.getenv("SW_TRAJECTORY_OUT")
        if (output != null) File(output).apply { parentFile.mkdirs(); writeText("frame,index,offset\n" + trajectory.mapIndexed { i,p -> "$i,${p.first},${p.second}" }.joinToString("\n") + "\n") }
        assertTrue("scroll must complete", job.isCompleted)
        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
        assertTrue("must observe actual intermediate positions", trajectory.size > 20)
        trajectory.zipWithNext().forEach { (a,b) ->
            val consumed = (a.first - b.first) * 56 + a.second - b.second
            assertTrue("no forward reversal: $a -> $b", consumed >= 0)
            assertTrue("frame delta bounded, no teleport: $a -> $b", consumed <= 2 * 850 + 56)
        }
    }
}
