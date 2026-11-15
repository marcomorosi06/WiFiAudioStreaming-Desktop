object Ansi {

    val enabled: Boolean by lazy {
        when {
            System.getenv("NO_COLOR") != null -> false
            System.getenv("TERM") == "dumb" -> false
            !System.getProperty("os.name", "").lowercase().contains("win") -> true
            else -> System.getenv("WT_SESSION") != null ||
                    System.getenv("COLORTERM") != null ||
                    System.getenv("ANSICON") != null ||
                    System.getenv("ConEmuANSI") == "ON" ||
                    System.getenv("TERM_PROGRAM") != null
        }
    }

    fun code(code: String, text: String): String =
        if (enabled) "[${code}m$text[0m" else text

    fun green(t: String)  = code("32", t)
    fun red(t: String)    = code("31", t)
    fun yellow(t: String) = code("33", t)
    fun cyan(t: String)   = code("36", t)
    fun bold(t: String)   = code("1",  t)
    fun dim(t: String)    = code("2",  t)

    /**
     * A name that came from the network (a beacon, mDNS, a UPnP description),
     * made safe to print: control characters, ESC above all since it drives the
     * terminal, and bidirectional overrides, which reorder what is shown, are
     * dropped.
     */
    fun printable(text: String): String = text.filterNot { c ->
        c.isISOControl() || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' ||
            c == '\u200E' || c == '\u200F'
    }
}
