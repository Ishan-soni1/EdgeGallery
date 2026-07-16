package com.edgegallery.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.edgegallery.app.model.ImageFeatures
import com.edgegallery.app.model.ScanIssue
import com.edgegallery.app.model.ScanUiState
import com.edgegallery.app.nativebridge.NativeEngine
import com.edgegallery.app.processing.ImageProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Coordinates selection, sequential image analysis, native grouping and UI state. */
class EdgeGalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val imageProcessor = ImageProcessor(application.contentResolver)
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

            // Sequential decoding keeps memory predictable for this first MVP.
            selectedImages.forEachIndexed { index, uri ->
                try {
                    features += imageProcessor.analyze(uri)
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
                mutableUiState.value = ScanUiState.Completed(
                    features = features,
                    groups = groups,
                    issues = issues,
                )
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
}
