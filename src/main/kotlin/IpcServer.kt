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
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

object IpcServer {

    private val pid: Long = ProcessHandle.current().pid()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var sessionFile: java.io.File? = null

    /** The token lives only in memory and in the 0600 file: never in the config. */
    @Volatile private var token: String = ""

    private var currentArgs: CliArgs = CliArgs()
    private var startTimeMs: Long = 0L

    @Volatile private var storedCache: Pair<String, String>? = null
    @Volatile private var storedCacheAt: Long = 0L
    private const val SECURITY_CACHE_MS = 3_000L

    private val failedAttempts = AtomicInteger(0)
    @Volatile private var lockedUntilMs: Long = 0L

    private const val MAX_FAILED_ATTEMPTS = 5
    private const val LOCKOUT_MS = 30_000L
    private const val HANDSHAKE_TIMEOUT_MS = 5_000

    // Everything the socket accepts before the caller has proved anything is part
    // of the authentication boundary, so both the size of a request and the number
    // of them in flight are bounded. Real control commands are a few hundred bytes
    // and arrive one at a time.
    private const val MAX_LINE_CHARS = 8 * 1024
    private const val MAX_LIVE_CLIENTS = 8

    private val liveClients = AtomicInteger(0)

    fun start(args: CliArgs) {
        currentArgs = args
        startTimeMs = System.currentTimeMillis()
        invalidateSecurityCache()
        token = IpcAuth.newToken()

        val cs = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = cs

        cs.launch {
            try {
                val ss = ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())
                serverSocket = ss
                sessionFile = IpcAuth.writeSession(pid, ss.localPort, token).also { it.deleteOnExit() }
                AppDebug.log("[IPC] listening on 127.0.0.1:${ss.localPort}, session ${sessionFile?.name}")

                while (isActive) {
                    val client: Socket = try { ss.accept() } catch (_: SocketException) { break }
                    if (liveClients.get() >= MAX_LIVE_CLIENTS) {
                        AppDebug.log("[IPC] refused: too many connections in flight")
                        runCatching { client.close() }
                        continue
                    }
                    liveClients.incrementAndGet()
                    launch {
                        try { handleClient(client) } finally { liveClients.decrementAndGet() }
                    }
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
        sessionFile?.runCatching { delete() }
        serverSocket = null
        sessionFile = null
        token = ""
        scope = null
        // Cancelled handlers run their finally, but a restart must not inherit a
        // count that never came back down for any reason.
        liveClients.set(0)
    }

    fun applySecurity(mode: String?, key: String?) {
        invalidateSecurityCache()
    }

    private fun invalidateSecurityCache() {
        storedCache = null
        storedCacheAt = 0L
    }

    private fun storedSecurity(): Pair<String, String> {
        val now = System.currentTimeMillis()
        val cached = storedCache
        if (cached != null && now - storedCacheAt < SECURITY_CACHE_MS) return cached
        val app = runCatching { SettingsRepository.loadSettings().app }.getOrNull()
        val value = SecurityMode.fromStringSafe(app?.securityMode).name to app?.authKey.orEmpty()
        storedCache = value
        storedCacheAt = now
        return value
    }

    private fun currentSecurity(): Pair<String, String> =
        if (NetworkHandler_v1.securityConfigured)
            SecurityMode.fromStringSafe(NetworkHandler_v1.securityMode).name to NetworkHandler_v1.authKey
        else
            storedSecurity()

    private fun keyRequired(): Boolean {
        val (mode, key) = currentSecurity()
        return SecurityMode.requiresKey(mode) && key.isNotBlank()
    }

    /**
     * Reads one line and refuses anything longer than [MAX_LINE_CHARS]. A plain
     * readLine() buffers whatever keeps arriving, and this runs before the caller
     * has presented a token, so an unbounded read would be work the socket does on
     * behalf of someone it has not authenticated yet. Returns null on overflow,
     * which the caller treats as a malformed request.
     */
    private fun readLimitedLine(reader: java.io.BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c == '\r'.code) continue
            if (sb.length >= MAX_LINE_CHARS) return null
            sb.append(c.toChar())
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream().bufferedWriter()

            fun reply(text: String) {
                writer.write(text); writer.newLine(); writer.flush()
            }

            val now = System.currentTimeMillis()
            if (now < lockedUntilMs) {
                reply(buildResponse("unauthorized", mapOf(
                    "message" to "too many failed attempts, try again in ${(lockedUntilMs - now) / 1000 + 1}s"
                )))
                return
            }

            val needKey = keyRequired()
            val nonce = IpcAuth.nonce()
            reply(
                "{\"status\": \"challenge\", \"v\": ${IpcAuth.PROTOCOL_VERSION}, " +
                        "\"nonce\": \"$nonce\", \"key_required\": $needKey}"
            )

            val authLine = runCatching { readLimitedLine(reader) }.getOrNull()?.trim().orEmpty()
            if (!authLine.startsWith("AUTH ")) {
                registerFailure("malformed handshake")
                reply(buildResponse("unauthorized", mapOf("message" to "handshake required")))
                return
            }
            val parts = authLine.removePrefix("AUTH ").trim().split(" ")
            val sentToken = parts.getOrNull(0).orEmpty()
            val sentProof = parts.getOrNull(1).orEmpty()

            val payload = runCatching { readLimitedLine(reader) }.getOrNull()?.trim().orEmpty()
            if (payload.isEmpty()) {
                registerFailure("empty command")
                reply(buildResponse("error", mapOf("message" to "empty command")))
                return
            }

            // Constant-time comparison, and the token is checked first: a caller
            // without one learns nothing about the key it may have tried.
            if (token.isEmpty() || !IpcAuth.constantTimeEquals(sentToken, token)) {
                registerFailure("bad session token")
                reply(buildResponse("unauthorized", mapOf("message" to "invalid session token")))
                return
            }

            if (needKey) {
                val expected = IpcAuth.proof(currentSecurity().second, nonce, payload)
                if (!IpcAuth.constantTimeEquals(sentProof, expected)) {
                    registerFailure("bad key proof")
                    reply(buildResponse("unauthorized", mapOf(
                        "message" to "wrong key",
                        "key_required" to true
                    )))
                    return
                }
            }

            failedAttempts.set(0)
            socket.soTimeout = 0

            val cmd = parseCommand(payload)
            reply(execute(cmd))
        }
    }

