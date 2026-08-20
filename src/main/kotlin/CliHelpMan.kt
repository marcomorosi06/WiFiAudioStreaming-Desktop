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

object CliHelpMan {

    private val PREFORMATTED = Regex("\\S {2,}\\S")

    private const val DESCRIPTION =
        "wfas transmits audio from one device (server) to one or more receivers (clients) on the " +
        "same local network using the WFAS v2 protocol. It also speaks RTP, HTTP, DLNA and " +
        "Snapcast as optional additional protocols, so receivers that know nothing about WFAS can " +
        "play the same audio.\n" +
        "\n" +
        "Run from a terminal with no arguments, wfas prints a short hint and exits. Run with no " +
        "arguments and no console, as happens on a double click, it opens the graphical " +
        "interface. Use --cli, or any of the mode flags, to stay in the terminal on headless " +
        "systems such as a Raspberry Pi or inside automation scripts.\n" +
        "\n" +
        "When a display is available, --gui opens the full graphical interface. Combining --gui " +
        "with --mode opens the window and immediately starts the corresponding operation, as if " +
        "the Start button had been clicked.\n" +
        "\n" +
        "The command line help mirrors this page. 'wfas --help' opens an interactive browser when " +
        "the output is a terminal, 'wfas --help all' prints everything at once, and " +
        "'wfas --help <topic>' prints a single section."

    private val EXIT_STATUS = listOf(
        "0" to "Success.",
        "1" to "General error: bad arguments, port conflict, device not found, expired invite."
    )

    private val NOTES = listOf(
        "On Linux the native C audio engine uses dlopen(3) to load libpulse-simple.so.0 at " +
        "runtime. No link-time dependency is required; the libaudio_engine.so bundled in the " +
        "package works on any distribution. If libpulse-simple is not installed, start the " +
        "server with --legacy-engine to use the FFmpeg backend instead, and install " +
        "pulseaudio-utils (Debian, Raspberry Pi OS) or pipewire-pulseaudio (Fedora, Arch) to " +
        "enable the native engine.",

        "On Windows, some firewalls block multicast packets. If clients cannot discover the " +
        "server, leave --multicast out and give the server address explicitly with --connect on " +
        "the client side. 'wfas firewall allow' opens the inbound ports.",

        "A unicast server is bound to the address of its connected client for the whole session: " +
        "control messages arriving from any other address are discarded, so a third device on the " +
        "LAN cannot terminate someone else's session. To serve several listeners at once, use " +
        "multicast.",

        "The --sdp flag generates an SDP descriptor based on the configured audio settings. The " +
        "payload type is L16 (raw PCM) over RTP. Most players, including VLC, ffplay and " +
        "GStreamer, support this format.",

        "--auth-mode, --auth-key and --encrypt protect the native WFAS stream only. RTP, HTTP, " +
        "DLNA and Snapcast are standard protocols with no authentication and no encryption, so " +
        "enabling them alongside --encrypt puts the same audio on the network in the clear.",

        "Authorization is never inherited from the desktop application. A key typed into its " +
        "settings window protects that window; a command line server is authenticated only if " +
        "the command says so. Asking for key mode without naming a key is the one implicit " +
        "step, and it is still something you asked for: the key is then read from " +
        "WFAS_AUTH_KEY, from the system credential store, or from the terminal, in that order.",

        "The key behind a QR pairing invite is different: it is generated on the spot, kept in " +
        "memory, and never stored. It is valid while the server that issued it runs, and the " +
        "next server starts from a new one - pairing a device again is a deliberate act, not a " +
        "state the machine drifts into."
    )

