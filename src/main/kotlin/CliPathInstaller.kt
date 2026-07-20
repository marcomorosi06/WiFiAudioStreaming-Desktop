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

    private val macSymlinkTarget = "/usr/local/bin/wfas"

    sealed class InstallResult {
        object Success          : InstallResult()
        object TerminalLaunched : InstallResult()
        data class Failure(val reason: String) : InstallResult()
    }

    // ── Windows helpers ───────────────────────────────────────────────────────

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

    private fun wfasBatDir(): File =
        File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "wfas")

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

    // ── Linux helpers ─────────────────────────────────────────────────────────

    private fun wfasScriptDir(): File =
        File(System.getProperty("user.home"), ".local/bin")

    private fun wfasScriptFile(): File = File(wfasScriptDir(), "wfas")

    private fun resolveLinuxInstallPaths(): Triple<String, String, String>? {
        val resourcesDirProp = System.getProperty("compose.application.resources.dir")
        val javaExeFromProcess = ProcessHandle.current().info().command().orElse(null)

        // Helper: given an install root directory, try to resolve java + classpath.
        // Uses the bundled JRE if present, otherwise falls back to system "java".
        // Returns a Triple only if lib/app exists (contains the application JARs).
        fun resolveFromRoot(root: File): Triple<String, String, String>? {
            val appDir = File(root, "lib/app")
            if (!appDir.exists()) return null
            val bundledJava = File(root, "lib/runtime/bin/java")
            val javaExe = if (bundledJava.exists()) bundledJava.absolutePath else "java"
            return Triple(javaExe, "${appDir.absolutePath}/*", "")
        }

        // 1. jpackage.app-path — set by the JPackage native launcher (RPM/DEB/distributable).
        // The launcher binary is at <root>/bin/<name>, so two parentFile calls reach <root>.
        val jpackageAppPath = System.getProperty("jpackage.app-path")
        if (!jpackageAppPath.isNullOrEmpty()) {
            val installRoot = File(jpackageAppPath).parentFile?.parentFile
            if (installRoot != null) {
                resolveFromRoot(installRoot)?.let { return it }
            }
        }

        // 2. compose.application.resources.dir points to <root>/lib/app/resources.
        // Walking up two levels gives <root>/lib, one more gives <root>.
        if (resourcesDirProp != null) {
            val installRoot = File(resourcesDirProp).parentFile?.parentFile?.parentFile
            if (installRoot != null) {
                resolveFromRoot(installRoot)?.let { return it }
            }
            // Legacy path: resources dir is directly inside lib/ (older Compose layout)
            val appDir  = File(resourcesDirProp).parentFile
            val libDir  = appDir?.parentFile
            val javaExe = libDir?.let { File(it, "runtime/bin/java") }
            if (javaExe != null && javaExe.exists()) {
                return Triple(javaExe.absolutePath, "${appDir!!.absolutePath}/*", resourcesDirProp)
            }
        }

        // 3. Known default install location (RPM/DEB installed via package manager)
        val standardInstall = File("/opt/wifi-audio-streaming")
        if (standardInstall.exists()) {
            resolveFromRoot(standardInstall)?.let { return it }
        }

        // 4. Development build fallback: current JVM process + full classpath.
        // Only applies when the current process IS a java binary (not a native launcher).
        val javaExe = javaExeFromProcess ?: return null
        if (!File(javaExe).name.lowercase().startsWith("java")) return null
        val cp = System.getProperty("java.class.path") ?: return null
        val resDir = resourcesDirProp
            ?: cp.split(":").map { File(it) }
                .firstOrNull { it.isDirectory && File(it, "version.properties").exists() }
                ?.absolutePath
            ?: ""
        return Triple(javaExe, cp, resDir)
    }

    /** Appends an export line to ~/.bashrc and ~/.profile if dir is not in PATH. */
    private fun ensureInLinuxPath(dir: String) {
        val currentPath = System.getenv("PATH") ?: ""
        if (currentPath.split(":").contains(dir)) return
        val home = System.getProperty("user.home")
        val exportLine = "\nexport PATH=\"$dir:\$PATH\""
        for (rc in listOf(".bashrc", ".profile")) {
            val f = File(home, rc)
            runCatching {
                if (f.exists() && !f.readText().contains(dir)) {
                    f.appendText(exportLine)
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun isInstalled(): Boolean = when {
        isWindows -> {
            val bat = File(wfasBatDir(), "wfas.bat")
            bat.exists() && isInWindowsUserPath(wfasBatDir().absolutePath)
        }
        isLinux -> wfasScriptFile().exists()
        else    -> {
            val link = File(macSymlinkTarget)
            if (!link.exists()) false
            else runCatching { link.canonicalPath }.getOrNull() ==
                    ProcessHandle.current().info().command().orElse(null)
        }
    }

    /**
     * Gli shim generati prima passavano "--help" quando invocati senza argomenti,
     * quindi `wfas` da solo scaricava tutto l'help. Reinstalla in silenzio lo shim
     * gia' presente se contiene ancora quella forma, cosi' chi ha una vecchia
     * installazione non deve rifare il setup a mano.
     */
    fun refreshIfOutdated() {
        runCatching {
            val shim = when {
                isWindows -> File(wfasBatDir(), "wfas.bat")
                isLinux   -> wfasScriptFile()
                else      -> return
            }
            if (!shim.exists()) return
            if (!shim.readText().contains("MainKt --help")) return
            install()
        }
    }

    fun install(): InstallResult = when {
        isWindows -> installWindows()
        isMac     -> installMac()
        isLinux   -> installLinux()
        else      -> InstallResult.Failure("Unsupported OS")
    }

    // ── Per-platform install ──────────────────────────────────────────────────

    private fun installWindows(): InstallResult {
        val exePath = resolveWindowsExePath()
            ?: return InstallResult.Failure("Cannot determine executable path.\nRun the installed app, not the development build.")

        val wfasDir = wfasBatDir()
        runCatching { wfasDir.mkdirs() }
            .onFailure { return InstallResult.Failure("Cannot create ${wfasDir.absolutePath}: ${it.message}") }

        val installDir   = File(exePath).parent ?: return InstallResult.Failure("Cannot determine install directory")
        val javaExe      = "$installDir\\runtime\\bin\\java.exe"
        val appDir       = "$installDir\\app"
        val cpWildcard   = "$appDir\\*"
        val resourcesDir = "$appDir\\resources"

        val bat = File(wfasDir, "wfas.bat")
        runCatching {
            bat.writeText(
                "@echo off\r\n" +
                "setlocal\r\n" +
                "set \"WFAS_JAVA=$javaExe\"\r\n" +
                "set \"WFAS_CP=$cpWildcard\"\r\n" +
                "set \"WFAS_RESDIR=$resourcesDir\"\r\n" +
                "set \"WFAS_APPDIR=$appDir\"\r\n" +
                "if \"%~1\"==\"\" (\r\n" +
                "    \"%WFAS_JAVA%\" -Djava.net.preferIPv4Stack=true \"-Dskiko.library.path=%WFAS_APPDIR%\" -Dcompose.application.configure.swing.globals=true \"-Dcompose.application.resources.dir=%WFAS_RESDIR%\" -cp \"%WFAS_CP%\" MainKt --cli-no-args\r\n" +
                ") else (\r\n" +
                "    \"%WFAS_JAVA%\" -Djava.net.preferIPv4Stack=true \"-Dskiko.library.path=%WFAS_APPDIR%\" -Dcompose.application.configure.swing.globals=true \"-Dcompose.application.resources.dir=%WFAS_RESDIR%\" -cp \"%WFAS_CP%\" MainKt %*\r\n" +
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
        val (javaExe, cpWildcard, resourcesDir) = resolveLinuxInstallPaths()
            ?: return InstallResult.Failure(
                "Cannot determine install directory.\n\n" +
                "Diagnostic info:\n" +
                "  jpackage.app-path = ${System.getProperty("jpackage.app-path") ?: "(null)"}\n" +
                "  process command   = ${ProcessHandle.current().info().command().orElse("(empty)")}\n" +
                "  java.class.path   = ${System.getProperty("java.class.path")?.take(120) ?: "(null)"}\n" +
                "  compose.res.dir   = ${System.getProperty("compose.application.resources.dir") ?: "(null)"}"
            )

        val wfasDir = wfasScriptDir()
        runCatching { wfasDir.mkdirs() }
            .onFailure { return InstallResult.Failure("Cannot create ${wfasDir.absolutePath}: ${it.message}") }

        val appDirPath = cpWildcard.removeSuffix("/*")
        val script = wfasScriptFile()
        runCatching {
            script.writeText(
                "#!/bin/sh\n" +
                "WFAS_JAVA=\"$javaExe\"\n" +
                "WFAS_CP=\"$cpWildcard\"\n" +
                "WFAS_RESDIR=\"$resourcesDir\"\n" +
                "WFAS_APPDIR=\"$appDirPath\"\n" +
                "if [ \$# -eq 0 ]; then\n" +
                "    exec \"\$WFAS_JAVA\" -Djava.net.preferIPv4Stack=true \"-Dskiko.library.path=\$WFAS_APPDIR\" -Dcompose.application.configure.swing.globals=true \"-Dcompose.application.resources.dir=\$WFAS_RESDIR\" -cp \"\$WFAS_CP\" MainKt --cli-no-args\n" +
                "else\n" +
                "    exec \"\$WFAS_JAVA\" -Djava.net.preferIPv4Stack=true \"-Dskiko.library.path=\$WFAS_APPDIR\" -Dcompose.application.configure.swing.globals=true \"-Dcompose.application.resources.dir=\$WFAS_RESDIR\" -cp \"\$WFAS_CP\" MainKt \"\$@\"\n" +
                "fi\n"
            )
            script.setExecutable(true)
        }.onFailure { return InstallResult.Failure("Cannot write wfas script: ${it.message}") }

        ensureInLinuxPath(wfasDir.absolutePath)
        installLinuxManPage()

        return InstallResult.Success
    }

    private fun installLinuxManPage() {
        val stream = CliPathInstaller::class.java.getResourceAsStream("/man/wfas.1") ?: return
        val manDir = File(System.getProperty("user.home"), ".local/share/man/man1")
        runCatching { manDir.mkdirs() }
        runCatching { stream.use { File(manDir, "wfas.1").writeBytes(it.readBytes()) } }
            .onFailure { return }
        // Try to update the user man-db cache; failures are silent
        runCatching { ProcessBuilder("mandb", "-q", "-u").redirectErrorStream(true).start().waitFor() }
        // Ensure ~/.local/share/man is in MANPATH for shells that don't include it by default
        val manBase = manDir.parent
        val exportLine = "\nexport MANPATH=\"$manBase:\$MANPATH\""
        val home = System.getProperty("user.home")
        for (rc in listOf(".bashrc", ".profile")) {
            val f = File(home, rc)
            runCatching {
                if (f.exists() && !f.readText().contains(manBase)) f.appendText(exportLine)
            }
        }
    }

    private fun installMac(): InstallResult {
        val exePath = ProcessHandle.current().info().command().orElse(null)
            ?: return InstallResult.Failure("Cannot determine executable path")

        val cmd = "sudo ln -sf \"$exePath\" $macSymlinkTarget"

        runCatching {
            val script = "tell application \"Terminal\" to do script \"$cmd\""
            ProcessBuilder("osascript", "-e", script).start()
        }.onSuccess { return InstallResult.TerminalLaunched }
         .onFailure { return InstallResult.Failure("Cannot open Terminal: ${it.message}") }

        return InstallResult.TerminalLaunched
    }
}
