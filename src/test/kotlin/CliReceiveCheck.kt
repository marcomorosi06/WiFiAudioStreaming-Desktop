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
 * --------------------------------------------------------------------------
 * CliReceiveCheck.kt
 *
 * Il lato ricezione della CLI: 'wfas rtp ...' e 'wfas snapcast ...'.
 *
 * Si prova quel che si puo' provare senza rete: come vengono lette le righe di
 * comando, come si riconosce una sorgente da come e' scritta, e come si trova
 * il client o il gruppo che l'utente ha nominato. Il resto — socket, mDNS,
 * canale di controllo — ha gia' le sue prove altrove.
 *
 * Gli argomenti sbagliati non si possono provare da qui: il parser esce dal
 * processo, ed e' giusto che lo faccia. Qui si controlla che le righe giuste
 * vengano capite bene, che e' l'altra meta' dello stesso problema.
 */

import java.io.File

private fun args(vararg a: String) = CliArgs.parse(arrayOf(*a))

private val SAMPLE_SDP = """
v=0
o=- 0 0 IN IP4 192.168.1.5
s=Salotto
c=IN IP4 239.255.0.1/4
t=0 0
m=audio 9200 RTP/AVP 96
a=rtpmap:96 L16/44100/2
a=recvonly
""".trimIndent()

