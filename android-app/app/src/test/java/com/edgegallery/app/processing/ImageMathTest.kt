package com.edgegallery.app.processing

import com.edgegallery.app.model.ExposureClass
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageMathTest {

    @Test
    fun `dHash is zero when every row grows brighter from left to right`() {
        val luminance = IntArray(
            ImageMath.DIFFERENCE_HASH_WIDTH * ImageMath.DIFFERENCE_HASH_HEIGHT,
        ) { index -> index % ImageMath.DIFFERENCE_HASH_WIDTH }

        assertEquals(0L, ImageMath.calculateDifferenceHash(luminance))
    }

    @Test
    fun `dHash sets every bit when every row grows darker from left to right`() {
        val luminance = IntArray(
            ImageMath.DIFFERENCE_HASH_WIDTH * ImageMath.DIFFERENCE_HASH_HEIGHT,
        ) { index ->
            ImageMath.DIFFERENCE_HASH_WIDTH - index % ImageMath.DIFFERENCE_HASH_WIDTH
        }

        assertEquals(-1L, ImageMath.calculateDifferenceHash(luminance))
    }

    @Test
    fun `dark image is reported as underexposed`() {
        val result = ImageMath.analyzeExposure(IntArray(64 * 64) { 10 })

        assertEquals(ExposureClass.UNDEREXPOSED, result.classification)
        assertEquals(1.0, result.darkPixelRatio, 0.0)
    }

    @Test
    fun `bright image is reported as overexposed`() {
        val result = ImageMath.analyzeExposure(IntArray(64 * 64) { 250 })

        assertEquals(ExposureClass.OVEREXPOSED, result.classification)
        assertEquals(1.0, result.brightPixelRatio, 0.0)
    }

    @Test
    fun `middle gray image has normal exposure`() {
        val result = ImageMath.analyzeExposure(IntArray(64 * 64) { 128 })

        assertEquals(ExposureClass.NORMAL, result.classification)
        assertEquals(128.0, result.meanLuminance, 0.0)
    }
}
