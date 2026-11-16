package dev.nanodash.core.palette

/**
 * The dark colours the phone app, the watch screen and the watch tile share, as ARGB, so they change in one place. See docs/WEAR_UI.md.
 * The phone's light theme and its page and card greys are the app's own.
 */
object Palette {
    /** The phone's page and card surfaces; the watch sits on [BLACK] instead. */
    const val BG = 0xFF141415L
    const val CARD = 0xFF19191BL
    const val RAISED = 0xFF232325L
    const val HAIRLINE = 0x14EDEDEDL
    const val BLACK = 0xFF000000L
    const val TEXT = 0xFFEDEDEDL
    const val TEXT_SECONDARY = 0xFFA1A1A6L
    const val TEXT_MUTED = 0xFF85858BL
    const val ACCENT = 0xFFFF7A1AL

    /** The label on the accent button. */
    const val ON_ACCENT_PHONE = BG
    const val ON_ACCENT_WATCH = BLACK
    const val DANGER = 0xFFC62E33L
    const val ON_DANGER = 0xFFFFFFFFL
    const val OK = 0xFF4CC38AL
    const val WARN = 0xFFE3B341L
    const val BAD = 0xFFF2575CL
}
