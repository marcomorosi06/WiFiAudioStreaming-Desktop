/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

object DebugHud {

    private const val ESC = ""
    private const val HUD_ROWS = 20
    private const val WIDTH = 64

    private val color = System.getenv("NO_COLOR") == null && System.getenv("TERM") != "dumb"

    private val lock = Any()
    @Volatile private var running = false
    private var thread: Thread? = null

    private var lastBytes = 0L
    private var lastNanos = 0L
    private var rateBps = 0.0
    private var ratePps = 0.0
    private var lastPkts = 0L

    private var micLastBytes = 0L
    private var micLastPkts = 0L
    private var micLastNanos = 0L
    private var micRateBps = 0.0
    private var micRatePps = 0.0

    private fun sgr(code: String, t: String) = if (color) "$ESC[${code}m$t$ESC[0m" else t

    fun start(sending: Boolean, peer: String) {
        if (running) return
        if (System.console() == null) return
        WfasStats.begin(sending, peer)
        lastBytes = 0L; lastPkts = 0L; lastNanos = System.nanoTime(); rateBps = 0.0; ratePps = 0.0
        running = true
        synchronized(lock) {
            print("$ESC[2J$ESC[H")
            print("$ESC[${HUD_ROWS + 1};r")
            print("$ESC[${HUD_ROWS + 1};1H")
            System.out.flush()
        }
        AppDebug.sink = { line ->
            synchronized(lock) {
                print(line); print("\n"); System.out.flush()
            }
        }
        thread = Thread {
            while (running) {
                try { Thread.sleep(250) } catch (_: InterruptedException) { break }
                if (!running) break
                runCatching { paint() }
            }
        }.apply { isDaemon = true; name = "wfas-debug-hud"; start() }
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.interrupt()
        thread = null
        AppDebug.sink = null
        WfasStats.stop()
        synchronized(lock) {
            print("$ESC[r")
            print("$ESC[?25h")
            print("$ESC[${HUD_ROWS + 1};1H")
            print("\n")
            System.out.flush()
        }
    }

    private fun human(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.1f GB", b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> String.format("%.1f MB", b / (1024.0 * 1024))
        b >= 1024      -> String.format("%.1f KB", b / 1024.0)
        else           -> "$b B"
    }

    private fun rate(bps: Double): String = when {
        bps >= 1024 * 1024 -> String.format("%.1f MB/s", bps / (1024 * 1024))
        bps >= 1024        -> String.format("%.1f KB/s", bps / 1024)
        else               -> String.format("%.0f B/s", bps)
    }

    private fun row(label: String, pkts: Long, bytes: Long, pct: Double): String =
        String.format("  %-12s %10s %12s %6.1f%%", label, String.format("%,d", pkts), human(bytes), pct)

