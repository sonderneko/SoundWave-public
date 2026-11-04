package xyz.cncnlp.soundwave.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSystemVolumeControllerTest {
    @Test
    fun initialSystemStepIsNormalizedAndClampedForUi() {
        val gateway = FakeGateway(maxStep = 20, currentStep = 5)
        val controller = PlayerSystemVolumeController(gateway)

        assertEquals(0.25f, controller.initialVolumeLevel(), 0.0001f)
        assertEquals(0f, PlayerSystemVolumePolicy.initialLevel(-2, 20), 0f)
        assertEquals(1f, PlayerSystemVolumePolicy.initialLevel(21, 20), 0f)
    }

    @Test
    fun requestedLevelsAreClampedAtBothSystemVolumeBounds() {
        val gateway = FakeGateway(maxStep = 10)
        val controller = PlayerSystemVolumeController(gateway)

        assertEquals(0f, controller.setVolume(-0.2f), 0f)
        assertEquals(1f, controller.setVolume(1.2f), 0f)
        assertEquals(listOf(0, 10), gateway.writtenSteps)
    }

    @Test
    fun normalizedLevelsMapToNearestDiscreteStepAndInvokeTheGatewayWrite() {
        val gateway = FakeGateway(maxStep = 7)
        val controller = PlayerSystemVolumeController(gateway)

        assertEquals(0.36f, controller.setVolume(0.36f), 0f)
        assertEquals(0.5f, controller.setVolume(0.5f), 0f)
        assertEquals(listOf(3, 4), gateway.writtenSteps)
    }

    private class FakeGateway(
        private val maxStep: Int,
        private val currentStep: Int = 0
    ) : PlayerSystemVolumeGateway {
        val writtenSteps = mutableListOf<Int>()

        override fun maxMusicVolume(): Int = maxStep
        override fun currentMusicVolume(): Int = currentStep
        override fun writeMusicVolume(step: Int) {
            writtenSteps += step
        }
    }
}
