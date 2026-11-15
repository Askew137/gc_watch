package com.askew137.gcwatch.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.askew137.gcwatch.mobile.maps.MapTransferState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.askew137.gcwatch.mobile.routing.SegmentRepository
import com.askew137.gcwatch.mobile.routing.TileNames
import java.io.File
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapsScreen(
    viewModel: MobileViewModel,
    onNavigateUp: () -> Unit,
) {
    val mapFiles by viewModel.mapFiles.collectAsState()
    val copyProgress by viewModel.copyProgress.collectAsState()
    val transfer by viewModel.mapTransfer.collectAsState()
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    val sending = transfer is MapTransferState.Working

    // SAF pickers
    val mapPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { viewModel.copyMapFile(it) }
    }

    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Remove ${file.name} from the phone?") },
            text = {
                Text(
                    "Only the phone's copy (${formatFileSize(file.length())}) is deleted. " +
                        "A map already sent stays on the watch. You can add the file again later.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteMapFile(file); fileToDelete = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { fileToDelete = null }) { Text("Keep") } },
        )
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Maps & Routing", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── Map files section ─────────────────────────────────────────────
            SectionHeader("Map files (.map)")

            if (mapFiles.isEmpty()) {
                EmptyHint("No .map files yet. Add one below.")
            } else {
                mapFiles.forEach { file ->
                    MapFileCard(
                        file = file,
                        sendEnabled = !sending,
                        onSendToWatch = { viewModel.sendMapToWatch(file) },
                        onRemove = { fileToDelete = file },
                    )
                }
            }

            // Progress / result of sending a map to the watch.
            TransferCard(
                state = transfer,
                phoneCopyExists = { name -> mapFiles.any { it.name == name } },
                onCancel = { viewModel.cancelMapTransfer() },
                onRetry = { name -> viewModel.retryMapTransfer(name) },
                onDismiss = { viewModel.dismissMapTransfer() },
                onRemoveFromPhone = { name -> mapFiles.firstOrNull { it.name == name }?.let { fileToDelete = it } },
            )

            // Adding a map is part of this section (a floating button used to cover the list).
            Button(
                onClick = { mapPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add map file")
            }

            // Copy progress
            copyProgress?.let { progress ->
                LinearProgressIndicator(
                    progress = {
                        if (progress.progressPercent >= 0) progress.progressPercent / 100f else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Copying ${progress.fileName}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Info card ─────────────────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Where to get map files",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ExternalLinkText(
                        label = "OpenAndroMaps.org",
                        url   = "https://www.openandromaps.org",
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ExternalLinkText(
                        label = "BBBike.org",
                        url   = "https://extract.bbbike.org",
                    )
                }
            }

            // ── Routing data ───────────────────────────────────────────────────
            RoutingDataSection(viewModel)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun MapFileCard(file: File, sendEnabled: Boolean, onSendToWatch: () -> Unit, onRemove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    formatFileSize(file.length()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (file.length() > 1_000_000L) {
                    // Over Bluetooth alone a few MB can take very long; with Wi-Fi it is quick.
                    Text(
                        "Fast when the phone and the watch are on the same Wi-Fi",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onSendToWatch, enabled = sendEnabled) {
                    Text("Send to watch")
                }
                TextButton(onClick = onRemove) {
                    Text("Remove from phone", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ExternalLinkText(label: String, url: String) {
    val context = LocalContext.current
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium.copy(
            textDecoration = TextDecoration.Underline,
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(vertical = 2.dp)
            .clickable {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url),
                )
                context.startActivity(intent)
            },
    )
}

private val sizeFormat = DecimalFormat("#.##")

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "${sizeFormat.format(bytes / 1_073_741_824.0)} GB"
    bytes >= 1_048_576L     -> "${sizeFormat.format(bytes / 1_048_576.0)} MB"
    bytes >= 1_024L         -> "${sizeFormat.format(bytes / 1_024.0)} KB"
    else                    -> "$bytes B"
}


// ── Routing data (BRouter tiles) ──────────────────────────────────────────────

@Composable
private fun RoutingDataSection(viewModel: MobileViewModel) {
    val installed by viewModel.installedSegments.collectAsState()
    val download by viewModel.segmentDownload.collectAsState()
    val downloadError by viewModel.segmentDownloadError.collectAsState()
    val missing by viewModel.missingSegment.collectAsState()
    val sizes by viewModel.segmentRemoteSizes.collectAsState()

    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    SectionHeader("Navigation data (routes)")
    Text(
        "Routes on foot are computed on the phone from BRouter data. Download the tiles for " +
            "the areas you play in. Only the phone needs them – nothing is sent to the watch.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val installedNames = installed.map { it.name.removeSuffix(".rd5") }.toSet()
    val missingTile = missing
    if (missingTile != null && missingTile !in installedNames) {
        LaunchedEffect(missingTile) { viewModel.fetchSegmentSize(missingTile) }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "The last route needs data that is not installed: $missingTile",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.downloadSegment(missingTile) }, enabled = download == null) {
                    Text("Download $missingTile" + (sizes[missingTile]?.let { " (${formatFileSize(it)})" } ?: ""))
                }
            }
        }
    }

    // Known tiles first, then any other installed ones.
    val knownNames = SegmentRepository.knownTiles.map { it.name }
    val rows = SegmentRepository.knownTiles +
        installedNames.filter { it !in knownNames }.map { SegmentRepository.Tile(it, "") }

    rows.forEach { tile ->
        val file = installed.firstOrNull { it.name == "${tile.name}.rd5" }
        val active = download?.takeIf { it.tile == tile.name }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        tile.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    if (tile.area.isNotEmpty()) {
                        Text(
                            tile.area,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        when {
                            active != null && active.total > 0 ->
                                "Downloading ${active.bytes * 100 / active.total} % (${formatFileSize(active.total)})"
                            active != null -> "Downloading…"
                            file != null -> "Installed, ${formatFileSize(file.length())}"
                            else -> sizes[tile.name]?.let { "Not installed, ${formatFileSize(it)}" } ?: "Not installed"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (active != null && active.total > 0) {
                        LinearProgressIndicator(
                            progress = { active.bytes.toFloat() / active.total },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                when {
                    active != null -> Unit
                    file != null -> OutlinedButton(onClick = { viewModel.deleteSegment(tile.name) }) { Text("Delete") }
                    else -> Button(onClick = { viewModel.downloadSegment(tile.name) }, enabled = download == null) {
                        Text("Download")
                    }
                }
            }
        }
    }

    // Any other area of the world: BRouter provides the whole planet as 5x5 degree tiles.
    OtherAreaCard(viewModel, installedNames, download != null)

    downloadError?.let {
        Text(
            "Download failed: $it",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Red,
        )
    }
}


/** Progress bar, size, speed and time left for the transfer to the watch; the result afterwards. */
@Composable
private fun TransferCard(
    state: MapTransferState,
    phoneCopyExists: (String) -> Boolean,
    onCancel: () -> Unit,
    onRetry: (String) -> Unit,
    onDismiss: () -> Unit,
    onRemoveFromPhone: (String) -> Unit,
) {
    if (state is MapTransferState.Idle) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                is MapTransferState.Failed -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (state) {
                is MapTransferState.Working -> {
                    Text("Sending ${state.fileName} to the watch", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    when (state.phase) {
                        MapTransferState.Phase.ASKING_WATCH -> {
                            Text("Asking the watch what it already has…", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        MapTransferState.Phase.SENDING -> {
                            LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                            Text(
                                "${state.percent} %  ·  ${formatFileSize(state.sentBytes)} of ${formatFileSize(state.totalBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                (if (state.bytesPerSec > 0) "${formatFileSize(state.bytesPerSec)}/s" else "measuring speed…") +
                                    (state.secondsLeft?.let { "  ·  ${formatDuration(it)} left" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                if (state.mode == "Wi-Fi") "Over Wi-Fi: the watch downloads the file from the phone. If it breaks, Retry resumes."
                                else "Over the slow Bluetooth link. " + state.note.ifBlank { "Put the phone and the watch on the same Wi-Fi to make it faster." },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        MapTransferState.Phase.CONFIRMING -> {
                            Text("Sent. Waiting for the watch to confirm the file…", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
                is MapTransferState.Done -> {
                    Text("${state.fileName} is on the watch", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Confirmed by the watch (${formatFileSize(state.totalBytes)}). You can remove the phone's copy to free space.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (phoneCopyExists(state.fileName)) {
                            Button(onClick = { onRemoveFromPhone(state.fileName) }) { Text("Remove from phone") }
                        }
                        OutlinedButton(onClick = onDismiss) { Text("OK") }
                    }
                }
                is MapTransferState.Failed -> {
                    Text("Sending ${state.fileName} failed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (phoneCopyExists(state.fileName)) {
                            Button(onClick = { onRetry(state.fileName) }) { Text("Retry (resumes)") }
                        }
                        OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
                    }
                }
                MapTransferState.Idle -> Unit
            }
        }
    }
}

private fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
}


/** Download the routing tile for any place: type coordinates ("48.2, 16.4") or a tile name ("E15_N45"). */
@Composable
private fun OtherAreaCard(viewModel: MobileViewModel, installedNames: Set<String>, busy: Boolean) {
    var input by remember { mutableStateOf("") }
    val sizes by viewModel.segmentRemoteSizes.collectAsState()
    val tile = TileNames.resolve(input)
    LaunchedEffect(tile) { if (tile != null) viewModel.fetchSegmentSize(tile) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Another area", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "Other country? Enter a place as coordinates (lat, lon, e.g. 48.2, 16.4 for Vienna) or a tile " +
                    "name (e.g. E15_N45). Every tile is a 5°×5° square named by its south-west corner.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                label = { Text("48.2, 16.4  or  E15_N45") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (input.isNotBlank() && tile == null) {
                Text("Not a valid tile or coordinates.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (tile != null) {
                val have = tile in installedNames
                Text(
                    if (have) "$tile is already installed."
                    else "Tile $tile" + (sizes[tile]?.let { ", ${formatFileSize(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!have) {
                    Button(onClick = { viewModel.downloadSegment(tile); input = "" }, enabled = !busy) { Text("Download $tile") }
                }
            }
        }
    }
}
