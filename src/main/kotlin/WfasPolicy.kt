import java.net.NetworkInterface

object WfasPolicy {

    const val MODE_ALWAYS = "ALWAYS"
    const val MODE_OFF = "OFF"
    const val MODE_OFF_ON_USB = "OFF_ON_USB"

    val MODES = listOf(MODE_ALWAYS, MODE_OFF_ON_USB, MODE_OFF)

    @Volatile
    var mode: String = MODE_OFF_ON_USB
        private set

    @Volatile private var overridden = false

    fun configure(value: String?, override: Boolean = false) {
        if (overridden && !override) return
        if (override) overridden = true
        mode = when (value?.uppercase()) {
            MODE_OFF -> MODE_OFF
            MODE_OFF_ON_USB -> MODE_OFF_ON_USB
            MODE_ALWAYS -> MODE_ALWAYS
            else -> MODE_OFF_ON_USB
        }
    }

    fun enabledOnUsb(): Boolean = UsbLink.isReady()

    fun enabledOnNetwork(): Boolean = when (mode) {
        MODE_OFF -> false
        MODE_ALWAYS -> true
        else -> !UsbLink.isReady()
    }

    fun enabledOn(iface: NetworkInterface): Boolean =
        if (MulticastNet.isUsbInterface(iface)) enabledOnUsb() else enabledOnNetwork()

    fun enabledForPeer(ip: String?): Boolean =
        if (UsbLink.isUsbPeer(ip)) enabledOnUsb() else enabledOnNetwork()

    fun hostOf(address: String?): String? {
        if (address.isNullOrBlank()) return null
        var s = address.trim()
        val named = Regex("hostname=([^,)\\s]+)").find(s)
        if (named != null) return named.groupValues[1].ifBlank { null }
        val slash = s.lastIndexOf('/')
        if (slash >= 0) s = s.substring(slash + 1)
        val colon = s.lastIndexOf(':')
        if (colon > 0) s = s.substring(0, colon)
        return s.ifBlank { null }
    }

    fun enabledForPeerAddress(address: String?): Boolean = enabledForPeer(hostOf(address))

    fun enabledAnywhere(): Boolean = enabledOnNetwork() || enabledOnUsb()

    fun canStartServer(
        rtpEnabled: Boolean,
        httpEnabled: Boolean,
        dlnaEnabled: Boolean = false,
        snapcastEnabled: Boolean = false
    ): Boolean =
        enabledAnywhere() || rtpEnabled || httpEnabled || dlnaEnabled || snapcastEnabled

    // Versione pura per la UI: Compose non ricompone leggendo UsbLink.isReady(),
    // quindi lo stato del collegamento va passato come parametro osservabile.
    fun canStartServerWith(
        mode: String,
        usbReady: Boolean,
        rtpEnabled: Boolean,
        httpEnabled: Boolean,
        dlnaEnabled: Boolean = false,
        snapcastEnabled: Boolean = false
    ): Boolean {
        val onNetwork = when (mode) {
            MODE_OFF -> false
            MODE_ALWAYS -> true
            else -> !usbReady
        }
        return onNetwork || usbReady || rtpEnabled || httpEnabled || dlnaEnabled || snapcastEnabled
    }
}
