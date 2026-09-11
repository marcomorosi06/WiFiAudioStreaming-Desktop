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
 * SnapcastMixer.kt
 *
 * Il mixer Snapcast dentro il terminale: 'wfas snapcast mixer'.
 *
 * Le stesse cose della finestra — gruppi, client, volumi, mute, latenza —
 * disegnate a caratteri e comandate dalla tastiera. Non e' un vezzo: su un
 * Raspberry o dentro una sessione ssh la finestra non c'e', e alzare il volume
 * di una stanza un comando alla volta, ognuno che riapre il canale di
 * controllo, e' lento da scrivere e lento da eseguire.
 *
 * Convive con i comandi singoli invece di sostituirli: quelli restano la via
 * per gli script ('wfas snapcast volume cucina 40', '--json' dappertutto),
 * questo e' la via per le mani. Parlano allo stesso canale di controllo e allo
 * stesso modello, quindi non possono raccontare storie diverse.
 *
 * Quel che si vede viene sempre dal server: nessuno stato locale che possa
 * scollarsi. La sovrapposizione delle intenzioni sta gia' dentro
 * [SnapcastControlClient], quindi premere un tasto si vede subito senza che il
 * valore torni indietro un istante dopo.
 *
 * Un solo lucchetto per tutto: la tastiera scrive da un thread e il disegno
 * legge da un altro. Senza, si vedrebbe a volte un fotogramma vecchio — e
 * peggio, un tasto premuto potrebbe non ridisegnare affatto.
 */

private const val MIX_ESC = "\u001B"

object SnapcastMixer {

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    /**
     * Il mixer ha senso solo davanti a una persona.
     *
     * Senza terminale — output rediretto, cron, una pipe — disegnare uno
     * schermo intero produce migliaia di righe di sequenze di controllo. In
     * quel caso chi chiama stampa la vista fissa, che e' la risposta giusta
     * alla stessa domanda.
     */
    fun usable(): Boolean =
        System.getenv("WFAS_NO_INTERACTIVE") == null &&
                System.getenv("TERM") != "dumb" &&
                System.console() != null

    // ── Righe ───────────────────────────────────────────────────────────────

    private sealed class Row {
        abstract val key: String
        data class Grp(val group: SnapGroupInfo, val n: Int) : Row() {
            override val key get() = "g:" + group.id
        }
        data class Cli(val client: SnapClientInfo, val group: SnapGroupInfo, val n: Int) : Row() {
            override val key get() = "c:" + client.id
        }
    }

    private fun rowsOf(st: SnapServerStatus): List<Row> {
        val out = ArrayList<Row>()
        var n = 0
        st.groups.forEachIndexed { gi, g ->
            out.add(Row.Grp(g, gi + 1))
            g.clients.forEach { c -> n++; out.add(Row.Cli(c, g, n)) }
        }
        return out
    }

    private class Menu(
        val title: String,
        val items: List<Pair<String, String>>,
        val onPick: (String) -> Unit
    ) {
        var sel = 0
    }

    private class Prompt(
        val title: String,
        var text: String,
        val numeric: Boolean,
        val onSubmit: (String) -> Unit
    )

    // ── Disegno ─────────────────────────────────────────────────────────────

    private fun dim(t: String)    = Ansi.dim(t)
    private fun bold(t: String)   = Ansi.bold(t)
    private fun cyan(t: String)   = Ansi.cyan(t)
    private fun green(t: String)  = Ansi.green(t)
    private fun yellow(t: String) = Ansi.yellow(t)
    private fun red(t: String)    = Ansi.red(t)

    /** Taglia e riempie il testo NUDO: il colore si mette dopo, o i conti saltano. */
    private fun fit(s: String, n: Int): String = when {
        n <= 0          -> ""
        s.length <= n   -> s.padEnd(n)
        n == 1          -> "…"
        else            -> s.take(n - 1) + "…"
    }

