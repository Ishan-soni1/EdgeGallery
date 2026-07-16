package com.edgegallery.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgegallery.app.EdgeGalleryViewModel
import com.edgegallery.app.model.DuplicateGroup
import com.edgegallery.app.model.DuplicateType
import com.edgegallery.app.model.ExposureClass
import com.edgegallery.app.model.ImageFeatures
import com.edgegallery.app.model.ScanIssue
import com.edgegallery.app.model.ScanUiState
import java.util.Locale

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

    // Android owns the picker UI and grants access only to these selected images.
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
            text = "Find exact copies, visually similar photos, and simple exposure warnings.",
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
            text = "MVP limits: results are not cached, no keeper is recommended, and nothing is deleted.",
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
    val similarGroups = state.groups.filter { it.type == DuplicateType.VISUALLY_SIMILAR }
    val exposureWarnings = state.features.filter {
        it.exposure.classification != ExposureClass.NORMAL
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
                    "${similarGroups.size} similar groups • ${exposureWarnings.size} exposure warnings",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item { ResultSectionTitle("Exact duplicates") }
        if (exactGroups.isEmpty()) {
            item { EmptyResult("No exact duplicates found") }
        } else {
            itemsIndexed(exactGroups) { index, group ->
                DuplicateGroupCard("Exact group ${index + 1}", group, featuresById)
            }
        }

        item { ResultSectionTitle("Visually similar") }
        if (similarGroups.isEmpty()) {
            item { EmptyResult("No visually similar groups found") }
        } else {
            itemsIndexed(similarGroups) { index, group ->
                DuplicateGroupCard("Similar group ${index + 1}", group, featuresById)
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
    group: DuplicateGroup,
    featuresById: Map<String, ImageFeatures>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            group.memberIds.forEach { id ->
                Text("• ${featuresById[id]?.displayName ?: id}")
            }
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

private const val MAX_SELECTED_IMAGES = 100
