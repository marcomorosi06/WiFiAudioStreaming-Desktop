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

enum class RunMode { GUI, CLI_SERVER, CLI_CLIENT, CLI_DISCOVER, CLI_CONTROL, CLI_MONITOR }

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
    val configCmd:       ConfigCommand?  = null,
    val firewallCmd:     FirewallCommand? = null,
    val networkIface:    String          = "Auto",
    val useNativeEngine: Boolean         = true,
    val viz:             Boolean         = false,
    val vizTheme:        String?         = null,
    val groove:          Float           = 0f,
    val monitor:         Boolean         = false,
    val printHelp:       Boolean         = false,
    val printVersion:    Boolean         = false,
    val printProtocol:   Boolean         = false,
    val printFred:       Boolean         = false,
    val printLicenses:   Boolean         = false,
    val debug:           Boolean         = false,
    val checkUpdate:     Boolean         = false,
    val autoCheckUpdate: String?         = null,
    val authMode:        String          = "OFF",
    val authKey:         String          = "",
    val encrypt:         Boolean         = false,
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
            var configCmd: ConfigCommand?   = null
            var firewallCmd: FirewallCommand? = null
            var networkIface    = "Auto"
            var useNativeEngine = true
            var viz             = false
            var vizTheme: String?           = null
            var groove          = 0f
            var monitor         = false
            var printHelp       = false
            var printVersion    = false
            var printProtocol   = false
            var printFred       = false
            var printLicenses   = false
            var debug           = false
            var checkUpdate     = false
            var autoCheckUpdate: String?    = null
            var authMode        = "OFF"
            var authKey         = ""
            var encrypt         = false

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

                    "config" -> {
                        modeExplicit = true
                        val sub = args.getOrNull(i + 1)?.lowercase()
                            ?: parseError("'config' requires a subcommand: list, get, set, path, edit, reset, export, import")
                        i++
                        configCmd = when (sub) {
                            "list", "ls", "show" -> ConfigCommand.List
                            "path", "where"      -> ConfigCommand.Path
                            "reset"              -> ConfigCommand.Reset
                            "edit", "open"       -> ConfigCommand.Edit
                            "get" -> {
                                val key = args.getOrNull(i + 1) ?: parseError("'config get' requires a key")
                                i++
                                ConfigCommand.Get(key)
                            }
                            "set" -> {
                                val key   = args.getOrNull(i + 1) ?: parseError("'config set' requires a key and a value")
                                val value = args.getOrNull(i + 2) ?: parseError("'config set $key' requires a value")
                                i += 2
                                ConfigCommand.Set(key, value)
                            }
                            "export", "save" -> {
                                val p = args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
                                if (p != null) i++
                                ConfigCommand.Export(p)
                            }
                            "import", "load" -> {
                                val p = args.getOrNull(i + 1) ?: parseError("'config import' requires a file path")
                                i++
                                ConfigCommand.Import(p)
                            }
                            else -> parseError("Unknown config subcommand '$sub'. Valid: list, get, set, path, edit, reset, export, import")
                        }
                    }

                    "firewall", "fw" -> {
                        modeExplicit = true
                        val sub = args.getOrNull(i + 1)?.lowercase()
                        firewallCmd = when {
                            sub == "status" -> { i++; FirewallCommand.Status }
                            sub == "allow" || sub == "open" || sub == "enable" -> {
                                i++
                                val p = args.getOrNull(i + 1)?.takeIf { !it.startsWith("-") }
                                if (p != null) i++
                                FirewallCommand.Allow(p?.split(Regex("[^0-9]+"))?.mapNotNull { it.toIntOrNull() } ?: emptyList())
                            }
                            sub == null -> FirewallCommand.Allow(emptyList())
                            sub.any { it.isDigit() } -> {
                                i++
                                FirewallCommand.Allow(sub.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() })
                            }
                            else -> parseError("Unknown firewall subcommand '$sub'. Valid: allow [ports], status")
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
                    "--groove" -> {
                        groove = 1f
                        val nv = args.getOrNull(i + 1)
                        if (nv != null && !nv.startsWith("-")) {
                            groove = when (nv.lowercase()) {
                                "soft", "subtle", "low"  -> 0.5f
                                "normal", "mid", "auto"  -> 1f
                                "hard", "strong", "high" -> 1.5f
                                else -> {
                                    val n = nv.toFloatOrNull()
                                        ?: parseError("--groove value must be soft, normal, hard or a number 0-160, got '$nv'")
                                    if (n < 0f || n > 160f) parseError("--groove value $nv is out of range (0-160)")
                                    n / 100f
                                }
                            }
                            i++
                        }
                    }
                    "--monitor", "--listen" -> monitor = true
                    "--help", "-h"     -> printHelp     = true
                    "--version", "-v"  -> printVersion  = true
                    "--protocol"       -> printProtocol = true
                    "--licenses", "--license", "--credits" -> printLicenses = true
                    "--fred", "--Fred" -> printFred     = true
                    "--debug"          -> debug         = true
                    "--auth-mode" -> {
                        val v = nextArg(args, i, "--auth-mode") ?: parseError("--auth-mode requires a value: off, ask or key")
                        i++
                        authMode = when (v.lowercase()) {
                            "off"  -> "OFF"
                            "ask"  -> "ASK"
                            "key"  -> "KEY"
                            else   -> parseError("--auth-mode must be off, ask or key, got '$v'")
                        }
                    }
                    "--auth-key" -> {
                        val v = nextArg(args, i, "--auth-key") ?: parseError("--auth-key requires a value")
                        i++
                        authKey = v
                        if (authMode == "OFF") authMode = "KEY"
                    }
                    "--encrypt" -> {
                        encrypt = true
                        if (authMode == "OFF") authMode = "KEY"
                    }
                    "--check-update", "--check-updates" -> checkUpdate = true
                    "--auto-check-update", "--auto-check-updates" -> {
                        val v = nextArg(args, i, "--auto-check-update") ?: parseError("--auto-check-update requires a value: on or off")
                        i++
                        autoCheckUpdate = when (v.lowercase()) {
                            "on", "true", "enable", "enabled"   -> "on"
                            "off", "false", "disable", "disabled" -> "off"
                            else -> parseError("--auto-check-update value must be 'on' or 'off', got '$v'")
                        }
                    }

                    else -> parseError("Unknown argument '$token'. Run 'wfas --help' for usage.")
                }
                i++
            }

            if (rtp || http) multicast = true

            if (monitor) {
                if (!viz) parseError("--monitor requires --viz")
                runMode = RunMode.CLI_MONITOR
            }

            if (groove > 0f && !viz) parseError("--groove requires --viz")

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
                configCmd       = configCmd,
                firewallCmd     = firewallCmd,
                networkIface    = networkIface,
                useNativeEngine = useNativeEngine,
                viz             = viz,
                vizTheme        = vizTheme,
                groove          = groove,
                monitor         = monitor,
                printHelp       = printHelp,
                printVersion    = printVersion,
                printProtocol   = printProtocol,
                printFred       = printFred,
                printLicenses   = printLicenses,
                debug           = debug,
                checkUpdate     = checkUpdate,
                autoCheckUpdate = autoCheckUpdate,
                authMode        = authMode,
                authKey         = authKey,
                encrypt         = encrypt,
            )
        }

        fun printHelp() {
            val files = ConfigPaths.describeForHelp()
            val fileWidth = files.maxOf { it.second.length }
            val filesBlock = files.joinToString("\n") { (label, path) ->
                "  ${path.padEnd(fileWidth)}  $label"
            }
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
  --auth-mode <m>     Connection authorization: off | ask | key  (default: off;
                      unicast only). 'ask' prompts on the terminal per client.
  --auth-key <key>    Pre-shared key (implies --auth-mode key). The key is never
                      sent on the wire (mutual HMAC challenge-response).
  --encrypt           Encrypt the audio with ChaCha20-Poly1305 (implies a key;
                      use with --auth-key). See --protocol for the wire details.
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

CONFIGURATION  (wfas config <command>)
  Persistent settings live in a single config.json shared by the CLI and the
  GUI. Changes apply the next time a server/client starts or the GUI opens.
  list                Show every setting and its current value
  get <key>           Print one setting (e.g. audio.sampleRate)
  set <key> <value>   Change one setting and save it
  path                Print the config.json path for this system
  edit                Open config.json in your default editor (${'$'}EDITOR)
  reset               Restore all settings to their defaults
  export [file]       Write the config to <file> (or stdout if omitted)
  import <file>       Load a config.json and make it active
  Add --json to any config command for machine-readable output.

FIREWALL  (wfas firewall <command>)   [Windows only]
  Opens the inbound UDP ports so clients can reach this machine, exactly like
  the button in the GUI settings. Prompts once for administrator approval.
  allow [ports]       Allow inbound UDP. With no ports, opens the configured
                      streaming, discovery (9091) and mic ports. Or pass a list,
                      e.g. 'wfas firewall allow 9090,9091'
  status              Show whether the WFAS firewall rule is active
  Add --json for machine-readable output.

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
  --groove [amount]   Only with --viz: adaptive spectrum. Instead of drawing
                      raw levels (where bass pins the low bars at full scale
                      and everything else flattens into one blob) each band is
                      compared with its frequency neighbours, so a note that
                      pokes out of its region is lifted, and a slow per-band
                      envelope is subtracted, so the constant part of the mix
                      stops dominating. Optional amount: soft | normal | hard,
                      or 0-160. Press 'g' in the visualizer to toggle it live.
  --monitor           Only with --viz: no server, just visualize the system
                      audio (loopback) without lowering the system volume.
                      Alias: --listen
  --debug             Live debug HUD: audio packet table + microphone
                      send/receive table (with --mic), then internal logs
  --protocol          Explain the WFAS v2 wire protocol and exit
  --licenses          Show third-party open-source licenses and exit
  --check-update      Check GitHub for a newer release and exit
  --auto-check-update on|off
                      Enable or disable the automatic update check at startup
  --help              Show this help
  --version           Show version

EXAMPLES
  wfas --server                           # start CLI audio server, default settings
  wfas --mode server --rtp --sdp          # server + RTP, print SDP
  wfas --connect 192.168.1.5              # connect to a specific server
  wfas --mode discover --json             # scan and output JSON
  wfas control volume 75                  # set volume on running instance
  wfas config set audio.sampleRate 44100  # change a setting (GUI + CLI)
  wfas config list                        # show every setting and its value
  wfas config path                        # print the config.json path
  wfas firewall allow                     # open the default ports in the firewall
  wfas firewall status                    # check if the firewall rule is active
  wfas --gui --mode server --multicast    # open GUI, start server immediately
  wfas --viz rainbow                      # spectrum with animated rainbow colors
  wfas --viz "#1e88e5"                    # spectrum themed from a hex color
  wfas --viz --monitor                    # spectrogram of system audio, no server
  wfas --viz --monitor --groove           # same, adaptive: follows the melody
  wfas --viz --groove hard                # maximum contrast between notes
  wfas --protocol                         # print the WFAS v2 protocol reference

FILES
$filesBlock
  Override the settings file with --config <path>, or print it with
  'wfas config path'.

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

  Payload length is chosen by the server, per packet: a whole number of frames
  (channels x 2 bytes), never more than MTU - 10 bytes (~1390 on a 1500 MTU). A
  receiver must accept any size within these bounds; it can be tuned smaller, e.g.
  for constrained / embedded receivers. Every server must fill seq and samplePos
  on every packet (monotonic), so receivers detect loss / reorder and conceal
  gaps by the exact missing duration.

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

SECURITY  (optional, unicast only)
  An optional server-side toggle gates who may connect (audio packets unchanged):
    off    any client that completes the handshake streams
    ask    the server user approves each client; the server replies WFAS_PENDING
           (re-sent ~2s as keep-alive) until Allow -> HELLO_ACK or Deny -> WFAS_UNAUTHORIZED
    key    pre-shared key, mutual HMAC-SHA256 challenge-response; the key never
           travels on the wire and both ends are authenticated:
             client -> HELLO_FROM_CLIENT;v=2;cnonce=C
             server -> WFAS_AUTH_REQUIRED;snonce=S;sproof=HMAC(K,"WFAS-S:"+C+":"+S)
             client -> HELLO_FROM_CLIENT;v=2;cnonce=C;cproof=HMAC(K,"WFAS-C:"+C+":"+S)
             server -> HELLO_ACK  or  WFAS_UNAUTHORIZED
  The discovery beacon is NOT trusted (it carries at most auth=/enc= hints); a
  client that requires a key aborts unless the key exchange actually happened, so
  a spoofed "no security" cannot downgrade it.

ENCRYPTION  (optional, requires a key)
  Authentication decides WHO connects; encryption protects WHAT is sent. With a
  pre-shared key the PCM payload is sealed per packet with ChaCha20-Poly1305
  (RFC 8439); session keys come from HKDF-SHA256 over the handshake nonces
  (unicast) or a random per-session salt announced in a signed beacon (multicast).
    packet  [header 10B (AAD)] [counter 8B] [ciphertext] [Poly1305 tag 16B]
    nonce   per-direction prefix(4B) || counter(8B), flagged ENCRYPTED(0x02)
  Each packet adds 24B (counter+tag), so the payload cap drops accordingly to stay
  under the MTU. Receivers run a 1024-wide anti-replay window (verify tag, then
  advance). Multicast beacon (clear, HMAC'd):
    WFAS_MCAST_ENC;epoch=N;time=T;salt=HEX;mac=HMAC(K,"WFAS-MCAST:epoch=N;time=T;salt=HEX")
  epoch is a server-persisted monotonic counter; clients reject epoch <= last seen,
  defeating whole-session replay without needing a synced clock. Multicast uses one
  shared group key, so it assumes mutual trust among members (no source signatures).

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
