package xyz.cncnlp.soundwave

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Notification artwork decoding. File inputs are length-checked and sampled by the platform decoder,
 * avoiding a second cover-sized compressed ByteArray in the playback service.
 */
internal object NotificationCoverCodec {
    private const val TARGET_PX = 512

    fun decode(file: File): Bitmap? {
        if (!file.isFile) return null
        val size = file.length()
        if (size <= 0L || size > BoundedCoverReader.MAX_BYTES) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        if (sample == 0) return null
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    /** Kept for existing codec-boundary tests; production notification loading uses the file overload. */
    fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty() || bytes.size > BoundedCoverReader.MAX_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        if (sample == 0) return null
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun sampleSize(width: Int, height: Int): Int {
        val largest = maxOf(width, height)
        if (largest <= 0) return 0
        var sample = 1
        while (largest / (sample * 2) >= TARGET_PX) sample *= 2
        return sample
    }
}
