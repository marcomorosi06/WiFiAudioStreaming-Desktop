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

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

sealed class PairCommand {
    data class Invite(val forceNewKey: Boolean) : PairCommand()
    data class Connect(val uri: String)         : PairCommand()
    data class Inspect(val uri: String)         : PairCommand()
    data class Encode(val text: String)         : PairCommand()
    object Show       : PairCommand()
    object Off        : PairCommand()
    object Register   : PairCommand()
    object Unregister : PairCommand()
}

data class QrRenderOptions(
    val enabled: Boolean = true,
    val plain: Boolean = false,
    val invert: Boolean = false,
    val revealKey: Boolean = false,
)

object PairRuntime {

    fun generate(port: Int, multicast: Boolean, forceNewKey: Boolean): QrInvite? {
        val all = SettingsRepository.loadSettings()
        val ip = NetworkHandler_v1.getLocalIpAddress()
        if (!multicast && (ip.isBlank() || ip == "0.0.0.0")) return null
        QrPairingState.invite.value = null
        QrPairingState.generateInvite(
            settings = all.app,
            localIp = ip,
            port = port,
            multicast = multicast,
            forceNewKey = forceNewKey
        ) { next -> SettingsRepository.saveSettings(all.copy(app = next)) }
        return QrPairingState.invite.value
    }

    fun disable(): Boolean {
        val all = SettingsRepository.loadSettings()
        val app = all.app
        if (!app.qrPairingEnabled) return false
        val target = if (app.manualAuthKey.isBlank()) SecurityMode.OFF.name else SecurityMode.KEY.name
        val next = WfasAuth.nextSecurityState(
            storedMode = app.securityMode,
            authKey = app.authKey,
            manualAuthKey = app.manualAuthKey,
            qrPairingEnabled = true,
            uiMode = target
        )
        SettingsRepository.saveSettings(
            all.copy(
                app = app.copy(
                    securityMode = next.storedMode,
                    authKey = next.authKey,
                    manualAuthKey = next.manualAuthKey,
                    qrPairingEnabled = next.qrPairingEnabled
                )
            )
        )
        return true
    }
}

object PairCli {

    private const val ESC = ""

    private fun dim(t: String)    = Ansi.dim(t)
    private fun bold(t: String)   = Ansi.bold(t)
    private fun cyan(t: String)   = Ansi.cyan(t)
    private fun green(t: String)  = Ansi.green(t)
    private fun red(t: String)    = Ansi.red(t)
    private fun yellow(t: String) = Ansi.yellow(t)

    private fun jsonEscape(s: String) =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun jsonObject(vararg fields: kotlin.Pair<String, Any?>): String =
        "{" + fields.joinToString(", ") { (k, v) ->
            val value = when (v) {
                null       -> "null"
                is Boolean -> v.toString()
                is Number  -> v.toString()
                else       -> "\"${jsonEscape(v.toString())}\""
            }
            "\"$k\": $value"
        } + "}"

    private fun fail(msg: String, json: Boolean, code: Int): Int {
        if (json) println(jsonObject("status" to "error", "message" to msg))
        else System.err.println("wfas pair: $msg")
        return code
    }

    private fun styleFor(opts: QrRenderOptions): QrAscii.Style =
        if (opts.plain) QrAscii.Style.ASCII else QrAscii.defaultStyle()

    private fun maskedKey(key: String, reveal: Boolean): String {
        val grouped = WfasAuth.groupKeyForDisplay(key)
        return if (reveal) grouped else "•".repeat(grouped.length.coerceAtMost(52))
    }

    private fun remaining(invite: QrInvite): Long =
        invite.expEpochSeconds - System.currentTimeMillis() / 1000

    private fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    fun run(cmd: PairCommand, args: CliArgs, opts: QrRenderOptions): Int = when (cmd) {
        is PairCommand.Invite     -> doInvite(cmd.forceNewKey, args, opts)
        is PairCommand.Inspect    -> doInspect(cmd.uri, args.json, opts)
        is PairCommand.Encode     -> doEncode(cmd.text, args.json, opts)
        is PairCommand.Show       -> doShow(args.json)
        is PairCommand.Off        -> doOff(args.json)
        is PairCommand.Register   -> doRegister(args.json)
        is PairCommand.Unregister -> doUnregister(args.json)
        is PairCommand.Connect    -> ExitCode.OK
    }

    // ─────────────────────────────────────────────────────────────────────────
    // invite / regenerate
    // ─────────────────────────────────────────────────────────────────────────

