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
    val dlnaEnabled: Boolean = false,
    val dlnaPort: String = "8081",
    val dlnaFormat: String = "auto",
    val dlnaDevices: List<String> = emptyList(),
    val snapcastEnabled: Boolean = false,
    val snapcastPort: String = SnapcastDefaults.STREAM_PORT.toString(),
    val snapcastControlPort: String = SnapcastDefaults.CONTROL_PORT.toString(),
    val snapcastCodec: String = SnapcastCodecs.PCM,
    val snapcastChunkMs: Int = SnapcastDefaults.CHUNK_MS,
    val snapcastBufferMs: Int = SnapcastDefaults.BUFFER_MS,
    val snapcastStreamName: String = SnapcastDefaults.STREAM_NAME,
    val networkInterface: String = "Auto",
    val rtpPort: String = "9094",
    val launchAtStartup: Boolean = false,
    val autoStartServer: Boolean = false,
    val autoStartMulticast: Boolean = true,
    /** Ritardo prima dell'avvio automatico: la rete non e' sempre pronta al login. */
    val autoStartDelaySec: Int = 0,
    /** Attende un indirizzo locale utilizzabile invece di partire e fallire. */
    val autoStartRequireNetwork: Boolean = true,
    /** Volume da applicare all'avvio automatico. -1 = lascia com'e'. */
    val autoStartVolume: Int = -1,
    /** Sicurezza del server avviato in automatico. INHERIT = quella generale. */
    val autoStartSecurityMode: String = AutoStartSecurity.INHERIT,
    /**
     * Cifratura del server avviato in automatico: INHERIT, ON oppure OFF.
     * E' una scelta separata da quella generale perche' le due cose servono a
     * momenti diversi: la sessione presidiata puo' restare in chiaro per farsi
     * ispezionare con Wireshark, mentre quella che parte da sola e resta su per
     * ore conviene cifrarla. Vale solo con una chiave: senza, non c'e' niente
     * con cui cifrare.
     */
    val autoStartEncryption: String = AutoStartSecurity.INHERIT,
    val muteRender: Boolean = true,
    val serverPersist: Boolean = false,
    val lastMulticastMode: Boolean = false,
    val autoConnectClientEnabled: Boolean = false,
    /**
     * Voci di connessione automatica in forma testuale (vedi [AutoConnectTarget]).
     * Il nome resta quello storico perche' una voce che era solo un indirizzo
     * continua a leggersi senza migrazioni.
     */
    val autoConnectIps: List<String> = emptyList(),
    /** Ogni quanto ricontrollare la lista, in secondi. */
    val autoConnectIntervalSec: Int = 5,
    /** Quanto aspettare dopo una disconnessione prima di riprovare, in secondi. */
    val autoConnectRetryDelaySec: Int = 10,
    /**
     * Se il server chiede la chiave e la voce non ne ha una salvata: chiedere
     * all'utente (true) o saltare quel server (false). Il default e' saltare,
     * perche' una procedura automatica che si ferma su una finestra modale non
     * e' piu' automatica.
     */
    val autoConnectPromptForKey: Boolean = false,
    val connectionSoundEnabled: Boolean = true,
    val disconnectionSoundEnabled: Boolean = true,
    val useNativeEngine: Boolean = true,
    val startMinimizedToTray: Boolean = false,
    val closeToTray: Boolean = true,
    val autoUpdateCheckEnabled: Boolean = true,
    val securityMode: String = "OFF",
    val authKey: String = "",
    val rememberAuthKey: Boolean = true,
    val encryptionEnabled: Boolean = false,
    val qrPairingEnabled: Boolean = false,
    val manualAuthKey: String = "",
    val developerMode: Boolean = false,
    val noiseReductionEnabled: Boolean = false,
    val noiseReductionStrength: Int = 50,
    val usbModeEnabled: Boolean = false,
    val usbLatencyMs: Int = 20,
    val usbInterface: String = "Auto",
    val linuxTray: String = LinuxTray.MODE_AUTO,
    val wfasMode: String = WfasPolicy.MODE_OFF_ON_USB,
    val vizEnabled: Boolean = true,
    val vizGroove: Int = 160
)

