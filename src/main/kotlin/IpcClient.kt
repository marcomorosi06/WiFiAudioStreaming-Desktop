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

import java.net.ConnectException
import java.net.Socket

object IpcClient {

    private const val MAX_KEY_ATTEMPTS = 3

    fun send(cmd: ControlCommand, args: CliArgs) {
        val sessions = IpcAuth.listSessions()
        if (sessions.isEmpty()) {
            printError("No running wfas instance found.", args)
            kotlin.system.exitProcess(ExitCode.NOT_FOUND)
        }

        val session = sessions.firstOrNull { it.authenticated }
        if (session == null) {
            val stale = sessions.first()
            printError(
                "The running instance (PID ${stale.pid}) predates the authenticated control channel. " +
                        "Restart it to use 'wfas control'.",
                args
            )
            kotlin.system.exitProcess(ExitCode.AUTH_FAILED)
        }

        val payload = buildRequest(cmd)
        var key = resolveKey(args)
        var attempt = 0
        var promptedOnce = false

        while (true) {
            val outcome = tryOnce(session, payload, key)

            when (outcome) {
                is Outcome.Answered -> {
                    if (args.json) println(outcome.response)
                    else prettyPrint(cmd, outcome.response, session.pid.toString(), args)
                    return
                }

                is Outcome.Unauthorized -> {
                    if (!outcome.keyRequired) {
                        // Token rifiutato: non e' un problema di chiave, e
                        // riprovare non cambia nulla.
                        printError("Refused by the running instance: ${outcome.message}", args)
                        kotlin.system.exitProcess(ExitCode.AUTH_FAILED)
                    }
                    attempt++
                    if (attempt >= MAX_KEY_ATTEMPTS) {
                        printError("Wrong key. Refusing after $MAX_KEY_ATTEMPTS attempts.", args)
                        kotlin.system.exitProcess(ExitCode.AUTH_FAILED)
                    }
                    val next = promptForKey(wrong = true, args = args)
                    if (next == null) {
                        printError("Wrong key.", args)
                        kotlin.system.exitProcess(ExitCode.AUTH_FAILED)
                    }
                    key = next
                    promptedOnce = true
                }

                is Outcome.KeyNeeded -> {
                    if (promptedOnce) {
                        printError("This instance requires the pre-shared key.", args)
                        kotlin.system.exitProcess(ExitCode.AUTH_FAILED)
                    }
                    val next = promptForKey(wrong = false, args = args)
                    if (next == null) {
                        printError(
                            "This instance runs in KEY mode. Set ${SettingsRepository.ENV_AUTH_KEY} " +
                                    "or pass --auth-key to control it.",
                            args
                        )
                        kotlin.system.exitProcess(ExitCode.AUTH_FAILED)
                    }
                    key = next
                    promptedOnce = true
                }

                is Outcome.Unreachable -> {
                    printError(outcome.message, args)
                    session.file.runCatching { delete() }
                    kotlin.system.exitProcess(ExitCode.NOT_FOUND)
                }

                is Outcome.Failed -> {
                    printError(outcome.message, args)
                    kotlin.system.exitProcess(ExitCode.RESOURCE_ERROR)
                }
            }
        }
    }

    // ── Un singolo tentativo completo di handshake ───────────────────────────