    private val LICENSING = listOf(
        "wfas itself is licensed under the European Union Public Licence (EUPL) version 1.2.",

        "It bundles third-party open-source software. In particular it uses FFmpeg " +
        "(https://ffmpeg.org) for AAC and Opus audio encoding, distributed under the GNU Lesser " +
        "General Public Licence (LGPL) version 2.1 or later; some optional FFmpeg components may " +
        "be under the GPL. The FFmpeg native libraries are unmodified and are provided by the " +
        "JavaCPP Presets project (https://github.com/bytedeco/javacpp-presets); their source code " +
        "is available at https://ffmpeg.org/download.html.",

        "Other bundled components include JavaCV/JavaCPP, JetBrains Compose Multiplatform, Kotlin " +
        "and kotlinx.coroutines, Ktor, Bouncy Castle, dorkbox SystemTray, JNA and SLF4J. Run " +
        "'wfas --licenses' for the full attribution list, or see THIRD_PARTY_LICENSES.md in the " +
        "project repository."
    )

    private val FILES = listOf(
        "~/.config/wfas/config.json" to
            "User settings on Linux. Honors XDG_CONFIG_HOME.",
        "~/Library/Application Support/wfas/config.json" to
            "User settings on macOS.",
        "%APPDATA%\\wfas\\config.json" to
            "User settings on Windows.",
        "/tmp/wfas-<pid>.port" to
            "IPC port file created by each running instance, deleted automatically on exit. " +
            "On Windows it lives in the user temporary directory.",
        "/tmp/stream.sdp" to
            "RTP session descriptor written when --rtp is active, for external players. " +
            "Relocatable with --sdp-out."
    )

    private val SEE_ALSO = listOf(
        "vlc(1)", "ffplay(1)", "gst-launch-1.0(1)", "aplay(1)", "snapclient(1)", "systemctl(1)"
    )

    private fun esc(s: String): String =
        s.replace("\\", "\\e").replace("-", "\\-")

    private fun line(s: String): String {
        val e = esc(s)
        return if (e.startsWith(".") || e.startsWith("'")) "\\&$e" else e
    }

    private fun paragraphs(sb: StringBuilder, text: String) {
        for (para in text.split("\n\n")) {
            if (para.isBlank()) continue
            sb.appendLine(".PP")
            emit(sb, para.trim())
        }
    }

    private fun emit(sb: StringBuilder, text: String) {
        val chunks = ArrayList<Pair<Boolean, MutableList<String>>>()
        for (raw in text.split("\n")) {
            val pre = raw.startsWith(" ") || PREFORMATTED.containsMatchIn(raw)
            if (chunks.isEmpty() || chunks.last().first != pre) chunks.add(pre to ArrayList())
            chunks.last().second.add(raw)
        }
        for ((pre, rows) in chunks) {
            if (pre) {
                sb.appendLine(".RS 2")
                sb.appendLine(".nf")
                rows.forEach { sb.appendLine(line(it)) }
                sb.appendLine(".fi")
                sb.appendLine(".RE")
            } else {
                val joined = rows.joinToString(" ").trim()
                if (joined.isNotEmpty()) sb.appendLine(line(joined))
            }
        }
    }

