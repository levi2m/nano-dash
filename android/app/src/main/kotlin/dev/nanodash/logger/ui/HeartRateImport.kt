package dev.nanodash.logger.ui

import android.content.Context
import dev.nanodash.core.ride.HealthCsv
import dev.nanodash.core.ride.RideFolder
import dev.nanodash.core.ride.RideSummary
import dev.nanodash.logger.HealthConnectReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Looks up a ride's heart rate in Health Connect, stores it next to the GPS log and rebuilds the .fit with it. */
internal object HeartRateImport {
    /** How far outside the first and last GPS fix to look, so a reading just before Start or after Stop is not missed. */
    private const val WINDOW_MARGIN_MS = 10_000L

    /** Two imports of the same ride would write the same temporary files, so they run one at a time. */
    private val lock = Mutex()

    /**
     * Returns true when readings were added. [say] receives what happened. A [manual] run (a tap on the button) also explains
     * an empty or refused result; the automatic pass stays quiet about those and tries again next time.
     */
    suspend fun run(context: Context, ride: RideSummary, manual: Boolean, say: (String) -> Unit): Boolean = lock.withLock {
        try {
            val window = withContext(Dispatchers.IO) { RideFolder.fixWindow(ride.dir, WINDOW_MARGIN_MS) }
            if (window == null) { if (manual) say("This track has no GPS fixes, so there is no time range to look up."); return@withLock false }
            val readings = HealthCsv.clean(HealthConnectReader.readHeartRate(context, window.first, window.second))
            if (readings.isEmpty()) {
                if (manual) say("No heart rate in Health Connect for this track. Did the watch workout sync to Health Connect, and was it recorded after you gave Nano Dash access?")
                return@withLock false
            }
            val rebuilt = withContext(Dispatchers.IO) { RideFolder.saveHealth(ride.dir, readings); RideFolder.rebuildFit(ride.dir) }
            if (!rebuilt) { say("The heart rate was saved but the .fit could not be rebuilt."); return@withLock false }
            say("Added ${readings.size} heart-rate readings to the .fit.")
            return@withLock true
        } catch (e: IOException) {
            if (manual) say("Could not save the heart rate. Is the phone full?")
        } catch (e: SecurityException) {
            if (manual) { say("Health Connect did not allow reading heart rate. Allow Nano Dash there."); HealthConnectReader.openSettings(context) }
        } catch (e: RuntimeException) {   // HealthConnectException is one; not named here so phones older than Android 14 never load it
            if (manual) say("Health Connect could not be read: ${e.message ?: "unknown error"}")
        }
        false
    }
}
