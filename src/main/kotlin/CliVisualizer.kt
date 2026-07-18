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

import java.io.PrintStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val ESC = "\u001B"

private val VIZ_MODE = (System.getenv("WFAS_VIZ") ?: System.getProperty("wfas.viz") ?: "").lowercase()

private val VIZ_TERMINAL_HINT: Boolean =
    System.getenv("WT_SESSION") != null ||
    System.getenv("WT_PROFILE_ID") != null ||
    System.getenv("ANSICON") != null ||
    System.getenv("ConEmuANSI")?.equals("ON", ignoreCase = true) == true ||
    System.getenv("TERM_PROGRAM") != null ||
    (System.getenv("COLORTERM") ?: "").isNotEmpty() ||
    (System.getenv("TERM") ?: "").let { it.isNotEmpty() && it != "dumb" }

private val VIZ_ANSI: Boolean = when (VIZ_MODE) {
    "plain", "ascii", "simple", "off", "none" -> false
    "full", "fancy", "ansi", "force", "1", "on" -> true
    else -> when {
        System.getenv("TERM") == "dumb" -> false
        System.console() != null -> true
        else -> VIZ_TERMINAL_HINT
    }
}

private val VIZ_COLOR: Boolean = VIZ_ANSI && System.getenv("NO_COLOR") == null && VIZ_MODE != "mono"

private val VIZ_COLORTERM = (System.getenv("COLORTERM") ?: "").lowercase()
private val VIZ_TERM = (System.getenv("TERM") ?: "").lowercase()
private val VIZ_IS_WINDOWS = System.getProperty("os.name", "").lowercase().contains("win")
private val VIZ_TRUECOLOR = VIZ_COLORTERM.contains("truecolor") || VIZ_COLORTERM.contains("24bit") ||
    System.getenv("WT_SESSION") != null ||
    System.getenv("ConEmuANSI")?.equals("ON", ignoreCase = true) == true ||
    System.getenv("TERM_PROGRAM") != null ||
    (VIZ_IS_WINDOWS && VIZ_ANSI)
private val VIZ_256 = VIZ_TRUECOLOR || VIZ_TERM.contains("256")

private val BASIC16 = arrayOf(
    intArrayOf(0, 0, 0, 30),
    intArrayOf(205, 49, 49, 31),
    intArrayOf(13, 188, 121, 32),
    intArrayOf(229, 229, 16, 33),
    intArrayOf(36, 114, 200, 34),
    intArrayOf(188, 63, 188, 35),
    intArrayOf(17, 168, 205, 36),
    intArrayOf(204, 204, 204, 37),
    intArrayOf(102, 102, 102, 90),
    intArrayOf(241, 76, 76, 91),
    intArrayOf(35, 209, 139, 92),
    intArrayOf(245, 245, 67, 93),
    intArrayOf(59, 142, 234, 94),
    intArrayOf(214, 112, 214, 95),
    intArrayOf(41, 184, 219, 96),
    intArrayOf(255, 255, 255, 97),
)

private fun nearest16(r: Int, g: Int, b: Int): Int {
    var best = 37
    var bestD = Int.MAX_VALUE
    for (c in BASIC16) {
        val dr = r - c[0]; val dg = g - c[1]; val db = b - c[2]
        val d = dr * dr + dg * dg + db * db
        if (d < bestD) { bestD = d; best = c[3] }
    }
    return best
}

private fun fg(r: Int, g: Int, b: Int): String = when {
    !VIZ_COLOR -> ""
    VIZ_TRUECOLOR -> "$ESC[38;2;$r;$g;${b}m"
    VIZ_256 -> {
        val code = 16 + 36 * ((r * 5 + 127) / 255) + 6 * ((g * 5 + 127) / 255) + ((b * 5 + 127) / 255)
        "$ESC[38;5;${code}m"
    }
    else -> "$ESC[${nearest16(r, g, b)}m"
}

private fun sgr(code: String): String = if (VIZ_COLOR) "$ESC[${code}m" else ""

private fun hsv(h: Double, s: Double, v: Double): IntArray {
    val c = v * s
    val hp = ((h % 360.0) + 360.0) % 360.0 / 60.0
    val x = c * (1.0 - abs(hp % 2.0 - 1.0))
    val r1: Double; val g1: Double; val b1: Double
    when {
        hp < 1.0 -> { r1 = c; g1 = x; b1 = 0.0 }
        hp < 2.0 -> { r1 = x; g1 = c; b1 = 0.0 }
        hp < 3.0 -> { r1 = 0.0; g1 = c; b1 = x }
        hp < 4.0 -> { r1 = 0.0; g1 = x; b1 = c }
        hp < 5.0 -> { r1 = x; g1 = 0.0; b1 = c }
        else -> { r1 = c; g1 = 0.0; b1 = x }
    }
    val m = v - c
    return intArrayOf(
        ((r1 + m) * 255.0).roundToInt().coerceIn(0, 255),
        ((g1 + m) * 255.0).roundToInt().coerceIn(0, 255),
        ((b1 + m) * 255.0).roundToInt().coerceIn(0, 255),
    )
}

