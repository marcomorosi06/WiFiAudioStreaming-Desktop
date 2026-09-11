import java.io.ByteArrayOutputStream

/*
 * Controlli sul client Snapcast: ricerca mDNS, canale di controllo e
 * sincronizzazione dell'orologio.
 *
 * Sono tutte parti che si possono provare senza un server vero: il parsing dei
 * pacchetti DNS e del JSON e' logica pura, e la stima dello scarto fra orologi
 * si verifica simulando ritardi di rete noti — compreso il caso che conta, in
 * cui i ritardi sono asimmetrici e la media sbaglierebbe.
 */

private var snapFailures = 0

private fun snapCheck(n: String, c: Boolean, d: String = "") {
    if (c) println("  ok   $n")
    else {
        println("  FAIL $n ${if (d.isNotBlank()) "-> $d" else ""}")
        snapFailures++
    }
}

private fun snapEq(n: String, a: Any?, e: Any?) = snapCheck(n, a == e, "actual=$a expected=$e")

/** Costruisce una risposta mDNS realistica, con compressione dei nomi. */
private fun buildSnapMdnsResponse(): Pair<ByteArray, Int> {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 4, 0, 0, 0, 0))  // 4 answers
    val body = ByteArrayOutputStream()

    val svcName = "_snapcast._tcp.local"
    val instance = "salotto.$svcName"
    val host = "snapbox.local"

    // offset del nome del servizio nel messaggio finale: header 12 byte
    val svcOffset = 12
    fun ptrTo(off: Int) = byteArrayOf((0xC0 or (off shr 8)).toByte(), (off and 0xFF).toByte())

    // 1) PTR: _snapcast._tcp.local -> salotto._snapcast._tcp.local
    body.write(SnapcastDns.encodeName(svcName))
    body.write(byteArrayOf(0, 12, 0, 1, 0, 0, 0x11, 0x94.toByte()))
    val instEnc = ByteArrayOutputStream().apply {
        write(7); write("salotto".toByteArray())
        write(ptrTo(svcOffset))            // resto compresso
    }.toByteArray()
    body.write(byteArrayOf(0, instEnc.size.toByte()))
    body.write(instEnc)
    val instanceOffset = svcOffset + SnapcastDns.encodeName(svcName).size + 8 + 2

    // 2) SRV per l'istanza (nome compresso), porta 1704, target snapbox.local
    body.write(ptrTo(instanceOffset))
    body.write(byteArrayOf(0, 33, 0, 1, 0, 0, 0x11, 0x94.toByte()))
    val srvRd = ByteArrayOutputStream().apply {
        write(byteArrayOf(0, 0, 0, 0))                 // priority, weight
        write(byteArrayOf(0x06, 0xA8.toByte()))        // porta 1704
        write(SnapcastDns.encodeName(host))
    }.toByteArray()
    body.write(byteArrayOf(0, srvRd.size.toByte()))
    body.write(srvRd)
    val hostOffset = instanceOffset + 2 + 8 + 2 + 6

    // 3) TXT
    body.write(ptrTo(instanceOffset))
    body.write(byteArrayOf(0, 16, 0, 1, 0, 0, 0x11, 0x94.toByte()))
    val txt = ByteArrayOutputStream().apply {
        val e = "version=0.27.0".toByteArray(); write(e.size); write(e)
    }.toByteArray()
    body.write(byteArrayOf(0, txt.size.toByte()))
    body.write(txt)

    // 4) A per snapbox.local (nome compresso al target dell'SRV)
    body.write(ptrTo(hostOffset))
    body.write(byteArrayOf(0, 1, 0, 1, 0, 0, 0, 0x78))
    body.write(byteArrayOf(0, 4))
    body.write(byteArrayOf(192.toByte(), 168.toByte(), 1, 42))

    out.write(body.toByteArray())
    val arr = out.toByteArray()
    return arr to arr.size
}

