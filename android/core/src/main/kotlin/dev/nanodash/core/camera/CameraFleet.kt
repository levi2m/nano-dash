package dev.nanodash.core.camera

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Several cameras controlled as one: record start and stop go out to all of them at the same time, each answered on its
 * own link, so one camera that is slow or gone never delays or blocks the others. Keys are whatever names the cameras
 * (an address, a label).
 */
class CameraFleet(private val links: Map<String, CameraLink>) {
    fun readyIds(): List<String> = links.filterValues { it.isReady() }.keys.toList()

    /** Starts every camera that is ready now (optionally only among [only]); the others are left out of the result. */
    suspend fun startReady(only: Collection<String>? = null): Map<String, StartResult> = coroutineScope {
        links.filter { (id, link) -> link.isReady() && (only == null || id in only) }
            .map { (id, link) -> async { id to link.startRecording() } }.awaitAll().toMap()
    }

    /** Whether each camera's clock was set by its current connection. */
    fun clockSet(id: String): Boolean = links[id]?.clockSet() == true

    /** Stops the given cameras. Each waits for its own link to come back if it dropped. */
    suspend fun stop(ids: Collection<String>): Map<String, StopResult> = coroutineScope {
        links.filterKeys { it in ids }.map { (id, link) -> async { id to link.stopRecording() } }.awaitAll().toMap()
    }

    fun close() = links.values.forEach { it.close() }
}