    private fun paint() {
        val now = System.nanoTime()
        val totalBytes = WfasStats.totalBytes()
        val totalPkts = WfasStats.totalPkts()
        val dt = (now - lastNanos) / 1e9
        if (dt > 0.05) {
            rateBps = (totalBytes - lastBytes) / dt
            ratePps = (totalPkts - lastPkts) / dt
            lastBytes = totalBytes; lastPkts = totalPkts; lastNanos = now
        }

        val micTotalBytes = MicStats.totalBytes()
        val micTotalPkts  = MicStats.totalPkts()
        val micDt = (now - micLastNanos) / 1e9
        if (micDt > 0.05) {
            micRateBps = (micTotalBytes - micLastBytes) / micDt
            micRatePps = (micTotalPkts - micLastPkts) / micDt
            micLastBytes = micTotalBytes; micLastPkts = micTotalPkts; micLastNanos = now
        }

        val role = if (WfasStats.sending) "SERVER -> sending" else "CLIENT <- receiving"
        val verb = if (WfasStats.sending) "TX" else "RX"

        val lines = ArrayList<String>(HUD_ROWS)
        lines += sgr("1;36", "  WFAS DEBUG  -  $role").padEndVisible(WIDTH)
        lines += "  peer ${WfasStats.peer}   up ${String.format("%.1f", WfasStats.elapsedSeconds())}s"
        lines += "  $verb ${rate(rateBps)}   ${String.format("%,.0f", ratePps)} pkt/s   total ${human(totalBytes)}"
        lines += sgr("2", "  magic 0x57 0x46 ('WF') marks audio; control msgs are ASCII")
        lines += sgr("2", "  " + "-".repeat(WIDTH - 2))
        lines += sgr("2", String.format("  %-12s %10s %12s %6s", "TYPE", "PACKETS", "BYTES", "SHARE"))

        fun pct(b: Long) = if (totalBytes > 0) b * 100.0 / totalBytes else 0.0
        val cats = listOf(
            "WFAS audio" to WfasStats.Cat.AUDIO,
            "silence"    to WfasStats.Cat.SILENCE,
            "PING"       to WfasStats.Cat.PING,
            "BYE"        to WfasStats.Cat.BYE,
            "HELLO/ACK"  to WfasStats.Cat.HELLO,
            "PROBE"      to WfasStats.Cat.PROBE,
            "other"      to WfasStats.Cat.OTHER,
        )
        for ((label, cat) in cats) {
            val p = WfasStats.pkts(cat)
            val b = WfasStats.bytes(cat)
            val line = row(label, p, b, pct(b))
            lines += if (p > 0L && (cat == WfasStats.Cat.OTHER)) sgr("33", line) else line
        }
        lines += sgr("2", "  " + "-".repeat(WIDTH - 2))

        val micDir = MicStats.dir
        val micActive  = micDir != MicStats.Dir.OFF
        val micWorking = micActive && MicStats.audioPkts() > 0
        val micVerb = when (micDir) {
            MicStats.Dir.RECEIVING -> "RX  receiving on ${MicStats.detail}"
            MicStats.Dir.SENDING   -> "TX  sending to ${MicStats.detail}"
            MicStats.Dir.OFF       -> "--  not configured"
        }
        val micHeaderColor = when {
            micWorking -> "1;32"
            micActive  -> "1;33"
            else       -> "2"
        }
        lines += sgr(micHeaderColor, "  MIC  $micVerb").padEndVisible(WIDTH)
        if (micActive) {
            val micRateVerb = if (micDir == MicStats.Dir.RECEIVING) "RX" else "TX"
            lines += "  $micRateVerb ${rate(micRateBps)}   ${String.format("%,.0f", micRatePps)} pkt/s   total ${human(micTotalBytes)}"
        } else {
            lines += sgr("2", "  add --mic to test microphone send/receive over the link")
        }
        lines += sgr("2", String.format("  %-12s %10s %12s %6s", "TYPE", "PACKETS", "BYTES", "SHARE"))
        fun micPct(b: Long) = if (micTotalBytes > 0) b * 100.0 / micTotalBytes else 0.0
        run {
            val ab = MicStats.audioBytes()
            val audioLine = row("mic audio", MicStats.audioPkts(), ab, micPct(ab))
            lines += if (micWorking) sgr("32", audioLine) else audioLine
        }
        lines += row("mic silence", MicStats.silentPkts(), MicStats.silentBytes(), micPct(MicStats.silentBytes()))

        lines += sgr("2", "  " + "-".repeat(WIDTH - 2) + "  (logs below)")

        val sb = StringBuilder()
        sb.append("$ESC[?25l")
        sb.append("${ESC}7")
        for (i in 0 until HUD_ROWS) {
            val content = lines.getOrElse(i) { "" }
            sb.append("$ESC[${i + 1};1H$ESC[K").append(content)
        }
        sb.append("${ESC}8")
        synchronized(lock) {
            print(sb)
            System.out.flush()
        }
    }

    private fun String.padEndVisible(width: Int): String {
        val visible = this.replace(Regex("$ESC\\[[0-9;]*m"), "").length
        return if (visible >= width) this else this + " ".repeat(width - visible)
    }
}
