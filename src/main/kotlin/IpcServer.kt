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

import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files

// ─────────────────────────────────────────────────────────────────────────────
// IPC transport
//
// Linux / macOS: Unix-domain socket via a loopback TCP on a fixed per-PID port
//                computed from the process PID so multiple instances coexist.
// Windows:       same loopback TCP mechanism (no UnixDomainSocketChannel
//                dependency needed — works on JVM 11+).
//
// Socket path file  →  $TMPDIR/wfas-<pid>.port   (contains the TCP port)
// This lets IpcClient find the right port without hard-coding anything.
// ─────────────────────────────────────────────────────────────────────────────

object IpcServer {

    private val pid: Long = ProcessHandle.current().pid()
    private val portFile = File(System.getProperty("java.io.tmpdir"), "wfas-$pid.port")

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var scope: CoroutineScope? = null

    private var currentArgs: CliArgs = CliArgs()
    private var startTimeMs: Long = 0L

    fun start(args: CliArgs) {
        currentArgs = args
        startTimeMs = System.currentTimeMillis()
        val cs = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = cs

        cs.launch {
            try {
                val ss = ServerSocket(0)
                serverSocket = ss
                portFile.writeText(ss.localPort.toString())
                portFile.deleteOnExit()

                while (isActive) {
                    val client: Socket = try { ss.accept() } catch (_: SocketException) { break }
                    launch { handleClient(client, args) }
                }
            } catch (e: Exception) {
                if (e !is CancellationException)
                    System.err.println("[IPC] Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        scope?.cancel()
        serverSocket?.runCatching { close() }
        portFile.runCatching { delete() }
        serverSocket = null
        scope = null
    }

    private fun handleClient(socket: Socket, args: CliArgs) {
        socket.use {
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream().bufferedWriter()

            val line = reader.readLine()?.trim() ?: return
            val cmd  = parseCommand(line)

            val response = when (cmd) {
                is ControlCommand.Volume -> {
                    NetworkHandler_v1.setServerVolume(cmd.value)
                    buildResponse("ok", mapOf("volume" to cmd.value))
                }
                is ControlCommand.Mute -> {
                    NetworkHandler_v1.isMicMuted.value = true
                    buildResponse("ok", mapOf("muted" to true))
                }
                is ControlCommand.Unmute -> {
                    NetworkHandler_v1.isMicMuted.value = false
                    buildResponse("ok", mapOf("muted" to false))
                }
                is ControlCommand.Stop -> {
                    runBlocking { NetworkHandler_v1.stopCurrentStream() }
                    buildResponse("ok", mapOf("stopped" to true))
                }
                is ControlCommand.Status -> {
                    val uptimeSec = (System.currentTimeMillis() - startTimeMs) / 1000
                    buildResponse("ok", mapOf(
                        "mode"     to args.runMode.name.lowercase().removePrefix("cli_"),
                        "volume"   to NetworkHandler_v1.currentServerVolume,
                        "muted"    to NetworkHandler_v1.isMicMuted.value,
                        "port"     to args.port,
                        "rtp"      to args.rtp,
                        "http"     to args.http,
                        "uptime"   to uptimeSec,
                        "pid"      to pid
                    ))
                }
                null -> buildResponse("error", mapOf("message" to "unknown command"))
            }

            writer.write(response)
            writer.newLine()
            writer.flush()
        }
    }

    private fun parseCommand(json: String): ControlCommand? {
        val cmd = extractString(json, "cmd") ?: return null
        return when (cmd) {
            "volume" -> {
                val v = extractNumber(json, "value")?.toFloat() ?: return null
                ControlCommand.Volume(v.coerceIn(0f, 1f))
            }
            "mute"   -> ControlCommand.Mute
            "unmute" -> ControlCommand.Unmute
            "stop"   -> ControlCommand.Stop
            "status" -> ControlCommand.Status
            else     -> null
        }
    }

    private fun buildResponse(status: String, data: Map<String, Any?>): String {
        val fields = mutableListOf<String>()
        fields += "\"status\": \"$status\""
        data.forEach { (k, v) ->
            val vStr = when (v) {
                null       -> "null"
                is String  -> "\"${v.replace("\"", "\\\"")}\""
                is Boolean -> v.toString()
                is Number  -> v.toString()
                else       -> "\"$v\""
            }
            fields += "\"$k\": $vStr"
        }
        return "{${fields.joinToString(", ")}}"
    }

    private fun extractString(json: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractNumber(json: String, key: String): Double? {
        val regex = Regex("\"$key\"\\s*:\\s*([\\d.]+)")
        return regex.find(json)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }
}
