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
 * RtpReceiver.kt
 *
 * Ricezione di flussi RTP, con due percorsi:
 *
 *   - NATIVO per L16 (PCM lineare big endian), che e' quello che emette il
 *     server RTP di questa stessa app e quello che si configura in ffmpeg o
 *     VLC. Socket UDP, header RTP letto a mano, nessun intermediario: la
 *     latenza e' quella del jitter buffer e basta.
 *
 *   - FFMPEG per tutto il resto (Opus, AAC, PCMU/PCMA...). Costa piu' latenza
 *     perche' libavformat fa il suo buffering, ma copre i mittenti che non
 *     mandano PCM.
 *
 * La scelta e' automatica e viene comunicata alla UI: l'utente vede sempre
 * quale dei due sta girando, perche' la differenza di latenza e' percepibile.
 *
 * Non tocca il protocollo WFAS: e' un percorso di ricezione separato.
 */

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

enum class RtpState { IDLE, WAITING, PLAYING, ERROR }

/** Cosa mostrare nella card mentre si ascolta. */
data class RtpStatus(
    val state: RtpState = RtpState.IDLE,
    val source: RtpSource? = null,
    /** true = percorso nativo L16, false = decodifica FFmpeg. */
    val nativePath: Boolean = true,
    val packets: Long = 0,
    val bytes: Long = 0,
    val lostPackets: Long = 0,
    /** Riempimento del buffer di riproduzione. Se oscilla verso lo zero,
     *  l'audio arriva a singhiozzo e si sentono scatti. */
    val bufferMs: Int = 0,
    /** Chiave di traduzione dell'errore, piu' un eventuale dettaglio. */
    val errorKey: String? = null,
    val errorDetail: String? = null,
    /**
     * Quel che il flusso ha detto di essere, quando non coincide con quel che
     * era configurato. La card lo mostra: senza, l'utente vede solo un audio
     * che suona giusto dopo aver scritto numeri sbagliati, e non impara niente.
     */
    val detectedSampleRate: Int? = null,
    val detectedChannels: Int? = null
) {
    val active: Boolean get() = state == RtpState.WAITING || state == RtpState.PLAYING
}

/**
 * Un ricevitore per volta. La UI mette in ascolto una sorgente e ferma; lo
 * stato viene pubblicato tramite [onStatus] cosi' la card puo' mostrare
 * "in attesa di pacchetti" invece di restare muta quando la rete non passa.
 */
