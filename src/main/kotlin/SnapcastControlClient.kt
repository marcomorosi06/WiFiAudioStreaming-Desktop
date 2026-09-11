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
 * SnapcastControlClient.kt
 *
 * Canale di controllo di un server Snapcast: JSON-RPC 2.0 su TCP (porta 1705),
 * un messaggio per riga.
 *
 * E' quello che permette di vedere e comandare TUTTI i client collegati al
 * server, non solo il nostro: volumi, mute, nomi, latenza, composizione dei
 * gruppi e stream sorgente.
 *
 * Il server manda anche notifiche non sollecitate quando qualcosa cambia (un
 * altro telefono alza il volume, un client si collega). Vengono applicate allo
 * stato locale senza richiedere di nuovo tutto: e' cio' che fa muovere gli
 * slider da soli mentre qualcun altro tocca le cose.
 *
 * Il parsing sta in [SnapcastControlModel], separato dalla rete, perche' e'
 * logica pura e va provata su JSON veri.
 */

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/* ── Modello ───────────────────────────────────────────────────────────────*/

data class SnapClientInfo(
    val id: String,
    val name: String,
    val hostName: String,
    val ip: String,
    val connected: Boolean,
    val volumePercent: Int,
    val muted: Boolean,
    val latencyMs: Int,
    val os: String = "",
    val arch: String = "",
    val version: String = ""
) {
    /** Nome da mostrare: quello scelto dall'utente, se c'e'; altrimenti l'host. */
    fun displayName(): String = name.ifBlank { hostName.ifBlank { ip.ifBlank { id } } }
}

data class SnapGroupInfo(
    val id: String,
    val name: String,
    val muted: Boolean,
    val streamId: String,
    val clients: List<SnapClientInfo>
) {
    fun displayName(): String = name.ifBlank {
        clients.firstOrNull()?.displayName()?.let { "$it…" } ?: id.take(8)
    }
}

data class SnapStreamInfo(
    val id: String,
    val status: String,
    val uri: String = ""
)

data class SnapServerStatus(
    val groups: List<SnapGroupInfo> = emptyList(),
    val streams: List<SnapStreamInfo> = emptyList(),
    val serverName: String = "",
    val serverVersion: String = ""
) {
    val allClients: List<SnapClientInfo> get() = groups.flatMap { it.clients }
    fun client(id: String): SnapClientInfo? = allClients.firstOrNull { it.id == id }
}

/* ── Parsing ───────────────────────────────────────────────────────────────*/

object SnapcastControlModel {

    /** Legge la risposta di `Server.GetStatus` (o una notifica `Server.OnUpdate`). */
    fun parseStatus(json: SnapJson?): SnapServerStatus? {
        // Server.GetStatus: {result:{server:{...}}}; Server.OnUpdate: {params:{server:{...}}}
        val server = json.field("result").field("server")
            ?: json.field("params").field("server")
            ?: json.field("server")
            ?: return null

        val groups = server.field("groups").asArray().mapNotNull { parseGroup(it) }
        val streams = server.field("streams").asArray().mapNotNull { s ->
            val id = s.stringAt("id") ?: return@mapNotNull null
            SnapStreamInfo(
                id = id,
                status = s.stringAt("status") ?: "",
                uri = s.field("uri").stringAt("raw") ?: ""
            )
        }
        val srv = server.field("server")
        return SnapServerStatus(
            groups = groups,
            streams = streams,
            serverName = srv.field("host").stringAt("name") ?: "",
            serverVersion = srv.field("snapserver").stringAt("version") ?: ""
        )
    }

    fun parseGroup(g: SnapJson?): SnapGroupInfo? {
        val id = g.stringAt("id") ?: return null
        return SnapGroupInfo(
            id = id,
            name = g.stringAt("name") ?: "",
            muted = g.boolAt("muted") ?: false,
            streamId = g.stringAt("stream_id") ?: "",
            clients = g.field("clients").asArray().mapNotNull { parseClient(it) }
        )
    }

