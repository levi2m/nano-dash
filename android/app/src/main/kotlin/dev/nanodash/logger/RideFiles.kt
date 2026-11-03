package dev.nanodash.logger

import android.content.Context
import dev.nanodash.core.ride.RideFolder
import dev.nanodash.core.ride.RideSummary
import java.io.File

/** Where the rides live on this phone; everything else about a ride folder is in `core` (`RideFolder`). */
object RideFiles {
    fun root(context: Context): File = context.getExternalFilesDir("rides") ?: File(context.filesDir, "rides")

    fun list(context: Context): List<RideSummary> = RideFolder.list(root(context))
}
