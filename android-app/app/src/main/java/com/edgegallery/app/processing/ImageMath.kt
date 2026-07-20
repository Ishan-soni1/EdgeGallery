package com.edgegallery.app.processing

import com.edgegallery.app.model.ExposureClass
import com.edgegallery.app.model.ExposureResult

/** Pure image calculations kept separate so they can be unit tested without Android. */
object ImageMath {
    const val DIFFERENCE_HASH_WIDTH = 9
    const val DIFFERENCE_HASH_HEIGHT = 8

    /** Builds a 64-bit dHash from a 9x8 row-major luminance image. */
    fun calculateDifferenceHash(luminance: IntArray): Long {
        val expectedPixels = DIFFERENCE_HASH_WIDTH * DIFFERENCE_HASH_HEIGHT
        require(luminance.size == expectedPixels) {
            "dHash needs exactly $expectedPixels luminance values"
        }

        var hash = 0L
        var bitPosition = 0
        for (row in 0 until DIFFERENCE_HASH_HEIGHT) {
            val rowStart = row * DIFFERENCE_HASH_WIDTH
            for (column in 0 until DIFFERENCE_HASH_WIDTH - 1) {
                if (luminance[rowStart + column] > luminance[rowStart + column + 1]) {
                    hash = hash or (1L shl bitPosition)
                }
                bitPosition++
            }
        }

        return hash
    }

    /**
     * Uses simple, documented thresholds for an MVP exposure warning.
     * These values must later be evaluated on labelled photographs.
     */
    fun analyzeExposure(luminance: IntArray): ExposureResult {
        require(luminance.isNotEmpty()) { "Exposure analysis needs at least one pixel" }

        val meanLuminance = luminance.average()
        val darkPixelRatio = luminance.count { it <= 15 }.toDouble() / luminance.size
        val brightPixelRatio = luminance.count { it >= 240 }.toDouble() / luminance.size

        val classification = when {
            meanLuminance < 50 || darkPixelRatio > 0.60 -> ExposureClass.UNDEREXPOSED
            meanLuminance > 205 || brightPixelRatio > 0.60 -> ExposureClass.OVEREXPOSED
            else -> ExposureClass.NORMAL
        }

        return ExposureResult(
            classification = classification,
            meanLuminance = meanLuminance,
            darkPixelRatio = darkPixelRatio,
            brightPixelRatio = brightPixelRatio,
        )
    }
}
