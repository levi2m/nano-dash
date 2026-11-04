package dev.nanodash.core.fit

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A small independent reader for the bytes our writer produces, used only to check the writer's output. */
private class ParsedFit(val bytes: ByteArray) {
    class Mesg(val global: Int, val fields: Map<Int, ByteArray>, val dev: Map<Int, ByteArray>)

    private class Def(val global: Int, val fields: List<Triple<Int, Int, Int>>, val dev: List<Triple<Int, Int, Int>>)

    val headerSize = bytes[0].toInt() and 0xFF
    val protocol = bytes[1].toInt() and 0xFF
    val profile = u(bytes, 2, 2)
    val dataSize = u(bytes, 4, 4)
    val signature = String(bytes, 8, 4, Charsets.US_ASCII)
    val headerCrcOk = u(bytes, 12, 2) == FitCrc.compute(bytes, 0, 12).toLong()
    val fileCrcOk = u(bytes, headerSize + dataSize.toInt(), 2) == FitCrc.compute(bytes, 0, headerSize + dataSize.toInt()).toLong()
    val messages = ArrayList<Mesg>()

    init {
        val defs = HashMap<Int, Def>()
        var p = headerSize
        val end = headerSize + dataSize.toInt()
        while (p < end) {
            val h = bytes[p++].toInt() and 0xFF
            val local = h and 0x0F
            if (h and 0x40 != 0) {
                p++                                       // reserved
                check(bytes[p++].toInt() == 0)            // little endian
                val global = u(bytes, p, 2).toInt(); p += 2
                val n = bytes[p++].toInt() and 0xFF
                val fields = (0 until n).map { Triple(b(p + it * 3), b(p + it * 3 + 1), b(p + it * 3 + 2)) }; p += n * 3
                var dev = emptyList<Triple<Int, Int, Int>>()
                if (h and 0x20 != 0) {
                    val dn = b(p++)
                    dev = (0 until dn).map { Triple(b(p + it * 3), b(p + it * 3 + 1), b(p + it * 3 + 2)) }; p += dn * 3
                }
                defs[local] = Def(global, fields, dev)
            } else {
                val d = defs.getValue(local)
                val f = LinkedHashMap<Int, ByteArray>()
                for ((num, size, _) in d.fields) { f[num] = bytes.copyOfRange(p, p + size); p += size }
                val dv = LinkedHashMap<Int, ByteArray>()
                for ((num, size, _) in d.dev) { dv[num] = bytes.copyOfRange(p, p + size); p += size }
                messages += Mesg(d.global, f, dv)
            }
        }
        check(p == end) { "parsed $p bytes of data, header says $end" }
    }

    private fun b(i: Int) = bytes[i].toInt() and 0xFF

    fun of(global: Int) = messages.filter { it.global == global }

    companion object {
        fun u(b: ByteArray, off: Int, n: Int): Long {
            var v = 0L
            for (i in 0 until n) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
            return v
        }
        fun s32(b: ByteArray): Int = u(b, 0, 4).toInt()
        fun f32(b: ByteArray): Float = java.lang.Float.intBitsToFloat(u(b, 0, 4).toInt())
    }
}

class FitActivityWriterTest {
    private val start = 1793673900000L   // 2026-11-03T02:45:00Z

    /** A 2-minute loop ride at 1 Hz: speed ramps 0 to 14 m/s, lean follows lateral acceleration. */
    private fun syntheticRide(): List<RideSample> {
        var dist = 0f
        return (0 until 120).map { i ->
            val speed = (14f * (i / 119f)).coerceAtMost(14f)
            dist += speed
            val a = i / 20.0 * PI
            RideSample(
                utcMillis = start + i * 1000L,
                latitudeDeg = -20.2650 + 0.002 * sin(a),
                longitudeDeg = -40.2950 + 0.002 * cos(a),
                altitudeM = 3f + (i % 7) * 0.2f,
                speedMps = speed,
                distanceM = dist,
                leanAngleDeg = (18 * sin(a)).toFloat(),
                lateralAccelMps2 = (3.2 * sin(a)).toFloat(),
                longitudinalAccelMps2 = (1.1 * cos(a)).toFloat(),
            )
        }
    }

    @Test
    fun crc16MatchesTheStandardCheckValue() {
        // CRC-16/ARC of "123456789" is 0xBB3D, the published check value for this algorithm.
        assertEquals(0xBB3D, FitCrc.compute("123456789".toByteArray()))
    }

    @Test
    fun fileStructureAndChecksumsAreValid() {
        val bytes = FitActivityWriter().encode(syntheticRide(), listOf(CameraSpan(start + 10_000, start + 70_000)))
        val fit = ParsedFit(bytes)
        assertEquals(14, fit.headerSize)
        assertEquals(0x20, fit.protocol)
        assertEquals(".FIT", fit.signature)
        assertEquals(FitWriter.PROFILE_VERSION.toLong(), fit.profile)
        assertTrue(fit.headerCrcOk, "header CRC")
        assertTrue(fit.fileCrcOk, "file CRC")
        assertEquals(bytes.size, 14 + fit.dataSize.toInt() + 2)
    }

