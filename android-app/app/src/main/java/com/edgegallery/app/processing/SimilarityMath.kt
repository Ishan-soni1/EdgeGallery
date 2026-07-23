package com.edgegallery.app.processing

import com.edgegallery.app.model.ImageComparison
import com.edgegallery.app.model.ImageFeatures
import kotlin.math.sqrt

/** Pure comparison calculations shared by result diagnostics and unit tests. */
object SimilarityMath {

    fun hammingDistance(left: Long, right: Long): Int =
        java.lang.Long.bitCount(left xor right)

    fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
        if (left.isEmpty() || left.size != right.size) return 0.0f

        var dot = 0.0
        var leftMagnitude = 0.0
        var rightMagnitude = 0.0
        for (index in left.indices) {
            dot += left[index].toDouble() * right[index]
            leftMagnitude += left[index].toDouble() * left[index]
            rightMagnitude += right[index].toDouble() * right[index]
        }

        val denominator = sqrt(leftMagnitude) * sqrt(rightMagnitude)
        return if (denominator == 0.0) 0.0f else (dot / denominator).toFloat()
    }

    fun comparisonsFor(
        memberIds: List<String>,
        featuresById: Map<String, ImageFeatures>,
    ): List<ImageComparison> = buildList {
        for (leftIndex in 0 until memberIds.lastIndex) {
            for (rightIndex in leftIndex + 1 until memberIds.size) {
                val left = featuresById[memberIds[leftIndex]] ?: continue
                val right = featuresById[memberIds[rightIndex]] ?: continue
                add(
                    ImageComparison(
                        leftId = left.id,
                        rightId = right.id,
                        exactMatch = left.sha256 == right.sha256,
                        hammingDistance = hammingDistance(
                            left.differenceHash,
                            right.differenceHash,
                        ),
                        cosineSimilarity = cosineSimilarity(left.embedding, right.embedding),
                    ),
                )
            }
        }
    }

    fun comparisonsFor(features: List<ImageFeatures>): List<ImageComparison> =
        comparisonsFor(
            memberIds = features.map(ImageFeatures::id),
            featuresById = features.associateBy(ImageFeatures::id),
        )
}
