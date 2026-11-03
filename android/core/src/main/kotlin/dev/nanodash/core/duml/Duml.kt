package dev.nanodash.core.duml

/**
 * DUML ("0x55") frames as spoken by the DJI Osmo Nano over BLE.
 *
 * Layout and the command ids come from KonradIT/osmosis MEDIA_PROTOCOL.md (MIT) and were checked on a real Nano in
 * the M0 spike (tools/ble-spike). All multi-byte fields are little-endian:
 *
 *   0x55 | length(10 bits) + version(6 bits, =1) | crc8 of the first 3 bytes | sender | receiver | seq(2) |
 *   cmdType | cmdSet | cmdId | payload | crc16 of everything before it
 */
class DumlException(message: String) : Exception(message)

class DumlFrame(
    val sender: Int,
    val receiver: Int,
    val seq: Int,
    val cmdType: Int,
    val cmdSet: Int,
    val cmdId: Int,
    val payload: ByteArray,
) {
    override fun toString(): String =
        "DUML %02X->%02X type=%02X %02X/%02X seq=%d payload=%s".format(
            sender, receiver, cmdType, cmdSet, cmdId, seq, payload.joinToString(" ") { "%02x".format(it) },
        )
}

internal object Crc {
    private fun table(reflectedPoly: Int, width: Int): IntArray = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor reflectedPoly else c ushr 1 }
        c and ((1 shl width) - 1)
    }

    private val t8 = table(0x8C, 8)        // polynomial 0x31, reflected
    private val t16 = table(0x8408, 16)    // polynomial 0x1021, reflected

    fun crc8(data: ByteArray, from: Int, to: Int): Int {
        var c = 0x77
        for (i in from until to) c = t8[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        return c and 0xFF
    }

    fun crc16(data: ByteArray, from: Int, to: Int): Int {
        var c = 0x3692
        for (i in from until to) c = t16[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        return c and 0xFFFF
    }
}

object Duml {
    const val SOF = 0x55
    private const val OVERHEAD = 13          // 11 header bytes + 2 crc16
    private const val MAX_LENGTH = 1023

    fun build(
        sender: Int,
        receiver: Int,
        seq: Int,
        cmdType: Int,
        cmdSet: Int,
        cmdId: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val length = OVERHEAD + payload.size
        require(length <= MAX_LENGTH) { "payload too long: ${payload.size}" }
        val out = ByteArray(length)
        out[0] = SOF.toByte()
        out[1] = (length and 0xFF).toByte()
        out[2] = (((length shr 8) and 0x03) or (1 shl 2)).toByte()
        out[3] = Crc.crc8(out, 0, 3).toByte()
        out[4] = sender.toByte()
        out[5] = receiver.toByte()
        out[6] = (seq and 0xFF).toByte()
        out[7] = ((seq shr 8) and 0xFF).toByte()
        out[8] = cmdType.toByte()
        out[9] = cmdSet.toByte()
        out[10] = cmdId.toByte()
        payload.copyInto(out, 11)
        val crc = Crc.crc16(out, 0, length - 2)
        out[length - 2] = (crc and 0xFF).toByte()
        out[length - 1] = ((crc shr 8) and 0xFF).toByte()
        return out
    }

    /** Parses exactly one complete frame; throws [DumlException] if it is malformed or fails a checksum. */
    fun parse(b: ByteArray): DumlFrame {
        if (b.size < OVERHEAD || (b[0].toInt() and 0xFF) != SOF) throw DumlException("not a DUML frame")
        val length = (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0x03) shl 8)
        if (length != b.size) throw DumlException("length field $length does not match ${b.size} bytes")
        if ((b[3].toInt() and 0xFF) != Crc.crc8(b, 0, 3)) throw DumlException("crc8 mismatch")
        val crc = (b[length - 2].toInt() and 0xFF) or ((b[length - 1].toInt() and 0xFF) shl 8)
        if (crc != Crc.crc16(b, 0, length - 2)) throw DumlException("crc16 mismatch")
        return DumlFrame(
            sender = b[4].toInt() and 0xFF,
            receiver = b[5].toInt() and 0xFF,
            seq = (b[6].toInt() and 0xFF) or ((b[7].toInt() and 0xFF) shl 8),
            cmdType = b[8].toInt() and 0xFF,
            cmdSet = b[9].toInt() and 0xFF,
            cmdId = b[10].toInt() and 0xFF,
            payload = b.copyOfRange(11, length - 2),
        )
    }

    /** `[len:u8][utf8]`, as used by the pairing command. */
    fun packString(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 255) { "string too long" }
        return byteArrayOf(bytes.size.toByte()) + bytes
    }
}

/**
 * Notifications can split one frame across several packets or merge several frames into one. Feed raw notification
 * bytes in; complete raw frames come out. Bytes that do not start a frame are dropped.
 */
class DumlReassembler {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<ByteArray> {
        buf += chunk
        val out = mutableListOf<ByteArray>()
        while (true) {
            val start = buf.indexOfFirst { (it.toInt() and 0xFF) == Duml.SOF }
            if (start < 0) { buf = ByteArray(0); break }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 4) break
            // A stray 0x55 byte must not make us wait for a bogus frame length: check the header checksum first and resync.
            if ((buf[3].toInt() and 0xFF) != Crc.crc8(buf, 0, 3)) { buf = buf.copyOfRange(1, buf.size); continue }
            val length = (buf[1].toInt() and 0xFF) or ((buf[2].toInt() and 0x03) shl 8)
            if (length < 13 || length > 1023) { buf = buf.copyOfRange(1, buf.size); continue }
            if (buf.size < length) break
            out += buf.copyOfRange(0, length)
            buf = buf.copyOfRange(length, buf.size)
        }
        return out
    }
}
