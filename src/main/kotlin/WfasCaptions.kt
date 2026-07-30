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

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object WfasCaptions {

    const val EXT_VERSION = 1

    const val MAGIC0 = 0x57
    const val MAGIC1 = 0x43
    const val HEADER_SIZE = 16

    const val FLAG_FINAL = 0x01
    const val FLAG_ENCRYPTED = 0x02
    const val FLAG_CLEAR = 0x04

    const val MAX_TEXT_BYTES = 512
    const val REPEAT_COUNT = 3
    const val REPEAT_DELAY_MS = 30L

    const val REQUEST_TIMEOUT_MS = 3000L
    const val REQUEST_RETRIES = 1

    const val MSG_REQ = "CAP_REQ"
    const val MSG_ACK = "CAP_ACK"
    const val MSG_UNAVAIL = "CAP_UNAVAIL"
    const val MSG_STOP = "CAP_STOP"

    const val REASON_NO_MODEL = "nomodel"
    const val REASON_DISABLED = "disabled"
    const val REASON_TOO_LOW = "toolow"
    const val REASON_LANG = "lang"
    const val REASON_BUSY = "busy"
    const val REASON_DENIED = "denied"

    const val BEACON_TOKEN = "cap"

    fun defaultPortFor(streamingPort: Int): Int = streamingPort + 1

    data class Caption(
        val capId: Long,
        val rev: Int,
        val samplePos: Long,
        val durMs: Int,
        val isFinal: Boolean,
        val isClear: Boolean,
        val text: String
    )

    sealed class Decoded {
        class Ok(val caption: Caption, val counter: Long) : Decoded()
        object Replay : Decoded()
        object AuthFail : Decoded()
        object Malformed : Decoded()
        object PolicyReject : Decoded()
    }

    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, len: Int): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(len)
        g.generateBytes(out, 0, len)
        return out
    }

    private fun aeadEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, pt: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), WfasCrypto.TAG_BYTES * 8, nonce, aad))
        val out = ByteArray(c.getOutputSize(pt.size))
        var off = c.processBytes(pt, 0, pt.size, out, 0)
        off += c.doFinal(out, off)
        return if (off == out.size) out else out.copyOf(off)
    }

    private fun aeadDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ctTag: ByteArray): ByteArray? {
        return try {
            val c = ChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(key), WfasCrypto.TAG_BYTES * 8, nonce, aad))
            val out = ByteArray(c.getOutputSize(ctTag.size))
            var off = c.processBytes(ctTag, 0, ctTag.size, out, 0)
            off += c.doFinal(out, off)
            if (off == out.size) out else out.copyOf(off)
        } catch (_: Exception) {
            null
        }
    }

    fun deriveUnicast(key: String, cnonceHex: String, snonceHex: String): WfasCrypto.Dir {
        val salt = (cnonceHex + snonceHex).toByteArray(Charsets.US_ASCII)
        val ikm = key.toByteArray(Charsets.UTF_8)
        return WfasCrypto.Dir(
            hkdf(salt, ikm, "WFAS cap key".toByteArray(Charsets.US_ASCII), 32),
            hkdf(salt, ikm, "WFAS cap iv".toByteArray(Charsets.US_ASCII), 4)
        )
    }

    fun deriveMulticast(key: String, salt: ByteArray): WfasCrypto.Dir {
        val ikm = key.toByteArray(Charsets.UTF_8)
        return WfasCrypto.Dir(
            hkdf(salt, ikm, "WFAS mcast cap key".toByteArray(Charsets.US_ASCII), 32),
            hkdf(salt, ikm, "WFAS mcast cap iv".toByteArray(Charsets.US_ASCII), 4)
        )
    }

    fun proof(key: String, cnonceHex: String, snonceHex: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return toHex(mac.doFinal("WFAS-CAP:$cnonceHex:$snonceHex".toByteArray(Charsets.UTF_8)))
    }

    fun clampText(text: String): ByteArray {
        var bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_TEXT_BYTES) return bytes
        var end = MAX_TEXT_BYTES
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
    }

    private fun buildHeader(c: Caption, encrypted: Boolean): ByteArray {
        val h = ByteArray(HEADER_SIZE)
        h[0] = MAGIC0.toByte()
        h[1] = MAGIC1.toByte()
        h[2] = EXT_VERSION.toByte()
        var flags = 0
        if (c.isFinal) flags = flags or FLAG_FINAL
        if (encrypted) flags = flags or FLAG_ENCRYPTED
        if (c.isClear) flags = flags or FLAG_CLEAR
        h[3] = flags.toByte()
        h[4] = (c.capId ushr 24).toByte()
        h[5] = (c.capId ushr 16).toByte()
        h[6] = (c.capId ushr 8).toByte()
        h[7] = c.capId.toByte()
        h[8] = (c.rev ushr 8).toByte()
        h[9] = c.rev.toByte()
        h[10] = (c.samplePos ushr 24).toByte()
        h[11] = (c.samplePos ushr 16).toByte()
        h[12] = (c.samplePos ushr 8).toByte()
        h[13] = c.samplePos.toByte()
        h[14] = (c.durMs ushr 8).toByte()
        h[15] = c.durMs.toByte()
        return h
    }

    fun encode(dir: WfasCrypto.Dir?, caption: Caption): ByteArray {
        val payload = if (caption.isClear) ByteArray(0) else clampText(caption.text)
        if (dir == null) return buildHeader(caption, false) + payload
        val header = buildHeader(caption, true)
        val counter = dir.sendCounter
        val cb = ByteArray(WfasCrypto.COUNTER_BYTES)
        for (i in 0 until 8) cb[i] = (counter ushr (56 - 8 * i)).toByte()
        val nonce = ByteArray(12)
        System.arraycopy(dir.noncePrefix, 0, nonce, 0, 4)
        System.arraycopy(cb, 0, nonce, 4, 8)
        val ctTag = aeadEncrypt(dir.key, nonce, header, payload)
        dir.sendCounter = counter + 1
        return header + cb + ctTag
    }

    fun decode(
        dir: WfasCrypto.Dir?,
        win: WfasCrypto.ReplayWindow?,
        buf: ByteArray,
        len: Int,
        requireEncryption: Boolean
    ): Decoded {
        if (len < HEADER_SIZE) return Decoded.Malformed
        if (buf[0].toInt() and 0xFF != MAGIC0 || buf[1].toInt() and 0xFF != MAGIC1) return Decoded.Malformed
        if (buf[2].toInt() and 0xFF != EXT_VERSION) return Decoded.Malformed

        val flags = buf[3].toInt() and 0xFF
        val encrypted = flags and FLAG_ENCRYPTED != 0
        if (requireEncryption && !encrypted) return Decoded.PolicyReject
        if (encrypted && (dir == null || win == null)) return Decoded.PolicyReject

        val capId = ((buf[4].toLong() and 0xFF) shl 24) or ((buf[5].toLong() and 0xFF) shl 16) or
            ((buf[6].toLong() and 0xFF) shl 8) or (buf[7].toLong() and 0xFF)
        val rev = ((buf[8].toInt() and 0xFF) shl 8) or (buf[9].toInt() and 0xFF)
        val samplePos = ((buf[10].toLong() and 0xFF) shl 24) or ((buf[11].toLong() and 0xFF) shl 16) or
            ((buf[12].toLong() and 0xFF) shl 8) or (buf[13].toLong() and 0xFF)
        val durMs = ((buf[14].toInt() and 0xFF) shl 8) or (buf[15].toInt() and 0xFF)

        val isFinal = flags and FLAG_FINAL != 0
        val isClear = flags and FLAG_CLEAR != 0

        if (!encrypted) {
            val text = if (isClear) "" else String(buf, HEADER_SIZE, len - HEADER_SIZE, Charsets.UTF_8)
            return Decoded.Ok(Caption(capId, rev, samplePos, durMs, isFinal, isClear, text), -1L)
        }

        if (len < HEADER_SIZE + WfasCrypto.AEAD_OVERHEAD) return Decoded.Malformed
        var counter = 0L
        for (i in 0 until 8) counter = (counter shl 8) or (buf[HEADER_SIZE + i].toLong() and 0xFF)
        if (!win!!.check(counter)) return Decoded.Replay

        val header = buf.copyOfRange(0, HEADER_SIZE)
        val nonce = ByteArray(12)
        System.arraycopy(dir!!.noncePrefix, 0, nonce, 0, 4)
        System.arraycopy(buf, HEADER_SIZE, nonce, 4, 8)
        val ctTag = buf.copyOfRange(HEADER_SIZE + WfasCrypto.COUNTER_BYTES, len)
        val pt = aeadDecrypt(dir.key, nonce, header, ctTag) ?: return Decoded.AuthFail
        win.commit(counter)

        val text = if (isClear) "" else String(pt, Charsets.UTF_8)
        return Decoded.Ok(Caption(capId, rev, samplePos, durMs, isFinal, isClear, text), counter)
    }

    class Dedup(private val capacity: Int = 64) {
        private val lastRev = LinkedHashMap<Long, Int>()
        private val finalized = LinkedHashSet<Long>()

        fun accept(c: Caption): Boolean {
            if (finalized.contains(c.capId)) return false
            val prev = lastRev[c.capId]
            if (prev != null && c.rev <= prev) return false
            lastRev[c.capId] = c.rev
            if (c.isFinal) {
                finalized.add(c.capId)
                while (finalized.size > capacity) {
                    val oldest = finalized.iterator().next()
                    finalized.remove(oldest)
                }
            }
            while (lastRev.size > capacity) {
                val oldest = lastRev.keys.iterator().next()
                lastRev.remove(oldest)
            }
            return true
        }

        fun reset() {
            lastRev.clear()
            finalized.clear()
        }
    }

    fun buildRequest(langTag: String, proofHex: String?): String {
        val sb = StringBuilder("$MSG_REQ;v=$EXT_VERSION;lang=$langTag")
        if (proofHex != null) sb.append(";proof=").append(proofHex)
        return sb.toString()
    }

    fun buildAck(langTag: String, encrypted: Boolean): String =
        "$MSG_ACK;v=$EXT_VERSION;lang=$langTag;enc=${if (encrypted) 1 else 0}"

    fun buildUnavailable(reason: String): String = "$MSG_UNAVAIL;reason=$reason"

    fun token(msg: String, name: String): String? {
        val needle = ";$name="
        val i = msg.indexOf(needle)
        if (i < 0) return null
        val start = i + needle.length
        var end = start
        while (end < msg.length && msg[end] != ';') end++
        return msg.substring(start, end)
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].code xor b[i].code)
        return d == 0
    }

    private fun toHex(b: ByteArray): String {
        val h = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append(h[v ushr 4]); sb.append(h[v and 15])
        }
        return sb.toString()
    }

    fun sampleDelta(a: Long, b: Long): Long {
        val d = (a - b) and 0xFFFFFFFFL
        return if (d >= 0x80000000L) d - 0x100000000L else d
    }
}
