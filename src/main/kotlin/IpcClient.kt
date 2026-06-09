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
import java.net.ConnectException
import java.net.Socket

// ─────────────────────────────────────────────────────────────────────────────
// IpcClient — sends a single control command to a running wfas instance
//
// Discovery: scans $TMPDIR for wfas-<pid>.port files, picks the most recent.
// If --pid <n> is ever added to CliArgs, we can target a specific instance.
// ─────────────────────────────────────────────────────────────────────────────

object IpcClient {

    fun send(cmd: ControlCommand, args: CliArgs) {
        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        val portFiles = tmpDir.listFiles { f ->
            f.name.startsWith("wfas-") && f.name.endsWith(".port")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (portFiles.isEmpty()) {
            printError("No running wfas instance found.", args)
            kotlin.system.exitProcess(1)
        }

        val portFile = portFiles.first()
        val port = portFile.readText().trim().toIntOrNull()
        if (port == null) {
            printError("Corrupt IPC port file: ${portFile.name}", args)
            kotlin.system.exitProcess(1)
        }

        val pid = portFile.name.removePrefix("wfas-").removeSuffix(".port")

        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3000
                val writer = socket.getOutputStream().bufferedWriter()
                val reader = socket.getInputStream().bufferedReader()

                writer.write(buildRequest(cmd))
                writer.newLine()
                writer.flush()

                val response = reader.readLine() ?: ""

                if (args.json) {
                    println(response)
                } else {
                    prettyPrint(cmd, response, pid, args)
                }
            }
        } catch (e: ConnectException) {
            printError("Could not connect to wfas instance (PID $pid). Is it still running?", args)
            portFile.runCatching { delete() }
            kotlin.system.exitProcess(1)
        } catch (e: Exception) {
            printError("IPC error: ${e.message}", args)
            kotlin.system.exitProcess(1)
        }
    }

    private fun buildRequest(cmd: ControlCommand): String = when (cmd) {
        is ControlCommand.Volume -> "{\"cmd\": \"volume\", \"value\": ${cmd.value}}"
        is ControlCommand.Mute   -> "{\"cmd\": \"mute\"}"
        is ControlCommand.Unmute -> "{\"cmd\": \"unmute\"}"
        is ControlCommand.Stop   -> "{\"cmd\": \"stop\"}"
        is ControlCommand.Status -> "{\"cmd\": \"status\"}"
    }

    private fun prettyPrint(cmd: ControlCommand, response: String, pid: String, args: CliArgs) {
        val ok = response.contains("\"status\": \"ok\"")

        when (cmd) {
            is ControlCommand.Volume -> {
                val pct = (cmd.value * 100).toInt()
                if (ok) println("  ✓  Volume set to $pct%  ${dim("(PID $pid)")}")
                else    System.err.println("  ✗  Failed to set volume.")
            }
            is ControlCommand.Mute -> {
                if (ok) println("  ✓  Muted  ${dim("(PID $pid)")}")
                else    System.err.println("  ✗  Failed to mute.")
            }
            is ControlCommand.Unmute -> {
                if (ok) println("  ✓  Unmuted  ${dim("(PID $pid)")}")
                else    System.err.println("  ✗  Failed to unmute.")
            }
            is ControlCommand.Stop -> {
                if (ok) println("  ✓  Streaming stopped  ${dim("(PID $pid)")}")
                else    System.err.println("  ✗  Failed to stop.")
            }
            is ControlCommand.Status -> printStatus(response, pid)
        }
    }

    private fun printStatus(json: String, pid: String) {
        fun str(key: String)  = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1)
        fun num(key: String)  = Regex("\"$key\"\\s*:\\s*([\\d.]+)").find(json)?.groupValues?.getOrNull(1)
        fun bool(key: String) = Regex("\"$key\"\\s*:\\s*(true|false)").find(json)?.groupValues?.getOrNull(1)

        val mode    = str("mode")    ?: "unknown"
        val volume  = num("volume")?.toFloat()?.let { "${(it * 100).toInt()}%" } ?: "?"
        val muted   = bool("muted")  == "true"
        val port    = num("port")    ?: "?"
        val rtp     = bool("rtp")    == "true"
        val http    = bool("http")   == "true"
        val uptime  = num("uptime")?.toLong()?.let { formatUptime(it) } ?: "?"

        println()
        println("  ${bold("wfas")}  PID $pid")
        println("  ${dim("Mode")}    $mode")
        println("  ${dim("Port")}    $port")
        println("  ${dim("Volume")}  $volume${if (muted) "  ${yellow("(muted)")}" else ""}")
        println("  ${dim("RTP")}     ${if (rtp) green("yes") else dim("no")}")
        println("  ${dim("HTTP")}    ${if (http) green("yes") else dim("no")}")
        println("  ${dim("Uptime")}  $uptime")
        println()
    }

    private fun formatUptime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0  -> "${h}h ${m}m ${s}s"
            m > 0  -> "${m}m ${s}s"
            else   -> "${s}s"
        }
    }

    private fun printError(msg: String, args: CliArgs) {
        if (args.json) println("{\"status\": \"error\", \"message\": \"$msg\"}")
        else System.err.println("  ✗  $msg")
    }

    private fun dim(t: String): String {
        val ansi = System.getenv("NO_COLOR") == null && System.getenv("TERM") != "dumb"
        return if (ansi) "[2m$t[0m" else t
    }

    private fun bold(t: String): String {
        val ansi = System.getenv("NO_COLOR") == null && System.getenv("TERM") != "dumb"
        return if (ansi) "[1m$t[0m" else t
    }

    private fun green(t: String): String {
        val ansi = System.getenv("NO_COLOR") == null && System.getenv("TERM") != "dumb"
        return if (ansi) "[32m$t[0m" else t
    }

    private fun yellow(t: String): String {
        val ansi = System.getenv("NO_COLOR") == null && System.getenv("TERM") != "dumb"
        return if (ansi) "[33m$t[0m" else t
    }
}