    private sealed class Outcome {
        data class Answered(val response: String) : Outcome()
        data class Unauthorized(val message: String, val keyRequired: Boolean) : Outcome()
        object KeyNeeded : Outcome()
        data class Unreachable(val message: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    private fun tryOnce(session: IpcAuth.Session, payload: String, key: String?): Outcome {
        return try {
            Socket(java.net.InetAddress.getLoopbackAddress(), session.port).use { socket ->
                socket.soTimeout = 5000
                val writer = socket.getOutputStream().bufferedWriter()
                val reader = socket.getInputStream().bufferedReader()

                val challenge = reader.readLine()
                    ?: return Outcome.Failed("The running instance closed the control channel.")

                if (challenge.contains("\"status\": \"unauthorized\"")) {
                    return Outcome.Unauthorized(field(challenge, "message") ?: "refused", false)
                }

                val nonce = field(challenge, "nonce")
                    ?: return Outcome.Failed("Malformed control handshake.")
                val keyRequired = challenge.contains("\"key_required\": true")

                if (keyRequired && key.isNullOrBlank()) return Outcome.KeyNeeded

                val proof = if (keyRequired) IpcAuth.proof(key!!, nonce, payload) else ""

                writer.write("AUTH ${session.token} $proof")
                writer.newLine()
                writer.write(payload)
                writer.newLine()
                writer.flush()

                val response = reader.readLine() ?: ""
                if (response.contains("\"status\": \"unauthorized\"")) {
                    Outcome.Unauthorized(
                        field(response, "message") ?: "unauthorized",
                        keyRequired || response.contains("\"key_required\": true")
                    )
                } else {
                    Outcome.Answered(response)
                }
            }
        } catch (e: ConnectException) {
            Outcome.Unreachable("Could not connect to wfas instance (PID ${session.pid}). Is it still running?")
        } catch (e: Exception) {
            Outcome.Failed("IPC error: ${e.message}")
        }
    }

    // ── Chiave ──────────────────────────────────────────────────────────────

    /**
     * L'ordine e' quello dell'esplicito prima dell'implicito: il flag batte
     * l'ambiente, l'ambiente batte la custodia di sistema. Nessuno di questi
     * chiede niente all'utente: il prompt arriva solo se il server risponde che
     * la chiave serve davvero.
     */
    private fun resolveKey(args: CliArgs): String? {
        if (args.authKey.isNotBlank()) return args.authKey
        return storedKey()
    }

    private fun storedKey(): String? {
        System.getenv(SettingsRepository.ENV_AUTH_KEY)?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { SettingsRepository.loadSettings().app.authKey }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Una richiesta sola, senza il ciclo di 'wfas control'.
     *
     * Serve a chi parla con l'istanza viva per altri motivi - un invito, un
     * deep link - e non ha una riga di comando da cui ricavare la chiave. Passa
     * comunque dallo stesso handshake: il canale di controllo non ha una porta
     * di servizio. Ritorna la risposta grezza, o null se non risponde nessuno o
     * l'autenticazione non passa.
     */
    fun requestRaw(payload: String): kotlin.Pair<IpcAuth.Session, String>? {
        val session = IpcAuth.listSessions().firstOrNull { it.authenticated } ?: return null
        return when (val outcome = tryOnce(session, payload, storedKey())) {
            is Outcome.Answered -> session to outcome.response
            else -> null
        }
    }

    private fun promptForKey(wrong: Boolean, args: CliArgs): String? {
        if (args.json) return null            // in JSON non si interrompe il flusso per chiedere
        val console = System.console() ?: return null
        if (wrong) System.err.println("  ✗  Wrong key.")
        else System.err.println("  This instance requires the pre-shared key.")
        val chars = runCatching { console.readPassword("  Key: ") }.getOrNull() ?: return null
        val value = String(chars).trim()
        java.util.Arrays.fill(chars, '\u0000')
        return value.ifEmpty { null }
    }

    private fun field(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.getOrNull(1)

    // ── Richieste e stampa ──────────────────────────────────────────────────

    private fun buildRequest(cmd: ControlCommand): String = when (cmd) {
        is ControlCommand.Volume -> "{\"cmd\": \"volume\", \"value\": ${cmd.value}}"
        is ControlCommand.Mute   -> "{\"cmd\": \"mute\"}"
        is ControlCommand.Unmute -> "{\"cmd\": \"unmute\"}"
        is ControlCommand.Stop   -> "{\"cmd\": \"stop\"}"
        is ControlCommand.Status -> "{\"cmd\": \"status\"}"
        is ControlCommand.PairInvite -> "{\"cmd\": \"pair\", \"rekey\": ${cmd.forceNewKey}}"
        is ControlCommand.DeepLink   -> "{\"cmd\": \"deeplink\", \"uri\": \"${cmd.uri.replace("\"", "\\\"")}\"}"
    }

    private fun prettyPrint(cmd: ControlCommand, response: String, pid: String, args: CliArgs) {
        val ok = response.contains("\"status\": \"ok\"")

        when (cmd) {
            is ControlCommand.Volume -> {
                val pct = (cmd.value * 100).toInt()
                if (ok) println("  ✓  Volume set to $pct%  ${dim("(PID $pid)")}")
                else    System.err.println("  ✗  Failed to set volume.")
            }
            is ControlCommand.Mute -> {
                if (ok) println("  ✓  Muted  ${dim("(PID $pid)")}")
                else    System.err.println("  ✗  Failed to mute.")
            }
            is ControlCommand.Unmute -> {
                if (ok) println("  ✓  Unmuted  ${dim("(PID $pid)")}")
                else    System.err.println("  ✗  Failed to unmute.")
            }
            is ControlCommand.Stop -> {
                if (ok) println("  ✓  Streaming stopped  ${dim("(PID $pid)")}")
                else    System.err.println("  ✗  Failed to stop.")
            }
            is ControlCommand.Status -> printStatus(response, pid)
            is ControlCommand.PairInvite -> println(response)
            is ControlCommand.DeepLink   -> println(response)
        }
    }

    private fun printStatus(json: String, pid: String) {
        fun str(key: String)  = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.getOrNull(1)
        fun num(key: String)  = Regex("\"$key\"\\s*:\\s*([\\d.]+)").find(json)?.groupValues?.getOrNull(1)
        fun bool(key: String) = Regex("\"$key\"\\s*:\\s*(true|false)").find(json)?.groupValues?.getOrNull(1)

        val mode    = str("mode")    ?: "unknown"
        val volume  = num("volume")?.toFloat()?.let { "${(it * 100).toInt()}%" } ?: "?"
        val muted   = bool("muted")  == "true"
        val port    = num("port")    ?: "?"
        val rtp     = bool("rtp")    == "true"
        val http    = bool("http")   == "true"
        val dlna     = bool("dlna")     == "true"
        val snapcast = bool("snapcast") == "true"
        val uptime  = num("uptime")?.toLong()?.let { formatUptime(it) } ?: "?"

        val labelWidth = 10
        fun row(label: String, value: String) =
            println("  ${dim(label.padEnd(labelWidth))}$value")

        val usbUp  = bool("usb") == "true"
        val usbIf  = str("usb_iface").orEmpty()
        val family = str("family") ?: "auto"
        val wfas   = when (str("wfas")?.lowercase()) {
            "off"        -> "off"
            "always"     -> "always"
            "off_on_usb" -> "not on USB"
            else         -> str("wfas") ?: "always"
        }
        val auth = str("auth")?.lowercase() ?: "off"
        val encrypted = bool("encrypted") == "true"

        println()
        println("  ${bold("wfas")}  PID $pid")
        row("Mode", mode)
        row("Port", port)
        row("Volume", "$volume${if (muted) "  ${yellow("(muted)")}" else ""}")
        row("RTP", if (rtp) green("yes") else dim("no"))
        row("HTTP", if (http) green("yes") else dim("no"))

        if (dlna) {
            val dlnaClients = num("dlna_clients")?.toIntOrNull() ?: 0
            val renderers   = str("dlna_renderers").orEmpty()
            val extras = buildList {
                add("port ${num("dlna_port") ?: "?"}")
                str("dlna_format").orEmpty().takeIf { it.isNotEmpty() }?.let { add(it) }
                add(if (dlnaClients == 1) "1 renderer" else "$dlnaClients renderers")
            }
            row("DLNA", "${green("yes")}  ${dim(extras.joinToString(", "))}")
            if (renderers.isNotEmpty()) row("", dim(renderers))
        } else {
            row("DLNA", dim("no"))
        }

        if (snapcast) {
            val snapClients = num("snapcast_clients")?.toIntOrNull() ?: 0
            val extras = buildList {
                add("port ${num("snapcast_port") ?: "?"}")
                add("control ${num("snapcast_control_port") ?: "?"}")
                str("snapcast_codec").orEmpty().takeIf { it.isNotEmpty() }?.let { add(it) }
                str("snapcast_stream").orEmpty().takeIf { it.isNotEmpty() }?.let { add("stream '$it'") }
                add(if (snapClients == 1) "1 client" else "$snapClients clients")
            }
            row("Snapcast", "${green("yes")}  ${dim(extras.joinToString(", "))}")
        } else {
            row("Snapcast", dim("no"))
        }

        row("Link", if (usbUp) green("USB") + (if (usbIf.isNotEmpty()) dim(" ($usbIf)") else "") else dim("Wi-Fi"))
        row("IP", if (family == "auto") dim("auto") else yellow(family))
        row("WFAS", wfas)
        row("Auth", when (auth) {
            "key" -> green("key") + (if (encrypted) dim("  (session encrypted)") else "")
            "ask" -> yellow("ask")
            else  -> dim("off")
        })
        row("Uptime", uptime)
        println()
    }

    private fun formatUptime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0  -> "${h}h ${m}m ${s}s"
            m > 0  -> "${m}m ${s}s"
            else   -> "${s}s"
        }
    }

    private fun printError(msg: String, args: CliArgs) {
        if (args.json) println("{\"status\": \"error\", \"message\": \"${msg.replace("\"", "\\\"")}\"}")
        else System.err.println("  ✗  $msg")
    }

    private fun dim(t: String)    = Ansi.dim(t)
    private fun bold(t: String)   = Ansi.bold(t)
    private fun green(t: String)  = Ansi.green(t)
    private fun yellow(t: String) = Ansi.yellow(t)
}