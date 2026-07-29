import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

object MulticastNet {

    const val GROUP_V4 = "239.255.0.1"
    const val GROUP_V6 = "ff02::5746"

    val groupV4: InetAddress by lazy { InetAddress.getByName(GROUP_V4) }
    val groupV6: InetAddress? by lazy { runCatching { InetAddress.getByName(GROUP_V6) }.getOrNull() }

    fun groups(): List<InetAddress> =
        listOfNotNull(groupV4, groupV6).filter { NetAddr.allows(it) }

    fun joinCandidates(): List<NetworkInterface> {
        val out = LinkedHashSet<NetworkInterface>()
        UsbLink.activeInterface()?.let { out.add(it) }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                if (isUsable(iface)) out.add(iface)
            }
        }
        return out.toList()
    }

    fun sendCandidates(preferred: NetworkInterface?): List<NetworkInterface> {
        val out = LinkedHashSet<NetworkInterface>()
        UsbLink.activeInterface()?.let { out.add(it) }
        preferred?.let { if (isUsable(it)) out.add(it) }
        if (out.isEmpty()) out.addAll(joinCandidates())
        return out.toList()
    }

    private fun isUsable(iface: NetworkInterface): Boolean = runCatching {
        iface.isUp && !iface.isLoopback && iface.supportsMulticast() &&
                iface.inetAddresses.toList().any { !it.isLoopbackAddress }
    }.getOrDefault(false)

    private fun supports(iface: NetworkInterface, group: InetAddress): Boolean = when (group) {
        is Inet6Address -> NetAddr.interfaceHasV6(iface)
        is Inet4Address -> NetAddr.interfaceHasV4(iface)
        else -> false
    }

    fun joinAll(socket: MulticastSocket, group: InetAddress): Int {
        val addr = InetSocketAddress(group, 0)
        var joined = 0
        joinCandidates().filter { supports(it, group) }.forEach { iface ->
            runCatching {
                socket.joinGroup(addr, iface)
                joined++
                AppDebug.log("[MCAST] joined $group on ${iface.name}")
            }.onFailure { AppDebug.log("[MCAST] join $group on ${iface.name} failed: ${it.message}") }
        }
        if (joined == 0 && group is Inet4Address) {
            runCatching {
                @Suppress("DEPRECATION")
                socket.joinGroup(group)
                joined = 1
                AppDebug.log("[MCAST] joined $group on the default interface (per-interface join failed)")
            }
        }
        return joined
    }

    fun joinAllGroups(socket: MulticastSocket): Int =
        groups().sumOf { joinAll(socket, it) }

    fun leaveAll(socket: MulticastSocket, group: InetAddress) {
        val addr = InetSocketAddress(group, 0)
        joinCandidates().forEach { iface -> runCatching { socket.leaveGroup(addr, iface) } }
        if (group is Inet4Address) {
            runCatching {
                @Suppress("DEPRECATION")
                socket.leaveGroup(group)
            }
        }
    }

    fun leaveAllGroups(socket: MulticastSocket) {
        groups().forEach { leaveAll(socket, it) }
    }

    fun sendAll(
        socket: MulticastSocket,
        payload: ByteArray,
        port: Int,
        preferred: NetworkInterface?,
        accept: (NetworkInterface) -> Boolean = { true }
    ): Int {
        var sent = 0
        val reached = LinkedHashSet<String>()
        val ifaces = sendCandidates(preferred).filter(accept)
        groups().forEach { group ->
            ifaces.filter { supports(it, group) }.forEach { iface ->
                runCatching {
                    socket.networkInterface = iface
                    socket.send(DatagramPacket(payload, payload.size, group, port))
                    sent++
                    reached.add("${iface.name}/$group")
                }.onFailure {
                    AppDebug.log("[MCAST] send $group on ${iface.name} failed: ${it.message}")
                }
            }
        }
        ifaces.filter { isTetherLink(it) }.forEach { iface ->
            broadcastAddresses(iface).forEach { addr ->
                runCatching {
                    socket.broadcast = true
                    socket.send(DatagramPacket(payload, payload.size, addr, port))
                    sent++
                    reached.add("${iface.name}/$addr")
                }.onFailure {
                    AppDebug.log("[MCAST] broadcast $addr on ${iface.name} failed: ${it.message}")
                }
            }
        }
        val summary = reached.joinToString(",").ifEmpty { "nothing" }
        if (summary != lastSendSummary) {
            lastSendSummary = summary
            AppDebug.log("[MCAST] beacon now going out on: $summary")
        }
        return sent
    }

    @Volatile private var lastSendSummary = ""

    private fun isTetherLink(iface: NetworkInterface): Boolean {
        val usb = UsbLink.detectedInterface() ?: return false
        return usb.name == iface.name
    }

    fun broadcastAddresses(iface: NetworkInterface): List<InetAddress> = runCatching {
        iface.interfaceAddresses.mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())

    fun audioGroup(iface: NetworkInterface?): InetAddress {
        val v6 = groupV6
        if (NetAddr.preferredFamily == NetAddr.Family.V6 && v6 != null) return v6
        if (NetAddr.preferredFamily == NetAddr.Family.V4) return groupV4
        if (iface != null && v6 != null && !NetAddr.interfaceHasV4(iface) && NetAddr.interfaceHasV6(iface)) {
            return v6
        }
        if (iface == null && v6 != null && !NetAddr.hasIpv4() && NetAddr.hasIpv6()) return v6
        return groupV4
    }

    fun chooseSendInterface(
        preferred: NetworkInterface?,
        accept: (NetworkInterface) -> Boolean = { true }
    ): NetworkInterface? = sendCandidates(preferred).firstOrNull(accept) ?: preferred

    fun peerScore(host: String?): Int =
        NetAddr.addressScore(host) + if (UsbLink.isUsbPeer(host)) 200 else 0

    fun shouldAdoptPeer(
        currentHost: String?,
        currentLastSeen: Long,
        candidateHost: String?,
        now: Long,
        staleAfterMs: Long = 10_000L
    ): Boolean {
        if (currentHost.isNullOrBlank()) return true
        if (currentHost == candidateHost) return true
        if (now - currentLastSeen > staleAfterMs) return true
        return peerScore(candidateHost) > peerScore(currentHost)
    }

    fun isUsbInterface(iface: NetworkInterface): Boolean {
        val usb = UsbLink.activeInterface() ?: return false
        return usb.name == iface.name
    }
}
