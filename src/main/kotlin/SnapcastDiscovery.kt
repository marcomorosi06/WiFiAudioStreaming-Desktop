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
 * SnapcastDiscovery.kt
 *
 * Ricerca dei server Snapcast in rete via mDNS (_snapcast._tcp.local).
 *
 * SnapcastMdns.kt risponde alle interrogazioni quando siamo noi il server;
 * qui si fa il mestiere opposto, che e' interrogare e mettere insieme le
 * risposte. Il parsing dei pacchetti DNS sta in [SnapcastDns], separato dalla
 * parte di rete, perche' e' logica pura e come tale va provata.
 *
 * Un server Snapcast si annuncia con tre record che arrivano spesso in
 * pacchetti diversi e in ordine qualsiasi: PTR dice quali istanze esistono,
 * SRV dice porta e nome host, A dice l'indirizzo. Si tiene quindi uno stato
 * parziale e si emette il server solo quando ha tutto quello che serve.
 */

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Un server Snapcast trovato in rete o inserito a mano. */
data class SnapcastServerRef(
    val name: String,
    val host: String,
    val streamPort: Int = SnapcastDefaults.STREAM_PORT,
    val controlPort: Int = SnapcastDefaults.CONTROL_PORT,
    val discovered: Boolean = true,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val key: String get() = "$host:$streamPort"

    fun displayName(): String = name.ifBlank { host }

    fun serialize(): String =
        listOf(name.replace('|', '/'), host, streamPort.toString(), controlPort.toString())
            .joinToString("|")

    companion object {
        fun deserialize(line: String): SnapcastServerRef? {
            val p = line.split('|')
            if (p.size < 4) return null
            return SnapcastServerRef(
                name        = p[0],
                host        = p[1],
                streamPort  = p[2].toIntOrNull() ?: return null,
                controlPort = p[3].toIntOrNull() ?: SnapcastDefaults.CONTROL_PORT,
                discovered  = false
            )
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Parsing DNS — logica pura, senza socket
 * ═══════════════════════════════════════════════════════════════════════════*/

/** Record utili estratti da una risposta mDNS. */
data class SnapcastDnsRecords(
    /** nome istanza completo, es. "salotto._snapcast._tcp.local" */
    val pointers: List<String> = emptyList(),
    /** istanza → (porta, host target) */
    val services: Map<String, Pair<Int, String>> = emptyMap(),
    /** host → indirizzo IPv4 */
    val addresses: Map<String, String> = emptyMap(),
    /** istanza → voci TXT */
    val texts: Map<String, List<String>> = emptyMap(),
    /**
     * Nomi annunciati con TTL 0: in mDNS e' il modo di dire "questo servizio
     * non c'e' piu'". Un server che si spegne per bene manda questi record, e
     * ignorarli lascia in lista dei fantasmi.
     */
    val goodbyes: Set<String> = emptySet()
)

object SnapcastDns {

    /** Servizio audio: porta 1704. */
    const val SERVICE_TYPE = "_snapcast._tcp.local"

    /**
     * Servizio di controllo: porta 1705. snapserver lo annuncia SEPARATAMENTE,
     * quindi lo stesso server compare due volte sul filo. Vanno riuniti, non
     * mostrati come due impianti diversi.
     */
    const val CONTROL_SERVICE_TYPE = "_snapcast-ctrl._tcp.local"

    private const val TYPE_A    = 1
    private const val TYPE_PTR  = 12
    private const val TYPE_TXT  = 16
    private const val TYPE_SRV  = 33

    /** Interrogazione PTR per il tipo di servizio. */
    fun buildQuery(serviceType: String = SERVICE_TYPE): ByteArray {
        val out = ByteArrayOutputStream()
        // header: id 0, flags 0 (standard query), 1 domanda
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        out.write(encodeName(serviceType))
        out.write(byteArrayOf(0, TYPE_PTR.toByte()))   // QTYPE = PTR
        out.write(byteArrayOf(0, 1))                   // QCLASS = IN
        return out.toByteArray()
    }

    /**
     * Estrae i record da una risposta mDNS. Non lancia mai: un pacchetto
     * malformato — e in mDNS ne girano — produce semplicemente meno record.
     */
    fun parseResponse(data: ByteArray, length: Int): SnapcastDnsRecords {
        val pointers = mutableListOf<String>()
        val services = mutableMapOf<String, Pair<Int, String>>()
        val addresses = mutableMapOf<String, String>()
        val texts = mutableMapOf<String, List<String>>()
        val goodbyes = mutableSetOf<String>()

        try {
            if (length < 12) return SnapcastDnsRecords()
            fun u16(at: Int) = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

            val qd = u16(4)
            val counts = u16(6) + u16(8) + u16(10)   // answer + authority + additional
            var pos = 12

            // Le domande si saltano: interessano solo le risposte.
            repeat(qd) {
                val n = readName(data, pos, length) ?: return SnapcastDnsRecords(pointers, services, addresses, texts, goodbyes)
                pos = n.second + 4
                if (pos > length) return SnapcastDnsRecords(pointers, services, addresses, texts, goodbyes)
            }

            repeat(counts) {
                if (pos + 10 > length) return@repeat
                val nameRes = readName(data, pos, length) ?: return@repeat
                val name = nameRes.first
                var p = nameRes.second
                if (p + 10 > length) return@repeat
                val type = u16(p)
                val ttl = (u16(p + 4).toLong() shl 16) or u16(p + 6).toLong()
                val rdLen = u16(p + 8)
                p += 10
                if (p + rdLen > length) return@repeat

                if (ttl == 0L) {
                    // Addio: si segnala il nome e non si registra il contenuto.
                    goodbyes += name
                    if (type == TYPE_PTR) readName(data, p, length)?.let { goodbyes += it.first }
                    pos = p + rdLen
                    return@repeat
                }

                when (type) {
                    TYPE_PTR -> readName(data, p, length)?.let { pointers += it.first }

                    TYPE_SRV -> {
                        if (rdLen >= 7) {
                            val port = u16(p + 4)
                            readName(data, p + 6, length)?.let { services[name] = port to it.first }
                        }
                    }

                    TYPE_A -> {
                        if (rdLen == 4) {
                            addresses[name] = "${data[p].toInt() and 0xFF}.${data[p + 1].toInt() and 0xFF}." +
                                    "${data[p + 2].toInt() and 0xFF}.${data[p + 3].toInt() and 0xFF}"
                        }
                    }

                    TYPE_TXT -> {
                        val entries = mutableListOf<String>()
                        var q = p
                        while (q < p + rdLen && q < length) {
                            val len = data[q].toInt() and 0xFF
                            if (len == 0 || q + 1 + len > p + rdLen) break
                            entries += String(data, q + 1, len, Charsets.UTF_8)
                            q += 1 + len
                        }
                        if (entries.isNotEmpty()) texts[name] = entries
                    }
                }
                pos = p + rdLen
            }
        } catch (_: Exception) {
            // Pacchetto storto: si tiene quel che si e' letto fin li'.
        }

        return SnapcastDnsRecords(pointers, services, addresses, texts, goodbyes)
    }

    /**
     * Nome dell'istanza in forma leggibile: "salotto._snapcast._tcp.local"
     * diventa "salotto". I nomi mDNS possono contenere spazi codificati.
     */
    fun instanceLabel(fullName: String, serviceType: String = SERVICE_TYPE): String {
        var base = fullName
        for (type in listOf(serviceType, SERVICE_TYPE, CONTROL_SERVICE_TYPE)) {
            val suffix = ".$type"
            if (base.endsWith(suffix, ignoreCase = true)) {
                base = base.dropLast(suffix.length)
                break
            }
        }
        return base.replace("\\032", " ").replace("\\ ", " ").trim()
    }

    /** true se l'istanza appartiene al tipo di servizio dato. */
    fun belongsTo(instance: String, serviceType: String): Boolean =
        instance.endsWith(".$serviceType", ignoreCase = true)

    fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        name.trim('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) return@forEach
            out.write(bytes.size.coerceAtMost(63))
            out.write(bytes, 0, bytes.size.coerceAtMost(63))
        }
        out.write(0)
        return out.toByteArray()
    }

    /**
     * Legge un nome DNS gestendo la compressione a puntatori.
     * Restituisce il nome e la posizione SUCCESSIVA al nome nel flusso
     * originale (non dove porta il puntatore).
     */
    fun readName(data: ByteArray, start: Int, limit: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var pos = start
        var end = -1
        var hops = 0

        while (pos < limit) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (pos + 1 >= limit) return null
                val ptr = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                if (end < 0) end = pos + 2
                pos = ptr
                if (++hops > 16) return null      // ciclo di puntatori
                continue
            }
            if (pos + 1 + len > limit) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(data, pos + 1, len, Charsets.UTF_8))
            pos += 1 + len
        }

        return sb.toString() to (if (end >= 0) end else pos)
    }
}

