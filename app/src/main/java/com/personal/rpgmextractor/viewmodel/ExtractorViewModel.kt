package com.personal.rpgmextractor.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.rpgmextractor.core.ExtractionEngine
import com.personal.rpgmextractor.core.GameScanner
import com.personal.rpgmextractor.core.RpgMakerDecryptor
import com.personal.rpgmextractor.core.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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

    fun startExtraction() {
        val result = scanResult ?: return
        val outUri = outputFolderUri
        if (outUri == null) {
            _state.value = UiState.Error("Please choose an output folder first")
            return
        }
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val outRoot = DocumentFile.fromTreeUri(ctx, outUri)
                    ?: return@launch run { _state.value = UiState.Error("Could not open the output folder") }

                val keyBytes = result.encryptionKeyHex?.let { RpgMakerDecryptor.hexKeyToBytes(it) }
                _state.value = UiState.Extracting(0, result.entries.size, "")

                val success = ExtractionEngine.extract(
                    context = ctx,
                    entries = result.entries,
                    keyBytes = keyBytes,
                    outputRoot = outRoot
                ) { done, total, current ->
                    _state.update { UiState.Extracting(done, total, current) }
                }
                _state.value = UiState.Finished(success, result.entries.size)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Extraction failed")
            }
        }
    }

    fun reset() {
        _state.value = UiState.Idle
        gameFolderUri = null
        scanResult = null
    }
}
