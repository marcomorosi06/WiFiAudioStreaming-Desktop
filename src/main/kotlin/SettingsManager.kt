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

import java.util.prefs.Preferences

data class AllSettings(
    val app: AppSettings,
    val audio: AudioSettings_V1,
    val streamingPort: String,
    val micPort: String,
    val micRoutingMode: String = "OFF"
)

data class AppSettings(
    val theme: Theme = Theme.System,
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
    val lastMulticastMode: Boolean = false,
    val autoConnectClientEnabled: Boolean = false,
    val autoConnectIps: List<String> = emptyList(),
    val connectionSoundEnabled: Boolean = true,
    val disconnectionSoundEnabled: Boolean = true,
    val useNativeEngine: Boolean = true,
    val startMinimizedToTray: Boolean = false,
    val closeToTray: Boolean = true,
    val autoUpdateCheckEnabled: Boolean = true,
    val securityMode: String = "OFF",
    val authKey: String = "",
    val encryptionEnabled: Boolean = false,
    val developerMode: Boolean = false,
    val noiseReductionEnabled: Boolean = false,
    val noiseReductionStrength: Int = 50
)

object SettingsRepository {
    private val prefs = Preferences.userRoot().node("com/mavco/wifiaudiostreamer")

    private const val THEME_KEY = "app_theme"
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
    private const val LATENCY_MS_KEY = "audio_latency_ms"
    private const val MAX_PAYLOAD_KEY = "audio_max_payload"
    private const val STREAMING_PORT_KEY = "net_streaming_port"
    private const val MIC_PORT_KEY = "net_mic_port"
    private const val NETWORK_INTERFACE_KEY = "net_interface"
    private const val RTP_PORT_KEY = "server_rtp_port"
    private const val LAUNCH_AT_STARTUP_KEY = "launch_at_startup"
    private const val AUTO_START_SERVER_KEY = "auto_start_server"
    private const val AUTO_START_MULTICAST_KEY = "auto_start_multicast"
    private const val LAST_MULTICAST_MODE_KEY = "last_multicast_mode"
    private const val AUTO_CONNECT_CLIENT_KEY = "auto_connect_client"
    private const val AUTO_CONNECT_IPS_KEY = "auto_connect_ips"
    private const val CONNECTION_SOUND_KEY = "connection_sound_enabled"
    private const val DISCONNECTION_SOUND_KEY = "disconnection_sound_enabled"
    private const val USE_NATIVE_ENGINE_KEY = "use_native_engine"
    private const val MIC_ROUTING_MODE_KEY = "mic_routing_mode"
    private const val START_MINIMIZED_TRAY_KEY = "start_minimized_tray"
    private const val CLOSE_TO_TRAY_KEY       = "close_to_tray"
    private const val HAS_SEEN_WELCOME_KEY     = "has_seen_welcome"
    private const val HAS_SEEN_CLI_WELCOME_KEY = "has_seen_cli_welcome"
    private const val LAST_SEEN_CHANGELOG_KEY  = "last_seen_changelog_version"
    private const val AUTO_UPDATE_CHECK_KEY    = "auto_update_check"
    private const val SECURITY_MODE_KEY        = "server_security_mode"
    private const val AUTH_KEY_KEY             = "server_auth_key"
    private const val ENCRYPTION_KEY           = "server_encryption_enabled"

    fun hasSeenWelcome(): Boolean    = prefs.getBoolean(HAS_SEEN_WELCOME_KEY,     false)
    fun markWelcomeSeen()            { prefs.putBoolean(HAS_SEEN_WELCOME_KEY,     true); runCatching { prefs.flush() } }
    fun hasSeenCliWelcome(): Boolean = prefs.getBoolean(HAS_SEEN_CLI_WELCOME_KEY, false)
    fun markCliWelcomeSeen()         { prefs.putBoolean(HAS_SEEN_CLI_WELCOME_KEY, true); runCatching { prefs.flush() } }
    fun lastSeenChangelog(): String  = prefs.get(LAST_SEEN_CHANGELOG_KEY, "")
    fun setLastSeenChangelog(v: String) { prefs.put(LAST_SEEN_CHANGELOG_KEY, v); runCatching { prefs.flush() } }
    fun isAutoUpdateCheckEnabled(): Boolean = loadSettings().app.autoUpdateCheckEnabled
    fun setAutoUpdateCheckEnabled(b: Boolean) {
        val s = loadSettings()
        saveSettings(s.copy(app = s.app.copy(autoUpdateCheckEnabled = b)))
    }

