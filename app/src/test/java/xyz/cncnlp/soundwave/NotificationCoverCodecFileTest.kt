package xyz.cncnlp.soundwave

import android.graphics.Bitmap
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NotificationCoverCodecFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun decodesValidatedFileUsingExistingPowerOfTwoSampling() {
        val file = temporaryFolder.newFile("cover.png")
        val source = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { output ->
                check(source.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            source.recycle()
        }

        val decoded = NotificationCoverCodec.decode(file)
        checkNotNull(decoded)
        try {
            assertEquals(800, decoded.width)
            assertEquals(400, decoded.height)
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun rejectsMissingMalformedAndOversizedFilesBeforeDecode() {
        assertNull(NotificationCoverCodec.decode(File(temporaryFolder.root, "missing.png")))

        val malformed = temporaryFolder.newFile("malformed.png")
        malformed.writeText("not an image")
        assertNull(NotificationCoverCodec.decode(malformed))

        val oversized = temporaryFolder.newFile("oversized.png")
        RandomAccessFile(oversized, "rw").use {
            it.setLength(BoundedCoverReader.MAX_BYTES.toLong() + 1L)
        }
        assertNull(NotificationCoverCodec.decode(oversized))
    }
}
