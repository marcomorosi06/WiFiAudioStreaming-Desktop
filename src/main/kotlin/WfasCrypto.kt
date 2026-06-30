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

/**
 * WFAS v2 encryption (protocol Section 8): ChaCha20-Poly1305 AEAD per packet.
 * Byte-compatible with the C reference (wfas-protocol): same RFC 8439/5869 math,
 * same packet framing, same HKDF labels.
 *
 * Encrypted packet: [header 10B] [counter 8B big-endian] [ciphertext] [tag 16B].
 * nonce(12B) = noncePrefix(4B, per-direction) || counter(8B big-endian).
 */
object WfasCrypto {
    const val FLAG_ENCRYPTED = 0x02
    const val TAG_BYTES = 16
    const val COUNTER_BYTES = 8
    const val NONCE_PREFIX_BYTES = 4
    const val AEAD_OVERHEAD = COUNTER_BYTES + TAG_BYTES   // 24
    const val SALT_BYTES = 16
    const val HEADER_SIZE = 10
    const val MSG_MCAST_ENC = "WFAS_MCAST_ENC"
    private const val REPLAY_BITS = 1024

    class Dir(val key: ByteArray, val noncePrefix: ByteArray) { var sendCounter: Long = 0 }

    class Header(val version: Int, val flags: Int, val seq: Int, val samplePos: Long)

    sealed class Decrypted {
        class Ok(val header: Header, val counter: Long, val pcm: ByteArray) : Decrypted()
        object Replay : Decrypted()
        object AuthFail : Decrypted()
        object Malformed : Decrypted()
    }

    class BeaconInfo(val epoch: Long, val time: Long, val salt: ByteArray)