    // Multicast encryption: server monotonic session epoch (survives reboot) and
    // the highest epoch a client has accepted per server IP (anti ghost-replay).
    fun nextMcastEpoch(): Long {
        val e = prefs.getLong("mcast_server_epoch", 0L) + 1L
        prefs.putLong("mcast_server_epoch", e); runCatching { prefs.flush() }
        return e
    }
    fun getMcastClientEpoch(ip: String): Long = prefs.getLong("mcast_client_epoch_$ip", 0L)
    fun setMcastClientEpoch(ip: String, e: Long) { prefs.putLong("mcast_client_epoch_$ip", e); runCatching { prefs.flush() } }

    fun isDonationQualified(): Boolean = prefs.getBoolean("donation_qualified", false)
    fun setDonationQualified(b: Boolean) { prefs.putBoolean("donation_qualified", b); runCatching { prefs.flush() } }
    fun donationSnoozeUntil(): Long = prefs.getLong("donation_snooze_until", 0L)
    fun setDonationSnoozeUntil(t: Long) { prefs.putLong("donation_snooze_until", t); runCatching { prefs.flush() } }
    fun donationDismissCount(): Int = prefs.getInt("donation_dismiss_count", 0)
    fun setDonationDismissCount(n: Int) { prefs.putInt("donation_dismiss_count", n); runCatching { prefs.flush() } }
    fun donationBackoffDays(count: Int): Long = when {
        count <= 1 -> 2L
        count == 2 -> 5L
        count == 3 -> 14L
        else -> 30L
    }

    fun saveSettings(settings: AllSettings) {
        runCatching { ConfigManager.save(settings) }
    }

    fun loadSettings(): AllSettings {
        if (ConfigManager.exists()) {
            return runCatching { ConfigManager.load() }.getOrDefault(ConfigManager.DEFAULTS)
        }
        val migrated = if (hasLegacyPreferences()) loadFromPreferencesLegacy() else ConfigManager.DEFAULTS
        runCatching { ConfigManager.save(migrated) }
        return migrated
    }

    private fun hasLegacyPreferences(): Boolean = try {
        prefs.get(STREAMING_PORT_KEY, null) != null ||
            prefs.get(THEME_KEY, null) != null ||
            prefs.get(SECURITY_MODE_KEY, null) != null
    } catch (_: Exception) { false }

