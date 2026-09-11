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
 *
 * --------------------------------------------------------------------------
 * SnapcastCli.kt
 *
 * Il lato terminale del client Snapcast: 'wfas snapcast ...'.
 *
 * Due canali, come nel protocollo: quello audio (porta 1704) lo apre CliMode
 * quando si ascolta, e quello di controllo (porta 1705) lo apre qui, per un
 * comando alla volta. Il canale di controllo e' accessorio per definizione —
 * se non risponde l'audio continua lo stesso — quindi qui un errore non e' un
 * disastro da nascondere: si dice a chi ha scritto il comando cosa non ha
 * funzionato e a quale indirizzo, che e' l'unica informazione che serve.
 */

import kotlinx.coroutines.*

object SnapcastCli {

    private fun dim(t: String)    = Ansi.dim(t)
    private fun bold(t: String)   = Ansi.bold(t)
    private fun cyan(t: String)   = Ansi.cyan(t)
    private fun green(t: String)  = Ansi.green(t)
    private fun red(t: String)    = Ansi.red(t)
    private fun yellow(t: String) = Ansi.yellow(t)

    /** Quanto si aspetta il primo stato completo dal canale di controllo. */
    private const val CONTROL_TIMEOUT_MS = 8_000L
    /** Quanto dura una ricerca mDNS che deve finire da sola. */
    const val DISCOVER_MS = 4_000L

    /**
     * I verbi di 'wfas snapcast'.
     *
     * Servono a riconoscere l'errore di battitura piu' probabile:
     * 'wfas snapcast listen mixer' e' sintatticamente valido — chiede di
     * collegarsi a un computer che si chiama "mixer" — e senza questo elenco
     * fallirebbe con un errore di rete, che non aiuta nessuno a capire.
     */
    val VERBS = listOf(
        "listen", "connect", "play", "join", "discover", "browse", "scan",
        "status", "state", "show", "mixer", "ui", "tui", "top",
        "clients", "groups", "streams", "servers", "saved", "save", "add",
        "forget", "remove", "rm", "delete", "volume", "vol", "mute", "unmute",
        "latency", "delay", "rename", "group-mute", "group-rename", "group-name",
        "group-stream", "move", "split", "detach"
    )

    private val CLEAR_SCREEN = "\u001B[2J\u001B[H"

