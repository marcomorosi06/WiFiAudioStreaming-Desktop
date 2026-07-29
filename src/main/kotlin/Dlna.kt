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

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

object DlnaConst {
    const val SSDP_ADDR_V4 = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val ST_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val TYPE_AVTRANSPORT = "AVTransport"
    const val TYPE_CONNECTION_MANAGER = "ConnectionManager"
    const val TYPE_RENDERING_CONTROL = "RenderingControl"
    const val DEFAULT_FLAGS = "8D500000000000000000000000000000"
    const val USER_AGENT = "Linux/1.0 UPnP/1.0 WiFiAudioStreaming/1.0 DLNADOC/1.50"
    const val DIDL_ITEM_ID = "wfas-live-0"
    const val CONNECT_TIMEOUT_MS = 4000
    const val READ_TIMEOUT_MS = 6000
    const val MAX_XML_BYTES = 512 * 1024
}

enum class DlnaCodec(
    val id: String,
    val path: String,
    val defaultPn: String,
    val label: String,
    val lossless: Boolean
) {
    LPCM("lpcm", "/dlna/lpcm.raw", "LPCM", "LPCM 16 bit", true),
    WAV("wav", "/dlna/stream.wav", "LPCM", "WAV", true),
    MP3("mp3", "/dlna/stream.mp3", "MP3", "MP3 320 kbps", false),
    ADTS("adts", "/dlna/stream.adts", "AAC_ADTS_320", "AAC ADTS", false);

    fun defaultMime(sampleRate: Int, channels: Int): String = when (this) {
        LPCM -> "audio/L16;rate=$sampleRate;channels=$channels"
        WAV -> "audio/wav"
        MP3 -> "audio/mpeg"
        ADTS -> "audio/vnd.dlna.adts"
    }

    fun mimeAliases(): Set<String> = when (this) {
        LPCM -> setOf("audio/l16", "audio/l16;rate", "audio/basic")
        WAV -> setOf("audio/wav", "audio/x-wav", "audio/vnd.wave", "audio/wave")
        MP3 -> setOf("audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3", "audio/x-mpeg")
        ADTS -> setOf("audio/vnd.dlna.adts", "audio/aac", "audio/aacp", "audio/x-aac", "audio/mp4", "audio/3gpp")
    }

    fun pnPrefixes(): Set<String> = when (this) {
        LPCM -> setOf("LPCM")
        WAV -> setOf("WAV", "LPCM")
        MP3 -> setOf("MP3")
        ADTS -> setOf("AAC_ADTS", "AAC_ISO", "HEAAC")
    }

    companion object {
        fun fromId(value: String?): DlnaCodec? =
            entries.firstOrNull { it.id.equals(value, true) }
    }
}

enum class DlnaFormatPreference(val id: String) {
    AUTO("auto"),
    LPCM("lpcm"),
    WAV("wav"),
    MP3("mp3"),
    ADTS("adts");

    fun codec(): DlnaCodec? = when (this) {
        AUTO -> null
        LPCM -> DlnaCodec.LPCM
        WAV -> DlnaCodec.WAV
        MP3 -> DlnaCodec.MP3
        ADTS -> DlnaCodec.ADTS
    }

    companion object {
        fun fromId(value: String?): DlnaFormatPreference =
            entries.firstOrNull { it.id.equals(value, true) } ?: AUTO
    }
}

data class DlnaServerConfig(
    val enabled: Boolean = false,
    val port: Int = 8081,
    val preference: DlnaFormatPreference = DlnaFormatPreference.AUTO,
    val selectedUdns: Set<String> = emptySet(),
    val title: String = "WiFi Audio Streaming"
)

object DlnaSelection {
    private const val SEPARATOR = '|'

    fun encode(udn: String, displayName: String): String =
        "$udn$SEPARATOR${displayName.replace(SEPARATOR, ' ').trim()}"

    fun udnOf(entry: String): String = entry.substringBefore(SEPARATOR).trim()

    fun nameOf(entry: String): String {
        val name = entry.substringAfter(SEPARATOR, "").trim()
        return name.ifBlank { udnOf(entry) }
    }

    fun udns(entries: List<String>): Set<String> =
        entries.map { udnOf(it) }.filter { it.isNotBlank() }.toSet()

