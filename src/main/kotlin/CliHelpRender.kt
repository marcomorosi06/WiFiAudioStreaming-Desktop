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

import kotlin.math.max
import kotlin.math.min

object CliHelp {

    private const val ESC = "\u001B"
    private val IS_WINDOWS = System.getProperty("os.name", "").lowercase().contains("win")
    private val PREFORMATTED = Regex("\\S {2,}\\S")

    private var color = false

    private fun bold(t: String) = if (color) "$ESC[1m$t$ESC[0m" else t
    private fun dim(t: String)  = if (color) "$ESC[2m$t$ESC[0m" else t

    fun show(topicArg: String?, json: Boolean, version: String) {
        if (json) { println(renderJson(version)); return }

        val interactive = interactiveAvailable()
        color = interactive && Ansi.enabled

        val arg = topicArg?.trim().orEmpty()
        if (arg.equals("all", true) || arg.equals("full", true) || arg.equals("everything", true)) {
            printLines(renderAll(version)); return
        }
        if (arg.isNotEmpty()) {
            val topic = CliHelpModel.byKey(arg)
            if (topic == null) {
                System.err.println("Unknown help topic '$arg'.")
                System.err.println("Available topics: " + CliHelpModel.topics.joinToString(", ") { it.key })
                System.err.println("Use 'wfas --help all' for everything.")
                return
            }
            printLines(renderTopic(topic, version)); return
        }
        if (interactive) browse(version) else printLines(renderAll(version))
    }

    private fun interactiveAvailable(): Boolean {
        if (System.getenv("WFAS_NO_INTERACTIVE") != null) return false
        if (System.getenv("TERM") == "dumb") return false
        if (System.getenv("CI") != null) return false
        return System.console() != null
    }

    private fun printLines(lines: List<String>) {
        val sb = StringBuilder()
        for (l in lines) sb.append(l.trimEnd()).append('\n')
        print(sb)
        System.out.flush()
    }

    private fun textWidth(): Int {
        val c = TerminalSize.columns
        return if (c < 40) 78 else min(c - 1, 96).coerceAtLeast(50)
    }

