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

import kotlinx.coroutines.*
import java.io.File
import java.net.ServerSocket
import javax.sound.sampled.Mixer

// ─────────────────────────────────────────────────────────────────────────────
// Exit codes
// ─────────────────────────────────────────────────────────────────────────────

object ExitCode {
    const val OK             = 0
    const val USAGE_ERROR    = 1
    const val NOT_FOUND      = 2
    const val DISCONNECTED   = 3
    const val RESOURCE_ERROR = 4
}

// ─────────────────────────────────────────────────────────────────────────────
// ANSI helpers
// ─────────────────────────────────────────────────────────────────────────────

private val ANSI = System.getenv("NO_COLOR") == null && System.getenv("TERM") != "dumb" &&
        !System.getProperty("os.name", "").lowercase().contains("win") ||
        System.getenv("WT_SESSION") != null || System.getenv("COLORTERM") != null

private fun ansi(code: String, text: String) = if (ANSI) "[${code}m$text[0m" else text
private fun green(t: String)  = ansi("32",   t)
private fun red(t: String)    = ansi("31",   t)
private fun yellow(t: String) = ansi("33",   t)
private fun cyan(t: String)   = ansi("36",   t)
private fun bold(t: String)   = ansi("1",    t)
private fun dim(t: String)    = ansi("2",    t)

// ─────────────────────────────────────────────────────────────────────────────
// Output helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun out(msg: String, args: CliArgs) {
    if (!args.quiet) println(msg)
}

private fun err(msg: String) = System.err.println(msg)

private fun jsonLine(vararg pairs: Pair<String, Any?>) {
    val body = pairs.joinToString(", ") { (k, v) ->
        val vStr = when (v) {
            null       -> "null"
            is String  -> "\"${v.replace("\"", "\\\"")}\""
            is Boolean -> v.toString()
            is Number  -> v.toString()
            else       -> "\"$v\""
        }
        "\"$k\": $vStr"
    }
    println("{$body}")
}

// ─────────────────────────────────────────────────────────────────────────────
// Port availability check
// ─────────────────────────────────────────────────────────────────────────────

private data class PortCheckResult(val available: Boolean, val pid: Int?)

private fun checkPort(port: Int): PortCheckResult {
    return try {
        ServerSocket(port).use { }
        PortCheckResult(available = true, pid = null)
    } catch (_: Exception) {
        PortCheckResult(available = false, pid = findPidOnPort(port))
    }
}

private fun findPidOnPort(port: Int): Int? {
    val os = System.getProperty("os.name", "").lowercase()
    return try {
        when {
            os.contains("linux") -> {
                val hexPort = port.toString(16).uppercase().padStart(4, '0')
                val tcpFile = File("/proc/net/tcp")
                if (!tcpFile.exists()) return null
                val line = tcpFile.readLines().firstOrNull { it.trim().split("\\s+".toRegex()).getOrNull(1)?.endsWith(":$hexPort") == true }
                    ?: return null
                val inode = line.trim().split("\\s+".toRegex()).getOrNull(9) ?: return null
                File("/proc").listFiles()?.filter { it.isDirectory && it.name.all { c -> c.isDigit() } }
                    ?.firstOrNull { pidDir ->
                        val fdDir = File(pidDir, "fd")
                        fdDir.listFiles()?.any { fd ->
                            try { fd.canonicalPath.contains("socket:[${inode}]") } catch (_: Exception) { false }
                        } == true
                    }?.name?.toIntOrNull()
            }
            os.contains("mac") || os.contains("darwin") -> {
                val result = ProcessBuilder("lsof", "-ti", ":$port")
                    .redirectErrorStream(true).start()
                    .inputStream.bufferedReader().readText().trim()
                result.lines().firstOrNull()?.toIntOrNull()
            }
            os.contains("win") -> {
                val result = ProcessBuilder("netstat", "-ano")
                    .redirectErrorStream(true).start()
                    .inputStream.bufferedReader().readText()
                result.lines()
                    .firstOrNull { it.contains(":$port ") && it.contains("LISTENING") }
                    ?.trim()?.split("\\s+".toRegex())?.lastOrNull()?.toIntOrNull()
            }
            else -> null
        }
    } catch (_: Exception) { null }
}

