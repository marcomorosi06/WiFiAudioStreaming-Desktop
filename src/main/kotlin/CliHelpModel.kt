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

data class HelpEntry(
    val syntax: String,
    val brief: String,
    val default: String? = null,
    val detail: String? = null,
    val tokens: List<String> = emptyList()
)

data class HelpBlock(
    val heading: String? = null,
    val intro: String? = null,
    val entries: List<HelpEntry> = emptyList(),
    val outro: String? = null
)

data class HelpTopic(
    val key: String,
    val title: String,
    val tagline: String,
    val aliases: List<String> = emptyList(),
    val intro: String? = null,
    val blocks: List<HelpBlock> = emptyList(),
    val examples: List<Pair<String, String>> = emptyList(),
    val seeAlso: List<String> = emptyList()
)

object CliHelpModel {

    const val TAGLINE = "Stream audio over your local network."

    val QUICK: List<Pair<String, String>> = listOf(
        "wfas --server"       to "stream this machine's audio",
        "wfas --client"       to "play audio from a server",
        "wfas --gui"          to "open the desktop app",
        "wfas --connect <ip>" to "join a specific server"
    )

    val SYNOPSIS: List<String> = listOf(
        "wfas [--gui | --cli] [--mode server|client|discover] [OPTIONS]",
        "wfas control <command>",
        "wfas config <command>",
        "wfas devices [--json]",
        "wfas pair <command>",
        "wfas firewall <command>",
        "wfas --help [<topic> | all]"
    )

    val LINKS: List<Pair<String, String>> = listOf(
        "Desktop source"   to "https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop",
        "Android app"      to "https://github.com/marcomorosi06/WiFiAudioStreaming-Android",
        "WFAS v2 protocol" to "https://github.com/marcomorosi06/wfas-protocol"
    )

    private val START = HelpTopic(
        key = "start",
        title = "Getting started",
        tagline = "modes, GUI vs CLI, discovery",
        aliases = listOf("basics", "modes", "gui"),
        intro = "One executable serves three roles: it captures this machine's audio and sends it " +
                "out (server), it receives audio from another machine and plays it (client), or it " +
                "just scans the network to see who is already streaming (discover). Without any " +
                "flag wfas decides on its own: launched from a terminal it prints a short hint, " +
                "launched by a double click it opens the desktop app.",
        blocks = listOf(
            HelpBlock(
                heading = "Entry point",
                entries = listOf(
                    HelpEntry(
                        syntax = "(no flags)",
                        brief = "From a terminal, print a short hint. Otherwise open the GUI."
                    ),
                    HelpEntry(
                        syntax = "--gui",
                        brief = "Open the desktop app.",
                        tokens = listOf("--gui"),
                        detail = "Can be combined with a mode to open the window and start straight " +
                                 "away, as in 'wfas --gui --mode server --multicast'."
                    ),
                    HelpEntry(
                        syntax = "--cli",
                        brief = "Stay in the terminal. Runs an audio server unless another mode is given.",
                        tokens = listOf("--cli")
                    ),
                    HelpEntry(
                        syntax = "--no-tray",
                        brief = "Start the GUI without the Linux tray icon.",
                        tokens = listOf("--no-tray"),
                        detail = "Use this if the app segfaults inside libgtk-3 on launch; the tray is " +
                                 "the only part that touches GTK. Same as WFAS_NO_TRAY=1, or " +
                                 "'wfas config set ui.linuxTray OFF' to make it permanent."
                    )
                )
            ),
            HelpBlock(
                heading = "Modes",
                entries = listOf(
                    HelpEntry(
                        syntax = "--server",
                        brief = "Start as audio source. Shorthand for --mode server.",
                        tokens = listOf("--server")
                    ),
                    HelpEntry(
                        syntax = "--client",
                        brief = "Start as audio receiver. Shorthand for --mode client.",
                        tokens = listOf("--client")
                    ),
                    HelpEntry(
                        syntax = "--mode <m>",
                        brief = "server | client | discover",
                        tokens = listOf("--mode"),
                        detail = "server    capture this machine's audio and serve it\n" +
                                 "client    receive audio from a server and play it\n" +
                                 "discover  scan the network for active servers, then exit"
                    )
                )
            ),
            HelpBlock(
                heading = "Discovery",
                intro = "Discovery is passive: servers announce themselves on the multicast group, " +
                        "so nothing is probed or port-scanned. Each entry reports the transport it " +
                        "was seen on and its address family.",
                entries = listOf(
                    HelpEntry(
                        syntax = "--watch",
                        brief = "Keep scanning and update the list live instead of exiting after one pass.",
                        tokens = listOf("--watch")
                    )
                )
            )
        ),
        examples = listOf(
            "wfas --server"                   to "start a server with the saved settings",
            "wfas --client"                   to "receive from the first server found",
            "wfas --mode discover"            to "list the servers on this network",
            "wfas --mode discover --watch"    to "keep the list updating",
            "wfas --mode discover --json"     to "scan and output JSON",
            "wfas --gui --mode server --multicast" to "open the GUI and start serving immediately"
        ),
        seeAlso = listOf("server", "client", "network")
    )

