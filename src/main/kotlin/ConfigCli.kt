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

sealed class ConfigCommand {
    object List   : ConfigCommand()
    object Path   : ConfigCommand()
    object Reset  : ConfigCommand()
    object Edit   : ConfigCommand()
    data class Get(val key: String) : ConfigCommand()
    data class Set(val key: String, val value: String) : ConfigCommand()
    data class Export(val path: String?) : ConfigCommand()
    data class Import(val path: String)  : ConfigCommand()
}

object ConfigCli {

    private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun jsonValue(field: CfgField, settings: AllSettings, reveal: Boolean = false): String {
        val v = field.getter(settings)
        if (field.secret && !reveal && !(v == null || v.toString().isEmpty())) {
            return "\"${ConfigManager.MASK}\""
        }
        return when (v) {
            null       -> "null"
            is Boolean -> v.toString()
            is Number  -> v.toString()
            is kotlin.collections.List<*> -> "[" + v.joinToString(", ") { "\"${jsonEscape(it.toString())}\"" } + "]"
            else       -> "\"${jsonEscape(v.toString())}\""
        }
    }

    private fun outputIsTerminal(): Boolean {
        val console = System.console() ?: return false
        return runCatching {
            val m = console.javaClass.getMethod("isTerminal")
            m.invoke(console) as Boolean
        }.getOrDefault(true)
    }

    private fun requireRevealAllowed(reveal: Boolean, json: Boolean) {
        if (!reveal) return
        if (json) throw ConfigException(
            "--reveal is refused with --json: that output is meant for a program or a log file, " +
                    "which is exactly where a key should not end up. Read the value on a terminal, " +
                    "or pass it through ${SettingsRepository.ENV_AUTH_KEY}."
        )
        if (!outputIsTerminal()) throw ConfigException(
            "--reveal only prints to an interactive terminal, and this output is redirected. " +
                    "Piping or saving a key turns one secret into two copies, and the second one " +
                    "outlives the first. Run it on a terminal, or set ${SettingsRepository.ENV_AUTH_KEY} " +
                    "for unattended use."
        )
    }

    private fun confirmPlaintextExport(target: String): Boolean {
        System.err.println("This writes ${ConfigManager.fields.count { it.secret }} secret value(s) to $target in plain text.")
        System.err.println("Anything that can read that file can use them: backups, sync folders and issue reports included.")
        return ConsoleInput.askYesNo("Continue? [y/N]", 60_000L, default = false)
    }

    fun run(cmd: ConfigCommand, json: Boolean, reveal: Boolean = false): Int {
        return try {
            requireRevealAllowed(reveal, json)
            when (cmd) {
                is ConfigCommand.Path   -> doPath(json)
                is ConfigCommand.List   -> doList(json, reveal)
                is ConfigCommand.Get    -> doGet(cmd.key, json, reveal)
                is ConfigCommand.Set    -> doSet(cmd.key, cmd.value, json, reveal)
                is ConfigCommand.Reset  -> doReset(json)
                is ConfigCommand.Edit   -> doEdit(json)
                is ConfigCommand.Export -> doExport(cmd.path, json, reveal)
                is ConfigCommand.Import -> doImport(cmd.path, json)
            }
            ExitCode.OK
        } catch (e: ConfigException) {
            if (json) println("{\"status\": \"error\", \"message\": \"${jsonEscape(e.message ?: "error")}\"}")
            else System.err.println("wfas config: ${e.message}")
            ExitCode.USAGE_ERROR
        }
    }

    private fun doPath(json: Boolean) {
        val file = ConfigPaths.configFile()
        if (json) {
            println("{\"status\": \"ok\", \"os\": \"${jsonEscape(ConfigPaths.osLabel)}\", \"path\": \"${jsonEscape(file.path)}\", \"exists\": ${file.exists()}}")
        } else {
            println(file.path)
        }
    }

    private fun doList(json: Boolean, reveal: Boolean) {
        val settings = SettingsRepository.loadSettings()
        if (json) {
            val body = ConfigManager.fields.joinToString(",\n") { f ->
                "  \"${f.key}\": ${jsonValue(f, settings, reveal)}"
            }
            println("{\n$body\n}")
            return
        }
        val width = ConfigManager.fields.maxOf { it.key.length }
        var section = ""
        for (f in ConfigManager.fields) {
            val sec = f.key.substringBefore('.')
            if (sec != section) {
                section = sec
                println()
                println("[$sec]")
            }
            val value = ConfigManager.display(settings, f, reveal)
            println("  ${f.key.padEnd(width)}  = ${value.ifEmpty { "(empty)" }}")
        }
        println()
        println("Config file: ${ConfigPaths.configFile().path}")
    }

    private fun doGet(key: String, json: Boolean, reveal: Boolean) {
        val field = ConfigManager.fieldByKey(key)
            ?: throw ConfigException("unknown key '$key'. Run 'wfas config list' to see all keys.")
        val settings = SettingsRepository.loadSettings()
        if (json) println("{\"status\": \"ok\", \"key\": \"${jsonEscape(field.key)}\", \"value\": ${jsonValue(field, settings, reveal)}}")
        else println(ConfigManager.display(settings, field, reveal))
        if (field.secret && !reveal && ConfigManager.display(settings, field, true).isNotEmpty()) {
            System.err.println("Value hidden. Add --reveal to print it.")
        }
    }