fun cliReceiveChecks() {
    println("== wfas rtp: lettura della riga di comando ==")

    args("rtp").let {
        check("'rtp' da solo ascolta", it.rtpCmd is RtpCommand.Listen)
        eq("modo di ricezione RTP", it.runMode, RunMode.CLI_RTP)
        eq("nessuna sorgente indicata", it.rtpSpec, null)
    }
    args("rtp", "listen", "stream.sdp").let {
        check("verbo listen", it.rtpCmd is RtpCommand.Listen)
        eq("sorgente posizionale", it.rtpSpec, "stream.sdp")
    }
    // Senza verbo il primo argomento e' gia' la sorgente: 'wfas rtp x.sdp'.
    args("rtp", "239.255.0.1:9094").let {
        check("verbo sottinteso", it.rtpCmd is RtpCommand.Listen)
        eq("indirizzo come sorgente", it.rtpSpec, "239.255.0.1:9094")
    }
    eq("SDP da standard input", args("rtp", "listen", "-").rtpSpec, "-")
    check("inspect", args("rtp", "inspect", "x.sdp").rtpCmd is RtpCommand.Inspect)
    eq("inspect tiene la sorgente", args("rtp", "inspect", "x.sdp").rtpSpec, "x.sdp")
    check("sdp", args("rtp", "sdp").rtpCmd is RtpCommand.Sdp)
    check("sources", args("rtp", "sources").rtpCmd is RtpCommand.Sources)
    check("alias ls", args("rtp", "ls").rtpCmd is RtpCommand.Sources)
    eq("save con nome", (args("rtp", "save", "casa").rtpCmd as RtpCommand.Save).name, "casa")
    eq("save senza nome", (args("rtp", "save").rtpCmd as RtpCommand.Save).name, null)
    // 'save' prende un nome, non una sorgente: quel che segue non va nel campo
    // sbagliato.
    eq("save non consuma la sorgente", args("rtp", "save", "casa").rtpSpec, null)
    eq("forget per indice", (args("rtp", "forget", "#2").rtpCmd as RtpCommand.Forget).target, "#2")
    eq("forget all", (args("rtp", "forget", "all").rtpCmd as RtpCommand.Forget).target, "all")

    args("rtp", "listen", "--rtp-address", "239.1.2.3", "--rtp-port", "9200",
         "--rtp-codec", "opus", "--rtp-rate", "44100", "--rtp-channels", "1",
         "--rtp-payload", "97", "--rtp-name", "Cucina").let {
        eq("indirizzo", it.rtpAddress, "239.1.2.3")
        eq("porta", it.rtpPort, 9200)
        check("porta scritta a mano", it.rtpPortExplicit)
        eq("codec", it.rtpCodec, "opus")
        eq("frequenza", it.rtpRate, 44100)
        eq("canali", it.rtpChannels, 1)
        eq("payload", it.rtpPayload, 97)
        eq("nome", it.rtpName, "Cucina")
    }
    check("senza --rtp-port la porta non e' esplicita", !args("rtp").rtpPortExplicit)
    eq("--mode rtp equivale al verbo", args("--mode", "rtp").runMode, RunMode.CLI_RTP)

    println("== wfas rtp: riconoscimento dell'indirizzo ==")

    RtpCli.parseEndpoint("239.255.0.1:9094", 9094)!!.let {
        eq("host e porta", it.address, "239.255.0.1")
        eq("porta letta", it.port, 9094)
        check("multicast riconosciuto", it.isMulticast)
    }
    RtpCli.parseEndpoint(":9100", 9094)!!.let {
        eq("solo porta: nessun indirizzo", it.address, "")
        eq("solo porta: porta", it.port, 9100)
        check("solo porta: non e' multicast", !it.isMulticast)
    }
    eq("numero nudo = porta", RtpCli.parseEndpoint("9100", 9094)!!.port, 9100)
    RtpCli.parseEndpoint("[fe80::1]:9000", 9094)!!.let {
        eq("IPv6 fra parentesi", it.address, "fe80::1")
        eq("IPv6: porta fuori dalle parentesi", it.port, 9000)
    }
    // Un IPv6 senza parentesi e' pieno di due punti: spezzarlo sull'ultimo
    // darebbe un indirizzo mutilato e una porta inventata.
    RtpCli.parseEndpoint("ff02::5", 9094)!!.let {
        eq("IPv6 nudo resta intero", it.address, "ff02::5")
        eq("IPv6 nudo: porta di default", it.port, 9094)
        check("IPv6 multicast riconosciuto", it.isMulticast)
    }
    RtpCli.parseEndpoint("salotto.local", 9094)!!.let {
        eq("nome host", it.address, "salotto.local")
        eq("nome host: porta di default", it.port, 9094)
    }

    println("== wfas rtp: sorgenti salvate ==")

    val sources = listOf(
        RtpSource(name = "Salotto", address = "239.255.0.1", port = 9094),
        RtpSource(name = "Cucina",  address = "239.255.0.2", port = 9095),
        RtpSource(name = "Cucina bis", address = "239.255.0.3", port = 9096)
    )
    eq("per indice", RtpCli.savedByRef("#2", sources)?.name, "Cucina")
    eq("per nome esatto", RtpCli.savedByRef("salotto", sources)?.name, "Salotto")
    eq("per indirizzo", RtpCli.savedByRef("239.255.0.3:9096", sources)?.name, "Cucina bis")
    eq("frammento univoco", RtpCli.savedByRef("Salot", sources)?.name, "Salotto")
    // "Cucina" e' anche l'inizio di "Cucina bis": il nome esatto vince, il
    // frammento ambiguo no.
    eq("nome esatto batte il frammento", RtpCli.savedByRef("Cucina", sources)?.name, "Cucina")
    eq("frammento ambiguo non sceglie", RtpCli.savedByRef("cuc", sources), null)
    eq("indice fuori lista", RtpCli.savedByRef("#9", sources), null)
    eq("lista vuota", RtpCli.savedByRef("x", emptyList()), null)

    println("== wfas rtp: dal file SDP alla sorgente ==")

    val sdpFile = File.createTempFile("wfas_check_", ".sdp").apply {
        writeText(SAMPLE_SDP); deleteOnExit()
    }
    when (val r = RtpCli.resolve(args("rtp", "listen", sdpFile.absolutePath))) {
        is RtpCli.Resolution.Ok -> {
            eq("nome dalla sessione", r.source.name, "Salotto")
            eq("indirizzo dal c=", r.source.address, "239.255.0.1")
            eq("porta dal m=audio", r.source.port, 9200)
            eq("codec dal rtpmap", r.source.encoding, "L16")
            eq("frequenza dal rtpmap", r.source.sampleRate, 44100)
            eq("canali dal rtpmap", r.source.channels, 2)
            eq("payload dal m=audio", r.source.payloadType, 96)
            check("percorso nativo per L16", r.source.isNativePcm)
            check("SDP conservato", r.source.sdpText != null)
        }
        is RtpCli.Resolution.Fail -> check("l'SDP di prova si legge", false, r.message)
    }
    // I flag hanno l'ultima parola anche su un SDP: e' quel che permette di
    // riusare lo stesso descrittore su una porta diversa.
    when (val r = RtpCli.resolve(args("rtp", "listen", sdpFile.absolutePath, "--rtp-port", "9300"))) {
        is RtpCli.Resolution.Ok -> eq("il flag sovrascrive l'SDP", r.source.port, 9300)
        is RtpCli.Resolution.Fail -> check("override della porta", false, r.message)
    }
    when (val r = RtpCli.resolve(args("rtp", "listen", "/non/esiste/x.sdp"))) {
        is RtpCli.Resolution.Fail -> check("file inesistente respinto", true)
        is RtpCli.Resolution.Ok -> check("file inesistente respinto", false)
    }

    println("== wfas snapcast: lettura della riga di comando ==")

    args("snapcast").let {
        check("'snapcast' da solo ascolta", it.snapCmd is SnapCommand.Listen)
        eq("modo client Snapcast", it.runMode, RunMode.CLI_SNAPCAST)
        eq("nessun server indicato", it.snapSpec, null)
    }
    eq("alias snap", args("snap").runMode, RunMode.CLI_SNAPCAST)
    args("snapcast", "salotto").let {
        check("server senza verbo", it.snapCmd is SnapCommand.Listen)
        eq("server posizionale", it.snapSpec, "salotto")
    }
    eq("listen con server", args("snapcast", "listen", "192.168.1.10").snapSpec, "192.168.1.10")
    check("discover", args("snapcast", "discover").snapCmd is SnapCommand.Discover)
    check("status", args("snapcast", "status").snapCmd is SnapCommand.Status)
    check("clients", args("snapcast", "clients").snapCmd is SnapCommand.Clients)
    check("groups", args("snapcast", "groups").snapCmd is SnapCommand.Groups)
    check("streams", args("snapcast", "streams").snapCmd is SnapCommand.Streams)
    check("servers", args("snapcast", "servers").snapCmd is SnapCommand.Servers)

    (args("snapcast", "volume", "cucina", "40").snapCmd as SnapCommand.Volume).let {
        eq("volume: client", it.client, "cucina")
        eq("volume: percentuale", it.percent, 40)
    }
    (args("snapcast", "mute", "#2").snapCmd as SnapCommand.Mute).let {
        eq("mute: client", it.client, "#2")
        check("mute mette a true", it.muted)
    }
    check("unmute mette a false", !(args("snapcast", "unmute", "x").snapCmd as SnapCommand.Mute).muted)
    // Una latenza negativa comincia con un trattino: va letta come valore, non
    // scambiata per un'opzione.
    (args("snapcast", "latency", "cucina", "-50").snapCmd as SnapCommand.Latency).let {
        eq("latenza: client", it.client, "cucina")
        eq("latenza negativa", it.ms, -50)
    }
    (args("snapcast", "rename", "#1", "Salotto").snapCmd as SnapCommand.Rename).let {
        eq("rename: client", it.client, "#1")
        eq("rename: nome", it.name, "Salotto")
    }
    (args("snapcast", "group-mute", "Salotto", "on").snapCmd as SnapCommand.GroupMute).let {
        eq("group-mute: gruppo", it.group, "Salotto")
        check("on = muto", it.muted)
    }
    check("off = non muto", !(args("snapcast", "group-mute", "g", "off").snapCmd as SnapCommand.GroupMute).muted)
    (args("snapcast", "group-rename", "g", "Piano di sopra").snapCmd as SnapCommand.GroupRename).let {
        eq("group-rename: nome", it.name, "Piano di sopra")
    }
    (args("snapcast", "group-stream", "g", "default").snapCmd as SnapCommand.GroupStream).let {
        eq("group-stream: stream", it.stream, "default")
    }
    (args("snapcast", "move", "cucina", "Salotto").snapCmd as SnapCommand.Move).let {
        eq("move: client", it.client, "cucina")
        eq("move: gruppo", it.group, "Salotto")
    }
    eq("split", (args("snapcast", "split", "cucina").snapCmd as SnapCommand.Split).client, "cucina")
    eq("forget", (args("snapcast", "forget", "#1").snapCmd as SnapCommand.Forget).target, "#1")
    eq("save con nome", (args("snapcast", "save", "casa").snapCmd as SnapCommand.Save).name, "casa")
    eq("save non consuma il server", args("snapcast", "save", "casa").snapSpec, null)

    args("snapcast", "listen", "--snap-host", "192.168.1.10", "--snap-port", "1800",
         "--snap-control-port", "1801", "--snap-name", "Studio", "--snap-id", "abc").let {
        eq("host", it.snapHost, "192.168.1.10")
        eq("porta audio", it.snapPort, 1800)
        eq("porta di controllo", it.snapControlPortCli, 1801)
        eq("nome annunciato", it.snapClientName, "Studio")
        eq("identificatore", it.snapClientId, "abc")
    }
    eq("--mode snapcast equivale al verbo", args("--mode", "snapcast").runMode, RunMode.CLI_SNAPCAST)
    // --watch fuori da discovery e' un errore, ma sulle viste Snapcast no.
    check("--watch ammesso su status", args("snapcast", "status", "--watch").watch)
    check("--watch ammesso su discover", args("snapcast", "discover", "--watch").watch)

    check("mixer", args("snapcast", "mixer").snapCmd is SnapCommand.Mixer)
    check("alias tui", args("snapcast", "tui").snapCmd is SnapCommand.Mixer)
    check("alias top", args("snapcast", "top").snapCmd is SnapCommand.Mixer)
    eq("mixer accetta il server", args("snapcast", "mixer", "casa").snapSpec, "casa")
    // Il mixer riproduce come l'ascolto: deve passare dalla stessa strada, o
    // si apre una schermata bellissima e non si sente niente.
    eq("il mixer apre una sessione che suona",
        args("snapcast", "mixer").runMode, RunMode.CLI_SNAPCAST)
    check("--no-audio lo rende un telecomando",
        args("snapcast", "mixer", "--no-audio").snapNoAudio)
    check("senza --no-audio si riproduce", !args("snapcast", "mixer").snapNoAudio)

    println("== wfas snapcast: la ricerca deve finire ==")

    // Il ciclo del browser e' tutto I/O bloccante e guardava l'attivita' dello
    // scope che l'aveva lanciato invece della propria: stop() non lo fermava, e
    // chi aspettava la fine della ricerca restava li' per sempre. E' esattamente
    // quel che si vedeva come "wfas snapcast discover non trova niente".
    // Quel che trova qui non conta — dipende da cosa c'e' in rete — conta che
    // torni indietro.
    val t0 = System.currentTimeMillis()
    val returned = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeoutOrNull(8000) { SnapcastCli.discover(400, "Auto") } != null
    }
    val elapsed = System.currentTimeMillis() - t0
    check("la ricerca mDNS finisce da sola", returned, "scaduto il tempo massimo")
    check("e finisce in fretta", elapsed < 6000, "ci ha messo ${elapsed} ms")

    println("== wfas snapcast: ritmo delle domande mDNS ==")

    // Una domanda sola per ricerca era una scommessa: se quel pacchetto o la
    // sua risposta si perdeva — e il multicast si perde — la ricerca tornava
    // vuota e sembrava che in rete non ci fosse nessuno. Con QUERY_INTERVAL_MS
    // a 5 secondi e una finestra di 4, di tentativi ce n'era esattamente uno.
    val asks = ArrayList<Long>()
    val sch = SnapcastQuerySchedule()
    var t = 0L
    while (t <= SnapcastCli.DISCOVER_MS) {
        if (sch.shouldAsk(t)) asks.add(t)
        t += 10
    }
    check("la prima domanda parte subito", asks.firstOrNull() == 0L, "asks=$asks")
    check("dentro la finestra di ricerca si riprova piu' volte", asks.size >= 5, "asks=$asks")
    check("i primi tentativi sono ravvicinati", (asks.getOrNull(1) ?: 9999L) <= 300L, "asks=$asks")

    // E poi si dirada, o si finirebbe a martellare la rete per sempre.
    val gaps = ArrayList<Long>()
    val steady = SnapcastQuerySchedule(firstMs = 250L, steadyMs = 1000L)
    var u = 0L
    var last = -1L
    while (u <= 20_000L) {
        if (steady.shouldAsk(u)) { if (last >= 0) gaps.add(u - last); last = u }
        u += 10
    }
    check("il ritmo si stabilizza sul valore di mantenimento",
        gaps.takeLast(3).isNotEmpty() && gaps.takeLast(3).all { it == 1000L }, "gaps=$gaps")
    check("e cresce senza salti indietro",
        gaps.zipWithNext().all { (a, b) -> b >= a }, "gaps=$gaps")

    // Una parola qualunque e' un nome host valido: e' per questo che serve il
    // suggerimento sui verbi, non un rifiuto in fase di lettura.
    eq("una parola nuda e' un nome host", SnapcastCli.parseHost("mixer")?.host, "mixer")
    check("i verbi noti comprendono quelli piu' facili da sbagliare",
        listOf("mixer", "status", "clients", "discover").all { it in SnapcastCli.VERBS })

    println("== wfas snapcast: riconoscimento del server ==")

    SnapcastCli.parseHost("192.168.1.10")!!.let {
        eq("host semplice", it.host, "192.168.1.10")
        eq("porta audio di default", it.streamPort, 1704)
        eq("porta di controllo di default", it.controlPort, 1705)
    }
    // Fuori dalla porta standard la convenzione e' "una piu' avanti", ed e'
    // meglio di un 1705 fisso che non c'entrerebbe niente.
    SnapcastCli.parseHost("192.168.1.10:1800")!!.let {
        eq("porta audio scelta", it.streamPort, 1800)
        eq("controllo dedotto", it.controlPort, 1801)
    }
    SnapcastCli.parseHost("[fe80::1]:1704")!!.let {
        eq("IPv6 fra parentesi", it.host, "fe80::1")
        eq("IPv6: porta standard", it.streamPort, 1704)
        eq("IPv6: controllo standard", it.controlPort, 1705)
    }
    eq("IPv6 nudo resta intero", SnapcastCli.parseHost("fe80::1")!!.host, "fe80::1")

    val servers = listOf(
        SnapcastServerRef(name = "Casa", host = "192.168.1.10"),
        SnapcastServerRef(name = "Ufficio", host = "192.168.1.20")
    )
    eq("server per indice", SnapcastCli.savedByRef("#2", servers)?.name, "Ufficio")
    eq("server per nome", SnapcastCli.savedByRef("casa", servers)?.name, "Casa")
    eq("server per host", SnapcastCli.savedByRef("192.168.1.20", servers)?.name, "Ufficio")
    eq("server sconosciuto", SnapcastCli.savedByRef("boh", servers), null)

    println("== wfas snapcast: chi e' il client, chi e' il gruppo ==")

    fun cl(id: String, name: String, ip: String, vol: Int = 50) = SnapClientInfo(
        id = id, name = name, hostName = name.lowercase(), ip = ip,
        connected = true, volumePercent = vol, muted = false, latencyMs = 0
    )
    val salotto = cl("aa:bb", "Salotto", "192.168.1.31")
    val cucina  = cl("cc:dd", "Cucina", "192.168.1.32")
    val cucina2 = cl("ee:ff", "Cucina bis", "192.168.1.33")
    val status = SnapServerStatus(
        groups = listOf(
            SnapGroupInfo("g1", "Piano terra", false, "default", listOf(salotto, cucina)),
            SnapGroupInfo("g2", "", false, "default", listOf(cucina2))
        ),
        streams = listOf(SnapStreamInfo("default", "playing"))
    )

    eq("client per indice", SnapcastCli.findClient(status, "#2")?.id, "cc:dd")
    eq("l'indice attraversa i gruppi", SnapcastCli.findClient(status, "#3")?.id, "ee:ff")
    eq("client per id", SnapcastCli.findClient(status, "aa:bb")?.name, "Salotto")
    eq("client per indirizzo", SnapcastCli.findClient(status, "192.168.1.33")?.id, "ee:ff")
    eq("client per nome esatto", SnapcastCli.findClient(status, "cucina")?.id, "cc:dd")
    eq("frammento univoco", SnapcastCli.findClient(status, "salot")?.id, "aa:bb")
    eq("frammento ambiguo non sceglie", SnapcastCli.findClient(status, "cuci"), null)
    eq("client inesistente", SnapcastCli.findClient(status, "garage"), null)

    eq("gruppo per indice", SnapcastCli.findGroup(status, "#1")?.id, "g1")
    eq("gruppo per id", SnapcastCli.findGroup(status, "g2")?.id, "g2")
    eq("gruppo per nome", SnapcastCli.findGroup(status, "Piano terra")?.id, "g1")
    eq("gruppo per frammento", SnapcastCli.findGroup(status, "terra")?.id, "g1")
    // Un gruppo senza nome si presenta con quello dei suoi client: quel nome
    // deve restare cercabile, o il gruppo diventa irraggiungibile.
    eq("gruppo senza nome trovato dal client", SnapcastCli.findGroup(status, "Cucina bis")?.id, "g2")
    eq("gruppo inesistente", SnapcastCli.findGroup(status, "cantina"), null)
}
