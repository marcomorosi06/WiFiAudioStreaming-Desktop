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
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ConfigException(message: String) : Exception(message)

enum class CfgKind { STRING, INT, PORT, FLOAT, BOOL, LONG_NULLABLE, STRING_LIST, ENUM, COLOR }

class CfgField(
    val key: String,
    val kind: CfgKind,
    val enumValues: List<String>,
    val desc: String,
    val getter: (AllSettings) -> Any?,
    val setter: (AllSettings, Any?) -> AllSettings
)

object ConfigManager {

    val DEFAULTS: AllSettings = AllSettings(
        app = AppSettings(),
        audio = AudioSettings_V1(48000f, 16, 2, 512, 120, 1390),
        streamingPort = "9090",
        micPort = "9092",
        micRoutingMode = "OFF"
    )

    private fun appCopy(s: AllSettings, block: AppSettings.() -> AppSettings): AllSettings =
        s.copy(app = s.app.block())

    private fun audioCopy(s: AllSettings, block: AudioSettings_V1.() -> AudioSettings_V1): AllSettings =
        s.copy(audio = s.audio.block())

    val fields: List<CfgField> = listOf(
        CfgField("audio.sampleRate", CfgKind.FLOAT, emptyList(), "Sample rate in Hz (e.g. 48000, 44100)",
            { it.audio.sampleRate }, { s, v -> audioCopy(s) { copy(sampleRate = coerceFloat("audio.sampleRate", v, min = 8000f, max = 384000f)) } }),
        CfgField("audio.bitDepth", CfgKind.INT, emptyList(), "Bits per sample (only 16 is supported)",
            { it.audio.bitDepth }, { s, v -> val n = coerceInt("audio.bitDepth", v); if (n != 16) throw ConfigException("audio.bitDepth must be 16"); audioCopy(s) { copy(bitDepth = 16) } }),
        CfgField("audio.channels", CfgKind.INT, emptyList(), "Channel count (1 = mono, 2 = stereo)",
            { it.audio.channels }, { s, v -> audioCopy(s) { copy(channels = coerceInt("audio.channels", v, min = 1, max = 8)) } }),
        CfgField("audio.bufferSize", CfgKind.INT, emptyList(), "Capture buffer size in bytes",
            { it.audio.bufferSize }, { s, v -> audioCopy(s) { copy(bufferSize = coerceInt("audio.bufferSize", v, min = 1)) } }),
        CfgField("audio.latencyMs", CfgKind.INT, emptyList(), "Target playout latency in milliseconds",
            { it.audio.latencyMs }, { s, v -> audioCopy(s) { copy(latencyMs = coerceInt("audio.latencyMs", v, min = 0, max = 5000)) } }),
        CfgField("audio.maxPayloadBytes", CfgKind.INT, emptyList(), "Max PCM bytes per packet (MTU bound)",
            { it.audio.maxPayloadBytes }, { s, v -> audioCopy(s) { copy(maxPayloadBytes = coerceInt("audio.maxPayloadBytes", v, min = 64, max = 65000)) } }),

        CfgField("net.streamingPort", CfgKind.PORT, emptyList(), "WFAS streaming UDP port",
            { it.streamingPort.toIntOrNull() ?: 9090 }, { s, v -> s.copy(streamingPort = coercePort("net.streamingPort", v).toString()) }),
        CfgField("net.micPort", CfgKind.PORT, emptyList(), "Microphone return UDP port",
            { it.micPort.toIntOrNull() ?: 9092 }, { s, v -> s.copy(micPort = coercePort("net.micPort", v).toString()) }),
        CfgField("net.rtpPort", CfgKind.PORT, emptyList(), "RTP port",
            { it.app.rtpPort.toIntOrNull() ?: 9094 }, { s, v -> appCopy(s) { copy(rtpPort = coercePort("net.rtpPort", v).toString()) } }),
        CfgField("net.httpPort", CfgKind.PORT, emptyList(), "HTTP stream port",
            { it.app.httpPort.toIntOrNull() ?: 8080 }, { s, v -> appCopy(s) { copy(httpPort = coercePort("net.httpPort", v).toString()) } }),
        CfgField("net.interface", CfgKind.STRING, emptyList(), "Network interface name or 'Auto'",
            { it.app.networkInterface }, { s, v -> appCopy(s) { copy(networkInterface = coerceString("net.interface", v)) } }),
        CfgField("net.micRoutingMode", CfgKind.ENUM, listOf("OFF", "MIX_INTO_STREAM", "VIRTUAL_MIC"), "Microphone routing mode",
            { it.micRoutingMode }, { s, v -> s.copy(micRoutingMode = coerceEnum("net.micRoutingMode", v, listOf("OFF", "MIX_INTO_STREAM", "VIRTUAL_MIC"))) }),

        CfgField("server.rtpEnabled", CfgKind.BOOL, emptyList(), "Advertise RTP protocol",
            { it.app.rtpEnabled }, { s, v -> appCopy(s) { copy(rtpEnabled = coerceBool("server.rtpEnabled", v)) } }),
        CfgField("server.httpEnabled", CfgKind.BOOL, emptyList(), "Advertise HTTP stream",
            { it.app.httpEnabled }, { s, v -> appCopy(s) { copy(httpEnabled = coerceBool("server.httpEnabled", v)) } }),
        CfgField("server.httpSafariMode", CfgKind.BOOL, emptyList(), "Safari-compatible HLS",
            { it.app.httpSafariMode }, { s, v -> appCopy(s) { copy(httpSafariMode = coerceBool("server.httpSafariMode", v)) } }),
        CfgField("server.autoStartServer", CfgKind.BOOL, emptyList(), "Start server automatically on launch",
            { it.app.autoStartServer }, { s, v -> appCopy(s) { copy(autoStartServer = coerceBool("server.autoStartServer", v)) } }),
        CfgField("server.autoStartMulticast", CfgKind.BOOL, emptyList(), "Use multicast on auto-start",
            { it.app.autoStartMulticast }, { s, v -> appCopy(s) { copy(autoStartMulticast = coerceBool("server.autoStartMulticast", v)) } }),
        CfgField("server.lastMulticastMode", CfgKind.BOOL, emptyList(), "Remember last multicast choice",
            { it.app.lastMulticastMode }, { s, v -> appCopy(s) { copy(lastMulticastMode = coerceBool("server.lastMulticastMode", v)) } }),

        CfgField("security.mode", CfgKind.ENUM, listOf("OFF", "ASK", "KEY"), "Connection authorization mode",
            { it.app.securityMode }, { s, v -> appCopy(s) { copy(securityMode = coerceEnum("security.mode", v, listOf("OFF", "ASK", "KEY"))) } }),
        CfgField("security.authKey", CfgKind.STRING, emptyList(), "Pre-shared key (used when mode = KEY)",
            { it.app.authKey }, { s, v -> appCopy(s) { copy(authKey = coerceString("security.authKey", v)) } }),
        CfgField("security.encryptionEnabled", CfgKind.BOOL, emptyList(), "Encrypt audio with ChaCha20-Poly1305",
            { it.app.encryptionEnabled }, { s, v -> appCopy(s) { copy(encryptionEnabled = coerceBool("security.encryptionEnabled", v)) } }),

        CfgField("app.theme", CfgKind.ENUM, listOf("Light", "Dark", "System"), "UI theme",
            { it.app.theme.name }, { s, v -> appCopy(s) { copy(theme = Theme.valueOf(coerceEnum("app.theme", v, listOf("Light", "Dark", "System")))) } }),
        CfgField("app.customThemeColor", CfgKind.COLOR, emptyList(), "Seed color as #RRGGBB or #AARRGGBB, or null",
            { it.app.customThemeColor?.let { c -> colorLongToString(c) } }, { s, v -> appCopy(s) { copy(customThemeColor = parseColor("app.customThemeColor", v)) } }),
        CfgField("app.launchAtStartup", CfgKind.BOOL, emptyList(), "Launch app at OS login",
            { it.app.launchAtStartup }, { s, v -> appCopy(s) { copy(launchAtStartup = coerceBool("app.launchAtStartup", v)) } }),
        CfgField("app.autoConnectClientEnabled", CfgKind.BOOL, emptyList(), "Client auto-connects on launch",
            { it.app.autoConnectClientEnabled }, { s, v -> appCopy(s) { copy(autoConnectClientEnabled = coerceBool("app.autoConnectClientEnabled", v)) } }),
        CfgField("app.autoConnectIps", CfgKind.STRING_LIST, emptyList(), "Saved server IPs for auto-connect",
            { it.app.autoConnectIps }, { s, v -> appCopy(s) { copy(autoConnectIps = coerceStringList("app.autoConnectIps", v)) } }),
        CfgField("app.connectionSoundEnabled", CfgKind.BOOL, emptyList(), "Play sound on connect",
            { it.app.connectionSoundEnabled }, { s, v -> appCopy(s) { copy(connectionSoundEnabled = coerceBool("app.connectionSoundEnabled", v)) } }),
        CfgField("app.disconnectionSoundEnabled", CfgKind.BOOL, emptyList(), "Play sound on disconnect",
            { it.app.disconnectionSoundEnabled }, { s, v -> appCopy(s) { copy(disconnectionSoundEnabled = coerceBool("app.disconnectionSoundEnabled", v)) } }),
        CfgField("app.useNativeEngine", CfgKind.BOOL, emptyList(), "Use native C audio engine (vs legacy FFmpeg)",
            { it.app.useNativeEngine }, { s, v -> appCopy(s) { copy(useNativeEngine = coerceBool("app.useNativeEngine", v)) } }),
        CfgField("app.startMinimizedToTray", CfgKind.BOOL, emptyList(), "Start minimized to system tray",
            { it.app.startMinimizedToTray }, { s, v -> appCopy(s) { copy(startMinimizedToTray = coerceBool("app.startMinimizedToTray", v)) } }),
        CfgField("app.closeToTray", CfgKind.BOOL, emptyList(), "Close button hides to tray",
            { it.app.closeToTray }, { s, v -> appCopy(s) { copy(closeToTray = coerceBool("app.closeToTray", v)) } }),
        CfgField("app.autoUpdateCheckEnabled", CfgKind.BOOL, emptyList(), "Check for updates at startup",
            { it.app.autoUpdateCheckEnabled }, { s, v -> appCopy(s) { copy(autoUpdateCheckEnabled = coerceBool("app.autoUpdateCheckEnabled", v)) } }),
        CfgField("app.hideWindowsPrivacyBanner", CfgKind.BOOL, emptyList(), "Hide the Windows privacy banner",
            { it.app.hideWindowsPrivacyBanner }, { s, v -> appCopy(s) { copy(hideWindowsPrivacyBanner = coerceBool("app.hideWindowsPrivacyBanner", v)) } }),
        CfgField("app.hideWindowsRoutingBanner", CfgKind.BOOL, emptyList(), "Hide the Windows routing banner",
            { it.app.hideWindowsRoutingBanner }, { s, v -> appCopy(s) { copy(hideWindowsRoutingBanner = coerceBool("app.hideWindowsRoutingBanner", v)) } })
    )