class RtpReceiver(
    private val source: RtpSource,
    private val onStatus: (RtpStatus) -> Unit,
    private val onPcm: ((ShortArray) -> Unit)? = null,
    /** Apre la linea audio: la fornisce il chiamante, che conosce il mixer scelto. */
    private val openPlayer: (sampleRate: Int, channels: Int) -> PcmPlaybackSink?,
    /** Preferenza di interfaccia dalle impostazioni di rete ("Auto" = lascia scegliere). */
    private val preferredInterface: String = "Auto"
) {
    private var job: Job? = null
    @Volatile private var stopping = false

    @Volatile private var bufferMsNow = 0

    /** Cosa e' risultato essere la sorgente, se diversa da come e' configurata. */
    @Volatile private var detected: RtpSourceProbe.Result? = null

    private val packets = AtomicLong(0)
    private val bytes   = AtomicLong(0)
    private val lost    = AtomicLong(0)

    /** Nessun pacchetto per questo tempo → si torna in attesa. */
    private val silenceTimeoutMs = 4000L

    fun start(scope: CoroutineScope) {
        if (job != null) return
        stopping = false
        job = scope.launch(Dispatchers.IO) {
            if (source.isNativePcm) runNative(this) else runFfmpeg(this)
        }
    }

    fun stop() {
        stopping = true
        job?.cancel()
        job = null
        publish(RtpState.IDLE)
    }

    private fun publish(
        state: RtpState,
        errorKey: String? = null,
        detail: String? = null
    ) = onStatus(
        RtpStatus(
            state       = state,
            source      = source,
            nativePath  = source.isNativePcm,
            packets     = packets.get(),
            bytes       = bytes.get(),
            lostPackets = lost.get(),
            bufferMs    = bufferMsNow,
            errorKey    = errorKey,
            errorDetail = detail,
            detectedSampleRate = detected?.sampleRate,
            detectedChannels   = detected?.channels
        )
    )

    // ── Percorso nativo: L16 ─────────────────────────────────────────────────

    private fun runNative(scope: CoroutineScope) {
        publish(RtpState.WAITING)

        var socket: DatagramSocket? = null
        var sink: PcmPlaybackSink? = null
        try {
            val sock = openSocket()
            socket = sock

            val buf = ByteArray(4096)
            val packet = DatagramPacket(buf, buf.size)
            var expectedSeq = -1
            var expectedTs = -1L
            var oldStreak = 0

            // La linea di uscita non si apre subito.
            //
            // Quel che il mittente manda davvero non e' detto sia quel che c'e'
            // scritto nel modulo: l'SDP puo' mancare, e chi compila i campi a
            // mano scrive quel che ricorda. Aprire l'uscita sui numeri
            // sbagliati non da' errore, da' un audio storto: un flusso a 96 kHz
            // aperto come 48 si sente un'ottava sotto, riempie la coda al
            // doppio della velocita' a cui si svuota, e quando la coda e' piena
            // l'arretrato passa nel socket, che comincia a buttare via
            // pacchetti. Da fuori sembra una rete rotta, ed e' solo un numero.
            //
            // I primi pacchetti pero' lo dicono da soli, e si tengono da parte
            // finche' non l'hanno detto: quel che si e' tenuto non va perso,
            // diventa il riempimento iniziale della linea.
            val probe = RtpSourceProbe(source.sampleRate, source.channels)
            var out: PcmPlaybackSink? = null
            var frameBytes = source.channels * 2
            val held = ArrayList<ByteArray>()
            var heldBytes = 0
            var probeStart = 0L
            // Riporto fra pacchetti: se un payload non finisce su un confine di
            // frame, i byte avanzati appartengono al frame che continua nel
            // pacchetto dopo. Troncarli sfasa l'interleave L/R da li' in poi.
            var carry = ByteArray(0)
            var lastRxAt = System.currentTimeMillis()
            var announcedPlaying = false
            var lastPublish = 0L

            while (scope.isActive && !stopping) {
                try {
                    packet.length = buf.size
                    sock.receive(packet)
                } catch (_: SocketTimeoutException) {
                    if (announcedPlaying &&
                        System.currentTimeMillis() - lastRxAt > silenceTimeoutMs) {
                        announcedPlaying = false
                        publish(RtpState.WAITING)
                    }
                    continue
                }

                lastRxAt = System.currentTimeMillis()
                val len = packet.length
                if (len < RTP_HEADER_MIN) continue

                val data = packet.data
                val version = (data[0].toInt() shr 6) and 0x03
                if (version != 2) continue

                val csrcCount = data[0].toInt() and 0x0F
                val hasExt    = (data[0].toInt() and 0x10) != 0
                val padded    = (data[0].toInt() and 0x20) != 0
                val pt        = data[1].toInt() and 0x7F

                // Un mittente diverso sulla stessa porta non deve entrare nel flusso.
                if (source.payloadType in 0..127 && pt != source.payloadType) continue

                var offset = RTP_HEADER_MIN + csrcCount * 4
                if (hasExt) {
                    if (offset + 4 > len) continue
                    val extWords = ((data[offset + 2].toInt() and 0xFF) shl 8) or
                                   (data[offset + 3].toInt() and 0xFF)
                    offset += 4 + extWords * 4
                }
                if (offset >= len) continue

                var payloadLen = len - offset
                if (padded) {
                    val pad = data[len - 1].toInt() and 0xFF
                    if (pad in 1..payloadLen) payloadLen -= pad
                }
                if (payloadLen <= 0) continue

                val seq = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                val ts = (((data[4].toInt() and 0xFF).toLong() shl 24) or
                          ((data[5].toInt() and 0xFF).toLong() shl 16) or
                          ((data[6].toInt() and 0xFF).toLong() shl 8) or
                           (data[7].toInt() and 0xFF).toLong())

                // ── Sondaggio: si misura prima di suonare ─────────────
                if (out == null) {
                    val now = System.currentTimeMillis()
                    if (probeStart == 0L) probeStart = now
                    probe.observe(seq, ts, payloadLen)
                    // Le perdite si contano da subito: anche questi
                    // pacchetti sono ascolto, non un preambolo che non conta.
                    if (expectedSeq >= 0) {
                        val d = (seq - expectedSeq) and 0xFFFF
                        if (d in 1..MAX_DROPOUT) lost.addAndGet(d.toLong())
                    }
                    expectedSeq = (seq + 1) and 0xFFFF
                    if (heldBytes < MAX_PROBE_BYTES) {
                        held.add(data.copyOfRange(offset, offset + payloadLen))
                        heldBytes += payloadLen
                    }
                    packets.incrementAndGet()
                    val waited = now - probeStart
                    if (!probe.decided() && waited < MAX_PROBE_MS && heldBytes < MAX_PROBE_BYTES) {
                        continue
                    }

                    val found = probe.resolve()
                    detected = found
                    if (found.changed) {
                        AppDebug.log(
                            "[RTP-RX] la sorgente non e' quella configurata: " +
                            "${found.sampleRate} Hz ${found.channels} ch invece di " +
                            "${source.sampleRate} Hz ${source.channels} ch"
                        )
                    }
                    frameBytes = found.channels * 2
                    var opened = openPlayer(found.sampleRate, found.channels)
                    if (opened == null && found.changed) {
                        // La scheda audio non regge il formato vero: meglio
                        // suonare storto che non suonare. Si torna ai valori
                        // impostati, e la card smette di dire di aver corretto
                        // qualcosa -- perche' non l'ha corretto.
                        AppDebug.log(
                            "[RTP-RX] ${found.sampleRate} Hz ${found.channels} ch " +
                            "non si apre: torno ai valori impostati"
                        )
                        detected = null
                        frameBytes = source.channels * 2
                        opened = openPlayer(source.sampleRate, source.channels)
                    }
                    val readyPlayer = opened
                    if (readyPlayer == null) {
                        publish(RtpState.ERROR, "rtp_err_no_output")
                        return
                    }
                    out = readyPlayer
                    sink = readyPlayer

                    val all = ByteArray(heldBytes)
                    var at = 0
                    for (b in held) { b.copyInto(all, at); at += b.size }
                    held.clear()
                    val head = all.size - (all.size % frameBytes)
                    carry = if (head < all.size) all.copyOfRange(head, all.size) else ByteArray(0)
                    if (head > 0) {
                        readyPlayer.submit(swap16(all, head))
                        bytes.addAndGet(head.toLong())
                    }
                    expectedSeq = (seq + 1) and 0xFFFF
                    expectedTs  = (ts + (payloadLen / frameBytes).toLong()) and 0xFFFFFFFFL
                    announcedPlaying = true
                    bufferMsNow = readyPlayer.bufferedMs()
                    publish(RtpState.PLAYING)
                    continue
                }
                val player = out ?: continue

                // Sequenza e timestamp dicono cose diverse e vanno letti
                // separatamente:
                //   - la SEQUENZA distingue duplicati e pacchetti vecchi, e
                //     conta le perdite;
                //   - il TIMESTAMP dice quanto audio manca davvero. Un mittente
                //     che si ferma e riprende lascia la sequenza consecutiva e
                //     salta solo il timestamp: guardando la sequenza quel buco
                //     non si vedrebbe, e si sentirebbe come un click.
                if (expectedSeq >= 0) {
                    val delta = (seq - expectedSeq) and 0xFFFF
                    if (delta >= 0x8000) {
                        // Vecchio o duplicato. Ma un mittente riavviato riparte
                        // da una sequenza casuale, che puo' benissimo essere
                        // piu' bassa: se ci limitassimo a scartare, ogni suo
                        // pacchetto sembrerebbe vecchio e resteremmo muti per
                        // sempre. Dopo una serie di scarti si riparte da capo.
                        if (++oldStreak < RESYNC_AFTER_OLD) continue
                        AppDebug.log("[RTP-RX] sequenza ripartita da $seq: risincronizzo")
                        expectedSeq = -1
                        expectedTs  = -1L
                        carry = ByteArray(0)
                    } else if (delta > MAX_DROPOUT) {
                        // Un salto in avanti cosi' grande non e' una perdita:
                        // e' un mittente ripartito con una sequenza nuova (RTP
                        // la sceglie a caso) o un secondo mittente sulla stessa
                        // porta. Contarlo come perdita aggiunge al totale
                        // decine di migliaia di pacchetti mai esistiti, e fa
                        // sembrare rotta una rete che sta benissimo. RFC 3550
                        // mette il confine a 3000.
                        AppDebug.log("[RTP-RX] salto di sequenza a $seq (+$delta): non e' una perdita")
                        expectedTs = -1L
                        carry = ByteArray(0)
                    } else if (delta > 0) {
                        lost.addAndGet(delta.toLong())
                    }
                }
                oldStreak = 0
                expectedSeq = (seq + 1) and 0xFFFF

                if (expectedTs >= 0 && ts != expectedTs) {
                    val gap = ((ts - expectedTs) and 0xFFFFFFFFL)
                        .let { if (it > 0x7FFFFFFFL) -1L else it }
                    if (gap in 1..MAX_CONCEAL_FRAMES) {
                        player.conceal((gap * frameBytes).toInt())
                    } else {
                        // Sovrapposizione o salto assurdo (mittente riavviato):
                        // si riparte puliti invece di inventare audio.
                        carry = ByteArray(0)
                    }
                }

                // RTP porta L16 in big endian, la linea audio vuole little endian.
                // Il byte swap lavora su coppie, quindi il riporto e' in byte
                // grezzi e va anteposto prima di scambiare.
                val raw = if (carry.isEmpty()) {
                    data.copyOfRange(offset, offset + payloadLen)
                } else {
                    ByteArray(carry.size + payloadLen).also {
                        carry.copyInto(it, 0)
                        data.copyInto(it, carry.size, offset, offset + payloadLen)
                    }
                }
                val usable = raw.size - (raw.size % frameBytes)
                carry = if (usable < raw.size) raw.copyOfRange(usable, raw.size) else ByteArray(0)
                if (usable <= 0) continue

                val pcm = swap16(raw, usable)
                expectedTs = (ts + (usable / frameBytes).toLong()) and 0xFFFFFFFFL

                player.submit(pcm)
                onPcm?.let { cb ->
                    val shorts = ShortArray(usable / 2)
                    java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer().get(shorts)
                    cb(shorts)
                }

                packets.incrementAndGet()
                bytes.addAndGet(usable.toLong())

                if (!announcedPlaying) {
                    announcedPlaying = true
                    bufferMsNow = player.bufferedMs()
                    publish(RtpState.PLAYING)
                } else if (lastRxAt - lastPublish > 400) {
                    lastPublish = lastRxAt
                    bufferMsNow = player.bufferedMs()
                    publish(RtpState.PLAYING)
                }
            }
        } catch (e: Exception) {
            if (!stopping) {
                AppDebug.log("[RTP-RX] errore: ${e.message}")
                publish(RtpState.ERROR, "rtp_err_socket", e.message)
            }
        } finally {
            runCatching { socket?.close() }
            runCatching { sink?.close() }
            if (!stopping) publish(RtpState.IDLE)
        }
    }

    private fun openSocket(): DatagramSocket {
        return if (source.isMulticast) {
            MulticastSocket(source.port).apply {
                soTimeout = 1000
                reuseAddress = true
                runCatching { receiveBufferSize = 1 shl 20 }
                val group = InetAddress.getByName(source.address)
                val iface = runCatching {
                    NetworkHandler_v1.getActiveNetworkInterface(preferredInterface)
                }.getOrNull()
                if (iface != null) {
                    runCatching { joinGroup(InetSocketAddress(group, source.port), iface) }
                        .onFailure { @Suppress("DEPRECATION") joinGroup(group) }
                } else {
                    @Suppress("DEPRECATION") joinGroup(group)
                }
                AppDebug.log("[RTP-RX] multicast ${source.address}:${source.port} iface=${iface?.name ?: "default"}")
            }
        } else {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 1000
                runCatching { receiveBufferSize = 1 shl 20 }
                bind(InetSocketAddress(source.port))
                AppDebug.log("[RTP-RX] unicast in ascolto sulla porta ${source.port}")
            }
        }
    }

    // ── Percorso FFmpeg: codec non nativi ───────────────────────────────────

    private fun runFfmpeg(scope: CoroutineScope) {
        publish(RtpState.WAITING)

        var grabber: org.bytedeco.javacv.FFmpegFrameGrabber? = null
        var sink: PcmPlaybackSink? = null
        var sdpFile: java.io.File? = null
        try {
            val sdp = source.sdpText ?: RtpSdp.synthesize(source)
            sdpFile = java.io.File.createTempFile("wfas_rx_", ".sdp").apply {
                writeText(sdp)
                deleteOnExit()
            }

            grabber = org.bytedeco.javacv.FFmpegFrameGrabber(sdpFile.absolutePath).apply {
                setFormat("sdp")
                // Senza whitelist libavformat rifiuta di aprire file+udp da un SDP.
                setOption("protocol_whitelist", "file,udp,rtp,crypto")
                setOption("probesize", "32")
                setOption("analyzeduration", "0")
                setOption("fflags", "nobuffer")
                setOption("flags", "low_delay")
                setOption("reorder_queue_size", "8")
                setOption("max_delay", "200000")
                sampleFormat = org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
                start()
            }

            val g = grabber
            val rate = g.sampleRate.takeIf { it > 0 } ?: source.sampleRate
            val ch   = g.audioChannels.takeIf { it > 0 } ?: source.channels
            AppDebug.log("[RTP-RX] ffmpeg aperto: $rate Hz $ch ch")

            val out = openPlayer(rate, ch)
            if (out == null) {
                publish(RtpState.ERROR, "rtp_err_no_output")
                return
            }
            sink = out

            var announcedPlaying = false
            var lastPublish = 0L
            while (scope.isActive && !stopping) {
                val frame = try {
                    g.grabSamples()
                } catch (e: Exception) {
                    if (stopping) break
                    AppDebug.log("[RTP-RX] ffmpeg grabSamples: ${e.message}")
                    break
                } ?: continue

                val samples = frame.samples ?: continue
                val sb = samples.getOrNull(0) as? java.nio.ShortBuffer ?: continue
                sb.position(0)
                val n = sb.remaining()
                if (n <= 0) continue
                val shorts = ShortArray(n)
                sb.get(shorts, 0, n)

                val pcm = ByteArray(n * 2)
                java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().put(shorts)

                out.submit(pcm)
                onPcm?.invoke(shorts)

                packets.incrementAndGet()
                bytes.addAndGet(pcm.size.toLong())

                val now = System.currentTimeMillis()
                if (!announcedPlaying) {
                    announcedPlaying = true
                    bufferMsNow = out.bufferedMs()
                    publish(RtpState.PLAYING)
                } else if (now - lastPublish > 400) {
                    lastPublish = now
                    bufferMsNow = out.bufferedMs()
                    publish(RtpState.PLAYING)
                }
            }
        } catch (e: Exception) {
            if (!stopping) {
                AppDebug.log("[RTP-RX] ffmpeg errore: ${e.message}")
                publish(RtpState.ERROR, "rtp_err_ffmpeg", e.message)
            }
        } finally {
            runCatching { grabber?.stop() }
            runCatching { grabber?.release() }
            runCatching { sink?.close() }
            runCatching { sdpFile?.delete() }
            if (!stopping) publish(RtpState.IDLE)
        }
    }

    /**
     * L16 viaggia in big endian, la linea audio vuole little endian.
     * Lavora su coppie di byte, quindi non sa niente di canali: e' la stessa
     * funzione per mono e stereo.
     */
    private fun swap16(raw: ByteArray, len: Int): ByteArray {
        val outBuf = ByteArray(len)
        var i = 0
        while (i + 1 < len) {
            outBuf[i]     = raw[i + 1]
            outBuf[i + 1] = raw[i]
            i += 2
        }
        return outBuf
    }

    companion object {
        private const val RTP_HEADER_MIN = 12
        /** Oltre questo salto di sequenza non si maschera: si riparte puliti. */
        /** Tetto alla mascheratura: 200 ms a 48 kHz. Oltre, si riparte puliti. */
        private const val MAX_CONCEAL_FRAMES = 9600L
        /** Pacchetti "vecchi" di fila dopo i quali si assume un mittente nuovo. */
        private const val RESYNC_AFTER_OLD = 12

        /** Oltre questo salto in avanti non e' perdita, e' un altro flusso (RFC 3550). */
        private const val MAX_DROPOUT = 3000

        /** Quanto si aspetta al massimo prima di aprire l'uscita comunque. */
        private const val MAX_PROBE_MS = 600L

        /** Tetto a quel che si tiene da parte mentre si misura: ~300 ms a 96 kHz. */
        private const val MAX_PROBE_BYTES = 192 * 1024
    }
}

