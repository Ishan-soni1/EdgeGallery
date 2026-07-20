package com.edgegallery.app.processing

import com.edgegallery.app.model.ExposureClass
import com.edgegallery.app.model.ExposureResult

/** Pure image calculations kept separate so they can be unit tested without Android. */
object ImageMath {

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
