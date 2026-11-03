package dev.nanodash.core.duml

/** Bus addresses used when talking to the Nano over BLE (from Osmosis' MEDIA_PROTOCOL.md, confirmed on a real Nano). */
object Addr {
    const val CAMERA = 0x01
    const val APP = 0x02
    const val WIFI = 0x07
    /** The camera's clock and system settings; the set-time command is silently dropped if sent to [CAMERA] instead (Osmosis). */
    const val CLOCK = 0x28
    const val SESSION_1C = 0x1C
    const val SESSION_F0 = 0xF0
}

/** App-to-camera frames always use command type 0x40. */
private const val APP_CMD = 0x40

/**
 * Builders for the frames the logger sends. Sequence numbers are passed in so callers (and tests) control them.
 * Command ids confirmed on a real Osmo Nano on 2026-11-02: pairing 07/45, keepalive 00/2B, wake 53/10, record 02/02.
 */
object CameraFrames {
    /** Sent once before pairing. */
    fun prePair(seq: Int) = Duml.build(Addr.APP, Addr.SESSION_F0, seq, APP_CMD, 0x00, 0x2B, byteArrayOf(0x04, 0x00))

    /** [identifier]: a 32-character id created once per install and reused, so the camera keeps recognising this phone. */
    fun pair(identifier: String, seq: Int) =
        Duml.build(Addr.APP, Addr.WIFI, seq, APP_CMD, 0x07, 0x45, Duml.packString(identifier) + Duml.packString("osmo"))

    /** The Nano drops an idle paired link after about 5 to 6 seconds: send this about once a second. */
    fun keepalive(seq: Int) = Duml.build(Addr.APP, Addr.SESSION_F0, seq, APP_CMD, 0x00, 0x2B, byteArrayOf(0x01, 0x01))

    fun wake(seq: Int) = Duml.build(Addr.APP, Addr.SESSION_1C, seq, APP_CMD, 0x53, 0x10, ByteArray(4))

    fun recordStart(seq: Int) = Duml.build(Addr.APP, Addr.CAMERA, seq, APP_CMD, 0x02, 0x02, byteArrayOf(0x01))

    fun recordStop(seq: Int) = Duml.build(Addr.APP, Addr.CAMERA, seq, APP_CMD, 0x02, 0x02, byteArrayOf(0x00))

    /**
     * Sets the camera's clock and time zone (00/6A to the clock receiver, documented by Osmosis, accepted by the Nano on
     * 2026-11-03): `01 00`, unix seconds as u64, UTC offset in minutes as i16, then the zone id with a length byte. The camera
     * takes whole seconds, so callers send it just before a second boundary of their own clock.
     */
    fun setClock(seq: Int, unixSeconds: Long, offsetMinutes: Int, zoneId: String): ByteArray {
        val zone = zoneId.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(2 + 8 + 2 + 1 + zone.size)
        payload[0] = 0x01
        for (i in 0 until 8) payload[2 + i] = (unixSeconds shr (8 * i)).toByte()
        payload[10] = offsetMinutes.toByte()
        payload[11] = (offsetMinutes shr 8).toByte()
        payload[12] = zone.size.toByte()
        zone.copyInto(payload, 13)
        return Duml.build(Addr.APP, Addr.CLOCK, seq, APP_CMD, 0x00, 0x6A, payload)
    }
}

/** What the camera's replies and pushes mean, as far as the M0 spike established. */
object CameraReplies {
    enum class Pairing { ALREADY_PAIRED, APPROVAL_REQUIRED, UNKNOWN }

    fun isPairingReply(f: DumlFrame) = f.cmdSet == 0x07 && f.cmdId == 0x45
    fun isApprovalRequest(f: DumlFrame) = f.cmdSet == 0x07 && f.cmdId == 0x46
    fun isWakeReply(f: DumlFrame) = f.cmdSet == 0x53 && f.cmdId == 0x10
    fun isRecordReply(f: DumlFrame) = f.cmdSet == 0x02 && f.cmdId == 0x02
    fun isClockReply(f: DumlFrame) = f.cmdSet == 0x00 && f.cmdId == 0x6A

    /** Observed reply `00 01 00`; a first payload byte of 0 means the clock was set. */
    fun clockAccepted(f: DumlFrame): Boolean = isClockReply(f) && f.payload.isNotEmpty() && f.payload[0].toInt() == 0

    fun pairing(f: DumlFrame): Pairing = when {
        !isPairingReply(f) || f.payload.size < 2 || f.payload[0].toInt() != 0 -> Pairing.UNKNOWN
        f.payload[1].toInt() == 1 -> Pairing.ALREADY_PAIRED
        f.payload[1].toInt() == 2 -> Pairing.APPROVAL_REQUIRED
        else -> Pairing.UNKNOWN
    }

    /** A record reply payload of `00` means accepted. `D6` was seen while USB was connected; `DF` per Osmosis means "already recording". */
    fun recordAccepted(f: DumlFrame): Boolean = isRecordReply(f) && f.payload.isNotEmpty() && f.payload[0].toInt() == 0

    /** Status push 02/80 (about 10 Hz, 60-byte payload): bit 7 of byte 0 is the recording flag. Null for other frames. */
    fun isRecording(f: DumlFrame): Boolean? =
        if (f.cmdSet == 0x02 && f.cmdId == 0x80 && f.payload.isNotEmpty()) (f.payload[0].toInt() and 0x80) != 0 else null
}
