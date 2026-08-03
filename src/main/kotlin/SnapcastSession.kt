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
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

data class SnapcastServerConfig(
    val enabled: Boolean = false,
    val streamPort: Int = SnapcastDefaults.STREAM_PORT,
    val controlPort: Int = SnapcastDefaults.CONTROL_PORT,
    val codec: String = SnapcastCodecs.PCM,
    val chunkMs: Int = SnapcastDefaults.CHUNK_MS,
    val bufferMs: Int = SnapcastDefaults.BUFFER_MS,
    val streamName: String = SnapcastDefaults.STREAM_NAME,
    val flacCompression: Int = SnapcastDefaults.FLAC_COMPRESSION,
    val opusBitrate: Int = SnapcastDefaults.OPUS_BITRATE
)

object SnapcastDefaults {
    const val STREAM_PORT = 1704
    const val CONTROL_PORT = 1705
    const val CHUNK_MS = 20
    const val BUFFER_MS = 1000
    const val STREAM_NAME = "default"
    const val FLAC_COMPRESSION = 2
    const val OPUS_BITRATE = 192_000
    val CHUNK_CHOICES = listOf(10, 20, 40, 60)
    const val MIN_BUFFER_MS = 200
    const val MAX_BUFFER_MS = 5000
}

data class SnapcastClientView(
    val id: String,
    val name: String,
    val ip: String,
    val volumePercent: Int,
    val muted: Boolean,
    val latency: Int,
    val connected: Boolean
)

data class SnapcastSessionState(
    val running: Boolean = false,
    val codec: String = SnapcastCodecs.PCM,
    val requestedCodec: String = SnapcastCodecs.PCM,
    val streamPort: Int = SnapcastDefaults.STREAM_PORT,
    val controlPort: Int = SnapcastDefaults.CONTROL_PORT,
    val sampleFormat: String = "",
    val clients: List<SnapcastClientView> = emptyList(),
    val controlConnections: Int = 0,
    val driftMicros: Long = 0L,
    val lastError: String = "",
    val serverIp: String = "",
    val streamBound: Boolean = false,
    val controlBound: Boolean = false,
    val discoveryActive: Boolean = false,
    val bindError: String = "",
    val discoveryError: String = ""
)

object SnapcastStatus {

    private val state = MutableStateFlow(SnapcastSessionState())
    val session: StateFlow<SnapcastSessionState> = state.asStateFlow()

    fun publish(value: SnapcastSessionState) {
        state.value = value
    }

    fun clear() {
        state.value = SnapcastSessionState()
    }
}

