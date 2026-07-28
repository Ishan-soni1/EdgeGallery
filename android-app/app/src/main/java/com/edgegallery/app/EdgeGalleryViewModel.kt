package com.edgegallery.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.edgegallery.app.model.ImageFeatures
import com.edgegallery.app.model.ScanIssue
import com.edgegallery.app.model.ScanUiState
import com.edgegallery.app.nativebridge.NativeEngine
import com.edgegallery.app.processing.EmbeddingExtractor
import com.edgegallery.app.processing.ImageProcessor
import com.edgegallery.app.processing.SimilarityMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Coordinates selection, sequential image analysis, native grouping and UI state. */
class EdgeGalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val embeddingExtractor = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EmbeddingExtractor(application)
    }
    private val mutableUiState = MutableStateFlow<ScanUiState>(ScanUiState.Ready())
    private var scanJob: Job? = null

    val uiState: StateFlow<ScanUiState> = mutableUiState.asStateFlow()

    fun selectImages(selectedImages: List<Uri>) {
        // A picker should not return duplicates, but distinct() makes the contract explicit.
        mutableUiState.value = ScanUiState.Ready(selectedImages.distinct())
    }

    fun startScan() {
        val readyState = mutableUiState.value as? ScanUiState.Ready ?: return
        if (readyState.selectedImages.isEmpty() || scanJob?.isActive == true) return

        scanJob = viewModelScope.launch {
            val features = mutableListOf<ImageFeatures>()
            val issues = mutableListOf<ScanIssue>()
            val selectedImages = readyState.selectedImages

            mutableUiState.value = ScanUiState.Scanning(processed = 0, total = selectedImages.size)

            val imageProcessor = try {
                // Model loading can perform file I/O and native initialization,
                // so keep it away from initial screen creation and the main thread.
                withContext(Dispatchers.IO) {
                    ImageProcessor(
                        getApplication<Application>().contentResolver,
                        embeddingExtractor.value,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.value = ScanUiState.Failed(
                    error.message ?: "The on-device similarity model could not be loaded",
                )
                return@launch
            }

            // Sequential decoding keeps memory predictable for this first MVP.
            selectedImages.forEachIndexed { index, uri ->
                try {
                    features += imageProcessor.analyze(uri)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    issues += ScanIssue(
                        imageName = uri.lastPathSegment ?: "Selected image",
                        message = error.message ?: "The image could not be analysed",
                    )
                }

                mutableUiState.value = ScanUiState.Scanning(
                    processed = index + 1,
                    total = selectedImages.size,
                )
            }

            try {
                val groups = NativeEngine.findDuplicateGroups(features)
                // Compute comparisons only within groups (Phase 3 optimization).
                // This replaces the old O(n²) all-pairs scan with O(Σ gᵢ²) where
                // gᵢ is the size of each group — typically much smaller.
                val featuresById = features.associateBy(ImageFeatures::id)
                val comparisons = groups.flatMap { group ->
                    SimilarityMath.comparisonsFor(group.memberIds, featuresById)
                }
                mutableUiState.value = ScanUiState.Completed(
                    features = features,
                    groups = groups,
                    comparisons = comparisons,
                    issues = issues,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.value = ScanUiState.Failed(
                    error.message ?: "Native duplicate grouping failed",
                )
            }
        }
    }

    fun reset() {
        mutableUiState.value = ScanUiState.Ready()
    }

    override fun onCleared() {
        super.onCleared()
        if (embeddingExtractor.isInitialized()) {
            embeddingExtractor.value.close()
        }
    }
}