    fun parseClient(c: SnapJson?): SnapClientInfo? {
        val id = c.stringAt("id") ?: return null
        val config = c.field("config")
        val volume = config.field("volume")
        val host = c.field("host")
        val snapclient = c.field("snapclient")
        return SnapClientInfo(
            id = id,
            name = config.stringAt("name") ?: "",
            hostName = host.stringAt("name") ?: "",
            ip = host.stringAt("ip") ?: "",
            connected = c.boolAt("connected") ?: false,
            volumePercent = volume.intAt("percent") ?: 100,
            muted = volume.boolAt("muted") ?: false,
            latencyMs = config.intAt("latency") ?: 0,
            os = host.stringAt("os") ?: "",
            arch = host.stringAt("arch") ?: "",
            version = snapclient.stringAt("version") ?: ""
        )
    }

    /**
     * Applica una notifica allo stato corrente.
     * Restituisce null se la notifica non riguarda lo stato (o non si capisce),
     * cosi' il chiamante sa che non deve ridisegnare nulla.
     */
    fun applyNotification(current: SnapServerStatus, method: String, params: SnapJson?): SnapServerStatus? {
        fun mapClients(f: (SnapClientInfo) -> SnapClientInfo) =
            current.copy(groups = current.groups.map { g -> g.copy(clients = g.clients.map(f)) })

        return when (method) {
            "Client.OnVolumeChanged" -> {
                val id = params.stringAt("id") ?: return null
                val vol = params.field("volume")
                val percent = vol.intAt("percent")
                val muted = vol.boolAt("muted")
                mapClients { if (it.id == id)
                    it.copy(volumePercent = percent ?: it.volumePercent, muted = muted ?: it.muted) else it }
            }

            "Client.OnLatencyChanged" -> {
                val id = params.stringAt("id") ?: return null
                val lat = params.intAt("latency") ?: return null
                mapClients { if (it.id == id) it.copy(latencyMs = lat) else it }
            }

            "Client.OnNameChanged" -> {
                val id = params.stringAt("id") ?: return null
                val name = params.stringAt("name") ?: return null
                mapClients { if (it.id == id) it.copy(name = name) else it }
            }

            "Client.OnConnect", "Client.OnDisconnect" -> {
                val updated = parseClient(params.field("client")) ?: return null
                val exists = current.allClients.any { it.id == updated.id }
                if (!exists) return null      // client nuovo: serve uno stato completo
                mapClients { if (it.id == updated.id) updated else it }
            }

            "Group.OnMute" -> {
                val id = params.stringAt("id") ?: return null
                val mute = params.boolAt("mute") ?: return null
                current.copy(groups = current.groups.map { if (it.id == id) it.copy(muted = mute) else it })
            }

            "Group.OnStreamChanged" -> {
                val id = params.stringAt("id") ?: return null
                val stream = params.stringAt("stream_id") ?: return null
                current.copy(groups = current.groups.map { if (it.id == id) it.copy(streamId = stream) else it })
            }

            "Group.OnNameChanged" -> {
                val id = params.stringAt("id") ?: return null
                val name = params.stringAt("name") ?: return null
                current.copy(groups = current.groups.map { if (it.id == id) it.copy(name = name) else it })
            }

            "Stream.OnUpdate" -> {
                val s = params.field("stream")
                val id = s.stringAt("id") ?: return null
                val status = s.stringAt("status") ?: ""
                val list = current.streams.toMutableList()
                val idx = list.indexOfFirst { it.id == id }
                if (idx >= 0) list[idx] = list[idx].copy(status = status)
                else list += SnapStreamInfo(id, status)
                current.copy(streams = list)
            }

            // Server.OnUpdate porta lo stato intero: lo gestisce il chiamante
            // con parseStatus, qui non c'e' nulla da fondere.
            else -> null
        }
    }
}

/* ── Client ────────────────────────────────────────────────────────────────*/

enum class SnapControlState { IDLE, CONNECTING, CONNECTED, ERROR }