    private fun loadFromPreferencesLegacy(): AllSettings {
        val themeName = prefs.get(THEME_KEY, Theme.System.name)
        val theme = try { Theme.valueOf(themeName) } catch (e: Exception) { Theme.System }
        val hidePrivacy = prefs.getBoolean(HIDE_PRIVACY_KEY, false)
        val hideRouting = prefs.getBoolean(HIDE_ROUTING_KEY, false)
        val colorString = prefs.get(CUSTOM_COLOR_KEY, null)
        val customColor = colorString?.toLongOrNull()
        val rtpEnabled = prefs.getBoolean(RTP_ENABLED_KEY, false)
        val httpEnabled = prefs.getBoolean(HTTP_ENABLED_KEY, false)
        val httpPort = prefs.get(HTTP_PORT_KEY, "8080")
        val httpSafariMode = prefs.getBoolean(HTTP_SAFARI_MODE_KEY, false)
        val sampleRate = prefs.getFloat(SAMPLE_RATE_KEY, 48000f)
        val bitDepth = prefs.getInt(BIT_DEPTH_KEY, 16).let { if (it == 16) it else 16 }
        val channels = prefs.getInt(CHANNELS_KEY, 2)
        val bufferSize = prefs.getInt(BUFFER_SIZE_KEY, 512)
        val latencyMs = prefs.getInt(LATENCY_MS_KEY, 120)
        val maxPayloadBytes = prefs.getInt(MAX_PAYLOAD_KEY, 1390)
        val audioSettings = AudioSettings_V1(sampleRate, bitDepth, channels, bufferSize, latencyMs, maxPayloadBytes)
        val streamingPort = prefs.get(STREAMING_PORT_KEY, "9090")
        val micPort = prefs.get(MIC_PORT_KEY, "9092")
        val netInterface = prefs.get(NETWORK_INTERFACE_KEY, "Auto")
        val rtpPort = prefs.get(RTP_PORT_KEY, "9094")
        val launchAtStartup = prefs.getBoolean(LAUNCH_AT_STARTUP_KEY, false)
        val autoStartServer = prefs.getBoolean(AUTO_START_SERVER_KEY, false)
        val autoStartMulticast = prefs.getBoolean(AUTO_START_MULTICAST_KEY, true)
        val lastMulticastMode = prefs.getBoolean(LAST_MULTICAST_MODE_KEY, false)
        val autoConnectClientEnabled = prefs.getBoolean(AUTO_CONNECT_CLIENT_KEY, false)
        val ipsString = prefs.get(AUTO_CONNECT_IPS_KEY, "")
        val autoConnectIps = if (ipsString.isNotEmpty()) ipsString.split(",") else emptyList()
        val connectionSoundEnabled = prefs.getBoolean(CONNECTION_SOUND_KEY, true)
        val disconnectionSoundEnabled = prefs.getBoolean(DISCONNECTION_SOUND_KEY, true)
        val useNativeEngine = prefs.getBoolean(USE_NATIVE_ENGINE_KEY, true)
        val micRoutingMode = prefs.get(MIC_ROUTING_MODE_KEY, "OFF")
        val startMinimizedToTray = prefs.getBoolean(START_MINIMIZED_TRAY_KEY, false)
        val closeToTray = prefs.getBoolean(CLOSE_TO_TRAY_KEY, true)
        val autoUpdateCheckEnabled = prefs.getBoolean(AUTO_UPDATE_CHECK_KEY, true)
        val securityMode = prefs.get(SECURITY_MODE_KEY, "OFF")
        val authKey = prefs.get(AUTH_KEY_KEY, "")
        val encryptionEnabled = prefs.getBoolean(ENCRYPTION_KEY, false)

        val appSettings = AppSettings(
            theme = theme,
            hideWindowsPrivacyBanner = hidePrivacy,
            hideWindowsRoutingBanner = hideRouting,
            customThemeColor = customColor,
            rtpEnabled = rtpEnabled,
            httpEnabled = httpEnabled,
            httpPort = httpPort,
            httpSafariMode = httpSafariMode,
            networkInterface = netInterface,
            rtpPort = rtpPort,
            launchAtStartup = launchAtStartup,
            autoStartServer = autoStartServer,
            autoStartMulticast = autoStartMulticast,
            lastMulticastMode = lastMulticastMode,
            autoConnectClientEnabled = autoConnectClientEnabled,
            autoConnectIps = autoConnectIps,
            connectionSoundEnabled = connectionSoundEnabled,
            disconnectionSoundEnabled = disconnectionSoundEnabled,
            useNativeEngine = useNativeEngine,
            startMinimizedToTray = startMinimizedToTray,
            closeToTray = closeToTray,
            autoUpdateCheckEnabled = autoUpdateCheckEnabled,
            securityMode = securityMode,
            authKey = authKey,
            encryptionEnabled = encryptionEnabled
        )
        return AllSettings(appSettings, audioSettings, streamingPort, micPort, micRoutingMode)
    }
}
