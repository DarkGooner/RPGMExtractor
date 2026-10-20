package com.personal.rpgmextractor.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.rpgmextractor.core.ExtractionControl
import com.personal.rpgmextractor.core.ExtractionProgress
import com.personal.rpgmextractor.core.ExtractionProgressBus
import com.personal.rpgmextractor.core.GameScanner
import com.personal.rpgmextractor.core.PauseGate
import com.personal.rpgmextractor.core.ScanResult
import com.personal.rpgmextractor.service.ExtractionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed class UiState {
    object Idle : UiState()
    data class Scanning(val found: Int, val paused: Boolean) : UiState()
    data class Scanned(val result: ScanResult) : UiState()
    data class Extracting(val done: Int, val total: Int, val current: String, val paused: Boolean) : UiState()
    data class Finished(val success: Int, val total: Int) : UiState()
    data class Stopped(val success: Int, val done: Int, val total: Int) : UiState()
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
    private var scanJob: Job? = null
    private val scanGate = PauseGate()

    init {
        // Mirrors whatever the background ExtractionService is doing (or already did),
        // so the UI reconnects correctly even after rotation or reopening the app.
        viewModelScope.launch {
            combine(ExtractionProgressBus.state, ExtractionControl.gate.paused) { progress, paused ->
                progress to paused
            }.collect { (progress, paused) ->
                when (progress) {
                    is ExtractionProgress.Idle -> Unit
                    is ExtractionProgress.Running ->
                        _state.value = UiState.Extracting(progress.done, progress.total, progress.current, paused)
                    is ExtractionProgress.Finished ->
                        _state.value = UiState.Finished(progress.success, progress.total)
                    is ExtractionProgress.Stopped ->
                        _state.value = UiState.Stopped(progress.success, progress.done, progress.total)
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
        scanJob?.cancel()
        scanGate.resume()
        scanResult = null
        _state.value = UiState.Scanning(0, paused = false)
        scanJob = viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val result = GameScanner.scan(ctx, uri, scanGate) { found ->
                    if (_state.value is UiState.Scanning) {
                        _state.value = UiState.Scanning(found, scanGate.paused.value)
                    }
                }
                scanResult = result
                ExtractionControl.lastScan = uri to result
                _state.value = UiState.Scanned(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Scan failed")
            }
        }
    }

    fun pauseScan() {
        scanGate.pause()
        (_state.value as? UiState.Scanning)?.let { _state.value = it.copy(paused = true) }
    }

    fun resumeScan() {
        scanGate.resume()
        (_state.value as? UiState.Scanning)?.let { _state.value = it.copy(paused = false) }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanGate.resume()
        gameFolderUri = null
        _state.value = UiState.Idle
    }

    /** Starts the background foreground-service extraction. Runs independently of this ViewModel. */
    fun startExtraction() {
        val gameUri = gameFolderUri ?: return
        val outUri = outputFolderUri
        if (outUri == null) {
            _state.value = UiState.Error("Please choose an output folder first")
            return
        }
        val result = scanResult ?: return

        val ctx = getApplication<Application>()
        _state.value = UiState.Extracting(0, result.entries.size, "Starting…", paused = false)

        val intent = Intent(ctx, ExtractionService::class.java).apply {
            putExtra(ExtractionService.EXTRA_GAME_URI, gameUri)
            putExtra(ExtractionService.EXTRA_OUTPUT_URI, outUri)
        }
        ContextCompat.startForegroundService(ctx, intent)
    }

    fun pauseExtraction() = ExtractionControl.gate.pause()

    fun resumeExtraction() = ExtractionControl.gate.resume()

    fun stopExtraction() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, ExtractionService::class.java).setAction(ExtractionService.ACTION_STOP))
    }

    /** True when a scan is still held, so a stopped extraction can be continued. */
    fun canContinue(): Boolean = scanResult != null && gameFolderUri != null

    fun reset() {
        scanJob?.cancel()
        ExtractionProgressBus.reset()
        _state.value = UiState.Idle
        gameFolderUri = null
        scanResult = null
    }
}
