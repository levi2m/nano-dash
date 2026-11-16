package dev.nanodash.logger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/** Whether the phone can give a GPS fix right now. The screen, Start and the watch's Start all ask this one question, so they cannot disagree. */
enum class GpsReadiness {
    Ready, LocationOff, NeedsPermission;

    companion object {
        /** Needs the precise-location permission and the GPS provider switched on, which is what the ride's location request uses. */
        fun of(context: Context): GpsReadiness = when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> NeedsPermission
            !context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationOff
            else -> Ready
        }
    }
}