/**
 * Accumula i record mDNS e ne ricava i server Snapcast.
 *
 * Tenuto separato dal socket perche' e' qui che si sbaglia, e qui si prova.
 * Due trappole, entrambe incontrate sul campo:
 *
 *  1. mDNS e' un canale condiviso: sulla stessa porta multicast passano gli
 *     annunci di TUTTI i servizi della rete. Senza filtrare per tipo, un
 *     telefono con KDE Connect diventa un "server Snapcast" con tanto di
 *     porta 1716.
 *
 *  2. snapserver annuncia DUE servizi distinti — audio su _snapcast._tcp e
 *     controllo su _snapcast-ctrl._tcp — quindi lo stesso impianto arriva due
 *     volte. Vanno riuniti guardando l'indirizzo IP, che e' l'unica cosa che
 *     i due annunci hanno davvero in comune.
 */
class SnapcastServiceIndex(private val ttlMs: Long = 20_000L) {

    private val srv = mutableMapOf<String, Pair<Int, String>>()   // istanza -> porta/target
    private val addr = mutableMapOf<String, String>()             // host -> ip
    private val seenAt = mutableMapOf<String, Long>()             // nome -> ultimo avvistamento

    fun ingest(rec: SnapcastDnsRecords, nowMs: Long = System.currentTimeMillis()) {
        // Un addio ha la precedenza su tutto il resto del pacchetto.
        for (name in rec.goodbyes) {
            srv.remove(name); addr.remove(name); seenAt.remove(name)
        }

        // Si tengono solo i servizi Snapcast; il resto della rete non ci riguarda.
        for ((instance, service) in rec.services) {
            if (SnapcastDns.belongsTo(instance, SnapcastDns.SERVICE_TYPE) ||
                SnapcastDns.belongsTo(instance, SnapcastDns.CONTROL_SERVICE_TYPE)) {
                srv[instance] = service
                seenAt[instance] = nowMs
            }
        }
        // Gli indirizzi si tengono tutti: il record A di un server Snapcast
        // puo' arrivare in un pacchetto che non contiene nessun SRV suo.
        for ((host, ip) in rec.addresses) {
            addr[host] = ip
            seenAt[host] = nowMs
        }
    }