    // ── Primitives ──────────────────────────────────────────────────────────
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, len: Int): ByteArray {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(len)
        g.generateBytes(out, 0, len)
        return out
    }

    private fun aeadEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, pt: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce, aad))
        val out = ByteArray(c.getOutputSize(pt.size))
        var off = c.processBytes(pt, 0, pt.size, out, 0)
        off += c.doFinal(out, off)
        return out
    }

    private fun aeadDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ctTag: ByteArray): ByteArray? {
        return try {
            val c = ChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce, aad))
            val out = ByteArray(c.getOutputSize(ctTag.size))
            var off = c.processBytes(ctTag, 0, ctTag.size, out, 0)
            off += c.doFinal(out, off)
            if (off == out.size) out else out.copyOf(off)
        } catch (_: Exception) {
            null
        }
    }

    private fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    private fun toHex(b: ByteArray): String {
        val h = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xFF; sb.append(h[v ushr 4]); sb.append(h[v and 15]) }
        return sb.toString()
    }
    private fun fromHex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = ((hexv(s[i * 2]) shl 4) or hexv(s[i * 2 + 1])).toByte()
        return out
    }
    private fun hexv(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'; in 'a'..'f' -> c - 'a' + 10; in 'A'..'F' -> c - 'A' + 10; else -> 0
    }

    // ── Key derivation (labels identical to the C reference) ─────────────────
    fun deriveUnicast(key: String, cnonceHex: String, snonceHex: String): Pair<Dir, Dir> {
        val salt = (cnonceHex + snonceHex).toByteArray(Charsets.US_ASCII)
        val ikm = key.toByteArray(Charsets.UTF_8)
        val c2s = Dir(
            hkdf(salt, ikm, "WFAS c2s key".toByteArray(Charsets.US_ASCII), 32),
            hkdf(salt, ikm, "WFAS c2s iv".toByteArray(Charsets.US_ASCII), 4)
        )
        val s2c = Dir(
            hkdf(salt, ikm, "WFAS s2c key".toByteArray(Charsets.US_ASCII), 32),
            hkdf(salt, ikm, "WFAS s2c iv".toByteArray(Charsets.US_ASCII), 4)
        )
        return c2s to s2c
    }

    fun deriveMulticast(key: String, salt: ByteArray): Dir {
        val ikm = key.toByteArray(Charsets.UTF_8)
        return Dir(
            hkdf(salt, ikm, "WFAS mcast key".toByteArray(Charsets.US_ASCII), 32),
            hkdf(salt, ikm, "WFAS mcast iv".toByteArray(Charsets.US_ASCII), 4)
        )
    }

    // ── Packet encrypt / decrypt ─────────────────────────────────────────────
    fun encryptPacket(dir: Dir, seq: Int, samplePos: Long, silence: Boolean, pcm: ByteArray): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        header[0] = 0x57; header[1] = 0x46; header[2] = 2
        header[3] = (((if (silence) 0x01 else 0) or FLAG_ENCRYPTED)).toByte()
        header[4] = (seq ushr 8).toByte(); header[5] = seq.toByte()
        header[6] = (samplePos ushr 24).toByte(); header[7] = (samplePos ushr 16).toByte()
        header[8] = (samplePos ushr 8).toByte(); header[9] = samplePos.toByte()
        val counter = dir.sendCounter
        val cb = ByteArray(COUNTER_BYTES)
        for (i in 0 until 8) cb[i] = (counter ushr (56 - 8 * i)).toByte()
        val nonce = ByteArray(12)
        System.arraycopy(dir.noncePrefix, 0, nonce, 0, 4)
        System.arraycopy(cb, 0, nonce, 4, 8)
        val ctTag = aeadEncrypt(dir.key, nonce, header, pcm)
        dir.sendCounter = counter + 1
        return header + cb + ctTag
    }

    fun decryptPacket(dir: Dir, win: ReplayWindow, buf: ByteArray, len: Int): Decrypted {
        if (len < HEADER_SIZE + COUNTER_BYTES + TAG_BYTES) return Decrypted.Malformed
        if (buf[0].toInt() and 0xFF != 0x57 || buf[1].toInt() and 0xFF != 0x46) return Decrypted.Malformed
        if (buf[3].toInt() and FLAG_ENCRYPTED == 0) return Decrypted.Malformed
        var counter = 0L
        for (i in 0 until 8) counter = (counter shl 8) or (buf[HEADER_SIZE + i].toLong() and 0xFF)
        if (!win.check(counter)) return Decrypted.Replay
        val header = buf.copyOfRange(0, HEADER_SIZE)
        val nonce = ByteArray(12)
        System.arraycopy(dir.noncePrefix, 0, nonce, 0, 4)
        System.arraycopy(buf, HEADER_SIZE, nonce, 4, 8)
        val ctTag = buf.copyOfRange(HEADER_SIZE + COUNTER_BYTES, len)
        val pcm = aeadDecrypt(dir.key, nonce, header, ctTag) ?: return Decrypted.AuthFail
        win.commit(counter)
        val h = Header(
            buf[2].toInt() and 0xFF, buf[3].toInt() and 0xFF,
            ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF),
            ((buf[6].toLong() and 0xFF) shl 24) or ((buf[7].toLong() and 0xFF) shl 16) or
                ((buf[8].toLong() and 0xFF) shl 8) or (buf[9].toLong() and 0xFF)
        )
        return Decrypted.Ok(h, counter, pcm)
    }

    // ── Multicast beacon ──────────────────────────────────────────────────────
    private fun beaconFields(epoch: Long, time: Long, saltHex: String) =
        "epoch=$epoch;time=$time;salt=$saltHex"

    fun buildMcastBeacon(key: String, epoch: Long, time: Long, salt: ByteArray): String {
        val fields = beaconFields(epoch, time, toHex(salt))
        val mac = hmacSha256(key.toByteArray(Charsets.UTF_8), ("WFAS-MCAST:$fields").toByteArray(Charsets.UTF_8))
        return "$MSG_MCAST_ENC;$fields;mac=${toHex(mac)}"
    }

    fun parseMcastBeacon(key: String, msg: String, lastEpoch: Long): BeaconInfo? {
        if (!msg.startsWith(MSG_MCAST_ENC)) return null
        val ev = token(msg, "epoch") ?: return null
        val tv = token(msg, "time") ?: return null
        val sh = token(msg, "salt") ?: return null
        val mv = token(msg, "mac") ?: return null
        val fields = "epoch=$ev;time=$tv;salt=$sh"
        val mac = toHex(hmacSha256(key.toByteArray(Charsets.UTF_8), ("WFAS-MCAST:$fields").toByteArray(Charsets.UTF_8)))
        if (!constantTimeEquals(mac, mv)) return null
        val epoch = ev.toLongOrNull() ?: return null
        if (epoch <= lastEpoch) return null            // stale / replayed session
        if (sh.length % 2 != 0) return null
        return BeaconInfo(epoch, tv.toLongOrNull() ?: 0L, fromHex(sh))
    }

    private fun token(msg: String, name: String): String? {
        val needle = ";$name="
        val i = msg.indexOf(needle); if (i < 0) return null
        val start = i + needle.length
        var end = start
        while (end < msg.length && msg[end] != ';') end++
        return msg.substring(start, end)
    }
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var d = 0; for (i in a.indices) d = d or (a[i].code xor b[i].code); return d == 0
    }

    // ── Anti-replay sliding window ───────────────────────────────────────────
    class ReplayWindow {
        private var maxCounter = 0L
        private val seen = LongArray(REPLAY_BITS / 64)
        private var initialized = false

        fun check(c: Long): Boolean {
            if (!initialized) return true
            if (c > maxCounter) return true
            val off = maxCounter - c
            if (off >= REPLAY_BITS) return false
            return (seen[(off / 64).toInt()] ushr (off % 64).toInt()) and 1L == 0L
        }
        fun commit(c: Long) {
            if (!initialized) { initialized = true; maxCounter = c; seen[0] = 1L; return }
            if (c > maxCounter) { shift(c - maxCounter); maxCounter = c; seen[0] = seen[0] or 1L }
            else { val off = maxCounter - c; if (off < REPLAY_BITS) seen[(off / 64).toInt()] = seen[(off / 64).toInt()] or (1L shl (off % 64).toInt()) }
        }
        private fun shift(shiftBits: Long) {
            if (shiftBits <= 0) return
            if (shiftBits >= REPLAY_BITS) { seen.fill(0L); return }
            val ws = (shiftBits / 64).toInt(); val bs = (shiftBits % 64).toInt()
            for (i in seen.indices.reversed()) {
                var v = 0L; val src = i - ws
                if (src >= 0) { v = seen[src] shl bs; if (bs != 0 && src - 1 >= 0) v = v or (seen[src - 1] ushr (64 - bs)) }
                seen[i] = v
            }
        }
    }
}