    private class Source(val port: Int, val multicast: Boolean, val pid: String?) {
        val live: Boolean get() = pid != null
        fun make(forceNewKey: Boolean): QrInvite? =
            (if (live) PairIpc.request(forceNewKey)?.invite else null)
                ?: PairRuntime.generate(port, multicast, forceNewKey)
    }

    private fun doInvite(forceNewKey: Boolean, args: CliArgs, opts: QrRenderOptions): Int {
        val remote = PairIpc.request(forceNewKey)
        val source = if (remote != null) Source(remote.port, remote.multicast, remote.pid)
                     else Source(args.port, args.multicast, null)

        if (remote == null && forceNewKey && !SettingsRepository.loadSettings().app.qrPairingEnabled) {
            return fail(
                "QR pairing is off and no server is running: there is no key to replace. " +
                    "Run 'wfas pair invite' first.",
                args.json, ExitCode.USAGE_ERROR
            )
        }

        val invite = remote?.invite ?: source.make(forceNewKey) ?: return fail(
            "Could not determine a local IP address for this machine.",
            args.json, ExitCode.RESOURCE_ERROR
        )

        if (args.json) {
            println(inviteJson(invite, source.live))
            return ExitCode.OK
        }
        if (args.quiet) {
            println(invite.uri)
            return ExitCode.OK
        }

        if (!args.watch || !Ansi.enabled) {
            printPanel(invite, source, opts, live = false)
            if (args.watch) System.err.println(
                dim("  This terminal has no ANSI support, so the countdown cannot refresh in place.")
            )
            return ExitCode.OK
        }

        return watchLoop(invite, source, opts)
    }

    private fun watchLoop(first: QrInvite, source: Source, opts: QrRenderOptions): Int {
        var invite = first
        var reveal = opts.revealKey
        val quit = AtomicBoolean(false)
        val action = AtomicReference<String?>(null)

        val stdin = Thread {
            runCatching {
                val reader = System.`in`.bufferedReader()
                while (!quit.get()) {
                    val line = reader.readLine()?.trim()?.lowercase() ?: break
                    when (line) {
                        "q", "quit", "exit"        -> { quit.set(true); break }
                        "n", "new"                 -> action.set("new")
                        "r", "rekey", "regenerate" -> action.set("rekey")
                        "k", "key"                 -> action.set("key")
                    }
                }
            }
            quit.set(true)
        }
        stdin.isDaemon = true
        stdin.start()

        var panel = printPanel(invite, source, opts.copy(revealKey = reveal), live = true)
        var nextRenewAt = 0L

        while (!quit.get()) {
            Thread.sleep(250)
            val now = System.currentTimeMillis()
            var redraw = false

            when (action.getAndSet(null)) {
                "key"   -> { reveal = !reveal; redraw = true }
                "new"   -> { invite = source.make(false) ?: invite; redraw = true }
                "rekey" -> { invite = source.make(true) ?: invite; redraw = true }
            }

            if (!redraw && remaining(invite) <= 0L && now >= nextRenewAt) {
                nextRenewAt = now + 3000
                val fresh = source.make(false)
                if (fresh != null && remaining(fresh) > 0L) {
                    invite = fresh
                    redraw = true
                }
            }

            if (redraw) {
                if (panel.inPlace) clearLines(panel.lines) else println()
                panel = printPanel(invite, source, opts.copy(revealKey = reveal), live = true)
            } else if (panel.inPlace) {
                repaintCountdown(invite)
            } else {
                repaintLastLine(invite)
            }
        }

        println()
        return ExitCode.OK
    }

    private fun clearLines(n: Int) {
        if (!Ansi.enabled || n <= 0) return
        print("$ESC[${n}A")
        repeat(n) { print("$ESC[2K$ESC[1B") }
        print("$ESC[${n}A")
        System.out.flush()
    }

    private fun repaintCountdown(invite: QrInvite) {
        if (!Ansi.enabled) return
        print("$ESC[2A$ESC[2K${countdownLine(remaining(invite))}\n$ESC[1B")
        System.out.flush()
    }

    private fun repaintLastLine(invite: QrInvite) {
        if (!Ansi.enabled) return
        print("\r$ESC[2K" + countdownLine(remaining(invite)) + "   " + hintLine())
        System.out.flush()
    }

    private fun hintLine() = dim("n=new  r=new key  k=key  q=quit")