/** Modalita' di sicurezza applicabili al server avviato automaticamente. */
object AutoStartSecurity {
    const val INHERIT = "INHERIT"
    const val ON = "ON"
    const val OFF = "OFF"

    val MODES = listOf(INHERIT, "OFF", "ASK", "KEY")
    val ENCRYPTION = listOf(INHERIT, ON, OFF)

    /** Risolve la modalita' effettiva: INHERIT ricade su quella generale. */
    fun resolve(autoStart: String?, general: String?): String =
        if (autoStart.isNullOrBlank() || autoStart.equals(INHERIT, true))
            SecurityMode.fromStringSafe(general).name
        else
            SecurityMode.fromStringSafe(autoStart).name

    /**
     * Risolve la cifratura del server avviato automaticamente.
     *
     * [effectiveMode] e' la modalita' gia' risolta da [resolve]. La cifratura
     * poggia sulla chiave precondivisa: in OFF e in ASK la risposta e' no
     * qualunque cosa dica l'impostazione, altrimenti si prometterebbe una
     * protezione che non puo' esistere.
     */
    fun resolveEncryption(choice: String?, general: Boolean, effectiveMode: String): Boolean {
        if (!SecurityMode.requiresKey(effectiveMode)) return false
        return when {
            choice.isNullOrBlank() || choice.equals(INHERIT, true) -> general
            choice.equals(ON, true)  -> true
            choice.equals(OFF, true) -> false
            else -> general
        }
    }
}

/** Le voci di auto-connessione gia' decodificate. */
fun AppSettings.autoConnectTargets(): List<AutoConnectTarget> =
    AutoConnectTarget.parseList(autoConnectIps)

/** Solo quelle attive e con un indirizzo scritto. */
fun AppSettings.activeAutoConnectTargets(): List<AutoConnectTarget> =
    autoConnectTargets().filter { it.enabled && it.ip.isNotBlank() }

fun AppSettings.withAutoConnectTargets(list: List<AutoConnectTarget>): AppSettings =
    copy(autoConnectIps = AutoConnectTarget.serializeList(list))

fun AppSettings.toSnapcastConfig(): SnapcastServerConfig = SnapcastServerConfig(
    enabled = snapcastEnabled,
    streamPort = snapcastPort.toIntOrNull() ?: SnapcastDefaults.STREAM_PORT,
    controlPort = snapcastControlPort.toIntOrNull() ?: SnapcastDefaults.CONTROL_PORT,
    codec = SnapcastCodecs.normalize(snapcastCodec),
    chunkMs = if (snapcastChunkMs in SnapcastDefaults.CHUNK_CHOICES) snapcastChunkMs else SnapcastDefaults.CHUNK_MS,
    bufferMs = snapcastBufferMs.coerceIn(SnapcastDefaults.MIN_BUFFER_MS, SnapcastDefaults.MAX_BUFFER_MS),
    streamName = snapcastStreamName.ifBlank { SnapcastDefaults.STREAM_NAME }
)

