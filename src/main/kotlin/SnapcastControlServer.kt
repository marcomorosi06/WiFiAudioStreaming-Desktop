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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object SnapcastRpcError {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

class SnapcastControlServer(
    private val scope: CoroutineScope,
    private val port: Int,
    private val state: SnapcastState,
    private val log: (String) -> Unit
) : SnapcastStateListener {

    private val connections = ConcurrentHashMap<Long, Connection>()
    private val connectionSequence = AtomicLong(0L)

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
                log("[Snapcast] control port $port unavailable: $bindError")
                return@launch
            }
            serverSocket = socket
            bound = true
            bindError = ""
            log("[Snapcast] control server listening on $port")
            try {
                while (isActive) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    val id = connectionSequence.incrementAndGet()
                    val connection = Connection(id, client)
                    connections[id] = connection
                    connection.start()
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
        connections.values.toList().forEach { it.close() }
        connections.clear()
    }

    fun activeConnections(): Int = connections.size

    override fun onClientSettingsChanged(clientId: String) = Unit

    override fun onServerUpdate() = Unit

    override fun onNotification(method: String, paramsJson: String) {
        val message = SnapJsonWriter.write {
            put("jsonrpc", "2.0")
            put("method", method)
            putRaw("params", paramsJson)
        }
        connections.values.toList().forEach { it.send(message) }
    }

    private fun success(id: SnapJson?, resultJson: String): String = SnapJsonWriter.write {
        put("jsonrpc", "2.0")
        putRaw("id", renderId(id))
        putRaw("result", resultJson)
    }

    private fun failure(id: SnapJson?, code: Int, message: String, data: String? = null): String =
        SnapJsonWriter.write {
            put("jsonrpc", "2.0")
            putRaw("id", renderId(id))
            obj("error") {
                put("code", code)
                put("message", message)
                if (data != null) put("data", data)
            }
        }

    private fun renderId(id: SnapJson?): String = when (id) {
        is SnapJson.Num -> {
            val value = id.value
            if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
        }
        is SnapJson.Str -> "\"" + SnapJson.escape(id.value) + "\""
        else -> "null"
    }

    private fun dispatch(request: SnapJson?): String? {
        if (request !is SnapJson.Obj) return failure(null, SnapcastRpcError.INVALID_REQUEST, "Invalid Request")
        val id = request.fields["id"]
        val method = request.stringAt("method")
            ?: return failure(id, SnapcastRpcError.INVALID_REQUEST, "Invalid Request")
        val params = request.fields["params"]
        val isNotification = !request.fields.containsKey("id")

        val response = runCatching { handle(method, params, id) }.getOrElse {
            failure(id, SnapcastRpcError.INTERNAL_ERROR, "Internal error", it.message ?: "")
        }
        return if (isNotification) null else response
    }

    private fun handle(method: String, params: SnapJson?, id: SnapJson?): String = when (method) {
        "Server.GetRPCVersion" -> success(id, SnapJsonWriter.write {
            put("major", 2)
            put("minor", 0)
            put("patch", 0)
        })

        "Server.GetStatus" -> success(id, state.serverStatusJson())

        "Server.DeleteClient" -> {
            val clientId = params.stringAt("id")
            when {
                clientId == null -> failure(id, SnapcastRpcError.INVALID_PARAMS, "Invalid params")
                !state.removeClient(clientId) -> failure(id, SnapcastRpcError.INVALID_PARAMS, "Client not found")
                else -> success(id, state.serverStatusJson())
            }
        }

        "Client.GetStatus" -> {
            val clientId = params.stringAt("id")
            val client = clientId?.let { state.clientSnapshot(it) }
            if (client == null) failure(id, SnapcastRpcError.INVALID_PARAMS, "Client not found")
            else success(id, SnapJsonWriter.write { putRaw("client", client.toJson()) })
        }

        "Client.SetVolume" -> {
            val clientId = params.stringAt("id")
            val volume = params.field("volume")
            val percent = volume.intAt("percent")
            val muted = volume.boolAt("muted") ?: false
            if (clientId == null || percent == null) {
                failure(id, SnapcastRpcError.INVALID_PARAMS, "Invalid params")
            } else {
                val updated = state.setClientVolume(clientId, percent, muted)
                if (updated == null) failure(id, SnapcastRpcError.INVALID_PARAMS, "Client not found")
                else success(id, SnapJsonWriter.write {
                    obj("volume") {
                        put("muted", updated.config.muted)
                        put("percent", updated.config.volumePercent)
                    }
                })
            }
        }

        "Client.SetLatency" -> {
            val clientId = params.stringAt("id")
            val latency = params.intAt("latency")
            if (clientId == null || latency == null) {
                failure(id, SnapcastRpcError.INVALID_PARAMS, "Invalid params")
            } else {
                val updated = state.setClientLatency(clientId, latency)
                if (updated == null) failure(id, SnapcastRpcError.INVALID_PARAMS, "Client not found")
                else success(id, SnapJsonWriter.write { put("latency", updated.config.latency) })
            }
        }

        "Client.SetName" -> {
            val clientId = params.stringAt("id")
            val name = params.stringAt("name")
            if (clientId == null || name == null) {
                failure(id, SnapcastRpcError.INVALID_PARAMS, "Invalid params")
            } else {
                val updated = state.setClientName(clientId, name)
                if (updated == null) failure(id, SnapcastRpcError.INVALID_PARAMS, "Client not found")
                else success(id, SnapJsonWriter.write { put("name", updated.config.name) })
            }
        }

        "Group.GetStatus" -> {
            val groupId = params.stringAt("id")
            val group = groupId?.let { state.groupJson(it) }
            if (group == null) failure(id, SnapcastRpcError.INVALID_PARAMS, "Group not found")
            else success(id, SnapJsonWriter.write { putRaw("group", group) })
        }

        "Group.SetMute" -> {
            val groupId = params.stringAt("id")
            val mute = params.boolAt("mute")
            if (groupId == null || mute == null) {
                failure(id, SnapcastRpcError.INVALID_PARAMS, "Invalid params")
            } else {
                val updated = state.setGroupMuted(groupId, mute)
                if (updated == null) failure(id, SnapcastRpcError.INVALID_PARAMS, "Group not found")
                else success(id, SnapJsonWriter.write { put("mute", updated.muted) })
            }
        }

        "Group.SetName" -> {
            val groupId = params.stringAt("id")
            val name = params.stringAt("name")
            if (groupId == null || name == null) {
                failure(id, SnapcastRpcError.INVALID_PARAMS, "Invalid params")
            } else {
                val updated = state.setGroupName(groupId, name)
                if (updated == null) failure(id, SnapcastRpcError.INVALID_PARAMS, "Group not found")
                else success(id, SnapJsonWriter.write { put("name", updated.name) })
            }
        }

        "Group.SetStream" -> {
            val groupId = params.stringAt("id")
            val streamId = params.stringAt("stream_id")
            if (groupId == null || streamId == null) {
                failure(id, SnapcastRpcError.INVALID_PARAMS, "Invalid params")
            } else {
                val updated = state.setGroupStream(groupId, streamId)
                if (updated == null) failure(id, SnapcastRpcError.INVALID_PARAMS, "Stream or group not found")
                else success(id, SnapJsonWriter.write { put("stream_id", updated.streamId) })
            }
        }

        "Group.SetClients" -> {
            val groupId = params.stringAt("id")
            val clientIds = params.field("clients").asArray().mapNotNull { it.asString() }
            if (groupId == null) failure(id, SnapcastRpcError.INVALID_PARAMS, "Invalid params")
            else if (!state.setGroupClients(groupId, clientIds)) {
                failure(id, SnapcastRpcError.INVALID_PARAMS, "Group not found")
            } else success(id, state.serverStatusJson())
        }

        "Stream.AddStream",
        "Stream.RemoveStream",
        "Stream.Control",
        "Stream.SetProperty" ->
            failure(id, SnapcastRpcError.METHOD_NOT_FOUND, "Method not supported by this server")

        else -> failure(id, SnapcastRpcError.METHOD_NOT_FOUND, "Method not found")
    }

    private fun handleLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val parsed = SnapJson.parse(trimmed)
            ?: return failure(null, SnapcastRpcError.PARSE_ERROR, "Parse error")
        if (parsed is SnapJson.Arr) {
            if (parsed.items.isEmpty()) return failure(null, SnapcastRpcError.INVALID_REQUEST, "Invalid Request")
            val responses = parsed.items.mapNotNull { dispatch(it) }
            return if (responses.isEmpty()) null else responses.joinToString(",", "[", "]")
        }
        return dispatch(parsed)
    }

    private inner class Connection(private val connectionId: Long, private val socket: Socket) {

        private var job: Job? = null
        private var writer: Writer? = null
        private val writeLock = Any()

        @Volatile
        private var closed = false

        fun start() {
            job = scope.launch(Dispatchers.IO) { run() }
        }

        private fun run() {
            try {
                runCatching { socket.tcpNoDelay = true }
                val out = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                writer = out
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (scope.isActive && !closed) {
                    val line = reader.readLine() ?: break
                    val response = handleLine(line) ?: continue
                    send(response)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) log("[Snapcast] control $connectionId ended: ${e.message}")
            } finally {
                close()
            }
        }

        fun send(message: String) {
            if (closed) return
            val out = writer ?: return
            synchronized(writeLock) {
                runCatching {
                    out.write(message)
                    out.write("\r\n")
                    out.flush()
                }.onFailure { close() }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            connections.remove(connectionId)
            job?.cancel()
            runCatching { socket.close() }
        }
    }
}
