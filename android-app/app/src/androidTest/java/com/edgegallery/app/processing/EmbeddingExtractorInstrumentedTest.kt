package com.edgegallery.app.processing

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddingExtractorInstrumentedTest {

    @Test
    fun bundledModelLoadsAndProducesFiniteEmbedding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GRAY)
        }
        val extractor = EmbeddingExtractor(context)

        try {
            val embedding = extractor.extract(bitmap)

            assertEquals(1024, extractor.dimension())
            assertEquals(extractor.dimension(), embedding.size)
            assertTrue(embedding.all(Float::isFinite))
            assertTrue(embedding.any { it != 0.0f })
        } finally {
            extractor.close()
            bitmap.recycle()
        }
    }
}
