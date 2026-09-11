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
    /** Il canale di controllo ha rifiutato: token o chiave non validi. */
    const val AUTH_FAILED    = 5
}

/**
 * Il registro della sessione di ricezione che non passa da NetworkHandler.
 *
 * 'wfas control stop' sa fermare uno stream WFAS perche' quello passa di li'.
 * Un ascolto RTP o un client Snapcast vivono per conto loro: senza questo
 * registro il comando risponderebbe "ok" senza fermare niente, che e' peggio
 * di un errore. Ce n'e' una alla volta, come per il resto della CLI.
 */
object ExternalReceiver {

    /** "rtp", "snapcast", oppure vuoto quando non si sta ricevendo. */
    @Volatile var kind: String = ""
    @Volatile var detail: String = ""
    @Volatile var onStop:   (() -> Unit)? = null
    @Volatile var onVolume: ((Float) -> Unit)? = null
    @Volatile var onMute:   ((Boolean) -> Unit)? = null
    @Volatile var statusFields: (() -> Map<String, Any?>)? = null

    val active: Boolean get() = kind.isNotEmpty()

    fun clear() {
        kind = ""; detail = ""
        onStop = null; onVolume = null; onMute = null; statusFields = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ANSI helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun ansi(code: String, text: String) = Ansi.code(code, text)
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

private fun linkLabel(): String {
    val st = UsbLink.state
    return if (UsbLink.isReady())
        "USB  ${st.displayName ?: st.interfaceName ?: "usb"}  ${st.localAddress ?: "-"}"
    else "Wi-Fi"
}

private fun reportLink(args: CliArgs) {
    if (args.json) {
        jsonLine(
            "event"   to "link_state",
            "usb"     to UsbLink.isReady(),
            "iface"   to (UsbLink.state.interfaceName ?: ""),
            "address" to (UsbLink.state.localAddress ?: ""),
            "family"  to NetAddr.preferredFamily.name.lowercase()
        )
        return
    }
    if (!args.viz) {
        out("  ${dim("Link")}    ${if (UsbLink.isReady()) green(linkLabel()) else dim(linkLabel())}", args)
    }
    if (args.usb == true && !UsbLink.isReady()) {
        err(yellow("!") + " --usb requested but no USB link is up. Streaming over Wi-Fi instead.")
        val stranded = UsbLink.state.takeIf { it.stage == UsbLink.Stage.FOUND_NO_IP }
        if (stranded != null)
            err(dim("  Adapter '${stranded.displayName ?: stranded.interfaceName}' is present but has no IP " +
                    "address. USB tethering is probably still off on the device."))
        else if (UsbLink.platform == UsbLink.Platform.MACOS)
            err(dim("  macOS has no built-in RNDIS driver, so Android USB tethering does not come up."))
        else
            err(dim("  Enable USB tethering on the phone, or run with --debug to list the interfaces."))
        if (args.debug) {
            err(dim("  Interfaces seen:"))
            UsbLink.inspect().forEach { err(dim("    $it")) }
        }
    }
}

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

private fun Mixer.Info.matchesName(name: String): Boolean =
    this.name.contains(name, ignoreCase = true) || description.contains(name, ignoreCase = true)

private fun resolveOutputDevice(name: String?): Mixer.Info? {
    val all = NetworkHandler_v1.findAvailableOutputMixers()
    if (name == null) return all.firstOrNull()

    all.firstOrNull { it.matchesName(name) }?.let { return it }

    val raw = NetworkHandler_v1.allMixers().firstOrNull {
        !NetworkHandler_v1.isPortMixer(it) && it.matchesName(name)
    }
    if (raw != null) {
        err(yellow("!") + " Output device \"$name\" did not answer the format probe - trying it anyway.")
        return raw
    }

    err(yellow("!") + " Output device \"$name\" not found - using system default.")
    err(dim("    Run 'wfas devices' to see the exact names this system reports."))
    return all.firstOrNull()
}

private fun resolveInputDevice(name: String?): Mixer.Info? {
    val all = NetworkHandler_v1.findAvailableInputMixers()
    if (name == null) return all.firstOrNull()

    all.firstOrNull { it.matchesName(name) }?.let { return it }

    val raw = NetworkHandler_v1.allMixers().firstOrNull {
        !NetworkHandler_v1.isPortMixer(it) && it.matchesName(name)
    }
    if (raw != null) {
        err(yellow("!") + " Input device \"$name\" did not answer the format probe - trying it anyway.")
        return raw
    }

    err(yellow("!") + " Input device \"$name\" not found - using system default.")
    err(dim("    Run 'wfas devices' to see the exact names this system reports."))
    return all.firstOrNull()
}

// ─────────────────────────────────────────────────────────────────────────────
// Main entry point
// ─────────────────────────────────────────────────────────────────────────────

/**
 * La sicurezza di una sessione CLI viene solo dalla riga di comando.
 *
 * Le impostazioni salvate descrivono la finestra: modo, cifratura e chiave sono
 * quelle che l'utente ha scelto li'. Ereditarle qui voleva dire che 'wfas
 * --server' partiva in modo chiave solo perche' una chiave era stata scritta
 * nelle impostazioni dell'app, senza che niente nel comando lo dicesse - e la
 * stessa riga si comportava in due modi diversi su due macchine. Senza flag di
 * autorizzazione la CLI parte in chiaro, punto.
 *
 * Resta il percorso opposto, che e' esplicito: se il comando chiede il modo
 * chiave e non dice quale, la chiave si cerca dove puo' essere stata messa
 * apposta - prima l'ambiente, poi la custodia di sistema, infine il terminale.
 * E' lo stesso ordine di [IpcClient]: l'esplicito batte l'implicito.
 */
private fun resolveAuthKey(args: CliArgs, settings: AllSettings): CliArgs {
    if (!SecurityMode.requiresKey(args.authMode)) return args
    if (args.authKey.isNotBlank()) return args
    // --qr la chiave se la fabbrica da solo, e 'control' la risolve per conto suo.
    if (args.qr || args.controlCmd != null) return args

    val key = System.getenv(SettingsRepository.ENV_AUTH_KEY)?.takeIf { it.isNotBlank() }
        ?: settings.app.authKey.takeIf { it.isNotBlank() }
        ?: promptForAuthKey()

    // Fail-closed: partire comunque vorrebbe dire calcolare la prova su una
    // stringa vuota, cioe' su un segreto che chiunque puo' indovinare.
    if (key.isNullOrBlank()) {
        System.err.println(
            "Refusing to start: --auth-mode key needs a key and none is available. " +
                    "Pass --auth-key <key|->, --auth-key-file <path>, or set " +
                    "${SettingsRepository.ENV_AUTH_KEY}." +
                    if (SecretVault.available)
                        " A key kept in the ${SecretVault.label} credential store " +
                                "('wfas config set security.authKey') is used too."
                    else ""
        )
        kotlin.system.exitProcess(ExitCode.USAGE_ERROR)
    }
    return args.copy(authKey = key)
}

private fun promptForAuthKey(): String? {
    val console = System.console() ?: return null
    System.err.println("Key authentication was requested and no key was given on the command line.")
    val chars = runCatching { console.readPassword("Key: ") }.getOrNull() ?: return null
    val value = String(chars).trim()
    java.util.Arrays.fill(chars, '\u0000')
    return value.ifEmpty { null }
}

/**
 * La chiave di un invito vive solo in memoria, per questo processo: non tocca
 * il file di configurazione ne' la custodia dell'OS. Chiuso il server che
 * l'ha emessa, l'invito non vale piu' e i dispositivi vanno riappaiati.
 */
private fun prepareQrKey(args: CliArgs): CliArgs {
    val key = args.authKey.takeIf { it.isNotBlank() }?.also { QrPairingState.adoptSessionKey(it) }
        ?: QrPairingState.newSessionKey()
    return args.copy(authMode = SecurityMode.KEY.name, authKey = key, encrypt = true)
}

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

fun printUpdateCheck() {
    println(dim("Checking for updates..."))
    when (val r = UpdateChecker.check()) {
        is UpdateChecker.Result.Available ->
            println(yellow("→ ") + bold("Update available: v${r.latest}") + dim(" (you have v${r.current})") + "\n  ${r.url}")
        is UpdateChecker.Result.UpToDate ->
            println(green("✓") + " You are on the latest version (v${r.current}).")
        is UpdateChecker.Result.Ahead ->
            println(yellow("→ ") + "I see what you did there in build.gradle. (local v${r.current}, GitHub v${r.latest})")
        is UpdateChecker.Result.Failed ->
            err(yellow("!") + " GitHub is not responding: ${r.reason}")
    }
}

/**
 * Automatic update checker.
 *
 * It never runs on the first launch: the welcome screen must first inform the
 * user of its existence and how to disable it. Contacting GitHub while the user
 * is still reading that screen would make any subsequent choice meaningless,
 * because the request would have already been sent.
 */
private fun maybeNotifyUpdate(args: CliArgs) {
    if (args.quiet || args.json || args.controlCmd != null) return
    if (!SettingsRepository.hasSeenCliWelcome()) return
    if (!SettingsRepository.isAutoUpdateCheckEnabled()) return
    val r = UpdateChecker.check(timeoutMs = 2500)
    if (r is UpdateChecker.Result.Available) {
        println(yellow("→ ") + "Update available: v${r.latest} (you have v${r.current})  ${dim(r.url)}")
        println(dim("  Disable this with: wfas --auto-check-update off"))
    }
}

fun runCli(rawArgs: CliArgs) {
    AppDebug.enabled = rawArgs.debug
    val stored = SettingsRepository.loadSettings()
    val args = resolveAuthKey(rawArgs, stored)
    val settings = args.latency
        ?.let { stored.copy(audio = stored.audio.copy(latencyMs = it)) }
        ?: stored
    PairCli.applyInviteToNetwork(args)

    NetAddr.configureFamily(args.ipFamily)
    UsbLink.configure(
        args.usb ?: settings.app.usbModeEnabled,
        args.usbLatency ?: settings.app.usbLatencyMs,
        args.usbIface ?: settings.app.usbInterface,
        override = args.usb != null || args.usbLatency != null || args.usbIface != null
    )
    WfasPolicy.configure(args.wfasMode ?: settings.app.wfasMode, override = args.wfasMode != null)

    val firstCliRun = !SettingsRepository.hasSeenCliWelcome()
    if (firstCliRun && args.controlCmd == null && !args.json) {
        printCliWelcome()
        SettingsRepository.markCliWelcomeSeen()
    }

    if (!firstCliRun) maybeNotifyUpdate(args)

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
            RunMode.CLI_MONITOR  -> runCliMonitor(args, settings)
            RunMode.CLI_RTP      -> runCliRtp(args, settings)
            RunMode.CLI_SNAPCAST -> runCliSnapcast(args, settings)
            else -> Unit
        }
    }
}

