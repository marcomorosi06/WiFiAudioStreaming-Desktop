/*
 * Copyright (c) 2026 Marco Morosi and contributors
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

import java.nio.file.Files

/**
 * Checks both quoting layers and the link flags without touching
 * /usr/local/bin or asking for administrator rights.
 */
fun main() {
    val paths = listOf(
        "/Applications/WiFi Audio Streaming.app/Contents/MacOS/WiFi Audio Streaming",
        "/Users/test/O'Brien/quoted \"app\"/WFAS",
        "/tmp/\$HOME `touch should-not-run` \\ path\nWFAS"
    )
    val os = System.getProperty("os.name").lowercase()
    val shell = System.getenv("WFAS_TEST_SHELL") ?: if (os.contains("win")) null else "/bin/sh"
    for (path in paths) {
        val script = CliPathInstaller.macInstallScript(path)
        // Independently decode the AppleScript string literal, rejecting unescaped quotes.
        check(script.startsWith("do shell script \""))
        check(script.endsWith("\" with administrator privileges"))
        check(!script.contains("tell application"))
        val literal = script.substringAfter("do shell script \"")
            .substringBeforeLast("\" with administrator privileges")
        val decoded = StringBuilder()
        var i = 0
        while (i < literal.length) {
            val ch = literal[i++]
            check(ch != '"') { "Unescaped AppleScript quote" }
            if (ch == '\\') {
                check(i < literal.length)
                decoded.append(literal[i++])
            } else decoded.append(ch)
        }
        check(decoded.startsWith("/bin/mkdir -p /usr/local/bin && /bin/ln -sfn -- "))
        if (shell != null) {
            // Replace the installation operation with printf: verify the shell sees
            // exactly one executable argument, with no expansion or execution.
            val args = decoded.toString().substringAfter("/bin/ln -sfn -- ")
            val proc = ProcessBuilder(shell).start()
            proc.outputStream.bufferedWriter().use { it.write("printf '%s\\000' $args\n") }
            val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            val error = proc.errorStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { error }
            check(output == "$path\u0000/usr/local/bin/wfas\u0000") { "Shell changed the executable path" }
        }
        if (os.contains("mac")) {
            val dir = Files.createTempDirectory("wfas-applescript-check").toFile()
            try {
                val source = dir.resolve("install.applescript").apply { writeText(script) }
                val proc = ProcessBuilder("/usr/bin/osacompile", "-o",
                    dir.resolve("install.scpt").path, source.path).redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                check(proc.waitFor() == 0) { output }
            } finally {
                dir.deleteRecursively()
            }
        }
        if (shell != null) checkLinkDoesNotFollowExistingLink(shell, path)
    }
    println("macOS CLI installer quoting checks passed")
}

/**
 * Runs the real link command against a scratch destination that is already a
 * symlink to a directory. The link must be replaced in place; nothing may be
 * created inside the directory it pointed to.
 */
private fun checkLinkDoesNotFollowExistingLink(shell: String, exePath: String) {
    val dir = Files.createTempDirectory("wfas-ln-check").toFile()
    try {
        val decoy = dir.resolve("decoy").apply { mkdirs() }
        val dest = dir.resolve("wfas").toPath()
        Files.createSymbolicLink(dest, decoy.toPath())
        val proc = ProcessBuilder(shell).redirectErrorStream(true).start()
        proc.outputStream.bufferedWriter().use {
            it.write(CliPathInstaller.macLinkCommand(exePath, dest.toString()) + "\n")
        }
        val output = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { output }
        check(decoy.list().isNullOrEmpty()) { "ln followed the existing link into its directory" }
        check(Files.isSymbolicLink(dest)) { "Destination is no longer a symlink" }
        check(Files.readSymbolicLink(dest).toString() == exePath) { "Symlink does not point to the executable" }
    } finally {
        // Remove the link itself first: deleteRecursively() would otherwise
        // walk into whatever it points to.
        Files.deleteIfExists(dir.resolve("wfas").toPath())
        dir.deleteRecursively()
    }
}
