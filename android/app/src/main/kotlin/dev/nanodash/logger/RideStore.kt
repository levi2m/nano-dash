package dev.nanodash.logger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** What the screen shows. Written by [LoggerService], read by the UI. */
data class RideUiState(
    val recording: Boolean = false,
    /** Stopped, and the files are still being written: a new ride cannot start yet. */
    val saving: Boolean = false,
    val startedUtcMillis: Long = 0,
    val fixes: Int = 0,
    val lastSpeedMps: Float? = null,
    val lastAccuracyM: Float? = null,
    val satellitesUsed: Int? = null,
    val satellitesVisible: Int? = null,
    val imuSamples: Long = 0,
    val message: String? = null,
    val cameraNote: String? = null,
)

object RideStore {
    val state = MutableStateFlow(RideUiState())

    fun update(f: (RideUiState) -> RideUiState) = state.update(f)
}
