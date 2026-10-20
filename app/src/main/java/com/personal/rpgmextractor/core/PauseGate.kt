package com.personal.rpgmextractor.core

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * A cooperative pause switch. Workers call [awaitResumed] between units of work
 * (one file / one directory), so pausing never leaves a half-written file behind.
 */
class PauseGate {
    private val _paused = MutableStateFlow(false)
    val paused = _paused.asStateFlow()

    fun pause() { _paused.value = true }
    fun resume() { _paused.value = false }

    suspend fun awaitResumed() {
        if (_paused.value) _paused.first { !it }
    }
}

/**
 * Process-wide handles shared between the UI and [com.personal.rpgmextractor.service.ExtractionService].
 */
object ExtractionControl {
    val gate = PauseGate()

    /**
     * The last scan done by the UI. The service reuses it instead of walking a
     * (possibly huge) game folder a second time; it only rescans if the process
     * was killed in between or a different folder was picked.
     */
    @Volatile
    var lastScan: Pair<Uri, ScanResult>? = null
}
