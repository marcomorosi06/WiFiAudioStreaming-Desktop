import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

// A new container for all settings that need to be saved
data class AllSettings(
    val app: AppSettings,
    val audio: AudioSettings_V1,
    val streamingPort: String,
    val micPort: String
)

// --- The Repository for Saving/Loading ---

object SettingsRepository {
    private val prefs = Preferences.userRoot().node("com/mavco/wifiaudiostreamer")

    // --- Preference Keys ---
    private const val THEME_KEY = "app_theme"
    private const val EXPERIMENTAL_KEY = "experimental_features"
    private const val HIDE_PRIVACY_KEY = "hide_windows_privacy"
    private const val HIDE_ROUTING_KEY = "hide_windows_routing"
    private const val CUSTOM_COLOR_KEY = "custom_theme_color"
    // Protocolli server
    private const val RTP_ENABLED_KEY = "server_rtp_enabled"
    private const val HTTP_ENABLED_KEY = "server_http_enabled"
    private const val HTTP_PORT_KEY = "server_http_port"
    private const val HTTP_SAFARI_MODE_KEY = "server_http_safari_mode"
    // Audio
    private const val SAMPLE_RATE_KEY = "audio_sample_rate"
    private const val BIT_DEPTH_KEY = "audio_bit_depth"
    private const val CHANNELS_KEY = "audio_channels"
    private const val BUFFER_SIZE_KEY = "audio_buffer_size"
    // Rete
    private const val STREAMING_PORT_KEY = "net_streaming_port"
    private const val MIC_PORT_KEY = "net_mic_port"
    private const val NETWORK_INTERFACE_KEY = "net_interface"
    private const val RTP_PORT_KEY = "server_rtp_port"

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

            prefs.flush()
        } catch (e: BackingStoreException) {
            println("Error saving settings: ${e.message}")
        }
    }

    fun loadSettings(): AllSettings {
        val themeName = prefs.get(THEME_KEY, Theme.System.name)
        val theme = try { Theme.valueOf(themeName) } catch (e: Exception) { Theme.System }
        val experimental = prefs.getBoolean(EXPERIMENTAL_KEY, false)
        val hidePrivacy = prefs.getBoolean(HIDE_PRIVACY_KEY, false)
        val hideRouting = prefs.getBoolean(HIDE_ROUTING_KEY, false)

        val colorString = prefs.get(CUSTOM_COLOR_KEY, null)
        val customColor = colorString?.toLongOrNull()

        val rtpEnabled    = prefs.getBoolean(RTP_ENABLED_KEY, false)
        val httpEnabled   = prefs.getBoolean(HTTP_ENABLED_KEY, false)
        val httpPort      = prefs.get(HTTP_PORT_KEY, "8080")
        val httpSafariMode = prefs.getBoolean(HTTP_SAFARI_MODE_KEY, false)

        val sampleRate  = prefs.getFloat(SAMPLE_RATE_KEY, 48000f)
        val bitDepth    = prefs.getInt(BIT_DEPTH_KEY, 16)
        val channels    = prefs.getInt(CHANNELS_KEY, 2)
        val bufferSize  = prefs.getInt(BUFFER_SIZE_KEY, 6400)
        val audioSettings = AudioSettings_V1(sampleRate, bitDepth, channels, bufferSize)

        val streamingPort = prefs.get(STREAMING_PORT_KEY, "9090")
        val micPort       = prefs.get(MIC_PORT_KEY, "9092")
        val netInterface = prefs.get(NETWORK_INTERFACE_KEY, "Auto")
        val rtpPort = prefs.get(RTP_PORT_KEY, "9094")

        val appSettings = AppSettings(
            theme, experimental, hidePrivacy, hideRouting, customColor,
            rtpEnabled, httpEnabled, httpPort, httpSafariMode, netInterface, rtpPort
        )

        return AllSettings(appSettings, audioSettings, streamingPort, micPort)
    }
}