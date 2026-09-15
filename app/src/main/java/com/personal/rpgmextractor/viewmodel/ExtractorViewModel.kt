package com.personal.rpgmextractor.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.rpgmextractor.core.ExtractionProgress
import com.personal.rpgmextractor.core.ExtractionProgressBus
import com.personal.rpgmextractor.core.GameScanner
import com.personal.rpgmextractor.core.ScanResult
import com.personal.rpgmextractor.service.ExtractionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UiState {
    object Idle : UiState()
    object Scanning : UiState()
    data class Scanned(val result: ScanResult) : UiState()
    data class Extracting(val done: Int, val total: Int, val current: String) : UiState()
    data class Finished(val success: Int, val total: Int) : UiState()
    data class Error(val message: String) : UiState()
}

class ExtractorViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    var gameFolderUri: Uri? = null
        private set
    var outputFolderUri: Uri? = null
        private set

    private var scanResult: ScanResult? = null

    init {
        // Mirrors whatever the background ExtractionService is doing (or already did),
        // so the UI reconnects correctly even after rotation or reopening the app.
        viewModelScope.launch {
            ExtractionProgressBus.state.collect { progress ->
                when (progress) {
                    is ExtractionProgress.Idle -> Unit
                    is ExtractionProgress.Running ->
                        _state.value = UiState.Extracting(progress.done, progress.total, progress.current)
                    is ExtractionProgress.Finished ->
                        _state.value = UiState.Finished(progress.success, progress.total)
                    is ExtractionProgress.Failed ->
                        _state.value = UiState.Error(progress.message)
                }
            }
        }
    }

    fun onGameFolderPicked(uri: Uri) {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        gameFolderUri = uri
        scan()
    }

    fun onOutputFolderPicked(uri: Uri) {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        outputFolderUri = uri
    }

    private fun scan() {
        val uri = gameFolderUri ?: return
        _state.value = UiState.Scanning
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val root = DocumentFile.fromTreeUri(ctx, uri)
                    ?: return@launch run { _state.value = UiState.Error("Could not open the selected folder") }

                val result = withContext(Dispatchers.IO) { GameScanner.scan(ctx, root) }
                scanResult = result
                _state.value = UiState.Scanned(result)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Scan failed")
            }
        }
    }

    /** Starts the background foreground-service extraction. Runs independently of this ViewModel. */
    fun startExtraction() {
        val gameUri = gameFolderUri ?: return
        val outUri = outputFolderUri
        if (outUri == null) {
            _state.value = UiState.Error("Please choose an output folder first")
            return
        }
        if (scanResult == null) return

        val ctx = getApplication<Application>()
        _state.value = UiState.Extracting(0, scanResult!!.entries.size, "Starting…")

        val intent = Intent(ctx, ExtractionService::class.java).apply {
            putExtra(ExtractionService.EXTRA_GAME_URI, gameUri)
            putExtra(ExtractionService.EXTRA_OUTPUT_URI, outUri)
        }
        ContextCompat.startForegroundService(ctx, intent)
    }

    fun reset() {
        ExtractionProgressBus.reset()
        _state.value = UiState.Idle
        gameFolderUri = null
        scanResult = null
    }
}