    fun render(version: String, date: String): String {
        val sb = StringBuilder()
        sb.appendLine(".\\\" Generated by 'gradlew generateMan' from CliHelpModel.kt. Do not edit by hand.")
        sb.appendLine(".\\\" Copyright (c) 2026 Marco Morosi. Licensed under EUPL\\-1.2.")
        sb.appendLine(".TH WFAS 1 \"$date\" \"$version\" \"WiFi Audio Streaming\"")

        sb.appendLine(".SH NAME")
        sb.appendLine("wfas \\- " + esc(CliHelpModel.TAGLINE.removeSuffix(".").lowercase()))

        sb.appendLine(".SH SYNOPSIS")
        CliHelpModel.SYNOPSIS.forEachIndexed { i, s ->
            if (i > 0) sb.appendLine(".br")
            sb.appendLine(".B " + esc(s.substringBefore(' ')))
            sb.appendLine(esc(s.substringAfter(' ', "")))
        }

        sb.appendLine(".SH DESCRIPTION")
        paragraphs(sb, DESCRIPTION)

        sb.appendLine(".SH TOPICS")
        sb.appendLine("Each section below is also reachable on its own with " +
            esc("'wfas --help <topic>'") + ".")
        for (t in CliHelpModel.topics) {
            sb.appendLine(".TP")
            sb.appendLine(".B " + esc(t.key))
            sb.appendLine(esc(t.title + " - " + t.tagline))
        }

        for (t in CliHelpModel.topics) {
            sb.appendLine(".SH " + t.title.uppercase())
            t.intro?.let { emit(sb, it) }
            for (b in t.blocks) {
                b.heading?.let { sb.appendLine(".SS " + esc(it)) }
                b.intro?.let { sb.appendLine(".PP"); emit(sb, it) }
                for (e in b.entries) {
                    sb.appendLine(".TP")
                    sb.appendLine(".B " + esc(e.syntax))
                    emit(sb, e.brief)
                    e.default?.let { sb.appendLine(".br"); sb.appendLine("Default: " + esc(it) + ".") }
                    e.detail?.let { sb.appendLine(".RS"); sb.appendLine(".PP"); emit(sb, it); sb.appendLine(".RE") }
                }
                b.outro?.let { sb.appendLine(".PP"); emit(sb, it) }
            }
            if (t.seeAlso.isNotEmpty()) {
                sb.appendLine(".PP")
                sb.appendLine("See also: " + esc(t.seeAlso.joinToString(", ") { "wfas --help $it" }) + ".")
            }
        }

        sb.appendLine(".SH EXAMPLES")
        for (t in CliHelpModel.topics) {
            if (t.examples.isEmpty()) continue
            sb.appendLine(".SS " + esc(t.title))
            for ((cmd, note) in t.examples) {
                sb.appendLine(".TP")
                sb.appendLine(".B " + esc(cmd))
                sb.appendLine(esc(note.replaceFirstChar { it.uppercase() }) + ".")
            }
        }

        sb.appendLine(".SH FILES")
        for ((path, meaning) in FILES) {
            sb.appendLine(".TP")
            sb.appendLine(".I " + esc(path))
            emit(sb, meaning)
        }
        sb.appendLine(".PP")
        sb.appendLine(esc("The settings file is a single JSON document shared by the CLI and the " +
            "GUI. Inspect or edit it with 'wfas config', override its location with --config, or " +
            "run 'wfas config path' to print the exact path resolved for the current system."))

        sb.appendLine(".SH EXIT STATUS")
        for ((code, meaning) in EXIT_STATUS) {
            sb.appendLine(".TP")
            sb.appendLine(".B $code")
            sb.appendLine(esc(meaning))
        }

        sb.appendLine(".SH NOTES")
        NOTES.forEachIndexed { i, n -> if (i > 0) sb.appendLine(".PP"); emit(sb, n) }

        sb.appendLine(".SH LICENSING")
        LICENSING.forEachIndexed { i, n -> if (i > 0) sb.appendLine(".PP"); emit(sb, n) }

        sb.appendLine(".SH SEE ALSO")
        SEE_ALSO.forEachIndexed { i, ref ->
            val name = esc(ref.substringBefore('('))
            val sect = ref.substringAfter('(').removeSuffix(")")
            sb.appendLine(".BR $name ($sect)" + if (i < SEE_ALSO.size - 1) "," else "")
        }
        for ((label, url) in CliHelpModel.LINKS) {
            sb.appendLine(".PP")
            sb.appendLine(esc(label) + ":")
            sb.appendLine(".br")
            sb.appendLine(".I " + esc(url))
        }

        sb.appendLine(".SH AUTHOR")
        sb.appendLine("Marco Morosi.")
        sb.appendLine(".br")
        sb.appendLine("Licensed under the European Union Public Licence (EUPL), version 1.2.")
        sb.appendLine(".br")
        sb.appendLine(".I https://joinup.ec.europa.eu/software/page/eupl")

        return sb.toString()
    }
}