fun AppSettings.toDlnaConfig(): DlnaServerConfig = DlnaServerConfig(
    enabled = dlnaEnabled,
    port = dlnaPort.toIntOrNull() ?: 8081,
    preference = DlnaFormatPreference.fromId(dlnaFormat),
    selectedUdns = DlnaSelection.udns(dlnaDevices),
    title = "WiFi Audio Streaming"
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
    private const val USB_MODE_KEY             = "net_usb_mode_enabled"
    private const val USB_LATENCY_KEY          = "net_usb_latency_ms"
    private const val USB_IFACE_KEY            = "net_usb_interface"

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

    const val VAULT_AUTH_KEY   = "authKey"
    const val VAULT_MANUAL_KEY = "manualAuthKey"
    const val ENV_AUTH_KEY     = "WFAS_AUTH_KEY"

    fun saveSettings(settings: AllSettings) {
        // Prima la custodia, poi il file: ConfigManager.save non scrive piu' i
        // segreti, quindi se il vault fallisce non restano comunque in chiaro.
        persistSecrets(settings)
        runCatching { ConfigManager.save(settings) }
        UsbLink.configure(settings.app.usbModeEnabled, settings.app.usbLatencyMs, settings.app.usbInterface)
        WfasPolicy.configure(settings.app.wfasMode)
    }

    fun loadSettings(): AllSettings {
        val stored = if (ConfigManager.exists()) {
            runCatching { ConfigManager.load() }.getOrDefault(ConfigManager.DEFAULTS)
        } else {
            val migrated = if (hasLegacyPreferences()) loadFromPreferencesLegacy() else ConfigManager.DEFAULTS
            runCatching { ConfigManager.save(migrated) }
            migrated
        }
        val settings = hydrateSecrets(stored)
        UsbLink.configure(settings.app.usbModeEnabled, settings.app.usbLatencyMs, settings.app.usbInterface)
        WfasPolicy.configure(settings.app.wfasMode)
        return settings
    }

    private fun persistSecrets(settings: AllSettings) {
        if (!SecretVault.available) return
        if (!settings.app.rememberAuthKey) {
            SecretVault.clear(VAULT_AUTH_KEY)
            SecretVault.clear(VAULT_MANUAL_KEY)
            return
        }
        settings.app.authKey.let {
            if (it.isNotEmpty()) SecretVault.store(VAULT_AUTH_KEY, it) else SecretVault.clear(VAULT_AUTH_KEY)
        }
        settings.app.manualAuthKey.let {
            if (it.isNotEmpty()) SecretVault.store(VAULT_MANUAL_KEY, it) else SecretVault.clear(VAULT_MANUAL_KEY)
        }
    }

    /**
     * I segreti non stanno piu' nel file di configurazione: arrivano dalla
     * custodia dell'OS, o da [ENV_AUTH_KEY] per chi fa girare il server senza
     * sessione desktop. Un valore ancora presente nel file viene da una versione
     * precedente: si trasferisce nella custodia e il file viene riscritto senza.
     */
    private fun hydrateSecrets(stored: AllSettings): AllSettings {
        val legacyAuth   = stored.app.authKey.takeIf { it.isNotEmpty() }
        val legacyManual = stored.app.manualAuthKey.takeIf { it.isNotEmpty() }
        val migrating    = legacyAuth != null || legacyManual != null

        if (migrating && stored.app.rememberAuthKey && SecretVault.available) {
            legacyAuth?.let { SecretVault.store(VAULT_AUTH_KEY, it) }
            legacyManual?.let { SecretVault.store(VAULT_MANUAL_KEY, it) }
        }

        val env = System.getenv(ENV_AUTH_KEY)?.takeIf { it.isNotBlank() }
        val auth   = env ?: legacyAuth   ?: SecretVault.load(VAULT_AUTH_KEY).orEmpty()
        val manual = legacyManual ?: SecretVault.load(VAULT_MANUAL_KEY).orEmpty()

        val hydrated = stored.copy(app = stored.app.copy(authKey = auth, manualAuthKey = manual))
        if (migrating) runCatching { ConfigManager.save(hydrated) }
        return hydrated
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
        val usbModeEnabled = prefs.getBoolean(USB_MODE_KEY, false)
        val usbLatencyMs = prefs.getInt(USB_LATENCY_KEY, UsbLink.DEFAULT_USB_LATENCY_MS)
        val usbInterface = prefs.get(USB_IFACE_KEY, "Auto")

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
            encryptionEnabled = encryptionEnabled,
            usbModeEnabled = usbModeEnabled,
            usbLatencyMs = usbLatencyMs,
            usbInterface = usbInterface
        )
        UsbLink.configure(usbModeEnabled, usbLatencyMs, usbInterface)
        return AllSettings(appSettings, audioSettings, streamingPort, micPort, micRoutingMode)
    }
}