    private val fieldMap: Map<String, CfgField> = fields.associateBy { it.key.lowercase() }

    fun fieldByKey(key: String): CfgField? = fieldMap[key.trim().lowercase()]

    fun keys(): List<String> = fields.map { it.key }

    fun exists(): Boolean = ConfigPaths.configFile().exists()

    fun loadOrNull(): AllSettings? {
        val file = ConfigPaths.configFile()
        if (!file.exists()) return null
        val text = try { file.readText() } catch (e: Exception) { throw ConfigException("cannot read ${file.path}: ${e.message}") }
        return deserialize(text)
    }

    fun load(): AllSettings = loadOrNull() ?: DEFAULTS

    fun deserialize(json: String): AllSettings {
        val root = try { Json.parse(json) } catch (e: Exception) { throw ConfigException("invalid JSON: ${e.message}") }
        if (root !is Map<*, *>) throw ConfigException("config root must be a JSON object")
        val flat = HashMap<String, Any?>()
        for ((section, body) in root) {
            val sk = section?.toString() ?: continue
            if (body is Map<*, *>) {
                for ((leaf, value) in body) flat["$sk.${leaf?.toString()}".lowercase()] = value
            } else {
                flat[sk.lowercase()] = body
            }
        }
        var result = DEFAULTS
        for (field in fields) {
            val raw = flat[field.key.lowercase()] ?: continue
            result = try { field.setter(result, raw) } catch (e: ConfigException) { throw e } catch (e: Exception) {
                throw ConfigException("invalid value for ${field.key}: ${e.message}")
            }
        }
        return result
    }

