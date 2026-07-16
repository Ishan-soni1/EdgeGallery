package com.edgegallery.app.nativebridge

import com.edgegallery.app.model.DuplicateGroup
import com.edgegallery.app.model.DuplicateType
import com.edgegallery.app.model.ImageFeatures

/** The only Kotlin class that knows how the JNI result is encoded. */
object NativeEngine {
    private const val EXACT_GROUP = 0
    private const val VISUALLY_SIMILAR_GROUP = 1

    init {
        System.loadLibrary("edgegallery_jni")
    }

    fun findDuplicateGroups(
        features: List<ImageFeatures>,
        hammingThreshold: Int = 8,
    ): List<DuplicateGroup> {
        if (features.size < 2) return emptyList()

        val contentHashes = Array(features.size) { index -> features[index].sha256 }
        val differenceHashes = LongArray(features.size) { index -> features[index].differenceHash }
        val encodedGroups = clusterNative(contentHashes, differenceHashes, hammingThreshold)

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
                VISUALLY_SIMILAR_GROUP -> DuplicateType.VISUALLY_SIMILAR
                else -> error("Native group type is invalid")
            }
            groups += DuplicateGroup(type = type, memberIds = memberIds)
        }

        return groups
    }

    private external fun clusterNative(
        contentHashes: Array<String>,
        differenceHashes: LongArray,
        hammingThreshold: Int,
    ): IntArray
}
