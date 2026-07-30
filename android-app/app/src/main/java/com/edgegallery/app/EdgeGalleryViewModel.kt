package com.edgegallery.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
        // Keep access after configuration changes and app restarts when the
        // selected document provider supports persistable permissions.
        val resolver = getApplication<Application>().contentResolver
        val images = selectedImages.distinct().take(MAX_SELECTED_IMAGES)
        images.forEach { uri ->
            try {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                try {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                    // Some providers grant access only for the current activity.
                }
            }
        }
        mutableUiState.value = ScanUiState.Ready(images)
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
                // NativeEngine already returns a small, bounded sample of
                // evidence for each group; do not rebuild a global pair matrix.
                val comparisons = groups.flatMap { group -> group.comparisons }
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

    /**
     * Permanently deletes only the photos explicitly selected in the results
     * screen. The UI presents a confirmation dialog before calling this method.
     */
    fun deletePhotos(photoIds: Set<String>) {
        val completed = mutableUiState.value as? ScanUiState.Completed ?: return
        if (completed.isDeleting || photoIds.isEmpty()) return

        val requested = completed.features.filter { it.id in photoIds }
        if (requested.isEmpty()) return

        mutableUiState.value = completed.copy(
            isDeleting = true,
            actionMessage = null,
        )

        viewModelScope.launch {
            val deletedIds = mutableSetOf<String>()
            val failures = mutableListOf<String>()
            val resolver = getApplication<Application>().contentResolver

            withContext(Dispatchers.IO) {
                requested.forEach { feature ->
                    try {
                        val deleted = if (
                            DocumentsContract.isDocumentUri(
                                getApplication<Application>(),
                                feature.uri,
                            )
                        ) {
                            DocumentsContract.deleteDocument(resolver, feature.uri)
                        } else {
                            resolver.delete(feature.uri, null, null) > 0
                        }

                        if (deleted) {
                            deletedIds += feature.id
                        } else {
                            failures += feature.displayName
                        }
                    } catch (_: Exception) {
                        failures += feature.displayName
                    }
                }
            }

            val latest = mutableUiState.value as? ScanUiState.Completed ?: return@launch
            val remainingFeatures = latest.features.filterNot { it.id in deletedIds }
            val featuresById = remainingFeatures.associateBy(ImageFeatures::id)
            val remainingGroups = latest.groups.mapNotNull { group ->
                val memberIds = group.memberIds.filterNot { it in deletedIds }
                if (memberIds.size < 2) {
                    null
                } else {
                    group.copy(
                        memberIds = memberIds,
                        comparisons = SimilarityMath.comparisonsFor(memberIds, featuresById),
                    )
                }
            }
            val remainingComparisons = remainingGroups.flatMap { it.comparisons }
            val message = when {
                deletedIds.isNotEmpty() && failures.isEmpty() ->
                    "Deleted ${deletedIds.size} photo${if (deletedIds.size == 1) "" else "s"}."
                deletedIds.isNotEmpty() ->
                    "Deleted ${deletedIds.size}; ${failures.size} could not be deleted."
                else ->
                    "No photos were deleted. The selected storage provider may not allow deletion."
            }

            mutableUiState.value = latest.copy(
                features = remainingFeatures,
                groups = remainingGroups,
                comparisons = remainingComparisons,
                isDeleting = false,
                actionMessage = message,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (embeddingExtractor.isInitialized()) {
            embeddingExtractor.value.close()
        }
    }

    private companion object {
        const val MAX_SELECTED_IMAGES = 100
    }
}