    fun serialize(settings: AllSettings): String {
        val sections = LinkedHashMap<String, LinkedHashMap<String, Any?>>()
        for (field in fields) {
            val dot = field.key.indexOf('.')
            val section = if (dot >= 0) field.key.substring(0, dot) else "general"
            val leaf = if (dot >= 0) field.key.substring(dot + 1) else field.key
            sections.getOrPut(section) { LinkedHashMap() }[leaf] = field.getter(settings)
        }
        val sb = StringBuilder()
        sb.append("{\n")
        val secEntries = sections.entries.toList()
        secEntries.forEachIndexed { si, (section, leaves) ->
            sb.append("  ").append(Json.str(section)).append(": {\n")
            val leafEntries = leaves.entries.toList()
            leafEntries.forEachIndexed { li, (leaf, value) ->
                sb.append("    ").append(Json.str(leaf)).append(": ").append(Json.write(value))
                sb.append(if (li < leafEntries.size - 1) ",\n" else "\n")
            }
            sb.append("  }")
            sb.append(if (si < secEntries.size - 1) ",\n" else "\n")
        }
        sb.append("}\n")
        return sb.toString()
    }

    fun save(settings: AllSettings) {
        val file = ConfigPaths.configFile()
        val dir = file.parentFile
        if (dir != null && !dir.exists()) dir.mkdirs()
        val text = serialize(settings)
        val tmp = File(file.parentFile ?: File("."), file.name + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    fun display(settings: AllSettings, field: CfgField): String {
        val v = field.getter(settings)
        return when (v) {
            null -> ""
            is List<*> -> v.joinToString(",")
            else -> v.toString()
        }
    }

    fun withSet(settings: AllSettings, key: String, rawValue: Any?): AllSettings {
        val field = fieldByKey(key) ?: throw ConfigException("unknown key '$key'. Run 'wfas config list' to see all keys.")
        return field.setter(settings, rawValue)
    }

    private fun coerceBool(key: String, v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number  -> v.toInt() != 0
        is String  -> when (v.trim().lowercase()) {
            "true", "on", "1", "yes", "enable", "enabled"  -> true
            "false", "off", "0", "no", "disable", "disabled" -> false
            else -> throw ConfigException("$key must be true/false (got '$v')")
        }
        else -> throw ConfigException("$key must be a boolean")
    }

    private fun coerceInt(key: String, v: Any?, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): Int {
        val n = when (v) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull() ?: throw ConfigException("$key must be an integer (got '$v')")
            else -> throw ConfigException("$key must be an integer")
        }
        if (n < min || n > max) throw ConfigException("$key must be between $min and $max (got $n)")
        return n
    }

    private fun coercePort(key: String, v: Any?): Int = coerceInt(key, v, 1024, 65535)

    private fun coerceFloat(key: String, v: Any?, min: Float, max: Float): Float {
        val n = when (v) {
            is Number -> v.toFloat()
            is String -> v.trim().toFloatOrNull() ?: throw ConfigException("$key must be a number (got '$v')")
            else -> throw ConfigException("$key must be a number")
        }
        if (n < min || n > max) throw ConfigException("$key must be between ${min.toInt()} and ${max.toInt()} (got ${n.toInt()})")
        return n
    }

    fun colorLongToString(value: Long): String {
        val low = value and 0xFFFFFFFFL
        return if (low == 0L) "#%08X".format((value ushr 32).toInt())
        else "0x%016X".format(value)
    }

    private fun argbToPacked(argb: Int): Long = (argb.toLong() and 0xFFFFFFFFL) shl 32

    private fun hexToArgb(key: String, raw: String): Int {
        var h = raw.trim()
        if (h.length == 3) h = buildString { for (c in h) { append(c); append(c) } }
        if (h.length != 6 && h.length != 8)
            throw ConfigException("$key: color must be #RGB, #RRGGBB or #AARRGGBB (got '$raw')")
        if (!h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
            throw ConfigException("$key: invalid hex color '$raw'")
        val n = h.toLong(16)
        return if (h.length == 6) (0xFF000000L or n).toInt() else n.toInt()
    }

    private fun parseColor(key: String, v: Any?): Long? = when (v) {
        null      -> null
        is Number -> v.toLong()
        is String -> {
            val t = v.trim()
            when {
                t.isEmpty() || t.equals("null", true) || t.equals("none", true) -> null
                t.startsWith("#")           -> argbToPacked(hexToArgb(key, t.substring(1)))
                t.startsWith("0x", true)    -> {
                    val h = t.substring(2)
                    if (h.length <= 8) argbToPacked(hexToArgb(key, h))
                    else h.toULongOrNull(16)?.toLong() ?: throw ConfigException("$key: invalid hex value '$t'")
                }
                else -> t.toLongOrNull() ?: argbToPacked(hexToArgb(key, t))
            }
        }
        else -> throw ConfigException("$key must be a color like #RRGGBB, #AARRGGBB, or null")
    }

    private fun coerceString(key: String, v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> throw ConfigException("$key must be a string")
    }

    private fun coerceStringList(key: String, v: Any?): List<String> = when (v) {
        null -> emptyList()
        is List<*> -> v.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        is String -> v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> throw ConfigException("$key must be a list or comma-separated string")
    }

    private fun coerceEnum(key: String, v: Any?, allowed: List<String>): String {
        val s = coerceString(key, v).trim()
        return allowed.firstOrNull { it.equals(s, ignoreCase = true) }
            ?: throw ConfigException("$key must be one of ${allowed.joinToString(", ")} (got '$s')")
    }
}

private object Json {

    fun parse(s: String): Any? {
        val p = Parser(s)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw ConfigException("unexpected trailing characters at ${p.pos}")
        return v
    }

    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"'  -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append("\"")
        return sb.toString()
    }

