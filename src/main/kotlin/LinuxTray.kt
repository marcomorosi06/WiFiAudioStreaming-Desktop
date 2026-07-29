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

object LinuxTray {

    const val MODE_AUTO = "AUTO"
    const val MODE_ON = "ON"
    const val MODE_OFF = "OFF"

    val MODES = listOf(MODE_AUTO, MODE_ON, MODE_OFF)

    private const val MARKER_NAME = "tray-attempt.lock"

    @Volatile
    var forcedOff = false
        private set

    fun disableForThisRun() { forcedOff = true }

    private fun markerFile(): File = File(ConfigPaths.configDir(), MARKER_NAME)

    private fun env(name: String): String =
        System.getenv(name)?.trim()?.lowercase().orEmpty()

    fun sessionIsWayland(): Boolean =
        env("XDG_SESSION_TYPE") == "wayland" || env("WAYLAND_DISPLAY").isNotEmpty()

    fun gdkForcedToX11(): Boolean = env("GDK_BACKEND") == "x11"

    fun crashedLastTime(): Boolean = runCatching { markerFile().exists() }.getOrDefault(false)

    fun clearCrashMarker() {
        runCatching { markerFile().delete() }
    }

    private fun skipReason(mode: String): String? = when {
        forcedOff -> "disabled for this run (--no-tray)"
        env("WFAS_NO_TRAY").isNotEmpty() && env("WFAS_NO_TRAY") != "0" -> "disabled by WFAS_NO_TRAY"
        mode.equals(MODE_OFF, true) -> "disabled in the settings (ui.linuxTray=OFF)"
        mode.equals(MODE_ON, true) -> null
        crashedLastTime() ->
            "the previous launch crashed while creating it; run 'wfas config set ui.linuxTray ON' to try again"
        sessionIsWayland() && !gdkForcedToX11() ->
            "this is a Wayland session and GDK_BACKEND is not x11, where the GTK tray is known to crash"
        else -> null
    }

    fun install(
        mode: String,
        iconUrl: java.net.URL?,
        tooltip: String,
        buildMenu: (dorkbox.systemTray.SystemTray) -> Unit
    ) {
        val reason = skipReason(mode)
        if (reason != null) {
            AppDebug.log("[TRAY] skipped: $reason")
            return
        }

        runCatching { markerFile().parentFile?.mkdirs(); markerFile().writeText("1") }

        val tray = runCatching { dorkbox.systemTray.SystemTray.get() }
            .onFailure { AppDebug.log("[TRAY] SystemTray.get() failed: ${it.message}") }
            .getOrNull()

        if (tray == null) {
            AppDebug.log("[TRAY] not supported on this desktop environment; continuing without it")
            clearCrashMarker()
            return
        }

        runCatching {
            iconUrl?.let { tray.setImage(it) }
            tray.setTooltip(tooltip)
            buildMenu(tray)
        }.onFailure { AppDebug.log("[TRAY] setup failed: ${it.message}") }

        clearCrashMarker()
    }
}
