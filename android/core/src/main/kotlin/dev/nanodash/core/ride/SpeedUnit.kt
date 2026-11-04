package dev.nanodash.core.ride

/** The units a rider reads: miles in the few countries that sign roads in miles, kilometres everywhere else. */
enum class SpeedUnit(val speedLabel: String, val speedPerMps: Double, val distanceLabel: String, val metersPerDistanceUnit: Double) {
    Metric("km/h", 3.6, "km", 1000.0),
    Imperial("mph", 2.236936, "mi", 1609.344);

    /** The other unit, for the switch at the bottom of the screen. */
    val other: SpeedUnit get() = if (this == Metric) Imperial else Metric

    companion object {
        private val MILES = setOf("US", "GB", "LR", "MM")

        fun forCountry(country: String): SpeedUnit = if (country.uppercase() in MILES) Imperial else Metric

        /** The unit saved by [name] (what the rider chose), or null when nothing valid is saved. */
        fun saved(name: String?): SpeedUnit? = entries.firstOrNull { it.name == name }
    }
}