    private fun doSet(key: String, value: String, json: Boolean, reveal: Boolean) {
        val field = ConfigManager.fieldByKey(key)
            ?: throw ConfigException("unknown key '$key'. Run 'wfas config list' to see all keys.")
        val current = SettingsRepository.loadSettings()
        val updated = ConfigManager.withSet(current, field.key, value)
        SettingsRepository.saveSettings(updated)
        if (json) println("{\"status\": \"ok\", \"key\": \"${jsonEscape(field.key)}\", \"value\": ${jsonValue(field, updated, reveal)}}")
        else println("${field.key} = ${ConfigManager.display(updated, field, reveal)}")
        // Il valore e' appena stato battuto sulla riga di comando, quindi e'
        // gia' nella cronologia della shell: meglio dirlo che lasciarlo li'.
        if (field.secret) {
            System.err.println("Warning: this value is now in your shell history. Clear it, or set the key from the app instead.")
        }
    }

    private fun doReset(json: Boolean) {
        SettingsRepository.saveSettings(ConfigManager.DEFAULTS)
        if (json) println("{\"status\": \"ok\", \"reset\": true, \"path\": \"${jsonEscape(ConfigPaths.configFile().path)}\"}")
        else println("Configuration reset to defaults: ${ConfigPaths.configFile().path}")
    }

    private fun doEdit(json: Boolean) {
        val file = ConfigPaths.configFile()
        if (!file.exists()) SettingsRepository.saveSettings(SettingsRepository.loadSettings())
        val path = file.path
        val editorEnv = System.getenv("VISUAL")?.takeIf { it.isNotBlank() }
            ?: System.getenv("EDITOR")?.takeIf { it.isNotBlank() }
        val (cmd, wait) = when {
            editorEnv != null                 -> (editorEnv.trim().split(Regex("\\s+")) + path) to true
            ConfigPaths.os == HostOs.WINDOWS  -> listOf("cmd", "/c", "start", "", path) to false
            ConfigPaths.os == HostOs.MACOS    -> listOf("open", "-t", path) to false
            else                              -> listOf("xdg-open", path) to false
        }
        try {
            val pb = ProcessBuilder(cmd)
            if (wait) {
                val code = pb.inheritIO().start().waitFor()
                if (json) println("{\"status\": \"ok\", \"edited\": \"${jsonEscape(path)}\", \"exitCode\": $code}")
                else System.err.println("Editor closed for $path")
            } else {
                pb.start()
                if (json) println("{\"status\": \"ok\", \"editing\": \"${jsonEscape(path)}\"}")
                else System.err.println("Opening $path in your default editor...")
            }
        } catch (e: Exception) {
            throw ConfigException("could not launch an editor (${e.message}). Set \$EDITOR, or edit the file directly: $path")
        }
    }

    private fun doExport(path: String?, json: Boolean, reveal: Boolean) {
        val settings = SettingsRepository.loadSettings()
        if (reveal && ConfigManager.fields.any { it.secret && ConfigManager.display(settings, it, true).isNotEmpty() }) {
            if (!confirmPlaintextExport(path ?: "standard output")) {
                System.err.println("Export cancelled. Nothing was written.")
                return
            }
        }
        val text =
            if (reveal) ConfigManager.serialize(settings)
            else ConfigManager.serializeRedacted(settings)
        val omitted = !reveal && ConfigManager.fields.any {
            it.secret && ConfigManager.display(settings, it, true).isNotEmpty()
        }
        if (path == null) {
            print(text)
            if (omitted) System.err.println(OMITTED_NOTE)
            return
        }
        val file = File(path)
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        file.writeText(text)
        if (json) println("{\"status\": \"ok\", \"exported\": \"${jsonEscape(file.path)}\", \"secretsOmitted\": $omitted}")
        else System.err.println("Exported configuration to ${file.path}")
        if (omitted && !json) System.err.println(OMITTED_NOTE)
    }

    private val OMITTED_NOTE: String
        get() {
            val keys = ConfigManager.fields.filter { it.secret }.joinToString(", ") { it.key }
            return "Note: $keys omitted. Importing this file will not restore them. " +
                    "Add --reveal to include them, and treat the result as a secret."
        }

    private fun doImport(path: String, json: Boolean) {
        val file = File(path)
        if (!file.exists()) throw ConfigException("file not found: $path")
        val text = try { file.readText() } catch (e: Exception) { throw ConfigException("cannot read $path: ${e.message}") }
        val imported = ConfigManager.deserialize(text)
        SettingsRepository.saveSettings(imported)
        if (json) println("{\"status\": \"ok\", \"imported\": \"${jsonEscape(file.path)}\", \"path\": \"${jsonEscape(ConfigPaths.configFile().path)}\"}")
        else System.err.println("Imported configuration from ${file.path} into ${ConfigPaths.configFile().path}")
    }
}