private fun parseHexColor(raw: String?): IntArray? {
    val h = (raw ?: return null).removePrefix("#")
    val full = when (h.length) {
        3 -> "${h[0]}${h[0]}${h[1]}${h[1]}${h[2]}${h[2]}"
        6 -> h
        else -> return null
    }
    return try {
        intArrayOf(full.substring(0, 2).toInt(16), full.substring(2, 4).toInt(16), full.substring(4, 6).toInt(16))
    } catch (_: Exception) {
        null
    }
}

class AudioVisualizer(
    private val channels: Int = 2,
    private val label: String = "server",
    private val sampleRate: Int = 48000,
    private val theme: String? = null,
    volumeEnabled: Boolean = true,
    groove: Float = 0f,
) {

    companion object {
        private const val FFT_SIZE     = 2048
        private const val F_MIN        = 35.0
        private const val FLOOR_DB     = -66.0
        private const val CEIL_DB      = -9.0
        private const val TILT_DB      = 6.0
        private const val ATTACK       = 0.55f
        private const val RELEASE      = 0.16f
        private const val PEAK_GRAV    = 0.006f
        private const val METER_FLOOR  = -54.0
        private const val FRAME_MS     = 33L
        private const val RAINBOW_SPAN = 340f
        private const val RAINBOW_STEP = 3f
        private const val DEF_COLS     = 90
        private const val DEF_ROWS     = 24

        private const val GRV_MAX      = 1.6f
        private const val GRV_SLOW     = 0.012f
        private const val GRV_RADIUS   = 0.07f
        private const val GRV_CONTRAST = 0.85f
        private const val GRV_WHITEN   = 0.25f
        private const val GRV_GATE_DB  = 8.0f
        private const val GRV_MAX_DEV  = 22.0f
        private const val GRV_RELEASE  = 0.30f

        private const val BAR_CHAR  = '#'
        private const val CAP_CHAR  = '-'
        private const val AXIS_CHAR = '-'
        private const val M_FILL    = '#'
        private const val M_EMPTY   = '-'
        private const val M_TICK    = '+'
        private const val BRACKET   = '|'
        private const val DB_W      = 9
    }

    private val ch = channels.coerceAtLeast(1)
    private val dispCh = ch.coerceAtMost(8)

    private val monoRing = DoubleArray(FFT_SIZE)
    private var monoWrite = 0
    private val window = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2.0 * PI * it / (FFT_SIZE - 1)) }
    private val fftRe = DoubleArray(FFT_SIZE)
    private val fftIm = DoubleArray(FFT_SIZE)
    private val cosT = DoubleArray(FFT_SIZE / 2)
    private val sinT = DoubleArray(FFT_SIZE / 2)
    private val bitRev = IntArray(FFT_SIZE)

    private val chSumSq = DoubleArray(ch)
    private val snapSums = DoubleArray(ch)
    private var chFrames = 0
    private var snapFrames = 0
    private val chLevel = FloatArray(ch)
    private val chPeak = FloatArray(ch)
    private val chDb = FloatArray(ch) { METER_FLOOR.toFloat() }

    private val themeLower = theme?.trim()?.lowercase()
    private val rainbow = themeLower == "rainbow" || themeLower == "rgb"
    private val seedRgb: IntArray? = if (rainbow) null else parseHexColor(themeLower)
    private var phase = 0f
    private var barW = 2
    private var gap = 1
    private var labelArea = 3
    private var showDb = true

    private var numBars = 24
    private var specRows = 14
    private var meterW = 44
    private var totalW = 71
    private var pad = 2
    private var topPad = 0
    private var vizCols = DEF_COLS
    private var vizRows = DEF_ROWS

    private val grooveDefault = groove.coerceIn(0f, GRV_MAX).takeIf { it > 0f } ?: 1f
    @Volatile private var grooveAmt = groove.coerceIn(0f, GRV_MAX)
    @Volatile private var hudDirty = false
    private var gvDb = FloatArray(0)
    private var gvSlow = FloatArray(0)
    private var gvSum = FloatArray(0)
    @Volatile private var gvReady = false

    private var binLo = IntArray(0)
    private var binHi = IntArray(0)
    private var bars = FloatArray(0)
    private var peaks = FloatArray(0)
    private var peakVel = FloatArray(0)
    private var rowColor = arrayOf<String>()
    private var rowColorBand = arrayOf<String>()
    private var meterColor = arrayOf<String>()
    private val regionMarks = ArrayList<Pair<Int, String>>()

    private var capColor = ""
    private var emptyColor = ""
    private var tickColor = ""
    private var textColor = ""
    private var RULE = ""
    private var TITLE = ""
    private var BAND = ""
    private val RESET = sgr("0")
    private val DIM = sgr("2")

    private var lnMin = 0.0
    private var lnSpan = 1.0
    private var topDashL = 0
    private var topTitle = ""
    private var topDashR = 0
    private var botDashL = 0
    private var botTitle = ""
    private var botDashR = 0

    @Volatile private var pendingCols = -1
    @Volatile private var pendingRows = -1
    @Volatile var statusMsg = "waiting for audio..."

    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val restored = AtomicBoolean(false)
    private var renderThread: Thread? = null
    private var sizeThread: Thread? = null
    private var inputThread: Thread? = null
    private var keyProc: Process? = null

    enum class Mode { NORMAL, CONFIRM_QUIT, VOLUME }
    @Volatile private var mode = Mode.NORMAL
    @Volatile private var volPct = 100
    var onQuit: (() -> Unit)? = null
    var onVolume: ((Float) -> Unit)? = null
    @Volatile var volumeEnabled = volumeEnabled

    init {
        var j = 0
        for (i in 0 until FFT_SIZE) {
            bitRev[i] = j
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
        }
        for (i in 0 until FFT_SIZE / 2) {
            val ang = -2.0 * PI * i / FFT_SIZE
            cosT[i] = cos(ang)
            sinT[i] = sin(ang)
        }
        val sz = if (VIZ_ANSI) queryTermSize() else null
        relayout(sz?.first ?: DEF_COLS, sz?.second ?: DEF_ROWS)
    }

    private fun colOfBand(b: Int): Int = b * (barW + gap)

    private fun rulePieces(text: String): Triple<Int, String, Int> {
        val t = text.take(totalW)
        val dash = (totalW - t.length).coerceAtLeast(0)
        return Triple(dash / 2, t, dash - dash / 2)
    }

    private fun addRegion(lo: Double, hi: Double, lab: String, fMax: Double) {
        var first = -1
        var last = -1
        for (b in 0 until numBars) {
            val cf = exp(lnMin + lnSpan * (b + 0.5) / numBars)
            if (cf >= lo && (cf < hi || hi >= fMax)) {
                if (first < 0) first = b
                last = b
            }
        }
        if (first < 0) return
        val c0 = colOfBand(first)
        val c1 = colOfBand(last) + barW - 1
        var col = (c0 + c1) / 2 - lab.length / 2
        if (col < 0) col = 0
        if (col + lab.length > totalW) col = totalW - lab.length
        regionMarks.add(col to lab)
    }

    private fun relayout(cols: Int, rows: Int) {
        vizCols = cols
        vizRows = rows
        if (cols >= 70) { barW = 2; gap = 1 } else if (cols >= 34) { barW = 1; gap = 1 } else { barW = 1; gap = 0 }
        val usable = (cols - 2).coerceAtLeast(6)
        numBars = ((usable + gap) / (barW + gap)).coerceIn(3, 96)
        totalW = numBars * (barW + gap) - gap
        specRows = (rows - dispCh - 8).coerceIn(4, 30)
        pad = ((cols - totalW) / 2).coerceAtLeast(0)
        val used = specRows + dispCh + 7
        topPad = ((rows - used) / 2).coerceIn(0, 6)
        var maxLab = 1
        for (cc in 0 until dispCh) maxLab = maxOf(maxLab, chLabel(cc).length)
        labelArea = maxLab.coerceAtMost(3)
        showDb = totalW >= 28
        meterW = (totalW - labelArea - 2 - (if (showDb) DB_W else 0)).coerceAtLeast(3)

        binLo = IntArray(numBars)
        binHi = IntArray(numBars)
        bars = FloatArray(numBars)
        peaks = FloatArray(numBars)
        peakVel = FloatArray(numBars)
        rowColorBand = Array(numBars) { "" }
        rowColor = Array(specRows) { "" }
        meterColor = Array(meterW) { "" }

        gvDb = FloatArray(numBars)
        gvSlow = FloatArray(numBars)
        gvSum = FloatArray(numBars + 1)
        gvReady = false

        val nyq = sampleRate / 2.0
        val fMax = min(nyq * 0.92, 20000.0).coerceAtLeast(F_MIN * 4)
        val freqRes = sampleRate.toDouble() / FFT_SIZE
        lnMin = ln(F_MIN)
        lnSpan = ln(fMax) - lnMin
        for (b in 0 until numBars) {
            val f0 = exp(lnMin + lnSpan * b / numBars)
            val f1 = exp(lnMin + lnSpan * (b + 1) / numBars)
            val lo = (f0 / freqRes).toInt().coerceIn(1, FFT_SIZE / 2 - 1)
            val hi = (f1 / freqRes).toInt().coerceIn(lo, FFT_SIZE / 2 - 1)
            binLo[b] = lo
            binHi[b] = hi
        }
        regionMarks.clear()
        addRegion(0.0, 250.0, "bass", fMax)
        addRegion(250.0, 4000.0, "mids", fMax)
        addRegion(4000.0, 1e9, "highs", fMax)

        val tp = rulePieces(" WiFi Audio Streaming | $label ")
        topDashL = tp.first; topTitle = tp.second; topDashR = tp.third
        buildBottomRule()

        buildColors()
    }

    private fun buildBottomRule() {
        val vol = if (volumeEnabled) " . v volume" else ""
        val volShort = if (volumeEnabled) " . v vol" else ""
        val state = if (grooveAmt > 0f) "on" else "off"
        val full = " q quit$vol . g groove $state . ${vizCols}x${vizRows} "
        val mid = " q quit$volShort . g groove $state "
        val small = if (volumeEnabled) " q . v . g $state " else " q . g $state "
        val txt = when {
            full.length <= totalW -> full
            mid.length <= totalW -> mid
            else -> small
        }
        val bp = rulePieces(txt)
        botDashL = bp.first; botTitle = bp.second; botDashR = bp.third
    }

    private fun awtColor(h: Float, s: Float, b: Float): String {
        val rgb = java.awt.Color.HSBtoRGB(((h % 1f) + 1f) % 1f, s.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        return fg((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }

    private fun buildColors() {
        when {
            rainbow -> {
                for (i in rowColor.indices) rowColor[i] = fg(255, 255, 255)
                for (i in meterColor.indices) meterColor[i] = fg(255, 255, 255)
                capColor = fg(245, 245, 245)
                tickColor = fg(245, 245, 245)
                emptyColor = fg(70, 78, 92)
                RULE = fg(210, 210, 210)
                TITLE = fg(255, 255, 255)
                BAND = fg(255, 255, 255)
                textColor = fg(235, 235, 235)
            }
            seedRgb != null -> buildSeedColors(seedRgb)
            else -> buildDefaultColors()
        }
    }

    private fun buildDefaultColors() {
        for (row in 0 until specRows) {
            val frac = (specRows - row).toFloat() / specRows
            val c = hsv(168.0 * (1.0 - frac), 0.92, 1.0)
            rowColor[row] = fg(c[0], c[1], c[2])
        }
        for (i in 0 until meterW) {
            val x = i.toFloat() / (meterW - 1).coerceAtLeast(1)
            val c = hsv(125.0 * (1.0 - x), 0.95, 1.0)
            meterColor[i] = fg(c[0], c[1], c[2])
        }
        capColor = fg(238, 238, 250)
        emptyColor = fg(70, 78, 92)
        tickColor = fg(236, 236, 248)
        RULE = sgr("2;36")
        TITLE = sgr("1;36")
        BAND = sgr("1;90")
        textColor = sgr("2")
    }

    private fun buildSeedColors(rgb: IntArray) {
        val hsb = FloatArray(3)
        java.awt.Color.RGBtoHSB(rgb[0], rgb[1], rgb[2], hsb)
        val h = hsb[0]
        val s = hsb[1].coerceAtLeast(0.15f)
        val bF = hsb[2].coerceIn(0.55f, 1f)
        val tH = (h + 0.15f) % 1f
        for (row in 0 until specRows) {
            val t = (specRows - 1 - row).toFloat() / (specRows - 1).coerceAtLeast(1)
            rowColor[row] = awtColor(h + 0.15f * t, (s * (0.95f - 0.35f * t)).coerceIn(0.30f, 1f), (0.45f + 0.50f * t) * bF)
        }
        for (i in 0 until meterW) {
            val t = i.toFloat() / (meterW - 1).coerceAtLeast(1)
            meterColor[i] = awtColor(h + 0.15f * t, (s * 0.7f).coerceIn(0.30f, 1f), (0.60f + 0.35f * t) * bF)
        }
        capColor = awtColor(h, s * 0.10f, 1f)
        tickColor = awtColor(tH, s * 0.55f, 0.98f)
        emptyColor = awtColor(h, s * 0.45f, 0.26f)
        RULE = awtColor(h, s * 0.60f, 0.55f * bF)
        TITLE = sgr("1") + awtColor(h, s * 0.75f, 0.92f * bF)
        BAND = sgr("1") + awtColor(tH, s * 0.65f, 0.85f * bF)
        textColor = awtColor(h, s * 0.25f, 0.90f)
    }

    private fun rainbowAt(col: Int): String {
        val hue = phase + col.toFloat() / totalW.coerceAtLeast(1) * RAINBOW_SPAN
        val c = hsv(hue.toDouble(), 0.9, 1.0)
        return fg(c[0], c[1], c[2])
    }

    private fun updateRainbow() {
        if (!rainbow || !VIZ_COLOR) return
        phase += RAINBOW_STEP
        if (phase >= 360f) phase -= 360f
        for (b in 0 until numBars) rowColorBand[b] = rainbowAt(pad + colOfBand(b))
        for (i in 0 until meterW) meterColor[i] = rainbowAt(pad + labelArea + 1 + i)
    }

    fun start() {
        if (VIZ_ANSI) {
            runCatching { System.setOut(PrintStream(System.out, true, "UTF-8")) }
            print("$ESC[?1049h$ESC[2J$ESC[H$ESC[?25l")
            System.out.flush()
        }
        running.set(true)
        Runtime.getRuntime().addShutdownHook(Thread { restoreScreen() })
        renderThread = Thread(::renderLoop, "wfas-viz").also { it.isDaemon = true; it.start() }
        if (VIZ_ANSI) sizeThread = Thread(::sizeLoop, "wfas-viz-size").also { it.isDaemon = true; it.start() }
        startInput()
    }

    fun stop() {
        running.set(false)
        renderThread?.join(400)
        sizeThread?.join(400)
        restoreScreen()
        if (!VIZ_ANSI) {
            print("\r${" ".repeat(80)}\r")
            System.out.flush()
        }
    }

    private fun restoreScreen() {
        if (VIZ_ANSI && restored.compareAndSet(false, true)) {
            stopInput()
            print("$RESET$ESC[?25h$ESC[?1049l")
            System.out.flush()
        }
    }

    fun feedFrame(samples: ShortArray) {
        synchronized(lock) {
            var i = 0
            val n = samples.size
            while (i + ch <= n) {
                var acc = 0.0
                var c = 0
                while (c < ch) {
                    val s = samples[i + c].toDouble()
                    acc += s
                    chSumSq[c] += s * s
                    c++
                }
                chFrames++
                monoRing[monoWrite] = acc / ch
                monoWrite = if (monoWrite + 1 >= FFT_SIZE) 0 else monoWrite + 1
                i += ch
            }
        }
    }

    private fun sizeLoop() {
        while (running.get()) {
            val sz = queryTermSize()
            if (sz != null) { pendingCols = sz.first; pendingRows = sz.second }
            try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
        }
    }

    private fun queryTermSize(): Pair<Int, Int>? {
        System.getenv("WFAS_VIZ_COLS")?.toIntOrNull()?.let { ec ->
            System.getenv("WFAS_VIZ_ROWS")?.toIntOrNull()?.let { er -> return ec to er }
        }
        try {
            if (VIZ_IS_WINDOWS) {
                val tmp = java.io.File(System.getProperty("java.io.tmpdir"), "wfas_viz_size.txt")
                runCatching { tmp.delete() }
                val path = tmp.absolutePath.replace("'", "''")
                val cmd = "\$o=[Console]::WindowWidth.ToString()+' '+[Console]::WindowHeight.ToString(); Set-Content -LiteralPath '" + path + "' -Value \$o -Encoding ascii"
                val pb = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", cmd)
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
                pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                pb.start().waitFor()
                val txt = if (tmp.exists()) tmp.readText().trim() else ""
                val parts = txt.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val cols = parts[0].toIntOrNull(); val rows = parts[1].toIntOrNull()
                    if (cols != null && rows != null && cols in 10..500 && rows in 5..300) return cols to rows
                }
            } else {
                val out = ProcessBuilder("sh", "-c", "stty size < /dev/tty 2>/dev/null")
                    .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
                val parts = out.split(Regex("\\s+"))
                if (parts.size == 2) {
                    val rows = parts[0].toIntOrNull(); val cols = parts[1].toIntOrNull()
                    if (rows != null && cols != null) return cols to rows
                }
            }
        } catch (_: Exception) {}
        val ec = System.getenv("COLUMNS")?.toIntOrNull()
        val er = System.getenv("LINES")?.toIntOrNull()
        if (ec != null && er != null) return ec to er
        return null
    }

    private fun renderLoop() {
        while (running.get()) {
            val pc = pendingCols
            val pr = pendingRows
            if (pc > 0 && pr > 0 && (pc != vizCols || pr != vizRows)) {
                relayout(pc, pr)
                if (VIZ_ANSI) { print("$ESC[2J"); System.out.flush() }
            }
            computeSpectrum()
            if (VIZ_ANSI) renderAnsi() else renderAscii()
            try { Thread.sleep(FRAME_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun fft() {
        val n = FFT_SIZE
        for (i in 0 until n) {
            val r = bitRev[i]
            if (r > i) {
                var tmp = fftRe[i]; fftRe[i] = fftRe[r]; fftRe[r] = tmp
                tmp = fftIm[i]; fftIm[i] = fftIm[r]; fftIm[r] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                var t = 0
                while (k < half) {
                    val wr = cosT[t]
                    val wi = sinT[t]
                    val a = i + k
                    val b = a + half
                    val xr = fftRe[b]
                    val xi = fftIm[b]
                    val tr = wr * xr - wi * xi
                    val ti = wr * xi + wi * xr
                    fftRe[b] = fftRe[a] - tr
                    fftIm[b] = fftIm[a] - ti
                    fftRe[a] += tr
                    fftIm[a] += ti
                    k++
                    t += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun computeSpectrum() {
        synchronized(lock) {
            val start = monoWrite
            for (i in 0 until FFT_SIZE) {
                fftRe[i] = monoRing[(start + i) % FFT_SIZE] * window[i]
                fftIm[i] = 0.0
            }
            snapFrames = chFrames
            System.arraycopy(chSumSq, 0, snapSums, 0, ch)
            chSumSq.fill(0.0)
            chFrames = 0
        }
        fft()
        val gain = (2.0 / (FFT_SIZE * 0.5)) / 32768.0
        val denom = CEIL_DB - FLOOR_DB
        var loudest = -400.0
        for (bnd in 0 until numBars) {
            var m = 0.0
            var b = binLo[bnd]
            val e = binHi[bnd]
            while (b <= e) {
                val re = fftRe[b]
                val im = fftIm[b]
                val p = re * re + im * im
                if (p > m) m = p
                b++
            }
            val amp = sqrt(m) * gain
            val tilt = TILT_DB * bnd / (numBars - 1).coerceAtLeast(1)
            val dbv = 20.0 * log10(amp + 1e-12) + tilt
            if (dbv > loudest) loudest = dbv
            gvDb[bnd] = dbv.toFloat()
        }
        val grv = grooveAmt
        if (grv > 0f) applyGroove(grv, loudest) else { gvReady = false }
        val release = RELEASE + (GRV_RELEASE - RELEASE) * (grv / GRV_MAX).coerceIn(0f, 1f)
        for (bnd in 0 until numBars) {
            var v = ((gvDb[bnd] - FLOOR_DB) / denom).toFloat()
            if (v < 0f) v = 0f else if (v > 1f) v = 1f
            if (v > bars[bnd]) bars[bnd] += (v - bars[bnd]) * ATTACK
            else bars[bnd] += (v - bars[bnd]) * release
            if (bars[bnd] >= peaks[bnd]) {
                peaks[bnd] = bars[bnd]
                peakVel[bnd] = 0f
            } else {
                peakVel[bnd] += PEAK_GRAV
                peaks[bnd] -= peakVel[bnd]
                if (peaks[bnd] < bars[bnd]) { peaks[bnd] = bars[bnd]; peakVel[bnd] = 0f }
            }
        }
        val frames = snapFrames
        for (c in 0 until ch) {
            val rms = if (frames > 0) sqrt(snapSums[c] / frames) / 32768.0 else 0.0
            val dbv = 20.0 * log10(rms + 1e-12)
            var v = ((dbv - METER_FLOOR) / (-METER_FLOOR)).toFloat()
            if (v < 0f) v = 0f else if (v > 1f) v = 1f
            if (v > chLevel[c]) chLevel[c] += (v - chLevel[c]) * 0.5f
            else chLevel[c] += (v - chLevel[c]) * 0.22f
            if (chLevel[c] >= chPeak[c]) chPeak[c] = chLevel[c]
            else { chPeak[c] -= 0.014f; if (chPeak[c] < chLevel[c]) chPeak[c] = chLevel[c] }
            chDb[c] = dbv.toFloat()
        }
    }

    private fun applyGroove(amount: Float, loudestDb: Double) {
        val n = numBars
        if (n < 3) return
        if (!gvReady) {
            for (b in 0 until n) gvSlow[b] = gvDb[b]
            gvReady = true
        }
        val gate = ((loudestDb - FLOOR_DB) / GRV_GATE_DB).coerceIn(0.0, 1.0).toFloat()
        if (gate <= 0f) return
        val k = amount * gate
        var slowSum = 0f
        for (b in 0 until n) {
            gvSlow[b] += (gvDb[b] - gvSlow[b]) * GRV_SLOW
            slowSum += gvSlow[b]
        }
        val slowMean = slowSum / n
        gvSum[0] = 0f
        for (b in 0 until n) gvSum[b + 1] = gvSum[b] + gvDb[b]
        val r = (n * GRV_RADIUS).roundToInt().coerceIn(1, 12)
        for (b in 0 until n) {
            val lo = (b - r).coerceAtLeast(0)
            val hi = (b + r + 1).coerceAtMost(n)
            val local = (gvSum[hi] - gvSum[lo]) / (hi - lo)
            val d = gvDb[b]
            var o = d + GRV_CONTRAST * (d - local) - GRV_WHITEN * (gvSlow[b] - slowMean)
            if (o > d + GRV_MAX_DEV) o = d + GRV_MAX_DEV
            else if (o < d - GRV_MAX_DEV) o = d - GRV_MAX_DEV
            gvDb[b] = d + (o - d) * k
        }
    }

    private fun renderAnsi() {
        if (hudDirty) { hudDirty = false; buildBottomRule() }
        updateRainbow()
        val lines = ArrayList<String>(specRows + dispCh + 7)
        lines.add(topRuleStr())
        for (r in 0 until specRows) lines.add(specRow(r))
        lines.add(axisLine())
        lines.add(regionLine())
        lines.add("")
        for (c in 0 until dispCh) lines.add(meterRow(c))
        lines.add("")
        lines.add(statusRow())
        lines.add(bottomRuleStr())
        val sb = StringBuilder(16384)
        sb.append(ESC).append("[?2026h")
        sb.append(ESC).append("[H")
        repeat(topPad) { sb.append(ESC).append("[K").append('\n') }
        for (idx in lines.indices) {
            sb.append(ESC).append("[K")
            repeat(pad) { sb.append(' ') }
            sb.append(lines[idx])
            if (idx < lines.size - 1) sb.append('\n')
        }
        sb.append(ESC).append("[J")
        sb.append(ESC).append("[?2026l")
        print(sb)
        System.out.flush()
    }

    private fun paint(sb: StringBuilder, text: String, startCol: Int, solid: String) {
        if (rainbow && VIZ_COLOR) {
            var cur = ""
            for (i in text.indices) {
                val chr = text[i]
                if (chr == ' ') {
                    if (cur.isNotEmpty()) { sb.append(RESET); cur = "" }
                    sb.append(' ')
                    continue
                }
                val col = rainbowAt(startCol + i)
                if (col != cur) { sb.append(col); cur = col }
                sb.append(chr)
            }
            if (cur.isNotEmpty()) sb.append(RESET)
        } else if (solid.isNotEmpty()) {
            sb.append(solid).append(text).append(RESET)
        } else {
            sb.append(text)
        }
    }

    private fun emit(sb: StringBuilder, cur: String, color: String, chr: Char, count: Int): String {
        var c = cur
        if (color.isEmpty()) {
            if (c.isNotEmpty()) { sb.append(RESET); c = "" }
        } else if (c != color) {
            sb.append(color); c = color
        }
        repeat(count) { sb.append(chr) }
        return c
    }

    private fun specRow(row: Int): String {
        val sb = StringBuilder(totalW + 64)
        var cur = ""
        val rfb = specRows - row
        for (bnd in 0 until numBars) {
            val filled = (bars[bnd] * specRows).roundToInt()
            val peak = (peaks[bnd] * specRows).roundToInt()
            val color: String
            val chr: Char
            when {
                filled >= rfb -> { color = if (rainbow) rowColorBand[bnd] else rowColor[row]; chr = BAR_CHAR }
                peak == rfb && peak > filled -> { color = capColor; chr = CAP_CHAR }
                else -> { color = ""; chr = ' ' }
            }
            cur = emit(sb, cur, color, chr, barW)
            if (bnd < numBars - 1) cur = emit(sb, cur, "", ' ', gap)
        }
        if (cur.isNotEmpty()) sb.append(RESET)
        return sb.toString()
    }

    private fun axisLine(): String {
        val sb = StringBuilder(totalW + 32)
        paint(sb, AXIS_CHAR.toString().repeat(totalW), pad, RULE)
        return sb.toString()
    }

    private fun regionLine(): String {
        val arr = CharArray(totalW) { ' ' }
        for ((col, lab) in regionMarks) for (k in lab.indices) if (col + k < totalW) arr[col + k] = lab[k]
        val sb = StringBuilder(totalW + 32)
        paint(sb, String(arr), pad, BAND)
        return sb.toString()
    }

    private fun meterRow(c: Int): String {
        val sb = StringBuilder(meterW + 64)
        val label = chLabel(c).take(labelArea).padEnd(labelArea)
        paint(sb, label, pad, TITLE)
        val openCol = pad + labelArea
        paint(sb, BRACKET.toString(), openCol, RULE)
        val filled = (chLevel[c] * meterW).roundToInt().coerceIn(0, meterW)
        val peakPos = (chPeak[c] * meterW).roundToInt().coerceIn(0, meterW)
        var cur = ""
        for (i in 0 until meterW) {
            val color: String
            val chr: Char
            when {
                i < filled -> { color = meterColor[i]; chr = M_FILL }
                peakPos > filled && i == peakPos - 1 -> { color = tickColor; chr = M_TICK }
                else -> { color = emptyColor; chr = M_EMPTY }
            }
            if (color != cur) { sb.append(color); cur = color }
            sb.append(chr)
        }
        if (cur.isNotEmpty()) sb.append(RESET)
        paint(sb, BRACKET.toString(), openCol + 1 + meterW, RULE)
        if (showDb) {
            val db = chDb[c].coerceAtLeast(METER_FLOOR.toFloat())
            val txt = String.format(Locale.US, " %5.1f dB", db)
            paint(sb, txt, openCol + 1 + meterW + 1, textColor)
        }
        return sb.toString()
    }

    private fun statusRow(): String {
        if (mode == Mode.CONFIRM_QUIT) return controlLine("Quit?   y = yes    n = no")
        if (mode == Mode.VOLUME) {
            val bw = (totalW - 22).coerceIn(6, 30)
            val fill = (volPct * bw / 200).coerceIn(0, bw)
            return controlLine("vol [" + "#".repeat(fill) + "-".repeat(bw - fill) + "] " + volPct + "%  up/down a/d  enter")
        }
        val s = statusMsg.take(totalW)
        if (s.isEmpty()) return ""
        if (rainbow && VIZ_COLOR) {
            val sb = StringBuilder(s.length + 64)
            paint(sb, s, pad, "")
            return sb.toString()
        }
        val glyph = s[0]
        val gcol = when (glyph) {
            '+' -> sgr("32")
            '-' -> sgr("33")
            '!' -> sgr("31")
            else -> textColor
        }
        val rest = if (s.length > 1) s.substring(1) else ""
        return "$gcol$glyph$RESET$textColor$rest$RESET"
    }

    private fun topRuleStr(): String {
        val sb = StringBuilder(totalW + 64)
        if (rainbow && VIZ_COLOR) {
            paint(sb, "-".repeat(topDashL) + topTitle + "-".repeat(topDashR), pad, "")
        } else {
            paint(sb, "-".repeat(topDashL), pad, RULE)
            paint(sb, topTitle, pad + topDashL, TITLE)
            paint(sb, "-".repeat(topDashR), pad + topDashL + topTitle.length, RULE)
        }
        return sb.toString()
    }

    private fun bottomRuleStr(): String {
        val sb = StringBuilder(totalW + 64)
        if (rainbow && VIZ_COLOR) {
            paint(sb, "-".repeat(botDashL) + botTitle + "-".repeat(botDashR), pad, "")
        } else {
            paint(sb, "-".repeat(botDashL), pad, RULE)
            paint(sb, botTitle, pad + botDashL, DIM)
            paint(sb, "-".repeat(botDashR), pad + botDashL + botTitle.length, RULE)
        }
        return sb.toString()
    }

    private fun chLabel(i: Int): String = when {
        ch == 1 -> "M"
        ch == 2 -> if (i == 0) "L" else "R"
        ch >= 6 && i < 6 -> listOf("L", "R", "C", "LFE", "Ls", "Rs")[i]
        else -> "C${i + 1}"
    }

    fun setVolumePercent(p: Int) {
        volPct = p.coerceIn(0, 200)
    }

    private fun changeVol(d: Int) {
        volPct = (volPct + d).coerceIn(0, 200)
        onVolume?.invoke(volPct / 100f)
    }

    private fun toggleGroove() {
        grooveAmt = if (grooveAmt > 0f) 0f else grooveDefault
        gvReady = false
        hudDirty = true
    }

    private fun handleKey(k: String) {
        when (mode) {
            Mode.NORMAL -> when (k) {
                "Q" -> mode = Mode.CONFIRM_QUIT
                "V" -> if (volumeEnabled) mode = Mode.VOLUME
                "G" -> toggleGroove()
                else -> {}
            }
            Mode.CONFIRM_QUIT -> when (k) {
                "Y" -> { mode = Mode.NORMAL; onQuit?.invoke() }
                "N", "ESCAPE" -> mode = Mode.NORMAL
                else -> {}
            }
            Mode.VOLUME -> when (k) {
                "UPARROW", "RIGHTARROW", "D", "ADD", "OEMPLUS" -> changeVol(5)
                "DOWNARROW", "LEFTARROW", "A", "SUBTRACT", "OEMMINUS" -> changeVol(-5)
                "ENTER", "ESCAPE", "V" -> mode = Mode.NORMAL
                "G" -> toggleGroove()
                "Q" -> mode = Mode.CONFIRM_QUIT
                else -> {}
            }
        }
    }

    private fun controlLine(text: String): String {
        val sb = StringBuilder(text.length + 64)
        paint(sb, text.take(totalW), pad, TITLE)
        return sb.toString()
    }

    private fun winKey(vk: String): String = when (vk.toIntOrNull()) {
        81 -> "Q"
        89 -> "Y"
        78 -> "N"
        86 -> "V"
        71 -> "G"
        65 -> "A"
        68 -> "D"
        38 -> "UPARROW"
        40 -> "DOWNARROW"
        37 -> "LEFTARROW"
        39 -> "RIGHTARROW"
        13 -> "ENTER"
        27 -> "ESCAPE"
        187, 107 -> "ADD"
        189, 109 -> "SUBTRACT"
        else -> ""
    }

    private fun startInput() {
        if (!VIZ_ANSI) return
        if (VIZ_IS_WINDOWS) {
            try {
                val keyCmd = "while(\$true){\$k=\$Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown');[Console]::Out.WriteLine(\$k.VirtualKeyCode);[Console]::Out.Flush()}"
                val pb = ProcessBuilder("powershell", "-NoProfile", "-Command", keyCmd)
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
                pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                val proc = pb.start()
                keyProc = proc
                val rd = proc.inputStream.bufferedReader()
                inputThread = Thread {
                    try {
                        while (running.get()) {
                            val line = rd.readLine() ?: break
                            val tok = winKey(line.trim())
                            if (tok.isNotEmpty()) handleKey(tok)
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        } else {
            try { ProcessBuilder("sh", "-c", "stty -echo -icanon min 1 time 0 < /dev/tty").start().waitFor() } catch (_: Exception) {}
            val inp = System.`in`
            inputThread = Thread {
                try {
                    while (running.get()) {
                        val b = inp.read()
                        if (b < 0) break
                        if (b == 27) {
                            if (inp.available() > 0 && inp.read().toChar() == '[' && inp.available() > 0) {
                                when (inp.read().toChar()) {
                                    'A' -> handleKey("UPARROW")
                                    'B' -> handleKey("DOWNARROW")
                                    'C' -> handleKey("RIGHTARROW")
                                    'D' -> handleKey("LEFTARROW")
                                    else -> {}
                                }
                            } else {
                                handleKey("ESCAPE")
                            }
                        } else {
                            when (b.toChar().uppercaseChar()) {
                                'Q' -> handleKey("Q")
                                'Y' -> handleKey("Y")
                                'N' -> handleKey("N")
                                'V' -> handleKey("V")
                                'G' -> handleKey("G")
                                'A' -> handleKey("A")
                                'D' -> handleKey("D")
                                '+' -> handleKey("ADD")
                                '-' -> handleKey("SUBTRACT")
                                '\n' -> handleKey("ENTER")
                                '\r' -> handleKey("ENTER")
                                else -> {}
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        inputThread?.also { it.isDaemon = true; it.start() }
    }

    private fun stopInput() {
        runCatching { keyProc?.destroyForcibly() }
        keyProc = null
        if (VIZ_ANSI && !VIZ_IS_WINDOWS) {
            try { ProcessBuilder("sh", "-c", "stty sane < /dev/tty").start().waitFor() } catch (_: Exception) {}
        }
    }

    private fun renderAscii() {
        val ramp = " .:-=+*#%@"
        val sb = StringBuilder(128)
        sb.append("\r  [")
        for (bnd in 0 until numBars) {
            sb.append(ramp[(bars[bnd] * (ramp.length - 1)).toInt().coerceIn(0, ramp.length - 1)])
        }
        sb.append("] ")
        for (c in 0 until dispCh) {
            sb.append(chLabel(c)).append((chLevel[c] * 99).toInt().coerceIn(0, 99).toString().padStart(2, '0')).append(' ')
        }
        sb.append(statusMsg.take(14))
        val line = sb.toString()
        val w = (vizCols - 1).coerceIn(40, 200)
        print(if (line.length >= w) line.substring(0, w) else line.padEnd(w))
        print("\r")
        System.out.flush()
    }
}
