import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetAddr {

    data class Local(
        val address: InetAddress,
        val ifaceName: String,
        val score: Int
    ) {
        val isV6: Boolean get() = address is Inet6Address
        val host: String get() = address.hostAddress ?: ""
        val hostNoZone: String get() = host.substringBefore('%')
    }

    fun isV6Literal(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val bare = value.trim().removeSurrounding("[", "]")
        return bare.contains(':')
    }

    fun display(host: String?): String {
        val h = host?.trim().orEmpty()
        if (h.isEmpty()) return h
        if (!isV6Literal(h)) return h
        val bare = h.removeSurrounding("[", "]")
        return "[$bare]"
    }

    fun hostPort(host: String?, port: Int): String = "${display(host)}:$port"

    fun normalize(input: String?): String {
        var s = input?.trim().orEmpty()
        if (s.isEmpty()) return s
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close > 0) return s.substring(1, close)
        }
        if (s.count { it == ':' } == 1) s = s.substringBefore(':')
        return s
    }

    fun splitHostPort(input: String?, defaultPort: Int): Pair<String, Int> {
        val s = input?.trim().orEmpty()
        if (s.isEmpty()) return "" to defaultPort
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close > 0) {
                val host = s.substring(1, close)
                val rest = s.substring(close + 1)
                val port = rest.removePrefix(":").toIntOrNull() ?: defaultPort
                return host to port
            }
        }
        if (s.count { it == ':' } == 1) {
            val host = s.substringBefore(':')
            val port = s.substringAfter(':').toIntOrNull() ?: defaultPort
            return host to port
        }
        return s to defaultPort
    }

    fun resolve(host: String?): InetAddress? {
        val h = normalize(host)
        if (h.isEmpty()) return null
        return runCatching { InetAddress.getByName(h) }.getOrNull()
    }

    fun literalHost(socketAddress: Any?): String? {
        if (socketAddress == null) return null
        val raw = runCatching {
            socketAddress.javaClass
                .getMethod("getAddress\$ktor_network")
                .invoke(socketAddress)
        }.getOrNull() as? java.net.InetSocketAddress ?: return null
        val inet = raw.address ?: return null
        return inet.hostAddress?.ifBlank { null }
    }

    fun literalHostOfText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        var s = text.trim()
        val slash = s.lastIndexOf('/')
        if (slash >= 0) s = s.substring(slash + 1)
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close > 0) return s.substring(1, close).ifBlank { null }
        }
        val colon = s.lastIndexOf(':')
        if (colon > 0 && s.substring(colon + 1).toIntOrNull() != null) s = s.substring(0, colon)
        return s.ifBlank { null }
    }

    enum class Family { AUTO, V4, V6 }

    @Volatile
    var preferredFamily: Family = Family.AUTO

    fun configureFamily(value: Family) {
        preferredFamily = value
    }

    fun allows(address: InetAddress): Boolean = when (preferredFamily) {
        Family.AUTO -> true
        Family.V4 -> address is Inet4Address
        Family.V6 -> address is Inet6Address
    }

    fun score(address: InetAddress): Int {
        if (address.isLoopbackAddress) return -1000
        val base = when {
            address is Inet4Address -> when {
                address.isLinkLocalAddress -> 10
                address.isSiteLocalAddress -> 100
                else -> 80
            }
            address is Inet6Address -> when {
                address.isLinkLocalAddress -> 40
                isUniqueLocal(address) -> 90
                address.isSiteLocalAddress -> 70
                else -> 85
            }
            else -> 0
        }
        val bias = when (preferredFamily) {
            Family.AUTO -> 0
            Family.V4 -> if (address is Inet4Address) 500 else -400
            Family.V6 -> if (address is Inet6Address) 500 else -400
        }
        return base + bias
    }

    private fun isUniqueLocal(address: Inet6Address): Boolean {
        val b = address.address
        return b.isNotEmpty() && (b[0].toInt() and 0xFE) == 0xFC
    }

    private const val SCAN_CACHE_MS = 1000L

    @Volatile private var cachedLocals: List<Local>? = null
    @Volatile private var cachedLocalsAt = 0L

    fun invalidateScan() {
        cachedLocals = null
    }

    fun localAddresses(): List<Local> {
        val now = System.currentTimeMillis()
        cachedLocals?.let { if (now - cachedLocalsAt < SCAN_CACHE_MS) return it }
        val out = mutableListOf<Local>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                val usable = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
                if (!usable) return@forEach
                val name = iface.displayName ?: iface.name ?: return@forEach
                if (name.contains("VMware", true) || name.contains("Hyper-V", true) ||
                    name.contains("WSL", true) || name.contains("Virtual", true)
                ) return@forEach
                iface.inetAddresses.toList().forEach { addr ->
                    if (addr.isLoopbackAddress || addr.isMulticastAddress) return@forEach
                    out.add(Local(addr, iface.name ?: name, score(addr)))
                }
            }
        }
        val sorted = out.sortedByDescending { it.score }
        cachedLocals = sorted
        cachedLocalsAt = now
        return sorted
    }

    fun bestLocalAddress(): String =
        localAddresses().firstOrNull()?.host ?: "127.0.0.1"

    fun isSelfAddress(host: String?): Boolean {
        val bare = normalize(host)
            .removeSurrounding("[", "]")
            .substringBefore('%')
            .lowercase()
        if (bare.isBlank()) return false
        if (bare == "127.0.0.1" || bare == "::1" || bare == "localhost") return true
        return localAddresses().any {
            it.hostNoZone.removeSurrounding("[", "]").lowercase() == bare
        }
    }

    fun hasIpv4(): Boolean = localAddresses().any { !it.isV6 }

    fun hasIpv6(): Boolean = localAddresses().any { it.isV6 }

    private val dualStackCapable: Boolean by lazy {
        runCatching {
            java.net.DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.bind(java.net.InetSocketAddress(InetAddress.getByName("::"), 0))
                true
            }
        }.getOrDefault(false)
    }

    fun wildcardHost(): String = when {
        preferredFamily == Family.V4 -> "0.0.0.0"
        preferredFamily == Family.V6 -> "::"
        !hasIpv4() && hasIpv6() && dualStackCapable -> "::"
        else -> "0.0.0.0"
    }

    fun wildcardFor(host: String?): String =
        if (isV6Literal(host)) "::" else "0.0.0.0"

    fun addressScore(host: String?): Int {
        val a = resolve(host) ?: return -1000
        return score(a)
    }

    fun shouldAdoptAddress(
        currentHost: String?,
        currentLastSeen: Long,
        candidateHost: String?,
        now: Long,
        staleAfterMs: Long = 10_000L
    ): Boolean {
        if (currentHost.isNullOrBlank()) return true
        if (currentHost == candidateHost) return true
        if (now - currentLastSeen > staleAfterMs) return true
        return addressScore(candidateHost) > addressScore(currentHost)
    }

    fun interfaceHasV4(iface: NetworkInterface): Boolean = runCatching {
        iface.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
    }.getOrDefault(false)

    fun interfaceHasV6(iface: NetworkInterface): Boolean = runCatching {
        iface.inetAddresses.toList().any { it is Inet6Address && !it.isLoopbackAddress }
    }.getOrDefault(false)
}
