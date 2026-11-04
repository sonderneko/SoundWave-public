package xyz.cncnlp.soundwave.ui.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.DlnaDevice

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerDlnaSessionCoordinatorTest {
    private val firstDevice = DlnaDevice("Living room", "http://first", "http://first/control")
    private val secondDevice = DlnaDevice("Bedroom", "http://second", "http://second/control")

    @Test
    fun concurrentScanRequestsAreDeduplicatedAndSnapshotSettles() = runTest {
        val gateway = FakeGateway()
        val gate = CompletableDeferred<List<DlnaDevice>>()
        gateway.scanGate = gate
        val coordinator = coordinator(gateway)

        coordinator.scan()
        coordinator.scan()
        runCurrent()

        assertEquals(1, gateway.scanCalls)
        assertTrue(coordinator.snapshot.value.isScanning)
        gate.complete(listOf(firstDevice))
        runCurrent()
        assertEquals(listOf(firstDevice), coordinator.snapshot.value.devices)
        assertFalse(coordinator.snapshot.value.isScanning)
    }

    @Test
    fun successfulRemotePollRefreshesThePanelSnapshot() = runTest {
        val gateway = FakeGateway()
        gateway.pollResult = {
            gateway.current!!.copy(positionMs = 4_000L, durationMs = 180_000L, lastOkAt = testScheduler.currentTime)
        }
        val coordinator = coordinator(gateway)
        var connected = ""

        coordinator.cast(firstDevice, "https://media/track", "Track", { connected = it }, {})
        runCurrent()
        assertEquals("Living room", connected)
        assertTrue(coordinator.snapshot.value.session!!.isPlaying)

        advanceTimeBy(2_500L)
        runCurrent()
        val session = coordinator.snapshot.value.session!!
        assertEquals(4_000L, session.positionMs)
        assertEquals(180_000L, session.durationMs)
        assertEquals(listOf(firstDevice), gateway.pollDevices)
    }

    @Test
    fun pollingStopsOnSessionSwitchWithoutClearingTheNewSession() = runTest {
        val gateway = FakeGateway()
        gateway.pollResult = { gateway.current?.copy(positionMs = 1_000L, lastOkAt = testScheduler.currentTime) }
        val coordinator = coordinator(gateway)
        coordinator.cast(firstDevice, "https://media/one", "One", {}, {})
        runCurrent()

        coordinator.cast(secondDevice, "https://media/two", "Two", {}, {})
        runCurrent()
        advanceTimeBy(2_500L)
        runCurrent()

        assertEquals(listOf(secondDevice), gateway.pollDevices)
        assertEquals(secondDevice, coordinator.snapshot.value.session?.device)
        assertEquals(secondDevice, gateway.current?.device)
    }

    @Test
    fun stalePausedSessionIsClearedAfterTheExistingFifteenSecondThreshold() = runTest {
        val gateway = FakeGateway()
        gateway.pollResult = { gateway.current?.copy(isPlaying = false, lastOkAt = 0L) }
        val coordinator = coordinator(gateway)
        coordinator.cast(firstDevice, "https://media/track", "Track", {}, {})
        runCurrent()

        advanceTimeBy(17_500L)
        runCurrent()

        assertEquals(7, gateway.pollDevices.size)
        assertEquals(1, gateway.disconnectCalls)
        assertNull(gateway.current)
        assertNull(coordinator.snapshot.value.session)
    }

    @Test
    fun failedRemoteControlsClearTheSessionAndReportFailure() = runTest {
        val operations: List<PlayerDlnaSessionCoordinator.(() -> Unit) -> Unit> = listOf(
            { onFailure -> pause(onFailure) },
            { onFailure -> resume(onFailure) },
            { onFailure -> seek(10_000L, onFailure) },
            { onFailure -> stop(onFailure) }
        )
        val gateways = operations.map { operation ->
            FakeGateway().also { it.current = session(firstDevice) }.let { gateway ->
                val coordinator = coordinator(gateway)
                operation(coordinator) { }
                gateway to coordinator
            }
        }
        runCurrent()

        gateways.forEach { (gateway, coordinator) ->
            assertEquals(1, gateway.disconnectCalls)
            assertNull(gateway.current)
            assertNull(coordinator.snapshot.value.session)
        }
    }

    private fun TestScope.coordinator(gateway: FakeGateway) = PlayerDlnaSessionCoordinator(
        scope = backgroundScope,
        gateway = gateway,
        nowMs = { testScheduler.currentTime }
    )

    private fun session(device: DlnaDevice) = PlayerDlnaSessionSnapshot(
        device = device,
        songTitle = "Track",
        positionMs = 0L,
        durationMs = 0L,
        isPlaying = true,
        lastOkAt = 0L
    )

    private class FakeGateway : PlayerDlnaGateway {
        var scanGate: CompletableDeferred<List<DlnaDevice>>? = null
        var scanCalls = 0
        var scanResult = emptyList<DlnaDevice>()
        var current: PlayerDlnaSessionSnapshot? = null
        var pollResult: ((DlnaDevice) -> PlayerDlnaSessionSnapshot?)? = null
        val pollDevices = mutableListOf<DlnaDevice>()
        var disconnectCalls = 0
        private val failure = Result.failure<Unit>(IllegalStateException("offline"))

        override suspend fun scanDevices(): List<DlnaDevice> {
            scanCalls += 1
            return scanGate?.await() ?: scanResult
        }

        override suspend fun play(device: DlnaDevice, mediaUrl: String, title: String): Result<Unit> {
            current = PlayerDlnaSessionSnapshot(
                device = device,
                songTitle = title,
                positionMs = 0L,
                durationMs = 0L,
                isPlaying = true,
                lastOkAt = 0L
            )
            return Result.success(Unit)
        }

        override fun currentSession(): PlayerDlnaSessionSnapshot? = current

        override suspend fun poll(device: DlnaDevice): PlayerDlnaSessionSnapshot? {
            pollDevices += device
            return (pollResult?.invoke(device) ?: current).also { current = it }
        }

        override suspend fun pause(device: DlnaDevice): Result<Unit> = failure
        override suspend fun resume(device: DlnaDevice): Result<Unit> = failure
        override suspend fun seek(device: DlnaDevice, positionMs: Long): Result<Unit> = failure
        override suspend fun stop(device: DlnaDevice): Result<Unit> = failure
        override fun markDisconnected() {
            disconnectCalls += 1
            current = null
        }
    }
}
