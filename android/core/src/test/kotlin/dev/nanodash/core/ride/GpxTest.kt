package dev.nanodash.core.ride

import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class GpxTest {
    private fun fix(utc: Long, lat: Double, lon: Double, alt: Double?) = GnssFix(utc, lat, lon, alt, null, null, null)

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml.byteInputStream())

    @Test
    fun writesAWellFormedTrackWithTimesAndOptionalElevation() {
        val xml = Gpx.write("ride-1 & <x>", listOf(fix(1_793_676_577_000, -20.265, -40.295, 5.5), fix(1_793_676_578_000, -20.2651, -40.2951, null)))
        val doc = parse(xml)                                                        // fails if the XML is not well formed
        val points = doc.getElementsByTagName("trkpt")
        assertEquals(2, points.length)
        assertEquals("-20.265", points.item(0).attributes.getNamedItem("lat").nodeValue)
        assertEquals("5.5", doc.getElementsByTagName("ele").item(0).textContent)
        assertEquals(1, doc.getElementsByTagName("ele").length)                      // the second fix has no altitude
        assertEquals("2026-11-03T03:29:37Z", doc.getElementsByTagName("time").item(0).textContent)
        assertEquals("ride-1 & <x>", doc.getElementsByTagName("name").item(0).textContent)
    }
}
