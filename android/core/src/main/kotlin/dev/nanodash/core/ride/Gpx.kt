package dev.nanodash.core.ride

import java.time.Instant

/** A ride's track as a GPX 1.1 file, which map apps such as OsmAnd and Organic Maps open offline. */
object Gpx {
    fun write(name: String, fixes: List<GnssFix>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Nano Dash" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("<trk><name>").append(escape(name)).append("</name><trkseg>\n")
        for (f in fixes) {
            append("""<trkpt lat="${f.latitudeDeg}" lon="${f.longitudeDeg}">""")
            f.altitudeM?.let { append("<ele>").append(it).append("</ele>") }
            append("<time>").append(Instant.ofEpochMilli(f.utcMillis)).append("</time></trkpt>\n")
        }
        append("</trkseg></trk>\n</gpx>\n")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