    private val SERVER = HelpTopic(
        key = "server",
        title = "Server options",
        tagline = "ports, capture engine, SDP",
        aliases = listOf("source", "send"),
        intro = "The server captures what this machine is playing and sends it out over the native " +
                "WFAS protocol. Everything here is about that native stream; the extra protocols " +
                "that can run alongside it live under 'Streaming protocols'.",
        blocks = listOf(
            HelpBlock(
                heading = "Ports and delivery",
                entries = listOf(
                    HelpEntry(
                        syntax = "--port <n>",
                        brief = "WFAS streaming port.",
                        default = "9090",
                        tokens = listOf("--port")
                    ),
                    HelpEntry(
                        syntax = "--mic-port <n>",
                        brief = "Port the microphone return channel comes back on.",
                        default = "9092",
                        tokens = listOf("--mic-port")
                    ),
                    HelpEntry(
                        syntax = "--multicast",
                        brief = "Serve to every listener on the group instead of one unicast peer.",
                        tokens = listOf("--multicast"),
                        detail = "Implied by --rtp, --http, --dlna and --snapcast. Note that " +
                                 "--auth-mode applies to unicast only."
                    ),
                    HelpEntry(
                        syntax = "--interface <name>",
                        brief = "Network interface to bind and announce on.",
                        default = "Auto",
                        tokens = listOf("--interface")
                    )
                )
            ),
            HelpBlock(
                heading = "Capture engine",
                entries = listOf(
                    HelpEntry(
                        syntax = "--legacy-engine",
                        brief = "Use the legacy FFmpeg grabber instead of the native C audio engine.",
                        tokens = listOf("--legacy-engine", "--no-native-engine"),
                        detail = "The native engine is the default on all platforms:\n" +
                                 "  Windows  WASAPI loopback (no virtual driver needed)\n" +
                                 "  macOS    ScreenCaptureKit\n" +
                                 "  Linux    PulseAudio/PipeWire via dlopen\n" +
                                 "Use --legacy-engine on Linux if PulseAudio is unavailable, or for " +
                                 "compatibility with older setups. Alias: --no-native-engine."
                    )
                )
            ),
            HelpBlock(
                heading = "Session descriptor",
                intro = "An SDP file describes the stream well enough for VLC, ffplay or any RTP " +
                        "receiver to open it. Only meaningful together with --rtp.",
                entries = listOf(
                    HelpEntry(
                        syntax = "--sdp",
                        brief = "Print stream.sdp to stdout when the server starts.",
                        tokens = listOf("--sdp")
                    ),
                    HelpEntry(
                        syntax = "--sdp-out <path>",
                        brief = "Write stream.sdp to a file, for example /tmp/stream.sdp.",
                        tokens = listOf("--sdp-out")
                    )
                )
            )
        ),
        examples = listOf(
            "wfas --server"                  to "start with the saved settings",
            "wfas --server --port 9500"      to "serve on a non-default port",
            "wfas --server --multicast"      to "serve every listener on the group",
            "wfas --mode server --rtp --sdp" to "server plus RTP, printing the SDP",
            "wfas --server --legacy-engine"  to "fall back to the FFmpeg grabber"
        ),
        seeAlso = listOf("protocols", "security", "network")
    )

