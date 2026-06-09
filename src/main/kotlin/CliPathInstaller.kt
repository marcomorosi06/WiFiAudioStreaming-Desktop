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

object CliPathInstaller {

    private val osName = System.getProperty("os.name", "").lowercase()
    val isWindows = osName.contains("win")
    val isMac     = osName.contains("mac") || osName.contains("darwin")
    val isLinux   = !isWindows && !isMac

    private val symlinkTarget = "/usr/local/bin/wfas"

    sealed class InstallResult {
        object Success          : InstallResult()
        object TerminalLaunched : InstallResult()
        data class Failure(val reason: String) : InstallResult()
    }

    private fun resolveWindowsExePath(): String? {
        val fromProcess = ProcessHandle.current().info().command().orElse(null)
        if (fromProcess != null
            && fromProcess.endsWith(".exe", ignoreCase = true)
            && !fromProcess.contains("java", ignoreCase = true)
            && !fromProcess.contains("jdk", ignoreCase = true)
            && !fromProcess.contains("jre", ignoreCase = true)) {
            return fromProcess
        }
        val resourcesDir = System.getProperty("compose.application.resources.dir")
        if (resourcesDir != null) {
            val installDir = File(resourcesDir).parentFile?.parentFile
            val exe = installDir?.listFiles()
                ?.firstOrNull { it.extension.equals("exe", ignoreCase = true) }
            if (exe != null) return exe.absolutePath
        }
        return findWindowsExeViaRegistry()
    }

