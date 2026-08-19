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

import java.io.File
import java.util.concurrent.TimeUnit

object LinuxTray {

    const val MODE_AUTO = "AUTO"
    const val MODE_ON = "ON"
    const val MODE_OFF = "OFF"

    val MODES = listOf(MODE_AUTO, MODE_ON, MODE_OFF)

    private const val MARKER_NAME = "tray-attempt.lock"
    private const val SNI_SERVICE = "org.kde.StatusNotifierWatcher"

    /** Oltre questo numero di crash consecutivi AUTO smette di riprovare. */
    private const val MAX_CRASHES = 2

    @Volatile
    var forcedOff = false
        private set

    /** L'ultima ragione per cui la tray non e' stata creata, per la diagnostica. */
    @Volatile
    var lastSkipReason: String? = null
        private set

    /** True se l'ultima chiamata a [install] ha davvero creato l'icona. */
    @Volatile
    var installed = false
        private set

    @Volatile private var instance: dorkbox.systemTray.SystemTray? = null
    @Volatile private var baseIcon: java.awt.image.BufferedImage? = null
    @Volatile private var baseTooltip: String = "WiFi Audio Streaming"

    fun reflectCapture(kinds: Set<CaptureMonitor.Kind>) {
        val tray = instance ?: return
        val base = baseIcon ?: return
        runCatching {
            val image = CaptureIcon.badge(base, kinds) ?: base
            tray.setImage(image)
            tray.setTooltip(
                if (kinds.isEmpty()) baseTooltip
                else "$baseTooltip - ${CaptureMonitor.summary()}"
            )
        }.onFailure { AppDebug.log("[TRAY] capture indicator failed: ${it.message}") }
    }

    fun disableForThisRun() { forcedOff = true }

    private fun markerFile(): File = File(ConfigPaths.configDir(), MARKER_NAME)

    private fun env(name: String): String =
        System.getenv(name)?.trim()?.lowercase().orEmpty()

    fun sessionIsWayland(): Boolean =
        env("XDG_SESSION_TYPE") == "wayland" || env("WAYLAND_DISPLAY").isNotEmpty()

    fun gdkForcedToX11(): Boolean = env("GDK_BACKEND") == "x11"

    /** KDE, LXQt e Unity ospitano SNI di serie; GNOME solo con l'estensione. */
    fun desktop(): String {
        val raw = listOf("XDG_CURRENT_DESKTOP", "XDG_SESSION_DESKTOP", "DESKTOP_SESSION")
            .map { env(it) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return raw
    }

    fun isKde(): Boolean = desktop().let { it.contains("kde") || it.contains("plasma") }

    // ── Rilevamento StatusNotifierWatcher ────────────────────────────────────

    private var sniCache: Boolean? = null

    /**
     * Chiede al bus di sessione se qualcuno possiede il nome dello watcher.
     * Si prova con gli strumenti standard nell'ordine in cui e' probabile
     * trovarli; se non c'e' nessuno dei tre si ripiega sull'ambiente, dove KDE
     * e' un'indicazione piu' che sufficiente.
     */
    fun statusNotifierAvailable(): Boolean {
        sniCache?.let { return it }
        val result = probeSni()
        sniCache = result
        AppDebug.log("[TRAY] StatusNotifierWatcher present: $result (desktop='${desktop()}')")
        return result
    }

    private fun probeSni(): Boolean {
        if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank() &&
            System.getenv("XDG_RUNTIME_DIR").isNullOrBlank()
        ) return false

        val gdbus = run(
            listOf(
                "gdbus", "call", "--session",
                "--dest", "org.freedesktop.DBus",
                "--object-path", "/org/freedesktop/DBus",
                "--method", "org.freedesktop.DBus.NameHasOwner", SNI_SERVICE
            )
        )
        if (gdbus != null) return gdbus.contains("true")

        val dbusSend = run(
            listOf(
                "dbus-send", "--session", "--print-reply",
                "--dest=org.freedesktop.DBus", "/org/freedesktop/DBus",
                "org.freedesktop.DBus.NameHasOwner", "string:$SNI_SERVICE"
            )
        )
        if (dbusSend != null) return dbusSend.contains("boolean true")

        val qdbus = run(listOf("qdbus", SNI_SERVICE))
        if (qdbus != null) return true

        // Nessuno strumento disponibile: l'ambiente e' l'unica prova che resta.
        return isKde()
    }

