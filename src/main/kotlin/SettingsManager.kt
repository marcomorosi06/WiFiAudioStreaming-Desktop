import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

data class AllSettings(
    val app: AppSettings,
    val audio: AudioSettings_V1,
    val streamingPort: String,
    val micPort: String
)

data class AppSettings(
    val theme: Theme = Theme.System,
    val experimentalFeaturesEnabled: Boolean = false,
    val hideWindowsPrivacyBanner: Boolean = false,
    val hideWindowsRoutingBanner: Boolean = false,
    val customThemeColor: Long? = null,
    val rtpEnabled: Boolean = false,
    val httpEnabled: Boolean = false,
    val httpPort: String = "8080",
    val httpSafariMode: Boolean = false,
    val networkInterface: String = "Auto",
    val rtpPort: String = "9094",
    val launchAtStartup: Boolean = false,
    val autoStartServer: Boolean = false,
    val autoStartMulticast: Boolean = true,
    val autoConnectClientEnabled: Boolean = false,
    val autoConnectIps: List<String> = emptyList(),
    val connectionSoundEnabled: Boolean = true,
    val disconnectionSoundEnabled: Boolean = true,
    val useNativeEngine: Boolean = true
)

object SettingsRepository {
    private val prefs = Preferences.userRoot().node("com/mavco/wifiaudiostreamer")

    private const val THEME_KEY = "app_theme"
    private const val EXPERIMENTAL_KEY = "experimental_features"
    private const val HIDE_PRIVACY_KEY = "hide_windows_privacy"
    private const val HIDE_ROUTING_KEY = "hide_windows_routing"
    private const val CUSTOM_COLOR_KEY = "custom_theme_color"
    private const val RTP_ENABLED_KEY = "server_rtp_enabled"
    private const val HTTP_ENABLED_KEY = "server_http_enabled"
    private const val HTTP_PORT_KEY = "server_http_port"
    private const val HTTP_SAFARI_MODE_KEY = "server_http_safari_mode"
    private const val SAMPLE_RATE_KEY = "audio_sample_rate"
    private const val BIT_DEPTH_KEY = "audio_bit_depth"
    private const val CHANNELS_KEY = "audio_channels"
    private const val BUFFER_SIZE_KEY = "audio_buffer_size"
    private const val STREAMING_PORT_KEY = "net_streaming_port"
    private const val MIC_PORT_KEY = "net_mic_port"
    private const val NETWORK_INTERFACE_KEY = "net_interface"
    private const val RTP_PORT_KEY = "server_rtp_port"
    private const val LAUNCH_AT_STARTUP_KEY = "launch_at_startup"
    private const val AUTO_START_SERVER_KEY = "auto_start_server"
    private const val AUTO_START_MULTICAST_KEY = "auto_start_multicast"
    private const val AUTO_CONNECT_CLIENT_KEY = "auto_connect_client"
    private const val AUTO_CONNECT_IPS_KEY = "auto_connect_ips"
    private const val CONNECTION_SOUND_KEY = "connection_sound_enabled"
    private const val DISCONNECTION_SOUND_KEY = "disconnection_sound_enabled"
    private const val USE_NATIVE_ENGINE_KEY = "use_native_engine"

    fun saveSettings(settings: AllSettings) {
        try {
            prefs.put(THEME_KEY, settings.app.theme.name)
            prefs.putBoolean(EXPERIMENTAL_KEY, settings.app.experimentalFeaturesEnabled)
            prefs.putBoolean(HIDE_PRIVACY_KEY, settings.app.hideWindowsPrivacyBanner)
            prefs.putBoolean(HIDE_ROUTING_KEY, settings.app.hideWindowsRoutingBanner)

            if (settings.app.customThemeColor != null) {
                prefs.put(CUSTOM_COLOR_KEY, settings.app.customThemeColor.toString())
            } else {
                prefs.remove(CUSTOM_COLOR_KEY)
            }

            prefs.putBoolean(RTP_ENABLED_KEY, settings.app.rtpEnabled)
            prefs.putBoolean(HTTP_ENABLED_KEY, settings.app.httpEnabled)
            prefs.put(HTTP_PORT_KEY, settings.app.httpPort)
            prefs.putBoolean(HTTP_SAFARI_MODE_KEY, settings.app.httpSafariMode)
            prefs.putFloat(SAMPLE_RATE_KEY, settings.audio.sampleRate)
            prefs.putInt(BIT_DEPTH_KEY, settings.audio.bitDepth)
            prefs.putInt(CHANNELS_KEY, settings.audio.channels)
            prefs.putInt(BUFFER_SIZE_KEY, settings.audio.bufferSize)
            prefs.put(STREAMING_PORT_KEY, settings.streamingPort)
            prefs.put(MIC_PORT_KEY, settings.micPort)
            prefs.put(NETWORK_INTERFACE_KEY, settings.app.networkInterface)
            prefs.put(RTP_PORT_KEY, settings.app.rtpPort)
            prefs.putBoolean(LAUNCH_AT_STARTUP_KEY, settings.app.launchAtStartup)
            prefs.putBoolean(AUTO_START_SERVER_KEY, settings.app.autoStartServer)
            prefs.putBoolean(AUTO_START_MULTICAST_KEY, settings.app.autoStartMulticast)
            prefs.putBoolean(AUTO_CONNECT_CLIENT_KEY, settings.app.autoConnectClientEnabled)
            prefs.put(AUTO_CONNECT_IPS_KEY, settings.app.autoConnectIps.joinToString(","))
            prefs.putBoolean(CONNECTION_SOUND_KEY, settings.app.connectionSoundEnabled)
            prefs.putBoolean(DISCONNECTION_SOUND_KEY, settings.app.disconnectionSoundEnabled)
            prefs.putBoolean(USE_NATIVE_ENGINE_KEY, settings.app.useNativeEngine)
            prefs.flush()
        } catch (e: BackingStoreException) {}
    }