    private fun wrap(text: String, width: Int, preserve: Boolean = false): List<String> {
        val out = ArrayList<String>()
        for (para in text.split("\n")) {
            if (para.isBlank()) { out.add(""); continue }
            if (preserve && (PREFORMATTED.containsMatchIn(para) || para.startsWith(" "))) {
                out.add(para); continue
            }
            val words = para.trim().split(Regex("\\s+"))
            val line = StringBuilder()
            for (w in words) {
                if (line.isNotEmpty() && line.length + 1 + w.length > width) {
                    out.add(line.toString()); line.setLength(0)
                }
                if (line.isNotEmpty()) line.append(' ')
                line.append(w)
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        return out
    }

    private fun indented(text: String, width: Int, pad: String, preserve: Boolean = false): List<String> =
        wrap(text, width - pad.length, preserve).map { if (it.isEmpty()) "" else pad + it }

    private fun body(e: HelpEntry): String =
        if (e.default != null) e.brief.trimEnd() + "  [" + e.default + "]" else e.brief

    private fun renderEntries(entries: List<HelpEntry>, width: Int, indent: String): List<String> {
        val out = ArrayList<String>()
        val longest = entries.maxOfOrNull { it.syntax.length } ?: 0
        val col = indent.length + min(longest, 22) + 2
        val chunks = entries.map { e ->
            val piece = ArrayList<String>()
            val text = wrap(body(e), width - col)
            if (indent.length + e.syntax.length + 2 > col) {
                piece.add(indent + bold(e.syntax))
                text.forEach { piece.add(" ".repeat(col) + it) }
            } else {
                val pad = " ".repeat(col - indent.length - e.syntax.length)
                piece.add(indent + bold(e.syntax) + pad + text.firstOrNull().orEmpty())
                text.drop(1).forEach { piece.add(" ".repeat(col) + it) }
            }
            e.detail?.let { d ->
                piece.add("")
                piece.addAll(indented(d, width, " ".repeat(col), preserve = true))
            }
            piece to (e.detail != null)
        }
        chunks.forEachIndexed { i, (piece, detailed) ->
            if (i > 0 && (detailed || chunks[i - 1].second)) out.add("")
            out.addAll(piece)
        }
        return out
    }

    private fun renderExamples(examples: List<Pair<String, String>>, width: Int): List<String> {
        if (examples.isEmpty()) return emptyList()
        val longest = examples.maxOf { it.first.length }
        val col = min(longest, width - 24) + 4
        val out = ArrayList<String>()
        for ((cmd, note) in examples) {
            if (cmd.length + 2 > col) {
                out.add("  $cmd")
                out.add(" ".repeat(col) + dim("# $note"))
            } else {
                out.add("  " + cmd + " ".repeat(col - 2 - cmd.length) + dim("# $note"))
            }
        }
        return out
    }

    private fun renderFiles(): List<String> {
        val files = ConfigPaths.describeForHelp()
        val w = files.maxOf { it.second.length }
        return files.map { (label, path) -> "  " + path.padEnd(w) + "  " + dim(label) }
    }

    private fun header(version: String): List<String> = listOf(
        bold("WiFi Audio Streaming $version") + "  " + dim("(c) 2026 Marco Morosi"),
        CliHelpModel.TAGLINE
    )

    fun renderMenu(version: String): List<String> {
        val out = ArrayList<String>()
        out.addAll(header(version))
        out.add("")
        out.add(bold("Most common"))
        val cw = CliHelpModel.QUICK.maxOf { it.first.length } + 2
        for ((cmd, note) in CliHelpModel.QUICK) out.add("  " + cmd.padEnd(cw) + dim(note))
        out.add("")
        out.add(bold("More help"))
        val tw = CliHelpModel.topics.maxOf { it.title.length } + 2
        CliHelpModel.topics.forEachIndexed { i, t ->
            out.add("  " + bold((i + 1).toString()) + "  " + t.title.padEnd(tw) + dim(t.tagline))
        }
        out.add("")
        out.add("  " + bold("a") + "  " + "Everything at once".padEnd(tw) + dim("same as 'wfas --help all'"))
        return out
    }

    fun renderTopic(topic: HelpTopic, version: String, withHeader: Boolean = true): List<String> {
        val width = textWidth()
        val out = ArrayList<String>()
        if (withHeader) { out.addAll(header(version)); out.add("") }
        out.add(bold(topic.title.uppercase()) + "  " + dim(topic.tagline))
        topic.intro?.let { out.add(""); out.addAll(wrap(it, width)) }
        for (b in topic.blocks) {
            out.add("")
            b.heading?.let { out.add(bold(it)) }
            b.intro?.let { out.addAll(indented(it, width, "  ")); out.add("") }
            if (b.entries.isNotEmpty()) out.addAll(renderEntries(b.entries, width, "  "))
            b.outro?.let { out.add(""); out.addAll(indented(it, width, "  ")) }
        }
        if (topic.examples.isNotEmpty()) {
            out.add("")
            out.add(bold("Examples"))
            out.addAll(renderExamples(topic.examples, width))
        }
        if (topic.seeAlso.isNotEmpty()) {
            out.add("")
            out.add(dim("See also: " + topic.seeAlso.joinToString(", ") { "wfas --help $it" }))
        }
        return out
    }

    fun renderAll(version: String): List<String> {
        val width = textWidth()
        val out = ArrayList<String>()
        out.addAll(header(version))
        out.add("")
        out.add(bold("USAGE"))
        CliHelpModel.SYNOPSIS.forEach { out.add("  $it") }
        out.add("")
        out.add(bold("TOPICS"))
        val tw = CliHelpModel.topics.maxOf { it.key.length } + 4
        CliHelpModel.topics.forEach { t ->
            out.add("  " + t.key.padEnd(tw) + t.title + dim("  " + t.tagline))
        }
        for (t in CliHelpModel.topics) {
            out.add("")
            out.addAll(renderTopic(t, version, withHeader = false))
        }
        out.add("")
        out.add(bold("FILES"))
        out.addAll(renderFiles())
        out.add("")
        out.addAll(indented(
            "Override the settings file with --config <path>, or print it with 'wfas config path'.",
            width, "  "))
        out.add("")
        out.add("See 'man wfas' for the same reference as a manual page.")
        out.add("")
        out.add("Licensed under the EUPL, Version 1.2")
        val lw = CliHelpModel.LINKS.maxOf { it.first.length } + 3
        CliHelpModel.LINKS.forEach { (label, url) -> out.add("  " + (label + ":").padEnd(lw) + url) }
        return out
    }

    private fun jsonString(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"'  -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    fun renderJson(version: String): String {
        val sb = StringBuilder()
        sb.append("{\"version\":").append(jsonString(version))
        sb.append(",\"tagline\":").append(jsonString(CliHelpModel.TAGLINE))
        sb.append(",\"synopsis\":[")
        sb.append(CliHelpModel.SYNOPSIS.joinToString(",") { jsonString(it) })
        sb.append("],\"topics\":[")
        sb.append(CliHelpModel.topics.joinToString(",") { t ->
            val blocks = t.blocks.joinToString(",") { b ->
                val entries = b.entries.joinToString(",") { e ->
                    "{\"syntax\":" + jsonString(e.syntax) +
                    ",\"brief\":" + jsonString(e.brief) +
                    ",\"default\":" + jsonString(e.default) +
                    ",\"detail\":" + jsonString(e.detail) +
                    ",\"tokens\":[" + e.tokens.joinToString(",") { jsonString(it) } + "]}"
                }
                "{\"heading\":" + jsonString(b.heading) +
                ",\"intro\":" + jsonString(b.intro) +
                ",\"outro\":" + jsonString(b.outro) +
                ",\"entries\":[" + entries + "]}"
            }
            val examples = t.examples.joinToString(",") { (c, n) ->
                "{\"command\":" + jsonString(c) + ",\"note\":" + jsonString(n) + "}"
            }
            "{\"key\":" + jsonString(t.key) +
            ",\"title\":" + jsonString(t.title) +
            ",\"tagline\":" + jsonString(t.tagline) +
            ",\"aliases\":[" + t.aliases.joinToString(",") { jsonString(it) } + "]" +
            ",\"intro\":" + jsonString(t.intro) +
            ",\"blocks\":[" + blocks + "]" +
            ",\"examples\":[" + examples + "]" +
            ",\"seeAlso\":[" + t.seeAlso.joinToString(",") { jsonString(it) } + "]}"
        })
        sb.append("]}")
        return sb.toString()
    }

    private fun browse(version: String) {
        val keys = RawKeys()
        if (!keys.start()) { printLines(renderAll(version)); return }
        val ansi = color
        if (ansi) print("$ESC[?1049h$ESC[?25l")
        try {
            var topic: HelpTopic? = null
            var scroll = 0
            while (true) {
                val lines = if (topic == null) renderMenu(version) else renderTopic(topic, version)
                val viewport = max(TerminalSize.rows - 2, 6)
                val maxScroll = max(0, lines.size - viewport)
                if (scroll > maxScroll) scroll = maxScroll
                draw(lines, scroll, viewport, topic != null, maxScroll, ansi)

                val key = keys.read() ?: return
                if (key.length == 1 && key[0] in '1'..'9') {
                    val idx = key[0] - '1'
                    if (idx < CliHelpModel.topics.size) { topic = CliHelpModel.topics[idx]; scroll = 0 }
                    continue
                }
                when (key) {
                    "Q" -> return
                    "A" -> { keys.stop(); restore(ansi); printLines(renderAll(version)); return }
                    "B", "ESCAPE", "LEFT" -> { topic = null; scroll = 0 }
                    "DOWN" -> scroll = min(scroll + 1, maxScroll)
                    "UP" -> scroll = max(scroll - 1, 0)
                    "SPACE", "PAGEDOWN", "ENTER", "RIGHT" ->
                        if (scroll < maxScroll) scroll = min(scroll + viewport - 1, maxScroll)
                        else if (topic != null) { topic = null; scroll = 0 }
                    "PAGEUP" -> scroll = max(scroll - viewport + 1, 0)
                    "HOME" -> scroll = 0
                    "END" -> scroll = maxScroll
                    else -> {}
                }
            }
        } finally {
            keys.stop()
            restore(ansi)
        }
    }

    private fun restore(ansi: Boolean) {
        if (ansi) { print("$ESC[?25h$ESC[?1049l"); System.out.flush() }
    }

    private fun draw(
        lines: List<String>, scroll: Int, viewport: Int,
        inTopic: Boolean, maxScroll: Int, ansi: Boolean
    ) {
        val sb = StringBuilder()
        if (ansi) sb.append("$ESC[2J$ESC[H") else sb.append('\n')
        val end = min(lines.size, scroll + viewport)
        for (i in scroll until end) sb.append(lines[i].trimEnd()).append('\n')
        for (i in end until scroll + viewport) sb.append('\n')
        sb.append(footer(inTopic, scroll, maxScroll))
        print(sb)
        System.out.flush()
    }

    private fun footer(inTopic: Boolean, scroll: Int, maxScroll: Int): String {
        val parts = ArrayList<String>()
        if (inTopic) {
            if (maxScroll > 0) parts.add("space scrolls")
            parts.add("b back")
        } else {
            parts.add("1-9 open a section")
            parts.add("a everything")
        }
        parts.add("q quit")
        val pos = if (maxScroll > 0) {
            val pct = (scroll * 100) / maxScroll
            "  " + (if (scroll >= maxScroll) "END" else "$pct%")
        } else ""
        return dim(parts.joinToString("   ") + pos)
    }

    private class RawKeys {

        private var proc: Process? = null
        private var reader: java.io.BufferedReader? = null
        private var rawSet = false

        fun start(): Boolean {
            if (IS_WINDOWS) {
                val keyCmd =
                    "while(\$true){\$k=\$Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown');" +
                    "[Console]::Out.WriteLine([string]\$k.VirtualKeyCode + ' ' + [string][int]\$k.Character);" +
                    "[Console]::Out.Flush()}"
                return runCatching {
                    val pb = ProcessBuilder("powershell", "-NoProfile", "-Command", keyCmd)
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
                    pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                    val p = pb.start()
                    proc = p
                    reader = p.inputStream.bufferedReader()
                    true
                }.getOrDefault(false)
            }
            return runCatching {
                val rc = ProcessBuilder("sh", "-c", "stty -echo -icanon min 1 time 0 < /dev/tty")
                    .start().waitFor()
                rawSet = rc == 0
                rawSet
            }.getOrDefault(false)
        }

        fun stop() {
            runCatching { proc?.destroyForcibly() }
            proc = null
            if (rawSet) {
                rawSet = false
                runCatching { ProcessBuilder("sh", "-c", "stty sane < /dev/tty").start().waitFor() }
            }
        }

        fun read(): String? = if (IS_WINDOWS) readWindows() else readUnix()

        private fun readWindows(): String? {
            val r = reader ?: return null
            while (true) {
                val line = runCatching { r.readLine() }.getOrNull() ?: return null
                val parts = line.trim().split(' ')
                val vk = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val ch = parts.getOrNull(1)?.toIntOrNull() ?: 0
                fromWindows(vk, ch)?.let { return it }
            }
        }

        private fun fromWindows(vk: Int, ch: Int): String? {
            if (ch in 49..57) return (ch - 48).toString()
            return when (vk) {
                38 -> "UP"
                40 -> "DOWN"
                37 -> "LEFT"
                39 -> "RIGHT"
                33 -> "PAGEUP"
                34 -> "PAGEDOWN"
                36 -> "HOME"
                35 -> "END"
                27 -> "ESCAPE"
                32 -> "SPACE"
                13 -> "ENTER"
                else -> if (ch in 32..126) ch.toChar().uppercaseChar().toString() else null
            }
        }

        private fun readUnix(): String? {
            val inp = System.`in`
            while (true) {
                val b = runCatching { inp.read() }.getOrNull() ?: return null
                if (b < 0) return null
                if (b == 27) {
                    if (inp.available() <= 0) return "ESCAPE"
                    if (inp.read().toChar() != '[') continue
                    if (inp.available() <= 0) return "ESCAPE"
                    when (val c = inp.read().toChar()) {
                        'A' -> return "UP"
                        'B' -> return "DOWN"
                        'C' -> return "RIGHT"
                        'D' -> return "LEFT"
                        'H' -> return "HOME"
                        'F' -> return "END"
                        '5' -> { runCatching { inp.read() }; return "PAGEUP" }
                        '6' -> { runCatching { inp.read() }; return "PAGEDOWN" }
                        else -> { if (c.isDigit()) runCatching { inp.read() }; continue }
                    }
                }
                if (b == 32) return "SPACE"
                if (b == 10 || b == 13) return "ENTER"
                val c = b.toChar()
                if (c in '1'..'9') return c.toString()
                if (c.isLetter()) return c.uppercaseChar().toString()
            }
        }
    }
}
