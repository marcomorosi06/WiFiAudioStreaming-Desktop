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

sealed class FirewallCommand {
    object Status : FirewallCommand()
    data class Allow(val ports: List<Int>) : FirewallCommand()
}

object FirewallCli {

    fun run(cmd: FirewallCommand, json: Boolean): Int {
        if (!FirewallHelper.isWindows) {
            val ports = when (cmd) {
                is FirewallCommand.Allow -> cmd.ports.filter { it in 1..65535 }.distinct().ifEmpty { defaultPorts() }
                else -> defaultPorts()
            }
            val suggestion = FirewallHelper.linuxAllowCommand(ports, defaultTcpPorts())
            if (suggestion != null) {
                if (json) {
                    val esc = suggestion.replace("\"", "'")
                    println("{\"status\": \"manual\", \"command\": \"$esc\"}")
                } else {
                    println("  A firewall is active on this system. Run:")
                    println()
                    println("    $suggestion")
                }
                return ExitCode.OK
            }
            if (json) println("{\"status\": \"unsupported\", \"message\": \"No managed firewall detected\"}")
            else System.err.println("wfas firewall: automatic setup is Windows-only; no active firewall detected here")
            return ExitCode.USAGE_ERROR
        }

        return when (cmd) {
            is FirewallCommand.Status -> {
                val active = FirewallHelper.rulesActive()
                if (json) println("{\"status\": \"ok\", \"active\": $active}")
                else println("  WFAS firewall rule: " + if (active) "active" else "not configured")
                ExitCode.OK
            }
            is FirewallCommand.Allow -> {
                val ports = cmd.ports.filter { it in 1..65535 }.distinct().ifEmpty { defaultPorts() }
                if (!json) println("  Allowing inbound UDP on ${ports.joinToString(", ")} (may prompt for administrator)...")
                when (val r = FirewallHelper.openInboundPorts(ports, defaultTcpPorts())) {
                    is FirewallHelper.Result.Success -> {
                        if (json) println("{\"status\": \"ok\", \"ports\": [${ports.joinToString(", ")}]}")
                        else println("  Firewall updated. Devices can now connect on these ports.")
                        ExitCode.OK
                    }
                    is FirewallHelper.Result.Denied -> {
                        if (json) println("{\"status\": \"denied\"}")
                        else System.err.println("wfas firewall: not applied - administrator approval was denied.")
                        ExitCode.USAGE_ERROR
                    }
                    is FirewallHelper.Result.NotSupported -> {
                        if (json) println("{\"status\": \"unsupported\"}")
                        else System.err.println("wfas firewall: only available on Windows")
                        ExitCode.USAGE_ERROR
                    }
                    is FirewallHelper.Result.Failure -> {
                        val msg = r.reason.replace("\"", "'")
                        if (json) println("{\"status\": \"error\", \"message\": \"$msg\"}")
                        else System.err.println("wfas firewall: $msg")
                        ExitCode.USAGE_ERROR
                    }
                }
            }
        }
    }

    private fun defaultTcpPorts(): List<Int> {
        val s = SettingsRepository.loadSettings()
        if (!s.httpEnabled) return emptyList()
        return listOfNotNull(s.httpPort.toIntOrNull()).filter { it in 1..65535 }
    }

    private fun defaultPorts(): List<Int> {
        val s = SettingsRepository.loadSettings()
        return listOf(
            s.streamingPort.toIntOrNull() ?: 9090,
            9091,
            s.micPort.toIntOrNull() ?: 9092
        ).filter { it in 1..65535 }.distinct()
    }
}