    private val CLIENT = HelpTopic(
        key = "client",
        title = "Client options",
        tagline = "output device, volume, microphone",
        aliases = listOf("receive", "mic", "devices", "audio"),
        intro = "The client receives a WFAS stream and plays it on a local device. With no --connect " +
                "it discovers a server by itself and joins the first one it sees.",
        blocks = listOf(
            HelpBlock(
                heading = "Connection and output",
                entries = listOf(
                    HelpEntry(
                        syntax = "--connect <ip>",
                        brief = "Server address to connect to. Implies client mode.",
                        default = "auto-discover",
                        tokens = listOf("--connect")
                    ),
                    HelpEntry(
                        syntax = "--output <name>",
                        brief = "Audio output device name.",
                        default = "system default",
                        tokens = listOf("--output"),
                        detail = "Matching is partial and case-insensitive, so a fragment such as " +
                                 "'Loopback' is enough. Run 'wfas devices' to see the exact names " +
                                 "this system reports."
                    )
                )
            ),
            HelpBlock(
                heading = "Playback",
                entries = listOf(
                    HelpEntry(
                        syntax = "--volume <0-100>",
                        brief = "Initial volume percentage.",
                        default = "100",
                        tokens = listOf("--volume")
                    ),
                    HelpEntry(
                        syntax = "--mute",
                        brief = "Start muted.",
                        tokens = listOf("--mute")
                    ),
                    HelpEntry(
                        syntax = "--latency <ms>",
                        brief = "Jitter buffer used on the Wi-Fi link.",
                        default = "120, range 0-5000",
                        tokens = listOf("--latency"),
                        detail = "Larger values survive a noisier network, smaller ones cut the delay. " +
                                 "The USB buffer is separate and is set by --usb-latency. Persists as " +
                                 "audio.latencyMs when saved with 'wfas config set'."
                    )
                )
            ),
            HelpBlock(
                heading = "Microphone return channel",
                intro = "Sends the client's microphone back to the server (talkback), independent of " +
                        "the main server-to-client audio stream.",
                entries = listOf(
                    HelpEntry(
                        syntax = "--mic",
                        brief = "Enable the microphone return channel.",
                        tokens = listOf("--mic")
                    ),
                    HelpEntry(
                        syntax = "--mic-input <name>",
                        brief = "Microphone device name. Same partial matching as --output.",
                        tokens = listOf("--mic-input")
                    ),
                    HelpEntry(
                        syntax = "--mic-routing <m>",
                        brief = "mix | virtual | off",
                        default = "mix when --mic is given",
                        tokens = listOf("--mic-routing"),
                        detail = "mix      blend the mic into the server's captured audio\n" +
                                 "virtual  expose the mic on the server as a virtual device\n" +
                                 "off      disable"
                    )
                )
            ),
            HelpBlock(
                heading = "Audio devices  (wfas devices)",
                intro = "Lists every audio device Java Sound reports on this system, with the exact " +
                        "name to hand to --output or --mic-input, what each one can do, and why any " +
                        "of them was skipped. Alias: list-devices. Add --json for machine-readable " +
                        "output.",
                entries = listOf(
                    HelpEntry(
                        syntax = "devices",
                        brief = "List the audio devices this system reports.",
                        tokens = listOf("devices", "list-devices")
                    )
                ),
                outro = "A device is normally kept only if it answers a format probe. That probe " +
                        "opens the card, so it fails whenever another audio server (PulseAudio, " +
                        "PipeWire, JACK) holds it, which is common on Linux and on Raspberry Pi. " +
                        "When no device passes, wfas keeps the full list instead of leaving you " +
                        "with nothing, and 'devices' says so explicitly."
            )
        ),
        examples = listOf(
            "wfas --client"                        to "join the first server found",
            "wfas --connect 192.168.1.5"           to "connect to a specific server",
            "wfas --client --output Loopback"      to "play into an ALSA loopback device",
            "wfas --client --volume 40"            to "start quiet",
            "wfas --client --latency 60"           to "trade robustness for a shorter delay",
            "wfas --client --mic --mic-routing mix" to "talk back into the server's mix",
            "wfas devices"                         to "list audio devices and their exact names",
            "wfas devices --json"                  to "same, for scripts"
        ),
        seeAlso = listOf("runtime", "network", "config")
    )

    private val PROTOCOLS = HelpTopic(
        key = "protocols",
        title = "Streaming protocols",
        tagline = "RTP, HTTP, DLNA, Snapcast",
        aliases = listOf("rtp", "http", "dlna", "snapcast", "multiroom"),
        intro = "Besides its own protocol the server can speak four standard ones at the same time, " +
                "so receivers that have never heard of WFAS can still play the audio. Each of these " +
                "implies --multicast. None of them is authenticated or encrypted: --auth-mode, " +
                "--auth-key and --encrypt cover the native WFAS stream only.",
        blocks = listOf(
            HelpBlock(
                heading = "RTP",
                intro = "Plain RTP for VLC, ffplay and hardware receivers. Pair it with --sdp so the " +
                        "receiver knows what it is getting.",
                entries = listOf(
                    HelpEntry(
                        syntax = "--rtp",
                        brief = "Enable the RTP protocol. Implies --multicast.",
                        tokens = listOf("--rtp")
                    ),
                    HelpEntry(
                        syntax = "--rtp-port <n>",
                        brief = "RTP port.",
                        default = "9094",
                        tokens = listOf("--rtp-port")
                    )
                )
            ),
            HelpBlock(
                heading = "HTTP",
                intro = "A plain HTTP stream any browser or media player can open by URL.",
                entries = listOf(
                    HelpEntry(
                        syntax = "--http",
                        brief = "Enable the HTTP stream. Implies --multicast.",
                        tokens = listOf("--http")
                    ),
                    HelpEntry(
                        syntax = "--http-port <n>",
                        brief = "HTTP port.",
                        default = "8080",
                        tokens = listOf("--http-port")
                    ),
                    HelpEntry(
                        syntax = "--http-safari",
                        brief = "Serve Safari-compatible AAC. Implies --http.",
                        tokens = listOf("--http-safari")
                    )
                )
            ),
            HelpBlock(
                heading = "DLNA",
                intro = "Pushes the audio to the DLNA renderers saved in the settings, so the " +
                        "renderer starts playing without anyone touching it.",
                entries = listOf(
                    HelpEntry(
                        syntax = "--dlna",
                        brief = "Push audio to the saved DLNA renderers. Implies --multicast.",
                        tokens = listOf("--dlna")
                    ),
                    HelpEntry(
                        syntax = "--dlna-port <n>",
                        brief = "DLNA media endpoint port.",
                        default = "8081",
                        tokens = listOf("--dlna-port")
                    ),
                    HelpEntry(
                        syntax = "--dlna-format <f>",
                        brief = "auto | lpcm | wav | mp3 | adts",
                        default = "auto",
                        tokens = listOf("--dlna-format")
                    )
                )
            ),
            HelpBlock(
                heading = "Snapcast",
                intro = "Acts as a Snapcast server for synchronised multiroom audio. Any snapclient " +
                        "on the network (Raspberry Pi, ESP32, Home Assistant, the Snapcast mobile " +
                        "apps) can join and stay in sync.",
                entries = listOf(
                    HelpEntry(
                        syntax = "--snapcast",
                        brief = "Act as a Snapcast server. Implies --multicast.",
                        tokens = listOf("--snapcast")
                    ),
                    HelpEntry(
                        syntax = "--snapcast-port <n>",
                        brief = "Snapcast audio stream port.",
                        default = "1704",
                        tokens = listOf("--snapcast-port")
                    ),
                    HelpEntry(
                        syntax = "--snapcast-control-port <n>",
                        brief = "Snapcast JSON-RPC control port.",
                        default = "1705",
                        tokens = listOf("--snapcast-control-port")
                    ),
                    HelpEntry(
                        syntax = "--snapcast-codec <c>",
                        brief = "pcm | flac | opus",
                        default = "pcm",
                        tokens = listOf("--snapcast-codec"),
                        detail = "flac roughly halves the bandwidth; opus needs 48000:16:2."
                    ),
                    HelpEntry(
                        syntax = "--snapcast-chunk <n>",
                        brief = "Chunk size in ms: 10 | 20 | 40 | 60",
                        default = "20",
                        tokens = listOf("--snapcast-chunk")
                    ),
                    HelpEntry(
                        syntax = "--snapcast-buffer <n>",
                        brief = "Client playback buffer in ms.",
                        default = "1000",
                        tokens = listOf("--snapcast-buffer")
                    ),
                    HelpEntry(
                        syntax = "--snapcast-name <s>",
                        brief = "Stream identifier advertised to clients.",
                        default = "default",
                        tokens = listOf("--snapcast-name")
                    )
                )
            )
        ),
        examples = listOf(
            "wfas --server --rtp --sdp"                to "RTP plus the SDP on stdout",
            "wfas --server --http"                     to "listen from a browser on port 8080",
            "wfas --server --http-safari"              to "AAC that Safari will play",
            "wfas --server --dlna"                     to "push to the saved DLNA renderers",
            "wfas --server --snapcast"                 to "synchronised multiroom audio",
            "wfas --server --snapcast --snapcast-codec flac" to "half the bandwidth, same sync"
        ),
        seeAlso = listOf("server", "security", "runtime")
    )

