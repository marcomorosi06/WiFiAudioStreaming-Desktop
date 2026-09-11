import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/*
 * Controlli sul percorso nativo L16 del ricevitore RTP, con pacchetti veri su
 * un socket UDP di loopback.
 *
 * Sono i casi che si sentono come click o scatti quando vanno storti: byte swap
 * sbagliato, payload non allineato al frame troncato invece che riportato al
 * pacchetto dopo (sfasa L/R da li' in poi), buco non mascherato o mascherato
 * per la durata sbagliata, e mittente riavviato che resta muto per sempre.
 */

private var rtpRxFailures = 0

private fun rxCheck(n: String, c: Boolean, d: String = "") {
    if (c) println("  ok   $n")
    else {
        println("  FAIL $n ${if (d.isNotBlank()) "-> $d" else ""}")
        rtpRxFailures++
    }
}

private class FakeSink : PcmPlaybackSink {
    val pcm = java.io.ByteArrayOutputStream()
    var concealCalls = 0
    var concealBytes = 0
    override fun submit(pcmLittleEndian: ByteArray) { synchronized(pcm) { pcm.write(pcmLittleEndian) } }
    override fun conceal(approxBytes: Int) { concealCalls++; concealBytes += approxBytes }
    override fun bufferedMs(): Int = 120
    override fun close() {}
}

/** Costruisce un pacchetto RTP L16 con seq e payload big endian dati. */
private fun rtp(seq: Int, pt: Int, payloadBE: ByteArray, ts: Long = -1L): ByteArray {
    val p = ByteArray(12 + payloadBE.size)
    p[0] = 0x80.toByte(); p[1] = pt.toByte()
    p[2] = ((seq shr 8) and 0xFF).toByte(); p[3] = (seq and 0xFF).toByte()
    // Timestamp in campioni. Se non specificato si deriva dalla sequenza
    // assumendo 2 frame per pacchetto, come nei casi qui sotto.
    val t = if (ts >= 0) ts else (seq.toLong() * 2)
    p[4] = ((t shr 24) and 0xFF).toByte(); p[5] = ((t shr 16) and 0xFF).toByte()
    p[6] = ((t shr 8) and 0xFF).toByte();  p[7] = (t and 0xFF).toByte()
    payloadBE.copyInto(p, 12)
    return p
}

private fun beSamples(vararg s: Int): ByteArray {
    val b = ByteArray(s.size * 2)
    s.forEachIndexed { i, v -> b[i*2] = ((v shr 8) and 0xFF).toByte(); b[i*2+1] = (v and 0xFF).toByte() }
    return b
}

