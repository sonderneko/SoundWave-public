package xyz.cncnlp.soundwave.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.ContextPreferences
import xyz.cncnlp.soundwave.LocalStore
import xyz.cncnlp.soundwave.PlayMode
import xyz.cncnlp.soundwave.PlaybackController
import xyz.cncnlp.soundwave.PlayerService
import xyz.cncnlp.soundwave.ScriptRuntime
import xyz.cncnlp.soundwave.Song
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Executes the production Service trigger with renderer facades, not an audible Android decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FixedCrossfadeServiceTest {
    private lateinit var service: PlayerService
    private val saved = linkedMapOf<String, Any?>()
    private val previousSnapshot = PlayerService.snapshot()
    private var calls = 0
    private val a = Song("crossfade-a", "A", "Fixture", "local")
    private val b = Song("crossfade-b", "B", "Fixture", "local")
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun field(name: String) = PlayerService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) {
        if (!saved.containsKey(name)) saved[name] = field(name).get(null)
        field(name).set(null, value)
    }
    private fun renderer(duration: Long, position: Long = 0, playing: Boolean = false): ExoPlayer =
        Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "getDuration" -> duration
                "getCurrentPosition" -> position
                "isPlaying", "getPlayWhenReady" -> playing
                "getPlaybackState" -> Player.STATE_READY
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "RendererFacade"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 1f
                    else -> null
                }
            }
        } as ExoPlayer

    @Before fun setup() {
        service = Robolectric.buildService(PlayerService::class.java).get()
        set("instance", service)
        set("player", renderer(180_000, 176_000, true))
        set("nextPlayer", renderer(120_000))
        set("currentSong", a)
        set("queueIndex", 0)
        // The real queue container is final: preserve and restore its entries separately.
        @Suppress("UNCHECKED_CAST")
        val queue = field("queue").get(null) as MutableList<Song>
        oldQueue = queue.toList()
        queue.clear(); queue.addAll(listOf(a, b))
        set("pendingNextSong", b)
        set("pendingNextReady", true)
        set("playMode", PlayMode.SEQUENCE)
        set("sleepStopAfterCurrent", false)
        set("sleepDeadlineElapsed", 0L)
        set("naturalEndHoldToken", "")
        set("activePlaybackToken", "crossfade-fixture#1")
        set("earlyCrossfadeRequestedToken", "")
        set("endedHandledToken", "")
        set("interruptedIntent", -1)
        set("playbackSpeed", 1f)
        oldCallback = PlayerService.onPreparedNext
        PlayerService.onPreparedNext = { calls++ }
        val prefs = ContextPreferences.open(context, "soundwave", 0)
        oldPreference = prefs.all[PlaybackTransitionSettingsStore.KEY_DURATION]
        PlaybackTransitionSettingsStore.write(prefs, 5)
    }
    private var oldQueue = emptyList<Song>()
    private var oldCallback: (() -> Unit)? = null
    private var oldPreference: Any? = null

    @After fun cleanup() {
        service.releaseTransitionWake()
        PlayerService.onPreparedNext = oldCallback
        @Suppress("UNCHECKED_CAST")
        val queue = field("queue").get(null) as MutableList<Song>
        queue.clear(); queue.addAll(oldQueue)
        saved.forEach { (name, value) -> field(name).set(null, value) }
        @Suppress("UNCHECKED_CAST")
        val snapshots = field("_playback").get(null) as kotlinx.coroutines.flow.MutableStateFlow<xyz.cncnlp.soundwave.PlaybackSnapshot>
        snapshots.value = previousSnapshot
        val edit = ContextPreferences.open(context, "soundwave", 0).edit()
        val previous = oldPreference
        if (previous is Int) edit.putInt(PlaybackTransitionSettingsStore.KEY_DURATION, previous)
        else edit.remove(PlaybackTransitionSettingsStore.KEY_DURATION)
        edit.commit()
    }

    private fun tick() {
        PlayerService::class.java.getDeclaredMethod("maybeStartFixedCrossfade").apply { isAccessible = true }.invoke(service)
    }

    @Test fun eligibleTriggerUsesForegroundGateOnceWithoutConsumingNaturalEnd() {
        tick(); tick()
        assertEquals(1, calls)
        assertEquals("", field("endedHandledToken").get(null))
        assertSame("the trigger must not promote around the controller gate", a, field("currentSong").get(null))
        assertSame(b, field("pendingNextSong").get(null))
    }

    @Test fun rejectedEarlyRequestStillAllowsNormalEndDispatch() {
        tick()
        PlayerService.handleTrackEnded()
        assertEquals(2, calls)
        PlayerService.handleTrackEnded()
        assertEquals("natural end itself is consumed only once", 2, calls)
    }

    @Test fun offDoesNotPrecut() {
        PlaybackTransitionSettingsStore.write(ContextPreferences.open(context, "soundwave", 0), 0)
        tick(); assertEquals(0, calls)
    }

    @Test fun sleepAfterCurrentDoesNotPrecut() {
        set("sleepStopAfterCurrent", true)
        tick(); assertEquals(0, calls)
    }

    @Test fun pendingUserSelectionDoesNotPrecut() {
        set("naturalEndHoldToken", "crossfade-fixture#1")
        tick(); assertEquals(0, calls)
    }

    @Test fun loopShuffleAndHeartNeverPrecut() {
        listOf(PlayMode.LOOP_ONE, PlayMode.SHUFFLE, PlayMode.HEART).forEach {
            set("playMode", it); tick()
        }
        assertEquals(0, calls)
    }

    @Test fun queueMismatchDoesNotPrecut() {
        set("pendingNextSong", b.copy(id = "stale"))
        tick(); assertEquals(0, calls)
    }

    @Test fun unreadyNextDoesNotPrecut() {
        set("pendingNextReady", false)
        tick(); assertEquals(0, calls)
    }

    @Test fun pausedCurrentDoesNotPrecut() {
        set("player", renderer(180_000, 176_000, false))
        tick(); assertEquals(0, calls)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun queuedRequest(changeBeforeExecution: () -> Unit, manual: Boolean = false): Int {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val runtime = ScriptRuntime(context)
        try {
            val controller = PlaybackController(context, scope, runtime, LocalStore.prefs(context),
                { emptyList() }, { LocalStore.UserBundle() }, { "128k" }, { "embedded" }, {})
            controller.currentSong = a
            var advances = 0
            controller.playDeltaRef[0] = { advances++ }
            PlayerService.onPreparedNext = { controller.enqueueQueueAdvance(1) }
            if (manual) controller.enqueueQueueAdvance(1) else tick()
            changeBeforeExecution()
            scheduler.runCurrent()
            return advances
        } finally {
            scope.cancel()
            runtime.destroy()
        }
    }

    @Test fun queuedEarlyRequestCommitsWhenStillEligible() {
        assertEquals(1, queuedRequest({}))
    }

    @Test fun switchingToLoopWhileEarlyRequestQueuedPreventsAdvance() {
        assertEquals(0, queuedRequest({ PlayerService.setMode(PlayMode.LOOP_ONE) }))
    }

    @Test fun disablingFadeWhileEarlyRequestQueuedPreventsAdvance() {
        assertEquals(0, queuedRequest({
            PlaybackTransitionSettingsStore.write(ContextPreferences.open(context, "soundwave", 0), 0)
        }))
    }

    @Test fun settingSleepWhileEarlyRequestQueuedPreventsAdvance() {
        assertEquals(0, queuedRequest({ set("sleepStopAfterCurrent", true) }))
    }

    @Test fun manualNextStillWorksInLoopMode() {
        PlayerService.setMode(PlayMode.LOOP_ONE)
        assertEquals(1, queuedRequest({}, manual = true))
    }
}
