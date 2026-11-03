package dev.nanodash.core.camera

import dev.nanodash.core.fit.CameraSpan

/**
 * Where a clip sits in phone time, from the Bluetooth anchors. Constants come from the flash trials of 2026-11-02/03
 * (one Osmo Nano, five clips, camera facing a screen flash, USB unplugged): the first frame landed 255 ms after the
 * camera's status first reported recording (sd 28 ms), and the clip ended 651 ms before the stop command was sent (sd 11 ms).
 *
 * Confirmed without a display on 2026-11-03: in a shake test with the phone and camera held together, the phone's gyroscope
 * and the camera's orientation data aligned with the true first frame 35 ms after "flag + 255 ms" (correlation 0.97, one
 * clip), and the clip length matched the estimate to 12 ms. So the camera really ends its clip about 0.65 s before the stop
 * command is sent. Treat the window as accurate to roughly 50 ms; the constants come from one camera.
 */
object CameraTiming {
    const val FIRST_FRAME_AFTER_FLAG_MS = 255L
    const val CLIP_END_BEFORE_STOP_SENT_MS = 651L

    /**
     * The camera's own clock stamps the clip header, and Mimo matches a .fit against it. The app sets that clock over Bluetooth
     * on every connect, with the write timed to land just after a whole second of the phone's clock (SET_CLOCK_LEAD_MS before
     * the boundary). Measured on 2026-11-03 with a PC as the reference: eight test clips over three separate sets, one of them
     * after a power cycle, all agree on a lag between 357 and 361 ms (the clip header has 1 s resolution, so the starts were
     * spread across the second to pin it down). Assumes the phone's Bluetooth write latency is close to the PC's.
     */
    const val CLOCK_BEHIND_PHONE_MS = 360L
    const val SET_CLOCK_LEAD_MS = 25L

    /** What the writer adds to every phone-UTC time so the .fit lines up with the clip's own clock. */
    const val FIT_SHIFT_MS = -CLOCK_BEHIND_PHONE_MS

    /**
     * Before the app set the camera's clock, the development Nano ran about 1.7 s behind the phone (1.45 to 1.81 s over seven
     * clips). Used for ride logs written then, and for a camera whose clock could not be set.
     */
    const val LEGACY_FIT_SHIFT_MS = -1700L

    /** Estimated phone-UTC time of the clip's first frame, or null if the camera never reported recording. */
    fun firstFrameUtcMillis(start: StartResult.Started): Long? = start.recordingFlagAt?.utcMillis?.plus(FIRST_FRAME_AFTER_FLAG_MS)

    /** Estimated phone-UTC window the clip covers. */
    fun clipSpan(start: StartResult.Started, stop: StopResult.Stopped): CameraSpan? {
        val first = firstFrameUtcMillis(start) ?: return null
        val last = stop.commandSent.utcMillis - CLIP_END_BEFORE_STOP_SENT_MS
        return if (last > first) CameraSpan(first, last) else null
    }
}