    fun contains(entries: List<String>, udn: String): Boolean =
        entries.any { udnOf(it) == udn }

    fun toggle(entries: List<String>, renderer: DlnaRenderer): List<String> =
        if (contains(entries, renderer.udn)) entries.filterNot { udnOf(it) == renderer.udn }
        else entries + encode(renderer.udn, renderer.displayName)
}

data class DlnaProtocolInfo(
    val protocol: String,
    val network: String,
    val contentFormat: String,
    val additionalInfo: String
) {
    val mimeBase: String = contentFormat.substringBefore(';').trim().lowercase(Locale.ROOT)

    val profileName: String? = Regex("DLNA\\.ORG_PN=([^;\\s]+)", RegexOption.IGNORE_CASE)
        .find(additionalInfo)?.groupValues?.get(1)

    val isWildcard: Boolean = contentFormat.trim() == "*"

    companion object {
        fun parse(raw: String): DlnaProtocolInfo? {
            val parts = raw.trim().split(':', limit = 4)
            if (parts.size < 3) return null
            return DlnaProtocolInfo(
                protocol = parts[0].trim(),
                network = parts[1].trim(),
                contentFormat = parts[2].trim(),
                additionalInfo = parts.getOrElse(3) { "" }.trim()
            )
        }

        fun parseList(raw: String?): List<DlnaProtocolInfo> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(',')
                .mapNotNull { parse(it) }
                .filter { it.protocol.equals("http-get", true) }
        }
    }
}

data class DlnaQuirks(
    val name: String = "generic",
    val preferredOrder: List<DlnaCodec> = listOf(DlnaCodec.LPCM, DlnaCodec.WAV, DlnaCodec.MP3, DlnaCodec.ADTS),
    val mimeOverride: Map<DlnaCodec, String> = emptyMap(),
    val pnOverride: Map<DlnaCodec, String> = emptyMap(),
    val flags: String = DlnaConst.DEFAULT_FLAGS,
    val requireContentLength: Boolean = false,
    val didlUpnpClass: String = "object.item.audioItem.audioBroadcast",
    val sendDidl: Boolean = true,
    val stopBeforeSetUri: Boolean = true,
    val playDelayMs: Long = 250L,
    val retryOnTransitionMs: Long = 700L
)

object DlnaQuirkTable {
    private data class Rule(
        val pattern: Regex,
        val apply: (DlnaQuirks) -> DlnaQuirks
    )

    private val rules = listOf(
        Rule(Regex("denon|marantz|heos", RegexOption.IGNORE_CASE)) {
            it.copy(name = "heos", playDelayMs = 600L, retryOnTransitionMs = 1200L)
        },
        Rule(Regex("sonos", RegexOption.IGNORE_CASE)) {
            it.copy(
                name = "sonos",
                preferredOrder = listOf(DlnaCodec.MP3, DlnaCodec.WAV, DlnaCodec.ADTS, DlnaCodec.LPCM),
                didlUpnpClass = "object.item.audioItem.audioBroadcast",
                playDelayMs = 400L
            )
        },
        Rule(Regex("samsung", RegexOption.IGNORE_CASE)) {
            it.copy(
                name = "samsung",
                preferredOrder = listOf(DlnaCodec.MP3, DlnaCodec.WAV, DlnaCodec.LPCM, DlnaCodec.ADTS),
                requireContentLength = true
            )
        },
        Rule(Regex("\\blg\\b|webos", RegexOption.IGNORE_CASE)) {
            it.copy(
                name = "lg",
                preferredOrder = listOf(DlnaCodec.MP3, DlnaCodec.WAV, DlnaCodec.LPCM, DlnaCodec.ADTS),
                mimeOverride = mapOf(DlnaCodec.WAV to "audio/x-wav")
            )
        },
        Rule(Regex("yamaha|musiccast", RegexOption.IGNORE_CASE)) {
            it.copy(
                name = "yamaha",
                preferredOrder = listOf(DlnaCodec.WAV, DlnaCodec.LPCM, DlnaCodec.MP3, DlnaCodec.ADTS),
                playDelayMs = 500L
            )
        }
    )

    fun forDevice(manufacturer: String?, modelName: String?, friendlyName: String?): DlnaQuirks {
        val haystack = listOfNotNull(manufacturer, modelName, friendlyName).joinToString(" ")
        var result = DlnaQuirks()
        rules.forEach { rule ->
            if (rule.pattern.containsMatchIn(haystack)) result = rule.apply(result)
        }
        return result
    }
}