// Risposta reale di Server.GetStatus di snapserver (struttura 0.2x).
private const val STATUS = """
{"id":1,"jsonrpc":"2.0","result":{"server":{
  "groups":[
    {"clients":[
      {"config":{"instance":1,"latency":10,"name":"Cucina","volume":{"muted":false,"percent":74}},
       "connected":true,
       "host":{"arch":"x86_64","ip":"192.168.1.31","mac":"aa:bb:cc:dd:ee:01","name":"cucina-pi","os":"Raspbian"},
       "id":"aa:bb:cc:dd:ee:01",
       "lastSeen":{"sec":1788900000,"usec":123},
       "snapclient":{"name":"Snapclient","protocolVersion":2,"version":"0.27.0"}},
      {"config":{"instance":1,"latency":0,"name":"","volume":{"muted":true,"percent":30}},
       "connected":false,
       "host":{"arch":"aarch64","ip":"192.168.1.32","mac":"aa:bb:cc:dd:ee:02","name":"bagno","os":"Android"},
       "id":"aa:bb:cc:dd:ee:02",
       "lastSeen":{"sec":1788899000,"usec":0},
       "snapclient":{"name":"Snapclient","protocolVersion":2,"version":"0.26.0"}}],
     "id":"g-1","muted":false,"name":"Piano terra","stream_id":"Spotify"},
    {"clients":[],"id":"g-2","muted":true,"name":"","stream_id":"default"}],
  "server":{"host":{"arch":"x86_64","ip":"","mac":"","name":"snapbox","os":"Debian"},
            "snapserver":{"controlProtocolVersion":1,"name":"Snapserver","protocolVersion":1,"version":"0.27.0"}},
  "streams":[
    {"id":"Spotify","status":"playing","uri":{"raw":"pipe:///tmp/snapfifo?name=Spotify"}},
    {"id":"default","status":"idle","uri":{"raw":"pipe:///tmp/default"}}]}}}
"""

/** Simula uno scambio TIME con ritardi di rete dati, in microsecondi. */
private fun exchange(sync: SnapcastClockSync, c1: Long, trueOffset: Long, d1: Long, d2: Long) {
    val s1 = c1 + d1 + trueOffset          // arrivo al server, orologio server
    val serverLatency = s1 - c1            // quello che il server mette nel payload
    val s2 = s1                            // risposta immediata
    val c2 = c1 + d1 + d2                  // ritorno da noi, orologio locale
    sync.update(c1, serverLatency, s2, c2)
}


// Ricostruisce esattamente quello che si e' visto sulla rete reale.
private fun snapRealWorldRecords(): SnapcastDnsRecords = SnapcastDnsRecords(
    pointers = listOf(
        "Pixel 9 Pro._snapcast._tcp.local",
        "Pixel 9 Pro._snapcast-ctrl._tcp.local"
    ),
    services = mapOf(
        // Rumore: KDE Connect vive sullo stesso canale multicast.
        "d64c7c3ef3d245ea99c27e6422b49c78._kdeconnect._udp.local" to (1716 to "pixel.local"),
        "dda2a088f63e481ea1a2d7fa3f3b6fa5._kdeconnect._udp.local" to (1716 to "altro.local"),
        // Il vero server, annunciato come due servizi distinti.
        "Pixel 9 Pro._snapcast._tcp.local"      to (1704 to "pixel.local"),
        "Pixel 9 Pro._snapcast-ctrl._tcp.local" to (1705 to "pixel.local")
    ),
    addresses = mapOf(
        "pixel.local" to "192.168.1.152",
        "altro.local" to "192.168.1.205"
    )
)

private fun snapPresentRecords() = SnapcastDnsRecords(
    services = mapOf(
        "Salotto._snapcast._tcp.local"      to (1704 to "box.local"),
        "Salotto._snapcast-ctrl._tcp.local" to (1705 to "box.local")),
    addresses = mapOf("box.local" to "192.168.1.9"))

/** Pacchetto DNS con un record SRV a TTL 0: l'addio dell'mDNS. */
private fun snapGoodbyePacket(): Pair<ByteArray, Int> {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0))
    out.write(SnapcastDns.encodeName("Salotto._snapcast._tcp.local"))
    out.write(byteArrayOf(0, 33, 0, 1))          // SRV, IN
    out.write(byteArrayOf(0, 0, 0, 0))           // TTL = 0  ← l'addio
    val rd = ByteArrayOutputStream().apply {
        write(byteArrayOf(0, 0, 0, 0)); write(byteArrayOf(0x06, 0xA8.toByte()))
        write(SnapcastDns.encodeName("box.local"))
    }.toByteArray()
    out.write(byteArrayOf(0, rd.size.toByte())); out.write(rd)
    val a = out.toByteArray()
    return a to a.size
}

