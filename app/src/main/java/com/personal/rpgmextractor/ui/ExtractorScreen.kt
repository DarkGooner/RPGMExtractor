package com.personal.rpgmextractor.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.rpgmextractor.viewmodel.ExtractorViewModel
import com.personal.rpgmextractor.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractorScreen(viewModel: ExtractorViewModel) {
    val state by viewModel.state.collectAsState()

    val gameFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { viewModel.onGameFolderPicked(it) } }

    val outputFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { viewModel.onOutputFolderPicked(it) } }

    // Android 13+ requires runtime permission to post the progress notification.
    // Extraction still runs in the background either way; this just controls whether
    // the user sees a progress notification for it.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.startExtraction() }

    fun beginExtraction() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startExtraction()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("RPGM Asset Extractor", fontWeight = FontWeight.Bold) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Select game folder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Pick the root folder of the RPG Maker game (the one containing www, or img/audio/movies directly).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { gameFolderLauncher.launch(null) }) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose Game Folder")
                    }
                }
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2. Select output folder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Decrypted assets will be written here, keeping the original folder layout.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { outputFolderLauncher.launch(null) }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose Output Folder")
                    }
                }
            }

            when (val s = state) {
                is UiState.Idle -> {
                    Text("Select a game folder to begin scanning.", style = MaterialTheme.typography.bodyMedium)
                }

                is UiState.Scanning -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Scanning game files…")
                }

                is UiState.Scanned -> {
                    SummaryCard(
                        images = s.result.imageCount,
                        audio = s.result.audioCount,
                        video = s.result.videoCount,
                        keyFound = s.result.encryptionKeyHex != null
                    )
                    Button(
                        onClick = { beginExtraction() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = s.result.entries.isNotEmpty()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Extraction")
                    }
                }

                is UiState.Extracting -> {
                    val progress = if (s.total > 0) s.done / s.total.toFloat() else 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("Extracting ${s.done}/${s.total}")
                    Text(s.current, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Text(
                        "Running in the background — check the notification shade for progress if you leave the app.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                is UiState.Finished -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Extraction complete: ${s.success}/${s.total} assets extracted.")
                    }
                    OutlinedButton(onClick = { viewModel.reset() }) { Text("Start Over") }
                }

                is UiState.Error -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = { viewModel.reset() }) { Text("Try Again") }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(images: Int, audio: Int, video: Int, keyFound: Boolean) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Scan results", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatChip(Icons.Default.Image, "Images", images)
                StatChip(Icons.Default.Audiotrack, "Audio", audio)
                StatChip(Icons.Default.Movie, "Video", video)
            }
            if (!keyFound) {
                Text(
                    "No encryption key found in System.json — only already-unencrypted assets can be copied.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StatChip(icon: ImageVector, label: String, count: Int) {
    AssistChip(
        onClick = {},
        label = { Text("$label: $count") },
        leadingIcon = { Icon(icon, contentDescription = null) }
    )
}