private suspend fun runCliMonitor(args: CliArgs, settings: AllSettings) {
    val audio = settings.audio

    if (!AudioEngine.loadLibrary()) {
        out(red("!") + " Native audio engine not available: ${AudioEngine.getLoadError() ?: "unknown error"}", args)
        return
    }

    val viz = AudioVisualizer(
        channels = audio.channels,
        label = "monitor  system audio",
        sampleRate = audio.sampleRate.toInt(),
        theme = args.vizTheme,
        volumeEnabled = false,
        groove = args.groove
    )
    val done = CompletableDeferred<Unit>()
    viz.onQuit = { if (!done.isCompleted) done.complete(Unit) }
    viz.statusMsg = ""
    viz.start()

    val bufFrames = (audio.sampleRate.toInt() * 10 / 1000).coerceAtLeast(256)
    val engine = AudioEngine(
        sampleRate   = audio.sampleRate.toInt(),
        channels     = audio.channels,
        bufferFrames = bufFrames,
        muteRender   = false
    )
    if (!engine.start()) {
        viz.stop()
        out(red("!") + " Cannot start audio capture: ${engine.lastError}", args)
        return
    }

    coroutineScope {
        val job = launch(Dispatchers.IO) {
            while (isActive) {
                val frame = engine.readFrame() ?: break
                if (frame.isNotEmpty()) viz.feedFrame(frame)
            }
        }
        done.await()
        job.cancelAndJoin()
    }

    engine.stop()
    viz.stop()
}

// ─────────────────────────────────────────────────────────────────────────────
// Server mode
// ─────────────────────────────────────────────────────────────────────────────

private fun printSnapcastClients(args: CliArgs) {
    val session = SnapcastStatus.session.value
    if (!session.running) {
        out(dim("  Snapcast server is not running"), args)
        return
    }
    if (args.json) {
        jsonLine(
            "event" to "snapcast_status",
            "codec" to session.codec,
            "clients" to session.clients.count { it.connected },
            "control_connections" to session.controlConnections
        )
        return
    }
    out("", args)
    out("  ${bold("Snapcast")} ${dim("codec")} ${session.codec} ${dim("format")} ${session.sampleFormat} " +
            "${dim("drift")} ${session.driftMicros / 1000}ms", args)
    if (session.clients.isEmpty()) {
        out(dim("  no clients known yet"), args)
    } else {
        session.clients.forEach { client ->
            val marker = if (client.connected) green("+") else dim("-")
            val mute = if (client.muted) yellow(" muted") else ""
            out("  $marker  ${client.name} ${dim(client.ip)}  vol ${client.volumePercent}%$mute " +
                    "${dim("latency")} ${client.latency}ms", args)
        }
    }
    out("", args)
}

/**
 * I comandi da tastiera del server CLI. Vive fuori dal ciclo di lettura perche'
 * ora le righe arrivano da [ConsoleInput], che le consegna solo quando nessun
 * prompt le sta aspettando.
 */