data class DlnaService(
    val serviceType: String,
    val controlUrl: String
)

data class DlnaRenderer(
    val udn: String,
    val friendlyName: String,
    val manufacturer: String,
    val modelName: String,
    val location: String,
    val address: String,
    val avTransport: DlnaService,
    val connectionManager: DlnaService?,
    val renderingControl: DlnaService?,
    val sinkProtocolInfo: List<DlnaProtocolInfo>,
    val quirks: DlnaQuirks,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() = friendlyName.ifBlank { modelName.ifBlank { udn } }

    val subtitle: String
        get() = listOf(manufacturer, modelName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { address }
}

data class DlnaNegotiation(
    val codec: DlnaCodec,
    val mime: String,
    val profileName: String,
    val negotiated: Boolean,
    val reason: String
)

object DlnaXml {
    fun parse(bytes: ByteArray): Document? =
        parseWith(bytes, rejectDoctype = true) ?: parseWith(bytes, rejectDoctype = false)

    private fun parseWith(bytes: ByteArray, rejectDoctype: Boolean): Document? {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        factory.isValidating = false
        if (rejectDoctype) {
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { factory.isXIncludeAware = false }
        return runCatching {
            factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
                setErrorHandler(null)
            }.parse(ByteArrayInputStream(bytes))
        }.getOrNull()
    }

    fun localName(node: Node): String = node.nodeName.substringAfterLast(':')

    fun children(element: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = element.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element) out.add(node)
        }
        return out
    }

    fun child(element: Element, name: String): Element? =
        children(element).firstOrNull { localName(it).equals(name, true) }

    fun childText(element: Element, name: String): String? =
        child(element, name)?.textContent?.trim()?.ifBlank { null }

    fun descendants(root: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(element: Element) {
            if (localName(element).equals(name, true)) out.add(element)
            children(element).forEach { walk(it) }
        }
        walk(root)
        return out
    }

    fun escape(value: String): String = buildString(value.length + 16) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (ch.code in 0x20..0xFFFD || ch == '\n' || ch == '\r' || ch == '\t') append(ch)
            }
        }
    }
}

object DlnaDiagnostics {
    private const val MAX_ENTRIES = 400
    private val entries = ArrayDeque<String>()
    private val lock = Any()