/**
 * Uscita audio per i ricevitori: la implementa Main con il suo jitter buffer.
 * La usano sia RTP sia Snapcast — i problemi di rete sono gli stessi e non
 * aveva senso una seconda implementazione destinata a invecchiare da sola.
 */
interface PcmPlaybackSink {
    fun submit(pcmLittleEndian: ByteArray)
    /**
     * Buco nel flusso, in byte stimati. Il sink lo maschera con l'ultimo audio
     * buono invece di lasciare un salto secco, che si sente come un click.
     */
    fun conceal(approxBytes: Int)
    /** Millisecondi di audio in coda: e' la diagnosi quando si sentono scatti. */
    fun bufferedMs(): Int
    fun close()
}

/**
 * Cosa manda davvero il mittente, misurato sul flusso invece che creduto.
 *
 * In RTP niente descrive il flusso: la descrizione sta nell'SDP, che qui puo'
 * benissimo non esserci -- l'utente scrive indirizzo, porta, frequenza e
 * canali a mano. Se sbaglia, non arriva nessun errore: arriva un audio storto,
 * e storto in un modo che sembra un problema di rete.
 *
 * Due cose pero' si misurano dal flusso stesso, e sono esatte:
 *
 *  - i BYTE PER FRAME. Fra due pacchetti consecutivi il timestamp avanza di un
 *    tick per frame, quindi payload diviso avanzamento da' i byte per frame, e
 *    da quelli i canali: L16 e' sempre a 16 bit per campione. E' aritmetica
 *    intera, senza margine di interpretazione: o la divisione torna esatta o il
 *    campione non si usa.
 *
 *  - la FREQUENZA. Per L16 il clock RTP e' la frequenza di campionamento
 *    (RFC 3551): quanti tick passano in un secondo di orologio e' la frequenza.
 *    Questa e' una misura, non un conto, quindi si adotta solo quando e' netta:
 *    deve cadere vicinissima a una frequenza standard e insieme lontana da
 *    quella configurata. Fra 44100 e 48000 non si sceglie -- sono troppo
 *    vicine perche' una misura di mezzo secondo decida, e sbagliare li' costa
 *    piu' di quanto non costi lasciare il valore dell'utente.
 */