private fun handleServerConsoleCommand(
    raw: String,
    args: CliArgs,
    done: kotlinx.coroutines.CompletableDeferred<Unit>
) {
    val line = raw.trim()
    if (line.isEmpty()) return
    when {
        line.equals("q", ignoreCase = true) ||
                line.equals("quit", ignoreCase = true) ||
                line.equals("stop", ignoreCase = true) -> {
            kotlinx.coroutines.runBlocking { NetworkHandler_v1.stopCurrentStream() }
            if (!done.isCompleted) done.complete(Unit)
        }
        line.matches(Regex("(?i)v(?:ol(?:ume)?)?\\s+(\\d+(?:\\.\\d+)?)")) -> {
            val pct = line.split("\\s+".toRegex()).last().toFloatOrNull() ?: return
            NetworkHandler_v1.setServerVolume((pct / 100f).coerceIn(0f, 2f))
            if (!args.quiet && !args.json) println("  volume: ${pct.toInt()}%")
        }
        line.equals("p", ignoreCase = true) ||
                line.equals("pair", ignoreCase = true) ||
                line.equals("qr", ignoreCase = true) -> PairCli.serverInvite(args)
        line.equals("r", ignoreCase = true) ||
                line.equals("rekey", ignoreCase = true) -> PairCli.serverInvite(args, forceNewKey = true)
        line.equals("s", ignoreCase = true) ||
                line.equals("snapcast", ignoreCase = true) -> printSnapcastClients(args)
    }
}