    private fun bar(percent: Int, width: Int, muted: Boolean): String {
        val filled = (percent.coerceIn(0, 100) * width + 50) / 100
        val body = "█".repeat(filled) + "░".repeat((width - filled).coerceAtLeast(0))
        return when {
            muted         -> dim(body)
            percent >= 85 -> yellow(body)
            else          -> green(body)
        }
    }

    private fun termSize(): Pair<Int, Int> {
        val ec = System.getenv("COLUMNS")?.toIntOrNull()
        val er = System.getenv("LINES")?.toIntOrNull()
        if (ec != null && er != null && ec in 20..500 && er in 8..300) return ec to er
        runCatching {
            if (isWindows) {
                val tmp = java.io.File(System.getProperty("java.io.tmpdir"), "wfas_mix_sz.txt")
                runCatching { tmp.delete() }
                val cmd = "[Console]::WindowWidth.ToString()+' '+[Console]::WindowHeight.ToString() | " +
                        "Set-Content -LiteralPath '" + tmp.absolutePath + "' -Encoding ascii"
                ProcessBuilder("powershell", "-NoProfile", "-Command", cmd)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor()
                if (tmp.exists()) {
                    val p = tmp.readText().trim().split(Regex("\\s+"))
                    val w = p.getOrNull(0)?.toIntOrNull()
                    val h = p.getOrNull(1)?.toIntOrNull()
                    if (w != null && h != null && w in 20..500 && h in 8..300) return w to h
                }
            } else {
                val o = ProcessBuilder("sh", "-c", "stty size < /dev/tty 2>/dev/null")
                    .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
                val p = o.split(Regex("\\s+"))
                val h = p.getOrNull(0)?.toIntOrNull()
                val w = p.getOrNull(1)?.toIntOrNull()
                if (w != null && h != null && w in 20..500 && h in 8..300) return w to h
            }
        }
        return 100 to 30
    }

    // ── Sessione ────────────────────────────────────────────────────────────