    @Test
    fun messageCountsAndValuesRoundTrip() {
        val ride = syntheticRide()
        val fit = ParsedFit(FitActivityWriter().encode(ride, listOf(CameraSpan(start + 10_000, start + 70_000))))

        assertEquals(1, fit.of(0).size)           // file_id
        assertEquals(1, fit.of(207).size)         // developer_data_id
        assertEquals(4, fit.of(206).size)         // field_description
        assertEquals(120, fit.of(20).size)        // record
        assertEquals(1, fit.of(18).size)          // session
        assertEquals(1, fit.of(34).size)          // activity

        val fid = fit.of(0).single().fields
        assertEquals(4, fid.getValue(0)[0].toInt())                                         // type: activity
        assertEquals(255L, ParsedFit.u(fid.getValue(1), 0, 2))                              // manufacturer: development
        assertEquals(FitTime.fromUtcMillis(start), ParsedFit.u(fid.getValue(4), 0, 4))      // real time, not Mimo's 2046

        val recs = fit.of(20)
        for ((i, r) in recs.withIndex()) {
            val s = ride[i]
            assertEquals(FitTime.fromUtcMillis(s.utcMillis), ParsedFit.u(r.fields.getValue(253), 0, 4))
            val lat = ParsedFit.s32(r.fields.getValue(0)) * 180.0 / 2147483648.0
            val lon = ParsedFit.s32(r.fields.getValue(1)) * 180.0 / 2147483648.0
            assertTrue(abs(lat - s.latitudeDeg) < 1e-7 && abs(lon - s.longitudeDeg) < 1e-7, "position $i")
            assertEquals(s.speedMps.toDouble(), ParsedFit.u(r.fields.getValue(6), 0, 2) / 1000.0, 0.001)
            assertEquals(s.speedMps.toDouble(), ParsedFit.u(r.fields.getValue(73), 0, 4) / 1000.0, 0.001)
            assertEquals(s.distanceM.toDouble(), ParsedFit.u(r.fields.getValue(5), 0, 4) / 100.0, 0.01)
            assertEquals(s.altitudeM.toDouble(), ParsedFit.u(r.fields.getValue(2), 0, 2) / 5.0 - 500.0, 0.2)
            assertEquals(s.altitudeM.toDouble(), ParsedFit.u(r.fields.getValue(78), 0, 4) / 5.0 - 500.0, 0.2)
            assertEquals(s.leanAngleDeg!!, ParsedFit.f32(r.dev.getValue(0)), 1e-6f)
            assertEquals(s.lateralAccelMps2!!, ParsedFit.f32(r.dev.getValue(1)), 1e-6f)
            assertEquals(s.longitudinalAccelMps2!!, ParsedFit.f32(r.dev.getValue(2)), 1e-6f)
            val inSpan = s.utcMillis in (start + 10_000)..(start + 70_000)
            assertEquals(if (inSpan) 1 else 0, r.dev.getValue(3)[0].toInt(), "camera flag at $i")
        }

        val session = fit.of(18).single().fields
        assertEquals(22, session.getValue(5)[0].toInt())                                    // sport: motorcycling
        assertEquals(FitTime.fromUtcMillis(start), ParsedFit.u(session.getValue(2), 0, 4))  // start_time
        assertEquals(119_000L, ParsedFit.u(session.getValue(7), 0, 4))                      // elapsed time, ms
        assertEquals(14000L, ParsedFit.u(session.getValue(125), 0, 4))                      // max speed 14 m/s
        assertEquals(1, ParsedFit.u(fit.of(34).single().fields.getValue(1), 0, 2).toInt())  // one session in the activity
    }

    @Test
    fun developerFieldsAreDeclaredByName() {
        val fit = ParsedFit(FitActivityWriter().encode(syntheticRide()))
        val names = fit.of(206).map { String(it.fields.getValue(3)).trimEnd('\u0000') }
        assertEquals(listOf("lean_angle", "lateral_accel", "longitudinal_accel", "camera_recording"), names)
        assertContentEquals(FitActivityWriter.APP_ID, fit.of(207).single().fields.getValue(1))
    }

    @Test
    fun missingDeveloperValuesAreWrittenAsInvalid() {
        val ride = syntheticRide().map { it.copy(leanAngleDeg = null) }
        val rec = ParsedFit(FitActivityWriter().encode(ride)).of(20).first()
        assertEquals(0xFFFF_FFFFL, ParsedFit.u(rec.dev.getValue(0), 0, 4))
    }

    @Test
    fun timeConversionIsRoundedFromTheFitEpoch() {
        assertEquals(0L, FitTime.fromUtcMillis(631_065_600_000L))
        assertEquals(1L, FitTime.fromUtcMillis(631_065_600_000L + 600))   // rounded to the nearest second, not cut
    }

    @Test
    fun writesAFileForTheExternalDecoders() {
        val dir = File(System.getProperty("fit.samples.dir") ?: "build/fit-samples").also { it.mkdirs() }
        val out = File(dir, "synthetic-ride.fit")
        out.writeBytes(FitActivityWriter().encode(syntheticRide(), listOf(CameraSpan(start + 10_000, start + 70_000))))
        assertTrue(out.length() > 1000)
    }
}
