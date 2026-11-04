package xyz.cncnlp.soundwave.ui.common

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ReturnToTopTest {
    @Test fun normalReturnIsOneAnimationWithoutPreJump() = runBlocking {
        val calls=mutableListOf<String>()
        returnToTop(false,{calls.add("snap")},{calls.add("animate")})
        assertEquals(listOf("animate"),calls)
    }
    @Test fun reducedMotionReturnsInOneSnap() = runBlocking {
        val calls=mutableListOf<String>()
        returnToTop(true,{calls.add("snap")},{calls.add("animate")})
        assertEquals(listOf("snap"),calls)
    }
}
