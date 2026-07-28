import java.net.Inet4Address
import java.net.NetworkInterface

object UsbLink {

    const val DEFAULT_USB_LATENCY_MS = 20
    const val MIN_USB_LATENCY_MS = 5
    const val MAX_USB_LATENCY_MS = 120

    private val IFACE_TOKENS = listOf("rndis", "ncm", "usb")
    private val TETHER_SUBNETS = listOf("192.168.42.", "192.168.112.")

    enum class Stage { DISABLED, NOT_FOUND, READY }

    enum class Platform { WINDOWS, MACOS, LINUX }

    data class State(
        val stage: Stage = Stage.DISABLED,
        val interfaceName: String? = null,
        val displayName: String? = null,
        val localAddress: String? = null
    ) {
        val isReady: Boolean get() = stage == Stage.READY
    }

    val platform: Platform by lazy {
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("win") -> Platform.WINDOWS
            os.contains("mac") || os.contains("darwin") -> Platform.MACOS
            else -> Platform.LINUX
        }
    }

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var latencyMs: Int = DEFAULT_USB_LATENCY_MS
        private set

    @Volatile
    var preferredName: String = "Auto"
        private set

    @Volatile
    var state: State = State()
        private set

    @Volatile private var cachedInterface: NetworkInterface? = null

    @Volatile private var overridden = false

    fun configure(
        on: Boolean,
        presetLatencyMs: Int = DEFAULT_USB_LATENCY_MS,
        prefer: String = "Auto",
        override: Boolean = false
    ) {
        if (overridden && !override) return
        if (override) overridden = true
        val newLatency = presetLatencyMs.coerceIn(MIN_USB_LATENCY_MS, MAX_USB_LATENCY_MS)
        val unchanged = on == enabled && newLatency == latencyMs && prefer == preferredName
        latencyMs = newLatency
        preferredName = prefer
        enabled = on
        if (!on) {
            cachedInterface = null
            state = State()
            return
        }
        if (unchanged && cachedInterface != null) return
        NetAddr.invalidateScan()
        refresh(force = true)
    }

    private const val SCAN_THROTTLE_MS = 1000L

    @Volatile private var lastScanAt = 0L

    fun refresh(force: Boolean = false): State {
        if (!enabled) {
            state = State()
            return state
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastScanAt < SCAN_THROTTLE_MS) return state
        lastScanAt = now
        val iface = findInterface()
        cachedInterface = iface
        val addr = iface?.let { firstIpv4(it) }
        val next = State(
            stage = if (iface != null && addr != null) Stage.READY else Stage.NOT_FOUND,
            interfaceName = iface?.name,
            displayName = iface?.displayName ?: iface?.name,
            localAddress = addr
        )
        if (next != state) {
            state = next
            AppDebug.log("[USB] state=$next platform=$platform")
        }
        return next
    }

    fun activeInterface(): NetworkInterface? {
        if (!enabled) return null
        val cached = cachedInterface
        if (cached != null && runCatching { cached.isUp }.getOrDefault(false) &&
            firstIpv4(cached) != null
        ) return cached
        refresh()
        return cachedInterface
    }

    fun isReady(): Boolean = enabled && activeInterface() != null

    fun effectiveLatencyMs(configured: Int): Int =
        if (isReady()) minOf(configured, latencyMs) else configured

    fun isUsbAddress(host: String?): Boolean {
        if (host == null) return false
        return TETHER_SUBNETS.any { host.startsWith(it) }
    }

    private fun sharesSubnet(iface: NetworkInterface, host: String): Boolean {
        val peer = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return false
        if (peer !is java.net.Inet4Address) return false
        val peerBits = toInt(peer.address)
        return runCatching {
            iface.interfaceAddresses.any { ia ->
                val local = ia.address
                if (local !is java.net.Inet4Address) return@any false
                val prefix = ia.networkPrefixLength.toInt()
                if (prefix !in 1..32) return@any false
                val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
                (toInt(local.address) and mask) == (peerBits and mask)
            }
        }.getOrDefault(false)
    }

    fun isUsbPeer(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val iface = activeInterface() ?: return false
        return sharesSubnet(iface, host) || isUsbAddress(host)
    }

    @Volatile private var detectedCache: NetworkInterface? = null
    @Volatile private var detectedAt = 0L

    fun detectedInterface(): NetworkInterface? {
        if (enabled) activeInterface()?.let { return it }
        val now = System.currentTimeMillis()
        if (detectedAt != 0L && now - detectedAt < SCAN_THROTTLE_MS) return detectedCache
        detectedAt = now
        detectedCache = findInterface()
        return detectedCache
    }

    fun isUsbPeerDetected(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        if (isUsbAddress(host)) return true
        val iface = detectedInterface() ?: return false
        return sharesSubnet(iface, host)
    }

    private fun toInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)

    fun findInterface(): NetworkInterface? {
        if (preferredName != "Auto") {
            val forced = runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .firstOrNull { it.name == preferredName || it.displayName == preferredName }
            }.getOrNull()
            if (forced != null && firstIpv4(forced) != null) return forced
        }
        return scored().maxByOrNull { it.second }?.first
    }

    fun candidates(): List<NetworkInterface> =
        scored().sortedByDescending { it.second }.map { it.first }

    private fun scored(): List<Pair<NetworkInterface, Int>> {
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull() ?: return emptyList()
        return all.mapNotNull { iface ->
            val usable = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            if (!usable || firstIpv4(iface) == null) null else iface to score(iface)
        }.filter { it.second > 0 }
    }

    private fun score(iface: NetworkInterface): Int {
        var s = 0
        val name = iface.name?.lowercase().orEmpty()
        val display = runCatching { iface.displayName?.lowercase() }.getOrNull().orEmpty()
        if (display.contains("remote ndis")) s += 120
        if (display.contains("ndis")) s += 60
        if (display.contains("internet sharing")) s += 60
        if (display.contains("tether")) s += 60
        if (display.contains("android")) s += 40
        if (display.contains("gadget")) s += 70
        if (display.contains("cdc")) s += 70
        if (IFACE_TOKENS.any { display.contains(it) }) s += 70
        if (IFACE_TOKENS.any { name.startsWith(it) }) s += 70
        if (platform == Platform.LINUX && Regex("^en.*u[0-9]+").containsMatchIn(name)) s += 70
        if (ipv4List(iface).any { host -> TETHER_SUBNETS.any { host.startsWith(it) } }) s += 90
        if (display.contains("virtual") || display.contains("vmware") ||
            display.contains("hyper-v") || display.contains("wsl") ||
            display.contains("loopback") || display.contains("tap") ||
            display.contains("tunnel")
        ) s -= 300
        if (display.contains("wi-fi") || display.contains("wireless") ||
            name.startsWith("wlan") || name.startsWith("wl")
        ) s -= 300
        return s
    }

    private fun ipv4List(iface: NetworkInterface): List<String> = runCatching {
        iface.inetAddresses.toList()
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private fun firstIpv4(iface: NetworkInterface): String? = ipv4List(iface).firstOrNull()

    fun inspect(): List<String> {
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull()
            ?: return listOf("enumeration failed: NetworkInterface.getNetworkInterfaces() returned nothing")
        if (all.isEmpty()) return listOf("enumeration returned no interfaces")
        return all.map { iface ->
            runCatching {
                val name = runCatching { iface.name }.getOrDefault("?")
                val display = runCatching { iface.displayName }.getOrNull() ?: "-"
                val v4 = ipv4List(iface).joinToString(",").ifBlank { "-" }
                val v6 = runCatching {
                    iface.inetAddresses.toList()
                        .filterIsInstance<java.net.Inet6Address>()
                        .mapNotNull { it.hostAddress }
                        .joinToString(",")
                }.getOrNull()?.ifBlank { "-" } ?: "-"
                val up = runCatching { iface.isUp }.getOrDefault(false)
                val virt = runCatching { iface.isVirtual }.getOrDefault(false)
                val mcast = runCatching { iface.supportsMulticast() }.getOrDefault(false)
                "$name | $display | v4=$v4 | v6=$v6 | up=$up virtual=$virt mcast=$mcast | score=${score(iface)}"
            }.getOrElse { "interface unreadable: ${it.javaClass.simpleName}: ${it.message}" }
        }
    }

    fun diagnosticKey(): String = when {
        !enabled -> "usb_diag_disabled"
        state.isReady -> "usb_diag_ready"
        platform == Platform.MACOS -> "usb_diag_macos"
        platform == Platform.WINDOWS -> "usb_diag_windows"
        else -> "usb_diag_linux"
    }
}
