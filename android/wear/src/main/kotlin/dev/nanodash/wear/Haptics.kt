package dev.nanodash.wear

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

/** Start, stop and error each buzz differently, so a gloved rider can tell them apart without looking. */
class Haptics(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    /** Two short pulses: the ride has started. */
    fun started() = play(VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60), -1))

    /** A light tick when the hold begins. */
    fun holdTick() = play(VibrationEffect.createOneShot(20, 120))

    /** One long pulse: the hold completed and the ride is stopping. */
    fun stopped() = play(VibrationEffect.createOneShot(400, 200))

    /** Three quick pulses: something was refused. */
    fun refused() = play(VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40, 60, 40), -1))

    private fun play(effect: VibrationEffect) { vibrator?.vibrate(effect) }
}
