package dev.nanodash.core.watch

/** How the watch writes a running time: short enough for a round screen, and a spoken form for screen readers. */
object WatchFormat {
    /** `12:45` under an hour, `1:02:45` from an hour on. */
    fun elapsed(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
    }

    /** `12 minutes 45 seconds`, `1 hour 2 minutes`; used as the screen reader description of the timer. */
    fun spoken(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        fun unit(n: Long, word: String) = "$n $word" + if (n == 1L) "" else "s"
        return when {
            h > 0 -> unit(h, "hour") + if (m > 0) " " + unit(m, "minute") else ""
            m > 0 -> unit(m, "minute") + if (sec > 0) " " + unit(sec, "second") else ""
            else -> unit(sec, "second")
        }
    }

    private fun two(n: Long) = if (n < 10) "0$n" else "$n"
}
