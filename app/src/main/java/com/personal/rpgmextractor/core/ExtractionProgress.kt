package com.personal.rpgmextractor.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide progress state, updated by [com.personal.rpgmextractor.service.ExtractionService]
 * and observed by the UI (via the ViewModel). Using a simple shared bus instead of a bound
 * service keeps the background job decoupled from the Activity/ViewModel lifecycle: extraction
 * keeps running (and the notification keeps updating) even if the screen is closed, and the UI
 * reconnects to whatever is currently happening whenever it's around to observe it.
 */
sealed class ExtractionProgress {
    object Idle : ExtractionProgress()
    data class Running(val done: Int, val total: Int, val current: String) : ExtractionProgress()
    data class Finished(val success: Int, val total: Int) : ExtractionProgress()
    data class Failed(val message: String) : ExtractionProgress()
}

object ExtractionProgressBus {
    private val _state = MutableStateFlow<ExtractionProgress>(ExtractionProgress.Idle)
    val state = _state.asStateFlow()

    fun update(progress: ExtractionProgress) {
        _state.value = progress
    }

    fun reset() {
        _state.value = ExtractionProgress.Idle
    }
}
