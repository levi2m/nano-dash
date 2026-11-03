package dev.nanodash.core.tools

import dev.nanodash.core.ride.RideRecovery
import java.io.File
import kotlin.system.exitProcess

/**
 * Command line: rebuild a ride's .fit from its gnss.csv (and events.jsonl next to it, if present).
 *
 *   gradle :core:rebuildFit -Pride=/path/to/ride-folder-or-gnss.csv -Pout=/path/to/output.fit
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: RebuildFit <ride folder or gnss.csv> <output.fit> [shift milliseconds, default: the camera clock lag]")
        exitProcess(2)
    }
    val input = File(args[0])
    val gnss = if (input.isDirectory) File(input, "gnss.csv") else input
    val events = File(gnss.parentFile, "events.jsonl").takeIf { it.exists() }?.readText()
    val fit = args.getOrNull(2)?.toLongOrNull()?.let { RideRecovery.rebuildFit(gnss.readText(), events, it) }
        ?: RideRecovery.rebuildFit(gnss.readText(), events)
    if (fit == null) {
        System.err.println("No GPS fixes in ${gnss.path}: nothing to write.")
        exitProcess(1)
    }
    File(args[1]).writeBytes(fit)
    println("Wrote ${args[1]} (${fit.size} bytes) from ${RideRecovery.parseGnssCsv(gnss.readText()).size} GPS fixes" +
        (if (events != null) ", camera events found" else ""))
}
