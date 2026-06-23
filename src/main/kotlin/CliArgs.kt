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
    val printProtocol:   Boolean         = false,
    val printFred:       Boolean         = false,
    val printLicenses:   Boolean         = false,
    val debug:           Boolean         = false,
) {
    companion object {

        private val VERSION: String by lazy {
            val raw = runCatching {
                CliArgs::class.java.getResourceAsStream("/version.properties")
                    ?.bufferedReader()
                    ?.lineSequence()
                    ?.firstOrNull { it.startsWith("app.version=") }
                    ?.removePrefix("app.version=")
                    ?.trim()
            }.getOrNull() ?: "unknown"
            displayVersion(raw)
        }

        fun parse(args: Array<String>): CliArgs {
            val isHeadless = GraphicsEnvironment.isHeadless()

            if (args.isEmpty() && isHeadless)
                return CliArgs(runMode = RunMode.CLI_SERVER)
            if (args.isEmpty())
                return CliArgs(runMode = RunMode.GUI)

            var runMode         = RunMode.CLI_SERVER
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
            var printProtocol   = false
            var printFred       = false
            var printLicenses   = false
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

                    "--connect" -> {
                        serverIp = nextArg(args, i, "--connect") ?: parseError("--connect requires an IP address")
                        i++
                        if (!modeExplicit) runMode = RunMode.CLI_CLIENT
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
                    "--no-native-engine", "--legacy-engine" -> useNativeEngine = false

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
                    "--help", "-h"     -> printHelp     = true
                    "--version", "-v"  -> printVersion  = true
                    "--protocol"       -> printProtocol = true
                    "--licenses", "--license", "--credits" -> printLicenses = true
                    "--fred", "--Fred" -> printFred     = true
                    "--debug"          -> debug         = true

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
                printProtocol   = printProtocol,
                printFred       = printFred,
                printLicenses   = printLicenses,
                debug           = debug,
            )
        }

        fun printHelp() {
            println("""
WiFi Audio Streaming ${VERSION} (c) 2026 Marco Morosi - Stream audio over your local network.

USAGE
  wfas [--gui | --cli] [--mode server|client|discover] [OPTIONS]
  wfas control <command>          (see RUNTIME CONTROL)

ENTRY POINT
  (no flags)          Show this help
  --cli               CLI mode (audio server by default)
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
  --legacy-engine     Use the legacy FFmpeg grabber instead of the native C
                      audio engine. Native engine is the default on all platforms:
                        Windows  WASAPI loopback (no virtual driver needed)
                        macOS    ScreenCaptureKit
                        Linux    PulseAudio/PipeWire via dlopen
                      Use --legacy-engine on Linux if PulseAudio is unavailable
                      or for compatibility with older setups.

CLIENT OPTIONS
  --connect <ip>      Server IP to connect to      (auto-discover if omitted;
                      implies client mode)
  --output <name>     Audio output device name     (system default if omitted)

MIC OPTIONS  (client-to-server return channel)
  Sends the client's microphone back to the server (talkback), independent
  of the main server-to-client audio stream.
  --mic               Enable microphone return
  --mic-input <name>  Microphone device name
  --mic-routing <m>   mix | virtual | off          (default: mix when --mic)
                        mix      blend mic into the server's captured audio
                        virtual  expose mic on the server as a virtual device
                        off      disable

AUDIO
  --volume <0-100>    Initial volume percentage    (default: 100)
  --mute              Start muted

RUNTIME CONTROL  (wfas control <command>)
  volume <0-100>      Set output volume
  mute | unmute       Toggle audio output
  stop                Stop the running instance
  status              Show current streaming status

DISCOVER OPTIONS
  --watch             Keep scanning (live update)

GLOBAL OPTIONS
  --interface <name>  Network interface            (default: Auto)
  --config <path>     Alternate settings file
  --json              All output as JSON
  --quiet             Suppress logs, only errors to stderr
  --viz [theme]       Animated ASCII spectrum histogram of the audio stream.
                      Optional theme: a hex color (e.g. #1e88e5) recolors the
                      whole view via the Material You palette, or 'rainbow'
                      for an animated dynamic rainbow.
  --debug             Live debug HUD: audio packet table + microphone
                      send/receive table (with --mic), then internal logs
  --protocol          Explain the WFAS v2 wire protocol and exit
  --licenses          Show third-party open-source licenses and exit
  --help              Show this help
  --version           Show version

EXAMPLES
  wfas --server                           # start CLI audio server, default settings
  wfas --mode server --rtp --sdp          # server + RTP, print SDP
  wfas --connect 192.168.1.5              # connect to a specific server
  wfas --mode discover --json             # scan and output JSON
  wfas control volume 75                  # set volume on running instance
  wfas --gui --mode server --multicast    # open GUI, start server immediately
  wfas --viz rainbow                      # spectrum with animated rainbow colors
  wfas --viz "#1e88e5"                    # spectrum themed from a hex color
  wfas --protocol                         # print the WFAS v2 protocol reference

FILES
  ~/.config/wfas/settings.json    settings file (Linux/Mac)
  %APPDATA%\wfas\settings.json    settings file (Windows)
  /tmp/wfas-<pid>.port            IPC control port file (Linux/Mac)
  %TEMP%\wfas-<pid>.port          IPC control port file (Windows)
  /tmp/stream.sdp                 RTP session descriptor

See 'man wfas' for the full reference manual.

Licensed under the EUPL, Version 1.2
  Desktop source:  https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop
  Android app:     https://github.com/marcomorosi06/WiFiAudioStreaming-Android
            """.trimIndent())
        }

        fun printVersion() {
            println("wfas $VERSION")
        }

        fun printLicenses() {
            val text = runCatching {
                CliArgs::class.java.getResourceAsStream("/third_party_licenses.txt")
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                println("Third-party licenses: see THIRD_PARTY_LICENSES.md in the project repository.")
            } else {
                println(text)
            }
        }

        fun printProtocol() {
            val v = NetworkHandler_v1.WFAS_PROTOCOL_VERSION
            println("""
WFAS - WiFi Audio Streaming protocol, version $v

WFAS streams raw 16-bit PCM audio over UDP on the local network. A session has
three phases: discovery (the server announces itself), connection (handshake in
unicast, group join in multicast) and streaming (a continuous flow of audio
packets, with PING/BYE control messages interleaved on the same socket).

AUDIO PACKET  (10-byte header + PCM payload)
  byte 0    0x57 'W'   magic
  byte 1    0x46 'F'   magic
  byte 2    version    protocol version (v$v = 0x0${v})
  byte 3    flags      bit0 = silence frame
  byte 4-5  seq        big-endian uint16, wraps at 0xFFFF
  byte 6-9  samplePos  big-endian uint32, per-channel sample index
  byte 10+  PCM        signed 16-bit little-endian, interleaved by channel

  Control messages are plain ASCII and never start with 'W''F', so audio and
  control are told apart by the two magic bytes alone. The header size is
  unchanged from v1: the version byte reuses an already-reserved slot, so the
  protocol stays lightweight (zero extra bytes on the wire).

CONTROL MESSAGES  (ASCII over UDP)
  MODE_PROBE                 client -> server   "are you unicast?"
  UNICAST                    server -> client   reply to MODE_PROBE
  HELLO_FROM_CLIENT;v=<n>    client -> server   connect, carries client version
  HELLO_ACK;v=<n>            server -> client   accept, carries server version
  WFAS_INCOMPATIBLE;v=<n>    server -> client   reject: version mismatch
  PING                       server -> client   keep-alive (1s, 3s timeout)
  BYE / CLIENT_BYE                              clean disconnect

DISCOVERY  (UDP multicast 239.255.0.1:9091, every ~3s)
  WIFI_AUDIO_STREAMER_DISCOVERY;<host>;<MULTICAST|UNICAST>;<port>;protocols=...
  Advertises capabilities only; version is enforced at connection time.

VERSION NEGOTIATION  (handles both directions)
  Unicast: the client sends its version in HELLO; the server replies HELLO_ACK
  on a match, or WFAS_INCOMPATIBLE otherwise (and keeps serving other clients).
  Multicast: there is no handshake, so the client validates the version byte of
  the first audio packet it receives (the first-packet sentinel).
  On any mismatch the device running the newer build stops the stream and shows
  an "update required" notice. Whether the outdated peer is the server (sender)
  or the client (receiver), the up-to-date side is the one that reports it.

  Releases:
    Desktop  https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop/releases
    Android  https://github.com/marcomorosi06/WiFiAudioStreaming-Android/releases
            """.trimIndent())
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
