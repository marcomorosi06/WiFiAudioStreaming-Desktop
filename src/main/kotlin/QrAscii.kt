/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 */

object TerminalSize {

    private var cached: Pair<Int, Int>? = null

    fun get(): Pair<Int, Int> {
        cached?.let { return it }
        val v = probe()
        cached = v
        return v
    }

    fun invalidate() { cached = null }

    val columns: Int get() = get().first
    val rows: Int get() = get().second

    private fun probe(): Pair<Int, Int> {
        val ec = System.getenv("COLUMNS")?.toIntOrNull()
        val er = System.getenv("LINES")?.toIntOrNull()
        if (ec != null && er != null && ec in 10..500 && er in 5..300) return ec to er

        runCatching {
            val isWin = System.getProperty("os.name", "").lowercase().contains("win")
            if (isWin) {
                val tmp = java.io.File(System.getProperty("java.io.tmpdir"), "wfas_termsize.txt")
                runCatching { tmp.delete() }
                val cmd = "[Console]::WindowWidth.ToString()+' '+[Console]::WindowHeight.ToString() | " +
                    "Set-Content -LiteralPath '" + tmp.absolutePath + "' -Encoding ascii"
                val pb = ProcessBuilder("powershell", "-NoProfile", "-Command", cmd)
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
                pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                pb.start().waitFor()
                if (tmp.exists()) {
                    val parts = tmp.readText().trim().split(Regex("\\s+"))
                    val w = parts.getOrNull(0)?.toIntOrNull()
                    val h = parts.getOrNull(1)?.toIntOrNull()
                    if (w != null && h != null && w in 10..500 && h in 5..300) return w to h
                }
            } else {
                val o = ProcessBuilder("sh", "-c", "stty size < /dev/tty 2>/dev/null")
                    .redirectErrorStream(true).start()
                    .inputStream.bufferedReader().readText().trim()
                val parts = o.split(Regex("\\s+"))
                val h = parts.getOrNull(0)?.toIntOrNull()
                val w = parts.getOrNull(1)?.toIntOrNull()
                if (w != null && h != null && w in 10..500 && h in 5..300) return w to h
            }
        }
        return 80 to 24
    }
}

object QrAscii {

    private const val FULL = '█'
    private const val UPPER = '▀'
    private const val LOWER = '▄'
    private const val BLANK = ' '

    const val QUIET_ZONE = 4

    enum class Style { HALF_BLOCK, ASCII }

    fun defaultStyle(): Style {
        val enc = System.getProperty("file.encoding", "").lowercase()
        val lang = (System.getenv("LC_ALL")
            ?: System.getenv("LC_CTYPE")
            ?: System.getenv("LANG")
            ?: "").lowercase()
        val win = System.getProperty("os.name", "").lowercase().contains("win")
        val winModern = win && (System.getenv("WT_SESSION") != null || System.getenv("TERM_PROGRAM") != null)
        val utf = enc.contains("utf") || lang.contains("utf") || winModern
        return if (utf) Style.HALF_BLOCK else Style.ASCII
    }

    fun widthFor(modules: Int, style: Style): Int {
        val side = modules + QUIET_ZONE * 2
        return if (style == Style.ASCII) side * 2 else side
    }

    fun heightFor(modules: Int, style: Style): Int {
        val side = modules + QUIET_ZONE * 2
        return if (style == Style.ASCII) side else (side + 1) / 2
    }

    fun sizeOf(content: String, style: Style = defaultStyle()): Pair<Int, Int>? {
        val m = QrMatrix.encode(content) ?: return null
        return widthFor(m.size, style) to heightFor(m.size, style)
    }

    fun render(
        content: String,
        style: Style = defaultStyle(),
        invertedTerminal: Boolean = false
    ): List<String> {
        val matrix = QrMatrix.encode(content) ?: return emptyList()
        val n = matrix.size
        val q = QUIET_ZONE

        fun dark(x: Int, y: Int): Boolean {
            val mx = x - q
            val my = y - q
            if (mx < 0 || my < 0 || mx >= n || my >= n) return false
            return matrix[mx, my]
        }

        val side = n + q * 2
        val out = ArrayList<String>(side)

        if (style == Style.ASCII) {
            for (y in 0 until side) {
                val sb = StringBuilder(side * 2)
                for (x in 0 until side) {
                    val on = dark(x, y) != invertedTerminal
                    sb.append(if (on) "$FULL$FULL" else "  ")
                }
                out.add(sb.toString())
            }
            return out
        }

        var y = 0
        while (y < side) {
            val sb = StringBuilder(side)
            for (x in 0 until side) {
                val top = dark(x, y) != invertedTerminal
                val bottom = if (y + 1 < side) dark(x, y + 1) != invertedTerminal else invertedTerminal
                sb.append(
                    when {
                        top && bottom -> FULL
                        top -> UPPER
                        bottom -> LOWER
                        else -> BLANK
                    }
                )
            }
            out.add(sb.toString())
            y += 2
        }
        return out
    }

    fun renderCentred(
        content: String,
        style: Style = defaultStyle(),
        invertedTerminal: Boolean = false,
        columns: Int = TerminalSize.columns
    ): List<String> {
        val rows = render(content, style, invertedTerminal)
        if (rows.isEmpty()) return rows
        val pad = ((columns - rows[0].length) / 2).coerceAtLeast(0)
        val prefix = " ".repeat(pad)
        return rows.map { prefix + it }
    }

    fun fitWarning(
        content: String,
        style: Style = defaultStyle(),
        columns: Int = TerminalSize.columns
    ): String? {
        val matrix = QrMatrix.encode(content) ?: return null
        val needed = widthFor(matrix.size, style)
        if (needed <= columns) return null
        val hint = if (style == Style.ASCII) " or switch the terminal to UTF-8" else ""
        return "This terminal is $columns columns wide, the code needs $needed. Widen the window$hint."
    }
}
