package com.edgegallery.app.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class SimilarityMathTest {

    @Test
    fun `hamming distance counts changed bits`() {
        assertEquals(3, SimilarityMath.hammingDistance(0b0000, 0b1011))
    }

    @Test
    fun `cosine similarity is one for identical vectors`() {
        assertEquals(
            1.0f,
            SimilarityMath.cosineSimilarity(
                floatArrayOf(0.6f, 0.8f),
                floatArrayOf(0.6f, 0.8f),
            ),
            0.000001f,
        )
    }

    @Test
    fun `cosine similarity is zero for orthogonal vectors`() {
        assertEquals(
            0.0f,
            SimilarityMath.cosineSimilarity(
                floatArrayOf(1.0f, 0.0f),
                floatArrayOf(0.0f, 1.0f),
            ),
            0.000001f,
        )
    }
}