    private val NETWORK = HelpTopic(
        key = "network",
        title = "USB & network",
        tagline = "cable link, interfaces, IPv4/IPv6",
        aliases = listOf("usb", "link", "ip", "ipv6", "transport"),
        intro = "How the audio physically gets across: over Wi-Fi, over the USB cable, or both, and " +
                "which address family to use.",
        blocks = listOf(
            HelpBlock(
                heading = "USB link",
                entries = listOf(
                    HelpEntry(
                        syntax = "--usb",
                        brief = "Stream over the USB cable instead of Wi-Fi.",
                        tokens = listOf("--usb"),
                        detail = "Enable USB tethering on the Android phone: the phone becomes the " +
                                 "gateway and the app finds the link on its own. Lower and far " +
                                 "steadier jitter than Wi-Fi, which is what lets the buffer shrink. " +
                                 "Not available on macOS: there is no built-in RNDIS driver, so " +
                                 "Android USB tethering does not come up. Overrides the saved " +
                                 "net.usbMode for this run."
                    ),
                    HelpEntry(
                        syntax = "--no-usb",
                        brief = "Force the USB link off even if it is enabled in the config.",
                        tokens = listOf("--no-usb")
                    ),
                    HelpEntry(
                        syntax = "--usb-latency <ms>",
                        brief = "Jitter buffer used only while the USB link is up. Implies --usb.",
                        default = "20, range 5-120",
                        tokens = listOf("--usb-latency"),
                        detail = "The Wi-Fi buffer set by --latency is left untouched."
                    ),
                    HelpEntry(
                        syntax = "--usb-iface <name>",
                        brief = "Force a specific tethering interface. Implies --usb.",
                        tokens = listOf("--usb-iface"),
                        detail = "Accepts the interface name or its display name as listed by " +
                                 "--debug, or 'Auto' to restore automatic detection."
                    ),
                    HelpEntry(
                        syntax = "--wfas-mode <m>",
                        brief = "always | not-on-usb | off",
                        default = "not-on-usb",
                        tokens = listOf("--wfas-mode"),
                        detail = "When this device serves the native WFAS protocol:\n" +
                                 "  always      always, over Wi-Fi and over the cable\n" +
                                 "  not-on-usb  over Wi-Fi, but suppressed as soon as the USB link\n" +
                                 "              comes up\n" +
                                 "  off         never over Wi-Fi; only the cable and the other\n" +
                                 "              protocols remain\n" +
                                 "With 'off' and no --rtp/--http and no USB link there is nothing " +
                                 "left to serve, and the server refuses to start."
                    )
                )
            ),
            HelpBlock(
                heading = "Interface and address family",
                entries = listOf(
                    HelpEntry(
                        syntax = "--interface <name>",
                        brief = "Network interface to bind and announce on.",
                        default = "Auto",
                        tokens = listOf("--interface")
                    ),
                    HelpEntry(
                        syntax = "--ip4, --ipv4",
                        brief = "IPv4 only: bind 0.0.0.0, announce and listen on 239.255.0.1 alone.",
                        tokens = listOf("--ip4", "--ipv4")
                    ),
                    HelpEntry(
                        syntax = "--ip6, --ipv6",
                        brief = "IPv6 only: the [ff02::5746] group alone.",
                        tokens = listOf("--ip6", "--ipv6")
                    )
                ),
                outro = "Without either flag the app is dual-stack: it binds the wildcard, joins " +
                        "both groups, and picks per peer the most usable address, where a routable " +
                        "v4 or v6 beats a link-local. Force a family only to work around a broken " +
                        "network; on a v6-only LAN auto already does the right thing."
            )
        ),
        examples = listOf(
            "wfas --server --usb"                   to "serve over the USB cable",
            "wfas --server --usb --usb-latency 10"  to "USB with an aggressive buffer",
            "wfas --server --usb --wfas-mode always" to "keep Wi-Fi serving while the cable is up",
            "wfas --client --ip6"                   to "receive on an IPv6-only network",
            "wfas --server --interface eth0"        to "pin the server to one interface"
        ),
        seeAlso = listOf("server", "client", "config")
    )

