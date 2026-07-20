package com.edgegallery.app.model

import android.net.Uri

/** A small, immutable description of one analysed image. */
data class ImageFeatures(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val sha256: String,
    val embedding: FloatArray,
    val exposure: ExposureResult,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageFeatures) return false
        return id == other.id &&
            uri == other.uri &&
            displayName == other.displayName &&
            sha256 == other.sha256 &&
            embedding.contentEquals(other.embedding) &&
            exposure == other.exposure
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + sha256.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + exposure.hashCode()
        return result
    }
}

enum class ExposureClass {
    UNDEREXPOSED,
    NORMAL,
    OVEREXPOSED,
}

/** Exposure is intentionally reported as a warning, not a quality judgement. */
data class ExposureResult(
    val classification: ExposureClass,
    val meanLuminance: Double,
    val darkPixelRatio: Double,
    val brightPixelRatio: Double,
)

enum class DuplicateType {
    EXACT,
    VISUALLY_SIMILAR,
}

data class DuplicateGroup(
    val type: DuplicateType,
    val memberIds: List<String>,
)

data class ScanIssue(
    val imageName: String,
    val message: String,
)

/** Every screen is rendered directly from one of these states. */
sealed interface ScanUiState {
    data class Ready(val selectedImages: List<Uri> = emptyList()) : ScanUiState

    data class Scanning(val processed: Int, val total: Int) : ScanUiState

    data class Completed(
        val features: List<ImageFeatures>,
        val groups: List<DuplicateGroup>,
        val issues: List<ScanIssue>,
    ) : ScanUiState

    data class Failed(val message: String) : ScanUiState
}
