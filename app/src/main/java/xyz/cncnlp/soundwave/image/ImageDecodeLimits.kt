package xyz.cncnlp.soundwave.image

/** Shared input and decoded-pixel budgets for local and remote image paths. */
internal object ImageDecodeLimits {
    const val MAX_COMPRESSED_BYTES: Int = 16 * 1024 * 1024
    const val MAX_COMPRESSED_BYTES_LONG: Long = 16L * 1024 * 1024
    const val MAX_AVATAR_DECODED_BYTES: Int = 1_500_000
    const val MAX_AVATAR_ENCODED_CHARS: Int = 2_000_024
    const val MAX_AVATAR_DIMENSION: Int = 1024
    const val MAX_IMAGE_DIMENSION: Int = 4096
    const val MAX_IMAGE_PIXELS: Long = 4L * 1024 * 1024
    /** ARGB_8888 decoded pixels at the source-size ceiling (decoder scratch/cache excluded). */
    const val MAX_ARGB_BITMAP_BYTES: Long = MAX_IMAGE_PIXELS * 4L

    fun hasAllowedDimensions(width: Int, height: Int): Boolean =
        width > 0 && height > 0 &&
            width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION &&
            width.toLong() * height.toLong() <= MAX_IMAGE_PIXELS

    /** Smallest power-of-two sample that keeps the decoded longest edge at or below the target. */
    fun sampleSize(width: Int, height: Int, targetMaxDimension: Int): Int {
        if (!hasAllowedDimensions(width, height) || targetMaxDimension <= 0) return 0
        var sample = 1
        while (ceilDiv(width, sample) > targetMaxDimension ||
            ceilDiv(height, sample) > targetMaxDimension
        ) {
            sample = sample shl 1
        }
        return sample
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()
}
