package xyz.cncnlp.soundwave

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaylistCoverEncodingTest {
    @Test fun noisyLargeCoverFitsCloudLimitAndDecodesAfterRoundTrip() {
        val file = File.createTempFile("playlist-cover-", ".png")
        val bitmap = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
        try {
            val random = Random(17)
            val pixels = IntArray(bitmap.width * bitmap.height) { random.nextInt() or (0xff shl 24) }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val cover = AvatarStore.toPlaylistCoverB64(file.absolutePath)
            assertTrue(cover.startsWith("data:image/jpeg;base64,"))
            assertTrue(cover.length <= MAX_CLOUD_INLINE_IMAGE_CHARS)
            assertEquals(cover, cloudSafeCover(cover, includeLocalUris = false))
            val bytes = Base64.decode(cover.substringAfter(','), Base64.NO_WRAP)
            val restored = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertTrue(maxOf(restored.width, restored.height) <= 1024)
                assertTrue(restored.width > 0 && restored.height > 0)
            } finally { restored.recycle() }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun missingOrInvalidImageDoesNotProduceAnUnsyncableValue() {
        assertEquals("", AvatarStore.toPlaylistCoverB64("/missing/playlist-cover.jpg"))
        val file = File.createTempFile("invalid-cover-", ".jpg")
        try {
            file.writeText("not an image")
            assertEquals("", AvatarStore.toPlaylistCoverB64(file.absolutePath))
        } finally { file.delete() }
    }
}