    private fun jsonEscape(s: String) =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun jsonLine(vararg pairs: Pair<String, Any?>) {
        println(pairs.joinToString(", ", "{", "}") { (k, v) ->
            val vs = when (v) {
                null       -> "null"
                is Boolean -> v.toString()
                is Number  -> v.toString()
                else       -> "\"" + jsonEscape(v.toString()) + "\""
            }
            "\"$k\": $vs"
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Server salvati e ricerca
    // ─────────────────────────────────────────────────────────────────────────

    fun saved(): List<SnapcastServerRef> =
        SettingsRepository.loadSettings().app.snapcastServers.mapNotNull { SnapcastServerRef.deserialize(it) }

    private fun store(list: List<SnapcastServerRef>) {
        val s = SettingsRepository.loadSettings()
        SettingsRepository.saveSettings(
            s.copy(app = s.app.copy(snapcastServers = list.map { it.serialize() }))
        )
    }

    fun savedByRef(ref: String, list: List<SnapcastServerRef> = saved()): SnapcastServerRef? {
        val r = ref.trim()
        if (r.isEmpty() || list.isEmpty()) return null
        r.removePrefix("#").toIntOrNull()?.let { n ->
            if (r.startsWith("#") && n in 1..list.size) return list[n - 1]
        }
        list.firstOrNull { it.name.equals(r, ignoreCase = true) }?.let { return it }
        list.firstOrNull { it.host.equals(r, ignoreCase = true) }?.let { return it }
        list.firstOrNull { it.key == r }?.let { return it }
        return list.filter { it.name.contains(r, ignoreCase = true) }.singleOrNull()
    }

    /**
     * Una ricerca mDNS che finisce: si ascolta [ms], poi si restituisce quel
     * che si e' visto.
     *
     * Lo scope e' tutto suo, e non si aspetta che muoia: il ciclo del browser
     * e' I/O bloccante e puo' metterci fino a un secondo ad accorgersi di
     * doversi fermare. Un coroutineScope qui aspetterebbe quel secondo — e,
     * quando il browser non si fermava affatto, aspettava per sempre.
     *
     * La mappa la scrive il thread del browser e la legge questo: concorrente,
     * quindi, o si leggerebbe una lista a meta'.
     */
    suspend fun discover(ms: Long, iface: String): List<SnapcastServerRef> {
        val found = java.util.concurrent.ConcurrentHashMap<String, SnapcastServerRef>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val browser = SnapcastBrowser(
            scope = scope,
            interfaceProvider = { NetworkHandler_v1.getActiveNetworkInterface(iface) },
            onServers = { list -> list.forEach { found[it.key] = it } }
        )
        browser.start()
        try {
            delay(ms)
        } finally {
            browser.stop()
            scope.cancel()
        }
        return found.values.sortedBy { it.displayName().lowercase() }
    }

    /**
     * Il server su cui agire.
     *
     * Come per RTP si va dall'esplicito all'implicito: --snap-host, poi
     * l'argomento posizionale, poi l'unico salvato, e solo alla fine si va a
     * cercarlo in rete. Le porte dei flag hanno sempre l'ultima parola, cosi'
     * un server su porte non standard resta raggiungibile anche se e' stato
     * trovato via mDNS con quelle sbagliate.
     */
    suspend fun resolveServer(args: CliArgs, announce: Boolean = true): SnapcastServerRef? {
        val spec = args.snapSpec?.trim()?.takeIf { it.isNotEmpty() }
        var ref: SnapcastServerRef? = null

        if (args.snapHost != null) {
            ref = SnapcastServerRef(name = "", host = args.snapHost, discovered = false)
        } else if (spec != null) {
            ref = savedByRef(spec) ?: parseHost(spec)
        }

        // Un nome che questa macchina non sa risolvere non diventa un errore di
        // rete tre secondi piu' tardi: si dice subito, e se sembra un verbo si
        // dice anche quello.
        val named = ref
        if (named != null && !named.discovered) {
            val resolves = runCatching { java.net.InetAddress.getByName(named.host) }.isSuccess
            if (!resolves) {
                val verb = VERBS.firstOrNull { it.equals(named.host, ignoreCase = true) }
                fail(
                    args,
                    "'${named.host}' is not a name this machine can resolve.",
                    if (verb != null) "'$verb' is a command, not a server: you probably meant " +
                            "'wfas snapcast $verb'."
                    else "Check the spelling, or give the address directly."
                )
                return null
            }
        }

        if (ref == null) {
            val list = saved()
            if (list.size == 1) ref = list.first()
        }

        if (ref == null) {
            if (announce && !args.json && !args.quiet)
                System.err.println(dim("  Looking for Snapcast servers..."))
            val seen = discover(DISCOVER_MS, args.networkIface)
            ref = when {
                seen.size == 1 -> seen.first()
                seen.isEmpty() -> {
                    val known = saved()
                    fail(
                        args,
                        "no Snapcast server answered on this network.",
                        if (known.isNotEmpty())
                            "Saved: " + known.joinToString(", ") { "${it.displayName()} (${it.host})" } +
                                    ". Name one of them, or give an address directly."
                        else
                            "Name one with 'wfas snapcast listen <host>'. mDNS travels by " +
                                    "multicast and gets dropped easily — Wi-Fi client isolation and " +
                                    "VPNs are the usual culprits. Add --debug to see what came back."
                    )
                    return null
                }
                else -> {
                    fail(args, "${seen.size} Snapcast servers are on this network, so one has to be named.",
                        "Seen: " + seen.joinToString(", ") { "${it.displayName()} (${it.host})" })
                    return null
                }
            }
        }

        return ref.copy(
            streamPort  = args.snapPort ?: ref.streamPort,
            controlPort = args.snapControlPortCli ?: ref.controlPort
        )
    }

    /** `host`, `host:porta-audio` oppure `[ipv6]:porta`. */
    fun parseHost(spec: String): SnapcastServerRef? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return null
            val host = s.substring(1, close)
            val port = s.substring(close + 1).removePrefix(":").toIntOrNull() ?: SnapcastDefaults.STREAM_PORT
            return SnapcastServerRef(name = "", host = host, streamPort = port,
                controlPort = if (port == SnapcastDefaults.STREAM_PORT) SnapcastDefaults.CONTROL_PORT else port + 1,
                discovered = false)
        }
        val colons = s.count { it == ':' }
        if (colons == 1) {
            val host = s.substringBefore(':')
            val port = s.substringAfter(':').toIntOrNull() ?: return null
            return SnapcastServerRef(name = "", host = host, streamPort = port,
                controlPort = if (port == SnapcastDefaults.STREAM_PORT) SnapcastDefaults.CONTROL_PORT else port + 1,
                discovered = false)
        }
        if (colons > 1) return SnapcastServerRef(name = "", host = s, discovered = false)   // IPv6 nudo
        return SnapcastServerRef(name = "", host = s, discovered = false)
    }

    private fun fail(args: CliArgs, message: String, hint: String? = null) {
        if (args.json) jsonLine("event" to "snap_error", "message" to message, "hint" to hint)
        else {
            System.err.println(red("!") + " " + message)
            hint?.let { System.err.println(dim("    $it")) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Canale di controllo, per un comando solo
    // ─────────────────────────────────────────────────────────────────────────

    private class Control(
        val client: SnapcastControlClient,
        val scope: CoroutineScope,
        val server: SnapcastServerRef
    ) {
        @Volatile var last: SnapControlStatus = SnapControlStatus()
        val status: SnapServerStatus get() = last.status
        fun close() { client.stop(); scope.cancel() }
    }

    /**
     * Apre il controllo, aspetta il primo stato completo, esegue, chiude.
     *
     * Lo stato "completo" e' quello che il client pubblica come CONNECTED: la
     * connessione da sola non basta, perche' fino alla risposta di
     * Server.GetStatus non c'e' niente su cui lavorare.
     */
    private suspend fun withControl(args: CliArgs, block: suspend (Control) -> Int): Int {
        val server = resolveServer(args) ?: return ExitCode.NOT_FOUND
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val ready = CompletableDeferred<Unit>()
        val holder = arrayOfNulls<Control>(1)
        val client = SnapcastControlClient(server.host, server.controlPort) { st ->
            holder[0]?.last = st
            if (st.state == SnapControlState.CONNECTED && !ready.isCompleted) ready.complete(Unit)
        }
        val ctrl = Control(client, scope, server)
        holder[0] = ctrl
        client.start(scope)
        val ok = withTimeoutOrNull(CONTROL_TIMEOUT_MS) { ready.await() } != null
        if (!ok) {
            ctrl.close()
            fail(
                args,
                "the Snapcast control channel at ${server.host}:${server.controlPort} did not answer.",
                (ctrl.last.errorDetail?.let { "Last error: $it. " } ?: "") +
                        "The control port is usually the audio port plus one; " +
                        "override it with --snap-control-port."
            )
            return ExitCode.NOT_FOUND
        }
        return try { block(ctrl) } finally { ctrl.close() }
    }

    /** Aspetta che il server confermi, cosi' quel che si stampa dopo e' vero. */
    private suspend fun settle(ctrl: Control) {
        delay(400)
        ctrl.client.refresh()
        delay(400)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Risoluzione di client e gruppi
    // ─────────────────────────────────────────────────────────────────────────

    fun findClient(status: SnapServerStatus, ref: String): SnapClientInfo? {
        val all = status.allClients
        val r = ref.trim()
        if (r.isEmpty() || all.isEmpty()) return null
        r.removePrefix("#").toIntOrNull()?.let { n ->
            if (r.startsWith("#") && n in 1..all.size) return all[n - 1]
        }
        all.firstOrNull { it.id.equals(r, ignoreCase = true) }?.let { return it }
        all.firstOrNull { it.displayName().equals(r, ignoreCase = true) }?.let { return it }
        all.firstOrNull { it.hostName.equals(r, ignoreCase = true) }?.let { return it }
        all.firstOrNull { it.ip == r }?.let { return it }
        return all.filter { it.displayName().contains(r, ignoreCase = true) }.singleOrNull()
    }

    fun findGroup(status: SnapServerStatus, ref: String): SnapGroupInfo? {
        val all = status.groups
        val r = ref.trim()
        if (r.isEmpty() || all.isEmpty()) return null
        r.removePrefix("#").toIntOrNull()?.let { n ->
            if (r.startsWith("#") && n in 1..all.size) return all[n - 1]
        }
        all.firstOrNull { it.id.equals(r, ignoreCase = true) }?.let { return it }
        all.firstOrNull { it.displayName().equals(r, ignoreCase = true) }?.let { return it }
        return all.filter { it.displayName().contains(r, ignoreCase = true) }.singleOrNull()
    }

    private fun noClient(args: CliArgs, ref: String, status: SnapServerStatus): Int {
        fail(args, "no Snapcast client matches '$ref'.",
            "Known: " + status.allClients.mapIndexed { i, c -> "#${i + 1} ${c.displayName()}" }
                .joinToString(", ").ifBlank { "(none)" })
        return ExitCode.NOT_FOUND
    }

    private fun noGroup(args: CliArgs, ref: String, status: SnapServerStatus): Int {
        fail(args, "no Snapcast group matches '$ref'.",
            "Known: " + status.groups.mapIndexed { i, g -> "#${i + 1} ${g.displayName()}" }
                .joinToString(", ").ifBlank { "(none)" })
        return ExitCode.NOT_FOUND
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stampa
    // ─────────────────────────────────────────────────────────────────────────

    private fun clientLine(index: Int, c: SnapClientInfo, groupName: String?): String {
        val mark = if (c.connected) green("+") else dim("-")
        val vol = (if (c.muted) yellow("muted".padEnd(6)) else "${c.volumePercent}%".padEnd(6))
        return "  $mark ${"#$index".padEnd(4)}${cyan(c.displayName().take(22).padEnd(24))}" +
                "${c.ip.padEnd(17)}$vol${dim("lat")} ${"${c.latencyMs}ms".padEnd(8)}" +
                (groupName?.let { dim("in ") + it } ?: "")
    }

    private fun printStatus(ctrl: Control, args: CliArgs) {
        val st = ctrl.status
        if (args.json) {
            jsonLine(
                "event" to "snap_status", "host" to ctrl.server.host,
                "control_port" to ctrl.server.controlPort,
                "server" to st.serverName, "version" to st.serverVersion,
                "groups" to st.groups.size, "clients" to st.allClients.size,
                "streams" to st.streams.size
            )
            emitGroups(st)
            emitClients(st)
            emitStreams(st)
            return
        }
        println()
        println("  " + bold("Snapcast") + "  " + cyan("${ctrl.server.host}:${ctrl.server.controlPort}") +
                dim("  ${st.serverName} ${st.serverVersion}"))
        println()
        if (st.groups.isEmpty()) {
            println("  " + dim("No group on this server yet."))
            println()
            return
        }
        var n = 0
        st.groups.forEachIndexed { gi, g ->
            val mute = if (g.muted) yellow(" muted") else ""
            println("  " + bold("#${gi + 1} ${g.displayName()}") + dim("  stream ") + g.streamId + mute)
            if (g.clients.isEmpty()) println("      " + dim("(empty)"))
            g.clients.forEach { c -> n++; println(clientLine(n, c, null)) }
            println()
        }
        if (st.streams.isNotEmpty()) {
            println("  " + bold("Streams"))
            st.streams.forEach { s ->
                val col = if (s.status.equals("playing", true)) green(s.status) else dim(s.status)
                println("    ${s.id.padEnd(20)}$col${if (s.uri.isNotBlank()) dim("  " + s.uri) else ""}")
            }
            println()
        }
    }

    private fun emitClients(st: SnapServerStatus) {
        var n = 0
        st.groups.forEach { g ->
            g.clients.forEach { c ->
                n++
                jsonLine(
                    "event" to "snap_client", "index" to n, "id" to c.id,
                    "name" to c.displayName(), "host" to c.hostName, "ip" to c.ip,
                    "connected" to c.connected, "volume" to c.volumePercent,
                    "muted" to c.muted, "latency" to c.latencyMs,
                    "group" to g.id, "group_name" to g.displayName(),
                    "os" to c.os, "arch" to c.arch, "version" to c.version
                )
            }
        }
    }

    private fun emitGroups(st: SnapServerStatus) {
        st.groups.forEachIndexed { i, g ->
            jsonLine(
                "event" to "snap_group", "index" to i + 1, "id" to g.id,
                "name" to g.displayName(), "muted" to g.muted,
                "stream" to g.streamId, "clients" to g.clients.size
            )
        }
    }

    private fun emitStreams(st: SnapServerStatus) {
        st.streams.forEachIndexed { i, s ->
            jsonLine("event" to "snap_stream", "index" to i + 1, "id" to s.id,
                "status" to s.status, "uri" to s.uri)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Comandi
    // ─────────────────────────────────────────────────────────────────────────

    fun run(cmd: SnapCommand, args: CliArgs): Int = runBlocking {
        when (cmd) {
            // Riproducono entrambi, quindi li apre runCli come sessione.
            is SnapCommand.Listen   -> ExitCode.OK
            is SnapCommand.Mixer    -> ExitCode.OK
            is SnapCommand.Discover -> discoverCmd(args)
            is SnapCommand.Servers  -> serversCmd(args)
            is SnapCommand.Save     -> saveCmd(cmd.name, args)
            is SnapCommand.Forget   -> forgetCmd(cmd.target, args)

            is SnapCommand.Status   -> view(args) { c -> printStatus(c, args) }
            is SnapCommand.Clients  -> view(args) { c -> printClients(c, args) }
            is SnapCommand.Groups   -> view(args) { c -> printGroups(c, args) }
            is SnapCommand.Streams  -> view(args) { c -> printStreams(c, args) }

            is SnapCommand.Volume   -> onClient(args, cmd.client) { c, cl ->
                c.client.setClientVolume(cl.id, cmd.percent, cl.muted)
                settle(c)
                report(args, "volume", cl, c)
            }
            is SnapCommand.Mute     -> onClient(args, cmd.client) { c, cl ->
                c.client.setClientVolume(cl.id, cl.volumePercent, cmd.muted)
                settle(c)
                report(args, if (cmd.muted) "mute" else "unmute", cl, c)
            }
            is SnapCommand.Latency  -> onClient(args, cmd.client) { c, cl ->
                c.client.setClientLatency(cl.id, cmd.ms)
                settle(c)
                report(args, "latency", cl, c)
            }
            is SnapCommand.Rename   -> onClient(args, cmd.client) { c, cl ->
                c.client.setClientName(cl.id, cmd.name)
                settle(c)
                report(args, "rename", cl, c)
            }
            is SnapCommand.Split    -> onClient(args, cmd.client) { c, cl -> splitClient(args, c, cl) }
            is SnapCommand.Move     -> onClient(args, cmd.client) { c, cl -> moveClient(args, c, cl, cmd.group) }

            is SnapCommand.GroupMute   -> onGroup(args, cmd.group) { c, g ->
                c.client.setGroupMute(g.id, cmd.muted); settle(c); reportGroup(args, "mute", g, c)
            }
            is SnapCommand.GroupRename -> onGroup(args, cmd.group) { c, g ->
                c.client.setGroupName(g.id, cmd.name); settle(c); reportGroup(args, "rename", g, c)
            }
            is SnapCommand.GroupStream -> onGroup(args, cmd.group) { c, g ->
                val known = c.status.streams.map { it.id }
                if (known.isNotEmpty() && cmd.stream !in known) {
                    fail(args, "'${cmd.stream}' is not a stream on this server.",
                        "Available: " + known.joinToString(", "))
                    return@onGroup ExitCode.NOT_FOUND
                }
                c.client.setGroupStream(g.id, cmd.stream); settle(c); reportGroup(args, "stream", g, c)
            }
        }
    }

    private suspend fun view(args: CliArgs, render: (Control) -> Unit): Int = withControl(args) { c ->
        if (!args.watch) { render(c); return@withControl ExitCode.OK }
        // --watch: si ridisegna quando lo stato cambia davvero, non a orologio.
        // Ridisegnare a intervallo fisso fa lampeggiare la finestra per niente.
        var shown = ""
        var running = true
        while (running) {
            val fingerprint = c.status.toString()
            if (fingerprint != shown) {
                shown = fingerprint
                if (!args.json) print(CLEAR_SCREEN)
                render(c)
            }
            delay(500)
        }
        ExitCode.OK
    }

    private suspend fun onClient(
        args: CliArgs,
        ref: String,
        block: suspend (Control, SnapClientInfo) -> Int
    ): Int = withControl(args) { c ->
        val cl = findClient(c.status, ref) ?: return@withControl noClient(args, ref, c.status)
        block(c, cl)
    }

    private suspend fun onGroup(
        args: CliArgs,
        ref: String,
        block: suspend (Control, SnapGroupInfo) -> Int
    ): Int = withControl(args) { c ->
        val g = findGroup(c.status, ref) ?: return@withControl noGroup(args, ref, c.status)
        block(c, g)
    }

    private fun report(args: CliArgs, what: String, before: SnapClientInfo, c: Control): Int {
        val now = c.status.client(before.id) ?: before
        if (args.json) jsonLine(
            "event" to "snap_client_set", "what" to what, "id" to now.id,
            "name" to now.displayName(), "volume" to now.volumePercent,
            "muted" to now.muted, "latency" to now.latencyMs
        ) else println(
            "  " + green("+") + " " + cyan(now.displayName()) + "  " +
                    (if (now.muted) yellow("muted") else "${now.volumePercent}%") +
                    dim("  latency ") + "${now.latencyMs}ms"
        )
        return ExitCode.OK
    }

    private fun reportGroup(args: CliArgs, what: String, before: SnapGroupInfo, c: Control): Int {
        val now = c.status.groups.firstOrNull { it.id == before.id } ?: before
        if (args.json) jsonLine(
            "event" to "snap_group_set", "what" to what, "id" to now.id,
            "name" to now.displayName(), "muted" to now.muted, "stream" to now.streamId
        ) else println(
            "  " + green("+") + " " + cyan(now.displayName()) + "  " +
                    (if (now.muted) yellow("muted") else "unmuted") +
                    dim("  stream ") + now.streamId
        )
        return ExitCode.OK
    }

    /**
     * Sposta un client in un gruppo esistente.
     *
     * Snapcast non ha un comando "sposta": l'unico disponibile riscrive
     * l'INTERA composizione del gruppo di destinazione, quindi si prendono i
     * suoi client attuali e si aggiunge quello spostato. Al vecchio gruppo ci
     * pensa il server.
     */
    private suspend fun moveClient(args: CliArgs, c: Control, cl: SnapClientInfo, groupRef: String): Int {
        val target = findGroup(c.status, groupRef) ?: return noGroup(args, groupRef, c.status)
        if (target.clients.any { it.id == cl.id }) {
            if (args.json) jsonLine("event" to "snap_move", "id" to cl.id, "group" to target.id, "changed" to false)
            else println("  " + dim("• ") + cyan(cl.displayName()) + " is already in " + target.displayName() + ".")
            return ExitCode.OK
        }
        c.client.setGroupClients(target.id, (target.clients.map { it.id } + cl.id).distinct())
        settle(c)
        val landed = c.status.groups.firstOrNull { g -> g.clients.any { it.id == cl.id } }
        if (args.json) jsonLine(
            "event" to "snap_move", "id" to cl.id, "name" to cl.displayName(),
            "group" to (landed?.id ?: ""), "group_name" to (landed?.displayName() ?: ""),
            "changed" to (landed?.id == target.id)
        ) else println(
            "  " + green("+") + " " + cyan(cl.displayName()) + dim(" → ") + (landed?.displayName() ?: "?")
        )
        return if (landed?.id == target.id) ExitCode.OK else ExitCode.RESOURCE_ERROR
    }

    /**
     * Stacca un client nel suo gruppo, da solo.
     *
     * Anche qui non c'e' un comando apposta: si riscrive il gruppo attuale
     * senza di lui e il server dovrebbe dargliene uno nuovo. Non tutti lo
     * fanno, e chi non lo fa lascia il client orfano — invisibile ovunque, pur
     * continuando a suonare. Dalla riga di comando non possiamo far
     * riconnettere un client altrui, quindi si verifica e, se e' sparito, si
     * rimette dov'era e si dice perche'.
     */
    private suspend fun splitClient(args: CliArgs, c: Control, cl: SnapClientInfo): Int {
        val g = c.status.groups.firstOrNull { grp -> grp.clients.any { it.id == cl.id } }
            ?: return noClient(args, cl.id, c.status)
        if (g.clients.size <= 1) {
            if (args.json) jsonLine("event" to "snap_split", "id" to cl.id, "changed" to false)
            else println("  " + dim("• ") + cyan(cl.displayName()) + " is already alone in its group.")
            return ExitCode.OK
        }
        val original = g.clients.map { it.id }
        c.client.setGroupClients(g.id, original.filter { it != cl.id })
        settle(c)
        delay(700)
        c.client.refresh()
        delay(500)

        val landed = c.status.groups.firstOrNull { grp -> grp.clients.any { it.id == cl.id } }
        if (landed == null) {
            c.client.setGroupClients(g.id, original)
            settle(c)
            fail(args,
                "this server does not re-home a client detached from a group: ${cl.displayName()} " +
                        "disappeared from the server's own list while still playing, so it has been put back.",
                "The desktop app can still do it for the machine it runs on, by reconnecting " +
                        "its own Snapcast client — a reconnection nobody can command from here " +
                        "for someone else's client.")
            return ExitCode.RESOURCE_ERROR
        }
        if (args.json) jsonLine(
            "event" to "snap_split", "id" to cl.id, "name" to cl.displayName(),
            "group" to landed.id, "group_name" to landed.displayName(),
            "changed" to (landed.id != g.id)
        ) else println("  " + green("+") + " " + cyan(cl.displayName()) + dim(" → ") + landed.displayName())
        return ExitCode.OK
    }

    private fun printClients(c: Control, args: CliArgs) {
        val st = c.status
        if (args.json) { emitClients(st); return }
        if (st.allClients.isEmpty()) { println("  " + dim("No Snapcast client on this server.")); return }
        println()
        println("  " + " ".repeat(2) + bold("#".padEnd(4)) + bold("NAME".padEnd(24)) +
                bold("ADDRESS".padEnd(17)) + bold("VOL".padEnd(6)) + bold("LATENCY".padEnd(12)) + bold("GROUP"))
        var n = 0
        st.groups.forEach { g -> g.clients.forEach { cl -> n++; println(clientLine(n, cl, g.displayName())) } }
        println()
    }

    private fun printGroups(c: Control, args: CliArgs) {
        val st = c.status
        if (args.json) { emitGroups(st); return }
        if (st.groups.isEmpty()) { println("  " + dim("No group on this server.")); return }
        println()
        println("  " + bold("#".padEnd(4)) + bold("NAME".padEnd(24)) + bold("STREAM".padEnd(18)) +
                bold("STATE".padEnd(10)) + bold("CLIENTS"))
        st.groups.forEachIndexed { i, g ->
            println("  " + "#${i + 1}".padEnd(4) + cyan(g.displayName().take(22).padEnd(24)) +
                    g.streamId.padEnd(18) + (if (g.muted) yellow("muted") else green("on")).padEnd(10) +
                    g.clients.joinToString(", ") { it.displayName() })
        }
        println()
    }

    private fun printStreams(c: Control, args: CliArgs) {
        val st = c.status
        if (args.json) { emitStreams(st); return }
        if (st.streams.isEmpty()) { println("  " + dim("No stream on this server.")); return }
        println()
        println("  " + bold("#".padEnd(4)) + bold("ID".padEnd(22)) + bold("STATE".padEnd(12)) + bold("URI"))
        st.streams.forEachIndexed { i, s ->
            val col = if (s.status.equals("playing", true)) green(s.status) else dim(s.status)
            println("  " + "#${i + 1}".padEnd(4) + cyan(s.id.padEnd(22)) + col.padEnd(12) + dim(s.uri))
        }
        println()
    }

    private suspend fun discoverCmd(args: CliArgs): Int {
        if (!args.json && !args.quiet) {
            println()
            println("  " + bold("WiFi Audio Streaming") + "  - Snapcast discovery")
            println("  " + dim(if (args.watch) "Scanning... (Ctrl+C to stop)" else "Scanning network (${DISCOVER_MS / 1000}s)..."))
            println()
        }
        if (args.watch) {
            val seen = mutableSetOf<String>()
            coroutineScope {
                val browser = SnapcastBrowser(
                    scope = this,
                    interfaceProvider = { NetworkHandler_v1.getActiveNetworkInterface(args.networkIface) },
                    onServers = { list ->
                        list.filter { it.key !in seen }.forEach { s ->
                            seen += s.key
                            emitServer(args, s)
                        }
                    }
                )
                browser.start()
                while (true) delay(1000)
            }
            return ExitCode.OK
        }
        val list = discover(DISCOVER_MS, args.networkIface)
        if (list.isEmpty()) {
            if (args.json) jsonLine("event" to "snap_discover_empty")
            else println("  " + dim("No Snapcast server found."))
            return ExitCode.NOT_FOUND
        }
        if (!args.json && !args.quiet) printServerHeader()
        list.forEach { emitServer(args, it) }
        if (!args.json) println()
        return ExitCode.OK
    }

    private fun printServerHeader() {
        println("  " + bold("NAME".padEnd(26)) + bold("HOST".padEnd(24)) +
                bold("AUDIO".padEnd(8)) + bold("CONTROL"))
    }

    private fun emitServer(args: CliArgs, s: SnapcastServerRef) {
        if (args.json) jsonLine(
            "event" to "snap_server_found", "name" to s.displayName(), "host" to s.host,
            "stream_port" to s.streamPort, "control_port" to s.controlPort
        ) else println("  " + cyan(s.displayName().take(24).padEnd(26)) + s.host.padEnd(24) +
                s.streamPort.toString().padEnd(8) + s.controlPort)
    }

    private fun serversCmd(args: CliArgs): Int {
        val list = saved()
        if (args.json) {
            list.forEachIndexed { i, s ->
                jsonLine("event" to "snap_saved_server", "index" to i + 1, "name" to s.displayName(),
                    "host" to s.host, "stream_port" to s.streamPort, "control_port" to s.controlPort)
            }
            if (list.isEmpty()) jsonLine("event" to "snap_saved_empty")
            return ExitCode.OK
        }
        if (list.isEmpty()) {
            println("  " + dim("No Snapcast server saved yet."))
            println("  " + dim("Save one with 'wfas snapcast save <name> --snap-host 192.168.1.10'."))
            return ExitCode.OK
        }
        println()
        println("  " + bold("#".padEnd(4)) + bold("NAME".padEnd(26)) + bold("HOST".padEnd(24)) +
                bold("AUDIO".padEnd(8)) + bold("CONTROL"))
        list.forEachIndexed { i, s ->
            println("  " + "${i + 1}".padEnd(4) + cyan(s.displayName().take(24).padEnd(26)) +
                    s.host.padEnd(24) + s.streamPort.toString().padEnd(8) + s.controlPort)
        }
        println()
        return ExitCode.OK
    }

    private suspend fun saveCmd(nameArg: String?, args: CliArgs): Int {
        val server = resolveServer(args) ?: return ExitCode.NOT_FOUND
        val name = (nameArg ?: args.snapClientName ?: server.name).ifBlank { server.host }
        val entry = server.copy(name = name, discovered = false)
        val list = saved().filterNot { it.host == entry.host && it.streamPort == entry.streamPort } + entry
        store(list)
        if (args.json) jsonLine(
            "event" to "snap_saved", "name" to name, "host" to entry.host,
            "stream_port" to entry.streamPort, "control_port" to entry.controlPort, "total" to list.size
        ) else println("  " + green("+") + " Saved " + cyan(name) +
                dim("  ${entry.host}  audio ${entry.streamPort}, control ${entry.controlPort}"))
        return ExitCode.OK
    }

    private fun forgetCmd(target: String, args: CliArgs): Int {
        val list = saved()
        if (target.equals("all", ignoreCase = true)) {
            store(emptyList())
            if (args.json) jsonLine("event" to "snap_forgot", "removed" to list.size)
            else println("  " + green("+") + " Removed ${list.size} saved server(s).")
            return ExitCode.OK
        }
        val hit = savedByRef(target, list)
        if (hit == null) {
            fail(args, "no saved Snapcast server matches '$target'.",
                if (list.isEmpty()) "Nothing is saved yet."
                else "Saved: " + list.mapIndexed { i, s -> "#${i + 1} ${s.displayName()}" }.joinToString(", "))
            return ExitCode.NOT_FOUND
        }
        store(list.filterNot { it.host == hit.host && it.streamPort == hit.streamPort })
        if (args.json) jsonLine("event" to "snap_forgot", "name" to hit.displayName(), "removed" to 1)
        else println("  " + green("+") + " Removed " + cyan(hit.displayName()) + ".")
        return ExitCode.OK
    }
}