    private val SECURITY = HelpTopic(
        key = "security",
        title = "Security & pairing",
        tagline = "encryption, keys, QR invites",
        aliases = listOf("auth", "encrypt", "qr", "pair", "crypto"),
        intro = "Everything in this section applies to the native WFAS protocol only. RTP, HTTP, " +
                "DLNA and Snapcast are standard protocols: their clients are never authenticated " +
                "and their audio always goes out in the clear, so enabling them alongside " +
                "--encrypt exposes the very audio you are encrypting.",
        blocks = listOf(
            HelpBlock(
                heading = "Authorization and encryption",
                entries = listOf(
                    HelpEntry(
                        syntax = "--auth-mode <m>",
                        brief = "off | ask | key",
                        default = "off, unicast only",
                        tokens = listOf("--auth-mode"),
                        detail = "off  accept anyone\n" +
                                 "ask  prompt on the terminal for each client that connects\n" +
                                 "key  require the pre-shared key"
                    ),
                    HelpEntry(
                        syntax = "--auth-key <key>",
                        brief = "Pre-shared key. Implies --auth-mode key.",
                        tokens = listOf("--auth-key"),
                        detail = "The key is never sent on the wire: both sides prove they hold it " +
                                 "through a mutual HMAC challenge-response."
                    ),
                    HelpEntry(
                        syntax = "--encrypt",
                        brief = "Encrypt the audio with ChaCha20-Poly1305.",
                        tokens = listOf("--encrypt"),
                        detail = "Requires a key, so pair it with --auth-key, or use --qr to have " +
                                 "one generated. See 'wfas --protocol' for the wire details."
                    ),
                    HelpEntry(
                        syntax = "--qr",
                        brief = "Generate a key, start encrypted, and print the pairing QR.",
                        tokens = listOf("--qr"),
                        detail = "Server mode. Implies --auth-mode key and --encrypt, so it needs no " +
                                 "--auth-key: the code carries the key it just made. While the " +
                                 "server runs, p prints a new invite and r rotates the key."
                    )
                )
            ),
            HelpBlock(
                heading = "QR pairing  (wfas pair <command>)",
                intro = "Hands a receiver everything it needs in one shot: address, port and a " +
                        "freshly generated 256-bit key. The key is never typed and never travels " +
                        "on the wire; the invite carries it and the handshake proves it. Invites " +
                        "last " + WfasPairingUri.PAIRING_TTL_SECONDS + " seconds.",
                entries = listOf(
                    HelpEntry(
                        syntax = "pair invite",
                        brief = "Generate an invite and draw it as a QR code on the terminal.",
                        tokens = listOf("pair", "qr", "invite"),
                        detail = "With a server already running, the invite is generated by that " +
                                 "instance so it matches the live session; otherwise it is " +
                                 "generated offline and saved to the config, ready for the next " +
                                 "'wfas --server'. Add --watch for a live countdown that renews " +
                                 "the code on expiry: press n for a new invite, r for a new key, " +
                                 "k to reveal the key, q to quit."
                    ),
                    HelpEntry(
                        syntax = "pair regenerate",
                        brief = "Same, but always with a brand new key. Aliases: rekey, new-key.",
                        tokens = listOf("regenerate", "rekey", "new-key"),
                        detail = "In multicast this evicts every listener still using the old key, " +
                                 "which is exactly what you want after handing the code to the " +
                                 "wrong person."
                    ),
                    HelpEntry(
                        syntax = "pair connect <link>",
                        brief = "Join using an invite link. Alias: join.",
                        tokens = listOf("connect", "join"),
                        detail = "Accepts the wifiaudio://pair?... form or the https one. Starts " +
                                 "the client with the key already applied."
                    ),
                    HelpEntry(
                        syntax = "pair inspect <link>",
                        brief = "Decode a link and show what it contains, without connecting.",
                        tokens = listOf("inspect", "check", "parse"),
                        detail = "Exits non-zero if the invite has expired. Aliases: check, parse. " +
                                 "Also available as 'wfas inspect <link>'."
                    ),
                    HelpEntry(
                        syntax = "pair encode <text>",
                        brief = "Render any text as a QR code on the terminal. Alias: render.",
                        tokens = listOf("encode", "render"),
                        detail = "Also available as 'wfas encode <text>'."
                    ),
                    HelpEntry(
                        syntax = "pair status",
                        brief = "Show pairing state, key origin and handler registration.",
                        tokens = listOf("status", "show", "info")
                    ),
                    HelpEntry(
                        syntax = "pair off",
                        brief = "Turn QR pairing off and restore the manually typed key. Alias: disable.",
                        tokens = listOf("off", "disable")
                    ),
                    HelpEntry(
                        syntax = "pair register",
                        brief = "Force-register the wifiaudio:// handler with this OS.",
                        tokens = listOf("register"),
                        detail = "Per-user, no administrator rights. Every run already does this in " +
                                 "the background, rewriting the entry when it points at a different " +
                                 "executable, so moving or reinstalling the app repairs itself. Use " +
                                 "this only to see the repair fail loudly."
                    ),
                    HelpEntry(
                        syntax = "pair unregister",
                        brief = "Remove the wifiaudio:// handler.",
                        tokens = listOf("unregister")
                    )
                )
            ),
            HelpBlock(
                heading = "QR rendering",
                entries = listOf(
                    HelpEntry(
                        syntax = "--no-qr",
                        brief = "Print only the link, no ASCII art.",
                        tokens = listOf("--no-qr")
                    ),
                    HelpEntry(
                        syntax = "--plain",
                        brief = "Two characters per module instead of half-blocks.",
                        tokens = listOf("--plain"),
                        detail = "Use it if the terminal font has no U+2580/U+2584. Needs twice the width."
                    ),
                    HelpEntry(
                        syntax = "--invert",
                        brief = "Invert the code for terminals with a light background.",
                        tokens = listOf("--invert")
                    ),
                    HelpEntry(
                        syntax = "--show-key",
                        brief = "Print the pairing key instead of masking it.",
                        tokens = listOf("--show-key")
                    )
                )
            )
        ),
        examples = listOf(
            "wfas --server --qr"                    to "start a server and show its pairing QR",
            "wfas pair invite --watch"              to "live QR that renews itself on expiry",
            "wfas pair invite --json"               to "the invite as JSON, for scripts",
            "wfas pair connect 'wifiaudio://pair?...'" to "join from an invite link",
            "wfas pair inspect 'wifiaudio://pair?...'" to "decode a link without connecting",
            "wfas pair regenerate"                  to "new key, evicts the old listeners",
            "wfas --server --auth-key hunter2 --encrypt" to "encrypted with a key you chose"
        ),
        seeAlso = listOf("protocols", "reference")
    )