    fun write(v: Any?): String = when (v) {
        null       -> "null"
        is String  -> str(v)
        is Boolean -> v.toString()
        is Number  -> numToStr(v)
        is List<*> -> "[" + v.joinToString(", ") { write(it) } + "]"
        else       -> str(v.toString())
    }

    private fun numToStr(n: Number): String {
        if (n is Int || n is Long) return n.toString()
        val d = n.toDouble()
        return if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 9.0e15) d.toLong().toString() else n.toString()
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun peek() = s[pos]
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun readValue(): Any? {
            skipWs()
            if (atEnd()) throw ConfigException("unexpected end of input")
            return when (peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBool()
                'n' -> readNull()
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (!atEnd() && peek() == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (atEnd() || peek() != '"') throw ConfigException("expected string key at $pos")
                val key = readString()
                skipWs()
                if (atEnd() || peek() != ':') throw ConfigException("expected ':' at $pos")
                pos++
                val value = readValue()
                map[key] = value
                skipWs()
                if (atEnd()) throw ConfigException("unterminated object")
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw ConfigException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++
            skipWs()
            if (!atEnd() && peek() == ']') { pos++; return list }
            while (true) {
                list.add(readValue())
                skipWs()
                if (atEnd()) throw ConfigException("unterminated array")
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw ConfigException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++
            val sb = StringBuilder()
            while (!atEnd()) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw ConfigException("unterminated escape")
                        when (val e = s[pos++]) {
                            '"'  -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/'  -> sb.append('/')
                            'n'  -> sb.append('\n')
                            'r'  -> sb.append('\r')
                            't'  -> sb.append('\t')
                            'b'  -> sb.append('\b')
                            'f'  -> sb.append('\u000C')
                            'u'  -> {
                                if (pos + 4 > s.length) throw ConfigException("bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw ConfigException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw ConfigException("unterminated string")
        }

        private fun readBool(): Boolean {
            if (s.startsWith("true", pos)) { pos += 4; return true }
            if (s.startsWith("false", pos)) { pos += 5; return false }
            throw ConfigException("invalid literal at $pos")
        }

        private fun readNull(): Any? {
            if (s.startsWith("null", pos)) { pos += 4; return null }
            throw ConfigException("invalid literal at $pos")
        }

        private fun readNumber(): Number {
            val start = pos
            if (!atEnd() && (peek() == '-' || peek() == '+')) pos++
            var isDouble = false
            while (!atEnd()) {
                val c = peek()
                if (c.isDigit()) pos++
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { isDouble = true; pos++ }
                else break
            }
            val token = s.substring(start, pos)
            if (token.isEmpty() || token == "-") throw ConfigException("invalid number at $start")
            return if (isDouble) token.toDouble()
            else token.toLongOrNull() ?: token.toDouble()
        }
    }
}