private suspend fun runCliServer(rawArgs: CliArgs, settings: AllSettings) {
    val args = if (rawArgs.qr) prepareQrKey(rawArgs) else rawArgs
    assertPortFree(args.port,    "streaming", args)
    assertPortFree(args.micPort, "mic",       args)
    if (args.rtp)  assertPortFree(args.rtpPort,  "RTP",  args)
    if (args.http) assertPortFree(args.httpPort,  "HTTP", args)
    if (args.snapcast) {
        assertPortFree(args.snapcastPort, "Snapcast stream", args)
        assertPortFree(args.snapcastControlPort, "Snapcast control", args)
    }

    val audio = settings.audio
    // I flag valgono per questa esecuzione, l'impostazione salvata (anche dalla GUI)
    // e' il default.
    val muteRender = args.muteRender ?: settings.app.muteRender
    val persist    = args.persist    ?: settings.app.serverPersist
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
            "snapcast"  to args.snapcast,
            "snapcast_port" to args.snapcastPort,
            "snapcast_control_port" to args.snapcastControlPort,
            "snapcast_codec" to args.snapcastCodec,
            "mute_render"   to muteRender,
            "persist"       to persist,
        )
    } else if (!args.viz) {
        out("", args)
        out(bold("  WiFi Audio Streaming") + "  - server mode", args)
        out("  ${dim("IP")}      ${cyan(NetAddr.hostPort(serverIp, args.port))}", args)
        out("  ${dim("Multicast")} ${if (args.multicast) green("enabled") else dim("disabled")}", args)
        if (args.rtp)  out("  ${dim("RTP")}     port ${args.rtpPort}", args)
        if (args.http) out("  ${dim("HTTP")}    http://$serverIp:${args.httpPort}", args)
        if (args.snapcast) {
            out("  ${dim("Snapcast")} stream ${args.snapcastPort}, control ${args.snapcastControlPort}, codec ${args.snapcastCodec}", args)
        }
        if (args.mic)  out("  ${dim("Mic")}     ${args.micRouting.name.lowercase().replace('_', '-')}", args)
        if (!muteRender) out("  ${dim("Speakers")} ${green("kept on")} ${dim("(local playback is not muted)")}", args)
        if (persist) out(
            "  ${dim("Persist")} " + if (args.multicast)
                dim("implicit in multicast: there is no per-client session to end")
            else
                "${green("on")} ${dim("- waits for the next client instead of exiting")}",
            args
        )
        reportLink(args)
        out("", args)
        out(
            dim(
                "  Commands: q=stop, v <0-100>=volume" +
                        (if (args.snapcast) ", s=snapcast clients" else "") +
                        (if (args.qr) ", p=new invite, r=new key" else "")
            ),
            args
        )
        out("", args)
    } else {
        reportLink(args)
    }

    val viz = if (args.viz && !args.json)
        AudioVisualizer(channels = audio.channels, label = "server  ${serverIp}:${args.port}", sampleRate = audio.sampleRate.toInt(), theme = args.vizTheme, groove = args.groove)
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

    NetworkHandler_v1.configureSecurity(args.authMode, args.authKey, args.encrypt)
    // 'wfas pair invite' da un altro terminale chiede l'invito a questo
    // processo, perche' e' qui che vive la chiave. Solo con --qr: su un server
    // partito con una chiave scelta dall'utente, emettere un invito vorrebbe
    // dire sostituirgliela sotto i piedi.
    if (args.qr) PairRuntime.issuer = PairRuntime.cliIssuer(args)
    if (args.authMode == "ASK") {
        if (!ConsoleInput.hasTty && viz == null && !args.json) {
            err(yellow("!") + " Security mode is ASK but this session has no terminal:")
            err(dim("    every client will be refused. Use --auth-mode key for unattended servers."))
        }
        val prompt = CliAuthPrompt(
            timeoutMs = args.askTimeoutSec * 1000L,
            jsonMode = args.json,
            visualizer = viz,
            log = { msg ->
                if (args.json) jsonLine("event" to "auth_request", "message" to msg)
                else err(dim("  .  $msg"))
            }
        )
        NetworkHandler_v1.onAuthRequest = { peer -> prompt.decide(peer) }
    }

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
        muteRender      = muteRender,
        persist         = persist,
        dlnaConfig      = SettingsRepository.loadSettings().app
            .copy(dlnaEnabled = args.dlna, dlnaPort = args.dlnaPort.toString(), dlnaFormat = args.dlnaFormat)
            .toDlnaConfig(),
        snapcastConfig  = SettingsRepository.loadSettings().app
            .copy(
                snapcastEnabled = args.snapcast,
                snapcastPort = args.snapcastPort.toString(),
                snapcastControlPort = args.snapcastControlPort.toString(),
                snapcastCodec = args.snapcastCodec,
                snapcastChunkMs = args.snapcastChunkMs,
                snapcastBufferMs = args.snapcastBufferMs,
                snapcastStreamName = args.snapcastStreamName
            )
            .toSnapcastConfig(),
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
        // Senza --persist il server e' una sessione sola: quando il client se ne
        // va il processo ha finito. Con --persist il loop unicast resta in ascolto,
        // quindi qui non c'e' niente da chiudere.
        if (key.contains("disconnect") && !persist) {
            if (!done.isCompleted) done.complete(Unit)
        }
    }

    if (args.qr && viz != null && !args.json) {
        err(yellow("!") + " --qr and --viz share the terminal: run 'wfas pair invite' elsewhere for the code.")
    }
    if (args.qr && viz == null) {
        if (args.multicast) {
            var waited = 0
            while (NetworkHandler_v1.mcastSession.value == null && waited < 4000) {
                delay(100); waited += 100
            }
        } else {
            delay(250)
        }
        PairCli.serverInvite(args)
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
        // Un solo lettore su stdin per tutto il processo: quando un prompt di
        // autorizzazione e' aperto le righe vanno li', altrimenti qui.
        ConsoleInput.start { line -> handleServerConsoleCommand(line, args, done) }
        done.await()
        ConsoleInput.stop()
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
        err(dim("    Run 'wfas devices' to see what Java Sound reports on this system."))
        kotlin.system.exitProcess(ExitCode.RESOURCE_ERROR)
    }

    val serverInfo = if (args.serverIp != null) {
        connectDirect(args, settings)
    } else {
        discoverAndChoose(args)
    } ?: kotlin.system.exitProcess(ExitCode.NOT_FOUND)

    val micInput = if (args.sendMic) resolveInputDevice(args.micInput) else null

    if (args.volume != null) NetworkHandler_v1.setClientVolume(args.volume)
    if (args.mute)           NetworkHandler_v1.isMicMuted.value = true

    if (args.json) {
        jsonLine("event" to "client_connecting", "pid" to ProcessHandle.current().pid(), "server" to serverInfo.ip, "port" to serverInfo.port)
    } else if (!args.viz) {
        out("", args)
        out(bold("  WiFi Audio Streaming") + "  - client mode", args)
        out("  ${dim("Connecting to")}  ${cyan(serverInfo.ip)}:${serverInfo.port}", args)
        out("  ${dim("Output")}         ${outputDevice.name}", args)
        if (args.sendMic) out("  ${dim("Mic")}            ${micInput?.name ?: "default"}", args)
        reportLink(args)
        out("", args)
        out(dim("  Commands: q=disconnect, v <0-100>=volume"), args)
        out("", args)
    }

    val viz = if (args.viz && !args.json)
        AudioVisualizer(channels = settings.audio.channels, label = "client  ${serverInfo.ip}:${serverInfo.port}", sampleRate = settings.audio.sampleRate.toInt(), theme = args.vizTheme, groove = args.groove)
    else null

    val done = kotlinx.coroutines.CompletableDeferred<Unit>()
    val connected = kotlinx.coroutines.CompletableDeferred<Unit>()
    var userStopped = false
    var cliExitCode = ExitCode.OK
    val keySlot = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.CompletableDeferred<String?>?>(null)

    if (viz != null) {
        args.volume?.let { viz.setVolumePercent((it * 100).toInt()) }
        viz.onVolume = { v -> NetworkHandler_v1.setClientVolume(v.coerceIn(0f, 2f)) }
        viz.onQuit = {
            userStopped = true
            kotlinx.coroutines.runBlocking { NetworkHandler_v1.stopCurrentStream() }
            if (!done.isCompleted) done.complete(Unit)
        }
    }
    viz?.start()

    NetworkHandler_v1.configureSecurity(args.authMode, args.authKey, args.encrypt)
    NetworkHandler_v1.clientPresharedKey = args.authKey
    if (viz == null && !args.json) {
        NetworkHandler_v1.onKeyRequest = { wrong ->
            val slot = kotlinx.coroutines.CompletableDeferred<String?>()
            keySlot.set(slot)
            if (wrong) System.err.println(Strings.get("key_dialog_wrong"))
            System.err.print(Strings.get("key_dialog_body") + " ")
            System.err.flush()
            slot.await()?.trim()?.takeIf { it.isNotBlank() }
        }
    }

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
        if (key.contains("connected") && !connected.isCompleted) connected.complete(Unit)
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
                    val line = reader.readLine() ?: break
                    val pendingKey = keySlot.getAndSet(null)
                    if (pendingKey != null) {
                        pendingKey.complete(line)
                        continue
                    }
                    val cmd = line.trim()
                    when {
                        cmd.equals("q", ignoreCase = true) ||
                                cmd.equals("quit", ignoreCase = true) ||
                                cmd.equals("stop", ignoreCase = true) ||
                                cmd.equals("disconnect", ignoreCase = true) -> {
                            userStopped = true
                            kotlinx.coroutines.runBlocking { NetworkHandler_v1.stopCurrentStream() }
                            done.complete(Unit)
                        }
                        cmd.matches(Regex("(?i)v(?:ol(?:ume)?)?\\s+(\\d+(?:\\.\\d+)?)")) -> {
                            val pct = cmd.split("\\s+".toRegex()).last().toFloatOrNull() ?: continue
                            NetworkHandler_v1.setClientVolume((pct / 100f).coerceIn(0f, 2f))
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

private suspend fun awaitServerAnnouncement(ip: String, timeoutMs: Long): ServerInfo? {
    val target = NetAddr.normalize(ip)
    val found = CompletableDeferred<ServerInfo>()
    return try {
        NetworkHandler_v1.beginDeviceDiscovery { _, info ->
            if (info.lastSeen != 0L &&
                NetAddr.normalize(info.ip) == target &&
                !found.isCompleted
            ) found.complete(info)
        }
        withTimeoutOrNull(timeoutMs) { found.await() }
    } catch (_: Exception) {
        null
    } finally {
        NetworkHandler_v1.endDeviceDiscovery()
    }
}

private suspend fun connectDirect(args: CliArgs, settings: AllSettings): ServerInfo? = coroutineScope {
    val ip = args.serverIp!!
    if (!args.json && !args.quiet)
        out("  ${dim("Probing")} $ip:${args.port}...", args)

    val announcement = async { awaitServerAnnouncement(ip, 2000) }

    val isMulticast = withTimeoutOrNull(2000) {
        NetworkHandler_v1.probeIsMulticast(ip, args.port)
    } ?: true

    val announced = announcement.await()
    val advertised = announced?.serverAudioSettings

    if (!args.json && !args.quiet) {
        if (advertised != null) {
            out("  ${dim("Stream format")} ${advertised.sampleRate.toInt()} Hz, " +
                    "${advertised.channels} ch, ${advertised.bitDepth}-bit", args)
        } else {
            err(yellow("!") + " The server did not announce its audio format; assuming this machine's settings.")
            err(dim("    If they differ from the server's, playback speed and pitch will be wrong."))
        }
    }

    ServerInfo(
        ip = ip,
        isMulticast = isMulticast,
        port = args.port,
        capabilities = announced?.capabilities,
        serverAudioSettings = advertised
    )
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
        out("  ${bold("${i + 1}.")}  ${cyan(hostname)}  ${dim(NetAddr.hostPort(info.ip, info.port))}", args)
    }
    out("", args)
    print("  Enter number [1-${found.size}]: ")
    val choice = readLine()?.toIntOrNull()?.minus(1) ?: return null
    return found.values.toList().getOrNull(choice)
}

// ─────────────────────────────────────────────────────────────────────────────
// Discover mode
// ─────────────────────────────────────────────────────────────────────────────

private fun discoverMode(info: ServerInfo): String {
    val protos = info.capabilities?.protocols ?: setOf(StreamingProtocol.WFAS)
    return when {
        StreamingProtocol.RTP in protos  -> "RTP"
        StreamingProtocol.HTTP in protos -> "HTTP"
        else                             -> if (info.isMulticast) "UDP/M" else "UDP"
    }
}

private fun discoverSecurity(info: ServerInfo): String {
    val caps = info.capabilities
    val mode = caps?.securityMode?.uppercase()
    return when {
        mode == "KEY" && caps?.encrypted == true -> "Encryption"
        mode == "KEY" || mode == "ASK"           -> "Authentication"
        else                                     -> "None"
    }
}

private fun discoverLink(info: ServerInfo): String =
    (if (info.viaUsb) "USB" else "WIFI") + "/" + (if (NetAddr.isV6Literal(info.ip)) "v6" else "v4")

private fun printDiscoverHeader(args: CliArgs) {
    out("  " + bold("HOST".padEnd(18)) + " " + bold("ADDRESS".padEnd(28)) + " " +
            bold("MODE".padEnd(6)) + " " + bold("LINK".padEnd(8)) + " " + bold("SECURITY"), args)
}

private fun emitDiscover(args: CliArgs, host: String, info: ServerInfo) {
    val mode     = discoverMode(info)
    val security = discoverSecurity(info)
    if (args.json) {
        val protocols = info.capabilities?.protocols?.joinToString("+") { it.name } ?: "WFAS"
        jsonLine(
            "event"     to "server_found",
            "hostname"  to host,
            "ip"        to info.ip,
            "port"      to info.port,
            "multicast" to info.isMulticast,
            "protocols" to protocols,
            "mode"      to mode,
            "security"  to security,
            "auth"      to (info.capabilities?.securityMode ?: "OFF"),
            "encrypted" to (info.capabilities?.encrypted ?: false),
            "transport" to (if (info.viaUsb) "usb" else "wifi"),
            "family"    to (if (NetAddr.isV6Literal(info.ip)) "ipv6" else "ipv4")
        )
    } else {
        val addr = NetAddr.hostPort(info.ip, info.port)
        out("  " + cyan(host.padEnd(18)) + " " + addr.padEnd(28) + " " +
                mode.padEnd(6) + " " + discoverLink(info).padEnd(8) + " " + security, args)
    }
}

private suspend fun runCliDiscover(args: CliArgs) {
    if (!args.json && !args.quiet) {
        out(bold("  WiFi Audio Streaming") + "  - discover mode", args)
        out("  ${dim(if (args.watch) "Scanning... (Ctrl+C to stop)" else "Scanning network (5s)...")}", args)
        out("", args)
    }

    if (args.debug && !args.json) {
        val usbIface = UsbLink.detectedInterface()
        err(dim("  USB link: iface=${usbIface?.name ?: "none"} " +
                "display=${usbIface?.displayName ?: "-"} " +
                "streamingEnabled=${UsbLink.enabled} ready=${UsbLink.isReady()}"))
        err(dim("  Interfaces seen:"))
        UsbLink.inspect().forEach { err(dim("    $it")) }
        err("")
    }

    if (args.watch) {
        if (!args.json && !args.quiet) printDiscoverHeader(args)
        val seen = mutableSetOf<String>()
        NetworkHandler_v1.beginDeviceDiscovery { hostname, serverInfo ->
            if (serverInfo.lastSeen == 0L) {
                seen.remove(hostname)
                if (args.json) jsonLine("event" to "server_gone", "hostname" to hostname)
                else if (!args.quiet) out("  " + red("-") + " $hostname ${dim("(gone)")}", args)
            } else if (hostname !in seen) {
                seen += hostname
                emitDiscover(args, hostname, serverInfo)
            }
        }
        withContext(Dispatchers.IO) {
            try { while (true) delay(1000) } catch (_: CancellationException) {}
        }
    } else {
        val found = LinkedHashMap<String, ServerInfo>()
        NetworkHandler_v1.beginDeviceDiscovery { hostname, serverInfo ->
            if (serverInfo.lastSeen == 0L) found.remove(hostname) else found[hostname] = serverInfo
        }
        delay(5000)
        NetworkHandler_v1.endDeviceDiscovery()
        if (found.isEmpty()) {
            if (args.json) jsonLine("event" to "discover_empty")
            else out("  ${dim("No servers found.")}", args)
            return
        }
        if (!args.json && !args.quiet) printDiscoverHeader(args)
        for ((host, info) in found.entries.sortedBy { it.value.ip }) emitDiscover(args, host, info)
    }
}
// ─────────────────────────────────────────────────────────────────────────────
// RTP receive mode
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Ascolta un flusso RTP altrui.
 *
 * A differenza del client WFAS qui non c'e' nessuna stretta di mano: si apre
 * una porta e si aspetta. Per questo lo stato "in attesa" e' visibile e
 * separato da "sto ricevendo": senza distinguerli, una porta sbagliata e un
 * mittente spento sarebbero la stessa schermata muta.
 */
private suspend fun runCliRtp(args: CliArgs, settings: AllSettings) {
    val source = when (val r = RtpCli.resolve(args)) {
        is RtpCli.Resolution.Fail -> {
            RtpCli.reportFailure(r, args.json)
            kotlin.system.exitProcess(r.code)
        }
        is RtpCli.Resolution.Ok -> {
            RtpCli.printWarnings(r.warnings, args.json, args.quiet)
            r.source
        }
    }

    val outputDevice = resolveOutputDevice(args.outputDevice)
    if (outputDevice == null) {
        err(red("!") + " No audio output device found.")
        err(dim("    Run 'wfas devices' to see what Java Sound reports on this system."))
        kotlin.system.exitProcess(ExitCode.RESOURCE_ERROR)
    }

    val latencyMs = args.latency ?: settings.audio.latencyMs
    if (args.volume != null) NetworkHandler_v1.setClientVolume(args.volume)
    var muted = args.mute
    if (muted) NetworkHandler_v1.setClientVolume(0f)

    val where = if (source.address.isBlank()) ":${source.port}" else "${source.address}:${source.port}"

    if (args.json) {
        jsonLine(
            "event" to "rtp_listening", "pid" to ProcessHandle.current().pid(),
            "address" to source.address, "port" to source.port,
            "codec" to source.encoding, "rate" to source.sampleRate,
            "channels" to source.channels, "payload" to source.payloadType,
            "multicast" to source.isMulticast, "native" to source.isNativePcm,
            "output" to outputDevice.name, "latency" to latencyMs
        )
    } else if (!args.viz) {
        out("", args)
        out(bold("  WiFi Audio Streaming") + "  - RTP receive", args)
        out("  ${dim("Source")}   ${cyan(where)} ${dim(if (source.isMulticast) "multicast" else "unicast")}", args)
        out("  ${dim("Format")}   ${source.formatSummary()}", args)
        out("  ${dim("Path")}     " + (if (source.isNativePcm) green("native L16") else yellow("FFmpeg")), args)
        out("  ${dim("Output")}   ${outputDevice.name}", args)
        out("  ${dim("Buffer")}   ${latencyMs} ms", args)
        out("", args)
        out(dim("  Commands: q=stop, v <0-100>=volume, m=mute, u=unmute, s=stats"), args)
        out("", args)
    }

    val viz = if (args.viz && !args.json)
        AudioVisualizer(
            channels = source.channels, label = "rtp  $where",
            sampleRate = source.sampleRate, theme = args.vizTheme, groove = args.groove
        )
    else null

    val done = CompletableDeferred<Unit>()
    var userStopped = false
    var exitCode = ExitCode.OK
    var last = RtpStatus()

    if (viz != null) {
        args.volume?.let { viz.setVolumePercent((it * 100).toInt()) }
        viz.onVolume = { v -> NetworkHandler_v1.setClientVolume(v.coerceIn(0f, 2f)) }
        viz.onQuit = { userStopped = true; if (!done.isCompleted) done.complete(Unit) }
        viz.statusMsg = "waiting for packets..."
    }
    viz?.start()

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val receiver = RtpReceiver(
        source = source,
        onStatus = { st ->
            val before = last.state
            last = st
            if (st.state != before) {
                when (st.state) {
                    RtpState.WAITING -> {
                        if (args.json) jsonLine("event" to "rtp_waiting", "port" to source.port)
                        else if (viz != null) viz.statusMsg = ".  waiting for packets"
                        else out("  ${dim(".")}  waiting for packets on $where", args)
                    }
                    RtpState.PLAYING -> {
                        if (args.json) jsonLine("event" to "rtp_playing", "port" to source.port)
                        else if (viz != null) viz.statusMsg = "+  receiving"
                        else out("  ${green("+")}  receiving", args)
                        // Se il flusso non e' quello descritto, dirlo: e'
                        // l'unica occasione in cui l'utente puo' correggere il
                        // proprio SDP invece di sentire un audio giusto e non
                        // sapere perche' i numeri che aveva scritto erano altri.
                        val realRate = st.detectedSampleRate
                        val realCh   = st.detectedChannels
                        if (realRate != null && realCh != null &&
                            (realRate != source.sampleRate || realCh != source.channels)) {
                            val was = "${source.sampleRate} Hz " +
                                    (if (source.channels == 1) "mono" else "${source.channels} ch")
                            val now = "$realRate Hz " + (if (realCh == 1) "mono" else "$realCh ch")
                            if (args.json) {
                                jsonLine(
                                    "event" to "rtp_format_detected",
                                    "sample_rate" to realRate, "channels" to realCh
                                )
                            } else if (viz != null) {
                                viz.statusMsg = "+  receiving  ($now)"
                            } else {
                                out("  ${yellow("~")}  the stream is $now, not $was: " +
                                        "playing what the stream says", args)
                            }
                        }
                    }
                    RtpState.ERROR -> {
                        val msg = Strings.get(st.errorKey ?: "rtp_err_socket") +
                                (st.errorDetail?.let { ": $it" } ?: "")
                        if (args.json) jsonLine("event" to "rtp_error", "message" to msg)
                        else if (viz != null) viz.statusMsg = "!  $msg"
                        else err(red("!") + " $msg")
                        exitCode = ExitCode.RESOURCE_ERROR
                        if (!done.isCompleted) done.complete(Unit)
                    }
                    RtpState.IDLE -> Unit
                }
            }
        },
        onPcm = viz?.let { v -> { samples -> v.feedFrame(samples) } },
        openPlayer = { rate, ch ->
            NetworkHandler_v1.openPcmPlaybackSink(outputDevice, rate, ch, latencyMs)
        },
        preferredInterface = args.networkIface
    )

    fun stats(): String =
        "${last.packets} packets, ${last.bytes / 1024} KB, ${last.lostPackets} lost, " +
                "buffer ${last.bufferMs} ms"

    ExternalReceiver.kind = "rtp"
    ExternalReceiver.detail = where
    ExternalReceiver.onStop = { userStopped = true; if (!done.isCompleted) done.complete(Unit) }
    ExternalReceiver.onVolume = { v -> NetworkHandler_v1.setClientVolume(v.coerceIn(0f, 2f)) }
    ExternalReceiver.onMute = { m ->
        muted = m
        NetworkHandler_v1.setClientVolume(if (m) 0f else (args.volume ?: 1f))
    }
    ExternalReceiver.statusFields = {
        mapOf(
            "rtp_address" to source.address, "rtp_port" to source.port,
            "rtp_codec" to source.encoding, "rtp_rate" to source.sampleRate,
            "rtp_channels" to source.channels, "rtp_native" to source.isNativePcm,
            "rtp_detected_rate" to (last.detectedSampleRate ?: source.sampleRate),
            "rtp_detected_channels" to (last.detectedChannels ?: source.channels),
            "rtp_state" to last.state.name.lowercase(), "rtp_packets" to last.packets,
            "rtp_lost" to last.lostPackets, "rtp_buffer_ms" to last.bufferMs,
            "rtp_muted" to muted
        )
    }

    receiver.start(scope)

    if (viz == null) {
        ConsoleInput.start { raw ->
            val line = raw.trim()
            when {
                line.equals("q", true) || line.equals("quit", true) || line.equals("stop", true) -> {
                    userStopped = true
                    if (!done.isCompleted) done.complete(Unit)
                }
                line.matches(Regex("(?i)v(?:ol(?:ume)?)?\\s+(\\d+(?:\\.\\d+)?)")) -> {
                    val pct = line.split("\\s+".toRegex()).last().toFloatOrNull()
                    if (pct != null) {
                        muted = false
                        NetworkHandler_v1.setClientVolume((pct / 100f).coerceIn(0f, 2f))
                        if (!args.quiet && !args.json) println("  volume: ${pct.toInt()}%")
                    }
                }
                line.equals("m", true) || line.equals("mute", true) -> {
                    muted = true; NetworkHandler_v1.setClientVolume(0f)
                    if (!args.quiet && !args.json) println("  muted")
                }
                line.equals("u", true) || line.equals("unmute", true) -> {
                    muted = false; NetworkHandler_v1.setClientVolume(args.volume ?: 1f)
                    if (!args.quiet && !args.json) println("  unmuted")
                }
                line.equals("s", true) || line.equals("stats", true) -> {
                    if (args.json) jsonLine(
                        "event" to "rtp_stats", "packets" to last.packets, "bytes" to last.bytes,
                        "lost" to last.lostPackets, "buffer_ms" to last.bufferMs
                    ) else println("  ${dim(stats())}")
                }
            }
        }
    }

    if (args.debug && !args.viz && !args.json) DebugHud.start(sending = false, peer = where)

    done.await()

    ConsoleInput.stop()
    receiver.stop()
    scope.cancel()
    ExternalReceiver.clear()
    DebugHud.stop()
    viz?.stop()

    if (args.json) jsonLine(
        "event" to "rtp_stopped", "packets" to last.packets,
        "bytes" to last.bytes, "lost" to last.lostPackets
    ) else if (!args.quiet && !args.viz) out("  ${dim(stats())}", args)

    if (!userStopped && exitCode != ExitCode.OK) kotlin.system.exitProcess(exitCode)
}

// ─────────────────────────────────────────────────────────────────────────────
// Snapcast client mode
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Client Snapcast: audio sincronizzato con tutti gli altri client del server.
 *
 * Due connessioni distinte, come vuole il protocollo: l'audio sulla 1704 e il
 * controllo sulla 1705. Il controllo e' accessorio — se non risponde l'audio
 * suona lo stesso — ma senza di lui non si vede la stanza intera e il volume
 * di questa macchina non si puo' cambiare da nessuna parte, quindi si dice a
 * voce alta quando manca invece di lasciarlo intuire.
 */
private suspend fun runCliSnapcast(args: CliArgs, settings: AllSettings) {
    val server = SnapcastCli.resolveServer(args) ?: kotlin.system.exitProcess(ExitCode.NOT_FOUND)

    // 'mixer' e' questa stessa sessione con una schermata al posto delle righe
    // di stato: riproduce come l'ascolto. Senza terminale, o in JSON, si torna
    // alle righe — una schermata ANSI dentro una pipe non serve a nessuno.
    val wantMixer = args.snapCmd is SnapCommand.Mixer
    val useMixer  = wantMixer && !args.json && SnapcastMixer.usable()
    // Il telecomando puro: si comanda l'impianto senza entrarci come client.
    val noAudio   = wantMixer && args.snapNoAudio

    if (useMixer && args.viz) err(
        yellow("!") + " --viz and the mixer both want the whole screen: showing the mixer."
    )

    val outputDevice = if (noAudio) null else resolveOutputDevice(args.outputDevice)
    if (outputDevice == null && !noAudio) {
        err(red("!") + " No audio output device found.")
        err(dim("    Run 'wfas devices' to see what Java Sound reports on this system."))
        err(dim("    'wfas snapcast mixer --no-audio' controls the system without playing."))
        kotlin.system.exitProcess(ExitCode.RESOURCE_ERROR)
    }

    val clientId = args.snapClientId ?: NetworkHandler_v1.localClientId()
    val clientName = args.snapClientName
        ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("WFAS")

    if (args.json) {
        jsonLine(
            "event" to "snap_connecting", "pid" to ProcessHandle.current().pid(),
            "host" to server.host, "stream_port" to server.streamPort,
            "control_port" to server.controlPort, "client_id" to clientId,
            "client_name" to clientName, "output" to (outputDevice?.name ?: ""),
            "audio" to !noAudio, "mixer" to useMixer
        )
    } else if (!args.viz && !useMixer) {
        out("", args)
        out(bold("  WiFi Audio Streaming") + "  - Snapcast client", args)
        out("  ${dim("Server")}   ${cyan(server.host)} ${dim("audio")} ${server.streamPort} ${dim("control")} ${server.controlPort}", args)
        out("  ${dim("As")}       $clientName ${dim(clientId)}", args)
        out("  ${dim("Output")}   ${outputDevice?.name ?: "(not playing)"}", args)
        out("", args)
        out(dim("  Commands: q=stop, v <0-100>=volume, m=mute, u=unmute, s=status, g=groups, l <ms>=latency"), args)
        out("", args)
    }

    val viz = if (args.viz && !args.json && !useMixer)
        AudioVisualizer(
            channels = settings.audio.channels, label = "snapcast  ${server.host}",
            sampleRate = settings.audio.sampleRate.toInt(), theme = args.vizTheme, groove = args.groove
        )
    else null

    val done = CompletableDeferred<Unit>()
    var userStopped = false
    var exitCode = ExitCode.OK
    var stream = SnapStreamStatus(server = server)
    var control = SnapControlStatus()
    var streamError: String? = null

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val ctrl = SnapcastControlClient(server.host, server.controlPort) { st -> control = st }

    /** Il nostro volume passa dal server: e' lui a decidere, e gli altri devono vederlo. */
    fun setOwnVolume(percent: Int, mute: Boolean): Boolean {
        val me = control.status.client(clientId) ?: return false
        return ctrl.setClientVolume(me.id, percent.coerceIn(0, 100), mute)
    }

    val client = if (noAudio) null else SnapcastStreamClient(
        server = server,
        clientId = clientId,
        clientName = clientName,
        onStatus = { st ->
            val before = stream.state
            stream = st
            if (st.state != before) {
                when (st.state) {
                    SnapStreamState.CONNECTING -> {
                        if (args.json) jsonLine("event" to "snap_state", "state" to "connecting")
                        else if (viz != null) viz.statusMsg = ".  connecting"
                        else if (!useMixer) out("  ${dim(".")}  connecting to ${server.host}:${server.streamPort}", args)
                    }
                    SnapStreamState.BUFFERING -> {
                        if (args.json) jsonLine(
                            "event" to "snap_state", "state" to "buffering",
                            "codec" to st.codec, "rate" to st.sampleRate,
                            "channels" to st.channels, "buffer" to st.bufferMs
                        ) else if (viz != null) viz.statusMsg = ".  syncing"
                        else if (!useMixer) out("  ${dim(".")}  ${st.codec} ${st.sampleRate} Hz, ${st.channels} ch, " +
                                "buffer ${st.bufferMs} ms - syncing", args)
                    }
                    SnapStreamState.PLAYING -> {
                        if (args.json) jsonLine("event" to "snap_state", "state" to "playing")
                        else if (viz != null) viz.statusMsg = "+  playing"
                        else if (!useMixer) out("  ${green("+")}  playing", args)
                    }
                    SnapStreamState.ERROR -> {
                        val msg = Strings.get(st.errorKey ?: "snap_err_stream") +
                                (st.errorDetail?.let { ": $it" } ?: "")
                        streamError = msg
                        if (args.json) jsonLine("event" to "snap_error", "message" to msg)
                        else if (viz != null) viz.statusMsg = "!  $msg"
                        else if (!useMixer) err(red("!") + " $msg")
                        exitCode = ExitCode.DISCONNECTED
                        if (!done.isCompleted) done.complete(Unit)
                    }
                    SnapStreamState.IDLE -> Unit
                }
            }
        },
        onPcm = viz?.let { v -> { samples -> v.feedFrame(samples) } },
        openPlayer = { rate, ch, bufferMs ->
            NetworkHandler_v1.openPcmPlaybackSink(outputDevice, rate, ch, bufferMs)
        }
    )

    if (viz != null) {
        viz.onVolume = { v -> if (!setOwnVolume((v * 100).toInt(), false)) NetworkHandler_v1.setClientVolume(v) }
        viz.onQuit = { userStopped = true; if (!done.isCompleted) done.complete(Unit) }
        viz.statusMsg = "connecting..."
    }
    viz?.start()

    ctrl.start(scope)
    client?.start(scope)

    // Volume, mute e latenza iniziali passano dal canale di controllo, che
    // pero' arriva dopo: si aspetta il primo stato invece di sparare comandi
    // su un client che il server non ha ancora registrato.
    if (args.volume != null || args.mute || args.latency != null) {
        scope.launch {
            var waited = 0
            while (waited < 8000 && control.status.client(clientId) == null) { delay(200); waited += 200 }
            val me = control.status.client(clientId)
            if (me == null) {
                if (!args.json && !args.quiet) err(
                    yellow("!") + " The control channel did not list this client, so --volume, --mute " +
                            "and --latency could not be applied on the server."
                )
                args.volume?.let { NetworkHandler_v1.setClientVolume(it) }
            } else {
                val pct = args.volume?.let { (it * 100).toInt() } ?: me.volumePercent
                if (args.volume != null || args.mute) ctrl.setClientVolume(me.id, pct.coerceIn(0, 100), args.mute)
                args.latency?.let { ctrl.setClientLatency(me.id, it.coerceIn(-2000, 2000)) }
            }
        }
    }

    ExternalReceiver.kind = "snapcast"
    ExternalReceiver.detail = "${server.host}:${server.streamPort}"
    ExternalReceiver.onStop = { userStopped = true; if (!done.isCompleted) done.complete(Unit) }
    ExternalReceiver.onVolume = { v ->
        if (!setOwnVolume((v * 100).toInt(), false)) NetworkHandler_v1.setClientVolume(v)
    }
    ExternalReceiver.onMute = { m ->
        val me = control.status.client(clientId)
        if (me != null) ctrl.setClientVolume(me.id, me.volumePercent, m)
        else NetworkHandler_v1.setClientVolume(if (m) 0f else 1f)
    }
    ExternalReceiver.statusFields = {
        val me = control.status.client(clientId)
        mapOf(
            "snap_host" to server.host, "snap_stream_port" to server.streamPort,
            "snap_control_port" to server.controlPort,
            "snap_state" to stream.state.name.lowercase(), "snap_codec" to stream.codec,
            "snap_rate" to stream.sampleRate, "snap_channels" to stream.channels,
            "snap_buffer_ms" to stream.bufferMs,
            "snap_sync_error_ms" to String.format("%.2f", stream.syncErrorMs),
            "snap_clock_offset_ms" to String.format("%.2f", stream.clockOffsetMs),
            "snap_control" to control.state.name.lowercase(),
            "snap_groups" to control.status.groups.size,
            "snap_clients" to control.status.allClients.size,
            "snap_volume" to (me?.volumePercent ?: stream.volumePercent),
            "snap_muted" to (me?.muted ?: stream.muted)
        )
    }

    fun printRoom() {
        val st = control.status
        if (args.json) {
            jsonLine("event" to "snap_room", "groups" to st.groups.size, "clients" to st.allClients.size)
            return
        }
        if (control.state != SnapControlState.CONNECTED) {
            println("  " + yellow("!") + " control channel: ${control.state.name.lowercase()}" +
                    (control.errorDetail?.let { " (${it})" } ?: ""))
            return
        }
        st.groups.forEachIndexed { gi, g ->
            println("  ${bold("#${gi + 1} ${g.displayName()}")}${dim("  stream ")}${g.streamId}" +
                    (if (g.muted) " " + yellow("muted") else ""))
            g.clients.forEach { c ->
                val me = if (c.id == clientId) cyan(" (this machine)") else ""
                val vol = if (c.muted) yellow("muted") else "${c.volumePercent}%"
                println("      ${if (c.connected) green("+") else dim("-")} ${c.displayName().padEnd(24)}" +
                        "$vol${dim("  latency ")}${c.latencyMs}ms$me")
            }
        }
    }

    fun printStatus() {
        if (args.json) {
            jsonLine(
                "event" to "snap_stats", "state" to stream.state.name.lowercase(),
                "codec" to stream.codec, "rate" to stream.sampleRate,
                "channels" to stream.channels, "buffer_ms" to stream.bufferMs,
                "playout_ms" to stream.playoutBufferMs,
                "sync_error_ms" to String.format("%.2f", stream.syncErrorMs),
                "clock_offset_ms" to String.format("%.2f", stream.clockOffsetMs),
                "control" to control.state.name.lowercase()
            )
            return
        }
        println("  ${dim("stream")} ${stream.state.name.lowercase()}  ${stream.codec} " +
                "${stream.sampleRate} Hz ${stream.channels} ch  ${dim("buffer")} ${stream.bufferMs} ms  " +
                "${dim("sync")} ${String.format("%.1f", stream.syncErrorMs)} ms  " +
                "${dim("control")} ${control.state.name.lowercase()}")
    }

    /** Quel che il mixer mostra dell'audio: una riga, quella che manca a colpo d'occhio. */
    fun audioLine(): String {
        if (noAudio) return dim("remote control only — this machine is not playing")
        streamError?.let { return red("! ") + it }
        val s = stream
        val word = when (s.state) {
            SnapStreamState.PLAYING    -> green("playing")
            SnapStreamState.BUFFERING  -> yellow("syncing")
            SnapStreamState.CONNECTING -> yellow("connecting")
            SnapStreamState.ERROR      -> red("error")
            SnapStreamState.IDLE       -> dim("idle")
        }
        val fmt = if (s.sampleRate > 0) "${s.codec} ${s.sampleRate} Hz ${s.channels} ch" else ""
        return "$word  " + dim(fmt) +
                (if (s.bufferMs > 0) dim("  buffer ") + "${s.bufferMs} ms" else "") +
                (if (s.state == SnapStreamState.PLAYING)
                    dim("  sync ") + String.format("%.1f ms", s.syncErrorMs) else "") +
                dim("  out ") + (outputDevice?.name ?: "-")
    }

    if (useMixer) {
        SnapcastMixer.run(
            client = ctrl,
            statusOf = { control },
            selfId = clientId,
            title = "${server.host}:${server.controlPort}",
            audioLine = { audioLine() },
            stopWhen = { done.isCompleted }
        )
        // Se il ciclo e' finito da solo (stream caduto, 'wfas control stop')
        // l'uscita l'ha gia' decisa qualcun altro, e non e' un'uscita voluta.
        if (!done.isCompleted) { userStopped = true; done.complete(Unit) }
    } else if (viz == null) {
        ConsoleInput.start { raw ->
            val line = raw.trim()
            when {
                line.equals("q", true) || line.equals("quit", true) || line.equals("stop", true) -> {
                    userStopped = true
                    if (!done.isCompleted) done.complete(Unit)
                }
                line.matches(Regex("(?i)v(?:ol(?:ume)?)?\\s+(\\d+(?:\\.\\d+)?)")) -> {
                    val pct = line.split("\\s+".toRegex()).last().toFloatOrNull()?.toInt()
                    if (pct != null) {
                        if (setOwnVolume(pct, false)) {
                            if (!args.quiet && !args.json) println("  volume: ${pct.coerceIn(0, 100)}%")
                        } else {
                            NetworkHandler_v1.setClientVolume((pct / 100f).coerceIn(0f, 2f))
                            if (!args.quiet && !args.json)
                                println("  volume: $pct% ${dim("(locally: the control channel is not available)")}")
                        }
                    }
                }
                line.equals("m", true) || line.equals("mute", true)   -> { ExternalReceiver.onMute?.invoke(true);  if (!args.quiet && !args.json) println("  muted") }
                line.equals("u", true) || line.equals("unmute", true) -> { ExternalReceiver.onMute?.invoke(false); if (!args.quiet && !args.json) println("  unmuted") }
                line.matches(Regex("(?i)l(?:at(?:ency)?)?\\s+(-?\\d+)")) -> {
                    val ms = line.split("\\s+".toRegex()).last().toIntOrNull()
                    val me = control.status.client(clientId)
                    if (ms != null && me != null) {
                        ctrl.setClientLatency(me.id, ms.coerceIn(-2000, 2000))
                        if (!args.quiet && !args.json) println("  latency: ${ms.coerceIn(-2000, 2000)} ms")
                    } else if (!args.quiet && !args.json) {
                        println("  ${yellow("!")} the control channel does not list this client yet")
                    }
                }
                line.equals("s", true) || line.equals("status", true) -> printStatus()
                line.equals("g", true) || line.equals("groups", true) -> printRoom()
            }
        }
    }

    // L'indicatore di debug scrive righe: dentro lo schermo del mixer le
    // sovrascriverebbe a caso.
    if (args.debug && !args.viz && !args.json && !useMixer)
        DebugHud.start(sending = false, peer = "${server.host}:${server.streamPort}")

    done.await()

    ConsoleInput.stop()
    client?.stop()
    ctrl.stop()
    scope.cancel()
    ExternalReceiver.clear()
    DebugHud.stop()
    viz?.stop()

    if (args.json) jsonLine("event" to "snap_stopped")
    if (!userStopped && exitCode != ExitCode.OK) kotlin.system.exitProcess(exitCode)
}