    fun record(tag: String, message: String) {
        val stamp = java.time.LocalTime.now().withNano(0).toString()
        val line = "[$stamp][$tag] ${message.take(4000)}"
        synchronized(lock) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        AppDebug.log("[DLNA][$tag] ${message.take(400)}")
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    fun report(): String {
        val head = buildString {
            appendLine("WiFi Audio Streaming - DLNA diagnostics")
            appendLine("generated: ${java.time.LocalDateTime.now().withNano(0)}")
            appendLine("os: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine("java: ${System.getProperty("java.version")}")
            appendLine("----------------------------------------")
        }
        val body = synchronized(lock) { entries.joinToString("\n") }
        return head + body
    }
}

object DlnaHttp {
    fun get(url: String, timeoutMs: Int = DlnaConst.READ_TIMEOUT_MS): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = DlnaConst.CONNECT_TIMEOUT_MS
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", DlnaConst.USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Connection", "close")
            }
            if (connection.responseCode !in 200..299) {
                DlnaDiagnostics.record("http", "GET $url -> ${connection.responseCode}")
                null
            } else {
                connection.inputStream.use { it.readNBytes(DlnaConst.MAX_XML_BYTES) }
            }
        } catch (e: Exception) {
            DlnaDiagnostics.record("http", "GET $url failed: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}

data class DlnaSoapResult(
    val success: Boolean,
    val values: Map<String, String>,
    val errorCode: Int?,
    val errorDescription: String?,
    val httpStatus: Int
)

object DlnaSoap {
    fun invoke(
        controlUrl: String,
        serviceType: String,
        action: String,
        arguments: List<Pair<String, String>>,
        timeoutMs: Int = DlnaConst.READ_TIMEOUT_MS
    ): DlnaSoapResult {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
            append("<s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(DlnaXml.escape(serviceType)).append("\">")
            arguments.forEach { (key, value) ->
                append('<').append(key).append('>')
                append(DlnaXml.escape(value))
                append("</").append(key).append('>')
            }
            append("</u:").append(action).append('>')
            append("</s:Body></s:Envelope>")
        }
        val payload = body.toByteArray(Charsets.UTF_8)
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = DlnaConst.CONNECT_TIMEOUT_MS
                readTimeout = timeoutMs
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
                setRequestProperty("User-Agent", DlnaConst.USER_AGENT)
                setRequestProperty("Connection", "close")
                setFixedLengthStreamingMode(payload.size)
            }
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            val responseBytes = runCatching {
                (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readNBytes(DlnaConst.MAX_XML_BYTES) }
            }.getOrNull() ?: ByteArray(0)

            DlnaDiagnostics.record(
                "soap",
                "$action -> HTTP $status @ $controlUrl :: ${String(responseBytes, Charsets.UTF_8).take(600)}"
            )

            val document = DlnaXml.parse(responseBytes)
            val root = document?.documentElement
            if (status in 200..299 && root != null) {
                val responseElement = DlnaXml.descendants(root, "${action}Response").firstOrNull()
                val values = HashMap<String, String>()
                responseElement?.let { element ->
                    DlnaXml.children(element).forEach { child ->
                        values[DlnaXml.localName(child)] = child.textContent ?: ""
                    }
                }
                DlnaSoapResult(true, values, null, null, status)
            } else {
                val code = root?.let { DlnaXml.descendants(it, "errorCode").firstOrNull()?.textContent?.trim()?.toIntOrNull() }
                val description = root?.let { DlnaXml.descendants(it, "errorDescription").firstOrNull()?.textContent?.trim() }
                DlnaSoapResult(false, emptyMap(), code, description, status)
            }
        } catch (e: Exception) {
            DlnaDiagnostics.record("soap", "$action failed @ $controlUrl :: ${e.javaClass.simpleName} ${e.message}")
            DlnaSoapResult(false, emptyMap(), null, e.message, -1)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}

object DlnaDeviceParser {
    fun parse(location: String, xml: ByteArray): DlnaRenderer? {
        val document = DlnaXml.parse(xml) ?: run {
            DlnaDiagnostics.record("desc", "unparsable description at $location")
            return null
        }
        val root = document.documentElement ?: return null
        val baseUrl = DlnaXml.descendants(root, "URLBase").firstOrNull()?.textContent?.trim()?.ifBlank { null }
            ?: location

        val deviceElement = DlnaXml.descendants(root, "device").firstOrNull { device ->
            DlnaXml.childText(device, "deviceType")?.contains("MediaRenderer", true) == true
        } ?: return null

        val services = DlnaXml.descendants(deviceElement, "service").mapNotNull { service ->
            val type = DlnaXml.childText(service, "serviceType") ?: return@mapNotNull null
            val control = DlnaXml.childText(service, "controlURL") ?: return@mapNotNull null
            type to DlnaService(type, resolve(baseUrl, control) ?: return@mapNotNull null)
        }

        fun pick(kind: String): DlnaService? =
            services.firstOrNull { it.first.contains(":$kind:", true) }?.second

        val avTransport = pick(DlnaConst.TYPE_AVTRANSPORT) ?: run {
            DlnaDiagnostics.record("desc", "no AVTransport service at $location")
            return null
        }

        val udn = DlnaXml.childText(deviceElement, "UDN")?.removePrefix("uuid:")?.trim()
            ?: location
        val friendly = DlnaXml.childText(deviceElement, "friendlyName").orEmpty()
        val manufacturer = DlnaXml.childText(deviceElement, "manufacturer").orEmpty()
        val model = DlnaXml.childText(deviceElement, "modelName").orEmpty()
        val host = runCatching { URI(location).host }.getOrNull().orEmpty()

        DlnaDiagnostics.record(
            "desc",
            "$friendly | $manufacturer | $model | udn=$udn | avt=${avTransport.controlUrl} | type=${avTransport.serviceType}"
        )

        return DlnaRenderer(
            udn = udn,
            friendlyName = friendly,
            manufacturer = manufacturer,
            modelName = model,
            location = location,
            address = host,
            avTransport = avTransport,
            connectionManager = pick(DlnaConst.TYPE_CONNECTION_MANAGER),
            renderingControl = pick(DlnaConst.TYPE_RENDERING_CONTROL),
            sinkProtocolInfo = emptyList(),
            quirks = DlnaQuirkTable.forDevice(manufacturer, model, friendly)
        )
    }

    fun resolve(base: String, reference: String): String? = runCatching {
        val trimmed = reference.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed
        else URI(base).resolve(if (trimmed.startsWith("/")) trimmed else "/$trimmed").toString()
    }.getOrNull()
}

object DlnaSsdp {
    fun discover(
        preferred: NetworkInterface?,
        perAddressTimeoutMs: Int = 2600
    ): List<String> {
        val locations = LinkedHashSet<String>()
        val addresses = localAddresses(preferred)
        if (addresses.isEmpty()) {
            DlnaDiagnostics.record("ssdp", "no usable IPv4 interface address found")
            return emptyList()
        }
        addresses.forEach { local ->
            runCatching { searchFrom(local, perAddressTimeoutMs, locations) }
                .onFailure { DlnaDiagnostics.record("ssdp", "search on ${local.hostAddress} failed: ${it.message}") }
        }
        return locations.toList()
    }

    private fun localAddresses(preferred: NetworkInterface?): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        val candidates = ArrayList<NetworkInterface>()
        preferred?.let { candidates.add(it) }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                val usable = runCatching {
                    iface.isUp && !iface.isLoopback && iface.supportsMulticast()
                }.getOrDefault(false)
                if (usable) candidates.add(iface)
            }
        }
        candidates.forEach { iface ->
            runCatching {
                iface.inetAddresses.toList().forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        out.add(address)
                    }
                }
            }
        }
        return out.toList()
    }

    private fun searchFrom(local: InetAddress, timeoutMs: Int, sink: MutableSet<String>) {
        val group = InetAddress.getByName(DlnaConst.SSDP_ADDR_V4)
        val socket = MulticastSocket(InetSocketAddress(local, 0))
        try {
            socket.timeToLive = 4
            socket.soTimeout = 350
            runCatching { socket.networkInterface = NetworkInterface.getByInetAddress(local) }

            val searchTargets = listOf(DlnaConst.ST_MEDIA_RENDERER, "ssdp:all")
            searchTargets.forEach { target ->
                val request = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: ${DlnaConst.SSDP_ADDR_V4}:${DlnaConst.SSDP_PORT}\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: $target\r\n")
                    append("USER-AGENT: ${DlnaConst.USER_AGENT}\r\n")
                    append("CPFN.UPNP.ORG: WiFi Audio Streaming\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.US_ASCII)
                repeat(2) {
                    runCatching {
                        socket.send(DatagramPacket(request, request.size, group, DlnaConst.SSDP_PORT))
                    }
                    Thread.sleep(60)
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val headers = parseHeaders(text)
                val location = headers["location"] ?: continue
                val searchTarget = headers["st"] ?: headers["nt"] ?: ""
                val server = headers["server"] ?: ""
                val interesting = searchTarget.contains("MediaRenderer", true) ||
                        searchTarget.contains("AVTransport", true) ||
                        searchTarget == "upnp:rootdevice" ||
                        searchTarget.startsWith("uuid:")
                if (!interesting) continue
                if (sink.add(location)) {
                    DlnaDiagnostics.record("ssdp", "candidate $location st=$searchTarget server=$server")
                }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    fun parseHeaders(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        raw.split("\r\n", "\n").drop(1).forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) {
                out[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
            }
        }
        return out
    }
}

object DlnaNegotiator {
    fun negotiate(
        renderer: DlnaRenderer,
        available: Set<DlnaCodec>,
        preference: DlnaFormatPreference,
        sampleRate: Int,
        channels: Int
    ): DlnaNegotiation? {
        if (available.isEmpty()) return null

        val forced = preference.codec()
        if (forced != null) {
            if (forced !in available) return null
            val match = renderer.sinkProtocolInfo.firstOrNull { matches(it, forced) }
            return build(renderer, forced, match, sampleRate, channels, false, "forced by user")
        }

        val order = renderer.quirks.preferredOrder.filter { it in available } +
                available.filter { it !in renderer.quirks.preferredOrder }

        val sink = renderer.sinkProtocolInfo
        if (sink.isEmpty()) {
            val fallback = order.firstOrNull() ?: return null
            return build(renderer, fallback, null, sampleRate, channels, false, "sink protocolInfo unavailable")
        }

        order.forEach { codec ->
            val match = sink.firstOrNull { matches(it, codec) }
            if (match != null) {
                return build(renderer, codec, match, sampleRate, channels, true, "matched ${match.contentFormat}")
            }
        }

        if (sink.any { it.isWildcard }) {
            val fallback = order.firstOrNull() ?: return null
            return build(renderer, fallback, null, sampleRate, channels, false, "renderer advertises wildcard sink")
        }

        val fallback = order.firstOrNull() ?: return null
        return build(renderer, fallback, null, sampleRate, channels, false, "no intersection, using fallback")
    }

    private fun matches(info: DlnaProtocolInfo, codec: DlnaCodec): Boolean {
        if (info.isWildcard) return false
        val profile = info.profileName
        if (profile != null && codec.pnPrefixes().any { profile.startsWith(it, true) }) return true
        return codec.mimeAliases().any { alias -> info.mimeBase == alias || info.mimeBase.startsWith(alias) }
    }

    private fun build(
        renderer: DlnaRenderer,
        codec: DlnaCodec,
        match: DlnaProtocolInfo?,
        sampleRate: Int,
        channels: Int,
        negotiated: Boolean,
        reason: String
    ): DlnaNegotiation {
        val quirks = renderer.quirks
        val mime = quirks.mimeOverride[codec]
            ?: match?.contentFormat?.takeIf { it.isNotBlank() && it != "*" && codec != DlnaCodec.LPCM }
            ?: codec.defaultMime(sampleRate, channels)
        val profile = quirks.pnOverride[codec] ?: match?.profileName ?: codec.defaultPn
        return DlnaNegotiation(codec, mime, profile, negotiated, reason)
    }
}

object DlnaDidl {
    fun build(
        renderer: DlnaRenderer,
        negotiation: DlnaNegotiation,
        url: String,
        title: String
    ): String {
        val quirks = renderer.quirks
        val protocolInfo = buildString {
            append("http-get:*:").append(negotiation.mime).append(':')
            append("DLNA.ORG_PN=").append(negotiation.profileName)
            append(";DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=").append(quirks.flags)
        }
        return buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
            append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">")
            append("<item id=\"").append(DlnaConst.DIDL_ITEM_ID).append("\" parentID=\"0\" restricted=\"1\">")
            append("<dc:title>").append(DlnaXml.escape(title)).append("</dc:title>")
            append("<dc:creator>WiFi Audio Streaming</dc:creator>")
            append("<upnp:artist>WiFi Audio Streaming</upnp:artist>")
            append("<upnp:class>").append(quirks.didlUpnpClass).append("</upnp:class>")
            append("<res protocolInfo=\"").append(DlnaXml.escape(protocolInfo)).append("\">")
            append(DlnaXml.escape(url))
            append("</res>")
            append("</item></DIDL-Lite>")
        }
    }
}

object DlnaActions {
    fun getSinkProtocolInfo(renderer: DlnaRenderer): List<DlnaProtocolInfo> {
        val service = renderer.connectionManager ?: return emptyList()
        val result = DlnaSoap.invoke(service.controlUrl, service.serviceType, "GetProtocolInfo", emptyList())
        if (!result.success) return emptyList()
        return DlnaProtocolInfo.parseList(result.values["Sink"])
    }

    fun setUriAndPlay(
        renderer: DlnaRenderer,
        url: String,
        didl: String
    ): Boolean {
        val service = renderer.avTransport
        val quirks = renderer.quirks

        if (quirks.stopBeforeSetUri) {
            DlnaSoap.invoke(service.controlUrl, service.serviceType, "Stop", listOf("InstanceID" to "0"))
        }

        val metadata = if (quirks.sendDidl) didl else ""
        var setResult = DlnaSoap.invoke(
            service.controlUrl, service.serviceType, "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to url, "CurrentURIMetaData" to metadata)
        )

        if (!setResult.success && setResult.errorCode == 705) {
            DlnaSoap.invoke(service.controlUrl, service.serviceType, "Stop", listOf("InstanceID" to "0"))
            Thread.sleep(quirks.retryOnTransitionMs)
            setResult = DlnaSoap.invoke(
                service.controlUrl, service.serviceType, "SetAVTransportURI",
                listOf("InstanceID" to "0", "CurrentURI" to url, "CurrentURIMetaData" to metadata)
            )
        }

        if (!setResult.success && quirks.sendDidl) {
            setResult = DlnaSoap.invoke(
                service.controlUrl, service.serviceType, "SetAVTransportURI",
                listOf("InstanceID" to "0", "CurrentURI" to url, "CurrentURIMetaData" to "")
            )
        }

        if (!setResult.success) {
            DlnaDiagnostics.record(
                "session",
                "SetAVTransportURI rejected by ${renderer.displayName}: ${setResult.errorCode} ${setResult.errorDescription}"
            )
            return false
        }

        if (quirks.playDelayMs > 0) Thread.sleep(quirks.playDelayMs)

        var playResult = DlnaSoap.invoke(
            service.controlUrl, service.serviceType, "Play",
            listOf("InstanceID" to "0", "Speed" to "1")
        )
        if (!playResult.success && playResult.errorCode == 701) {
            Thread.sleep(quirks.retryOnTransitionMs)
            playResult = DlnaSoap.invoke(
                service.controlUrl, service.serviceType, "Play",
                listOf("InstanceID" to "0", "Speed" to "1")
            )
        }
        if (!playResult.success) {
            DlnaDiagnostics.record(
                "session",
                "Play rejected by ${renderer.displayName}: ${playResult.errorCode} ${playResult.errorDescription}"
            )
        }
        return playResult.success
    }

    fun stop(renderer: DlnaRenderer) {
        val service = renderer.avTransport
        DlnaSoap.invoke(service.controlUrl, service.serviceType, "Stop", listOf("InstanceID" to "0"))
    }

    fun transportState(renderer: DlnaRenderer): String? {
        val service = renderer.avTransport
        val result = DlnaSoap.invoke(
            service.controlUrl, service.serviceType, "GetTransportInfo",
            listOf("InstanceID" to "0"), timeoutMs = 4000
        )
        if (!result.success) return null
        return result.values["CurrentTransportState"]?.trim()
    }

    fun setVolume(renderer: DlnaRenderer, percent: Int): Boolean {
        val service = renderer.renderingControl ?: return false
        val clamped = percent.coerceIn(0, 100)
        return DlnaSoap.invoke(
            service.controlUrl, service.serviceType, "SetVolume",
            listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to clamped.toString())
        ).success
    }
}

