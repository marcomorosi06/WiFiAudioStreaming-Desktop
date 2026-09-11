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
 * RtpCli.kt
 *
 * Il lato terminale della ricezione RTP: 'wfas rtp ...'.
 *
 * Qui vivono la risoluzione della sorgente e i comandi che finiscono subito
 * (elenco, salvataggio, analisi di un SDP, stampa del descrittore). L'ascolto
 * vero e proprio sta in CliMode, perche' e' una sessione lunga come le altre;
 * quello che ha bisogno anche lui e' [resolve], ed e' l'unico motivo per cui
 * questa risoluzione e' pubblica.
 */

import java.io.File

object RtpCli {

    private fun dim(t: String)    = Ansi.dim(t)
    private fun bold(t: String)   = Ansi.bold(t)
    private fun cyan(t: String)   = Ansi.cyan(t)
    private fun green(t: String)  = Ansi.green(t)
    private fun red(t: String)    = Ansi.red(t)
    private fun yellow(t: String) = Ansi.yellow(t)

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

    /** Il testo di una chiave di traduzione, con la chiave come rete di sicurezza. */
    private fun t(key: String): String = runCatching { Strings.get(key) }.getOrNull()
        ?.takeIf { it.isNotBlank() && it != key } ?: key

    // ─────────────────────────────────────────────────────────────────────────
    // Sorgenti salvate
    // ─────────────────────────────────────────────────────────────────────────

    fun saved(): List<RtpSource> =
        SettingsRepository.loadSettings().app.rtpSources.mapNotNull { RtpSource.deserialize(it) }

    private fun store(sources: List<RtpSource>) {
        val s = SettingsRepository.loadSettings()
        SettingsRepository.saveSettings(
            s.copy(app = s.app.copy(rtpSources = sources.map { it.serialize() }))
        )
    }

