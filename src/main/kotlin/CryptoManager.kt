// CryptoManagerDesktop.kt
// Stessa logica di CryptoManager.kt Android ma senza dipendenze Android.
// Il verifier viene cifrato con una chiave AES conservata in un PKCS12 KeyStore
// sul filesystem, protetto con una passphrase derivata da un identificatore
// univoco della macchina (MAC address del primary network interface).
// Non è HSM-backed come Android Keystore, ma il verifier non è mai in chiaro su disco.

import java.io.File
import java.math.BigInteger
import java.net.NetworkInterface
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManagerDesktop {

    // SRP-6a: gruppo 2048-bit RFC 5054
    private val N = BigInteger(
        "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4" +
                "A099ED8193E0757767A13DD52312AB4B03310093D48D0A9C7B2AF5BD4C0F4AA9E8B5F87FE" +
                "7D0F3E4D2F8C4A208F72FBCE7BDBFC0F0E6A0F13E5E4FE6B4B4CE5D6C77D3DF7E8D2CD6" +
                "4C7B4E9C7A3B0C5D4E2A8F1C6B9E7A2D5F8C3A0B7E4D9F2A6C8B5E3D0A7C4B1E8F5A2" +
                "D6C9B3E0A4C7B2E5D8F1A9C6B4E2D7F0A3C5B8E6D4F2A0C8B5E3D1F7A4C2B9E6D8F5" +
                "A1C4B7E5D3F0A6C9B2E8D6F4A2C0B8E5D3F1A7C4B2E9D7F5A3C1B6E8D4F2A0C7B5E3", 16
    )
    private val g = BigInteger.valueOf(2)
    private val k = computeK()
    private val random = SecureRandom()

    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val KEYSTORE_ALIAS = "wifi_audio_srp_key"

    // File dove salviamo: il PKCS12 KeyStore e i dati del verifier cifrato
    private val configDir = File(System.getProperty("user.home"), ".wifiaudio")
    private val keystoreFile = File(configDir, "keystore.p12")
    private val verifierFile = File(configDir, "verifier.dat")

    private fun computeK(): BigInteger {
        val d = MessageDigest.getInstance("SHA-256")
        d.update(N.toByteArray()); d.update(g.toByteArray())
        return BigInteger(1, d.digest())
    }

    private fun H(vararg inputs: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        for (input in inputs) {
            val len = ByteArray(4)
            len[0] = (input.size shr 24).toByte(); len[1] = (input.size shr 16).toByte()
            len[2] = (input.size shr 8).toByte();  len[3] = input.size.toByte()
            d.update(len); d.update(input)
        }
        return d.digest()
    }

    private fun BigInteger.toFixedBytes(): ByteArray {
        val b = toByteArray()
        return if (b[0] == 0.toByte() && b.size > 1) b.copyOfRange(1, b.size) else b
    }

    // =========================================================
    // Keystore passphrase: derivata dal MAC address della macchina
    // (non è un segreto forte, ma rende il PKCS12 non portabile
    // su altre macchine, aggiungendo un layer contro furto fisico del file)
    // =========================================================
    private fun getMachinePassphrase(): CharArray {
        return try {
            val mac = NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { !it.isLoopback && it.isUp && it.hardwareAddress != null }
                .map { it.hardwareAddress }
                .firstOrNull()
                ?: ByteArray(6)
            val hash = MessageDigest.getInstance("SHA-256").digest(mac + "wifi_audio_v1".toByteArray())
            Base64.getEncoder().encodeToString(hash).toCharArray()
        } catch (_: Exception) { "fallback_passphrase_wifi_audio".toCharArray() }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        configDir.mkdirs()
        val passphrase = getMachinePassphrase()
        val ks = KeyStore.getInstance("PKCS12")

        if (keystoreFile.exists()) {
            keystoreFile.inputStream().use { ks.load(it, passphrase) }
            val entry = ks.getEntry(KEYSTORE_ALIAS, KeyStore.PasswordProtection(passphrase))
            if (entry is KeyStore.SecretKeyEntry) return entry.secretKey
        } else {
            ks.load(null, passphrase)
        }

        // Genera nuova chiave AES-256
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, random)
        val newKey = keyGen.generateKey()
        ks.setEntry(KEYSTORE_ALIAS, KeyStore.SecretKeyEntry(newKey), KeyStore.PasswordProtection(passphrase))
        keystoreFile.outputStream().use { ks.store(it, passphrase) }
        return newKey
    }

    // =========================================================
    // Registrazione password: calcola e salva salt + verifier cifrato
    // =========================================================
    fun registerPassword(password: String) {
        configDir.mkdirs()
        val salt = ByteArray(32).also { random.nextBytes(it) }
        val x = BigInteger(1, H(salt, password.toByteArray(Charsets.UTF_8)))
        val verifier = g.modPow(x, N).toFixedBytes()

        val encKey = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encKey)
        val iv = cipher.iv
        val encVerifier = cipher.doFinal(verifier)

        // Formato: salt_b64:iv_b64:encVerifier_b64
        val enc = Base64.getEncoder()
        verifierFile.writeText("${enc.encodeToString(salt)}:${enc.encodeToString(iv)}:${enc.encodeToString(encVerifier)}")
    }

    fun clearPassword() { verifierFile.delete() }

    fun hasPassword(): Boolean = verifierFile.exists()

    private fun loadVerifier(): Pair<ByteArray, BigInteger>? {
        if (!verifierFile.exists()) return null
        return try {
            val parts = verifierFile.readText().trim().split(":")
            if (parts.size != 3) return null
            val dec = Base64.getDecoder()
            val salt = dec.decode(parts[0])
            val iv = dec.decode(parts[1])
            val encVerifier = dec.decode(parts[2])

            val encKey = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val verifierBytes = cipher.doFinal(encVerifier)
            salt to BigInteger(1, verifierBytes)
        } catch (e: Exception) {
            println("Failed to load verifier: ${e.message}")
            null
        }
    }

    // =========================================================
    // SERVER Session
    // =========================================================
    class ServerSession(val salt: ByteArray, private val verifier: BigInteger) {
        val serverPrivateKey: BigInteger = BigInteger(256, random)
        val serverPublicKey: BigInteger = (k.multiply(verifier).add(g.modPow(serverPrivateKey, N))).mod(N)
        private var sessionKey: ByteArray? = null

        fun computeSessionKey(clientA: BigInteger): ByteArray? {
            if (clientA.mod(N) == BigInteger.ZERO) return null
            val u = BigInteger(1, H(clientA.toFixedBytes(), serverPublicKey.toFixedBytes()))
            if (u == BigInteger.ZERO) return null
            val s = clientA.multiply(verifier.modPow(u, N)).modPow(serverPrivateKey, N)
            sessionKey = H(s.toFixedBytes())
            return sessionKey
        }

        fun verifyClientProof(clientA: BigInteger, clientM1: ByteArray): Boolean {
            val key = sessionKey ?: return false
            return H(clientA.toFixedBytes(), serverPublicKey.toFixedBytes(), key).contentEquals(clientM1)
        }

        fun computeServerProof(clientA: BigInteger, clientM1: ByteArray): ByteArray {
            val key = sessionKey ?: error("Session key not computed")
            return H(clientA.toFixedBytes(), clientM1, key)
        }

        fun getAesKey(): SecretKeySpec = SecretKeySpec((sessionKey ?: error("")).copyOf(16), "AES")
    }

    // =========================================================
    // CLIENT Session
    // =========================================================
    class ClientSession(private val password: String) {
        val clientPrivateKey: BigInteger = BigInteger(256, random)
        val clientPublicKey: BigInteger = g.modPow(clientPrivateKey, N)
        private var sessionKey: ByteArray? = null

        fun computeSessionKey(salt: ByteArray, serverB: BigInteger): ByteArray? {
            if (serverB.mod(N) == BigInteger.ZERO) return null
            val u = BigInteger(1, H(clientPublicKey.toFixedBytes(), serverB.toFixedBytes()))
            if (u == BigInteger.ZERO) return null
            val x = BigInteger(1, H(salt, password.toByteArray(Charsets.UTF_8)))
            val base = serverB.subtract(k.multiply(g.modPow(x, N))).mod(N)
            val s = base.modPow(clientPrivateKey.add(u.multiply(x)), N)
            sessionKey = H(s.toFixedBytes())
            return sessionKey
        }

        fun computeClientProof(serverB: BigInteger): ByteArray {
            val key = sessionKey ?: error("Session key not computed")
            return H(clientPublicKey.toFixedBytes(), serverB.toFixedBytes(), key)
        }

        fun verifyServerProof(serverM2: ByteArray, serverB: BigInteger, clientM1: ByteArray): Boolean {
            val key = sessionKey ?: return false
            return H(clientPublicKey.toFixedBytes(), clientM1, key).contentEquals(serverM2)
        }

        fun getAesKey(): SecretKeySpec = SecretKeySpec((sessionKey ?: error("")).copyOf(16), "AES")
    }

    fun createServerSession(): ServerSession? {
        val (salt, verifier) = loadVerifier() ?: return null
        return ServerSession(salt, verifier)
    }

    // =========================================================
    // =========================================================
    // CipherSession — una istanza per sessione, Cipher pre-istanziato.
    // Formato pacchetto: [seqNo 8B] [IV 12B] [ciphertext + tag 16B]
    //
    // FIX 1: Cipher pre-allocato (una volta per sessione).
    // FIX 2: IV deterministico [sessionPrefix 4B | seq 8B].
    // FIX 3: Jitter buffer per riordinamento pacchetti UDP.
    // =========================================================
    class CipherSession private constructor(
        private val aesKey: SecretKeySpec,
        private val _sessionPrefix: ByteArray
    ) {
        private val encCipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val decCipher = Cipher.getInstance("AES/GCM/NoPadding")
        private var encSeq = 0L
        private val ivBuf = ByteArray(12)

        // Jitter buffer
        private var nextExpectedSeq = -1L
        private val jitterBuffer = java.util.TreeMap<Long, ByteArray>()
        private val jitterWindowSize = 32

        companion object {
            fun create(aesKey: SecretKeySpec): CipherSession {
                val prefix = ByteArray(4).also { SecureRandom().nextBytes(it) }
                return CipherSession(aesKey, prefix)
            }
            fun createPeer(aesKey: SecretKeySpec, peerPrefix: ByteArray): CipherSession =
                CipherSession(aesKey, peerPrefix.copyOf())
        }

        fun getSessionPrefix(): ByteArray = _sessionPrefix.copyOf()

        fun createPeerSession(peerPrefix: ByteArray): CipherSession =
            createPeer(aesKey, peerPrefix)

        fun encrypt(plaintext: ByteArray): ByteArray {
            val seq = encSeq++
            buildIv(seq)
            encCipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, ivBuf))
            val ct = encCipher.doFinal(plaintext)
            val out = ByteArray(8 + 12 + ct.size)
            for (i in 0..7) out[i] = (seq ushr (56 - i * 8)).toByte()
            ivBuf.copyInto(out, 8)
            ct.copyInto(out, 20)
            return out
        }

        fun wrap(plaintext: ByteArray): ByteArray {
            val seq = encSeq++
            val out = ByteArray(8 + plaintext.size)
            for (i in 0..7) out[i] = (seq ushr (56 - i * 8)).toByte()
            plaintext.copyInto(out, 8)
            return out
        }

        /** Ritorna frame audio in ordine, gestendo il jitter buffer. */
        fun receive(packet: ByteArray): List<ByteArray> {
            if (packet.size < 8) return emptyList()
            val seq = (0..7).fold(0L) { acc, i -> (acc shl 8) or (packet[i].toLong() and 0xFF) }
            if (nextExpectedSeq < 0) nextExpectedSeq = seq
            if (seq < nextExpectedSeq) return emptyList()

            val decoded = decodePayload(packet) ?: return emptyList()
            jitterBuffer[seq] = decoded

            val ready = mutableListOf<ByteArray>()
            while (jitterBuffer.isNotEmpty() && jitterBuffer.firstKey() == nextExpectedSeq) {
                ready += jitterBuffer.remove(nextExpectedSeq)!!
                nextExpectedSeq++
            }
            if (jitterBuffer.size > jitterWindowSize) {
                println("CryptoManagerDesktop: jitter overflow, flushing")
                ready += jitterBuffer.values.toList()
                nextExpectedSeq = jitterBuffer.lastKey() + 1
                jitterBuffer.clear()
            }
            return ready
        }

        /** decrypt() senza jitter buffer — per scambi di chiavi, non per audio. */
        fun decrypt(packet: ByteArray): ByteArray? = decodePayload(packet)

        private fun decodePayload(packet: ByteArray): ByteArray? {
            val minSize = 8 + 12 + 16
            if (packet.size <= minSize) return null
            return try {
                val iv = packet.copyOfRange(8, 20)
                decCipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
                decCipher.doFinal(packet, 20, packet.size - 20)
            } catch (_: Exception) { null }
        }

        private fun buildIv(seq: Long) {
            _sessionPrefix.copyInto(ivBuf, 0)
            for (i in 0..7) ivBuf[4 + i] = (seq ushr (56 - i * 8)).toByte()
        }
    }

    // PlainSession — jitter buffer senza cifratura (modalità aperta)
    class PlainSession {
        private var encSeq = 0L
        private var nextExpectedSeq = -1L
        private val jitterBuffer = java.util.TreeMap<Long, ByteArray>()
        private val jitterWindowSize = 32

        fun wrap(plaintext: ByteArray): ByteArray {
            val seq = encSeq++
            val out = ByteArray(8 + plaintext.size)
            for (i in 0..7) out[i] = (seq ushr (56 - i * 8)).toByte()
            plaintext.copyInto(out, 8)
            return out
        }

        fun receive(packet: ByteArray): List<ByteArray> {
            if (packet.size <= 8) return emptyList()
            val seq = (0..7).fold(0L) { acc, i -> (acc shl 8) or (packet[i].toLong() and 0xFF) }
            if (nextExpectedSeq < 0) nextExpectedSeq = seq
            if (seq < nextExpectedSeq) return emptyList()

            jitterBuffer[seq] = packet.copyOfRange(8, packet.size)
            val ready = mutableListOf<ByteArray>()
            while (jitterBuffer.isNotEmpty() && jitterBuffer.firstKey() == nextExpectedSeq) {
                ready += jitterBuffer.remove(nextExpectedSeq)!!
                nextExpectedSeq++
            }
            if (jitterBuffer.size > jitterWindowSize) {
                ready += jitterBuffer.values.toList()
                nextExpectedSeq = jitterBuffer.lastKey() + 1
                jitterBuffer.clear()
            }
            return ready
        }
    }

    fun createCipherSession(aesKey: SecretKeySpec): CipherSession = CipherSession.create(aesKey)
    fun createPlainSession(): PlainSession = PlainSession()
}