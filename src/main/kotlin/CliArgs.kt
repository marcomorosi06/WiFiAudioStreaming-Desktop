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

import java.awt.GraphicsEnvironment

enum class RunMode { GUI, CLI_SERVER, CLI_CLIENT, CLI_DISCOVER, CLI_CONTROL }

sealed class ControlCommand {
    data class Volume(val value: Float) : ControlCommand()
    object Mute   : ControlCommand()
    object Unmute : ControlCommand()
    object Stop   : ControlCommand()
    object Status : ControlCommand()
}

data class CliArgs(
    val runMode:         RunMode         = RunMode.CLI_SERVER,
    val guiInitMode:     String?         = null,
    val port:            Int             = 9090,
    val micPort:         Int             = 9092,
    val multicast:       Boolean         = false,
    val rtp:             Boolean         = false,
    val rtpPort:         Int             = 9094,
    val http:            Boolean         = false,
    val httpPort:        Int             = 8080,
    val httpSafari:      Boolean         = false,
    val serverIp:        String?         = null,
    val outputDevice:    String?         = null,
    val mic:             Boolean         = false,
    val micInput:        String?         = null,
    val micRouting:      MicRoutingMode  = MicRoutingMode.OFF,
    val volume:          Float?          = null,
    val mute:            Boolean         = false,
    val watch:           Boolean         = false,
    val json:            Boolean         = false,
    val quiet:           Boolean         = false,
    val configPath:      String?         = null,
    val sdp:             Boolean         = false,
    val sdpOut:          String?         = null,
    val controlCmd:      ControlCommand? = null,
    val networkIface:    String          = "Auto",
    val useNativeEngine: Boolean         = true,
    val viz:             Boolean         = false,
    val vizTheme:        String?         = null,
    val printHelp:       Boolean         = false,
    val printVersion:    Boolean         = false,
    val printFred:       Boolean         = false,
    val debug:           Boolean         = false,
) {
    companion object {

        private val VERSION: String by lazy {
            runCatching {
                CliArgs::class.java.getResourceAsStream("/version.properties")
                    ?.bufferedReader()
                    ?.lineSequence()
                    ?.firstOrNull { it.startsWith("app.version=") }
                    ?.removePrefix("app.version=")
                    ?.trim()
            }.getOrNull() ?: "unknown"
        }

        fun parse(args: Array<String>): CliArgs {
            val isHeadless = GraphicsEnvironment.isHeadless()

            if (args.isEmpty() && isHeadless)
                return CliArgs(runMode = RunMode.CLI_SERVER)
            if (args.isEmpty())
                return CliArgs(runMode = RunMode.GUI)

            var runMode         = if (isHeadless) RunMode.CLI_SERVER else RunMode.GUI
            var modeExplicit    = false
            var guiSubMode: String?         = null
            var port            = 9090
            var micPort         = 9092
            var multicast       = false
            var rtp             = false
            var rtpPort         = 9094
            var http            = false
            var httpPort        = 8080
            var httpSafari      = false
            var serverIp: String?           = null
            var outputDevice: String?       = null
            var mic             = false
            var micInput: String?           = null
            var micRouting      = MicRoutingMode.OFF
            var volume: Float?              = null
            var mute            = false
            var watch           = false
            var json            = false
            var quiet           = false
            var configPath: String?         = null
            var sdp             = false
            var sdpOut: String?             = null
            var controlCmd: ControlCommand? = null
            var networkIface    = "Auto"
            var useNativeEngine = true
            var viz             = false
            var vizTheme: String?           = null
            var printHelp       = false
            var printVersion    = false
            var printFred       = false
            var debug           = false

            var i = 0
            while (i < args.size) {
                when (val token = args[i]) {

                    "--gui"    -> { runMode = RunMode.GUI;           modeExplicit = true }
                    "--cli"    -> { if (!modeExplicit) runMode = RunMode.CLI_SERVER; modeExplicit = true }
                    "--server" -> { runMode = RunMode.CLI_SERVER;    modeExplicit = true }
                    "--client" -> { runMode = RunMode.CLI_CLIENT;    modeExplicit = true }

                    "--mode" -> {
                        val v = nextArg(args, i, "--mode") ?: parseError("--mode requires a value: server, client, discover")
                        i++
                        when (v.lowercase()) {
                            "server"   -> { guiSubMode = "server";   if (runMode != RunMode.GUI || !modeExplicit) runMode = RunMode.CLI_SERVER }
                            "client"   -> { guiSubMode = "client";   if (runMode != RunMode.GUI || !modeExplicit) runMode = RunMode.CLI_CLIENT }
                            "discover" -> { guiSubMode = "discover"; runMode = RunMode.CLI_DISCOVER }
                            else -> parseError("Unknown mode '$v'. Valid: server, client, discover")
                        }
                        modeExplicit = true
                    }

                    "control" -> {
                        runMode = RunMode.CLI_CONTROL
                        modeExplicit = true
                        val sub = nextArg(args, i, "control")
                        if (sub == null) parseError("'control' requires a subcommand: volume <n>, mute, unmute, stop, status")
                        i++
                        controlCmd = when (sub.lowercase()) {
                            "volume" -> {
                                val raw = nextArg(args, i, "control volume")
                                    ?: parseError("'control volume' requires a value between 0 and 100")
                                i++
                                val pct = raw.toFloatOrNull()
                                    ?: parseError("'control volume' value must be numeric, got '$raw'")
                                ControlCommand.Volume((pct / 100f).coerceIn(0f, 1f))
                            }
                            "mute"   -> ControlCommand.Mute
                            "unmute" -> ControlCommand.Unmute
                            "stop"   -> ControlCommand.Stop
                            "status" -> ControlCommand.Status
                            else -> parseError("Unknown control subcommand '$sub'. Valid: volume, mute, unmute, stop, status")
                        }
                    }

                    "--port"     -> { port    = nextInt(args, i, "--port",     1024, 65535); i++ }
                    "--mic-port" -> { micPort = nextInt(args, i, "--mic-port", 1024, 65535); i++ }

                    "--multicast"   -> multicast  = true
                    "--rtp"         -> rtp        = true
                    "--rtp-port"    -> { rtpPort  = nextInt(args, i, "--rtp-port",  1024, 65535); i++ }
                    "--http"        -> http       = true
                    "--http-port"   -> { httpPort = nextInt(args, i, "--http-port", 1024, 65535); i++ }
                    "--http-safari" -> httpSafari = true

                    "--server" -> {
                        serverIp = nextArg(args, i, "--server") ?: parseError("--server requires an IP address")
                        i++
                    }
                    "--output" -> {
                        outputDevice = nextArg(args, i, "--output") ?: parseError("--output requires a device name")
                        i++
                    }

                    "--mic"       -> mic = true
                    "--mic-input" -> {
                        micInput = nextArg(args, i, "--mic-input") ?: parseError("--mic-input requires a device name")
                        i++
                    }
                    "--mic-routing" -> {
                        val v = nextArg(args, i, "--mic-routing") ?: parseError("--mic-routing requires a value")
                        i++
                        micRouting = when (v.lowercase()) {
                            "mix", "mix-into-stream" -> MicRoutingMode.MIX_INTO_STREAM
                            "virtual", "virtual-mic" -> MicRoutingMode.VIRTUAL_MIC
                            "off"                    -> MicRoutingMode.OFF
                            else -> parseError("Unknown mic-routing '$v'. Valid: mix, virtual, off")
                        }
                    }

                    "--volume" -> {
                        val raw = nextArg(args, i, "--volume") ?: parseError("--volume requires a value 0-100")
                        i++
                        val pct = raw.toFloatOrNull() ?: parseError("--volume must be numeric, got '$raw'")
                        volume = (pct / 100f).coerceIn(0f, 1f)
                    }
                    "--mute" -> mute = true

                    "--watch" -> watch = true
                    "--json"  -> json  = true
                    "--quiet" -> quiet = true

                    "--config" -> {
                        configPath = nextArg(args, i, "--config") ?: parseError("--config requires a file path")
                        i++
                    }
                    "--sdp"     -> sdp = true
                    "--sdp-out" -> {
                        sdpOut = nextArg(args, i, "--sdp-out") ?: parseError("--sdp-out requires a file path")
                        i++
                    }

                    "--interface" -> {
                        networkIface = nextArg(args, i, "--interface") ?: parseError("--interface requires a name")
                        i++
                    }
                    "--no-native-engine" -> useNativeEngine = false

                    "--viz" -> {
                        viz = true
                        if (!modeExplicit) runMode = RunMode.CLI_SERVER
                        val nv = args.getOrNull(i + 1)
                        if (nv != null && !nv.startsWith("-")) {
                            val low = nv.lowercase()
                            if (low == "rainbow" || looksLikeHex(nv)) { vizTheme = low; i++ }
                            else parseError("--viz value must be a hex color (e.g. #1e88e5) or 'rainbow', got '$nv'")
                        }
                    }
                    "--help", "-h"     -> printHelp    = true
                    "--version", "-v"  -> printVersion = true
                    "--fred", "--Fred" -> printFred    = true
                    "--debug"          -> debug        = true

                    else -> parseError("Unknown argument '$token'. Run 'wfas --help' for usage.")
                }
                i++
            }

            if (rtp || http) multicast = true

            return CliArgs(
                runMode         = runMode,
                guiInitMode     = if (runMode == RunMode.GUI) guiSubMode else null,
                port            = port,
                micPort         = micPort,
                multicast       = multicast,
                rtp             = rtp,
                rtpPort         = rtpPort,
                http            = http,
                httpPort        = httpPort,
                httpSafari      = httpSafari,
                serverIp        = serverIp,
                outputDevice    = outputDevice,
                mic             = mic,
                micInput        = micInput,
                micRouting      = if (mic && micRouting == MicRoutingMode.OFF) MicRoutingMode.MIX_INTO_STREAM else micRouting,
                volume          = volume,
                mute            = mute,
                watch           = watch,
                json            = json,
                quiet           = quiet,
                configPath      = configPath,
                sdp             = sdp,
                sdpOut          = sdpOut,
                controlCmd      = controlCmd,
                networkIface    = networkIface,
                useNativeEngine = useNativeEngine,
                viz             = viz,
                vizTheme        = vizTheme,
                printHelp       = printHelp,
                printVersion    = printVersion,
                printFred       = printFred,
                debug           = debug,
            )
        }

        fun printHelp() {
            println("""
WiFi Audio Streaming ${'$'}{VERSION} (c) stream audio over your local network.

USAGE
  wfas [--gui | --cli] [--mode server|client|discover] [OPTIONS]
  wfas control volume <0-100> | mute | unmute | stop | status

ENTRY POINT
  (no flags)          Show this help
  --cli               CLI mode (server by default)
  --gui               GUI mode

MODES
  --server            Start as audio source  (shorthand for --mode server)
  --client            Start as audio receiver (shorthand for --mode client)
  --mode server       Start as audio source
  --mode client       Start as audio receiver
  --mode discover     Scan the network for active servers

SERVER OPTIONS
  --port <n>          WFAS streaming port         (default: 9090)
  --mic-port <n>      Microphone return port       (default: 9092)
  --multicast         Enable multicast mode
  --rtp               Enable RTP protocol          (implies --multicast)
  --rtp-port <n>      RTP port                     (default: 9094)
  --http              Enable HTTP stream            (implies --multicast)
  --http-port <n>     HTTP port                    (default: 8080)
  --http-safari       Enable Safari-compatible HLS
  --sdp               Print stream.sdp to stdout when server starts
  --sdp-out <path>    Write stream.sdp to file     (e.g. /tmp/stream.sdp)

CLIENT OPTIONS
  --server <ip>       Server IP to connect to      (auto-discover if omitted)
  --output <name>     Audio output device name     (system default if omitted)

MIC OPTIONS  (server and client)
  --mic               Enable microphone
  --mic-input <name>  Microphone device name
  --mic-routing <m>   mix | virtual | off          (default: mix when --mic)

AUDIO
  --volume <0-100>    Initial volume percentage    (default: 100)
  --mute              Start muted

RUNTIME CONTROL  (sends command to running instance)
  wfas control volume <0-100>
  wfas control mute | unmute
  wfas control stop
  wfas control status

DISCOVER OPTIONS
  --watch             Keep scanning (live update)
  --json              Machine-readable JSON output

GLOBAL OPTIONS
  --interface <name>  Network interface            (default: Auto)
  --config <path>     Alternate settings file
  --json              All output as JSON
  --quiet             Suppress logs, only errors to stderr
  --no-native-engine  Force FFmpeg backend
  --viz [theme]       Animated ASCII spectrum histogram of the audio stream.
                      Optional theme: a hex color (e.g. #1e88e5) recolors the
                      whole view via the Material You palette, or 'rainbow'
                      for an animated dynamic rainbow.
  --debug             Print internal debug logs
  --help              Show this help
  --version           Show version

EXAMPLES
  wfas                                    # start CLI server, default settings
  wfas --mode server --rtp --sdp          # server + RTP, print SDP
  wfas --mode client --server 192.168.1.5 # connect to specific server
  wfas --mode discover --json             # scan and output JSON
  wfas control volume 75                  # set volume on running instance
  wfas --gui --mode server --multicast    # open GUI, start server immediately
  wfas --viz rainbow                      # spectrum with animated rainbow colors
  wfas --viz "#1e88e5"                    # spectrum themed from a hex color

FILES
  ~/.config/wfas/settings.json    settings file (Linux/Mac)
  %APPDATA%\wfas\settings.json    settings file (Windows)
  /tmp/wfas-<pid>.sock            IPC socket (Linux/Mac)
  /tmp/stream.sdp                 RTP session descriptor

See 'man wfas' for the full reference manual.

Licensed under the EUPL, Version 1.2
  Desktop source:  https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop
  Android app:     https://github.com/marcomorosi06/WiFiAudioStreaming-Android/releases
            """.trimIndent())
        }

        fun printVersion() {
            println("wfas ${'$'}{VERSION}")
        }

        fun printFred() {
            val ESC = 27.toChar().toString()
            val img = try {
                CliArgs::class.java.getResourceAsStream("/derF.jpeg")?.use { javax.imageio.ImageIO.read(it) }
            } catch (_: Exception) {
                null
            }
            if (img == null) {
                val raw = CliArgs::class.java.getResourceAsStream("/fred.ans")?.readBytes() ?: return
                print("${ESC}[?7l")
                System.out.flush()
                System.out.write(raw)
                print("\n${ESC}[?7h${ESC}[0m")
                System.out.flush()
                return
            }
            val (tw, th) = fredTerminalSize()
            val availW = (tw - 1).coerceIn(10, 200)
            val availH = (th - 2).coerceIn(4, 120)
            val iw = img.width.coerceAtLeast(1)
            val ih = img.height.coerceAtLeast(1)
            val maxWByH = (availH.toDouble() * 2.0 * iw / ih).toInt()
            val cols = minOf(availW, maxWByH).coerceIn(10, 200)
            var ph = Math.round(cols.toDouble() * ih / iw).toInt().coerceAtLeast(2)
            if (ph % 2 != 0) ph += 1
            val scaled = java.awt.image.BufferedImage(cols, ph, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val gfx = scaled.createGraphics()
            gfx.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            gfx.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
            gfx.drawImage(img, 0, 0, cols, ph, null)
            gfx.dispose()
            val out = StringBuilder(cols * ph * 20)
            out.append("${ESC}[?7l")
            var y = 0
            while (y < ph) {
                var lastTop = -1
                var lastBot = -1
                for (x in 0 until cols) {
                    val top = scaled.getRGB(x, y)
                    val bot = scaled.getRGB(x, (y + 1).coerceAtMost(ph - 1))
                    if (top != lastTop) {
                        out.append("${ESC}[38;2;${(top shr 16) and 0xFF};${(top shr 8) and 0xFF};${top and 0xFF}m")
                        lastTop = top
                    }
                    if (bot != lastBot) {
                        out.append("${ESC}[48;2;${(bot shr 16) and 0xFF};${(bot shr 8) and 0xFF};${bot and 0xFF}m")
                        lastBot = bot
                    }
                    out.append('▀')
                }
                out.append("${ESC}[0m\n")
                y += 2
            }
            out.append("${ESC}[?7h${ESC}[0m\n")
            runCatching { System.setOut(java.io.PrintStream(System.out, true, "UTF-8")) }
            print(out)
            System.out.flush()
        }

        private fun fredTerminalSize(): Pair<Int, Int> {
            val ec = System.getenv("COLUMNS")?.toIntOrNull()
            val er = System.getenv("LINES")?.toIntOrNull()
            if (ec != null && er != null && ec in 10..500 && er in 5..300) return ec to er
            try {
                val isWin = System.getProperty("os.name", "").lowercase().contains("win")
                if (isWin) {
                    val tmp = java.io.File(System.getProperty("java.io.tmpdir"), "wfas_fred_sz.txt")
                    runCatching { tmp.delete() }
                    val cmd = "[Console]::WindowWidth.ToString()+' '+[Console]::WindowHeight.ToString() | Set-Content -LiteralPath '" + tmp.absolutePath + "' -Encoding ascii"
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
                        .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
                    val parts = o.split(Regex("\\s+"))
                    val h = parts.getOrNull(0)?.toIntOrNull()
                    val w = parts.getOrNull(1)?.toIntOrNull()
                    if (w != null && h != null) return w to h
                }
            } catch (_: Exception) {}
            return 72 to 24
        }

        private fun looksLikeHex(s: String): Boolean {
            val h = s.removePrefix("#")
            return (h.length == 3 || h.length == 6) && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        }

        private fun nextArg(args: Array<String>, i: Int, flag: String): String? =
            args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") && it != "control" }

        private fun nextInt(args: Array<String>, i: Int, flag: String, min: Int, max: Int): Int {
            val raw = args.getOrNull(i + 1) ?: parseError("$flag requires a numeric value")
            val n   = raw.toIntOrNull()      ?: parseError("$flag requires a number, got '$raw'")
            if (n < min || n > max) parseError("$flag value $n is out of range ($min-$max)")
            return n
        }

        private fun parseError(msg: String): Nothing {
            System.err.println("wfas: $msg")
            System.err.println("Run 'wfas --help' for usage.")
            kotlin.system.exitProcess(1)
        }
    }
}