    private fun countdownLine(left: Long): String = when {
        left <= 0L  -> "  " + red("Expired") + dim("  renewing...")
        left <= 15L -> "  " + yellow("Expires in ${clock(left)}")
        else        -> "  " + dim("Expires in ") + bold(clock(left))
    }

    private fun printPanel(
        invite: QrInvite,
        source: Source,
        opts: QrRenderOptions,
        live: Boolean
    ): Panel {
        val style = styleFor(opts)
        val cols = TerminalSize.columns
        val warn = QrAscii.fitWarning(invite.uri, style, cols)
        val out = ArrayList<String>()

        out += ""
        out += "  " + bold("WFAS pairing invite") + when {
            source.live -> dim("  running server, PID ${source.pid}")
            else        -> dim("  no server running yet")
        }
        out += ""

        if (opts.enabled && warn == null) {
            out += QrAscii.renderCentred(invite.uri, style, opts.invert, cols)
            out += ""
        } else if (opts.enabled && warn != null) {
            out += "  " + yellow(warn)
            out += ""
        }

        val mode = if (invite.multicast) "multicast" else "unicast"
        out += field("Address", cyan(NetAddr.hostPort(invite.ip, invite.port)) + "  " + dim(mode), cols)
        out += field("Key", maskedKey(invite.key, opts.revealKey), cols)
        if (invite.encryptionForced)
            out += field("Encryption", green("on") + " " + dim("turned on for this invite"), cols)
        out += field("Link", invite.uri, cols)
        if (!source.live)
            out += "  ${dim("Saved to the config: start the server with 'wfas --server' to accept it.")}"
        out += ""
        val fits = out.size + 2 < TerminalSize.rows - 1
        val inPlace = live && fits

        if (live && !fits) {
            out += countdownLine(remaining(invite)) + "   " + hintLine()
        } else {
            out += countdownLine(remaining(invite))
            out += if (live)
                dim("  n=new invite   r=new key   k=show/hide key   q=quit") + dim("   (then Enter)")
            else
                dim("  Scan it with the WFAS app, or open the link on the other device.")
        }

        out.forEachIndexed { i, line ->
            if (live && !fits && i == out.lastIndex) print(line) else println(line)
        }
        System.out.flush()
        return Panel(out.size, inPlace)
    }

    private class Panel(val lines: Int, val inPlace: Boolean)

    private const val LABEL_WIDTH = 12

    private fun field(label: String, value: String, cols: Int): List<String> {
        val head = "  " + dim(label.padEnd(LABEL_WIDTH))
        val indent = "  " + " ".repeat(LABEL_WIDTH)
        val width = (cols - 2 - LABEL_WIDTH).coerceAtLeast(24)
        val plain = value.replace(Regex("\\[[0-9;]*m"), "")
        if (plain.length <= width || plain.length != value.length) return listOf(head + value)
        return value.chunked(width).mapIndexed { i, c -> if (i == 0) head + c else indent + c }
    }

    private fun inviteJson(invite: QrInvite, live: Boolean): String = jsonObject(
        "status" to "ok",
        "uri" to invite.uri,
        "deeplink" to WfasPairingUri.build(
            invite.ip, invite.port,
            if (invite.multicast) WfasPairingUri.MODE_MULTICAST else WfasPairingUri.MODE_UNICAST,
            invite.key, invite.expEpochSeconds
        ),
        "key" to invite.key,
        "ip" to invite.ip,
        "port" to invite.port,
        "mode" to if (invite.multicast) "multicast" else "unicast",
        "expires_at" to invite.expEpochSeconds,
        "expires_in" to remaining(invite).coerceAtLeast(0L),
        "ttl" to WfasPairingUri.PAIRING_TTL_SECONDS,
        "encryption_forced" to invite.encryptionForced,
        "source" to if (live) "running_server" else "offline"
    )

    // ─────────────────────────────────────────────────────────────────────────
    // invite panel printed by a running CLI server
    // ─────────────────────────────────────────────────────────────────────────

