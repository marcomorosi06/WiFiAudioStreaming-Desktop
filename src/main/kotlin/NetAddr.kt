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

    fun score(address: InetAddress): Int = when {
        address.isLoopbackAddress -> -1000
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

    private fun isUniqueLocal(address: Inet6Address): Boolean {
        val b = address.address
        return b.isNotEmpty() && (b[0].toInt() and 0xFE) == 0xFC
    }

    fun localAddresses(): List<Local> {
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
        return out.sortedByDescending { it.score }
    }

    fun bestLocalAddress(): String =
        localAddresses().firstOrNull()?.host ?: "127.0.0.1"

    fun hasIpv4(): Boolean = localAddresses().any { !it.isV6 }

    fun hasIpv6(): Boolean = localAddresses().any { it.isV6 }

    fun interfaceHasV4(iface: NetworkInterface): Boolean = runCatching {
        iface.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
    }.getOrDefault(false)

    fun interfaceHasV6(iface: NetworkInterface): Boolean = runCatching {
        iface.inetAddresses.toList().any { it is Inet6Address && !it.isLoopbackAddress }
    }.getOrDefault(false)
}
