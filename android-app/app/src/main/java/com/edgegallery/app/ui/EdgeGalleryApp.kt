package com.edgegallery.app.ui

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgegallery.app.EdgeGalleryViewModel
import com.edgegallery.app.model.DuplicateGroup
import com.edgegallery.app.model.DuplicateType
import com.edgegallery.app.model.ExposureClass
import com.edgegallery.app.model.ImageComparison
import com.edgegallery.app.model.ImageFeatures
import com.edgegallery.app.model.ScanIssue
import com.edgegallery.app.model.ScanUiState
import com.edgegallery.app.nativebridge.NativeEngine
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF255EA8),
    secondary = Color(0xFF53657D),
    background = Color(0xFFF7F9FC),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    secondary = Color(0xFFBBC7DB),
)

@Composable
fun EdgeGalleryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeGalleryApp(viewModel: EdgeGalleryViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_SELECTED_IMAGES),
        onResult = viewModel::selectImages,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EdgeGallery", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        when (val state = uiState) {
            is ScanUiState.Ready -> ReadyScreen(
                selectedCount = state.selectedImages.size,
                onSelectImages = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onStartScan = viewModel::startScan,
                modifier = Modifier.padding(contentPadding),
            )

            is ScanUiState.Scanning -> ScanningScreen(
                state = state,
                modifier = Modifier.padding(contentPadding),
            )

            is ScanUiState.Completed -> ResultsScreen(
                state = state,
                onStartOver = viewModel::reset,
                modifier = Modifier.padding(contentPadding),
            )

            is ScanUiState.Failed -> FailedScreen(
                message = state.message,
                onStartOver = viewModel::reset,
                modifier = Modifier.padding(contentPadding),
            )
        }
    }
}

@Composable
private fun ReadyScreen(
    selectedCount: Int,
    onSelectImages: () -> Unit,
    onStartScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Find exact duplicates, modified copies, related photos, and exposure warnings.",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        InfoCard(
            title = "Private by design",
            body = "Your images are analysed on this device. The app has no internet permission and never deletes photos.",
        )

        OutlinedButton(
            onClick = onSelectImages,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (selectedCount == 0) "Select photos" else "Change selected photos")
        }

        Text(
            text = if (selectedCount == 0) {
                "No photos selected"
            } else {
                "$selectedCount photo${if (selectedCount == 1) "" else "s"} selected"
            },
            style = MaterialTheme.typography.bodyLarge,
        )

        Button(
            onClick = onStartScan,
            enabled = selectedCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start scan")
        }

        Text(
            text = "Results are suggestions only. The app never recommends a keeper or deletes a photo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanningScreen(state: ScanUiState.Scanning, modifier: Modifier = Modifier) {
    val progress = if (state.total == 0) 0f else state.processed.toFloat() / state.total

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Scanning photos", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text("${state.processed} / ${state.total} processed")
    }
}