data class SnapControlStatus(
    val state: SnapControlState = SnapControlState.IDLE,
    val status: SnapServerStatus = SnapServerStatus(),
    /**
     * Messaggio grezzo dell'eccezione, piu' l'indirizzo a cui si stava
     * provando. Va mostrato in chiaro nella UI: "non raggiungibile" da solo non
     * distingue un rifiuto della connessione da un timeout o da una caduta
     * dopo l'apertura, che sono tre problemi con tre soluzioni diverse.
     */
    val errorDetail: String? = null,
    val host: String = "",
    val port: Int = 0,
    /**
     * Quanti tentativi di connessione sono stati fatti. Se resta a 1 mentre
     * l'errore persiste, il ciclo di ritentativi non sta girando: e' un difetto
     * diverso dal non riuscire a connettersi.
     */
    val attempts: Int = 0
)

class SnapcastControlClient(
    private val host: String,
    private val port: Int,
    private val onStatus: (SnapControlStatus) -> Unit
) {
    private var job: Job? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var stopping = false
    private val nextId = AtomicInteger(1)
    private val attempts = AtomicInteger(0)

    @Volatile private var current = SnapServerStatus()

    private companion object {
        /** Quanto vive un'intenzione non ancora confermata dal server. */
        const val INTENT_TTL_MS = 2_500L
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        stopping = false
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !stopping) {
                try {
                    runSession(this)
                } catch (e: Exception) {
                    if (!stopping) {
                        AppDebug.log("[Snapcast/ctrl] tentativo ${attempts.get()} fallito: ${describe(e)}")
                        publish(SnapControlState.ERROR, describe(e))
                    }
                }
                if (stopping) break
                // Il controllo e' accessorio: se cade, l'audio continua. Si
                // riprova con calma invece di martellare.
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    private fun runSession(scope: CoroutineScope) {
        val attempt = attempts.incrementAndGet()
        publish(SnapControlState.CONNECTING)

        // L'indirizzo si risolve prima e si logga: se quello che finisce nel
        // socket non e' quello che la UI mostra, si vede qui e non altrove.
        val target = InetSocketAddress(host, port)
        AppDebug.log("[Snapcast/ctrl] tentativo $attempt verso $target " +
                     "(risolto=${target.address?.hostAddress ?: "NON RISOLTO"})")

        val sock = Socket()
        try {
            sock.connect(target, 4000)
            sock.tcpNoDelay = true
            sock.soTimeout = 0
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw e
        }
        socket = sock
        val out = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
        writer = out
        val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))

        request("Server.GetStatus", null)

        try {
            var lines = 0
            while (scope.isActive && !stopping) {
                val line = input.readLine() ?: break
                if (line.isBlank()) continue
                lines++
                handleLine(line)
            }
            // Connessione aperta ma chiusa dall'altra parte senza dire niente:
            // e' un caso diverso dal "non raggiungibile" e va distinto.
            if (!stopping && lines == 0) {
                publish(SnapControlState.ERROR, "connesso, ma il server ha chiuso senza rispondere")
            }
        } finally {
            runCatching { sock.close() }
            socket = null
            writer = null
            if (!stopping) publish(SnapControlState.IDLE)
        }
    }

    private fun describe(e: Throwable): String {
        val cause = generateSequence(e) { it.cause }.last()
        val base = "${e.javaClass.simpleName}: ${e.message ?: "(nessun messaggio)"}"
        return if (cause !== e) "$base  ←  ${cause.javaClass.simpleName}: ${cause.message}" else base
    }

    private fun handleLine(line: String) {
        val json = SnapJson.parse(line) ?: return

        // Risposta a Server.GetStatus, oppure notifica Server.OnUpdate.
        SnapcastControlModel.parseStatus(json)?.let {
            current = it
            settle(it)
            publish(SnapControlState.CONNECTED)
            return
        }

        val method = json.stringAt("method") ?: return
        val updated = SnapcastControlModel.applyNotification(current, method, json.field("params"))
        if (updated != null) {
            current = updated
            settle(updated)
            publish(SnapControlState.CONNECTED)
        } else {
            // Notifica che cambia la struttura (client nuovo, gruppo nuovo):
            // si richiede lo stato intero invece di indovinare.
            if (method.startsWith("Client.On") || method.startsWith("Group.On") ||
                method.startsWith("Server.On")) {
                runCatching { request("Server.GetStatus", null) }
            }
        }
    }

    private fun request(method: String, paramsJson: String?) {
        val w = writer ?: return
        val id = nextId.getAndIncrement()
        val msg = buildString {
            append("{\"id\":").append(id)
            append(",\"jsonrpc\":\"2.0\",\"method\":\"").append(SnapJson.escape(method)).append('"')
            if (paramsJson != null) append(",\"params\":").append(paramsJson)
            append('}')
        }
        synchronized(w) {
            w.write(msg); w.write("\r\n"); w.flush()
        }
    }

    /* ── Intenzioni in volo ────────────────────────────────────────────────
     *
     * Quando si comanda qualcosa, il server risponde con la sua notifica — ma
     * puo' anche spedire, nello stesso momento, una fotografia dello stato
     * scattata PRIMA che il comando arrivasse. Applicarla riporta il controllo
     * al valore vecchio, e un attimo dopo la notifica lo rimanda avanti: e'
     * quello che si vede come lampeggio.
     *
     * La cura non puo' stare nella UI, perche' li' andrebbe ripetuta per ogni
     * singolo controllo — volume, mute, latenza, nome, mute di gruppo, stream —
     * e ne dimenticheresti sempre uno. Sta qui: quello che abbiamo appena
     * chiesto vince su qualunque cosa arrivi, finche' il server non conferma
     * esattamente quel valore o finche' non scade il tempo.
     *
     * Lo stato "vero" del server resta intatto in [current]: la sovrapposizione
     * si applica solo al momento di pubblicare, quindi appena l'intenzione
     * scade o viene confermata si torna a mostrare la verita' senza scatti.
     * ─────────────────────────────────────────────────────────────────────── */

    private data class ClientIntent(
        val volumePercent: Int? = null,
        val muted: Boolean? = null,
        val latencyMs: Int? = null,
        val name: String? = null,
        val expiresAt: Long
    )

    private data class GroupIntent(
        val muted: Boolean? = null,
        val streamId: String? = null,
        val name: String? = null,
        val expiresAt: Long
    )

    private val clientIntents = ConcurrentHashMap<String, ClientIntent>()
    private val groupIntents = ConcurrentHashMap<String, GroupIntent>()

    private fun deadline() = System.currentTimeMillis() + INTENT_TTL_MS

    private fun noteClient(id: String, block: (ClientIntent) -> ClientIntent) {
        val base = clientIntents[id]?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?: ClientIntent(expiresAt = deadline())
        clientIntents[id] = block(base).copy(expiresAt = deadline())
        publish(SnapControlState.CONNECTED)
    }

    private fun noteGroup(id: String, block: (GroupIntent) -> GroupIntent) {
        val base = groupIntents[id]?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?: GroupIntent(expiresAt = deadline())
        groupIntents[id] = block(base).copy(expiresAt = deadline())
        publish(SnapControlState.CONNECTED)
    }

    /** Toglie le intenzioni che il server ha gia' recepito: da li' comanda lui. */
    private fun settle(truth: SnapServerStatus) {
        clientIntents.entries.removeIf { (id, i) ->
            val c = truth.client(id) ?: return@removeIf false
            (i.volumePercent == null || i.volumePercent == c.volumePercent) &&
            (i.muted == null || i.muted == c.muted) &&
            (i.latencyMs == null || i.latencyMs == c.latencyMs) &&
            (i.name == null || i.name == c.name)
        }
        groupIntents.entries.removeIf { (id, i) ->
            val g = truth.groups.firstOrNull { it.id == id } ?: return@removeIf false
            (i.muted == null || i.muted == g.muted) &&
            (i.streamId == null || i.streamId == g.streamId) &&
            (i.name == null || i.name == g.name)
        }
    }

    private fun overlay(truth: SnapServerStatus): SnapServerStatus {
        val now = System.currentTimeMillis()
        clientIntents.entries.removeIf { it.value.expiresAt <= now }
        groupIntents.entries.removeIf { it.value.expiresAt <= now }
        if (clientIntents.isEmpty() && groupIntents.isEmpty()) return truth

        return truth.copy(groups = truth.groups.map { g ->
            val gi = groupIntents[g.id]
            val merged = if (gi == null) g else g.copy(
                muted = gi.muted ?: g.muted,
                streamId = gi.streamId ?: g.streamId,
                name = gi.name ?: g.name
            )
            merged.copy(clients = merged.clients.map { c ->
                val ci = clientIntents[c.id] ?: return@map c
                c.copy(
                    volumePercent = ci.volumePercent ?: c.volumePercent,
                    muted = ci.muted ?: c.muted,
                    latencyMs = ci.latencyMs ?: c.latencyMs,
                    name = ci.name ?: c.name
                )
            })
        })
    }

    /** Unico punto da cui lo stato esce verso la UI. */
    private fun publish(
        state: SnapControlState,
        detail: String? = null
    ) = onStatus(
        SnapControlStatus(state, overlay(current), detail, host, port, attempts.get())
    )

    // ── Comandi ────────────────────────────────────────────────────────────

    fun setClientVolume(clientId: String, percent: Int, muted: Boolean) = runCatching {
        val p = percent.coerceIn(0, 100)
        // L'intenzione si registra PRIMA di spedire. Il server puo' rispondere
        // cosi' in fretta che la sua fotografia arriva mentre questo metodo non
        // ha ancora finito: registrandola dopo, quel primo fotogramma vecchio
        // passa lo stesso ed e' il lampeggio che si vede.
        noteClient(clientId) { it.copy(volumePercent = p, muted = muted) }
        request("Client.SetVolume", SnapJsonWriter.write {
            put("id", clientId)
            obj("volume") {
                put("muted", muted)
                put("percent", p)
            }
        })
    }.isSuccess

    fun setClientName(clientId: String, name: String) = runCatching {
        noteClient(clientId) { it.copy(name = name) }
        request("Client.SetName", SnapJsonWriter.write {
            put("id", clientId); put("name", name)
        })
    }.isSuccess

    fun setClientLatency(clientId: String, latencyMs: Int) = runCatching {
        val l = latencyMs.coerceIn(-2000, 2000)
        noteClient(clientId) { it.copy(latencyMs = l) }
        request("Client.SetLatency", SnapJsonWriter.write {
            put("id", clientId); put("latency", l)
        })
    }.isSuccess

    fun setGroupMute(groupId: String, mute: Boolean) = runCatching {
        noteGroup(groupId) { it.copy(muted = mute) }
        request("Group.SetMute", SnapJsonWriter.write {
            put("id", groupId); put("mute", mute)
        })
    }.isSuccess

    fun setGroupName(groupId: String, name: String) = runCatching {
        noteGroup(groupId) { it.copy(name = name) }
        request("Group.SetName", SnapJsonWriter.write {
            put("id", groupId); put("name", name)
        })
    }.isSuccess

    fun setGroupStream(groupId: String, streamId: String) = runCatching {
        noteGroup(groupId) { it.copy(streamId = streamId) }
        request("Group.SetStream", SnapJsonWriter.write {
            put("id", groupId); put("stream_id", streamId)
        })
    }.isSuccess

    fun setGroupClients(groupId: String, clientIds: List<String>) = runCatching {
        request("Group.SetClients", SnapJsonWriter.write {
            put("id", groupId)
            arrayOfStrings("clients", clientIds)
        })
    }.isSuccess

    fun refresh() = runCatching { request("Server.GetStatus", null) }.isSuccess

    fun stop() {
        stopping = true
        runCatching { socket?.close() }
        job?.cancel()
        job = null
        clientIntents.clear(); groupIntents.clear()
        onStatus(SnapControlStatus(SnapControlState.IDLE, host = host, port = port))
    }
}