fun rtpReceiverChecks() = runBlocking {
    println("== rtp receiver (udp loopback) ==")
    val port = 45789
    val sink = FakeSink()
    val src = RtpSource(address = "", port = port, payloadType = 96,
                        encoding = "L16", sampleRate = 48000, channels = 2)
    var lastStatus = RtpStatus()
    val rx = RtpReceiver(src, onStatus = { lastStatus = it }, onPcm = null,
                         openPlayer = { _, _ -> sink })
    val scope = CoroutineScope(Dispatchers.IO)
    rx.start(scope)
    delay(400)

    val tx = DatagramSocket()
    val dst = InetAddress.getByName("127.0.0.1")
    fun send(b: ByteArray) { tx.send(DatagramPacket(b, b.size, dst, port)) }

    // 1. Byte swap: due frame stereo, valori riconoscibili
    send(rtp(100, 96, beSamples(0x1234, 0x5678, 0x0A0B, 0x0C0D)))
    delay(250)
    val got = synchronized(sink.pcm) { sink.pcm.toByteArray() }
    rxCheck("byte swap BE->LE", got.size >= 8 &&
        got[0] == 0x34.toByte() && got[1] == 0x12.toByte() &&
        got[2] == 0x78.toByte() && got[3] == 0x56.toByte(),
        got.take(8).joinToString(" ") { "%02x".format(it) })

    // 2. Payload NON allineato al frame: il resto deve essere riportato al
    //    pacchetto successivo, non troncato (troncarlo sfasa L/R per sempre).
    synchronized(sink.pcm) { sink.pcm.reset() }
    send(rtp(101, 96, beSamples(0x1111, 0x2222, 0x3333)))   // 6 byte = 1.5 frame
    delay(150)
    val afterPartial = synchronized(sink.pcm) { sink.pcm.size() }
    rxCheck("payload parziale: consegna solo frame interi", afterPartial == 4,
        "consegnati $afterPartial byte")
    send(rtp(102, 96, beSamples(0x4444)))                    // completa il frame
    delay(200)
    val afterCarry = synchronized(sink.pcm) { sink.pcm.toByteArray() }
    rxCheck("riporto: il frame a cavallo viene ricomposto", afterCarry.size == 8,
        "totale ${afterCarry.size} byte")
    rxCheck("riporto: i campioni sono nell'ordine giusto",
        afterCarry.size == 8 &&
        afterCarry[4] == 0x33.toByte() && afterCarry[5] == 0x33.toByte() &&
        afterCarry[6] == 0x44.toByte() && afterCarry[7] == 0x44.toByte(),
        afterCarry.joinToString(" ") { "%02x".format(it) })

    // 3. Perdita: salto di sequenza -> mascheratura, non salto secco
    val before = sink.concealCalls
    send(rtp(106, 96, beSamples(1, 2)))    // persi 103,104,105
    delay(200)
    rxCheck("perdita: mascherata", sink.concealCalls == before + 1,
        "conceal chiamata ${sink.concealCalls - before} volte")
    // Lo stato si pubblica al massimo ogni 500 ms: serve un altro pacchetto
    // oltre quella soglia perche' il contatore arrivi alla UI.
    delay(600); send(rtp(107, 96, beSamples(1, 2))); delay(200)
    rxCheck("perdita: contata", lastStatus.lostPackets >= 3, "persi=${lastStatus.lostPackets}")

    // 4. Duplicato / fuori ordine vecchio: scartato senza mascherare
    val cBefore = sink.concealCalls
    val nBefore = synchronized(sink.pcm) { sink.pcm.size() }
    send(rtp(104, 96, beSamples(9, 9)))
    delay(200)
    rxCheck("pacchetto vecchio: scartato", synchronized(sink.pcm) { sink.pcm.size() } == nBefore)
    rxCheck("pacchetto vecchio: niente mascheratura", sink.concealCalls == cBefore)

    // 5. Payload type diverso: non entra nel flusso
    val nBefore2 = synchronized(sink.pcm) { sink.pcm.size() }
    send(rtp(108, 111, beSamples(7, 7)))
    delay(200)
    rxCheck("payload type estraneo: ignorato", synchronized(sink.pcm) { sink.pcm.size() } == nBefore2)

    // 6. Salto enorme (mittente riavviato): niente mascheratura chilometrica
    val cBefore2 = sink.concealCalls
    send(rtp(9000, 96, beSamples(3, 4), ts = 999_999_999L))
    delay(200)
    rxCheck("mittente riavviato: nessuna mascheratura", sink.concealCalls == cBefore2)
    rxCheck("riparte a ricevere", lastStatus.state == RtpState.PLAYING, "stato=${lastStatus.state}")

    // 7. Buco misurato dal timestamp: la durata mascherata deve essere esatta.
    //    Ogni pacchetto qui porta UN frame stereo (2 short), quindi il
    //    timestamp avanza di 1 per pacchetto consegnato.
    sink.concealBytes = 0; sink.concealCalls = 0
    var t = 1_000_000L
    send(rtp(9001, 96, beSamples(1, 2), ts = t)); delay(200)
    t += 1                                   // il pacchetto sopra vale 1 frame
    send(rtp(9003, 96, beSamples(3, 4), ts = t + 480)); delay(250)
    rxCheck("perdita: mascherata per la durata esatta dal timestamp",
        sink.concealBytes == 480 * 4,
        "mascherati ${sink.concealBytes} byte, attesi ${480 * 4}")
    t = t + 480 + 1

    // 8. Mittente che si ferma e riprende: sequenza CONSECUTIVA, timestamp che
    //    salta. Guardando solo la sequenza il buco sarebbe invisibile.
    sink.concealBytes = 0; sink.concealCalls = 0
    send(rtp(9004, 96, beSamples(5, 6), ts = t)); delay(200)
    t += 1
    send(rtp(9005, 96, beSamples(7, 8), ts = t + 960)); delay(250)
    rxCheck("pausa del mittente: buco rilevato dal solo timestamp",
        sink.concealBytes == 960 * 4,
        "mascherati ${sink.concealBytes} byte, attesi ${960 * 4}")

    // 9. Mittente riavviato con sequenza piu' bassa: senza risincronizzazione
    //    ogni suo pacchetto sembrerebbe "vecchio" e resteremmo muti per sempre.
    synchronized(sink.pcm) { sink.pcm.reset() }
    for (i in 0 until 20) { send(rtp(50 + i, 96, beSamples(1, 1), ts = 500L + i * 2)); delay(15) }
    delay(300)
    rxCheck("mittente ripartito da sequenza bassa: risincronizza",
        synchronized(sink.pcm) { sink.pcm.size() } > 0,
        "consegnati ${synchronized(sink.pcm) { sink.pcm.size() }} byte")

    rx.stop(); scope.cancel(); tx.close()
    delay(200)
    if (rtpRxFailures > 0) {
        println("  $rtpRxFailures controlli falliti")
        kotlin.system.exitProcess(1)
    }
}