    private val CONFIG = HelpTopic(
        key = "config",
        title = "Configuration",
        tagline = "settings, firewall, file locations",
        aliases = listOf("settings", "firewall", "files"),
        intro = "Persistent settings live in a single config.json shared by the CLI and the GUI. " +
                "Changes apply the next time a server or client starts, or the next time the GUI " +
                "opens.",
        blocks = listOf(
            HelpBlock(
                heading = "Settings  (wfas config <command>)",
                entries = listOf(
                    HelpEntry(
                        syntax = "config list",
                        brief = "Show every setting and its current value. Aliases: ls, show.",
                        tokens = listOf("config", "list", "ls", "show")
                    ),
                    HelpEntry(
                        syntax = "config get <key>",
                        brief = "Print one setting, for example audio.sampleRate.",
                        tokens = listOf("get")
                    ),
                    HelpEntry(
                        syntax = "config set <key> <value>",
                        brief = "Change one setting and save it.",
                        tokens = listOf("set")
                    ),
                    HelpEntry(
                        syntax = "config path",
                        brief = "Print the config.json path for this system. Alias: where.",
                        tokens = listOf("path", "where")
                    ),
                    HelpEntry(
                        syntax = "config edit",
                        brief = "Open config.json in your default editor. Alias: open.",
                        tokens = listOf("edit", "open")
                    ),
                    HelpEntry(
                        syntax = "config reset",
                        brief = "Restore all settings to their defaults.",
                        tokens = listOf("reset")
                    ),
                    HelpEntry(
                        syntax = "config export [file]",
                        brief = "Write the config to a file, or to stdout if omitted. Alias: save.",
                        tokens = listOf("export", "save")
                    ),
                    HelpEntry(
                        syntax = "config import <file>",
                        brief = "Load a config.json and make it active. Alias: load.",
                        tokens = listOf("import", "load")
                    )
                ),
                outro = "Add --json to any config command for machine-readable output."
            ),
            HelpBlock(
                heading = "Overrides",
                entries = listOf(
                    HelpEntry(
                        syntax = "--config <path>",
                        brief = "Use an alternate settings file for this run.",
                        tokens = listOf("--config")
                    )
                )
            ),
            HelpBlock(
                heading = "Firewall  (wfas firewall <command>)   [Windows only]",
                intro = "Opens the inbound UDP ports so clients can reach this machine, exactly like " +
                        "the button in the GUI settings. Prompts once for administrator approval. " +
                        "Alias: fw.",
                entries = listOf(
                    HelpEntry(
                        syntax = "firewall allow [ports]",
                        brief = "Allow inbound UDP.",
                        tokens = listOf("firewall", "fw", "allow"),
                        detail = "With no ports, opens the configured streaming, discovery (9091) " +
                                 "and mic ports. Or pass a list, as in " +
                                 "'wfas firewall allow 9090,9091'."
                    ),
                    HelpEntry(
                        syntax = "firewall status",
                        brief = "Show whether the WFAS firewall rule is active.",
                        tokens = listOf("status")
                    )
                ),
                outro = "Add --json for machine-readable output."
            )
        ),
        examples = listOf(
            "wfas config list"                      to "show every setting and its value",
            "wfas config set audio.sampleRate 44100" to "change a setting for both GUI and CLI",
            "wfas config get audio.latencyMs"       to "read one setting",
            "wfas config path"                      to "print the config.json path",
            "wfas config export backup.json"        to "save the current configuration",
            "wfas firewall allow"                   to "open the default ports in the firewall",
            "wfas firewall status"                  to "check if the firewall rule is active"
        ),
        seeAlso = listOf("network", "reference")
    )

