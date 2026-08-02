/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 */

import java.io.File

object PendingDeepLink {
    @Volatile
    var uri: String? = null

    fun take(): String? {
        val v = uri
        uri = null
        return v
    }

    fun looksLikePairing(arg: String): Boolean =
        arg.startsWith("${WfasPairingUri.SCHEME}://", ignoreCase = true) ||
            arg.startsWith("https://${WfasPairingUri.APPLINK_HOST}${WfasPairingUri.APPLINK_PATH}", ignoreCase = true) ||
            arg.startsWith("https://${WfasPairingUri.APPLINK_HOST}${WfasPairingUri.APPLINK_PATH_IT}", ignoreCase = true)
}

object ProtocolRegistrar {

    private val os = System.getProperty("os.name").lowercase()
    private val isWindows = os.contains("win")
    private val isMac = os.contains("mac") || os.contains("darwin")
    private val isLinux = !isWindows && !isMac

    const val SCHEME = WfasPairingUri.SCHEME

    private const val WIN_KEY = "HKCU\\Software\\Classes\\$SCHEME"
    private const val WIN_CMD_KEY = "$WIN_KEY\\shell\\open\\command"

    fun launcherPath(): String? = when {
        isWindows -> CliPathInstaller.resolveWindowsExePath()
        else -> ProcessHandle.current().info().command().orElse(null)
            ?.let { c -> File(c).takeIf { it.exists() }?.absolutePath }
    }

    private fun desktopFile(): File =
        File(
            System.getProperty("user.home"),
            ".local/share/applications/wifi-audio-streaming-url.desktop"
        )

    private fun queryDefault(key: String): String? = runCatching {
        val proc = ProcessBuilder("reg", "query", key, "/ve")
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) return@runCatching null
        Regex("REG_SZ\\s+(.+)").find(out)?.groupValues?.getOrNull(1)?.trim()
    }.getOrNull()

    fun registeredCommand(): String? = when {
        isWindows -> queryDefault(WIN_CMD_KEY)
        isLinux -> desktopFile().takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.startsWith("Exec=") }?.removePrefix("Exec=")
        else -> null
    }

    private val urlPlaceholder = if (isWindows) "%1" else "%u"

    fun isRegistered(): Boolean = when {
        isWindows -> {
            val cmd = registeredCommand()
            cmd != null && cmd.contains("%1") && cmd.contains(".exe", ignoreCase = true)
        }
        isLinux -> desktopFile().exists()
        else -> true
    }

    /**
     * Registra lo schema se manca, e lo riscrive se punta a un eseguibile diverso da
     * questo: dopo un aggiornamento o uno spostamento il vecchio comando resta valido
     * a vedersi ma non apre piu' niente. Va chiamata a ogni avvio, non e' compito
     * dell'utente accorgersene.
     */
    fun ensureRegistered(): Boolean {
        if (isMac) return true
        val exe = launcherPath() ?: return false
        val current = registeredCommand()
        val healthy = current != null &&
            current.contains(urlPlaceholder) &&
            current.contains(exe, ignoreCase = true)
        if (healthy) return true
        return register().isSuccess
    }

    fun register(): Result<Unit> = runCatching {
        val exe = launcherPath()
            ?: error("cannot locate the WiFi Audio Streaming executable")
        when {
            isWindows -> registerWindows(exe)
            isLinux -> registerLinux(exe)
            else -> Unit
        }
    }

    private fun run(vararg cmd: String) {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() != 0) error("${cmd.joinToString(" ")} -> $out")
    }

    private fun regEscape(value: String) =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun registerWindows(exe: String) {
        val body = buildString {
            append("Windows Registry Editor Version 5.00\r\n\r\n")
            append("[HKEY_CURRENT_USER\\Software\\Classes\\$SCHEME]\r\n")
            append("@=\"URL:WFAS Pairing\"\r\n")
            append("\"URL Protocol\"=\"\"\r\n\r\n")
            append("[HKEY_CURRENT_USER\\Software\\Classes\\$SCHEME\\DefaultIcon]\r\n")
            append("@=\"${regEscape("$exe,0")}\"\r\n\r\n")
            append("[HKEY_CURRENT_USER\\Software\\Classes\\$SCHEME\\shell\\open\\command]\r\n")
            append("@=\"${regEscape("\"$exe\" \"%1\"")}\"\r\n")
        }
        val file = File.createTempFile("wfas-scheme", ".reg")
        try {
            file.outputStream().use { out ->
                out.write(0xFF)
                out.write(0xFE)
                out.write(body.toByteArray(Charsets.UTF_16LE))
            }
            run("reg", "import", file.absolutePath)
        } finally {
            file.delete()
        }
    }

    private fun registerLinux(exe: String) {
        val file = desktopFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=WiFi Audio Streaming
            Exec="$exe" %u
            Terminal=false
            NoDisplay=true
            MimeType=x-scheme-handler/$SCHEME;
            """.trimIndent() + "\n"
        )
        runCatching { run("chmod", "+x", file.absolutePath) }
        runCatching { run("xdg-mime", "default", file.name, "x-scheme-handler/$SCHEME") }
        runCatching { run("update-desktop-database", file.parentFile.absolutePath) }
    }

    fun unregister(): Result<Unit> = runCatching {
        when {
            isWindows -> run("reg", "delete", WIN_KEY, "/f")
            isLinux -> { desktopFile().delete() }
            else -> Unit
        }
    }
}