class SnapcastSessionManager(
    private val scope: CoroutineScope,
    private val config: SnapcastServerConfig,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitDepth: Int,
    private val hostName: String,
    private val serverVersion: String,
    private val persistenceFile: File?,
    private val localAddressProvider: () -> String?,
    private val preferredInterfaceProvider: () -> NetworkInterface?,
    private val log: (String) -> Unit
) {

    private val format = SnapcastAudioFormat(sampleRate, bitDepth, channels)
    private val clock = SnapcastSteadyClock()
    private val streamClock = SnapcastStreamClock(clock, sampleRate)
    private val pcmQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    private val chunkBytes: Int = (format.bytesPerMs * config.chunkMs).coerceAtLeast(format.frameBytes)
    private val pending = ByteArray(chunkBytes)
    private var pendingSize = 0
    private var publishedHeader: ByteArray = ByteArray(0)

    private var state: SnapcastState? = null
    private var streamServer: SnapcastStreamServer? = null
    private var controlServer: SnapcastControlServer? = null
    private var mdns: SnapcastMdnsResponder? = null
    private var encoder: SnapcastEncoder? = null
    private var pumpJob: Job? = null
    private var statusJob: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var fallbackNotice = ""

    fun start() {
        if (running) return
        running = true

        val host = SnapcastHostInfo(
            name = hostName,
            mac = localMacAddress(),
            os = ConfigPaths.osLabel,
            arch = System.getProperty("os.arch", "unknown"),
            ip = localAddressProvider() ?: ""
        )

        val activeState = SnapcastState(host, serverVersion, persistenceFile, config.streamName)
        state = activeState

        val activeEncoder = SnapcastCodecs.create(
            codec = config.codec,
            format = format,
            flacCompression = config.flacCompression,
            opusBitrate = config.opusBitrate,
            opusFrameMs = config.chunkMs,
            onFallback = { requested ->
                fallbackNotice = requested
                log("[Snapcast] codec $requested unavailable for ${format.describe()}, using pcm")
            }
        )
        encoder = activeEncoder

        val stream = SnapcastStreamServer(
            scope = scope,
            port = config.streamPort,
            state = activeState,
            clock = clock,
            bufferMs = config.bufferMs.coerceIn(SnapcastDefaults.MIN_BUFFER_MS, SnapcastDefaults.MAX_BUFFER_MS),
            log = log
        )
        streamServer = stream
        stream.start()
        publishHeaderIfReady()

        val control = SnapcastControlServer(
            scope = scope,
            port = config.controlPort,
            state = activeState,
            log = log
        )
        controlServer = control
        control.start()

        activeState.updateStream(
            codec = activeEncoder.codecName,
            sampleFormat = activeEncoder.outputFormat.describe(),
            chunkMs = config.chunkMs,
            status = "playing"
        )

        val instance = mdnsInstanceName()
        mdns = SnapcastMdnsResponder(
            scope = scope,
            hostName = hostName,
            services = listOf(
                SnapcastMdnsService(
                    instance = instance,
                    type = SnapcastMdnsResponder.SERVICE_STREAM,
                    port = config.streamPort,
                    txt = listOf(
                        "codec=${activeEncoder.codecName}",
                        "sampleformat=${activeEncoder.outputFormat.describe()}",
                        "name=${config.streamName}"
                    )
                ),
                SnapcastMdnsService(
                    instance = instance,
                    type = SnapcastMdnsResponder.SERVICE_CONTROL,
                    port = config.controlPort,
                    txt = listOf("version=$serverVersion")
                )
            ),
            addressProvider = localAddressProvider,
            interfaceProvider = preferredInterfaceProvider,
            log = log
        ).also { it.start() }

        pumpJob = scope.launch(Dispatchers.IO) { pumpLoop() }
        statusJob = scope.launch { statusLoop() }
        log("[Snapcast] server started on ${config.streamPort}/${config.controlPort} codec=${activeEncoder.codecName}")
    }

    fun stop() {
        if (!running) return
        running = false
        pumpJob?.cancel()
        pumpJob = null
        statusJob?.cancel()
        statusJob = null
        runCatching { mdns?.stop() }
        mdns = null
        runCatching { controlServer?.stop() }
        controlServer = null
        runCatching { streamServer?.stop() }
        streamServer = null
        runCatching { encoder?.close() }
        encoder = null
        state?.updateStream(config.codec, format.describe(), config.chunkMs, "idle")
        state = null
        pcmQueue.clear()
        pendingSize = 0
        publishedHeader = ByteArray(0)
        streamClock.reset()
        SnapcastStatus.clear()
        log("[Snapcast] server stopped")
    }

    fun submitPcm(data: ByteArray) {
        if (!running) return
        if (!pcmQueue.offer(data)) {
            pcmQueue.poll()
            pcmQueue.offer(data)
        }
    }

    fun activeClientCount(): Int = streamServer?.activeClientCount() ?: 0

    private fun publishHeaderIfReady() {
        val activeEncoder = encoder ?: return
        val server = streamServer ?: return
        val header = activeEncoder.header
        if (header.isEmpty() || header.contentEquals(publishedHeader)) return
        publishedHeader = header.copyOf()
        server.setCodec(activeEncoder.codecName, header)
    }

    private fun pumpLoop() {
        try {
            while (scope.isActive && running) {
                val block = pcmQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                consume(block)
            }
        } catch (e: Exception) {
            if (e !is CancellationException) log("[Snapcast] pump ended: ${e.message}")
        }
    }

    private fun consume(block: ByteArray) {
        val activeEncoder = encoder ?: return
        val server = streamServer ?: return
        var offset = 0
        while (offset < block.size) {
            val take = minOf(chunkBytes - pendingSize, block.size - offset)
            System.arraycopy(block, offset, pending, pendingSize, take)
            pendingSize += take
            offset += take
            if (pendingSize == chunkBytes) {
                activeEncoder.encode(pending, 0, chunkBytes) { payload, frames ->
                    val timestamp = streamClock.timestampFor(frames)
                    server.broadcastChunk(timestamp, payload)
                }
                publishHeaderIfReady()
                pendingSize = 0
            }
        }
    }

    private suspend fun statusLoop() {
        while (scope.isActive && running) {
            val activeState = state
            val clients = activeState?.allClients()?.map { client ->
                SnapcastClientView(
                    id = client.id,
                    name = client.config.name.ifBlank { client.host.name.ifBlank { client.id } },
                    ip = client.host.ip,
                    volumePercent = client.config.volumePercent,
                    muted = client.config.muted,
                    latency = client.config.latency,
                    connected = client.connected
                )
            } ?: emptyList()
            SnapcastStatus.publish(
                SnapcastSessionState(
                    running = true,
                    codec = encoder?.codecName ?: config.codec,
                    requestedCodec = config.codec,
                    streamPort = config.streamPort,
                    controlPort = config.controlPort,
                    sampleFormat = encoder?.outputFormat?.describe() ?: format.describe(),
                    clients = clients,
                    controlConnections = controlServer?.activeConnections() ?: 0,
                    driftMicros = streamClock.lastDriftMicros,
                    lastError = fallbackNotice,
                    serverIp = localAddressProvider() ?: "",
                    streamBound = streamServer?.bound == true,
                    controlBound = controlServer?.bound == true,
                    discoveryActive = mdns?.active == true,
                    bindError = listOfNotNull(
                        streamServer?.bindError?.takeIf { it.isNotBlank() },
                        controlServer?.bindError?.takeIf { it.isNotBlank() }
                    ).joinToString("; "),
                    discoveryError = mdns?.lastError ?: ""
                )
            )
            delay(1000L)
        }
    }

    private fun mdnsInstanceName(): String {
        val base = hostName.filter { it.isLetterOrDigit() || it == '-' || it == ' ' }.trim()
        return if (base.isEmpty()) "WiFi Audio Streaming" else base
    }

    private fun localMacAddress(): String = runCatching {
        val ip = localAddressProvider()
        val iface = if (ip != null) NetworkInterface.getByInetAddress(java.net.InetAddress.getByName(ip))
        else preferredInterfaceProvider()
        val bytes = iface?.hardwareAddress ?: return@runCatching ""
        bytes.joinToString(":") { String.format("%02x", it) }
    }.getOrDefault("")

    companion object {
        private const val QUEUE_CAPACITY = 64
    }
}