    private val RUNTIME = HelpTopic(
        key = "runtime",
        title = "Runtime & monitoring",
        tagline = "control, status, visualizer, debug",
        aliases = listOf("control", "viz", "debug", "monitor", "status"),
        intro = "Talk to an instance that is already running, watch what it is doing, or look at " +
                "the audio itself.",
        blocks = listOf(
            HelpBlock(
                heading = "Runtime control  (wfas control <command>)",
                intro = "Sent over a local IPC socket to the running instance, GUI or CLI alike.",
                entries = listOf(
                    HelpEntry(
                        syntax = "control volume <0-100>",
                        brief = "Set the output volume.",
                        tokens = listOf("control", "volume")
                    ),
                    HelpEntry(
                        syntax = "control mute | unmute",
                        brief = "Toggle audio output.",
                        tokens = listOf("mute", "unmute")
                    ),
                    HelpEntry(
                        syntax = "control stop",
                        brief = "Stop the running instance.",
                        tokens = listOf("stop")
                    ),
                    HelpEntry(
                        syntax = "control status",
                        brief = "Show the current streaming status.",
                        tokens = listOf("status"),
                        detail = "Includes the RTP, HTTP, DLNA and Snapcast side-protocols and " +
                                 "their connected clients."
                    )
                )
            ),
            HelpBlock(
                heading = "Visualizer",
                entries = listOf(
                    HelpEntry(
                        syntax = "--viz [theme]",
                        brief = "Animated ASCII spectrum histogram of the audio stream.",
                        tokens = listOf("--viz"),
                        detail = "Optional theme: a hex color such as #1e88e5 recolors the whole " +
                                 "view via the Material You palette, or 'rainbow' for an animated " +
                                 "dynamic rainbow."
                    ),
                    HelpEntry(
                        syntax = "--groove [amount]",
                        brief = "Only with --viz: adaptive spectrum that follows the melody.",
                        tokens = listOf("--groove"),
                        detail = "Instead of drawing raw levels, where bass pins the low bars at " +
                                 "full scale and everything else flattens into one blob, each band " +
                                 "is compared with its frequency neighbours, so a note that pokes " +
                                 "out of its region is lifted, and a slow per-band envelope is " +
                                 "subtracted, so the constant part of the mix stops dominating. " +
                                 "Optional amount: soft | normal | hard, or 0-160. Press g in the " +
                                 "visualizer to toggle it live."
                    ),
                    HelpEntry(
                        syntax = "--monitor",
                        brief = "Only with --viz: no server, just visualize the system audio. Alias: --listen.",
                        tokens = listOf("--monitor", "--listen"),
                        detail = "Reads the loopback without lowering the system volume."
                    )
                )
            ),
            HelpBlock(
                heading = "Diagnostics and output",
                entries = listOf(
                    HelpEntry(
                        syntax = "--debug",
                        brief = "Live debug HUD, then internal logs.",
                        tokens = listOf("--debug"),
                        detail = "Audio packet table plus, with --mic, the microphone send/receive " +
                                 "table. Also lists the network interfaces by name and display name, " +
                                 "which is what --usb-iface expects."
                    ),
                    HelpEntry(
                        syntax = "--json",
                        brief = "Emit all output as JSON.",
                        tokens = listOf("--json")
                    ),
                    HelpEntry(
                        syntax = "--reveal",
                        brief = "Print secret config values instead of masking them.",
                        tokens = listOf("--reveal"),
                        detail = "Secrets such as security.authKey are shown as ******** by " +
                                 "'config list', 'config get' and 'config export', so they do not " +
                                 "end up in terminal scrollback, shell history or a shared export " +
                                 "by accident. Pass --reveal when you actually need the value, and " +
                                 "treat whatever it lands in as a secret."
                    ),
                    HelpEntry(
                        syntax = "--quiet",
                        brief = "Suppress logs; only errors go to stderr.",
                        tokens = listOf("--quiet")
                    )
                )
            )
        ),
        examples = listOf(
            "wfas control volume 75"        to "set volume on the running instance",
            "wfas control status"           to "what is streaming right now",
            "wfas control status --json"    to "same, for scripts",
            "wfas control stop"             to "stop the running instance",
            "wfas --viz rainbow"            to "spectrum with animated rainbow colors",
            "wfas --viz \"#1e88e5\""          to "spectrum themed from a hex color",
            "wfas --viz --monitor"          to "spectrum of the system audio, no server",
            "wfas --viz --monitor --groove" to "same, adaptive: follows the melody",
            "wfas --viz --groove hard"      to "maximum contrast between notes",
            "wfas --server --debug"         to "serve with the debug HUD"
        ),
        seeAlso = listOf("client", "config")
    )