internal class RtpSourceProbe(
    private val configuredRate: Int,
    private val configuredChannels: Int
) {

    /** Quel che si e' concluso, e se e' diverso da quel che c'era scritto. */
    data class Result(
        val sampleRate: Int,
        val channels: Int,
        val rateChanged: Boolean,
        val channelsChanged: Boolean
    ) {
        val changed: Boolean get() = rateChanged || channelsChanged
    }

    private var lastSeq = -1
    private var lastTs  = -1L
    private var lastLen = 0
    private val votes = HashMap<Int, Int>()
    private var voted = 0

    // Il tempo si misura solo fra CAPOFILA.
    //
    // Chi manda non consegna a gocce, consegna a blocchi: la scheda audio da'
    // sessantasei millisecondi in un colpo e il mittente li spara tutti
    // attaccati. Dentro un blocco il tempo dell'audio corre e quello
    // dell'orologio no, quindi una finestra che comincia o finisce in mezzo a
    // un blocco misura una frequenza che non esiste. Fra il primo pacchetto di
    // un blocco e il primo del blocco dopo, invece, i due tempi scorrono
    // uguali -- e bastano tre blocchi per avere una misura pulita.
    private var lastArrivalNs = 0L
    private var leaders = 0
    private var firstTs = -1L
    private var firstNs = 0L
    private var lastTsLead = -1L
    private var lastNs  = 0L

    fun observe(seq: Int, ts: Long, payloadLen: Int) {
        val now = System.nanoTime()
        if (lastArrivalNs == 0L || now - lastArrivalNs >= LEAD_GAP_NS) {
            if (firstTs < 0L) { firstTs = ts; firstNs = now }
            lastTsLead = ts
            lastNs = now
            leaders++
        }
        lastArrivalNs = now
        // Solo fra pacchetti consecutivi: con un buco in mezzo l'avanzamento
        // del timestamp non e' quello di un pacchetto solo e il conto salta.
        if (lastSeq >= 0 && ((seq - lastSeq) and 0xFFFF) == 1 && lastTs >= 0L && lastLen > 0) {
            val advance = ((ts - lastTs) and 0xFFFFFFFFL).toInt()
            if (advance in 1..100_000 && lastLen % advance == 0) {
                val perFrame = lastLen / advance
                if (perFrame == 2 || perFrame == 4) {
                    votes[perFrame] = (votes[perFrame] ?: 0) + 1
                    voted++
                }
            }
        }
        lastSeq = seq
        lastTs  = ts
        lastLen = payloadLen
    }

    /**
     * Abbastanza per concludere: i canali contati, e almeno tre blocchi di
     * audio visti arrivare, distanti fra loro quanto basta.
     */
    fun decided(): Boolean =
        voted >= 8 && leaders >= 3 && (lastNs - firstNs) >= MIN_SPAN_NS

    fun resolve(): Result {
        var channels = configuredChannels
        val best = votes.maxByOrNull { it.value }
        if (best != null && best.value >= 4 && best.value * 2 > voted) {
            val ch = best.key / 2
            if (ch in 1..2) channels = ch
        }

        var rate = configuredRate
        val spanTs = if (firstTs >= 0L && lastTsLead >= 0L) (lastTsLead - firstTs) and 0xFFFFFFFFL else 0L
        val spanNs = lastNs - firstNs
        if (spanTs in 1L..0x7FFFFFFFL && spanNs >= MIN_SPAN_NS) {
            val measured = spanTs * 1_000_000_000.0 / spanNs
            // Il rapporto fra misurato e dichiarato, non la differenza.
            //
            // La misura e' grezza per forza: chi manda consegna l'audio a
            // blocchi, non a gocce, e la finestra puo' cominciare e finire
            // dentro un blocco -- qualche punto percentuale di errore c'e'
            // sempre. Ma un numero sbagliato nel modulo non e' mai sbagliato
            // di poco: si scrive 48000 dove c'era 96000, o 44100 dove c'era
            // 22050. Sono rapporti semplici, e stanno lontanissimi dall'errore
            // di misura. Cosi' il conto sopporta la grana della misura senza
            // mai poter scambiare una sorgente giusta per un'altra.
            val ratio = measured / configuredRate
            val near = SIMPLE_RATIOS.minByOrNull { kotlin.math.abs(it - ratio) }
            if (near != null && near != 1.0 && kotlin.math.abs(near - ratio) <= near * 0.12) {
                val candidate = kotlin.math.round(configuredRate * near).toInt()
                // E deve essere una frequenza che esiste: 48000 per 1.5 fa
                // 72000, che non e' una frequenza, e' un conto sbagliato.
                if (STANDARD_RATES.any { it == candidate }) rate = candidate
            }
        }
        return Result(rate, channels, rate != configuredRate, channels != configuredChannels)
    }

    private companion object {
        /** Due millisecondi di silenzio fra un pacchetto e l'altro: blocco nuovo. */
        const val LEAD_GAP_NS = 2_000_000L

        /** Duecento millisecondi fra il primo e l'ultimo capofila: tanto basta. */
        const val MIN_SPAN_NS = 200_000_000L

        /** I modi in cui si sbaglia una frequenza: doppio, meta', terzo... */
        val SIMPLE_RATIOS = doubleArrayOf(
            0.25, 1.0 / 3.0, 0.5, 2.0 / 3.0, 1.0, 1.5, 2.0, 3.0, 4.0
        )

        val STANDARD_RATES = intArrayOf(
            8000, 11025, 16000, 22050, 24000, 32000,
            44100, 48000, 64000, 88200, 96000, 176400, 192000
        )
    }
}