    /**
     * Toglie quello che non si fa vivo da troppo tempo.
     *
     * Serve perche' un server che sparisce di brutto — processo ucciso, rete
     * staccata, telefono che si spegne — non manda nessun addio: senza scadenza
     * resterebbe in lista per sempre. Il browser interroga ogni 5 secondi, quindi
     * questa finestra sono quattro occasioni mancate prima di dichiararlo andato.
     *
     * @return true se qualcosa e' stato tolto.
     */
    fun expire(nowMs: Long = System.currentTimeMillis()): Boolean {
        val dead = seenAt.filterValues { nowMs - it > ttlMs }.keys.toList()
        if (dead.isEmpty()) return false
        for (name in dead) { srv.remove(name); addr.remove(name); seenAt.remove(name) }
        return true
    }

    fun clear() { srv.clear(); addr.clear(); seenAt.clear() }

    private fun ipOf(target: String): String? =
        addr[target] ?: addr[target.trimEnd('.')] ?: addr["$target."]

    fun servers(): List<SnapcastServerRef> {
        // Porta di controllo per indirizzo, dal servizio _snapcast-ctrl._tcp.
        val controlByIp = mutableMapOf<String, Int>()
        for ((instance, service) in srv) {
            if (!SnapcastDns.belongsTo(instance, SnapcastDns.CONTROL_SERVICE_TYPE)) continue
            val ip = ipOf(service.second) ?: continue
            controlByIp[ip] = service.first
        }

        val out = mutableMapOf<String, SnapcastServerRef>()
        for ((instance, service) in srv) {
            if (!SnapcastDns.belongsTo(instance, SnapcastDns.SERVICE_TYPE)) continue
            val (port, target) = service
            val ip = ipOf(target) ?: continue
            val ref = SnapcastServerRef(
                name = SnapcastDns.instanceLabel(instance),
                host = ip,
                streamPort = port,
                // Se il servizio di controllo non e' stato annunciato si ricade
                // sulla convenzione: una porta piu' avanti di quella audio.
                controlPort = controlByIp[ip]
                    ?: if (port == SnapcastDefaults.STREAM_PORT) SnapcastDefaults.CONTROL_PORT
                       else port + 1,
                discovered = true
            )
            out[ref.key] = ref
        }
        return out.values.sortedBy { it.displayName().lowercase() }
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Browser mDNS
 * ═══════════════════════════════════════════════════════════════════════════*/

/**
 * Quando ripetere la domanda.
 *
 * Chiedere una volta sola e sperare e' una scommessa: mDNS viaggia su UDP
 * multicast, che si perde facilmente e sul Wi-Fi ancora di piu'. Una ricerca
 * che dura quattro secondi con una domanda sola torna a mani vuote ogni volta
 * che quel pacchetto — o la sua risposta — non arriva, e sembra che in rete non
 * ci sia nessuno.
 *
 * Si fa come dice l'RFC 6762: fitto all'inizio, dove serve, e poi si dirada
 * fino al ritmo di mantenimento. In quattro secondi diventano cinque tentativi
 * invece di uno.
 *
 * Sta fuori dal socket perche' e' l'unica parte che si puo' provare senza rete.
 */
class SnapcastQuerySchedule(
    private val firstMs: Long = FIRST_QUERY_GAP_MS,
    private val steadyMs: Long = QUERY_INTERVAL_MS
) {
    private var due = 0L
    private var gap = 0L

    /** true se e' il momento di chiedere; sposta avanti il prossimo appuntamento. */
    fun shouldAsk(now: Long): Boolean {
        if (due != 0L && now < due) return false
        gap = if (gap == 0L) firstMs else (gap * 2).coerceAtMost(steadyMs)
        due = now + gap
        return true
    }

    companion object {
        const val FIRST_QUERY_GAP_MS = 250L
        const val QUERY_INTERVAL_MS = 5_000L
    }
}

class SnapcastBrowser(
    private val scope: CoroutineScope,
    private val interfaceProvider: () -> NetworkInterface? = { null },
    private val onServers: (List<SnapcastServerRef>) -> Unit
) {
    private var job: Job? = null

    /**
     * Fermarsi va detto in due modi.
     *
     * Il ciclo di ascolto non ha punti di sospensione: e' tutto I/O bloccante,
     * quindi annullare il job non lo interrompe. Serve una bandiera che il
     * ciclo guarda, e la chiusura del socket per non aspettare il timeout di
     * ricezione. Senza, [stop] tornava subito ma il thread restava vivo per
     * sempre — e chi aspettava la fine di questa ricerca (la riga di comando)
     * restava li' a guardare.
     */
    @Volatile private var stopping = false
    @Volatile private var liveSocket: MulticastSocket? = null

    /** SRV, A e PTR arrivano in pacchetti diversi: l'indice li mette insieme. */
    private val index = SnapcastServiceIndex()
    private var lastEmitted: List<SnapcastServerRef> = emptyList()

    fun start() {
        if (job != null) return
        stopping = false
        job = scope.launch(Dispatchers.IO) {
            var socket: MulticastSocket? = null
            var asked = 0
            var heard = 0
            try {
                val group = InetAddress.getByName(MDNS_ADDR)
                socket = MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(MDNS_PORT))
                    soTimeout = 1000
                    runCatching { timeToLive = 255 }
                    val iface = runCatching { interfaceProvider() }.getOrNull()
                    if (iface != null) {
                        runCatching { joinGroup(InetSocketAddress(group, MDNS_PORT), iface) }
                            .onFailure { @Suppress("DEPRECATION") joinGroup(group) }
                        // Iscriversi su un'interfaccia e spedire da un'altra e'
                        // un modo silenzioso di non ricevere niente: su una
                        // macchina con piu' schede la domanda uscirebbe dalla
                        // rotta di default, che puo' non essere quella giusta.
                        runCatching { networkInterface = iface }
                    } else {
                        @Suppress("DEPRECATION") joinGroup(group)
                    }
                    AppDebug.log("[Snapcast/browse] in ascolto su ${iface?.name ?: "interfaccia di default"}")
                }
                liveSocket = socket

                val queries = listOf(
                    SnapcastDns.buildQuery(SnapcastDns.SERVICE_TYPE),
                    SnapcastDns.buildQuery(SnapcastDns.CONTROL_SERVICE_TYPE)
                )
                val buf = ByteArray(4096)
                val packet = DatagramPacket(buf, buf.size)
                val schedule = SnapcastQuerySchedule()

                // isActive nudo e' quello di QUESTA coroutine, non dello scope
                // che ce l'ha lanciata: e' la differenza fra fermarsi e non
                // fermarsi mai.
                while (isActive && !stopping) {
                    val now = System.currentTimeMillis()
                    // Le risposte non sollecitate da sole non bastano: un server
                    // annuncia raramente, e chi cerca adesso non puo' aspettare
                    // il suo prossimo annuncio.
                    if (schedule.shouldAsk(now)) {
                        asked++
                        queries.forEach { q ->
                            runCatching { socket.send(DatagramPacket(q, q.size, group, MDNS_PORT)) }
                                .onFailure { AppDebug.log("[Snapcast/browse] domanda non spedita: ${it.message}") }
                        }
                    }

                    try {
                        packet.length = buf.size
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        // Il timeout e' il momento buono per fare le pulizie.
                        if (synchronized(index) { index.expire() }) emit()
                        continue
                    } catch (e: Exception) {
                        if (stopping) break
                        throw e
                    }

                    heard++
                    val rec = SnapcastDns.parseResponse(packet.data, packet.length)
                    if (rec.services.isNotEmpty() || rec.addresses.isNotEmpty()) {
                        AppDebug.log("[Snapcast/browse] da ${packet.address?.hostAddress}: " +
                                "${rec.services.size} servizi, ${rec.addresses.size} indirizzi" +
                                (if (rec.goodbyes.isNotEmpty()) ", ${rec.goodbyes.size} addii" else ""))
                    }
                    ingest(rec)
                }
            } catch (e: Exception) {
                AppDebug.log("[Snapcast/browse] interrotto: ${e.message}")
            } finally {
                AppDebug.log("[Snapcast/browse] fine: $asked giri di domande, " +
                             "$heard pacchetti letti, ${lastEmitted.size} server")
                liveSocket = null
                runCatching { socket?.close() }
            }
        }
    }

    fun stop() {
        stopping = true
        // Chiudere il socket sveglia subito la receive bloccata, invece di
        // lasciar scadere il suo secondo di timeout.
        runCatching { liveSocket?.close() }
        job?.cancel()
        job = null
        synchronized(index) { index.clear() }
        lastEmitted = emptyList()
    }

    private fun ingest(rec: SnapcastDnsRecords) {
        synchronized(index) { index.ingest(rec) }
        emit()
    }

    /**
     * Si emette solo quando la lista cambia davvero: le risposte mDNS si
     * ripetono a raffica e ridisegnare la UI a ogni pacchetto la fa tremolare.
     */
    private fun emit() {
        val list = synchronized(index) { index.servers() }
        if (list == lastEmitted) return
        lastEmitted = list
        onServers(list)
    }

    companion object {
        private const val MDNS_ADDR = "224.0.0.251"
        private const val MDNS_PORT = 5353
    }
}