    private suspend fun registerFailure(reason: String) {
        val n = failedAttempts.incrementAndGet()
        AppDebug.log("[IPC] refused: $reason (attempt $n)")
        // A fixed delay takes the value out of looping on attempts, and after a
        // few failures the channel closes entirely for a while. It suspends rather
        // than sleeps: a blocked dispatcher thread per refused connection is the
        // kind of cost a caller should not be able to impose before authenticating.
        runCatching { delay(250) }
        if (n >= MAX_FAILED_ATTEMPTS) {
            lockedUntilMs = System.currentTimeMillis() + LOCKOUT_MS
            failedAttempts.set(0)
            System.err.println("[IPC] too many failed control attempts, channel locked for ${LOCKOUT_MS / 1000}s")
        }
    }

    private fun execute(cmd: ControlCommand?): String {
        val args = currentArgs
        return when (cmd) {
            is ControlCommand.Volume -> {
                NetworkHandler_v1.setPlaybackOrCaptureVolume(cmd.value)
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
                val snap = SnapcastStatus.session.value
                val dlnaOn = args.dlna || NetworkHandler_v1.dlnaActive()
                val snapcastOn = args.snapcast || NetworkHandler_v1.snapcastActive()
                val dlnaTargets = DlnaStatus.targets.value
                buildResponse("ok", mapOf(
                    "mode"     to args.runMode.name.lowercase().removePrefix("cli_"),
                    "volume"   to NetworkHandler_v1.currentServerVolume,
                    "muted"    to NetworkHandler_v1.isMicMuted.value,
                    "port"     to args.port,
                    "rtp"      to args.rtp,
                    "http"     to args.http,
                    "dlna"     to dlnaOn,
                    "dlna_port" to args.dlnaPort,
                    "dlna_format" to args.dlnaFormat,
                    "dlna_clients" to NetworkHandler_v1.dlnaClientCount(),
                    "dlna_targets" to dlnaTargets.size,
                    "dlna_renderers" to dlnaTargets.joinToString(", ") { it.name },
                    "snapcast" to snapcastOn,
                    "snapcast_clients" to NetworkHandler_v1.snapcastClientCount(),
                    "snapcast_codec" to snap.codec,
                    "snapcast_port" to snap.streamPort,
                    "snapcast_control_port" to snap.controlPort,
                    "snapcast_stream" to args.snapcastStreamName,
                    "uptime"   to uptimeSec,
                    "pid"      to pid,
                    "usb"      to UsbLink.isReady(),
                    "usb_iface" to (UsbLink.state.interfaceName ?: ""),
                    "family"   to NetAddr.preferredFamily.name.lowercase(),
                    "wfas"     to WfasPolicy.mode.lowercase(),
                    "auth"     to currentSecurity().first.lowercase(),
                    "encrypted" to NetworkHandler_v1.sessionEncryptedLive.value
                ))
            }
            is ControlCommand.DeepLink -> {
                val ok = QrPairingState.submitDeepLink(cmd.uri)
                buildResponse(if (ok) "ok" else "error", mapOf("handled" to ok))
            }
            is ControlCommand.PairInvite -> {
                val invite = PairRuntime.generate(args.port, args.multicast, cmd.forceNewKey)
                if (invite == null) {
                    buildResponse("error", mapOf("message" to "no local address"))
                } else {
                    buildResponse("ok", mapOf(
                        "uri"       to invite.uri,
                        "key"       to invite.key,
                        "ip"        to invite.ip,
                        "port"      to invite.port,
                        "multicast" to invite.multicast,
                        "expires_at" to invite.expEpochSeconds,
                        "encryption_forced" to invite.encryptionForced
                    ))
                }
            }
            null -> buildResponse("error", mapOf("message" to "unknown command"))
        }
    }

    private fun parseCommand(json: String): ControlCommand? {
        val cmd = extractString(json, "cmd") ?: return null
        return when (cmd) {
            "volume" -> {
                val v = extractNumber(json, "value")?.toFloat() ?: return null
                ControlCommand.Volume(v.coerceIn(0f, 2f))
            }
            "mute"   -> ControlCommand.Mute
            "unmute" -> ControlCommand.Unmute
            "stop"   -> ControlCommand.Stop
            "status" -> ControlCommand.Status
            "pair"   -> ControlCommand.PairInvite(Regex("\"rekey\"\\s*:\\s*true").containsMatchIn(json))
            "deeplink" -> extractString(json, "uri")?.let { ControlCommand.DeepLink(it) }
            else     -> null
        }
    }

    private fun buildResponse(status: String, data: Map<String, Any?>): String {
        val fields = mutableListOf<String>()
        fields += "\"status\": \"$status\""
        data.forEach { (k, v) ->
            val vStr = when (v) {
                null       -> "null"
                is String  -> "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
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