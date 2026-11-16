package dev.nanodash.logger

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.HeartRateRecord
import android.os.Build
import android.os.OutcomeReceiver
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import androidx.core.content.ContextCompat
import dev.nanodash.core.ride.HeartRateReading
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads heart rate from Health Connect through the platform API that Android 14 carries (no library, no network). Read only: the app
 * asks for READ_HEART_RATE and nothing else. Health Connect arrived as an extension of Android 14 (extension level 7), so every use
 * is behind [available]. Untested on a real device until the maintainer has tried it.
 */
object HealthConnectReader {
    /** The permission's name as Health Connect defines it (a literal, because the constant needs a newer SDK extension than minSdk). */
    const val PERMISSION = "android.permission.health.READ_HEART_RATE"

    private const val SERVICE = "healthconnect"   // Context.HEALTHCONNECT_SERVICE

    fun available(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= EXTENSION &&
            context.getSystemService(SERVICE) != null

    fun granted(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Every heart-rate sample whose time is between [fromUtcMs] and [toUtcMs], from any app that wrote to Health Connect, oldest first.
     * Empty when Health Connect is not there. Throws a HealthConnectException (a RuntimeException) if it refuses, and a
     * SecurityException if the permission was taken back.
     */
    suspend fun readHeartRate(context: Context, fromUtcMs: Long, toUtcMs: Long): List<HeartRateReading> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) < EXTENSION) return emptyList()
        return Platform.read(context, fromUtcMs, toUtcMs)
    }

    private const val EXTENSION = 7

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @RequiresExtension(extension = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, version = EXTENSION)
    private object Platform {
        /**
         * Health Connect may match a heart-rate record by when it starts, and a watch can write one long record for a whole workout, so a
         * ride that begins in the middle of one would find nothing. The query starts earlier; only samples inside the ride are kept.
         * One hour is a guess, and how Health Connect matches records is unchecked (see docs/HEALTH.md).
         */
        private const val LOOKBACK_MS = 3_600_000L
        private const val PAGE_SIZE = 1000
        private const val MAX_PAGES = 50
        private const val END_OF_PAGES = -1L

        suspend fun read(context: Context, fromUtcMs: Long, toUtcMs: Long): List<HeartRateReading> {
            val manager = context.getSystemService(HealthConnectManager::class.java) ?: return emptyList()
            val filter = TimeInstantRangeFilter.Builder().setStartTime(Instant.ofEpochMilli(fromUtcMs - LOOKBACK_MS)).setEndTime(Instant.ofEpochMilli(toUtcMs)).build()
            val out = ArrayList<HeartRateReading>()
            var token = 0L
            repeat(MAX_PAGES) {
                val request = ReadRecordsRequestUsingFilters.Builder(HeartRateRecord::class.java)
                    .setTimeRangeFilter(filter).setAscending(true).setPageSize(PAGE_SIZE).setPageToken(token).build()
                val response = readPage(context, manager, request)
                for (record in response.records) for (s in record.samples) {
                    val t = s.time.toEpochMilli()
                    if (t in fromUtcMs..toUtcMs) out += HeartRateReading(t, s.beatsPerMinute.toInt())
                }
                if (response.records.isEmpty() || response.nextPageToken == END_OF_PAGES) return out.sortedBy { it.utcMillis }
                token = response.nextPageToken
            }
            return out.sortedBy { it.utcMillis }
        }

        private suspend fun readPage(context: Context, manager: HealthConnectManager, request: ReadRecordsRequestUsingFilters<HeartRateRecord>): ReadRecordsResponse<HeartRateRecord> =
            suspendCancellableCoroutine { cont ->
                manager.readRecords(request, ContextCompat.getMainExecutor(context), object : OutcomeReceiver<ReadRecordsResponse<HeartRateRecord>, HealthConnectException> {
                    override fun onResult(result: ReadRecordsResponse<HeartRateRecord>) = cont.resume(result)
                    override fun onError(error: HealthConnectException) = cont.resumeWithException(error)
                })
            }
    }
}