    /**
     * Apre il mixer su un canale di controllo gia' collegato.
     *
     * Non lo apre e non lo chiude: chi chiama sa se e' suo o se e' quello di
     * una sessione di ascolto in corso. Allo stesso modo l'audio non lo tocca:
     * [audioLine] e' l'unico filo che lo lega alla riproduzione, e serve solo a
     * mostrarne lo stato in cima.
     *
     * [stopWhen] esiste perche' la sessione puo' finire da fuori — 'wfas
     * control stop' da un altro terminale, o lo stream che cade — e allora
     * questa schermata deve chiudersi da sola invece di restare aperta su una
     * cosa che non c'e' piu'.
     */
    fun run(
        client: SnapcastControlClient,
        statusOf: () -> SnapControlStatus,
        selfId: String?,
        title: String,
        audioLine: () -> String? = { null },
        stopWhen: () -> Boolean = { false }
    ): Int {
        val lock = Any()
        var running = true
        var selKey: String? = null
        var menu: Menu? = null
        var prompt: Prompt? = null
        var notice = ""
        var noticeAt = 0L
        var dirty = true

        fun say(msg: String) = synchronized(lock) {
            notice = msg; noticeAt = System.currentTimeMillis(); dirty = true
        }

        fun rows() = rowsOf(statusOf().status)

        fun selected(): Row? {
            val rs = rows()
            if (rs.isEmpty()) return null
            return rs.firstOrNull { it.key == selKey } ?: rs.first().also { selKey = it.key }
        }

        fun move(delta: Int) {
            val rs = rows()
            if (rs.isEmpty()) return
            val i = rs.indexOfFirst { it.key == selKey }.coerceAtLeast(0)
            selKey = rs[(i + delta).coerceIn(0, rs.size - 1)].key
        }

        /**
         * Il volume di gruppo non esiste nel protocollo: si sposta quello di
         * ogni suo client della stessa quantita'. Cosi' il rapporto fra le
         * stanze resta quello impostato, invece di appiattirsi su un valore
         * unico che l'utente non ha chiesto.
         */
        fun nudge(delta: Int) {
            when (val r = selected()) {
                is Row.Cli -> client.setClientVolume(
                    r.client.id, (r.client.volumePercent + delta).coerceIn(0, 100), r.client.muted
                )
                is Row.Grp -> r.group.clients.forEach { c ->
                    client.setClientVolume(c.id, (c.volumePercent + delta).coerceIn(0, 100), c.muted)
                }
                null -> Unit
            }
        }

        fun toggleMute() {
            when (val r = selected()) {
                is Row.Cli -> client.setClientVolume(r.client.id, r.client.volumePercent, !r.client.muted)
                is Row.Grp -> client.setGroupMute(r.group.id, !r.group.muted)
                null -> Unit
            }
        }

        fun openMoveMenu() {
            val r = selected() as? Row.Cli
            if (r == null) { say("Moving applies to a client."); return }
            val others = statusOf().status.groups.filter { it.id != r.group.id }
            if (others.isEmpty()) { say("There is no other group to move it to."); return }
            menu = Menu("Move ${r.client.displayName()} to", others.map { it.displayName() to it.id }) { gid ->
                val target = statusOf().status.groups.firstOrNull { it.id == gid }
                if (target != null) {
                    // Snapcast non ha uno "sposta": l'unico comando riscrive
                    // l'intera composizione del gruppo di destinazione.
                    client.setGroupClients(gid, (target.clients.map { it.id } + r.client.id).distinct())
                    client.refresh()
                    say("Moved to ${target.displayName()}.")
                }
            }
        }

        fun openStreamMenu() {
            val g = when (val r = selected()) {
                is Row.Grp -> r.group
                is Row.Cli -> r.group
                null -> null
            }
            if (g == null) return
            val streams = statusOf().status.streams
            if (streams.isEmpty()) { say("The server did not report any stream."); return }
            menu = Menu("Stream for ${g.displayName()}", streams.map { "${it.id}  ${it.status}" to it.id }) { sid ->
                client.setGroupStream(g.id, sid)
                client.refresh()
                say("${g.displayName()} now plays $sid.")
            }
        }

        fun openRename() {
            when (val r = selected()) {
                is Row.Cli -> prompt = Prompt("Name for ${r.client.displayName()}", r.client.name, false) {
                    client.setClientName(r.client.id, it); say("Renamed.")
                }
                is Row.Grp -> prompt = Prompt("Name for ${r.group.displayName()}", r.group.name, false) {
                    client.setGroupName(r.group.id, it); say("Renamed.")
                }
                null -> Unit
            }
        }

        fun openLatency() {
            val r = selected() as? Row.Cli
            if (r == null) { say("Latency applies to a client."); return }
            prompt = Prompt(
                "Latency for ${r.client.displayName()} in ms, -2000 to 2000",
                r.client.latencyMs.toString(), true
            ) {
                val ms = it.trim().toIntOrNull()
                if (ms == null) say("'$it' is not a number.")
                else { client.setClientLatency(r.client.id, ms.coerceIn(-2000, 2000)); say("Latency set.") }
            }
        }

        /**
         * Staccare un client dipende dal server: alcuni non lo rialloggiano e
         * lo lasciano orfano — invisibile ovunque mentre continua a suonare. Si
         * controlla dopo, e se e' successo si rimette dov'era.
         */
        fun split() {
            val r = selected() as? Row.Cli
            if (r == null) { say("Splitting applies to a client."); return }
            if (r.group.clients.size <= 1) { say("It is already alone in its group."); return }
            val original = r.group.clients.map { it.id }
            val gid = r.group.id
            val cid = r.client.id
            val label = r.client.displayName()
            client.setGroupClients(gid, original.filter { it != cid })
            client.refresh()
            say("Detaching $label…")
            Thread {
                Thread.sleep(1600)
                val landed = statusOf().status.groups.any { g -> g.clients.any { it.id == cid } }
                if (!landed) {
                    client.setGroupClients(gid, original)
                    client.refresh()
                    say("This server does not re-home a detached client: $label was put back.")
                } else {
                    say("$label is now in a group of its own.")
                }
            }.apply { isDaemon = true; name = "wfas-snap-split" }.start()
        }

        // ── Tastiera ────────────────────────────────────────────────────────

        fun onKey(k: String) = synchronized(lock) {
            dirty = true
            val p = prompt
            if (p != null) {
                when (k) {
                    "ENTER" -> { prompt = null; p.onSubmit(p.text) }
                    "ESC" -> { prompt = null; say("Cancelled.") }
                    "BACKSPACE" -> if (p.text.isNotEmpty()) p.text = p.text.dropLast(1)
                    else -> if (k.length == 1) {
                        val c = k[0]
                        if (!p.numeric || c.isDigit() || (c == '-' && p.text.isEmpty())) p.text += c
                    }
                }
                return@synchronized
            }
            val m = menu
            if (m != null) {
                when (k) {
                    "UP" -> m.sel = (m.sel - 1).coerceAtLeast(0)
                    "DOWN" -> m.sel = (m.sel + 1).coerceAtMost(m.items.size - 1)
                    "ENTER" -> { menu = null; m.items.getOrNull(m.sel)?.let { m.onPick(it.second) } }
                    "ESC", "q", "Q" -> { menu = null; say("Cancelled.") }
                    else -> {
                        val n = k.toIntOrNull()
                        if (n != null && n in 1..m.items.size) { menu = null; m.onPick(m.items[n - 1].second) }
                    }
                }
                return@synchronized
            }
            when (k) {
                "UP", "k", "K" -> move(-1)
                "DOWN", "j", "J" -> move(1)
                "LEFT", "h", "H", "-", "_" -> nudge(-5)
                "RIGHT", "l", "L", "+", "=" -> nudge(5)
                "m", "M" -> toggleMute()
                "g", "G" -> openMoveMenu()
                "t", "T" -> openStreamMenu()
                "n", "N" -> openRename()
                "y", "Y" -> openLatency()
                "s", "S" -> split()
                "r", "R" -> { client.refresh(); say("Refreshed.") }
                "q", "Q", "ESC" -> running = false
                else -> Unit
            }
        }

        // ── Schermo ─────────────────────────────────────────────────────────

        fun header(cols: Int): List<String> {
            val st = statusOf()
            val s = st.status
            val ctrl = when (st.state) {
                SnapControlState.CONNECTED  -> green("connected")
                SnapControlState.CONNECTING -> yellow("connecting")
                SnapControlState.ERROR      -> red("error")
                SnapControlState.IDLE       -> dim("idle")
            }
            val who = listOfNotNull(
                s.serverName.takeIf { it.isNotBlank() },
                s.serverVersion.takeIf { it.isNotBlank() }
            ).joinToString(" ")
            val head = ArrayList<String>()
            head.add(bold("  WiFi Audio Streaming") + dim("  ·  Snapcast mixer"))
            head.add("  " + cyan(fit(title, 26)) + dim(fit(who, 24)) +
                    dim("${s.groups.size} groups · ${s.allClients.size} clients · control ") + ctrl)
            audioLine()?.let { head.add("  " + it) }
            head.add("")
            return head
        }

        fun body(cols: Int): List<String> {
            val nameW = (cols - 68).coerceIn(14, 28)
            val barW  = if (cols < 92) 12 else 20
            val selK = selected()?.key
            val out = ArrayList<String>()
            for (r in rows()) {
                val here = r.key == selK
                val cursor = if (here) cyan("▸") else " "
                when (r) {
                    is Row.Grp -> {
                        val label = fit("#${r.n} ${r.group.displayName()}", nameW + 5)
                        out.add("  $cursor  " + (if (here) bold(label) else label) +
                                dim("stream ") + fit(r.group.streamId, 16) +
                                fit(r.group.clients.size.toString() + " client" +
                                        (if (r.group.clients.size == 1) "" else "s"), 11) +
                                (if (r.group.muted) yellow("muted") else dim("on")))
                    }
                    is Row.Cli -> {
                        val c = r.client
                        val dot = if (c.connected) green("●") else dim("○")
                        val nm = fit("#${r.n} ${c.displayName()}", nameW)
                        out.add("  $cursor $dot " + (if (here) bold(nm) else nm) + " " +
                                fit(c.ip, 16) +
                                bar(c.volumePercent, barW, c.muted) + " " +
                                (if (c.muted) yellow(fit("muted", 7)) else fit("${c.volumePercent}%", 7)) +
                                dim("lat ") + fit("${c.latencyMs}ms", 8) +
                                (if (c.id == selfId) cyan("this machine") else ""))
                    }
                }
            }
            if (out.isEmpty()) {
                out.add("  " + dim(
                    if (statusOf().state == SnapControlState.CONNECTED) "This server has no group yet."
                    else "Waiting for the control channel…"
                ))
            }
            return out
        }

        fun footer(): List<String> {
            val out = ArrayList<String>()
            out.add("")
            val p = prompt
            val m = menu
            when {
                p != null -> {
                    out.add("  " + bold(p.title))
                    out.add("  > " + cyan(p.text) + "_")
                    out.add("  " + dim("Enter confirms, Esc cancels"))
                }
                m != null -> {
                    out.add("  " + bold(m.title))
                    m.items.forEachIndexed { i, item ->
                        val mark = if (i == m.sel) cyan("▸") else " "
                        out.add("   $mark ${i + 1}. " + (if (i == m.sel) bold(item.first) else item.first))
                    }
                    out.add("  " + dim("↑↓ choose, Enter or the number picks, Esc cancels"))
                }
                else -> {
                    out.add("  " + dim("↑↓ select   ←→ volume ±5   m mute   g group   s alone   " +
                            "n name   y latency   t stream"))
                    out.add("  " + dim("r refresh   q quit") +
                            dim("       for scripts: wfas snapcast clients --json"))
                }
            }
            val st = statusOf()
            if (notice.isNotBlank() && System.currentTimeMillis() - noticeAt < 6000) {
                out.add("  " + yellow("· ") + notice)
            } else if (st.state == SnapControlState.ERROR && st.errorDetail != null) {
                out.add("  " + red("! ") + st.errorDetail)
            } else {
                out.add("")
            }
            return out
        }

        /**
         * Con piu' righe che schermo si scorre attorno alla selezione: sparire
         * dal fondo mentre si preme la freccia sarebbe il modo piu' rapido di
         * perdersi.
         */
        fun frame(cols: Int, rowsAvail: Int): List<String> {
            val head = header(cols)
            val foot = footer()
            val bodyLines = body(cols)
            val room = (rowsAvail - head.size - foot.size).coerceAtLeast(3)
            if (bodyLines.size <= room) return head + bodyLines + foot
            val selK = selected()?.key
            val selIdx = rows().indexOfFirst { it.key == selK }.coerceAtLeast(0)
            var from = (selIdx - room / 2).coerceIn(0, (bodyLines.size - room).coerceAtLeast(0))
            val slice = bodyLines.subList(from, (from + room).coerceAtMost(bodyLines.size))
            return head + slice + foot
        }

        // ── Ciclo ───────────────────────────────────────────────────────────

        val restored = java.util.concurrent.atomic.AtomicBoolean(false)
        fun restore() {
            if (!restored.compareAndSet(false, true)) return
            print("$MIX_ESC[?25h$MIX_ESC[?1049l")
            System.out.flush()
            if (!isWindows) runCatching {
                ProcessBuilder("sh", "-c", "stty sane < /dev/tty").start().waitFor()
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { restore() })

        print("$MIX_ESC[?1049h$MIX_ESC[2J$MIX_ESC[H$MIX_ESC[?25l")
        System.out.flush()

        var keyProc: Process? = null
        val reader = Thread {
            try {
                if (isWindows) {
                    val cmd = "while(\$true){\$k=\$Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown');" +
                            "[Console]::Out.WriteLine([int]\$k.Character.ToString() + ' ' + \$k.VirtualKeyCode);" +
                            "[Console]::Out.Flush()}"
                    val pb = ProcessBuilder("powershell", "-NoProfile", "-Command", cmd)
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
                    pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                    val proc = pb.start()
                    keyProc = proc
                    val rd = proc.inputStream.bufferedReader()
                    while (running) {
                        val line = rd.readLine() ?: break
                        val parts = line.trim().split(" ")
                        val ch = parts.getOrNull(0)?.toIntOrNull() ?: 0
                        val vk = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        val tok = when (vk) {
                            38 -> "UP"
                            40 -> "DOWN"
                            37 -> "LEFT"
                            39 -> "RIGHT"
                            13 -> "ENTER"
                            27 -> "ESC"
                            8  -> "BACKSPACE"
                            else -> if (ch in 32..126) ch.toChar().toString() else ""
                        }
                        if (tok.isNotEmpty()) onKey(tok)
                    }
                } else {
                    runCatching {
                        ProcessBuilder("sh", "-c", "stty -echo -icanon min 1 time 0 < /dev/tty")
                            .start().waitFor()
                    }
                    val inp = System.`in`
                    while (running) {
                        val b = inp.read()
                        if (b < 0) break
                        if (b == 27) {
                            // Una freccia arriva tutta insieme, un Esc premuto
                            // da solo no: si distinguono guardando se dietro
                            // c'e' subito dell'altro.
                            if (inp.available() > 0) {
                                val a = inp.read()
                                if (a == '['.code && inp.available() > 0) {
                                    when (inp.read().toChar()) {
                                        'A' -> onKey("UP")
                                        'B' -> onKey("DOWN")
                                        'C' -> onKey("RIGHT")
                                        'D' -> onKey("LEFT")
                                        else -> Unit
                                    }
                                } else {
                                    onKey("ESC")
                                }
                            } else {
                                onKey("ESC")
                            }
                        } else if (b == 10 || b == 13) {
                            onKey("ENTER")
                        } else if (b == 127 || b == 8) {
                            onKey("BACKSPACE")
                        } else if (b in 32..126) {
                            onKey(b.toChar().toString())
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.name = "wfas-snap-mixer-keys"
        reader.start()

        var lastFingerprint = ""
        var size = termSize()
        var sizeAt = 0L
        try {
            while (running) {
                if (stopWhen()) break
                val now = System.currentTimeMillis()
                if (now - sizeAt > 1000) { sizeAt = now; size = termSize() }
                // Anche lo stato dell'audio fa parte di quel che si vede: se
                // non entrasse qui, la riga in cima resterebbe ferma su
                // "connecting" mentre la musica gia' suona.
                val fp = statusOf().toString() + "|" + (audioLine() ?: "")
                val redraw = synchronized(lock) {
                    val r = dirty || fp != lastFingerprint
                    dirty = false
                    r
                }
                if (redraw) {
                    lastFingerprint = fp
                    val cols = size.first
                    val rowsAvail = size.second
                    val lines = synchronized(lock) { frame(cols, rowsAvail) }
                    val sb = StringBuilder(cols * (rowsAvail + 2))
                    sb.append("$MIX_ESC[H")
                    for (i in 0 until rowsAvail) {
                        sb.append(lines.getOrElse(i) { "" }).append("$MIX_ESC[K")
                        if (i < rowsAvail - 1) sb.append('\n')
                    }
                    sb.append("$MIX_ESC[J")
                    print(sb)
                    System.out.flush()
                }
                Thread.sleep(60)
            }
        } finally {
            running = false
            runCatching { keyProc?.destroyForcibly() }
            restore()
        }
        return ExitCode.OK
    }
}