object DlnaRegistry {
    private val renderers = ConcurrentHashMap<String, DlnaRenderer>()

    fun snapshot(): List<DlnaRenderer> =
        renderers.values.sortedBy { it.displayName.lowercase(Locale.ROOT) }

    fun byUdn(udn: String): DlnaRenderer? = renderers[udn]

    fun put(renderer: DlnaRenderer) {
        renderers[renderer.udn] = renderer
    }

    fun clear() = renderers.clear()

    fun pruneOlderThan(maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        renderers.entries.removeIf { it.value.lastSeen < cutoff }
    }

    fun refresh(preferred: NetworkInterface?, withProtocolInfo: Boolean): List<DlnaRenderer> {
        val locations = DlnaSsdp.discover(preferred)
        val seen = HashSet<String>()
        locations.forEach { location ->
            val xml = DlnaHttp.get(location) ?: return@forEach
            val parsed = DlnaDeviceParser.parse(location, xml) ?: return@forEach
            val existing = renderers[parsed.udn]
            val sink = when {
                !withProtocolInfo -> existing?.sinkProtocolInfo ?: emptyList()
                else -> DlnaActions.getSinkProtocolInfo(parsed).ifEmpty {
                    existing?.sinkProtocolInfo ?: emptyList()
                }
            }
            if (withProtocolInfo && sink.isNotEmpty()) {
                DlnaDiagnostics.record(
                    "sink",
                    "${parsed.displayName} advertises ${sink.size} http-get entries: " +
                            sink.joinToString(" | ") { "${it.contentFormat}${it.profileName?.let { p -> " [$p]" } ?: ""}" }
                )
            }
            val merged = parsed.copy(sinkProtocolInfo = sink, lastSeen = System.currentTimeMillis())
            renderers[merged.udn] = merged
            seen.add(merged.udn)
        }
        pruneOlderThan(5 * 60 * 1000L)
        return snapshot()
    }
}