@Composable
private fun ResultsScreen(
    state: ScanUiState.Completed,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val featuresById = state.features.associateBy(ImageFeatures::id)
    val exactGroups = state.groups.filter { it.type == DuplicateType.EXACT }
    val modifiedGroups = state.groups.filter { it.type == DuplicateType.MODIFIED_COPY }
    val relatedGroups = state.groups.filter { it.type == DuplicateType.RELATED }
    val exposureWarnings = state.features.filter {
        it.exposure.classification != ExposureClass.NORMAL
    }
    val groupedPairs = remember(state.groups) {
        state.groups
            .flatMap(DuplicateGroup::comparisons)
            .map { comparisonKey(it.leftId, it.rightId) }
            .toSet()
    }
    val closestUnmatched = remember(state.comparisons, groupedPairs) {
        state.comparisons
            .filterNot { it.exactMatch || comparisonKey(it.leftId, it.rightId) in groupedPairs }
            .sortedByDescending(ImageComparison::cosineSimilarity)
            .take(MAX_UNMATCHED_COMPARISONS)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Scan complete",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        item {
            Text(
                "${state.features.size} analysed • ${exactGroups.size} exact groups • " +
                    "${modifiedGroups.size} modified-copy groups • " +
                    "${relatedGroups.size} related groups • " +
                    "${exposureWarnings.size} exposure warnings",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item { ResultSectionTitle("Exact duplicates") }
        if (exactGroups.isEmpty()) {
            item { EmptyResult("No exact duplicates found") }
        } else {
            itemsIndexed(exactGroups) { index, group ->
                DuplicateGroupCard(
                    title = "Exact duplicate group ${index + 1}",
                    description = "These files have identical contents.",
                    group = group,
                    featuresById = featuresById,
                )
            }
        }

        item { ResultSectionTitle("Modified copies") }
        if (modifiedGroups.isEmpty()) {
            item { EmptyResult("No resized, recompressed, or lightly edited copies found") }
        } else {
            itemsIndexed(modifiedGroups) { index, group ->
                DuplicateGroupCard(
                    title = "Modified copy group ${index + 1}",
                    description = "These photos passed the dHash near-duplicate check.",
                    group = group,
                    featuresById = featuresById,
                )
            }
        }

        item { ResultSectionTitle("Related photos") }
        if (relatedGroups.isEmpty()) {
            item { EmptyResult("No related photo groups passed the MobileNet threshold") }
        } else {
            itemsIndexed(relatedGroups) { index, group ->
                DuplicateGroupCard(
                    title = "Related photo group ${index + 1}",
                    description = "These photos passed the MobileNet similarity check.",
                    group = group,
                    featuresById = featuresById,
                )
            }
        }

        if (closestUnmatched.isNotEmpty()) {
            item { ResultSectionTitle("Closest unmatched pairs") }
            item {
                Text(
                    "Diagnostic scores for the closest pairs that did not pass either threshold.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(closestUnmatched) { _, comparison ->
                UnmatchedComparisonCard(comparison, featuresById)
            }
        }

        item { ResultSectionTitle("Exposure warnings") }
        if (exposureWarnings.isEmpty()) {
            item { EmptyResult("No exposure warnings found") }
        } else {
            itemsIndexed(exposureWarnings, key = { _, feature -> feature.id }) { _, feature ->
                ExposureCard(feature)
            }
        }

        if (state.issues.isNotEmpty()) {
            item { ResultSectionTitle("Images that could not be read") }
            itemsIndexed(state.issues) { _, issue -> ScanIssueCard(issue) }
        }

        item {
            Button(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
                Text("Start another scan")
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    title: String,
    description: String,
    group: DuplicateGroup,
    featuresById: Map<String, ImageFeatures>,
) {
    val features = group.memberIds.mapNotNull(featuresById::get)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PhotoGrid(features)
            if (group.type == DuplicateType.EXACT) {
                Text(
                    "Evidence: matching SHA-256 file hashes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                ComparisonEvidence(group, featuresById)
            }
        }
    }
}

@Composable
private fun PhotoGrid(features: List<ImageFeatures>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        features.chunked(PHOTO_COLUMNS).forEach { rowFeatures ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowFeatures.forEach { feature ->
                    PhotoTile(feature, Modifier.weight(1f))
                }
                repeat(PHOTO_COLUMNS - rowFeatures.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PhotoTile(feature: ImageFeatures, modifier: Modifier = Modifier) {
    val contentResolver = LocalContext.current.contentResolver
    val thumbnailState by produceState<ThumbnailState>(
        initialValue = ThumbnailState.Loading,
        key1 = feature.uri,
    ) {
        value = withContext(Dispatchers.IO) {
            try {
                ThumbnailState.Loaded(loadThumbnail(contentResolver, feature.uri))
            } catch (_: Exception) {
                ThumbnailState.Failed
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            when (val thumbnail = thumbnailState) {
                ThumbnailState.Loading -> ThumbnailMessage("Loading…")
                ThumbnailState.Failed -> ThumbnailMessage("Preview unavailable")
                is ThumbnailState.Loaded -> Image(
                    bitmap = thumbnail.bitmap,
                    contentDescription = feature.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Text(
            text = feature.displayName,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ThumbnailMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            message,
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComparisonEvidence(
    group: DuplicateGroup,
    featuresById: Map<String, ImageFeatures>,
) {
    var expanded by rememberSaveable(group.memberIds.joinToString(separator = "|")) {
        mutableStateOf(false)
    }
    val visibleComparisons = if (expanded) {
        group.comparisons
    } else {
        group.comparisons.take(COLLAPSED_COMPARISON_COUNT)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Match evidence", style = MaterialTheme.typography.labelLarge)
        visibleComparisons.forEach { comparison ->
            ComparisonRow(comparison, group.type, featuresById)
        }
        if (group.comparisons.size > COLLAPSED_COMPARISON_COUNT) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show fewer comparisons" else "Show all comparisons")
            }
        }
    }
}

@Composable
private fun ComparisonRow(
    comparison: ImageComparison,
    type: DuplicateType,
    featuresById: Map<String, ImageFeatures>,
) {
    val leftName = featuresById[comparison.leftId]?.displayName ?: comparison.leftId
    val rightName = featuresById[comparison.rightId]?.displayName ?: comparison.rightId
    val evidence = when (type) {
        DuplicateType.MODIFIED_COPY ->
            "dHash ${comparison.hammingDistance}/64 " +
                "(passes at ≤ ${NativeEngine.DEFAULT_HAMMING_THRESHOLD}) • " +
                "MobileNet ${formatPercent(comparison.cosineSimilarity)}"
        DuplicateType.RELATED ->
            "MobileNet ${formatPercent(comparison.cosineSimilarity)} " +
                "(passes at ≥ ${formatPercent(NativeEngine.DEFAULT_SIMILARITY_THRESHOLD)}) • " +
                "dHash ${comparison.hammingDistance}/64"
        DuplicateType.EXACT -> "Matching SHA-256 file hashes"
    }

    Column {
        Text(
            "$leftName ↔ $rightName",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        Text(
            evidence,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnmatchedComparisonCard(
    comparison: ImageComparison,
    featuresById: Map<String, ImageFeatures>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ComparisonRow(comparison, DuplicateType.RELATED, featuresById)
            Text(
                "Not grouped: dHash must be ≤ ${NativeEngine.DEFAULT_HAMMING_THRESHOLD} " +
                    "or MobileNet must be ≥ " +
                    formatPercent(NativeEngine.DEFAULT_SIMILARITY_THRESHOLD),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ExposureCard(feature: ImageFeatures) {
    val warning = when (feature.exposure.classification) {
        ExposureClass.UNDEREXPOSED -> "Possibly underexposed"
        ExposureClass.OVEREXPOSED -> "Possibly overexposed"
        ExposureClass.NORMAL -> "Normal exposure"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(feature.displayName, fontWeight = FontWeight.SemiBold)
            Text(warning)
            Text(
                "Mean luminance: ${formatNumber(feature.exposure.meanLuminance)} / 255",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScanIssueCard(issue: ScanIssue) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(issue.imageName, fontWeight = FontWeight.SemiBold)
            Text(issue.message)
        }
    }
}

@Composable
private fun FailedScreen(
    message: String,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Scan failed", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(message)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
            Text("Return to start")
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ResultSectionTitle(title: String) {
    Column {
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
    }
}

@Composable
private fun EmptyResult(message: String) {
    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun formatNumber(value: Double): String = String.format(Locale.US, "%.0f", value)

private fun formatPercent(value: Float): String =
    String.format(Locale.US, "%.1f%%", value * 100f)

private fun comparisonKey(leftId: String, rightId: String): Pair<String, String> =
    if (leftId <= rightId) leftId to rightId else rightId to leftId

private fun loadThumbnail(contentResolver: ContentResolver, uri: Uri): ImageBitmap {
    val source = ImageDecoder.createSource(contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val largestSide = maxOf(info.size.width, info.size.height)
        val scale = minOf(1f, THUMBNAIL_PIXEL_SIZE.toFloat() / largestSide)
        decoder.setTargetSize(
            (info.size.width * scale).roundToInt().coerceAtLeast(1),
            (info.size.height * scale).roundToInt().coerceAtLeast(1),
        )
    }
    return bitmap.asImageBitmap()
}

private sealed interface ThumbnailState {
    data object Loading : ThumbnailState
    data object Failed : ThumbnailState
    data class Loaded(val bitmap: ImageBitmap) : ThumbnailState
}

private const val MAX_SELECTED_IMAGES = 100
private const val MAX_UNMATCHED_COMPARISONS = 10
private const val COLLAPSED_COMPARISON_COUNT = 3
private const val PHOTO_COLUMNS = 2
private const val THUMBNAIL_PIXEL_SIZE = 512
