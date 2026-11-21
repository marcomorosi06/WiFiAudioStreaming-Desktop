/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
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

import kotlinx.coroutines.delay
import wfas3.W3
import wfas3.Wfas3Addr
import wfas3.Wfas3Beacon
import wfas3.Wfas3ClientState
import wfas3.Wfas3PairInfo
import javax.sound.sampled.Mixer

/** The verbs of 'wfas v3 ...'. Each one runs and ends; none keeps a stream open. */
sealed class V3Command {
    object Status : V3Command()
    /** Client side: pair with a server, with its code (null: ask for it) or, with --pin, its PIN. */
    data class Pair(val code: String?) : V3Command()
    /** Server side: open a pairing window here and wait for one device. */
    object Invite : V3Command()
    data class Remove(val target: String) : V3Command()
    object Reset : V3Command()
    data class Use(val protocol: String) : V3Command()
    data class Name(val name: String?) : V3Command()
}

/**
 * WFAS v3 on the command line: the 'wfas v3' verbs, the pairing prompts of
 * 'wfas --server', and the client session of 'wfas --client'.
 *
 * Everything here goes through [Wfas3Desktop], like the window does: one
 * engine, one copy of the records, the same rules. Messages say what
 * happened and what can be done next; nothing here is meant to alarm.
 */
object Wfas3Cli {

    private fun green(t: String) = Ansi.green(t)
    private fun red(t: String) = Ansi.red(t)
    private fun yellow(t: String) = Ansi.yellow(t)
    private fun cyan(t: String) = Ansi.cyan(t)
    private fun bold(t: String) = Ansi.bold(t)
    private fun dim(t: String) = Ansi.dim(t)

    private fun say(msg: String, args: CliArgs) { if (!args.quiet && !args.json) println(msg) }
    private fun note(msg: String) = System.err.println(msg)

