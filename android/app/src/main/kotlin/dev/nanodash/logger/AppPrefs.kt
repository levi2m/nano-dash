package dev.nanodash.logger

import android.content.Context
import androidx.core.content.edit
import dev.nanodash.core.ride.SpeedUnit
import java.security.SecureRandom

/** Everything the app remembers between launches, in one SharedPreferences file with one owner. */
object AppPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("nanodash", Context.MODE_PRIVATE)

    /** What every camera recognises as "this phone": 32 hex characters, created once. */
    fun controllerId(context: Context): String {
        val p = prefs(context)
        p.getString("controller_id", null)?.let { return it }
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        p.edit { putString("controller_id", id) }
        return id
    }

    /** The cameras chosen before; reads the older single-camera key too. */
    fun cameraAddresses(context: Context): Set<String> {
        val p = prefs(context)
        return p.getStringSet("camera_addresses", null) ?: setOfNotNull(p.getString("camera_address", null))
    }

    fun setCameraAddresses(context: Context, addresses: Set<String>) = prefs(context).edit { putStringSet("camera_addresses", addresses) }

    fun speedUnit(context: Context): SpeedUnit? = SpeedUnit.saved(prefs(context).getString("speed_unit", null))

    fun setSpeedUnit(context: Context, unit: SpeedUnit) = prefs(context).edit { putString("speed_unit", unit.name) }

    /** Off by default. On, the service stays running between rides (one quiet notification) so the watch can reach it. */
    fun quickStart(context: Context): Boolean = prefs(context).getBoolean("quick_start", false)

    fun setQuickStart(context: Context, on: Boolean) = prefs(context).edit { putBoolean("quick_start", on) }

    /** The paired device the rider chose as their watch; the phone serves no other device. Null until one is chosen. */
    fun watchAddress(context: Context): String? = prefs(context).getString("watch_address", null)

    fun watchName(context: Context): String? = prefs(context).getString("watch_name", null)

    fun setWatch(context: Context, address: String, name: String) =
        prefs(context).edit { putString("watch_address", address).putString("watch_name", name) }

    /** When the watch app last completed a handshake with this phone, in ms since 1970; 0 if never. */
    fun watchSeen(context: Context): Long = prefs(context).getLong("watch_seen", 0L)

    fun markWatchSeen(context: Context) = prefs(context).edit { putLong("watch_seen", System.currentTimeMillis()) }
}