    private fun findWindowsExeViaRegistry(): String? {
        val ps = """
            ${'$'}keywords = @('WiFiAudioStreaming', 'WiFi Audio Streaming', 'wfas')
            ${'$'}roots = @(
                'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
                'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
                'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall'
            )
            foreach (${'$'}root in ${'$'}roots) {
                Get-ChildItem ${'$'}root -ErrorAction SilentlyContinue | ForEach-Object {
                    ${'$'}props = ${'$'}_ | Get-ItemProperty -ErrorAction SilentlyContinue
                    ${'$'}name  = ${'$'}props.DisplayName
                    ${'$'}loc   = ${'$'}props.InstallLocation
                    if (${'$'}name -and ${'$'}loc) {
                        foreach (${'$'}kw in ${'$'}keywords) {
                            if (${'$'}name -like "*${'$'}kw*" -or ${'$'}loc -like "*${'$'}kw*") {
                                ${'$'}exe = Get-ChildItem ${'$'}loc -Filter '*.exe' -ErrorAction SilentlyContinue |
                                    Where-Object { ${'$'}_.Name -notmatch '^(unins|uninst)' } |
                                    Select-Object -First 1
                                if (${'$'}exe) { Write-Output ${'$'}exe.FullName; exit }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
        return runCatching {
            val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out.takeIf { it.isNotEmpty() && it.endsWith(".exe", ignoreCase = true) }
        }.getOrNull()
    }

    fun executablePath(): String? =
        if (isWindows) resolveWindowsExePath()
        else ProcessHandle.current().info().command().orElse(null)

    private fun wfasBatDir(): File =
        File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "wfas")

    fun isInstalled(): Boolean {
        if (isWindows) {
            val bat = File(wfasBatDir(), "wfas.bat")
            return bat.exists() && isInWindowsUserPath(wfasBatDir().absolutePath)
        } else {
            val link   = File(symlinkTarget)
            if (!link.exists()) return false
            val target = runCatching { link.canonicalPath }.getOrNull() ?: return false
            return target == executablePath()
        }
    }

    fun install(): InstallResult = when {
        isWindows -> installWindows()
        isMac     -> installUnix()
        isLinux   -> installLinux()
        else      -> InstallResult.Failure("Unsupported OS")
    }

    private fun installWindows(): InstallResult {
        val exePath = resolveWindowsExePath()
            ?: return InstallResult.Failure("Cannot determine executable path.\nRun the installed app, not the development build.")

        val wfasDir = wfasBatDir()
        runCatching { wfasDir.mkdirs() }
            .onFailure { return InstallResult.Failure("Cannot create ${wfasDir.absolutePath}: ${it.message}") }

        val installDir   = File(exePath).parent ?: return InstallResult.Failure("Cannot determine install directory")
        val javaExe      = "$installDir\\runtime\\bin\\java.exe"
        val cpWildcard   = "$installDir\\app\\*"
        val resourcesDir = "$installDir\\app\\resources"

        val bat = File(wfasDir, "wfas.bat")
        runCatching {
            bat.writeText(
                "@echo off\r\n" +
                "setlocal\r\n" +
                "set \"WFAS_JAVA=$javaExe\"\r\n" +
                "set \"WFAS_CP=$cpWildcard\"\r\n" +
                "set \"WFAS_RESDIR=$resourcesDir\"\r\n" +
                "if \"%~1\"==\"\" (\r\n" +
                "    \"%WFAS_JAVA%\" -Djava.net.preferIPv4Stack=true \"-Dcompose.application.resources.dir=%WFAS_RESDIR%\" -cp \"%WFAS_CP%\" MainKt --help\r\n" +
                ") else (\r\n" +
                "    \"%WFAS_JAVA%\" -Djava.net.preferIPv4Stack=true \"-Dcompose.application.resources.dir=%WFAS_RESDIR%\" -cp \"%WFAS_CP%\" MainKt %*\r\n" +
                ")\r\n"
            )
        }.onFailure { return InstallResult.Failure("Cannot write wfas.bat: ${it.message}") }

        val dir = wfasDir.absolutePath
        val psAddPath = """
            ${'$'}dir = '$dir'
            ${'$'}scope = 'User'
            ${'$'}cur = [Environment]::GetEnvironmentVariable('PATH', ${'$'}scope)
            ${'$'}entries = (${'$'}cur -split ';') | Where-Object { ${'$'}_ -ne ${'$'}dir -and ${'$'}_ -ne '' }
            ${'$'}entries += ${'$'}dir
            [Environment]::SetEnvironmentVariable('PATH', (${'$'}entries -join ';'), ${'$'}scope)
        """.trimIndent()

        val result = runCatching {
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psAddPath)
                .inheritIO()
                .start()
                .waitFor()
        }.getOrElse { return InstallResult.Failure("PowerShell error: ${it.message}") }

        return if (result == 0) InstallResult.Success
               else InstallResult.Failure("PowerShell exited with code $result")
    }

    private fun installLinux(): InstallResult {
        val exePath = executablePath()
            ?: return InstallResult.Failure("Cannot determine executable path")

        val cmd = "ln -sf \"$exePath\" $symlinkTarget"

        if (tryPkexec(cmd)) return InstallResult.TerminalLaunched

        val terminals = listOf(
            listOf("x-terminal-emulator", "-e"),
            listOf("gnome-terminal", "--"),
            listOf("konsole", "-e"),
            listOf("xfce4-terminal", "-e"),
            listOf("xterm", "-e"),
        )
        for ((term, flag) in terminals.map { it[0] to it[1] }) {
            if (tryTerminal(term, flag, "sudo $cmd ; read -p 'Press Enter to close...'"))
                return InstallResult.TerminalLaunched
        }
        return InstallResult.Failure("No terminal emulator found. Run manually:\n  sudo $cmd")
    }

    private fun installUnix(): InstallResult {
        val exePath = executablePath()
            ?: return InstallResult.Failure("Cannot determine executable path")

        val cmd = "sudo ln -sf \"$exePath\" $symlinkTarget"

        runCatching {
            val script = "tell application \"Terminal\" to do script \"$cmd\""
            ProcessBuilder("osascript", "-e", script).start()
        }.onSuccess { return InstallResult.TerminalLaunched }
         .onFailure { return InstallResult.Failure("Cannot open Terminal: ${it.message}") }

        return InstallResult.TerminalLaunched
    }

    private fun tryPkexec(cmd: String): Boolean {
        val which = runCatching {
            ProcessBuilder("which", "pkexec").start().inputStream.bufferedReader().readText().trim()
        }.getOrNull()
        if (which.isNullOrEmpty()) return false
        return runCatching {
            ProcessBuilder("pkexec", "sh", "-c", cmd).start()
            true
        }.getOrDefault(false)
    }

    private fun tryTerminal(termBin: String, flag: String, cmd: String): Boolean {
        val which = runCatching {
            ProcessBuilder("which", termBin).start().inputStream.bufferedReader().readText().trim()
        }.getOrNull()
        if (which.isNullOrEmpty()) return false
        return runCatching {
            ProcessBuilder(termBin, flag, "sh", "-c", cmd).start()
            true
        }.getOrDefault(false)
    }

    private fun isInWindowsUserPath(dir: String): Boolean {
        val ps = """
            ${'$'}cur = [Environment]::GetEnvironmentVariable('PATH', 'User')
            if ((${'$'}cur -split ';') -contains '$dir') { exit 0 } else { exit 1 }
        """.trimIndent()
        return runCatching {
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
                .start().waitFor() == 0
        }.getOrDefault(false)
    }
}
