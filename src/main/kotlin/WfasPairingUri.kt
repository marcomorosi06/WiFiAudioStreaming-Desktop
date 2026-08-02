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


import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class PairingPayload(
    val ip: String,
    val port: Int,
    val mode: String,
    val keyBase64: String,
    val expEpochSeconds: Long,
    val mcastEpoch: Long?,
    val version: Int
) {
    val isMulticast: Boolean get() = mode == WfasPairingUri.MODE_MULTICAST
}

object WfasPairingUri {

    const val SCHEME = "wifiaudio"
    const val HOST = "pair"
    const val VERSION = 2
    const val MODE_UNICAST = "unicast"
    const val MODE_MULTICAST = "multicast"

    const val APPLINK_HOST = "www.marcomorosi.eu"
    const val APPLINK_PATH = "/wifi-audio-streaming/pair"
    const val APPLINK_PATH_IT = "/it/wifi-audio-streaming/pair"

    private val APPLINK_PATHS = setOf(APPLINK_PATH, APPLINK_PATH_IT)
    private val APPLINK_HOSTS = setOf(APPLINK_HOST, APPLINK_HOST.removePrefix("www."))

    const val CLOCK_SKEW_SECONDS = 30L
    const val PAIRING_TTL_SECONDS = 120L

    private val SUPPORTED_VERSIONS = setOf(2)
    private val KEY_CHARSET = Regex("^[A-Za-z0-9_-]{16,512}$")

    fun build(
        ip: String,
        port: Int,
        mode: String,
        keyBase64: String,
        expEpochSeconds: Long,
        mcastEpoch: Long? = null
    ): String {
        val params = LinkedHashMap<String, String>()
        params["ip"] = ip
        params["port"] = port.toString()
        params["mode"] = mode
        params["key"] = keyBase64
        if (mode == MODE_MULTICAST && mcastEpoch != null) params["epoch"] = mcastEpoch.toString()
        params["exp"] = expEpochSeconds.toString()
        params["v"] = VERSION.toString()

        val query = params.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return "$SCHEME://$HOST?$query"
    }

    fun buildAppLink(
        ip: String,
        port: Int,
        mode: String,
        keyBase64: String,
        expEpochSeconds: Long,
        mcastEpoch: Long? = null,
        italian: Boolean = java.util.Locale.getDefault().language == "it"
    ): String {
        val custom = build(ip, port, mode, keyBase64, expEpochSeconds, mcastEpoch)
        val path = if (italian) APPLINK_PATH_IT else APPLINK_PATH
        return "https://$APPLINK_HOST$path/#" + custom.substringAfter('?')
    }

    fun parse(uri: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): PairingPayload? {
        val parsed = runCatching { URI(uri.trim()) }.getOrNull() ?: return null

        val scheme = parsed.scheme?.lowercase() ?: return null
        when (scheme) {
            SCHEME -> {
                val target = (parsed.host ?: parsed.authority)?.lowercase()?.substringBefore(':')
                val path = parsed.path?.trim('/')?.lowercase().orEmpty()
                if (target != HOST && path != HOST) return null
            }
            "https" -> {
                if (parsed.host?.lowercase() !in APPLINK_HOSTS) return null
                val path = parsed.path?.trimEnd('/')?.lowercase()
                if (path !in APPLINK_PATHS) return null
            }
            else -> return null
        }

        val raw = parsed.rawFragment?.takeIf { it.isNotBlank() }
            ?: parsed.rawQuery?.takeIf { it.isNotBlank() }
            ?: return null
        val q = queryMap(raw)

        val version = q["v"]?.toIntOrNull() ?: return null
        if (version !in SUPPORTED_VERSIONS) return null

        val mode = q["mode"]?.lowercase() ?: return null
        if (mode != MODE_UNICAST && mode != MODE_MULTICAST) return null

        val ip = q["ip"]?.trim().orEmpty()
        if (ip.isBlank()) return null

        val port = q["port"]?.toIntOrNull() ?: return null
        if (port !in 1..65535) return null

        val key = q["key"].orEmpty()
        if (!KEY_CHARSET.matches(key)) return null

        val exp = q["exp"]?.toLongOrNull() ?: return null
        if (exp <= 0L) return null
        if (nowEpochSeconds - CLOCK_SKEW_SECONDS > exp) return null

        val mcastEpoch = if (mode == MODE_MULTICAST) {
            val raw = q["epoch"]
            if (raw == null) null else (raw.toLongOrNull()?.takeIf { it >= 0L } ?: return null)
        } else {
            null
        }

        return PairingPayload(
            ip = ip,
            port = port,
            mode = mode,
            keyBase64 = key,
            expEpochSeconds = exp,
            mcastEpoch = mcastEpoch,
            version = version
        )
    }

    fun isExpiredUri(uri: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        if (parse(uri, nowEpochSeconds) != null) return false
        return parse(uri, 0L) != null
    }

    private fun queryMap(rawQuery: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val k = dec(pair.substring(0, i)).lowercase()
            val v = dec(pair.substring(i + 1))
            if (k.isNotEmpty() && !out.containsKey(k)) out[k] = v
        }
        return out
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun dec(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