    /** Esegue un comando breve; null se il binario non c'e' o esce male. */
    private fun run(cmd: List<String>): String? = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return@runCatching null
        }
        if (p.exitValue() != 0) return@runCatching null
        p.inputStream.bufferedReader().readText()
    }.getOrNull()

    // ── Marcatore dei crash ─────────────────────────────────────────────────

    private fun crashCount(): Int = runCatching {
        val f = markerFile()
        if (!f.isFile) 0 else f.readText().trim().toIntOrNull() ?: 1
    }.getOrDefault(0)

    fun crashedLastTime(): Boolean = crashCount() > 0

    fun clearCrashMarker() {
        runCatching { markerFile().delete() }
    }

    private fun markAttempt() {
        runCatching {
            val f = markerFile()
            f.parentFile?.mkdirs()
            f.writeText((crashCount() + 1).toString())
        }
    }

    // ── Decisione ───────────────────────────────────────────────────────────

    private fun skipReason(mode: String): String? = when {
        forcedOff -> "disabled for this run (--no-tray)"
        env("WFAS_NO_TRAY").isNotEmpty() && env("WFAS_NO_TRAY") != "0" -> "disabled by WFAS_NO_TRAY"
        mode.equals(MODE_OFF, true) -> "disabled in the settings (ui.linuxTray=OFF)"
        mode.equals(MODE_ON, true) -> null
        crashCount() >= MAX_CRASHES ->
            "the last $MAX_CRASHES launches crashed while creating it; run 'wfas config set ui.linuxTray ON' to try again"
        // Da qui in giu' siamo in AUTO e senza crash recenti.
        statusNotifierAvailable() -> null
        !sessionIsWayland() -> null                    // X11: GtkStatusIcon va bene
        gdkForcedToX11() -> null                       // XWayland con GDK forzato a x11
        else ->
            "this Wayland session has no StatusNotifierItem host (no $SNI_SERVICE on the session bus), " +
                    "so there is no tray to attach to"
    }

    /**
     * Prova a creare l'icona. Ritorna true se e' stata creata.
     *
     * [buildMenu] viene invocato solo a tray ottenuta, e ogni eccezione al suo
     * interno viene assorbita: una voce di menu che non si costruisce non deve
     * impedire l'avvio dell'applicazione.
     */
    fun install(
        mode: String,
        iconUrl: java.net.URL?,
        tooltip: String,
        buildMenu: (dorkbox.systemTray.SystemTray) -> Unit
    ): Boolean {
        installed = false
        val reason = skipReason(mode)
        if (reason != null) {
            lastSkipReason = reason
            AppDebug.log("[TRAY] skipped: $reason")
            return false
        }

        val sni = statusNotifierAvailable()
        tune(preferAppIndicator = sni)

        // Il marcatore vive solo per la durata della creazione: se il processo
        // muore dentro GTK resta sul disco e il lancio successivo lo trova.
        markAttempt()

        val tray = runCatching { dorkbox.systemTray.SystemTray.get() }
            .onFailure { AppDebug.log("[TRAY] SystemTray.get() failed: ${it.message}") }
            .getOrNull()

        if (tray == null) {
            clearCrashMarker()
            lastSkipReason = missingBackendHint(sni)
            AppDebug.log("[TRAY] ${lastSkipReason}")
            return false
        }

        val ok = runCatching {
            iconUrl?.let { tray.setImage(it) }
            tray.setTooltip(tooltip)
            buildMenu(tray)
            true
        }.onFailure { AppDebug.log("[TRAY] setup failed: ${it.message}") }.getOrDefault(false)

        if (ok) {
            instance = tray
            baseTooltip = tooltip
            baseIcon = iconUrl?.let { url -> runCatching { javax.imageio.ImageIO.read(url) }.getOrNull() }
        }

        clearCrashMarker()
        installed = ok
        if (ok) {
            lastSkipReason = null
            AppDebug.log("[TRAY] installed (backend hint: ${if (sni) "AppIndicator/SNI" else "GtkStatusIcon"})")
        }
        return ok
    }

    /**
     * Il caso piu' frequente su Fedora: il watcher SNI c'e', ma manca la libreria
     * che dorkbox carica per parlarci. Dirlo con il nome del pacchetto vale piu'
     * di dieci righe di stack trace.
     */
    private fun missingBackendHint(sni: Boolean): String = if (sni) {
        "the desktop hosts a tray but no supported backend could be loaded; " +
                "on Fedora install 'libappindicator-gtk3' (or 'libayatana-appindicator-gtk3'), " +
                "on Debian/Ubuntu 'libayatana-appindicator3-1'"
    } else {
        "no tray backend available on this desktop environment"
    }

    // ── Messa a punto di dorkbox ────────────────────────────────────────────

    /**
     * I parametri statici di dorkbox cambiano fra le minor: impostarli per
     * riflessione significa che una versione senza uno di questi campi non fa
     * fallire la compilazione ne' l'avvio.
     */
    private fun tune(preferAppIndicator: Boolean) {
        setStaticField("FORCE_GTK2", false)
        setStaticField("PREFER_GTK3", true)
        setStaticField("AUTO_FIX_INCONSISTENCIES", true)
        setStaticField("ENABLE_ROOT_CHECK", false)
        if (preferAppIndicator) forceTrayType("AppIndicator")
    }

    private fun setStaticField(name: String, value: Any) {
        runCatching {
            val f = dorkbox.systemTray.SystemTray::class.java.getField(name)
            f.set(null, value)
        }.onFailure { AppDebug.log("[TRAY] field $name not settable: ${it.message}") }
    }

    private fun forceTrayType(constant: String) {
        runCatching {
            val f = dorkbox.systemTray.SystemTray::class.java.getField("FORCE_TRAY_TYPE")
            val type = f.type
            if (type.isEnum) {
                val value = type.enumConstants?.firstOrNull { (it as Enum<*>).name.equals(constant, true) }
                if (value != null) {
                    f.set(null, value)
                    AppDebug.log("[TRAY] forcing backend $constant")
                }
            }
        }.onFailure { AppDebug.log("[TRAY] cannot force tray type: ${it.message}") }
    }

    /** Riepilogo leggibile, usato dalla diagnostica e dalle impostazioni. */
    fun describe(mode: String): String = buildString {
        append("mode=").append(mode)
        append(", desktop='").append(desktop()).append('\'')
        append(", session=").append(if (sessionIsWayland()) "wayland" else "x11")
        append(", sni=").append(statusNotifierAvailable())
        append(", crashes=").append(crashCount())
        lastSkipReason?.let { append(", skipped: ").append(it) }
    }
}