    /**
     * Trova una sorgente salvata da come l'utente l'ha nominata.
     *
     * Si accetta l'indice della lista (#2), il nome esatto, un frammento di
     * nome se non e' ambiguo, e l'indirizzo. Il frammento ambiguo non sceglie
     * a caso: torna null, e chi chiama lo dice.
     */
    fun savedByRef(ref: String, list: List<RtpSource> = saved()): RtpSource? {
        val r = ref.trim()
        if (r.isEmpty() || list.isEmpty()) return null

        r.removePrefix("#").toIntOrNull()?.let { n ->
            if (r.startsWith("#") && n in 1..list.size) return list[n - 1]
        }
        list.firstOrNull { it.name.equals(r, ignoreCase = true) }?.let { return it }
        list.firstOrNull { it.displayName().equals(r, ignoreCase = true) }?.let { return it }
        list.firstOrNull { "${it.address}:${it.port}" == r }?.let { return it }

        val partial = list.filter { it.name.contains(r, ignoreCase = true) }
        return partial.singleOrNull()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Risoluzione della sorgente
    // ─────────────────────────────────────────────────────────────────────────

    sealed class Resolution {
        data class Ok(val source: RtpSource, val warnings: List<String> = emptyList()) : Resolution()
        data class Fail(val message: String, val hint: String? = null, val code: Int = ExitCode.USAGE_ERROR) : Resolution()
    }

    private fun looksLikeSdpPath(spec: String): Boolean =
        spec.endsWith(".sdp", ignoreCase = true) || File(spec).isFile

    private fun readSdp(path: String): String? =
        if (path == "-") runCatching { System.`in`.bufferedReader().readText() }.getOrNull()
        else runCatching { File(path).readText() }.getOrNull()

    /**
     * `host:porta` nelle forme che si incontrano davvero.
     *
     * IPv6 senza parentesi e' pieno di due punti, quindi non si puo' spezzare
     * sull'ultimo e sperare: senza parentesi l'intera stringa e' l'indirizzo.
     */
    fun parseEndpoint(spec: String, defaultPort: Int): RtpSource? {
        val s = spec.trim()
        if (s.isEmpty()) return null

        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return null
            val host = s.substring(1, close)
            val port = s.substring(close + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return RtpSource(address = host, port = port)
        }
        if (s.startsWith(":")) {
            val port = s.drop(1).toIntOrNull() ?: return null
            return RtpSource(address = "", port = port)
        }
        s.toIntOrNull()?.let { return RtpSource(address = "", port = it) }

        val colons = s.count { it == ':' }
        if (colons == 1) {
            val host = s.substringBefore(':')
            val port = s.substringAfter(':').toIntOrNull() ?: return null
            return RtpSource(address = host, port = port)
        }
        if (colons > 1) return RtpSource(address = s, port = defaultPort)   // IPv6 nudo
        return RtpSource(address = s, port = defaultPort)
    }

    /**
     * Da quel che c'e' sulla riga di comando alla sorgente da ascoltare.
     *
     * L'ordine e' dal piu' esplicito al piu' implicito: un SDP indicato batte
     * tutto, poi l'argomento posizionale, poi i flag, e solo alla fine l'unica
     * sorgente salvata. I flag ritoccano sempre il risultato, cosi'
     * 'wfas rtp listen casa --rtp-port 9100' e' una cosa sensata da scrivere.
     */
    fun resolve(args: CliArgs): Resolution {
        val warnings = mutableListOf<String>()
        var base: RtpSource? = null

        val spec = args.rtpSpec?.trim()?.takeIf { it.isNotEmpty() }
        val sdpPath = args.sdpFile ?: spec?.takeIf { it == "-" || looksLikeSdpPath(it) }

        if (sdpPath != null) {
            val text = readSdp(sdpPath)
                ?: return Resolution.Fail(
                    if (sdpPath == "-") "nothing arrived on standard input."
                    else "cannot read the SDP file '$sdpPath'.",
                    "Pass a readable .sdp file, or - to pipe the descriptor in."
                )
            val parsed = RtpSdp.parse(text)
            val src = parsed.source
                ?: return Resolution.Fail(
                    t(parsed.error ?: "sdp_err_empty"),
                    "The descriptor must contain an 'm=audio' line. " +
                            "'wfas --server --rtp --sdp' prints a valid one."
                )
            warnings += parsed.warnings
            base = src.copy(
                origin = if (sdpPath == "-") RtpSourceOrigin.SDP_PASTE else RtpSourceOrigin.SDP_FILE,
                sdpText = text
            )
        } else if (spec != null) {
            val list = saved()
            base = savedByRef(spec, list)
                ?: parseEndpoint(spec, if (args.rtpPortExplicit) args.rtpPort else 9094)
                ?: return Resolution.Fail(
                    "'$spec' is not a saved source, an address or an SDP file.",
                    if (list.isEmpty()) "Nothing is saved yet. Try 'wfas rtp listen 239.255.0.1:9094'."
                    else "Saved: " + list.joinToString(", ") { it.displayName() }
                )
        }

        if (base == null) {
            val byFlags = args.rtpAddress != null || args.rtpPortExplicit || args.rtpCodec != null ||
                    args.rtpRate != null || args.rtpChannels != null || args.rtpPayload != null
            val list = saved()
            base = when {
                byFlags        -> RtpSource()
                list.size == 1 -> list.first()
                list.isEmpty() -> return Resolution.Fail(
                    "no RTP source given.",
                    "Import one with 'wfas rtp listen stream.sdp', name it directly with " +
                            "'wfas rtp listen 239.255.0.1:9094', or save one with 'wfas rtp save'."
                )
                else -> return Resolution.Fail(
                    "${list.size} sources are saved, so the one to listen to has to be named.",
                    "Saved: " + list.mapIndexed { i, s -> "#${i + 1} ${s.displayName()}" }.joinToString(", ")
                )
            }
        }

        val src = base.copy(
            name        = args.rtpName ?: base.name,
            address     = args.rtpAddress ?: base.address,
            port        = if (args.rtpPortExplicit) args.rtpPort else base.port,
            encoding    = args.rtpCodec ?: base.encoding,
            sampleRate  = args.rtpRate ?: base.sampleRate,
            channels    = args.rtpChannels ?: base.channels,
            payloadType = args.rtpPayload ?: base.payloadType
        )

        val errs = RtpSdp.validate(src)
        if (errs.isNotEmpty()) return Resolution.Fail(errs.joinToString(" ") { t(it) })

        return Resolution.Ok(src, warnings)
    }

    fun reportFailure(f: Resolution.Fail, json: Boolean) {
        if (json) jsonLine("event" to "rtp_error", "message" to f.message, "hint" to f.hint)
        else {
            System.err.println(red("!") + " " + f.message)
            f.hint?.let { System.err.println(dim("    $it")) }
        }
    }

    fun printWarnings(warnings: List<String>, json: Boolean, quiet: Boolean) {
        if (warnings.isEmpty() || quiet) return
        for (w in warnings.distinct()) {
            if (json) jsonLine("event" to "rtp_warning", "key" to w, "message" to t(w))
            else System.err.println(yellow("!") + " " + t(w))
        }
    }

    fun describe(s: RtpSource): String {
        val where = if (s.address.isBlank()) "any interface :${s.port}" else "${s.address}:${s.port}"
        val kind = if (s.address.isBlank()) "unicast"
        else if (s.isMulticast) "multicast" else "unicast"
        return "$where  ${s.formatSummary()}  $kind  " +
                (if (s.isNativePcm) "native" else "FFmpeg")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Comandi
    // ─────────────────────────────────────────────────────────────────────────

    fun run(cmd: RtpCommand, args: CliArgs): Int = when (cmd) {
        is RtpCommand.Sources -> listSources(args)
        is RtpCommand.Inspect -> inspect(args)
        is RtpCommand.Sdp     -> printSdp(args)
        is RtpCommand.Save    -> save(cmd.name, args)
        is RtpCommand.Forget  -> forget(cmd.target, args)
        is RtpCommand.Listen  -> ExitCode.OK          // gestito da runCli
    }

    private fun listSources(args: CliArgs): Int {
        val list = saved()
        if (args.json) {
            list.forEachIndexed { i, s ->
                jsonLine(
                    "event" to "rtp_source", "index" to i + 1, "name" to s.displayName(),
                    "address" to s.address, "port" to s.port, "codec" to s.encoding,
                    "rate" to s.sampleRate, "channels" to s.channels,
                    "payload" to s.payloadType, "multicast" to s.isMulticast,
                    "native" to s.isNativePcm
                )
            }
            if (list.isEmpty()) jsonLine("event" to "rtp_sources_empty")
            return ExitCode.OK
        }
        if (list.isEmpty()) {
            println("  " + dim("No RTP source saved yet."))
            println("  " + dim("Save one with 'wfas rtp save <name> --rtp-address 239.255.0.1 --rtp-port 9094'."))
            return ExitCode.OK
        }
        println()
        println("  " + bold("#".padEnd(4)) + bold("NAME".padEnd(22)) + bold("ADDRESS".padEnd(26)) +
                bold("FORMAT".padEnd(28)) + bold("PATH"))
        list.forEachIndexed { i, s ->
            val addr = if (s.address.isBlank()) ":${s.port}" else "${s.address}:${s.port}"
            println("  " + "${i + 1}".padEnd(4) + cyan(s.displayName().take(20).padEnd(22)) +
                    addr.padEnd(26) + s.formatSummary().padEnd(28) +
                    (if (s.isNativePcm) green("native") else yellow("FFmpeg")))
        }
        println()
        return ExitCode.OK
    }

    private fun inspect(args: CliArgs): Int {
        when (val r = resolve(args)) {
            is Resolution.Fail -> { reportFailure(r, args.json); return r.code }
            is Resolution.Ok -> {
                val s = r.source
                if (args.json) {
                    jsonLine(
                        "event" to "rtp_inspect", "name" to s.displayName(),
                        "address" to s.address, "port" to s.port, "codec" to s.encoding,
                        "rate" to s.sampleRate, "channels" to s.channels,
                        "payload" to s.payloadType, "multicast" to s.isMulticast,
                        "native" to s.isNativePcm, "origin" to s.origin.name.lowercase(),
                        "warnings" to r.warnings.size
                    )
                    r.warnings.distinct().forEach { jsonLine("event" to "rtp_warning", "key" to it, "message" to t(it)) }
                    return ExitCode.OK
                }
                println()
                println("  " + bold("RTP source") + "  " + cyan(s.displayName()))
                println("  " + dim("Address ") + (if (s.address.isBlank()) "(any interface)" else s.address))
                println("  " + dim("Port    ") + s.port)
                println("  " + dim("Codec   ") + "${s.encoding} ${dim("payload")} ${s.payloadType}")
                println("  " + dim("Format  ") + "${s.sampleRate} Hz, ${if (s.channels == 1) "mono" else "${s.channels} ch"}")
                println("  " + dim("Delivery") + " " + (if (s.isMulticast) "multicast" else "unicast"))
                println("  " + dim("Path    ") + (if (s.isNativePcm)
                    green("native") + dim("  L16 is played straight through, lowest latency")
                else
                    yellow("FFmpeg") + dim("  decoded by FFmpeg, which adds its own buffering")))
                if (r.warnings.isNotEmpty()) {
                    println()
                    r.warnings.distinct().forEach { println("  " + yellow("!") + " " + t(it)) }
                }
                println()
                println("  " + dim("Listen with: ") + "wfas rtp listen " +
                        (if (s.address.isBlank()) ":${s.port}" else "${s.address}:${s.port}"))
                println()
                return ExitCode.OK
            }
        }
    }

    private fun printSdp(args: CliArgs): Int {
        when (val r = resolve(args)) {
            is Resolution.Fail -> { reportFailure(r, args.json); return r.code }
            is Resolution.Ok -> {
                val text = r.source.sdpText ?: RtpSdp.synthesize(r.source)
                println(text)
                args.sdpOut?.let { path ->
                    runCatching { File(path).writeText(text) }
                        .onSuccess { if (!args.quiet) System.err.println(dim("  SDP written to $path")) }
                        .onFailure { System.err.println(red("!") + " Could not write $path: ${it.message}") }
                }
                return ExitCode.OK
            }
        }
    }

    private fun save(nameArg: String?, args: CliArgs): Int {
        when (val r = resolve(args)) {
            is Resolution.Fail -> { reportFailure(r, args.json); return r.code }
            is Resolution.Ok -> {
                val name = (nameArg ?: args.rtpName ?: r.source.name).ifBlank { r.source.displayName() }
                val entry = r.source.copy(name = name, origin = RtpSourceOrigin.SAVED)
                // Stesso indirizzo e stessa porta sono la stessa sorgente: si
                // aggiorna invece di accumulare doppioni che poi confondono la
                // scelta per nome.
                val list = saved().filterNot { it.address == entry.address && it.port == entry.port } + entry
                store(list)
                if (args.json) jsonLine(
                    "event" to "rtp_saved", "name" to name,
                    "address" to entry.address, "port" to entry.port, "total" to list.size
                ) else {
                    println("  " + green("+") + " Saved " + cyan(name) + dim("  " + describe(entry)))
                }
                printWarnings(r.warnings, args.json, args.quiet)
                return ExitCode.OK
            }
        }
    }

    private fun forget(target: String, args: CliArgs): Int {
        val list = saved()
        if (target.equals("all", ignoreCase = true)) {
            store(emptyList())
            if (args.json) jsonLine("event" to "rtp_forgot", "removed" to list.size)
            else println("  " + green("+") + " Removed ${list.size} saved source(s).")
            return ExitCode.OK
        }
        val hit = savedByRef(target, list)
        if (hit == null) {
            if (args.json) jsonLine("event" to "rtp_error", "message" to "no saved source matches '$target'")
            else {
                System.err.println(red("!") + " No saved source matches '$target'.")
                if (list.isNotEmpty()) System.err.println(
                    dim("    Saved: " + list.mapIndexed { i, s -> "#${i + 1} ${s.displayName()}" }.joinToString(", "))
                )
            }
            return ExitCode.NOT_FOUND
        }
        store(list.filterNot { it.address == hit.address && it.port == hit.port && it.name == hit.name })
        if (args.json) jsonLine("event" to "rtp_forgot", "name" to hit.displayName(), "removed" to 1)
        else println("  " + green("+") + " Removed " + cyan(hit.displayName()) + ".")
        return ExitCode.OK
    }
}