    private fun json(vararg pairs: kotlin.Pair<String, Any?>) {
        val body = pairs.joinToString(", ") { (k, v) -> "\"$k\": " + jsonValue(v) }
        println("{$body}")
    }

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Int, is Long -> v.toString()
        is List<*> -> v.joinToString(", ", "[", "]") { jsonValue(it) }
        is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { (k, x) -> "\"$k\": " + jsonValue(x) }
        else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }

    // -- protocol of this run ---------------------------------------------------------

    /**
     * '--legacy-v2' makes this run use WFAS v2 without touching the saved
     * setting. Said once, plainly, so the choice is a known one.
     */
    fun applyRunOverride(args: CliArgs) {
        if (!args.legacyV2) return
        Wfas3Desktop.legacyForThisRun = true
        Wfas3Desktop.configure(Wfas3Desktop.appSettings)
        if (args.json) json("event" to "legacy_v2", "message" to LEGACY_NOTE)
        else note(yellow("i") + " " + LEGACY_NOTE)
    }

    private const val LEGACY_NOTE =
        "This run uses WFAS v2, the legacy protocol, for devices that do not have WFAS v3 yet. " +
            "WFAS v3 protects pairing and audio better: update the other device when you can."

    /** Before joining a v2 server from an invite link while v3 is the protocol of this device. */
    fun legacyServerNote(args: CliArgs) {
        val m = "This invite is from a server with WFAS v2, the legacy protocol. The connection works, " +
            "with the weaker protection of v2: updating that server to WFAS v3 is worth it when you can."
        if (args.json) json("event" to "legacy_v2_server", "message" to m) else note(yellow("i") + " " + m)
    }

    /** The v2-only options given to a v3 server or client, to mention once. */
    private fun v2OnlyOptions(args: CliArgs): List<String> = buildList {
        if (args.authMode != "OFF") add("--auth-mode")
        if (args.authKey.isNotEmpty()) add("--auth-key")
        if (args.encrypt) add("--encrypt")
        if (args.qr) add("--qr")
        if (args.port != 9090) add("--port")
        if (args.micPort != 9092) add("--mic-port")
    }

    // -- 'wfas v3 ...' ----------------------------------------------------------------

    fun run(cmd: V3Command, args: CliArgs): Int {
        AppDebug.enabled = args.debug
        val settings = SettingsRepository.loadSettings()
        return try {
            when (cmd) {
                is V3Command.Status -> status(settings.app, args)
                is V3Command.Pair -> pair(cmd, settings.app, args)
                is V3Command.Invite -> invite(settings.app, args)
                is V3Command.Remove -> remove(cmd.target, settings.app, args)
                is V3Command.Reset -> reset(args)
                is V3Command.Use -> use(cmd.protocol, settings, args)
                is V3Command.Name -> name(cmd.name, settings, args)
            }
        } finally {
            Wfas3Desktop.shutdown()
        }
    }

    private fun engineOrExplain(app: AppSettings, args: CliArgs): Boolean {
        if (Wfas3Desktop.ensure(app) != null) return true
        val why = Wfas3Desktop.failureText()
        if (args.json) json("status" to "error", "message" to why) else note(red("!") + " " + why)
        return false
    }

    private fun keysText(protection: String): String =
        if (protection == Wfas3FileStore.PROTECTION_FILE) "a file readable by this account only (no system credential store found)"
        else "the system credential store ($protection)"

    private fun status(app: AppSettings, args: CliArgs): Int {
        val v3 = Wfas3Settings.isV3(app)
        if (!engineOrExplain(app, args)) return ExitCode.RESOURCE_ERROR
        val pairs = Wfas3Desktop.pairs.value
        val protection = Wfas3Desktop.protection.value
        if (args.json) {
            json(
                "protocol" to if (v3) "v3" else "v2",
                "device" to Wfas3Settings.deviceName(app),
                "keys" to if (protection == Wfas3FileStore.PROTECTION_FILE) "file" else protection,
                "pairs" to pairs.map {
                    mapOf("number" to it.slot + 1, "name" to it.name, "guest" to it.guest,
                        "microphone" to it.microphone, "paused" to it.suspect)
                }
            )
            return ExitCode.OK
        }
        println()
        if (v3) println("  " + bold("WFAS v3") + "  " + green("active") + dim("  (default protocol)"))
        else {
            println("  " + bold("WFAS v2") + "  " + yellow("active") + dim("  (legacy protocol)"))
            println("  " + dim("Switch back to WFAS v3 with: wfas v3 use v3"))
        }
        println("  ${dim("Device name")}  ${Wfas3Settings.deviceName(app)}")
        if (protection.isNotBlank()) println("  ${dim("Keys")}         kept in ${keysText(protection)}")
        println("  ${dim("Pairings")}     ${pairs.size}")
        printPairs(pairs)
        println()
        if (pairs.isEmpty()) {
            println("  " + dim("To pair: on the server run 'wfas --server' and press p, or use the app;"))
            println("  " + dim("then here: wfas v3 pair   (it asks for the code, which then stays out of the shell history)"))
            println()
        }
        return ExitCode.OK
    }

    private fun printPairs(pairs: List<Wfas3PairInfo>) {
        pairs.forEach { p ->
            val tags = buildList {
                if (p.guest) add("guest")
                if (p.microphone) add("microphone allowed")
                if (p.suspect) add(yellow("paused: pair it again to continue"))
            }
            println("    " + bold("#${p.slot + 1}") + "  " + p.name.ifBlank { "?" } +
                (if (tags.isEmpty()) "" else "  " + dim("(") + tags.joinToString(", ") + dim(")")))
        }
    }

    /** A pairing by number (as 'status' shows it) or by name (exact, then unique prefix). */
    private fun findPair(target: String, pairs: List<Wfas3PairInfo>): Wfas3PairInfo? {
        val t = target.trim().removePrefix("#")
        t.toIntOrNull()?.let { n -> pairs.firstOrNull { it.slot + 1 == n }?.let { return it } }
        pairs.firstOrNull { it.name.equals(t, ignoreCase = true) }?.let { return it }
        val prefix = pairs.filter { it.name.startsWith(t, ignoreCase = true) }
        return prefix.singleOrNull()
    }

    private fun remove(target: String, app: AppSettings, args: CliArgs): Int {
        if (!engineOrExplain(app, args)) return ExitCode.RESOURCE_ERROR
        val p = findPair(target, Wfas3Desktop.pairs.value)
        if (p == null) {
            if (args.json) json("status" to "not_found", "target" to target)
            else {
                note(red("!") + " No pairing matches '$target'. These are the pairings of this device:")
                printPairs(Wfas3Desktop.pairs.value)
            }
            return ExitCode.NOT_FOUND
        }
        Wfas3Desktop.removePair(p.slot)
        if (args.json) json("status" to "ok", "removed" to p.name)
        else say("  Removed the pairing with ${bold(p.name)}. The other device is told when it next connects.", args)
        return ExitCode.OK
    }

    private fun confirm(question: String, args: CliArgs): Boolean {
        if (args.v3Yes) return true
        if (!ConsoleInput.hasTty || args.json) return false
        return ConsoleInput.askYesNo("$question [y/N]", 0, default = false)
    }

    private fun reset(args: CliArgs): Int {
        val q = "Erase every WFAS v3 key and pairing of this device? Paired devices will need to pair again."
        if (!confirm(q, args)) {
            if (args.json) json("status" to "cancelled")
            else say("  Nothing erased." + if (!args.v3Yes && !ConsoleInput.hasTty) " Add --yes to confirm without a terminal." else "", args)
            return ExitCode.USAGE_ERROR
        }
        if (!Wfas3Desktop.eraseAll()) {
            val why = Wfas3Desktop.failureText()
            if (args.json) json("status" to "error", "message" to why) else note(red("!") + " " + why)
            return ExitCode.RESOURCE_ERROR
        }
        if (args.json) json("status" to "ok") else say("  WFAS v3 data erased. New keys are made the next time WFAS v3 starts.", args)
        return ExitCode.OK
    }

    private fun use(protocol: String, settings: AllSettings, args: CliArgs): Int {
        val app = settings.app
        if (protocol == Wfas3Settings.PROTOCOL_V3) {
            SettingsRepository.saveSettings(settings.copy(app = app.copy(wfasProtocol = Wfas3Settings.PROTOCOL_V3)))
            if (args.json) json("status" to "ok", "protocol" to "v3")
            else say("  WFAS v3 is the protocol of this device again, for the app and the command line.", args)
            return ExitCode.OK
        }
        if (!args.json) {
            println()
            println("  " + bold("Switch to WFAS v2 (legacy)?"))
            println("  WFAS v2 is the earlier version of the protocol. Its pairing and encryption are")
            println("  weaker than WFAS v3, and it cannot check as well who connects. It is kept for")
            println("  devices that do not have WFAS v3 yet; staying on WFAS v3 is the better choice.")
            println("  While WFAS v2 is on, WFAS v3 is off; your v3 pairings stay saved for later.")
            println("  For a single session, '--legacy-v2' does the same without changing the setting.")
            println()
        }
        if (!confirm("Switch to WFAS v2?", args)) {
            if (args.json) json("status" to "cancelled")
            else say("  Staying on WFAS v3." + if (!args.v3Yes && !ConsoleInput.hasTty) " Add --yes to confirm without a terminal." else "", args)
            return ExitCode.USAGE_ERROR
        }
        SettingsRepository.saveSettings(settings.copy(app = app.copy(wfasProtocol = Wfas3Settings.PROTOCOL_V2)))
        if (args.json) json("status" to "ok", "protocol" to "v2")
        else say("  WFAS v2 is on. Switch back any time with: wfas v3 use v3", args)
        return ExitCode.OK
    }

    private fun name(newName: String?, settings: AllSettings, args: CliArgs): Int {
        if (newName == null) {
            val n = Wfas3Settings.deviceName(settings.app)
            if (args.json) json("device" to n) else println(n)
            return ExitCode.OK
        }
        val clean = newName.trim().take(32)
        SettingsRepository.saveSettings(settings.copy(app = settings.app.copy(v3DeviceName = clean)))
        val shown = Wfas3Settings.deviceName(settings.app.copy(v3DeviceName = clean))
        if (args.json) json("status" to "ok", "device" to shown)
        else say("  Device name: ${bold(shown)}. Devices see it the next time they pair or look for this one.", args)
        return ExitCode.OK
    }

    // -- pairing window (server side) -------------------------------------------------

    private fun pinGroups(pin: String) = if (pin.length == 8) pin.substring(0, 4) + " " + pin.substring(4) else pin
    private fun sasGroups(c: String) = if (c.length == 6) c.substring(0, 3) + " " + c.substring(3) else c

    /** Open a window and print what the other device needs. False if it could not open. */
    private fun openAndShow(app: AppSettings, args: CliArgs, pin: Boolean, ask: Boolean): Boolean {
        val kind = if (pin) W3.PAIR_PIN else W3.PAIR_CODE
        if (!Wfas3Desktop.openWindow(app, kind, ask, args.mic)) {
            val why = Wfas3Desktop.failureText()
            if (args.json) json("event" to "pair_window_error", "message" to why) else note(red("!") + " " + why)
            return false
        }
        val w = Wfas3Desktop.window.value ?: return false
        if (args.json) {
            // The code is a secret for the next two minutes: --json prints it
            // only because the caller asked for machine output on purpose.
            json("event" to "pair_window", "kind" to if (pin) "pin" else "code", "code" to w.text, "qr" to w.qr,
                "seconds" to WINDOW_SECONDS, "device" to Wfas3Settings.deviceName(app))
            return true
        }
        println()
        if (pin) {
            println("  " + bold("Pairing with a PIN") + dim("  valid for ${WINDOW_SECONDS}s, for one device"))
            println("  On the other device choose " + bold(Wfas3Settings.deviceName(app)) + " and type this PIN:")
            println()
            println("        " + bold(pinGroups(w.text)))
            println()
            println("  " + dim("Both devices then show a 6-digit code: you will be asked to compare them here."))
        } else {
            val qr = w.qr
            if (qr != null && !args.noQr) {
                val style = if (args.qrPlain) QrAscii.Style.ASCII else QrAscii.defaultStyle()
                val cols = TerminalSize.columns
                val warn = QrAscii.fitWarning(qr, style, cols)
                if (warn == null) {
                    QrAscii.renderCentred(qr, style, args.qrInvert, cols).forEach { println(it) }
                    println()
                } else println("  " + yellow(warn))
            }
            println("  " + bold("Pairing code") + dim("  valid for ${WINDOW_SECONDS}s, for one device"))
            println("        " + bold(w.text))
            println()
            println("  " + dim("Scan the QR code with the app, or on another computer run: wfas v3 pair   (it asks for the code)"))
            println("  " + dim("Keep the code to yourself: whoever has it can pair while the window is open."))
        }
        if (!ask) println("  " + dim("The code is enough to pair: no confirmation is asked here."))
        println()
        return true
    }

    private const val WINDOW_SECONDS = 120

    private val handledAsks = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** Answer the pairing requests of the open window on this terminal; one at a time. */
    @Volatile private var promptsStarted = false

    private fun startAskPrompts(args: CliArgs) {
        if (promptsStarted) return
        promptsStarted = true
        Thread({
            var shownFailures = 0
            var lastPaired: String? = null
            while (true) {
                val w = Wfas3Desktop.window.value
                if (w == null) { Thread.sleep(150); continue }
                if (w.paired != null && w.paired != lastPaired) {
                    lastPaired = w.paired
                    handledPaired = true
                    if (args.json) json("event" to "paired", "name" to w.paired)
                    else println("  " + green("+") + "  Paired with ${bold(w.paired)}. It can connect from now on.")
                    Wfas3Desktop.windowDismissed()
                    continue
                }
                val f = Wfas3Desktop.windowFailures.value
                if (f > shownFailures) {
                    shownFailures = f
                    if (args.json) json("event" to "pair_attempt_failed", "count" to f)
                    else println("  " + dim(".") + "  A pairing attempt did not succeed ($f so far). The window stays open.")
                }
                val a = Wfas3Desktop.asks.value.firstOrNull { it.ask !in handledAsks }
                if (a == null) { Thread.sleep(150); continue }
                handledAsks.add(a.ask)
                answerAsk(a, w, args)
            }
        }, "wfas3-cli-asks").apply { isDaemon = true; start() }
    }

    private fun answerAsk(a: Wfas3Desktop.AskState, w: Wfas3Desktop.WindowState, args: CliArgs) {
        val left = (WINDOW_SECONDS * 1000L - (System.currentTimeMillis() - w.openedAtMs)).coerceAtLeast(1000L)
        if (args.json || !ConsoleInput.hasTty) {
            // Nobody can answer here: a request is a decision, and no answer is not a yes.
            if (args.json) json("event" to "pair_request_refused", "name" to a.name, "reason" to "no terminal to ask on")
            else note(dim("  .  Pairing request from ${a.name} refused: there is no terminal to ask on."))
            Wfas3Desktop.answer(a, yes = false, allowMic = false, guest = false)
            return
        }
        println()
        val who = bold(a.name.ifBlank { "a device" }) + (if (a.from.isNotBlank()) dim(" at ${a.from}") else "")
        val yes = if (a.pin) {
            println("  Pairing request from $who.")
            println("  It shows a code: check that it is " + bold(sasGroups(a.code)) + ".")
            ConsoleInput.askYesNo("  Same code on both devices? [y/N]", left, default = false)
        } else {
            println("  Pairing request from $who" + (if (a.wantsGuest) " as a guest (forgotten when it disconnects)" else "") + ".")
            ConsoleInput.askYesNo("  Pair with it? [y/N]", left, default = false)
        }
        var mic = false
        if (yes && a.wantsMic) {
            mic = args.mic || ConsoleInput.askYesNo("  It would also like to send its microphone here. Allow it? [y/N]", 30_000L, default = false)
        }
        Wfas3Desktop.answer(a, yes, allowMic = mic, guest = false)
        if (!yes) println("  " + dim("-") + "  Request declined. The window stays open until it expires.")
    }

    private fun invite(app: AppSettings, args: CliArgs): Int {
        if (!Wfas3Settings.isV3(app) || args.legacyV2) {
            val m = "WFAS v3 is off on this device (WFAS v2, legacy, is on). Turn it back on with: wfas v3 use v3"
            if (args.json) json("status" to "error", "message" to m) else note(yellow("i") + " " + m)
            return ExitCode.USAGE_ERROR
        }
        val pin = args.v3Pin
        val tty = ConsoleInput.hasTty && !args.json
        if (pin && !tty) {
            val m = "PIN pairing needs this terminal to compare the codes. Use the code (without --pin) instead."
            if (args.json) json("status" to "error", "message" to m) else note(yellow("i") + " " + m)
            return ExitCode.USAGE_ERROR
        }
        // PIN mode always asks: the users compare the codes. A code is a secret of
        // its own, so without a terminal it can stand alone.
        val ask = pin || (tty && !args.v3NoAsk && app.v3PairAsk)
        if (!engineOrExplain(app, args)) return ExitCode.RESOURCE_ERROR
        if (!openAndShow(app, args, pin, ask)) return ExitCode.RESOURCE_ERROR
        startAskPrompts(args)
        val end = System.currentTimeMillis() + WINDOW_SECONDS * 1000L + 500L
        var paired = false
        while (System.currentTimeMillis() < end) {
            val w = Wfas3Desktop.window.value
            if (w == null) { paired = handledPaired; break }
            if (w.paired != null) { paired = true; Thread.sleep(300); break }
            Thread.sleep(150)
        }
        Wfas3Desktop.closeWindow()
        if (!paired) {
            if (args.json) json("event" to "pair_window_closed", "paired" to false)
            else say("  The window closed without a pairing. Run 'wfas v3 invite' again for a new code.", args)
            return ExitCode.NOT_FOUND
        }
        return ExitCode.OK
    }

    @Volatile private var handledPaired = false

    // -- pairing (client side) --------------------------------------------------------

    private fun readSecret(prompt: String): String? {
        val c = System.console()
        return if (c != null) c.readPassword(prompt)?.let { String(it).also { _ -> it.fill(' ') } }
        else { print(prompt); System.out.flush(); readLine() }
    }

    private fun pair(cmd: V3Command.Pair, app: AppSettings, args: CliArgs): Int {
        if (!Wfas3Settings.isV3(app) || args.legacyV2) {
            val m = "WFAS v3 is off on this device (WFAS v2, legacy, is on). Turn it back on with: wfas v3 use v3"
            if (args.json) json("status" to "error", "message" to m) else note(yellow("i") + " " + m)
            return ExitCode.USAGE_ERROR
        }
        if (args.v3Guest) {
            val m = "A guest pairing lives only in memory, so it would end with this command. " +
                "Pair normally here, or pair as a guest from the app."
            if (args.json) json("status" to "error", "message" to m) else note(yellow("i") + " " + m)
            return ExitCode.USAGE_ERROR
        }
        if (!engineOrExplain(app, args)) return ExitCode.RESOURCE_ERROR
        val started = if (args.v3Pin) pairByPin(app, args) else pairByCode(cmd.code, app, args)
        if (!started) {
            Wfas3Desktop.cancelPairing()
            return ExitCode.USAGE_ERROR
        }
        val end = System.currentTimeMillis() + 150_000L
        var compared = false
        while (System.currentTimeMillis() < end) {
            when (val p = Wfas3Desktop.pairing.value) {
                is Wfas3Desktop.ClientPairing.Compare -> if (!compared) {
                    compared = true
                    println()
                    println("  The server now shows a code. This device shows " + bold(sasGroups(p.code)) + ".")
                    val same = ConsoleInput.askYesNo("  Same code on both devices? [y/N]", 60_000L, default = false)
                    Wfas3Desktop.confirmCode(same)
                    if (!same) println("  " + dim("Pairing stopped: the codes differ, or no answer was given."))
                }
                is Wfas3Desktop.ClientPairing.Done -> {
                    if (args.json) json("status" to "ok", "paired" to p.name)
                    else {
                        println()
                        println("  " + green("+") + "  Paired with ${bold(p.name)}.")
                        println("  " + dim("Connect with: wfas --client --connect \"${p.name}\""))
                        println()
                    }
                    return ExitCode.OK
                }
                is Wfas3Desktop.ClientPairing.Failed -> {
                    val why = wfas3PairFailText(p.reason, p.race)
                    if (args.json) json("status" to "failed", "reason" to p.reason, "race" to p.race, "message" to why)
                    else note(yellow("i") + " " + why)
                    return ExitCode.NOT_FOUND
                }
                else -> Unit
            }
            Thread.sleep(100)
        }
        Wfas3Desktop.cancelPairing()
        if (args.json) json("status" to "timeout") else note(yellow("i") + " No answer from the server in time. Open a new pairing window there and try again.")
        return ExitCode.NOT_FOUND
    }

    private fun pairByCode(given: String?, app: AppSettings, args: CliArgs): Boolean {
        val code = (given ?: run {
            if (!ConsoleInput.hasTty || args.json) null
            else readSecret("  Code shown on the server: ")
        })?.trim().orEmpty()
        if (code.isEmpty()) {
            val m = "No code given. Usage: wfas v3 pair   (asks for the code), or 'wfas v3 pair --pin'"
            if (args.json) json("status" to "error", "message" to m) else note(yellow("i") + " " + m)
            return false
        }
        if (!Wfas3Desktop.codeValid(code)) {
            val m = "That is not a valid WFAS v3 pairing code. Copy it exactly as the server shows it; " +
                "a code is valid for two minutes."
            if (args.json) json("status" to "error", "message" to m) else note(yellow("i") + " " + m)
            return false
        }
        if (given != null && !args.json && ConsoleInput.hasTty) {
            // A code on the command line is in the shell history and, while
            // this runs, in the process list of every user of this computer.
            println("  " + dim("Next time run 'wfas v3 pair' without the code: it is asked here and stays out of the shell history."))
        }
        say("  Pairing...", args)
        return Wfas3Desktop.pairWithCode(app, code, guest = false, mic = args.mic)
    }

    private fun pairByPin(app: AppSettings, args: CliArgs): Boolean {
        if (!ConsoleInput.hasTty || args.json) {
            val m = "PIN pairing needs a terminal: the PIN is typed and the codes compared here."
            if (args.json) json("status" to "error", "message" to m) else note(yellow("i") + " " + m)
            return false
        }
        if (!Wfas3Desktop.startPinSearch(app)) { note(red("!") + " " + Wfas3Desktop.failureText()); return false }
        println("  Looking for servers with an open PIN window" + dim(" (open one on the server: press n in 'wfas --server', or use the app)"))
        val end = System.currentTimeMillis() + 15_000L
        var list: List<Wfas3Beacon> = emptyList()
        while (System.currentTimeMillis() < end) {
            list = Wfas3Desktop.beacons.value
            if (list.isNotEmpty()) { Thread.sleep(1500); list = Wfas3Desktop.beacons.value; break }
            Thread.sleep(150)
        }
        if (list.isEmpty()) {
            Wfas3Desktop.stopPinSearch()
            note(yellow("i") + " No server with an open PIN window was found nearby. Check that the window is open and that both devices are on the same network.")
            return false
        }
        val b: Wfas3Beacon = if (list.size == 1) list[0] else {
            println()
            list.forEachIndexed { i, x ->
                println("    " + bold("${i + 1}.") + "  " + x.name.ifBlank { "?" } + "  " + dim(x.addr.hostText()) +
                    (if (x.used) dim("  (window already used)") else ""))
            }
            println("  " + dim("Only the names chosen by their owners are shown; check the name on the server screen."))
            print("  Which one? [1-${list.size}]: "); System.out.flush()
            val n = readLine()?.trim()?.toIntOrNull()
            list.getOrNull((n ?: 0) - 1) ?: run { Wfas3Desktop.stopPinSearch(); note("  Nothing chosen."); return false }
        }
        println("  Server: " + bold(b.name.ifBlank { "?" }) + "  " + dim(b.addr.hostText()))
        val pin = readSecret("  PIN shown on the server: ")?.filter { it.isDigit() }.orEmpty()
        if (pin.length != 8) {
            Wfas3Desktop.stopPinSearch()
            note(yellow("i") + " The PIN has 8 digits, as the server shows it.")
            return false
        }
        say("  Pairing...", args)
        return Wfas3Desktop.pairWithPin(b, pin, guest = false, mic = args.mic)
    }

    // -- 'wfas --server' with WFAS v3 -------------------------------------------------

    /** Header lines and notes for a v3 server; called instead of the v2 ones. */
    fun serverHeader(args: CliArgs, app: AppSettings) {
        val unused = v2OnlyOptions(args)
        if (unused.isNotEmpty()) {
            val m = "WFAS v3 is active, so ${unused.joinToString(", ")} " +
                (if (unused.size == 1) "does" else "do") + " not apply: only paired devices connect, and audio is always encrypted. " +
                "For the legacy protocol, add --legacy-v2."
            if (args.json) json("event" to "notice", "message" to m) else note(yellow("i") + " " + m)
        }
        if (args.json || args.viz) return
        val pairs = Wfas3Desktop.pairs.value
        say("", args)
        say(bold("  WiFi Audio Streaming") + "  - server mode, " + bold("WFAS v3"), args)
        say("  ${dim("Name")}      ${Wfas3Settings.deviceName(app)}", args)
        say("  ${dim("Port")}      UDP ${W3.UDP_PORT}" + if (args.multicast) " and ${W3.GROUP_PORT} (multicast)" else "", args)
        say("  ${dim("Multicast")} ${if (args.multicast) green("enabled") else dim("disabled")}", args)
        say("  ${dim("Paired")}    ${pairs.size} device" + (if (pairs.size == 1) "" else "s") +
            if (pairs.isEmpty()) dim("  - press p to pair one") else "", args)
    }

    /** Extra console commands of a v3 server. Returns true if the line was one of them. */
    fun serverCommand(line: String, args: CliArgs): Boolean {
        val app = Wfas3Desktop.appSettings
        val l = line.trim().lowercase()
        when (l) {
            "p", "pair", "qr", "code" -> {
                if (Wfas3Desktop.window.value != null) Wfas3Desktop.closeWindow()
                openAndShow(app, args, pin = false, ask = ConsoleInput.hasTty && !args.json && !args.v3NoAsk && app.v3PairAsk)
            }
            "n", "pin" -> {
                if (!ConsoleInput.hasTty || args.json) {
                    note(yellow("i") + " PIN pairing needs this terminal to compare the codes: press p for a code instead.")
                } else {
                    if (Wfas3Desktop.window.value != null) Wfas3Desktop.closeWindow()
                    openAndShow(app, args, pin = true, ask = true)
                }
            }
            "c", "close" -> {
                if (Wfas3Desktop.window.value != null) {
                    Wfas3Desktop.closeWindow()
                    say("  Pairing window closed.", args)
                }
            }
            "l", "list", "peers" -> {
                val peers = Wfas3Desktop.peers.value
                if (peers.isEmpty()) say("  No device is connected.", args)
                peers.forEach { pr ->
                    val tags = buildList {
                        if (!pr.streaming) add("waiting for audio")
                        if (pr.microphone) add("microphone")
                        if (pr.synchronised) add("synchronised")
                        if (pr.multicast) add("multicast")
                    }
                    say("    " + green("+") + "  " + pr.name + (if (tags.isEmpty()) "" else dim("  (" + tags.joinToString(", ") + ")")), args)
                }
            }
            "r", "rekey" -> say("  WFAS v3 renews its keys by itself; there is nothing to do.", args)
            else -> return false
        }
        return true
    }

    /** Prompts for pairing requests, and lines when devices come and go. */
    fun attachServer(args: CliArgs) {
        startAskPrompts(args)
        Thread({
            var known = emptyMap<Int, String>()
            while (true) {
                val now = Wfas3Desktop.peers.value.associate { it.session to it.name }
                if (Wfas3Desktop.serving) {
                    (now.keys - known.keys).forEach { s ->
                        if (args.json) json("event" to "client_connected", "name" to now[s])
                        else say("  " + green("+") + "  ${now[s]} connected", args)
                    }
                    (known.keys - now.keys).forEach { s ->
                        if (args.json) json("event" to "client_disconnected", "name" to known[s])
                        else say("  " + yellow("-") + "  ${known[s]} left", args)
                    }
                }
                known = now
                Thread.sleep(250)
            }
        }, "wfas3-cli-peers").apply { isDaemon = true; start() }
    }

    fun serverCommandsHint(args: CliArgs): String =
        "  Commands: q=stop, v <0-100>=volume, p=pair with a code, n=pair with a PIN, c=close pairing, l=connected devices"

    // -- 'wfas --client' with WFAS v3 -------------------------------------------------

    private fun noPairsHelp(args: CliArgs) {
        val m = "This device has no WFAS v3 pairing yet. On the server press p in 'wfas --server' (or use the app), " +
            "then run: wfas v3 pair (it asks for the code). A server that only has WFAS v2 is reached with --legacy-v2."
        if (args.json) json("event" to "no_pairs", "message" to m) else note(yellow("i") + " " + m)
    }

    /** Which pairing to connect to, and where; null when nothing fits (already explained). */
    private fun chooseTarget(args: CliArgs, app: AppSettings): kotlin.Pair<Wfas3PairInfo, Wfas3Addr?>? {
        val pairs = Wfas3Desktop.pairs.value.filterNot { it.suspect }
        val paused = Wfas3Desktop.pairs.value.filter { it.suspect }
        if (pairs.isEmpty()) {
            if (paused.isNotEmpty()) {
                note(yellow("i") + " The pairing with ${paused.joinToString { it.name }} is paused as a precaution " +
                    "(its keys were used by another device, or the data was restored from an older copy). Pair it again to continue.")
            } else noPairsHelp(args)
            return null
        }
        val target = args.serverIp
        if (target != null) {
            findPair(target, pairs)?.let { return it to null }
            val ip = runCatching { java.net.InetAddress.getByName(target.removePrefix("[").substringBefore("]")) }.getOrNull()
            if (ip == null) {
                note(yellow("i") + " '$target' is neither a paired server nor an address. Paired servers:")
                printPairs(Wfas3Desktop.pairs.value)
                return null
            }
            val at = Wfas3Addr.of(ip, W3.UDP_PORT)
            if (pairs.size == 1) return pairs[0] to at
            say("  ${dim("Asking the paired servers at")} ${ip.hostAddress}...", args)
            val found = Wfas3Desktop.locate(app, 3000L, ip)
            val here = found.filterValues { it.sameHost(ip) }.keys
            val p = pairs.firstOrNull { it.slot in here } ?: pairs.firstOrNull { it.slot in found.keys }
            if (p == null) {
                note(yellow("i") + " None of the paired servers answered at ${ip.hostAddress}. If that server has only " +
                    "WFAS v2, add --legacy-v2; otherwise check that it is running.")
                return null
            }
            return p to (found[p.slot] ?: at)
        }
        pairs.firstOrNull { it.slot == app.v3LastPair }?.let { return it to null }
        if (pairs.size == 1) return pairs[0] to null
        say("  ${dim("Looking for the paired servers...")}", args)
        val found = Wfas3Desktop.locate(app, 3000L)
        val live = pairs.filter { it.slot in found.keys }
        if (live.size == 1) return live[0] to found[live[0].slot]
        if (live.isEmpty()) {
            note(yellow("i") + " None of the paired servers answered. Check that one is running, or name it: --connect <name>")
            printPairs(pairs)
            return null
        }
        if (args.json || !ConsoleInput.hasTty) {
            if (args.json) json("event" to "choose_server", "servers" to live.map { it.name })
            else note(yellow("i") + " Several paired servers answered: choose one with --connect <name>.")
            return null
        }
        println()
        println("  Several paired servers answered - choose one:")
        live.forEachIndexed { i, p -> println("    " + bold("${i + 1}.") + "  " + cyan(p.name)) }
        print("  Enter number [1-${live.size}]: "); System.out.flush()
        val n = readLine()?.trim()?.toIntOrNull() ?: return null
        val p = live.getOrNull(n - 1) ?: return null
        return p to found[p.slot]
    }

    /**
     * Play a v3 server until the user stops or the session ends. Returns the
     * exit code. [onPcm] feeds a visualizer.
     */
    suspend fun runClient(
        args: CliArgs,
        settings: AllSettings,
        output: Mixer.Info,
        micInput: Mixer.Info?,
        viz: AudioVisualizer?,
    ): Int {
        val app = settings.app
        val unused = v2OnlyOptions(args)
        if (unused.isNotEmpty()) {
            val m = "WFAS v3 is active, so ${unused.joinToString(", ")} " +
                (if (unused.size == 1) "does" else "do") + " not apply: the pairing decides who talks to whom, always encrypted."
            if (args.json) json("event" to "notice", "message" to m) else note(yellow("i") + " " + m)
        }
        if (!engineOrExplain(app, args)) return ExitCode.RESOURCE_ERROR
        val (pair, at) = chooseTarget(args, app) ?: return ExitCode.NOT_FOUND

        if (args.json) json("event" to "client_connecting", "pid" to ProcessHandle.current().pid(), "server" to pair.name, "protocol" to "v3")
        else if (viz == null) {
            say("", args)
            say(bold("  WiFi Audio Streaming") + "  - client mode, " + bold("WFAS v3"), args)
            say("  ${dim("Server")}         ${cyan(pair.name)}" + (at?.let { dim("  at " + it.hostText()) } ?: ""), args)
            say("  ${dim("Output")}         ${output.name}", args)
            if (args.mic) say("  ${dim("Mic")}            ${micInput?.name ?: "default"}", args)
            say("", args)
            say(dim("  Commands: q=disconnect, v <0-100>=volume, m=microphone on/off"), args)
            say("", args)
        }

        val ok = Wfas3Desktop.connect(
            app, pair.slot, output, at, micInput, args.mic,
            args.latency ?: app.v3LatencyMs,
            onPcm = viz?.let { v -> { pcm: ShortArray -> v.feedFrame(pcm) } }
        )
        if (!ok) {
            val why = Wfas3Desktop.failureText()
            if (args.json) json("event" to "error", "message" to why) else note(red("!") + " " + why)
            return ExitCode.RESOURCE_ERROR
        }

        val done = kotlinx.coroutines.CompletableDeferred<Int>()
        var micOn = args.mic
        viz?.onQuit = { if (!done.isCompleted) done.complete(ExitCode.OK) }
        viz?.onVolume = { v -> NetworkHandler_v1.setClientVolume(v.coerceIn(0f, 2f)) }
        if (viz == null) {
            ConsoleInput.start { raw ->
                val line = raw.trim()
                when {
                    line.equals("q", true) || line.equals("quit", true) || line.equals("stop", true) ->
                        if (!done.isCompleted) done.complete(ExitCode.OK)
                    line.matches(Regex("(?i)v(?:ol(?:ume)?)?\\s+(\\d+(?:\\.\\d+)?)")) -> {
                        val pct = line.split("\\s+".toRegex()).last().toFloatOrNull() ?: return@start
                        NetworkHandler_v1.setClientVolume((pct / 100f).coerceIn(0f, 2f))
                        say("  volume: ${pct.toInt()}%", args)
                    }
                    line.equals("m", true) || line.equals("mic", true) -> {
                        micOn = !micOn
                        Wfas3Desktop.setMicrophone(micOn, micInput)
                        say("  microphone: " + if (micOn) "on" else "off", args)
                    }
                }
            }
        }

        var last: Wfas3ClientState? = null
        var connectedOnce = false
        var started = false
        while (!done.isCompleted) {
            val st = Wfas3Desktop.clientState.value
            if (st != last) {
                last = st
                if (st is Wfas3ClientState.Connected) connectedOnce = true
                val text = wfas3ClientStateText(st, Wfas3Desktop.pairs.value)
                if (args.json) json("event" to "state", "state" to st.javaClass.simpleName.lowercase(), "message" to text)
                else if (viz != null) viz.statusMsg = text
                else if (st !is Wfas3ClientState.Idle) say("  " + (if (st is Wfas3ClientState.Connected) green("+") else dim(".")) + "  " + text, args)
                if (st is Wfas3ClientState.Stopped) {
                    done.complete(if (st.reason == Wfas3ClientState.Reason.NOT_FOUND && !connectedOnce) ExitCode.NOT_FOUND else ExitCode.DISCONNECTED)
                }
                // Back to idle after a start: stopped from elsewhere ('wfas control stop').
                if (st is Wfas3ClientState.Idle && started && !done.isCompleted) done.complete(ExitCode.OK)
                if (st !is Wfas3ClientState.Idle) started = true
            }
            Wfas3Desktop.notices.value.forEach { n ->
                val m = when (n) {
                    is Wfas3Desktop.Notice.MicRefused -> "The server does not allow the microphone of this device: the audio continues without it."
                    is Wfas3Desktop.Notice.ForgetAsked -> "${n.name} removed this pairing. Remove it here too with: wfas v3 remove \"${n.name}\""
                    is Wfas3Desktop.Notice.Suspect -> "The pairing with ${n.name} is paused as a precaution. Pair it again to continue."
                    is Wfas3Desktop.Notice.Storage -> Wfas3Desktop.failureText()
                }
                if (args.json) json("event" to "notice", "message" to m) else note(yellow("i") + " " + m)
                Wfas3Desktop.dismiss(n)
            }
            delay(150)
        }
        val code = done.await()
        if (viz == null) ConsoleInput.stop()
        Wfas3Desktop.disconnect()
        delay(300)
        Wfas3Desktop.shutdown()
        return code
    }
}