    fun serverInvite(args: CliArgs, forceNewKey: Boolean = false) {
        val invite = PairRuntime.generate(args.port, args.multicast, forceNewKey)
        if (invite == null) {
            if (!args.json) System.err.println("  " + red("!") + " No local address to invite on.")
            return
        }
        if (args.json) { println(inviteJson(invite, false)); return }
        if (args.quiet) { println(invite.uri); return }

        val opts = args.qrOptions()
        val style = styleFor(opts)
        val cols = TerminalSize.columns
        val warn = QrAscii.fitWarning(invite.uri, style, cols)

        println()
        if (opts.enabled && warn == null) {
            QrAscii.renderCentred(invite.uri, style, opts.invert, cols).forEach { println(it) }
            println()
        } else if (opts.enabled && warn != null) {
            println("  " + yellow(warn))
            println()
        }
        println("  " + bold("Pairing invite") + dim("  valid for ${WfasPairingUri.PAIRING_TTL_SECONDS}s"))
        println("  ${dim("Key")}   ${maskedKey(invite.key, opts.revealKey)}")
        println("  ${dim("Link")}  ${invite.uri}")
        if (forceNewKey && invite.multicast)
            println("  ${yellow("New key: listeners still on the old one have been dropped.")}")
        println()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // connect
    // ─────────────────────────────────────────────────────────────────────────

    fun connectArgs(uri: String, args: CliArgs): CliArgs? {
        val payload = WfasPairingUri.parse(uri)
        if (payload == null) {
            val expired = WfasPairingUri.isExpiredUri(uri)
            val msg = if (expired)
                "This invite has expired. Ask the other device for a new code."
            else
                "That is not a WFAS invite link."
            if (args.json) println(jsonObject("status" to "error", "message" to msg, "expired" to expired))
            else System.err.println("wfas pair: $msg")
            return null
        }

        if (!payload.isMulticast && NetAddr.isSelfAddress(payload.ip)) {
            val msg = "That invite was generated by this machine."
            if (args.json) println(jsonObject("status" to "error", "message" to msg))
            else System.err.println("wfas pair: $msg")
            return null
        }

        return args.copy(
            runMode = RunMode.CLI_CLIENT,
            serverIp = if (payload.isMulticast) null else payload.ip,
            port = payload.port,
            multicast = payload.isMulticast,
            authMode = SecurityMode.KEY.name,
            authKey = payload.keyBase64,
            authExplicit = true,
            encrypt = true,
            fromInvite = true,
            inviteEpoch = payload.mcastEpoch,
        )
    }

    fun applyInviteToNetwork(args: CliArgs) {
        if (!args.fromInvite) return
        NetworkHandler_v1.clearEpochMismatch()
        NetworkHandler_v1.clearInviteRejected()
        NetworkHandler_v1.expectedMcastEpoch = args.inviteEpoch
        NetworkHandler_v1.clientKeyFromInvite = true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // inspect / encode
    // ─────────────────────────────────────────────────────────────────────────

    private fun doInspect(uri: String, json: Boolean, opts: QrRenderOptions): Int {
        val now = System.currentTimeMillis() / 1000
        val payload = WfasPairingUri.parse(uri) ?: WfasPairingUri.parse(uri, 0L)
            ?: return fail("Not a WFAS invite link.", json, ExitCode.USAGE_ERROR)
        val expired = now - WfasPairingUri.CLOCK_SKEW_SECONDS > payload.expEpochSeconds
        val self = NetAddr.isSelfAddress(payload.ip)

        if (json) {
            println(
                jsonObject(
                    "status" to "ok",
                    "valid" to !expired,
                    "expired" to expired,
                    "ip" to payload.ip,
                    "port" to payload.port,
                    "mode" to payload.mode,
                    "key_length" to payload.keyBase64.length,
                    "mcast_epoch" to payload.mcastEpoch,
                    "expires_at" to payload.expEpochSeconds,
                    "expires_in" to (payload.expEpochSeconds - now).coerceAtLeast(0L),
                    "version" to payload.version,
                    "self" to self
                )
            )
            return if (expired) ExitCode.USAGE_ERROR else ExitCode.OK
        }

        println()
        println("  " + bold("WFAS invite"))
        println("  ${dim("Address")}   ${cyan(NetAddr.hostPort(payload.ip, payload.port))}")
        println("  ${dim("Mode")}      ${payload.mode}")
        println("  ${dim("Key")}       ${maskedKey(payload.keyBase64, opts.revealKey)}  ${dim("${payload.keyBase64.length} chars")}")
        payload.mcastEpoch?.let { println("  ${dim("Epoch")}     $it") }
        println("  ${dim("Version")}   ${payload.version}")
        println(
            "  ${dim("Expiry")}    " +
                if (expired) red("expired")
                else green("valid") + dim("  ${clock(payload.expEpochSeconds - now)} left")
        )
        if (self) println("  ${yellow("This invite points at this machine.")}")
        println()
        return if (expired) ExitCode.USAGE_ERROR else ExitCode.OK
    }

    private fun doEncode(text: String, json: Boolean, opts: QrRenderOptions): Int {
        val style = styleFor(opts)
        val size = QrAscii.sizeOf(text, style)
            ?: return fail("Cannot encode that text as a QR code.", json, ExitCode.USAGE_ERROR)

        if (json) {
            println(
                jsonObject(
                    "status" to "ok",
                    "columns" to size.first,
                    "rows" to size.second,
                    "style" to style.name.lowercase(),
                    "art" to QrAscii.render(text, style, opts.invert).joinToString("\n")
                )
            )
            return ExitCode.OK
        }

        QrAscii.fitWarning(text, style)?.let { System.err.println(yellow("  $it")) }
        println()
        QrAscii.renderCentred(text, style, opts.invert).forEach { println(it) }
        println()
        return ExitCode.OK
    }

    // ─────────────────────────────────────────────────────────────────────────
    // status / off / register
    // ─────────────────────────────────────────────────────────────────────────

    private fun doShow(json: Boolean): Int {
        val app = SettingsRepository.loadSettings().app
        val registered = ProtocolRegistrar.isRegistered()
        val running = PairIpc.findInstance()
        val generated = app.authKey.isNotBlank() && app.authKey != app.manualAuthKey && app.qrPairingEnabled

        if (json) {
            println(
                jsonObject(
                    "status" to "ok",
                    "qr_pairing" to app.qrPairingEnabled,
                    "security_mode" to app.securityMode,
                    "ui_mode" to SecurityMode.uiMode(app.securityMode, app.qrPairingEnabled),
                    "key_present" to app.authKey.isNotBlank(),
                    "key_generated" to generated,
                    "manual_key_present" to app.manualAuthKey.isNotBlank(),
                    "encryption" to app.encryptionEnabled,
                    "scheme" to ProtocolRegistrar.SCHEME,
                    "scheme_registered" to registered,
                    "scheme_command" to ProtocolRegistrar.registeredCommand(),
                    "launcher" to ProtocolRegistrar.launcherPath(),
                    "applink_en" to "https://${WfasPairingUri.APPLINK_HOST}${WfasPairingUri.APPLINK_PATH}",
                    "applink_it" to "https://${WfasPairingUri.APPLINK_HOST}${WfasPairingUri.APPLINK_PATH_IT}",
                    "ttl" to WfasPairingUri.PAIRING_TTL_SECONDS,
                    "running_pid" to running?.second
                )
            )
            return ExitCode.OK
        }

        println()
        println("  " + bold("QR pairing"))
        println("  ${dim("Enabled")}     ${if (app.qrPairingEnabled) green("yes") else dim("no")}")
        println("  ${dim("Security")}    ${SecurityMode.uiMode(app.securityMode, app.qrPairingEnabled)}")
        val mode = SecurityMode.fromStringSafe(app.securityMode)
        println(
            "  ${dim("Key")}         " + when {
                app.authKey.isBlank() -> dim("none")
                !mode.requiresKey     -> yellow("manual") + dim("  stored but unused: security is ${mode.name}")
                generated             -> green("generated") + dim("  came from an invite")
                else                  -> yellow("manual")
            }
        )
        println("  ${dim("Encryption")}  ${if (app.encryptionEnabled) green("on") else dim("off")}")
        println(
            "  ${dim("Handler")}     ${ProtocolRegistrar.SCHEME}://  " +
                if (registered) green("registered") else yellow("not registered")
        )
        ProtocolRegistrar.registeredCommand()?.let { println("  ${dim("Opens with")}  $it") }
        println(
            "  ${dim("Server")}      " +
                if (running != null) green("running") + dim("  PID ${running.second}") else dim("not running")
        )
        println("  ${dim("Invite TTL")}  ${WfasPairingUri.PAIRING_TTL_SECONDS}s")
        if (!registered) {
            println()
            val exe = ProtocolRegistrar.launcherPath()
            if (exe == null) {
                println("  ${red("!")}  Cannot locate the executable, so the handler cannot be registered.")
                println("     ${dim("Run the installed app, not the development build.")}")
            } else {
                println("  ${yellow("!")}  Not registered yet. Starting the app or a server registers it.")
                println("     ${dim("Would point at:")} $exe")
            }
        }
        println()
        return ExitCode.OK
    }

    private fun doOff(json: Boolean): Int {
        val changed = PairRuntime.disable()
        val app = SettingsRepository.loadSettings().app
        if (json) {
            println(jsonObject("status" to "ok", "changed" to changed, "security_mode" to app.securityMode))
        } else if (changed) {
            println("  ${green("✓")}  QR pairing off. Security is now ${bold(app.securityMode)}.")
        } else {
            println("  ${dim("QR pairing was already off.")}")
        }
        return ExitCode.OK
    }

    private fun doRegister(json: Boolean): Int {
        val r = ProtocolRegistrar.register()
        return if (r.isSuccess) {
            val ok = ProtocolRegistrar.isRegistered()
            if (json) println(jsonObject("status" to "ok", "registered" to ok, "scheme" to ProtocolRegistrar.SCHEME))
            else println("  ${green("✓")}  ${ProtocolRegistrar.SCHEME}:// links now open WiFi Audio Streaming.")
            ExitCode.OK
        } else {
            fail(
                "Could not register the ${ProtocolRegistrar.SCHEME}:// handler: ${r.exceptionOrNull()?.message}",
                json, ExitCode.RESOURCE_ERROR
            )
        }
    }

    private fun doUnregister(json: Boolean): Int {
        val r = ProtocolRegistrar.unregister()
        return if (r.isSuccess) {
            if (json) println(jsonObject("status" to "ok", "registered" to false, "scheme" to ProtocolRegistrar.SCHEME))
            else println("  ${green("✓")}  ${ProtocolRegistrar.SCHEME}:// handler removed.")
            ExitCode.OK
        } else {
            fail(
                "Could not remove the handler: ${r.exceptionOrNull()?.message}",
                json, ExitCode.RESOURCE_ERROR
            )
        }
    }
}

object PairIpc {

    class Remote(val invite: QrInvite, val port: Int, val multicast: Boolean, val pid: String?)

    fun findInstance(): kotlin.Pair<Int, String>? {
        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        val files = tmpDir.listFiles { f -> f.name.startsWith("wfas-") && f.name.endsWith(".port") }
            ?.sortedByDescending { it.lastModified() } ?: return null
        for (f in files) {
            val port = runCatching { f.readText().trim().toIntOrNull() }.getOrNull() ?: continue
            val pid = f.name.removePrefix("wfas-").removeSuffix(".port")
            val alive = pid.toLongOrNull()
                ?.let { ProcessHandle.of(it).map { h -> h.isAlive }.orElse(false) } ?: false
            if (!alive) continue
            return port to pid
        }
        return null
    }

    private fun ask(request: String): String? {
        val found = findInstance() ?: return null
        return runCatching {
            java.net.Socket(java.net.InetAddress.getLoopbackAddress(), found.first).use { socket ->
                socket.soTimeout = 4000
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write(request)
                writer.newLine()
                writer.flush()
                socket.getInputStream().bufferedReader().readLine()
            }
        }.getOrNull()
    }

    fun handOffDeepLink(uri: String): Boolean {
        val escaped = uri.replace("\\", "\\\\").replace("\"", "\\\"")
        val response = ask("{\"cmd\": \"deeplink\", \"uri\": \"$escaped\"}") ?: return false
        return response.contains("\"status\": \"ok\"")
    }

    fun request(forceNewKey: Boolean): Remote? {
        val found = findInstance() ?: return null
        val response = runCatching {
            java.net.Socket(java.net.InetAddress.getLoopbackAddress(), found.first).use { socket ->
                socket.soTimeout = 4000
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write("{\"cmd\": \"pair\", \"rekey\": $forceNewKey}")
                writer.newLine()
                writer.flush()
                socket.getInputStream().bufferedReader().readLine()
            }
        }.getOrNull() ?: return null

        if (!response.contains("\"status\": \"ok\"")) return null

        fun str(k: String) = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(response)?.groupValues?.getOrNull(1)
        fun num(k: String) = Regex("\"$k\"\\s*:\\s*(-?[\\d.]+)").find(response)?.groupValues?.getOrNull(1)
        fun flag(k: String) = Regex("\"$k\"\\s*:\\s*(true|false)").find(response)?.groupValues?.getOrNull(1) == "true"

        val uri = str("uri") ?: return null
        val key = str("key") ?: return null
        val ip = str("ip") ?: return null
        val port = num("port")?.toDouble()?.toInt() ?: return null
        val exp = num("expires_at")?.toDouble()?.toLong() ?: return null
        val multicast = flag("multicast")

        return Remote(
            invite = QrInvite(
                uri = uri,
                key = key,
                ip = ip,
                port = port,
                multicast = multicast,
                expEpochSeconds = exp,
                encryptionForced = flag("encryption_forced")
            ),
            port = port,
            multicast = multicast,
            pid = found.second
        )
    }
}