fun snapcastClientChecks() {

    println("== snapcast client: mdns ==")

    val q = SnapcastDns.buildQuery()
    snapCheck("query: header di interrogazione", q[2].toInt() == 0 && q[5].toInt() == 1)
    snapCheck("query: chiede un PTR", q[q.size - 4].toInt() == 0 && q[q.size - 3].toInt() == 12)
    snapCheck("query: contiene il tipo di servizio",
        String(q, 13, 9) == "_snapcast", String(q, 13, 9))

    val (data, len) = buildSnapMdnsResponse()
    val r = SnapcastDns.parseResponse(data, len)

    snapCheck("PTR letto", r.pointers.any { it.startsWith("salotto") }, r.pointers.toString())
    snapEq("istanza in chiaro", SnapcastDns.instanceLabel(r.pointers.first()), "salotto")
    snapCheck("SRV: porta e host", r.services.values.any { it.first == 1704 && it.second == "snapbox.local" },
        r.services.toString())
    snapCheck("A: indirizzo risolto", r.addresses.values.contains("192.168.1.42"), r.addresses.toString())
    snapCheck("TXT letto", r.texts.values.flatten().any { it.startsWith("version=") }, r.texts.toString())

    // il nome dell'SRV deve essere quello completo dell'istanza, non "salotto"
    snapCheck("SRV indicizzato per istanza completa",
        r.services.keys.any { it == "salotto._snapcast._tcp.local" }, r.services.keys.toString())

    // robustezza: niente eccezioni su spazzatura
    snapCheck("pacchetto troncato non esplode",
        SnapcastDns.parseResponse(data, 20).let { true })
    snapCheck("pacchetto vuoto non esplode",
        SnapcastDns.parseResponse(ByteArray(0), 0).pointers.isEmpty())
    snapCheck("lunghezza incoerente non esplode",
        SnapcastDns.parseResponse(ByteArray(12) { 0xFF.toByte() }, 12).let { true })

    // cicli di puntatori: non deve andare in loop
    val loop = ByteArray(16)
    loop[12] = 0xC0.toByte(); loop[13] = 12
    snapCheck("ciclo di puntatori interrotto", SnapcastDns.readName(loop, 12, 16) == null)

    snapEq("etichetta con spazio codificato",
        SnapcastDns.instanceLabel("sala\\032prove._snapcast._tcp.local"), "sala prove")

    println("== snapcast client: control ==")
    val st = SnapcastControlModel.parseStatus(SnapJson.parse(STATUS))
    snapCheck("stato letto", st != null)
    st!!
    snapEq("gruppi", st.groups.size, 2)
    snapEq("nome server", st.serverName, "snapbox")
    snapEq("versione server", st.serverVersion, "0.27.0")
    snapEq("stream", st.streams.size, 2)
    snapEq("stream in riproduzione", st.streams.first { it.id == "Spotify" }.status, "playing")
    snapEq("uri dello stream", st.streams.first { it.id == "Spotify" }.uri, "pipe:///tmp/snapfifo?name=Spotify")

    val g1 = st.groups.first { it.id == "g-1" }
    snapEq("nome gruppo", g1.name, "Piano terra")
    snapEq("stream del gruppo", g1.streamId, "Spotify")
    snapEq("client nel gruppo", g1.clients.size, 2)
    snapCheck("gruppo non mutato", !g1.muted)
    snapCheck("secondo gruppo mutato", st.groups.first { it.id == "g-2" }.muted)
    snapEq("gruppo senza nome usa un ripiego",
        st.groups.first { it.id == "g-2" }.displayName().isNotBlank(), true)

    val cucina = st.client("aa:bb:cc:dd:ee:01")!!
    snapEq("nome client", cucina.displayName(), "Cucina")
    snapEq("volume", cucina.volumePercent, 74)
    snapCheck("non mutato", !cucina.muted)
    snapEq("latenza", cucina.latencyMs, 10)
    snapCheck("connesso", cucina.connected)
    snapEq("ip", cucina.ip, "192.168.1.31")
    snapEq("os", cucina.os, "Raspbian")

    val bagno = st.client("aa:bb:cc:dd:ee:02")!!
    snapEq("client senza nome ripiega sull'host", bagno.displayName(), "bagno")
    snapCheck("mutato", bagno.muted)
    snapCheck("non connesso", !bagno.connected)

    // ── Notifiche ─────────────────────────────────────────────────────────
    fun notify(m: String, p: String) =
        SnapcastControlModel.applyNotification(st, m, SnapJson.parse(p).field("params"))

    notify("Client.OnVolumeChanged",
        """{"params":{"id":"aa:bb:cc:dd:ee:01","volume":{"muted":false,"percent":15}}}""").let {
        snapCheck("volume aggiornato da notifica", it != null)
        snapEq("nuovo volume", it?.client("aa:bb:cc:dd:ee:01")?.volumePercent, 15)
        snapEq("gli altri non si toccano", it?.client("aa:bb:cc:dd:ee:02")?.volumePercent, 30)
    }

    notify("Client.OnNameChanged",
        """{"params":{"id":"aa:bb:cc:dd:ee:02","name":"Bagno grande"}}""").let {
        snapEq("nome aggiornato", it?.client("aa:bb:cc:dd:ee:02")?.displayName(), "Bagno grande")
    }

    notify("Client.OnLatencyChanged",
        """{"params":{"id":"aa:bb:cc:dd:ee:01","latency":42}}""").let {
        snapEq("latenza aggiornata", it?.client("aa:bb:cc:dd:ee:01")?.latencyMs, 42)
    }

    notify("Group.OnMute", """{"params":{"id":"g-1","mute":true}}""").let {
        snapEq("gruppo mutato da notifica", it?.groups?.first { g -> g.id == "g-1" }?.muted, true)
    }

    notify("Group.OnStreamChanged", """{"params":{"id":"g-1","stream_id":"default"}}""").let {
        snapEq("stream del gruppo cambiato", it?.groups?.first { g -> g.id == "g-1" }?.streamId, "default")
    }

    notify("Stream.OnUpdate",
        """{"params":{"id":"Spotify","stream":{"id":"Spotify","status":"idle"}}}""").let {
        snapEq("stato stream aggiornato", it?.streams?.first { s -> s.id == "Spotify" }?.status, "idle")
    }

    snapCheck("notifica sconosciuta ignorata", notify("Pippo.OnBoh", """{"params":{}}""") == null)
    snapCheck("notifica senza id ignorata",
        notify("Client.OnVolumeChanged", """{"params":{"volume":{"percent":1}}}""") == null)

    // Server.OnUpdate porta lo stato intero
    val full = SnapcastControlModel.parseStatus(
        SnapJson.parse("""{"method":"Server.OnUpdate","params":{"server":{"groups":[],"streams":[],"server":{"host":{"name":"x"},"snapserver":{"version":"9"}}}}}"""))
    snapEq("Server.OnUpdate letto come stato intero", full?.serverName, "x")

    snapCheck("json spazzatura non esplode", SnapcastControlModel.parseStatus(SnapJson.parse("{{{")) == null)
    snapCheck("json senza server", SnapcastControlModel.parseStatus(SnapJson.parse("""{"result":{}}""")) == null)

    println("== snapcast client: clock + codec ==")

    // ── Sincronizzazione ──────────────────────────────────────────────────
    val trueOffset = 5_000_000L            // il server e' avanti di 5 secondi
    val sync = SnapcastClockSync()
    exchange(sync, 1_000_000L, trueOffset, 2_000, 2_000)
    snapCheck("una misura simmetrica basta",
        Math.abs(sync.offsetMicros - trueOffset) < 100.0,
        "offset=${sync.offsetMicros} vero=$trueOffset")

    // Misure sporche: ritardi grandi e asimmetrici, piu' una buona in mezzo.
    val sync2 = SnapcastClockSync()
    exchange(sync2, 1_000_000L, trueOffset, 40_000, 3_000)     // andata lenta
    exchange(sync2, 2_000_000L, trueOffset, 3_000, 55_000)     // ritorno lento
    exchange(sync2, 3_000_000L, trueOffset, 1_200, 1_100)      // buona
    exchange(sync2, 4_000_000L, trueOffset, 80_000, 2_000)     // pessima
    snapCheck("il minimo andata-ritorno scarta le misure sporche",
        Math.abs(sync2.offsetMicros - trueOffset) < 200.0,
        "offset=${sync2.offsetMicros} vero=$trueOffset")

    val naiveAvg = listOf(
        (40_000L + trueOffset - (3_000L - trueOffset)) / 2.0,
        (3_000L + trueOffset - (55_000L - trueOffset)) / 2.0,
        (1_200L + trueOffset - (1_100L - trueOffset)) / 2.0,
        (80_000L + trueOffset - (2_000L - trueOffset)) / 2.0
    ).average()
    snapCheck("...e fa meglio della media semplice",
        Math.abs(sync2.offsetMicros - trueOffset) < Math.abs(naiveAvg - trueOffset),
        "min=${Math.abs(sync2.offsetMicros - trueOffset)} media=${Math.abs(naiveAvg - trueOffset)}")

    snapEq("conversione server -> locale", sync.serverToLocal(10_000_000L), 10_000_000L - trueOffset)
    sync.reset()
    snapEq("reset azzera", sync.samplesSeen, 0)

    // La finestra scorre: misure vecchie non restano per sempre
    val sync3 = SnapcastClockSync(window = 3)
    exchange(sync3, 1_000_000L, trueOffset, 500, 500)          // ottima ma vecchia
    repeat(5) { i -> exchange(sync3, (2_000_000L + i * 1_000_000L), trueOffset + 1_000_000L, 9_000, 9_000) }
    snapCheck("la finestra dimentica le misure vecchie",
        Math.abs(sync3.offsetMicros - (trueOffset + 1_000_000L)) < 1000.0,
        "offset=${sync3.offsetMicros}")

    // ── Codec header PCM ──────────────────────────────────────────────────
    val wav = SnapcastWire.wavHeader(48000, 16, 2)
    val dec = SnapcastPcmDecoder.fromWavHeader(wav)
    snapCheck("header WAV riconosciuto", dec != null)
    snapEq("frequenza", dec?.sampleRate, 48000)
    snapEq("canali", dec?.channels, 2)

    val wav44 = SnapcastWire.wavHeader(44100, 16, 1)
    SnapcastPcmDecoder.fromWavHeader(wav44).let {
        snapEq("mono 44100: frequenza", it?.sampleRate, 44100)
        snapEq("mono 44100: canali", it?.channels, 1)
    }

    snapCheck("header troppo corto rifiutato", SnapcastPcmDecoder.fromWavHeader(ByteArray(8)) == null)
    snapCheck("spazzatura rifiutata", SnapcastPcmDecoder.fromWavHeader(ByteArray(64) { 0x7F }) == null)

    // 16 bit: passa senza toccare nulla
    val pcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    val out = dec!!.decode(pcm, 0, pcm.size)
    snapCheck("PCM 16 bit passa intatto", out != null && out.contentEquals(pcm),
        out?.joinToString(",") ?: "null")
    val partial = dec.decode(pcm, 4, 4)
    snapCheck("offset e lunghezza rispettati",
        partial != null && partial.contentEquals(byteArrayOf(5, 6, 7, 8)))

    // 24 bit -> 16
    val d24 = SnapcastPcmDecoder(48000, 2, 24)
    val src24 = byteArrayOf(0x00, 0x34, 0x12, 0x00, 0x78, 0x56)   // due campioni
    d24.decode(src24, 0, 6).let {
        snapCheck("24 bit ridotto a 16", it != null && it.size == 4, "size=${it?.size}")
        snapCheck("24 bit: campioni giusti",
            it != null && it[0] == 0x34.toByte() && it[1] == 0x12.toByte() &&
                          it[2] == 0x78.toByte() && it[3] == 0x56.toByte(),
            it?.joinToString(",") ?: "null")
    }

    // ── SnapcastServerRef ─────────────────────────────────────────────────
    val ref = SnapcastServerRef("Sala|prove", "192.168.1.9", 1704, 1705, discovered = false)
    SnapcastServerRef.deserialize(ref.serialize()).let {
        snapCheck("server manuale rileggibile", it != null)
        snapEq("barra neutralizzata nel nome", it?.name, "Sala/prove")
        snapEq("host", it?.host, "192.168.1.9")
        snapEq("porta controllo", it?.controlPort, 1705)
        snapCheck("riletto come manuale", it?.discovered == false)
    }
    snapCheck("riga corrotta -> null", SnapcastServerRef.deserialize("boh") == null)
    snapEq("nome vuoto ripiega sull'host",
        SnapcastServerRef("", "10.0.0.5").displayName(), "10.0.0.5")



    println("== snapcast client: service index ==")
    val idx = SnapcastServiceIndex()
    idx.ingest(snapRealWorldRecords())
    val servers = idx.servers()

    snapEq("un solo server, non quattro", servers.size, 1)
    val s = servers.first()
    snapEq("nome pulito", s.name, "Pixel 9 Pro")
    snapEq("indirizzo", s.host, "192.168.1.152")
    snapEq("porta audio", s.streamPort, 1704)
    snapEq("porta di controllo dal suo annuncio", s.controlPort, 1705)
    snapCheck("marcato come trovato in rete", s.discovered)

    snapCheck("nessun kdeconnect fra i risultati",
        servers.none { it.streamPort == 1716 }, servers.toString())
    snapCheck("nessun host estraneo",
        servers.none { it.host == "192.168.1.205" }, servers.toString())

    // Solo il servizio audio: la porta di controllo si deduce per convenzione
    val idx2 = SnapcastServiceIndex()
    idx2.ingest(SnapcastDnsRecords(
        services = mapOf("Salotto._snapcast._tcp.local" to (1704 to "box.local")),
        addresses = mapOf("box.local" to "10.0.0.7")))
    idx2.servers().first().let {
        snapEq("senza annuncio del controllo: convenzione 1705", it.controlPort, 1705)
        snapEq("nome", it.name, "Salotto")
    }

    // Porte non standard: la convenzione e' "una piu' avanti"
    val idx3 = SnapcastServiceIndex()
    idx3.ingest(SnapcastDnsRecords(
        services = mapOf("Studio._snapcast._tcp.local" to (9000 to "s.local")),
        addresses = mapOf("s.local" to "10.0.0.8")))
    snapEq("porta non standard: controllo a +1", idx3.servers().first().controlPort, 9001)

    // Solo il servizio di controllo: senza audio non c'e' niente da ascoltare
    val idx4 = SnapcastServiceIndex()
    idx4.ingest(SnapcastDnsRecords(
        services = mapOf("Solo._snapcast-ctrl._tcp.local" to (1705 to "c.local")),
        addresses = mapOf("c.local" to "10.0.0.9")))
    snapEq("solo controllo: nessun server", idx4.servers().size, 0)

    // I record arrivano in pacchetti separati, come succede davvero
    val idx5 = SnapcastServiceIndex()
    idx5.ingest(SnapcastDnsRecords(services = mapOf("A._snapcast._tcp.local" to (1704 to "a.local"))))
    snapEq("SRV senza indirizzo: ancora niente", idx5.servers().size, 0)
    idx5.ingest(SnapcastDnsRecords(addresses = mapOf("a.local" to "10.0.0.10")))
    snapEq("arriva il record A: ora c'e'", idx5.servers().size, 1)
    idx5.ingest(SnapcastDnsRecords(services = mapOf("A._snapcast-ctrl._tcp.local" to (1705 to "a.local"))))
    snapEq("arriva il controllo: si fonde, non si duplica", idx5.servers().size, 1)
    snapEq("...e porta la sua porta", idx5.servers().first().controlPort, 1705)

    // Due server veri restano due
    val idx6 = SnapcastServiceIndex()
    idx6.ingest(SnapcastDnsRecords(
        services = mapOf(
            "Uno._snapcast._tcp.local" to (1704 to "u.local"),
            "Due._snapcast._tcp.local" to (1704 to "d.local")),
        addresses = mapOf("u.local" to "10.0.0.1", "d.local" to "10.0.0.2")))
    snapEq("due server distinti", idx6.servers().size, 2)
    snapEq("ordinati per nome", idx6.servers().map { it.name }, listOf("Due", "Uno"))

    idx6.clear()
    snapEq("clear svuota", idx6.servers().size, 0)

    // Etichette
    snapEq("etichetta dal servizio audio",
        SnapcastDns.instanceLabel("Salotto._snapcast._tcp.local"), "Salotto")
    snapEq("etichetta dal servizio di controllo",
        SnapcastDns.instanceLabel("Salotto._snapcast-ctrl._tcp.local"), "Salotto")
    snapCheck("belongsTo distingue i due tipi",
        SnapcastDns.belongsTo("X._snapcast._tcp.local", SnapcastDns.SERVICE_TYPE) &&
        !SnapcastDns.belongsTo("X._snapcast-ctrl._tcp.local", SnapcastDns.SERVICE_TYPE) &&
        SnapcastDns.belongsTo("X._snapcast-ctrl._tcp.local", SnapcastDns.CONTROL_SERVICE_TYPE))
    snapCheck("kdeconnect non appartiene a nessuno dei due",
        !SnapcastDns.belongsTo("x._kdeconnect._udp.local", SnapcastDns.SERVICE_TYPE) &&
        !SnapcastDns.belongsTo("x._kdeconnect._udp.local", SnapcastDns.CONTROL_SERVICE_TYPE))



    println("== snapcast client: expiry + goodbye ==")

    // 1. Scadenza: un server che smette di rispondere se ne deve andare
    val gIdx = SnapcastServiceIndex(ttlMs = 20_000L)
    val gT0 = 1_000_000L
    gIdx.ingest(snapPresentRecords(), gT0)
    snapEq("prima c'e'", gIdx.servers().size, 1)

    snapCheck("a 10 s non scade ancora", !gIdx.expire(gT0 + 10_000))
    snapEq("...ed e' ancora in lista", gIdx.servers().size, 1)

    snapCheck("a 25 s scade", gIdx.expire(gT0 + 25_000))
    snapEq("il fantasma sparisce", gIdx.servers().size, 0)

    // 2. Chi continua a rispondere resta
    val gIdx2 = SnapcastServiceIndex(ttlMs = 20_000L)
    gIdx2.ingest(snapPresentRecords(), gT0)
    gIdx2.ingest(snapPresentRecords(), gT0 + 15_000)          // si e' rifatto vivo
    snapCheck("chi risponde non scade", !gIdx2.expire(gT0 + 25_000))
    snapEq("resta in lista", gIdx2.servers().size, 1)

    // 3. Addio esplicito: sparisce subito, senza aspettare la scadenza
    val gIdx3 = SnapcastServiceIndex()
    gIdx3.ingest(snapPresentRecords(), gT0)
    snapEq("prima dell'addio", gIdx3.servers().size, 1)
    val (byeData, byeLen) = snapGoodbyePacket()
    val rec = SnapcastDns.parseResponse(byeData, byeLen)
    snapCheck("il TTL 0 e' letto come addio",
        "Salotto._snapcast._tcp.local" in rec.goodbyes, rec.goodbyes.toString())
    snapCheck("un addio non registra il servizio",
        rec.services.isEmpty(), rec.services.toString())
    gIdx3.ingest(rec, gT0 + 100)
    snapEq("dopo l'addio sparisce subito", gIdx3.servers().size, 0)

    // 4. Il server torna: deve ricomparire
    gIdx3.ingest(snapPresentRecords(), gT0 + 200)
    snapEq("se torna, ricompare", gIdx3.servers().size, 1)

    // 5. Scade il solo record A: senza indirizzo non si puo' mostrare nulla
    val gIdx4 = SnapcastServiceIndex(ttlMs = 20_000L)
    gIdx4.ingest(snapPresentRecords(), gT0)
    gIdx4.ingest(SnapcastDnsRecords(services = snapPresentRecords().services), gT0 + 15_000)  // solo SRV
    snapCheck("scade l'indirizzo", gIdx4.expire(gT0 + 25_000))
    snapEq("senza indirizzo non compare", gIdx4.servers().size, 0)

    // 6. expire su indice vuoto non inventa cambiamenti
    snapCheck("niente da togliere: nessun cambiamento", !SnapcastServiceIndex().expire(gT0))

    println("== snapcast client: volume curve ==")
    // Il volume del client lo decide il server: se il client lo mostra e basta,
    // il cursore si muove e il suono resta identico. Qui si verifica la curva.
    val gain = { p: Int -> SnapcastStreamClient.gainFor(p) }

    snapCheck("100% lascia il segnale intatto", gain(100) == 1.0f, "${gain(100)}")
    snapCheck("0% azzera", gain(0) == 0.0f, "${gain(0)}")
    snapCheck("la curva e' monotona", (0..99).all { gain(it) < gain(it + 1) })
    snapCheck("50% e' molto meno di meta' potenza (curva cubica come snapclient)",
        gain(50) in 0.11f..0.14f, "${gain(50)}")
    snapCheck("valori fuori scala limitati",
        gain(-30) == 0.0f && gain(400) == 1.0f, "${gain(-30)} / ${gain(400)}")
    snapCheck("a guadagno pieno nessun campione cambia",
        listOf(Short.MIN_VALUE, Short.MAX_VALUE, 0, 12345).all {
            (it.toShort() * gain(100)).toInt() == it.toInt()
        })

    if (snapFailures > 0) {
        println("  $snapFailures controlli falliti")
        kotlin.system.exitProcess(1)
    }
}
