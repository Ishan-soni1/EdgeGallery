package com.edgegallery.app.nativebridge

import com.edgegallery.app.model.DuplicateGroup
import com.edgegallery.app.model.DuplicateType
import com.edgegallery.app.model.ImageFeatures
import com.edgegallery.app.processing.SimilarityMath

/** The only Kotlin class that knows how the JNI result is encoded. */
object NativeEngine {
    private const val EXACT_GROUP = 0
    private const val MODIFIED_COPY_GROUP = 1
    private const val RELATED_GROUP = 2

    init {
        System.loadLibrary("edgegallery_jni")
    }

    fun findDuplicateGroups(
        features: List<ImageFeatures>,
        hammingThreshold: Int = DEFAULT_HAMMING_THRESHOLD,
        similarityThreshold: Float = DEFAULT_SIMILARITY_THRESHOLD,
    ): List<DuplicateGroup> {
        if (features.size < 2) return emptyList()

        val embeddingDimension = features.first().embedding.size
        if (embeddingDimension == 0) return emptyList()

        val contentHashes = Array(features.size) { index -> features[index].sha256 }
        val differenceHashes = LongArray(features.size) { index -> features[index].differenceHash }
        val widths = IntArray(features.size) { index -> features[index].imageWidth }
        val heights = IntArray(features.size) { index -> features[index].imageHeight }
        val fileSizes = LongArray(features.size) { index -> features[index].fileSize }

        // Flatten all embeddings into a single contiguous array for efficient
        // JNI transfer. This avoids N separate object references across the
        // JNI boundary.
        val flatEmbeddings = FloatArray(features.size * embeddingDimension)
        for (index in features.indices) {
            features[index].embedding.copyInto(
                destination = flatEmbeddings,
                destinationOffset = index * embeddingDimension,
            )
        }

        val encodedGroups = clusterNative(
            contentHashes,
            differenceHashes,
            flatEmbeddings,
            embeddingDimension,
            features.size,
            hammingThreshold,
            similarityThreshold,
            widths,
            heights,
            fileSizes,
        )

        return decodeGroups(encodedGroups, features)
    }

    /**
     * Native records are flat and explicit:
     * [group type, member count, member index, member index, ...]
     */
    private fun decodeGroups(
        encodedGroups: IntArray,
        features: List<ImageFeatures>,
    ): List<DuplicateGroup> {
        val groups = mutableListOf<DuplicateGroup>()
        val featuresById = features.associateBy(ImageFeatures::id)
        var cursor = 0

        while (cursor < encodedGroups.size) {
            require(cursor + 1 < encodedGroups.size) { "Native group header is incomplete" }

            val encodedType = encodedGroups[cursor++]
            val memberCount = encodedGroups[cursor++]
            require(memberCount >= 2 && cursor + memberCount <= encodedGroups.size) {
                "Native group members are incomplete"
            }

            val memberIds = List(memberCount) {
                val imageIndex = encodedGroups[cursor++]
                require(imageIndex in features.indices) { "Native image index is invalid" }
                features[imageIndex].id
            }

            val type = when (encodedType) {
                EXACT_GROUP -> DuplicateType.EXACT
                MODIFIED_COPY_GROUP -> DuplicateType.MODIFIED_COPY
                RELATED_GROUP -> DuplicateType.RELATED
                else -> error("Native group type is invalid")
            }
            groups += DuplicateGroup(
                type = type,
                memberIds = memberIds,
                comparisons = SimilarityMath.comparisonsFor(memberIds, featuresById),
            )
        }

        return groups
    }

    private external fun clusterNative(
        contentHashes: Array<String>,
        differenceHashes: LongArray,
        embeddings: FloatArray,
        embeddingDimension: Int,
        imageCount: Int,
        hammingThreshold: Int,
        similarityThreshold: Float,
        widths: IntArray,
        heights: IntArray,
        fileSizes: LongArray,
    ): IntArray

    const val DEFAULT_HAMMING_THRESHOLD = 8
    const val DEFAULT_SIMILARITY_THRESHOLD = 0.85f
}