    private const val TOPIC_KEYS_PLACEHOLDER = "start, server, client, protocols, network, " +
            "security, config, runtime, reference"

    private val REFERENCE = HelpTopic(
        key = "reference",
        title = "Reference",
        tagline = "protocol, updates, licenses, help itself",
        aliases = listOf("about", "version", "protocol", "licenses", "update", "help"),
        blocks = listOf(
            HelpBlock(
                heading = "Documentation",
                entries = listOf(
                    HelpEntry(
                        syntax = "--protocol",
                        brief = "Explain the WFAS v2 wire protocol and exit.",
                        tokens = listOf("--protocol")
                    ),
                    HelpEntry(
                        syntax = "--licenses",
                        brief = "Show third-party open-source licenses and exit. Aliases: --license, --credits.",
                        tokens = listOf("--licenses", "--license", "--credits")
                    ),
                    HelpEntry(
                        syntax = "--version, -v",
                        brief = "Show the version and exit.",
                        tokens = listOf("--version", "-v")
                    )
                )
            ),
            HelpBlock(
                heading = "Updates",
                entries = listOf(
                    HelpEntry(
                        syntax = "--check-update",
                        brief = "Check GitHub for a newer release and exit. Alias: --check-updates.",
                        tokens = listOf("--check-update", "--check-updates")
                    ),
                    HelpEntry(
                        syntax = "--auto-check-update on|off",
                        brief = "Enable or disable the automatic update check at startup.",
                        tokens = listOf("--auto-check-update", "--auto-check-updates"),
                        detail = "Equivalent to 'wfas config set app.autoCheckUpdate on|off'."
                    )
                )
            ),
            HelpBlock(
                heading = "Help itself",
                entries = listOf(
                    HelpEntry(
                        syntax = "--help, -h",
                        brief = "Open the interactive help browser.",
                        tokens = listOf("--help", "-h"),
                        detail = "Falls back to the complete text whenever the output is not an " +
                                 "interactive terminal, so 'wfas --help > help.txt' and " +
                                 "'wfas --help | grep snapcast' both give you everything. Set " +
                                 "WFAS_NO_INTERACTIVE=1 to force that fallback."
                    ),
                    HelpEntry(
                        syntax = "--help all",
                        brief = "Print every section at once, never interactive."
                    ),
                    HelpEntry(
                        syntax = "--help <topic>",
                        brief = "Print one section. Topics: " + TOPIC_KEYS_PLACEHOLDER
                    ),
                    HelpEntry(
                        syntax = "--help --json",
                        brief = "Emit the whole option table as JSON, for completions and docs."
                    )
                )
            )
        ),
        examples = listOf(
            "wfas --protocol"           to "print the WFAS v2 protocol reference",
            "wfas --help snapcast"      to "jump straight to the Snapcast options",
            "wfas --help all | less"    to "read the whole thing in a pager",
            "wfas --check-update"       to "see if a newer release exists"
        ),
        seeAlso = listOf("start")
    )

    val topics: List<HelpTopic> = listOf(
        START, SERVER, CLIENT, PROTOCOLS, NETWORK, SECURITY, CONFIG, RUNTIME, REFERENCE
    )

    fun byKey(raw: String): HelpTopic? {
        val k = raw.lowercase().removePrefix("--").trim()
        if (k.isEmpty()) return null
        topics.firstOrNull { it.key == k }?.let { return it }
        topics.firstOrNull { k in it.aliases }?.let { return it }
        k.toIntOrNull()?.let { n -> if (n in 1..topics.size) return topics[n - 1] }
        return topics.firstOrNull { it.key.startsWith(k) }
    }

    fun allTokens(): Set<String> =
        topics.flatMap { t -> t.blocks.flatMap { b -> b.entries.flatMap { it.tokens } } }.toSet()
}
