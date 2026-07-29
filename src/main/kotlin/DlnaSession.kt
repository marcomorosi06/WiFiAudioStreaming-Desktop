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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

enum class DlnaTargetStatus { IDLE, CONNECTING, PLAYING, RETRYING, ERROR, OFFLINE }

data class DlnaTargetState(
    val udn: String,
    val name: String,
    val status: DlnaTargetStatus,
    val detail: String = "",
    val codec: DlnaCodec? = null,
    val negotiated: Boolean = false
)

data class DlnaDiscoveryState(
    val scanning: Boolean = false,
    val renderers: List<DlnaRenderer> = emptyList(),
    val lastScanAt: Long = 0L
)

object DlnaStatus {
    private val state = MutableStateFlow<List<DlnaTargetState>>(emptyList())
    val targets: StateFlow<List<DlnaTargetState>> = state.asStateFlow()

    fun publish(value: List<DlnaTargetState>) {
        state.value = value
    }

    fun clear() {
        state.value = emptyList()
    }
}

object DlnaDiscoveryService {
    private val state = MutableStateFlow(DlnaDiscoveryState())
    val flow: StateFlow<DlnaDiscoveryState> = state.asStateFlow()
    private val mutex = Mutex()

    suspend fun scan(preferred: NetworkInterface?, withProtocolInfo: Boolean = true) {
        if (!mutex.tryLock()) return
        try {
            state.value = state.value.copy(scanning = true)
            val found = withContext(Dispatchers.IO) {
                DlnaRegistry.refresh(preferred, withProtocolInfo)
            }
            state.value = DlnaDiscoveryState(
                scanning = false,
                renderers = found,
                lastScanAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            if (e !is CancellationException) DlnaDiagnostics.record("discovery", "scan failed: ${e.message}")
            state.value = state.value.copy(scanning = false)
        } finally {
            mutex.unlock()
        }
    }

    fun publish(renderers: List<DlnaRenderer>) {
        state.value = state.value.copy(renderers = renderers, lastScanAt = System.currentTimeMillis())
    }

    fun reset() {
        DlnaRegistry.clear()
        state.value = DlnaDiscoveryState()
    }
}

private class DlnaSession(
    val renderer: DlnaRenderer,
    val negotiation: DlnaNegotiation,
    val url: String
) {
    @Volatile
    var status: DlnaTargetStatus = DlnaTargetStatus.CONNECTING

    @Volatile
    var detail: String = ""

    @Volatile
    var failures: Int = 0

    @Volatile
    var nextAttemptAt: Long = 0L
}

class DlnaSessionManager(
    private val scope: CoroutineScope,
    private val sampleRate: Int,
    private val channels: Int,
    private val mediaPort: Int,
    private val preference: DlnaFormatPreference,
    private val selectedUdns: Set<String>,
    private val streamTitle: String,
    private val localAddressProvider: () -> String,
    private val preferredInterfaceProvider: () -> NetworkInterface?
) {
    private val sessions = ConcurrentHashMap<String, DlnaSession>()
    private val quirksByAddress = ConcurrentHashMap<String, DlnaQuirks>()

    private var mediaServer: DlnaMediaServer? = null
    private var supervisorJob: Job? = null

    val availableCodecs: Set<DlnaCodec> by lazy { DlnaCodecSupport.available(sampleRate, channels) }

    fun submitPcm(pcmLittleEndian: ByteArray) {
        mediaServer?.submitPcm(pcmLittleEndian)
    }

    fun activeClientCount(): Int = mediaServer?.clientCount() ?: 0

    fun start() {
        if (selectedUdns.isEmpty()) {
            DlnaDiagnostics.record("session", "no renderer selected, DLNA idle")
        }
        val server = DlnaMediaServer(
            scope = scope,
            port = mediaPort,
            sampleRate = sampleRate,
            channels = channels,
            quirksForAddress = { address -> quirksByAddress[address] ?: DlnaQuirks() }
        )
        mediaServer = server
        server.start()
        supervisorJob = scope.launch(Dispatchers.IO) { supervise() }
    }

    suspend fun stop() {
        supervisorJob?.cancel()
        supervisorJob = null
        sessions.values.forEach { session ->
            runCatching { DlnaActions.stop(session.renderer) }
        }
        sessions.clear()
        quirksByAddress.clear()
        DlnaStatus.clear()
        mediaServer?.stop()
        mediaServer = null
    }

    private suspend fun supervise() {
        var firstPass = true
        while (scope.isActive) {
            try {
                val preferredInterface = preferredInterfaceProvider()
                val discovered = if (firstPass || sessions.size < selectedUdns.size) {
                    DlnaRegistry.refresh(preferredInterface, true)
                } else {
                    DlnaRegistry.snapshot()
                }
                DlnaDiscoveryService.publish(discovered)
                firstPass = false

                selectedUdns.forEach { udn ->
                    val renderer = discovered.firstOrNull { it.udn == udn }
                    if (renderer == null) {
                        markOffline(udn)
                    } else {
                        ensureSession(renderer)
                    }
                }

                sessions.keys.filter { it !in selectedUdns }.forEach { stale ->
                    sessions.remove(stale)?.let { runCatching { DlnaActions.stop(it.renderer) } }
                }

                publishStates()
            } catch (e: Exception) {
                if (e !is CancellationException) DlnaDiagnostics.record("session", "supervisor error: ${e.message}")
            }
            delay(8000)
        }
    }

    private fun markOffline(udn: String) {
        val existing = sessions[udn]
        if (existing != null) {
            existing.status = DlnaTargetStatus.OFFLINE
            existing.detail = "not reachable"
        }
    }

    private fun ensureSession(renderer: DlnaRenderer) {
        val now = System.currentTimeMillis()
        val existing = sessions[renderer.udn]

        if (existing != null && existing.status == DlnaTargetStatus.PLAYING) {
            val state = runCatching { DlnaActions.transportState(existing.renderer) }.getOrNull()
            if (state == null) {
                existing.status = DlnaTargetStatus.RETRYING
                existing.detail = "no answer from renderer"
            } else if (state.equals("PLAYING", true) || state.equals("TRANSITIONING", true)) {
                existing.failures = 0
                existing.detail = state.lowercase()
                return
            } else {
                existing.status = DlnaTargetStatus.RETRYING
                existing.detail = "transport state $state"
            }
        }

        if (existing != null && now < existing.nextAttemptAt) return

        val negotiation = DlnaNegotiator.negotiate(
            renderer = renderer,
            available = availableCodecs,
            preference = preference,
            sampleRate = sampleRate,
            channels = channels
        )
        if (negotiation == null) {
            val session = existing ?: DlnaSession(renderer, DlnaNegotiation(DlnaCodec.LPCM, "", "", false, ""), "")
            session.status = DlnaTargetStatus.ERROR
            session.detail = "no compatible format"
            sessions[renderer.udn] = session
            return
        }

        val host = localAddressProvider()
        if (host.isBlank()) {
            DlnaDiagnostics.record("session", "local address unavailable, cannot build media URL")
            return
        }
        val url = "http://${NetAddr.hostPort(host, mediaPort)}${negotiation.codec.path}"
        quirksByAddress[renderer.address] = renderer.quirks

        val session = DlnaSession(renderer, negotiation, url)
        session.status = DlnaTargetStatus.CONNECTING
        session.detail = negotiation.reason
        session.failures = existing?.failures ?: 0
        sessions[renderer.udn] = session

        DlnaDiagnostics.record(
            "session",
            "pushing to ${renderer.displayName} codec=${negotiation.codec.id} mime=${negotiation.mime} " +
                    "pn=${negotiation.profileName} negotiated=${negotiation.negotiated} url=$url quirks=${renderer.quirks.name}"
        )

        val didl = DlnaDidl.build(renderer, negotiation, url, streamTitle)
        val ok = runCatching { DlnaActions.setUriAndPlay(renderer, url, didl) }.getOrDefault(false)

        if (ok) {
            session.status = DlnaTargetStatus.PLAYING
            session.detail = negotiation.codec.label
            session.failures = 0
            session.nextAttemptAt = 0L
        } else {
            session.failures += 1
            session.status = if (session.failures >= 4) DlnaTargetStatus.ERROR else DlnaTargetStatus.RETRYING
            session.detail = "handshake failed (${session.failures})"
            val backoff = (2000L * session.failures).coerceAtMost(30000L)
            session.nextAttemptAt = now + backoff
        }
    }

    private fun publishStates() {
        val list = selectedUdns.map { udn ->
            val session = sessions[udn]
            val renderer = session?.renderer ?: DlnaRegistry.byUdn(udn)
            DlnaTargetState(
                udn = udn,
                name = renderer?.displayName ?: udn,
                status = session?.status ?: DlnaTargetStatus.OFFLINE,
                detail = session?.detail.orEmpty(),
                codec = session?.negotiation?.codec,
                negotiated = session?.negotiation?.negotiated ?: false
            )
        }
        DlnaStatus.publish(list)
    }
}
