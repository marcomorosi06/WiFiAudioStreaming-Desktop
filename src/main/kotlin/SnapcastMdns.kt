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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

data class SnapcastMdnsService(
    val instance: String,
    val type: String,
    val port: Int,
    val txt: List<String>
)

class SnapcastMdnsResponder(
    private val scope: CoroutineScope,
    private val hostName: String,
    private val services: List<SnapcastMdnsService>,
    private val addressProvider: () -> String?,
    private val interfaceProvider: () -> NetworkInterface?,
    private val log: (String) -> Unit
) {

    private var job: Job? = null

    @Volatile
    private var socket: MulticastSocket? = null

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    private val hostFqdn = "${sanitize(hostName)}.local."

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            val group = InetAddress.getByName(MULTICAST_ADDRESS)
            val sock = runCatching {
                MulticastSocket(null as java.net.SocketAddress?).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(MDNS_PORT))
                    timeToLive = 255
                    val iface = interfaceProvider()
                    if (iface != null) {
                        runCatching { networkInterface = iface }
                        runCatching { joinGroup(InetSocketAddress(group, MDNS_PORT), iface) }
                    } else {
                        runCatching { joinGroup(group) }
                    }
                }
            }.getOrElse {
                lastError = it.message ?: it.javaClass.simpleName
                log("[Snapcast] mDNS unavailable: $lastError")
                return@launch
            }
            socket = sock
            active = true
            lastError = ""
            log("[Snapcast] mDNS responder active for $hostFqdn")
            announce()
            val buffer = ByteArray(4096)
            try {
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val received = runCatching { sock.receive(packet) }.isSuccess
                    if (!received) break
                    runCatching { handleQuery(packet) }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) log("[Snapcast] mDNS loop ended: ${e.message}")
            } finally {
                runCatching { sock.close() }
            }
        }
    }

    fun stop() {
        runCatching { sendGoodbye() }
        active = false
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
    }

    private suspend fun announce() {
        repeat(3) { attempt ->
            runCatching { sendAnnouncement(ttl = DEFAULT_TTL) }
            delay(250L * (attempt + 1))
        }
    }

    private fun localAddressBytes(): ByteArray? {
        val text = addressProvider() ?: return null
        val address = runCatching { InetAddress.getByName(text) }.getOrNull() ?: return null
        if (address !is Inet4Address) return null
        return address.address
    }

    private fun sendAnnouncement(ttl: Int) {
        val sock = socket ?: return
        val payload = buildResponse(services, includeAll = true, ttl = ttl) ?: return
        val group = InetAddress.getByName(MULTICAST_ADDRESS)
        runCatching { sock.send(DatagramPacket(payload, payload.size, group, MDNS_PORT)) }
    }

    private fun sendGoodbye() {
        sendAnnouncement(ttl = 0)
    }

    private fun handleQuery(packet: DatagramPacket) {
        val data = packet.data
        val length = packet.length
        if (length < 12) return
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if (flags and 0x8000 != 0) return
        val questionCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        if (questionCount <= 0) return

        var offset = 12
        val matched = LinkedHashSet<SnapcastMdnsService>()
        var wantsMeta = false
        var unicast = false

        repeat(questionCount) {
            val parsed = readName(data, offset, length) ?: return
            val name = parsed.first
            offset = parsed.second
            if (offset + 4 > length) return
            val qtype = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val qclass = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            offset += 4
            if (qclass and 0x8000 != 0) unicast = true
            val lower = name.lowercase()
            if (lower == META_QUERY) {
                wantsMeta = true
                return@repeat
            }
            services.forEach { service ->
                val serviceType = "${service.type}.local."
                val instanceName = "${service.instance}.${service.type}.local."
                val matchesType = lower == serviceType.lowercase() && (qtype == TYPE_PTR || qtype == TYPE_ANY)
                val matchesInstance = lower == instanceName.lowercase() &&
                    (qtype == TYPE_SRV || qtype == TYPE_TXT || qtype == TYPE_ANY)
                val matchesHost = lower == hostFqdn.lowercase() && (qtype == TYPE_A || qtype == TYPE_ANY)
                if (matchesType || matchesInstance || matchesHost) matched.add(service)
            }
        }

        if (matched.isEmpty() && !wantsMeta) return
        val target = if (matched.isEmpty()) services else matched.toList()
        val response = buildResponse(target, includeAll = matched.isNotEmpty(), ttl = DEFAULT_TTL, meta = wantsMeta)
            ?: return
        val sock = socket ?: return
        if (unicast) {
            runCatching { sock.send(DatagramPacket(response, response.size, packet.address, packet.port)) }
        }
        val group = InetAddress.getByName(MULTICAST_ADDRESS)
        runCatching { sock.send(DatagramPacket(response, response.size, group, MDNS_PORT)) }
    }

    private fun buildResponse(
        target: List<SnapcastMdnsService>,
        includeAll: Boolean,
        ttl: Int,
        meta: Boolean = false
    ): ByteArray? {
        val addressBytes = localAddressBytes()
        val answers = ByteArrayOutputStream()
        var answerCount = 0

        if (meta) {
            services.forEach { service ->
                writeRecord(answers, META_QUERY, TYPE_PTR, encodeName("${service.type}.local."), ttl)
                answerCount++
            }
        }

        target.forEach { service ->
            val serviceType = "${service.type}.local."
            val instanceName = "${service.instance}.${service.type}.local."
            writeRecord(answers, serviceType, TYPE_PTR, encodeName(instanceName), ttl)
            answerCount++
            if (includeAll) {
                writeRecord(answers, instanceName, TYPE_SRV, encodeSrv(service.port, hostFqdn), ttl, cacheFlush = true)
                answerCount++
                writeRecord(answers, instanceName, TYPE_TXT, encodeTxt(service.txt), ttl, cacheFlush = true)
                answerCount++
            }
        }

        if (includeAll && addressBytes != null) {
            writeRecord(answers, hostFqdn, TYPE_A, addressBytes, ttl, cacheFlush = true)
            answerCount++
        }

        if (answerCount == 0) return null

        val out = ByteArrayOutputStream()
        out.write(0); out.write(0)
        out.write(0x84); out.write(0x00)
        out.write(0); out.write(0)
        out.write((answerCount shr 8) and 0xFF); out.write(answerCount and 0xFF)
        out.write(0); out.write(0)
        out.write(0); out.write(0)
        out.write(answers.toByteArray())
        return out.toByteArray()
    }

    private fun writeRecord(
        out: ByteArrayOutputStream,
        name: String,
        type: Int,
        data: ByteArray,
        ttl: Int,
        cacheFlush: Boolean = false
    ) {
        out.write(encodeName(name))
        out.write((type shr 8) and 0xFF); out.write(type and 0xFF)
        val rclass = if (cacheFlush) 0x8001 else 0x0001
        out.write((rclass shr 8) and 0xFF); out.write(rclass and 0xFF)
        out.write((ttl shr 24) and 0xFF)
        out.write((ttl shr 16) and 0xFF)
        out.write((ttl shr 8) and 0xFF)
        out.write(ttl and 0xFF)
        out.write((data.size shr 8) and 0xFF); out.write(data.size and 0xFF)
        out.write(data)
    }

    private fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        name.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            val truncated = if (bytes.size > 63) bytes.copyOf(63) else bytes
            out.write(truncated.size)
            out.write(truncated)
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun encodeSrv(port: Int, target: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0); out.write(0)
        out.write(0); out.write(0)
        out.write((port shr 8) and 0xFF); out.write(port and 0xFF)
        out.write(encodeName(target))
        return out.toByteArray()
    }

    private fun encodeTxt(entries: List<String>): ByteArray {
        if (entries.isEmpty()) return byteArrayOf(0)
        val out = ByteArrayOutputStream()
        entries.forEach { entry ->
            val bytes = entry.toByteArray(Charsets.UTF_8)
            val truncated = if (bytes.size > 255) bytes.copyOf(255) else bytes
            out.write(truncated.size)
            out.write(truncated)
        }
        return out.toByteArray()
    }

    private fun readName(data: ByteArray, start: Int, limit: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var offset = start
        var jumped = false
        var next = start
        var guard = 0
        while (offset < limit) {
            if (guard++ > 128) return null
            val length = data[offset].toInt() and 0xFF
            if (length == 0) {
                offset++
                if (!jumped) next = offset
                return sb.toString() to next
            }
            if (length and 0xC0 == 0xC0) {
                if (offset + 1 >= limit) return null
                val pointer = ((length and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                if (!jumped) next = offset + 2
                jumped = true
                offset = pointer
                continue
            }
            if (offset + 1 + length > limit) return null
            sb.append(String(data, offset + 1, length, Charsets.UTF_8)).append('.')
            offset += 1 + length
        }
        return null
    }

    private fun sanitize(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "wfas" }

    companion object {
        const val SERVICE_STREAM = "_snapcast._tcp"
        const val SERVICE_CONTROL = "_snapcast-ctrl._tcp"
        private const val MULTICAST_ADDRESS = "224.0.0.251"
        private const val MDNS_PORT = 5353
        private const val META_QUERY = "_services._dns-sd._udp.local."
        private const val DEFAULT_TTL = 120
        private const val TYPE_A = 1
        private const val TYPE_PTR = 12
        private const val TYPE_TXT = 16
        private const val TYPE_SRV = 33
        private const val TYPE_ANY = 255
    }
}
