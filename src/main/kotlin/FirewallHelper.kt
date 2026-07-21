/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
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

object FirewallHelper {

    private const val RULE_PORT = "WFAS-in"
    private const val RULE_PORT_TCP = "WFAS-in-tcp"
    private const val RULE_PROG = "WFAS-app"

    val isWindows: Boolean
        get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    val isLinux: Boolean
        get() = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)

    enum class LinuxFirewall { FIREWALLD, UFW, NONE }

    private fun serviceActive(name: String): Boolean = runCatching {
        val proc = ProcessBuilder("systemctl", "is-active", name)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out == "active"
    }.getOrDefault(false)

    fun detectLinuxFirewall(): LinuxFirewall = when {
        !isLinux -> LinuxFirewall.NONE
        serviceActive("firewalld") -> LinuxFirewall.FIREWALLD
        serviceActive("ufw") -> LinuxFirewall.UFW
        else -> LinuxFirewall.NONE
    }

    fun linuxAllowCommand(ports: List<Int>, tcpPorts: List<Int> = emptyList()): String? {
        val udp = ports.filter { it in 1..65535 }.distinct()
        val tcp = tcpPorts.filter { it in 1..65535 }.distinct()
        if (udp.isEmpty() && tcp.isEmpty()) return null
        val specs = udp.map { "$it/udp" } + tcp.map { "$it/tcp" }
        return when (detectLinuxFirewall()) {
            LinuxFirewall.FIREWALLD ->
                specs.joinToString(" ") { "sudo firewall-cmd --add-port=$it --permanent &&" } +
                    " sudo firewall-cmd --reload"
            LinuxFirewall.UFW ->
                specs.joinToString(" && ") { "sudo ufw allow $it" }
            LinuxFirewall.NONE -> null
        }
    }

    sealed class Result {
        object Success      : Result()
        object Denied       : Result()
        object NotSupported : Result()
        data class Failure(val reason: String) : Result()
    }

    fun rulesActive(): Boolean = isWindows && ruleExists(RULE_PORT)

    fun openInboundPorts(ports: List<Int>, tcpPorts: List<Int> = emptyList()): Result {
        if (!isWindows) return Result.NotSupported
        val clean = ports.filter { it in 1..65535 }.distinct()
        val cleanTcp = tcpPorts.filter { it in 1..65535 }.distinct()
        if (clean.isEmpty() && cleanTcp.isEmpty()) return Result.Failure("no valid ports")
        return runCatching {
            applyElevated(clean, cleanTcp)
            if (ruleExists(RULE_PORT) || ruleExists(RULE_PORT_TCP)) Result.Success else Result.Denied
        }.getOrElse { Result.Failure(it.message ?: "unknown error") }
    }

    private fun ruleExists(name: String): Boolean = runCatching {
        val proc = ProcessBuilder("netsh", "advfirewall", "firewall", "show", "rule", "name=$name", "dir=in")
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        !out.contains("No rules match", ignoreCase = true) && out.contains(name)
    }.getOrDefault(false)

    private fun applyElevated(ports: List<Int>, tcpPorts: List<Int>) {
        val exe = ProcessHandle.current().info().command().orElse(null)
        val exeEsc = exe?.replace("'", "''")
        val portList = ports.joinToString(",")
        val script = buildString {
            appendLine("netsh advfirewall firewall delete rule name=$RULE_PORT dir=in 2>\$null | Out-Null")
            appendLine("netsh advfirewall firewall delete rule name=$RULE_PORT_TCP dir=in 2>\$null | Out-Null")
            appendLine("netsh advfirewall firewall delete rule name=$RULE_PROG dir=in 2>\$null | Out-Null")
            if (exeEsc != null) {
                appendLine("Get-NetFirewallRule -Direction Inbound -Action Block -ErrorAction SilentlyContinue | Where-Object { ((\$_ | Get-NetFirewallApplicationFilter -ErrorAction SilentlyContinue).Program) -ieq '$exeEsc' } | Remove-NetFirewallRule -ErrorAction SilentlyContinue")
            }
            if (ports.isNotEmpty()) {
                appendLine("netsh advfirewall firewall add rule name=$RULE_PORT dir=in action=allow protocol=UDP localport=$portList profile=any enable=yes | Out-Null")
            }
            if (tcpPorts.isNotEmpty()) {
                appendLine("netsh advfirewall firewall add rule name=$RULE_PORT_TCP dir=in action=allow protocol=TCP localport=${tcpPorts.joinToString(",")} profile=any enable=yes | Out-Null")
            }
        }
        val tmp = File.createTempFile("wfas-fw", ".ps1")
        try {
            tmp.writeText(script)
            val tmpEsc = tmp.absolutePath.replace("'", "''")
            val launch = "Start-Process -FilePath powershell -Verb RunAs -WindowStyle Hidden -Wait " +
                "-ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File','$tmpEsc')"
            ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", launch)
                .redirectErrorStream(true).start().waitFor()
        } finally {
            runCatching { tmp.delete() }
        }
    }
}