private fun assertPortFree(port: Int, label: String, args: CliArgs) {
    val check = checkPort(port)
    if (!check.available) {
        val pidInfo = check.pid?.let { " (PID $it)" } ?: ""
        val suggestion = port + 1
        if (args.json) {
            jsonLine("error" to "port_in_use", "port" to port, "pid" to check.pid, "label" to label)
        } else {
            err(red("!") + " $label port $port is already in use$pidInfo.")
            err("  Try: wfas --mode ${args.runMode.name.lowercase().removePrefix("cli_")} --${label.lowercase().replace(' ', '-')}-port $suggestion")
        }
        kotlin.system.exitProcess(ExitCode.RESOURCE_ERROR)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SDP generation
// ─────────────────────────────────────────────────────────────────────────────

private fun buildSdp(args: CliArgs, serverIp: String, audio: AudioSettings_V1): String {
    val sessionId = System.currentTimeMillis() / 1000
    val multicastIp = "239.255.0.1"
    val destIp = if (args.multicast) multicastIp else serverIp
    val sampleRate = audio.sampleRate.toInt()
    val channels = audio.channels
    val payloadType = 96
    val ttl = if (args.multicast) "/4" else ""
    return buildString {
        appendLine("v=0")
        appendLine("o=- $sessionId $sessionId IN IP4 $serverIp")
        appendLine("s=WiFi Audio Streaming")
        appendLine("i=WFAS RTP stream -wfas.app")
        if (args.multicast)
            appendLine("c=IN IP4 $destIp$ttl")
        else
            appendLine("c=IN IP4 $destIp")
        appendLine("t=0 0")
        appendLine("a=tool:wfas")
        appendLine("m=audio ${args.rtpPort} RTP/AVP $payloadType")
        appendLine("a=rtpmap:$payloadType L16/$sampleRate/$channels")
        appendLine("a=ptime:${(audio.bufferSize.toFloat() / sampleRate * 1000 / channels / 2).toInt()}")
        append("a=recvonly")
    }
}

private fun printSdp(args: CliArgs, serverIp: String, audio: AudioSettings_V1) {
    val sdp = buildSdp(args, serverIp, audio)
    println(sdp)
    if (args.sdpOut != null) {
        try {
            File(args.sdpOut).writeText(sdp)
            if (!args.quiet) err(dim("  SDP written to ${args.sdpOut}"))
        } catch (e: Exception) {
            err(red("!") + " Could not write SDP to ${args.sdpOut}: ${e.message}")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Device resolution
// ─────────────────────────────────────────────────────────────────────────────

private fun resolveOutputDevice(name: String?): Mixer.Info? {
    val all = NetworkHandler_v1.findAvailableOutputMixers()
    if (name == null) return all.firstOrNull()
    return all.firstOrNull { it.name.contains(name, ignoreCase = true) || it.description.contains(name, ignoreCase = true) }
        ?: run {
            err(yellow("!") + " Output device \"$name\" not found -using system default.")
            all.firstOrNull()
        }
}

private fun resolveInputDevice(name: String?): Mixer.Info? {
    val all = NetworkHandler_v1.findAvailableInputMixers()
    if (name == null) return all.firstOrNull()
    return all.firstOrNull { it.name.contains(name, ignoreCase = true) || it.description.contains(name, ignoreCase = true) }
        ?: run {
            err(yellow("!") + " Input device \"$name\" not found -using system default.")
            all.firstOrNull()
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main entry point
// ─────────────────────────────────────────────────────────────────────────────

private fun printCliWelcome() {
    val w = 58
    val line = "-".repeat(w)
    println()
    println(bold("  +$line+"))
    println(bold("  |") + "  " + bold("WiFi Audio Streaming") + " - " + Strings.get("cli_welcome_thanks") + "  " + bold("|"))
    println(bold("  |") + " ".repeat(w) + bold("|"))
    println(bold("  |") + "  " + cyan("Android app:") + "  " + bold("|"))
    println(bold("  |") + "  " + dim("https://github.com/marcomorosi06/") + "      " + bold("|"))
    println(bold("  |") + "  " + dim("WiFiAudioStreaming-Android/releases") + "    " + bold("|"))
    println(bold("  |") + " ".repeat(w) + bold("|"))
    println(bold("  |") + "  " + Strings.get("cli_welcome_path_tip") + "  " + bold("|"))
    println(bold("  |") + "  " + dim(Strings.get("cli_welcome_path_how")) + "  " + bold("|"))
    println(bold("  +$line+"))
    println()
}

fun runCli(args: CliArgs) {
    AppDebug.enabled = args.debug
    val settings = SettingsRepository.loadSettings()

    if (!SettingsRepository.hasSeenCliWelcome() && args.controlCmd == null && !args.json) {
        printCliWelcome()
        SettingsRepository.markCliWelcomeSeen()
    }

    if (args.controlCmd != null) {
        IpcClient.send(args.controlCmd, args)
        return
    }

    if (args.sdp && args.runMode != RunMode.CLI_SERVER) {
        val tmpSdp = File(System.getProperty("java.io.tmpdir"), "stream.sdp")
        if (tmpSdp.exists()) { println(tmpSdp.readText()); return }
        err(red("!") + " No running server found. Start a server with --rtp first.")
        kotlin.system.exitProcess(1)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        DebugHud.stop()
        runBlocking { NetworkHandler_v1.stopCurrentStream() }
        IpcServer.stop()
    })

    IpcServer.start(args)

    runBlocking {
        when (args.runMode) {
            RunMode.CLI_SERVER  -> runCliServer(args, settings)
            RunMode.CLI_CLIENT  -> runCliClient(args, settings)
            RunMode.CLI_DISCOVER -> runCliDiscover(args)
            else -> Unit
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Server mode
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun runCliServer(args: CliArgs, settings: AllSettings) {
    assertPortFree(args.port,    "streaming", args)
    assertPortFree(args.micPort, "mic",       args)
    if (args.rtp)  assertPortFree(args.rtpPort,  "RTP",  args)
    if (args.http) assertPortFree(args.httpPort,  "HTTP", args)

    val audio = settings.audio
    val protocols = mutableSetOf(StreamingProtocol.WFAS)
    if (args.rtp)  protocols += StreamingProtocol.RTP
    if (args.http) protocols += StreamingProtocol.HTTP
    val capabilities = ServerCapabilities(
        protocols  = protocols,
        httpPort   = if (args.http) args.httpPort else null,
        safariMode = args.httpSafari
    )

    val micMixInput    = if (args.mic && args.micRouting == MicRoutingMode.MIX_INTO_STREAM) resolveInputDevice(args.micInput) else null
    val micVirtualOut  = if (args.mic && args.micRouting == MicRoutingMode.VIRTUAL_MIC)     resolveOutputDevice(null)        else null

    val serverIp = NetworkHandler_v1.getLocalIpAddress()

    if (args.volume != null) NetworkHandler_v1.setServerVolume(args.volume)
    if (args.mute)           NetworkHandler_v1.isMicMuted.value = true

    if (args.json) {
        jsonLine(
            "event"     to "server_starting",
            "pid"       to ProcessHandle.current().pid(),
            "ip"        to serverIp,
            "port"      to args.port,
            "multicast" to args.multicast,
            "rtp"       to args.rtp,
            "http"      to args.http,
        )
    } else if (!args.viz) {
        out("", args)
        out(bold("  WiFi Audio Streaming") + "  - server mode", args)
        out("  ${dim("IP")}      ${cyan(serverIp)}:${args.port}", args)
        out("  ${dim("Multicast")} ${if (args.multicast) green("enabled") else dim("disabled")}", args)
        if (args.rtp)  out("  ${dim("RTP")}     port ${args.rtpPort}", args)
        if (args.http) out("  ${dim("HTTP")}    http://$serverIp:${args.httpPort}", args)
        if (args.mic)  out("  ${dim("Mic")}     ${args.micRouting.name.lowercase().replace('_', '-')}", args)
        out("", args)
        out(dim("  Commands: q=stop, v <0-100>=volume"), args)
        out("", args)
    }

    val viz = if (args.viz && !args.json)
        AudioVisualizer(channels = audio.channels, label = "server  ${serverIp}:${args.port}", sampleRate = audio.sampleRate.toInt(), theme = args.vizTheme)
    else null

    val done = kotlinx.coroutines.CompletableDeferred<Unit>()

    if (viz != null) {
        args.volume?.let { viz.setVolumePercent((it * 100).toInt()) }
        viz.onVolume = { v -> NetworkHandler_v1.setServerVolume(v.coerceIn(0f, 2f)) }
        viz.onQuit = {
            kotlinx.coroutines.runBlocking { NetworkHandler_v1.stopCurrentStream() }
            if (!done.isCompleted) done.complete(Unit)
        }
    }
    viz?.start()

    NetworkHandler_v1.launchServerInstance(
        audioSettings  = audio,
        port           = args.port,
        isMulticast    = args.multicast,
        capabilities   = capabilities,
        micRoutingMode = if (args.mic) args.micRouting else MicRoutingMode.OFF,
        micOutputMixerInfo = micVirtualOut,
        micPort        = args.micPort,
        rtpPort        = args.rtpPort,
        useNativeEngine = args.useNativeEngine,
        micMixInputInfo = micMixInput,
        onAudioFrame    = viz?.let { v -> { samples -> v.feedFrame(samples) } },
    ) { key, fmtArgs ->
        val msg = if (fmtArgs.isEmpty()) Strings.get(key) else try { String.format(Strings.get(key), *fmtArgs) } catch (_: Exception) { key }
        if (args.json) {
            jsonLine("event" to key, "message" to msg)
        } else if (viz != null) {
            val icon = when {
                key.contains("error")      -> "!"
                key.contains("connected")  -> "+"
                key.contains("disconnect") -> "-"
                else                       -> "."
            }
            viz.statusMsg = "$icon  $msg"
        } else {
            val icon = when {
                key.contains("error")      -> red("!")
                key.contains("connected")  -> green("+")
                key.contains("disconnect") -> yellow("-")
                else                       -> dim(".")
            }
            out("  $icon  $msg", args)
        }
    }

    if (args.debug && !args.viz && !args.json)
        DebugHud.start(sending = true, peer = "$serverIp:${args.port}")

    if (args.rtp && (args.sdp || args.sdpOut != null)) {
        delay(300)
        if (args.sdpOut != null) {
            val sdp = buildSdp(args, serverIp, audio)
            try {
                File(args.sdpOut).writeText(sdp)
                val tmpSdp = File(System.getProperty("java.io.tmpdir"), "stream.sdp")
                tmpSdp.writeText(sdp)
                if (!args.quiet && !args.json) out(dim("  SDP written to ${args.sdpOut}"), args)
            } catch (e: Exception) {
                err(red("!") + " Could not write SDP: ${e.message}")
            }
        }
        if (args.sdp) {
            printSdp(args, serverIp, audio)
        } else {
            val tmpSdp = File(System.getProperty("java.io.tmpdir"), "stream.sdp")
            tmpSdp.writeText(buildSdp(args, serverIp, audio))
        }
    }

    if (viz != null) {
        done.await()
    } else {
    val stdinThread = Thread {
        try {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(System.`in`))
            while (!done.isCompleted) {
                val line = reader.readLine()?.trim() ?: break
                when {
                    line.equals("q", ignoreCase = true) ||
                    line.equals("quit", ignoreCase = true) ||
                    line.equals("stop", ignoreCase = true) -> {
                        kotlinx.coroutines.runBlocking { NetworkHandler_v1.stopCurrentStream() }
                        done.complete(Unit)
                    }
                    line.matches(Regex("(?i)v(?:ol(?:ume)?)?\\s+(\\d+(?:\\.\\d+)?)")) -> {
                        val pct = line.trim().split("\\s+".toRegex()).last().toFloatOrNull() ?: return@Thread
                        NetworkHandler_v1.setServerVolume((pct / 100f).coerceIn(0f, 2f))
                        if (!args.quiet && !args.json) out("  volume: ${pct.toInt()}%", args)
                    }
                }
            }
        } catch (_: Exception) {}
    }
    stdinThread.isDaemon = true
    stdinThread.start()

    done.await()
    stdinThread.interrupt()
    }
    DebugHud.stop()
    viz?.stop()
}

// ─────────────────────────────────────────────────────────────────────────────
// Client mode
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun runCliClient(args: CliArgs, settings: AllSettings) {
    val outputDevice = resolveOutputDevice(args.outputDevice)
    if (outputDevice == null) {
        err(red("!") + " No audio output device found.")
        kotlin.system.exitProcess(ExitCode.RESOURCE_ERROR)
    }

    val serverInfo = if (args.serverIp != null) {
        connectDirect(args, settings)
    } else {
        discoverAndChoose(args)
    } ?: kotlin.system.exitProcess(ExitCode.NOT_FOUND)

    val micInput = if (args.sendMic) resolveInputDevice(args.micInput) else null

    if (args.volume != null) NetworkHandler_v1.setServerVolume(args.volume)
    if (args.mute)           NetworkHandler_v1.isMicMuted.value = true

    if (args.json) {
        jsonLine("event" to "client_connecting", "pid" to ProcessHandle.current().pid(), "server" to serverInfo.ip, "port" to serverInfo.port)
    } else if (!args.viz) {
        out("", args)
        out(bold("  WiFi Audio Streaming") + "  - client mode", args)
        out("  ${dim("Connecting to")}  ${cyan(serverInfo.ip)}:${serverInfo.port}", args)
        out("  ${dim("Output")}         ${outputDevice.name}", args)
        if (args.sendMic) out("  ${dim("Mic")}            ${micInput?.name ?: "default"}", args)
        out("", args)
        out(dim("  Commands: q=disconnect, v <0-100>=volume"), args)
        out("", args)
    }

    val viz = if (args.viz && !args.json)
        AudioVisualizer(channels = settings.audio.channels, label = "client  ${serverInfo.ip}:${serverInfo.port}", sampleRate = settings.audio.sampleRate.toInt(), theme = args.vizTheme)
    else null

    val done = kotlinx.coroutines.CompletableDeferred<Unit>()
    var userStopped = false
    var cliExitCode = ExitCode.OK

    if (viz != null) {
        args.volume?.let { viz.setVolumePercent((it * 100).toInt()) }
        viz.onVolume = { v -> NetworkHandler_v1.setServerVolume(v.coerceIn(0f, 2f)) }
        viz.onQuit = {
            userStopped = true
            kotlinx.coroutines.runBlocking { NetworkHandler_v1.stopCurrentStream() }
            if (!done.isCompleted) done.complete(Unit)
        }
    }
    viz?.start()

    NetworkHandler_v1.launchClientInstance(
        audioSettings            = settings.audio,
        serverInfo               = serverInfo,
        selectedMixerInfo        = outputDevice,
        sendMicrophone           = args.sendMic,
        micInputMixerInfo        = micInput,
        micPort                  = args.micPort,
        connectionSoundEnabled   = true,
        disconnectionSoundEnabled = true,
        onAudioFrame             = viz?.let { v -> { samples -> v.feedFrame(samples) } },
    ) { key, fmtArgs ->
        val msg = if (fmtArgs.isEmpty()) Strings.get(key) else try { String.format(Strings.get(key), *fmtArgs) } catch (_: Exception) { key }
        if (args.json) {
            jsonLine("event" to key, "message" to msg)
        } else if (viz != null) {
            val icon = when {
                key.contains("error")      -> "!"
                key.contains("connected")  -> "+"
                key.contains("disconnect") -> "-"
                else                       -> "."
            }
            viz.statusMsg = "$icon  $msg"
        } else {
            val icon = when {
                key.contains("error")      -> red("!")
                key.contains("connected")  -> green("+")
                key.contains("disconnect") -> yellow("-")
                else                       -> dim(".")
            }
            out("  $icon  $msg", args)
        }
        if (key.contains("error") || key.contains("timeout") || key.contains("disconnect") || key.contains("incompatible")) {
            if (!userStopped) cliExitCode = ExitCode.DISCONNECTED
            done.complete(Unit)
        }
    }

    if (args.debug && !args.viz && !args.json)
        DebugHud.start(sending = false, peer = "${serverInfo.ip}:${serverInfo.port}")

    if (viz != null) {
        done.await()
    } else {
    val stdinThread = Thread {
        try {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(System.`in`))
            while (!done.isCompleted) {
                val line = reader.readLine()?.trim() ?: break
                when {
                    line.equals("q", ignoreCase = true) ||
                    line.equals("quit", ignoreCase = true) ||
                    line.equals("stop", ignoreCase = true) ||
                    line.equals("disconnect", ignoreCase = true) -> {
                        userStopped = true
                        kotlinx.coroutines.runBlocking { NetworkHandler_v1.stopCurrentStream() }
                        done.complete(Unit)
                    }
                    line.matches(Regex("(?i)v(?:ol(?:ume)?)?\\s+(\\d+(?:\\.\\d+)?)")) -> {
                        val pct = line.trim().split("\\s+".toRegex()).last().toFloatOrNull() ?: return@Thread
                        NetworkHandler_v1.setServerVolume((pct / 100f).coerceIn(0f, 2f))
                        if (!args.quiet && !args.json) out("  volume: ${pct.toInt()}%", args)
                    }
                }
            }
        } catch (_: Exception) {}
    }
    stdinThread.isDaemon = true
    stdinThread.start()

    done.await()
    stdinThread.interrupt()
    }
    DebugHud.stop()
    viz?.stop()
    if (cliExitCode != ExitCode.OK) kotlin.system.exitProcess(cliExitCode)
}

private val CliArgs.sendMic get() = mic

private suspend fun connectDirect(args: CliArgs, settings: AllSettings): ServerInfo? {
    val ip = args.serverIp!!
    if (!args.json && !args.quiet)
        out("  ${dim("Probing")} $ip:${args.port}...", args)

    val isMulticast = withTimeoutOrNull(2000) {
        NetworkHandler_v1.probeIsMulticast(ip, args.port)
    } ?: true

    return ServerInfo(ip = ip, isMulticast = isMulticast, port = args.port)
}

private suspend fun discoverAndChoose(args: CliArgs): ServerInfo? {
    if (!args.json) out("  ${dim("Scanning network for servers...")}", args)

    val found = mutableMapOf<String, ServerInfo>()

    NetworkHandler_v1.beginDeviceDiscovery { hostname, serverInfo ->
        if (serverInfo.lastSeen == 0L) found.remove(hostname)
        else found[hostname] = serverInfo
    }

    delay(5000)
    NetworkHandler_v1.endDeviceDiscovery()

    if (found.isEmpty()) {
        if (args.json) jsonLine("event" to "discover_empty")
        else err(red("!") + " No servers found on the network.")
        return null
    }

    if (found.size == 1) {
        val (hostname, info) = found.entries.first()
        if (!args.json) out("  ${green("->")} Auto-connecting to ${bold(hostname)} (${info.ip})", args)
        return info
    }

    if (args.json) {
        found.values.forEachIndexed { i, s ->
            jsonLine("event" to "discover_server", "index" to i + 1, "ip" to s.ip, "port" to s.port)
        }
        jsonLine("event" to "discover_ambiguous", "count" to found.size)
        return null
    }

    out("", args)
    out("  Multiple servers found -choose one:", args)
    found.entries.forEachIndexed { i, (hostname, info) ->
        out("  ${bold("${i + 1}.")}  ${cyan(hostname)}  ${dim("${info.ip}:${info.port}")}", args)
    }
    out("", args)
    print("  Enter number [1-${found.size}]: ")
    val choice = readLine()?.toIntOrNull()?.minus(1) ?: return null
    return found.values.toList().getOrNull(choice)
}

// ─────────────────────────────────────────────────────────────────────────────
// Discover mode
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun runCliDiscover(args: CliArgs) {
    if (!args.json && !args.quiet) {
        out(bold("  WiFi Audio Streaming") + "  - discover mode", args)
        out("  ${dim("Scanning... (Ctrl+C to stop)")}", args)
        out("", args)
    }

    val seen = mutableSetOf<String>()

    NetworkHandler_v1.beginDeviceDiscovery { hostname, serverInfo ->
        if (serverInfo.lastSeen == 0L) {
            if (args.json) jsonLine("event" to "server_gone", "hostname" to hostname)
            else if (!args.quiet) out("  ${red("-")}  $hostname  ${dim("(gone)")}", args)
        } else if (hostname !in seen || args.watch) {
            seen += hostname
            val caps = serverInfo.capabilities
            val protocols = caps?.protocols?.joinToString("+") { it.name } ?: "WFAS"
            if (args.json) {
                jsonLine(
                    "event"     to "server_found",
                    "hostname"  to hostname,
                    "ip"        to serverInfo.ip,
                    "port"      to serverInfo.port,
                    "multicast" to serverInfo.isMulticast,
                    "protocols" to protocols
                )
            } else {
                out("  ${green("+")}  ${bold(hostname)}  ${cyan(serverInfo.ip)}:${serverInfo.port}  ${dim(protocols)}", args)
            }
        }
    }

    if (!args.watch) {
        delay(5000)
        NetworkHandler_v1.endDeviceDiscovery()
        if (seen.isEmpty()) {
            if (args.json) jsonLine("event" to "discover_empty")
            else out("  ${dim("No servers found.")}", args)
        }
    } else {
        withContext(Dispatchers.IO) {
            try { while (true) delay(1000) } catch (_: CancellationException) {}
        }
    }
}