    fun loadSettings(): AllSettings {
        val themeName = prefs.get(THEME_KEY, Theme.System.name)
        val theme = try { Theme.valueOf(themeName) } catch (e: Exception) { Theme.System }
        val experimental = prefs.getBoolean(EXPERIMENTAL_KEY, false)
        val hidePrivacy = prefs.getBoolean(HIDE_PRIVACY_KEY, false)
        val hideRouting = prefs.getBoolean(HIDE_ROUTING_KEY, false)
        val colorString = prefs.get(CUSTOM_COLOR_KEY, null)
        val customColor = colorString?.toLongOrNull()
        val rtpEnabled = prefs.getBoolean(RTP_ENABLED_KEY, false)
        val httpEnabled = prefs.getBoolean(HTTP_ENABLED_KEY, false)
        val httpPort = prefs.get(HTTP_PORT_KEY, "8080")
        val httpSafariMode = prefs.getBoolean(HTTP_SAFARI_MODE_KEY, false)
        val sampleRate = prefs.getFloat(SAMPLE_RATE_KEY, 48000f)
        val bitDepth = prefs.getInt(BIT_DEPTH_KEY, 16)
        val channels = prefs.getInt(CHANNELS_KEY, 2)
        val bufferSize = prefs.getInt(BUFFER_SIZE_KEY, 6400)
        val audioSettings = AudioSettings_V1(sampleRate, bitDepth, channels, bufferSize)
        val streamingPort = prefs.get(STREAMING_PORT_KEY, "9090")
        val micPort = prefs.get(MIC_PORT_KEY, "9092")
        val netInterface = prefs.get(NETWORK_INTERFACE_KEY, "Auto")
        val rtpPort = prefs.get(RTP_PORT_KEY, "9094")
        val launchAtStartup = prefs.getBoolean(LAUNCH_AT_STARTUP_KEY, false)
        val autoStartServer = prefs.getBoolean(AUTO_START_SERVER_KEY, false)
        val autoStartMulticast = prefs.getBoolean(AUTO_START_MULTICAST_KEY, true)
        val autoConnectClientEnabled = prefs.getBoolean(AUTO_CONNECT_CLIENT_KEY, false)
        val ipsString = prefs.get(AUTO_CONNECT_IPS_KEY, "")
        val autoConnectIps = if (ipsString.isNotEmpty()) ipsString.split(",") else emptyList()
        val connectionSoundEnabled = prefs.getBoolean(CONNECTION_SOUND_KEY, true)
        val disconnectionSoundEnabled = prefs.getBoolean(DISCONNECTION_SOUND_KEY, true)
        val useNativeEngine = prefs.getBoolean(USE_NATIVE_ENGINE_KEY, true)

        val appSettings = AppSettings(
            theme, experimental, hidePrivacy, hideRouting, customColor,
            rtpEnabled, httpEnabled, httpPort, httpSafariMode, netInterface,
            rtpPort, launchAtStartup, autoStartServer, autoStartMulticast,
            autoConnectClientEnabled, autoConnectIps,
            connectionSoundEnabled, disconnectionSoundEnabled,
            useNativeEngine
        )
        return AllSettings(appSettings, audioSettings, streamingPort, micPort)
    }
}