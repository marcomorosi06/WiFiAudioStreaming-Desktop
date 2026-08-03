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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SnapcastStreamServer(
    private val scope: CoroutineScope,
    private val port: Int,
    private val state: SnapcastState,
    private val clock: SnapcastSteadyClock,
    private val bufferMs: Int,
    private val log: (String) -> Unit
) : SnapcastStateListener {

    private val sessions = ConcurrentHashMap<Long, Session>()
    private val sessionSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val messageId = AtomicInteger(0)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    var bound: Boolean = false
        private set

    @Volatile
    var bindError: String = ""
        private set

    @Volatile
    private var codecName: String = SnapcastCodecs.PCM

    @Volatile
    private var codecHeaderFrame: ByteArray? = null

    fun start() {
        state.addListener(this)
        acceptJob = scope.launch(Dispatchers.IO) {
            val socket = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            }.getOrElse {
                bindError = it.message ?: it.javaClass.simpleName
                log("[Snapcast] stream port $port unavailable: $bindError")
                return@launch
            }
            serverSocket = socket
            bound = true
            bindError = ""
            log("[Snapcast] stream server listening on $port")
            try {
                while (isActive) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    val id = sessionSequence.incrementAndGet()
                    val session = Session(id, client)
                    sessions[id] = session
                    session.start()
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    fun stop() {
        state.removeListener(this)
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        bound = false
        sessions.values.toList().forEach { it.close() }
        sessions.clear()
        codecHeaderFrame = null
    }

    fun setCodec(name: String, header: ByteArray) {
        codecName = name
        codecHeaderFrame = SnapcastWire.frame(
            SnapcastMessageType.CODEC_HEADER,
            nextId(),
            0,
            clock.now(),
            SnapcastTv(0, 0),
            SnapcastWire.codecHeaderPayload(name, header)
        )
        sessions.values.toList().forEach { session ->
            if (session.helloReceived) session.sendCodecHeader()
        }
    }

    fun broadcastChunk(timestamp: SnapcastTv, payload: ByteArray) {
        if (sessions.isEmpty()) return
        println("[DEBUG] broadcastChunk: generating chunk for timestamp ${timestamp.sec}.${timestamp.usec}")
        val frame = SnapcastWire.frame(
            SnapcastMessageType.WIRE_CHUNK,
            nextId(),
            0,
            clock.now(),
            SnapcastTv(0, 0),
            SnapcastWire.wireChunkPayload(timestamp, payload, 0, payload.size)
        )
        sessions.values.forEach { session -> if (session.streaming) session.enqueue(frame) }
    }

    fun activeClientCount(): Int = sessions.values.count { it.streaming }

    override fun onClientSettingsChanged(clientId: String) {
        sessions.values.toList().forEach { session ->
            if (session.clientId == clientId) session.sendServerSettings()
        }
    }

    override fun onServerUpdate() = Unit

    override fun onNotification(method: String, paramsJson: String) = Unit

    private fun nextId(): Int = messageId.incrementAndGet() and 0xFFFF

    private inner class Session(private val sessionId: Long, private val socket: Socket) {

        private val outgoing = ArrayBlockingQueue<ByteArray>(OUTGOING_CAPACITY)
        private val priority = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        private var readerJob: Job? = null
        private var writerJob: Job? = null

        @Volatile
        var clientId: String? = null
            private set

        @Volatile
        var helloReceived: Boolean = false
            private set

        @Volatile
        var streaming: Boolean = false
            private set

        @Volatile
        private var closed = false

        fun start() {
            runCatching {
                socket.tcpNoDelay = true
                socket.keepAlive = true
            }
            writerJob = scope.launch(Dispatchers.IO) { writeLoop() }
            readerJob = scope.launch(Dispatchers.IO) { readLoop() }
        }

        private fun writeLoop() {
            val output = runCatching { BufferedOutputStream(socket.getOutputStream(), 64 * 1024) }.getOrNull() ?: return
            try {
                var chunksSent = 0
                while (scope.isActive && !closed) {
                    var wrote = false
                    while (true) {
                        val urgent = priority.poll() ?: break
                        output.write(urgent)
                        wrote = true
                    }
                    if (wrote) output.flush()
                    var frame = outgoing.poll(20, TimeUnit.MILLISECONDS)
                    if (frame != null && frame.size >= 2) {
                        val type = (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8)
                        if (type == SnapcastMessageType.WIRE_CHUNK) {
                            chunksSent++
                            if (chunksSent % 50 == 0) {
                                println("[DEBUG] Session $sessionId sent $chunksSent chunks so far.")
                            }
                        }
                    }
                    if (frame == null) continue

                    while (true) {
                        val urgent = priority.poll() ?: break
                        output.write(urgent)
                    }
                    output.write(frame)
                    var batched = 0
                    while (batched < MAX_BATCH) {
                        val more = outgoing.poll() ?: break
                        output.write(more)
                        batched++
                    }
                    output.flush()
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    println("[DEBUG] Snapcast session $sessionId write ended: ${e.message}")
                    e.printStackTrace()
                }
            } finally {
                close()
            }
        }

        private fun readLoop() {
            val input = runCatching { BufferedInputStream(socket.getInputStream(), 16 * 1024) }.getOrNull() ?: return
            try {
                while (scope.isActive && !closed) {
                    val frame = SnapcastWire.readFrame(input)
                    val received = clock.now()
                    when (frame.header.type) {
                        SnapcastMessageType.HELLO -> handleHello(frame)
                        SnapcastMessageType.TIME -> handleTime(frame, received)
                        SnapcastMessageType.CLIENT_INFO -> handleClientInfo(frame)
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    println("[DEBUG] Snapcast session $sessionId read ended: ${e.message}")
                    e.printStackTrace()
                }
            } finally {
                close()
            }
        }

        private fun handleHello(frame: SnapcastFrame) {
            val json = SnapJson.parse(SnapcastWire.readString(frame.payload))
            val id = json.stringAt("ID")
                ?: json.stringAt("MAC")
                ?: socket.inetAddress?.hostAddress
                ?: "unknown-$sessionId"
            val host = SnapcastHostInfo(
                name = json.stringAt("HostName") ?: (socket.inetAddress?.hostAddress ?: ""),
                mac = json.stringAt("MAC") ?: "",
                os = json.stringAt("OS") ?: "",
                arch = json.stringAt("Arch") ?: "",
                ip = socket.inetAddress?.hostAddress ?: ""
            )
            clientId = id
            helloReceived = true
            state.onClientConnected(
                id = id,
                host = host,
                clientName = json.stringAt("ClientName") ?: "Snapclient",
                version = json.stringAt("Version") ?: "",
                protocolVersion = json.intAt("SnapStreamProtocolVersion") ?: SnapcastWire.STREAM_PROTOCOL_VERSION,
                instance = json.intAt("Instance") ?: 1
            )
            log("[Snapcast] client connected: $id (${host.name})")
            sendServerSettings(frame.header.id)
            sendCodecHeader()
        }

        private fun handleTime(frame: SnapcastFrame, received: SnapcastTv) {
            val latencyMicros = received.toMicros() - frame.header.sent.toMicros()
            println("[DEBUG] handleTime: received.micros=${received.toMicros()}, sent.micros=${frame.header.sent.toMicros()}, latencyMicros=$latencyMicros")
            val payload = SnapcastWire.timePayload(SnapcastTv.fromMicros(latencyMicros))
            enqueuePriority(
                SnapcastWire.frame(
                    SnapcastMessageType.TIME,
                    nextId(),
                    frame.header.id,
                    clock.now(),
                    received,
                    payload
                )
            )
        }

        private fun handleClientInfo(frame: SnapcastFrame) {
            val id = clientId ?: return
            val json = SnapJson.parse(SnapcastWire.readString(frame.payload))
            val percent = json.intAt("volume") ?: return
            val muted = json.boolAt("muted") ?: false
            state.setClientVolume(id, percent, muted)
        }

        fun sendServerSettings(refersTo: Int = 0) {
            val id = clientId ?: return
            val client = state.clientSnapshot(id) ?: return
            val (volume, muted) = state.effectiveVolume(id)
            val payload = SnapcastWire.stringPayload(SnapJsonWriter.write {
                put("bufferMs", bufferMs)
                put("latency", client.config.latency)
                put("muted", muted)
                put("volume", volume)
            })
            enqueuePriority(
                SnapcastWire.frame(
                    SnapcastMessageType.SERVER_SETTINGS,
                    nextId(),
                    refersTo,
                    clock.now(),
                    SnapcastTv(0, 0),
                    payload
                )
            )
        }

        fun sendCodecHeader() {
            val header = codecHeaderFrame ?: return
            enqueuePriority(header)
            streaming = true
        }

        fun enqueue(frame: ByteArray) {
            if (closed) return
            if (!outgoing.offer(frame)) {
                outgoing.poll()
                outgoing.offer(frame)
            }
        }

        private fun enqueuePriority(frame: ByteArray) {
            if (closed) return
            priority.add(frame)
        }

        fun close() {
            if (closed) return
            closed = true
            streaming = false
            sessions.remove(sessionId)
            clientId?.let { id ->
                if (sessions.values.none { it.clientId == id }) state.onClientDisconnected(id)
            }
            priority.clear()
            outgoing.clear()
            readerJob?.cancel()
            writerJob?.cancel()
            runCatching { socket.close() }
            log("[Snapcast] session $sessionId closed")
        }
    }

    companion object {
        private const val OUTGOING_CAPACITY = 256
        private const val MAX_BATCH = 32
    }
}
