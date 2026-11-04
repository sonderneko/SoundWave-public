package xyz.cncnlp.soundwave.ui.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.cncnlp.soundwave.DlnaController
import xyz.cncnlp.soundwave.DlnaDevice

internal data class PlayerDlnaSessionSnapshot(
    val device: DlnaDevice,
    val songTitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val lastOkAt: Long
)

internal data class PlayerDlnaPanelSnapshot(
    val devices: List<DlnaDevice> = emptyList(),
    val isScanning: Boolean = false,
    val session: PlayerDlnaSessionSnapshot? = null
)

internal interface PlayerDlnaGateway {
    suspend fun scanDevices(): List<DlnaDevice>
    suspend fun play(device: DlnaDevice, mediaUrl: String, title: String): Result<Unit>
    fun currentSession(): PlayerDlnaSessionSnapshot?
    suspend fun poll(device: DlnaDevice): PlayerDlnaSessionSnapshot?
    suspend fun pause(device: DlnaDevice): Result<Unit>
    suspend fun resume(device: DlnaDevice): Result<Unit>
    suspend fun seek(device: DlnaDevice, positionMs: Long): Result<Unit>
    suspend fun stop(device: DlnaDevice): Result<Unit>
    fun markDisconnected()
}

/** Thin delegation only; network/SSDP/SOAP behavior remains in DlnaController. */
internal object DlnaControllerGateway : PlayerDlnaGateway {
    override suspend fun scanDevices(): List<DlnaDevice> = DlnaController.scan()
    override suspend fun play(device: DlnaDevice, mediaUrl: String, title: String): Result<Unit> =
        DlnaController.play(device, mediaUrl, title)
    override fun currentSession(): PlayerDlnaSessionSnapshot? = DlnaController.session?.toPlayerSnapshot()
    override suspend fun poll(device: DlnaDevice): PlayerDlnaSessionSnapshot? =
        DlnaController.poll(device)?.toPlayerSnapshot()
    override suspend fun pause(device: DlnaDevice): Result<Unit> = DlnaController.pause(device)
    override suspend fun resume(device: DlnaDevice): Result<Unit> = DlnaController.resume(device)
    override suspend fun seek(device: DlnaDevice, positionMs: Long): Result<Unit> =
        DlnaController.seek(device, positionMs)
    override suspend fun stop(device: DlnaDevice): Result<Unit> = DlnaController.stop(device)
    override fun markDisconnected() = DlnaController.markDisconnected()

    private fun DlnaController.Session.toPlayerSnapshot() = PlayerDlnaSessionSnapshot(
        device = device,
        songTitle = songTitle,
        positionMs = positionMs,
        durationMs = durationMs,
        isPlaying = isPlaying,
        lastOkAt = lastOkAt
    )
}

/** Owns only DLNA session effects and the immutable state displayed by FullPlayer's device panel. */
internal class PlayerDlnaSessionCoordinator(
    private val scope: CoroutineScope,
    private val gateway: PlayerDlnaGateway,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val disconnectedAfterMs: Long = DISCONNECTED_AFTER_MS
) {
    private val _snapshot = MutableStateFlow(PlayerDlnaPanelSnapshot(session = gateway.currentSession()))
    val snapshot: StateFlow<PlayerDlnaPanelSnapshot> = _snapshot.asStateFlow()

    fun scan() {
        if (_snapshot.value.isScanning) return
        _snapshot.value = _snapshot.value.copy(isScanning = true)
        scope.launch {
            try {
                _snapshot.value = _snapshot.value.copy(devices = gateway.scanDevices())
            } finally {
                _snapshot.value = _snapshot.value.copy(isScanning = false)
            }
        }
    }

    fun cast(
        device: DlnaDevice,
        mediaUrl: String,
        title: String,
        onConnected: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        scope.launch {
            val result = gateway.play(device, mediaUrl, title)
            if (result.isFailure) {
                onFailure(result.exceptionOrNull()?.message ?: "设备拒绝播放")
                return@launch
            }
            publishCurrentSession()
            onConnected(device.name)
            pollRemoteSession(device)
        }
    }

    fun pause(onDisconnected: () -> Unit) = runRemoteCommand({ gateway.pause(it) }, onDisconnected)

    fun resume(onDisconnected: () -> Unit) = runRemoteCommand({ gateway.resume(it) }, onDisconnected)

    fun seek(positionMs: Long, onDisconnected: () -> Unit) =
        runRemoteCommand({ gateway.seek(it, positionMs) }, onDisconnected)

    fun stop(onFailure: () -> Unit) = runRemoteCommand({ gateway.stop(it) }, onFailure, disconnectAfterAttempt = true)

    private fun runRemoteCommand(
        operation: suspend (DlnaDevice) -> Result<Unit>,
        onFailure: () -> Unit,
        disconnectAfterAttempt: Boolean = false
    ) {
        val device = gateway.currentSession()?.device ?: return
        scope.launch {
            val succeeded = operation(device).isSuccess
            if (disconnectAfterAttempt || !succeeded) gateway.markDisconnected()
            publishCurrentSession()
            if (!succeeded) onFailure()
        }
    }

    private suspend fun pollRemoteSession(device: DlnaDevice) {
        while (gateway.currentSession()?.device?.location == device.location) {
            delay(pollIntervalMs)
            if (gateway.currentSession()?.device?.location != device.location) break
            val session = gateway.poll(device)
            if (session == null) {
                publishCurrentSession()
                break
            }
            publishSession(session)
            if (!session.isPlaying && nowMs() - session.lastOkAt > disconnectedAfterMs) {
                gateway.markDisconnected()
                publishCurrentSession()
                break
            }
        }
    }

    private fun publishCurrentSession() = publishSession(gateway.currentSession())

    private fun publishSession(session: PlayerDlnaSessionSnapshot?) {
        _snapshot.value = _snapshot.value.copy(session = session)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_500L
        const val DISCONNECTED_AFTER_MS = 15_000L
    }
}
