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

    val requiresKey: Boolean get() = this == KEY

    companion object {
        const val UI_MODE_QR = "QR"

        fun fromStringSafe(s: String?): SecurityMode =
            runCatching { valueOf((s ?: "OFF").uppercase()) }.getOrDefault(OFF)

        fun requiresKey(s: String?): Boolean = fromStringSafe(s).requiresKey

        fun uiMode(stored: String?, qrPairing: Boolean): String =
            if (qrPairing && fromStringSafe(stored) == KEY) UI_MODE_QR
            else fromStringSafe(stored).name

        fun storedMode(uiMode: String): String =
            if (uiMode.equals(UI_MODE_QR, ignoreCase = true)) KEY.name
            else fromStringSafe(uiMode).name

        fun isQrUiMode(uiMode: String): Boolean = uiMode.equals(UI_MODE_QR, ignoreCase = true)

        fun encryptionForced(uiMode: String, multicast: Boolean): Boolean =
            multicast && isQrUiMode(uiMode)

        fun encryptionForcedStored(
            storedMode: String?,
            qrPairing: Boolean,
            multicast: Boolean
        ): Boolean = encryptionForced(uiMode(storedMode, qrPairing), multicast)
    }
}

object WfasAuth {
    private val rng = SecureRandom()

    const val PAIRING_KEY_BYTES = 32

    fun nonceHex(): String = ByteArray(16).also { rng.nextBytes(it) }.toHex()

    fun randomPairingKey(): String {
        val raw = ByteArray(PAIRING_KEY_BYTES).also { rng.nextBytes(it) }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    fun groupKeyForDisplay(key: String, blockSize: Int = 5): String =
        key.chunked(blockSize).joinToString("-")

    fun nextSecurityState(
        storedMode: String,
        authKey: String,
        manualAuthKey: String,
        qrPairingEnabled: Boolean,
        uiMode: String
    ): SecurityState {
        val wasQr = qrPairingEnabled && SecurityMode.requiresKey(storedMode)
        val nowQr = SecurityMode.isQrUiMode(uiMode)

        if (nowQr) {
            val seeded =
                if (!wasQr && manualAuthKey.isBlank() && SecurityMode.requiresKey(storedMode)) authKey
                else manualAuthKey
            return SecurityState(SecurityMode.KEY.name, authKey, seeded, true)
        }

        val effective = if (wasQr) manualAuthKey else authKey
        return SecurityState(SecurityMode.storedMode(uiMode), effective, effective, false)
    }

    data class SecurityState(
        val storedMode: String,
        val authKey: String,
        val manualAuthKey: String,
        val qrPairingEnabled: Boolean
    )

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
