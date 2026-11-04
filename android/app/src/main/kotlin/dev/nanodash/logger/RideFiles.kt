package dev.nanodash.logger

import android.content.Context
import dev.nanodash.core.ride.RideFolder
import dev.nanodash.core.ride.RideSummary
import java.io.File

/** Where the rides live on this phone; everything else about a ride folder is in `core` (`RideFolder`). */
object RideFiles {
    fun root(context: Context): File = context.getExternalFilesDir("rides") ?: File(context.filesDir, "rides")

    fun list(context: Context): List<RideSummary> = RideFolder.list(root(context))

    /** Share copies (GPX, export zip) live in the cache, not beside the rides, so Delete and Android's own cleanup remove them. */
    fun exports(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    fun clearExports(context: Context) { File(context.cacheDir, "exports").deleteRecursively() }

    /** Earlier builds wrote these beside the rides, where Delete did not reach them. */
    fun removeLegacyExports(context: Context) { listOf("route.gpx", "nano-dash-rides.zip").forEach { File(root(context), it).delete() } }
}
