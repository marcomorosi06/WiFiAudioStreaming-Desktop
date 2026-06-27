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

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class SecurityMode {
    OFF, ASK, KEY;

    companion object {
        fun fromStringSafe(s: String?): SecurityMode =
            runCatching { valueOf((s ?: "OFF").uppercase()) }.getOrDefault(OFF)
    }
}

object WfasAuth {
    private val rng = SecureRandom()

    fun nonceHex(): String = ByteArray(16).also { rng.nextBytes(it) }.toHex()

    fun proof(key: String, side: Char, cnonce: String, snonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("WFAS-$side:$cnonce:$snonce".toByteArray(Charsets.UTF_8)).toHex()
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].code xor b[i].code)
        return d == 0
    }

    fun getToken(msg: String, token: String): String? {
        val needle = ";$token="
        val i = msg.indexOf(needle)
        if (i < 0) return null
        val start = i + needle.length
        var end = start
        while (end < msg.length && msg[end] != ';') end++
        return msg.substring(start, end)
    }

    private fun ByteArray.toHex(): String {
        val h = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (x in this) {
            val v = x.toInt() and 0xFF
            sb.append(h[v ushr 4]); sb.append(h[v and 15])
        }
        return sb.toString()
    }
}
