package dev.nanodash.core.fit

import java.io.ByteArrayOutputStream

/*
 * A small FIT file writer, written from the public description of the file format: a 14-byte header, a stream of
 * definition messages (which declare a message's fields) and data messages (which carry the values), and a CRC-16
 * trailer. Little-endian throughout. Message and field numbers come from the FIT global profile and are set by the caller.
 */

// FIT base type ids
internal const val BT_ENUM = 0x00
internal const val BT_UINT8 = 0x02
internal const val BT_SINT16 = 0x83
internal const val BT_UINT16 = 0x84
internal const val BT_SINT32 = 0x85
internal const val BT_UINT32 = 0x86
internal const val BT_STRING = 0x07
internal const val BT_FLOAT32 = 0x88
internal const val BT_BYTE = 0x0D

/** One field of a message: its number in the global profile, its size in bytes and its base type. */
internal data class Field(val number: Int, val size: Int, val baseType: Int)

/** A developer-defined field attached to a message. */
internal data class DevField(val number: Int, val size: Int, val devDataIndex: Int)

/** FIT timestamps count seconds from 1989-12-31 00:00:00 UTC. */
object FitTime {
    private const val EPOCH_OFFSET_S = 631_065_600L

    /** FIT times are whole seconds. Rounded, not cut: cutting labels the sample half a second early on average. */
    fun fromUtcMillis(utcMillis: Long): Long = (Math.floorDiv(utcMillis + 500, 1000L) - EPOCH_OFFSET_S).coerceAtLeast(0)
}

/** CRC-16 used by FIT (the reflected 0xA001 polynomial, initial value 0). */
internal object FitCrc {
    private val table = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
        c
    }

    fun compute(data: ByteArray, from: Int = 0, to: Int = data.size, start: Int = 0): Int {
        var crc = start
        for (i in from until to) crc = (crc ushr 8) xor table[(crc xor data[i].toInt()) and 0xFF]
        return crc and 0xFFFF
    }
}

/** Collects the bytes of one data message. */
internal class Values {
    private val out = ByteArrayOutputStream(64)

    fun u8(v: Int) = u8(v.toLong())
    fun u8(v: Long) { out.write(v.toInt() and 0xFF) }
    fun u16(v: Int) = u16(v.toLong())
    fun u16(v: Long) { out.write((v and 0xFF).toInt()); out.write(((v shr 8) and 0xFF).toInt()) }
    fun u32(v: Int) = u32(v.toLong())
    fun u32(v: Long) { for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
    fun s16(v: Int) = u16(v.toLong() and 0xFFFF)
    fun s32(v: Long) = u32(v and 0xFFFF_FFFFL)

    /** A 32-bit float; null is written as the FIT "invalid" value (all ones). */
    fun f32(v: Float?) = u32(if (v == null) 0xFFFF_FFFFL else java.lang.Float.floatToIntBits(v).toLong() and 0xFFFF_FFFFL)

    fun bytes(b: ByteArray, size: Int) {
        for (i in 0 until size) out.write(if (i < b.size) b[i].toInt() else 0)
    }

    /** A zero-terminated string padded with zeros to [size] bytes. */
    fun string(s: String, size: Int) {
        val b = s.toByteArray(Charsets.UTF_8)
        for (i in 0 until size) out.write(if (i < size - 1 && i < b.size) b[i].toInt() else 0)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal class FitWriter {
    private val body = ByteArrayOutputStream()
    private val sizes = HashMap<Int, Int>()          // local message type -> expected data size

    fun define(localType: Int, globalMessage: Int, fields: List<Field>, devFields: List<DevField> = emptyList()) {
        require(localType in 0..15)
        body.write(0x40 or localType or (if (devFields.isNotEmpty()) 0x20 else 0))
        body.write(0)                                  // reserved
        body.write(0)                                  // architecture: little endian
        body.write(globalMessage and 0xFF); body.write((globalMessage shr 8) and 0xFF)
        body.write(fields.size)
        for (f in fields) { body.write(f.number); body.write(f.size); body.write(f.baseType) }
        if (devFields.isNotEmpty()) {
            body.write(devFields.size)
            for (d in devFields) { body.write(d.number); body.write(d.size); body.write(d.devDataIndex) }
        }
        sizes[localType] = fields.sumOf { it.size } + devFields.sumOf { it.size }
    }

    fun data(localType: Int, block: Values.() -> Unit) {
        val v = Values().apply(block).toByteArray()
        check(v.size == sizes[localType]) { "local type $localType: wrote ${v.size} bytes, its definition says ${sizes[localType]}" }
        body.write(localType)
        body.write(v)
    }

    fun finish(): ByteArray {
        val data = body.toByteArray()
        val header = ByteArray(14)
        header[0] = 14
        header[1] = 0x20                                // protocol version 2.0
        header[2] = (PROFILE_VERSION and 0xFF).toByte(); header[3] = ((PROFILE_VERSION shr 8) and 0xFF).toByte()
        for (i in 0 until 4) header[4 + i] = ((data.size shr (8 * i)) and 0xFF).toByte()
        header[8] = '.'.code.toByte(); header[9] = 'F'.code.toByte(); header[10] = 'I'.code.toByte(); header[11] = 'T'.code.toByte()
        val hcrc = FitCrc.compute(header, 0, 12)
        header[12] = (hcrc and 0xFF).toByte(); header[13] = ((hcrc shr 8) and 0xFF).toByte()

        val file = ByteArray(14 + data.size + 2)
        header.copyInto(file, 0)
        data.copyInto(file, 14)
        val crc = FitCrc.compute(file, 0, 14 + data.size)
        file[14 + data.size] = (crc and 0xFF).toByte(); file[15 + data.size] = ((crc shr 8) and 0xFF).toByte()
        return file
    }

    companion object {
        /** Profile version 21.126, the same value Mimo's own export carries. */
        const val PROFILE_VERSION = 21126
    }
}
