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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class AudioEngine(
    val sampleRate: Int   = 48000,
    val channels:   Int   = 2,
    val bufferFrames: Int = 3200
) {
    var lastError: String = ""
        private set

    private var started = false

    private val numSamplesPerRead = bufferFrames * channels
    private val readBufShort      = ShortArray(numSamplesPerRead)

    private external fun nativeStart(sampleRate: Int, channels: Int, bufferFrames: Int): Boolean
    private external fun nativeRead(outBuf: ShortArray, numSamples: Int): Int
    private external fun nativeStop()
    private external fun nativeGetError(): String

    private external fun nativeMicSetMixEnabled(enabled: Boolean): Boolean
    private external fun nativeMicSetVolume(volume: Float)
    private external fun nativeMicPushPcm(pcm: ShortArray, numSamples: Int): Int

    private external fun nativeVirtualSinkCreate(sampleRate: Int, channels: Int): Boolean
    private external fun nativeVirtualSinkDestroy()
    private external fun nativeVirtualSinkWrite(pcm: ShortArray, numSamples: Int): Int
    private external fun nativeVirtualSinkName(): String

    private external fun nativeMicSinkOpen(deviceName: String?, sampleRate: Int, channels: Int): Boolean
    private external fun nativeMicSinkWrite(pcm: ShortArray, numSamples: Int): Int
    private external fun nativeMicSinkClose()
    private external fun nativeMicSinkDeviceName(): String

    companion object {
        private var libraryLoaded = false
        private var loadError: String? = null

        fun loadLibrary(): Boolean {
            if (libraryLoaded) return true
            loadError?.let { return false }

            val osName = System.getProperty("os.name").lowercase()
            val osArch = System.getProperty("os.arch").lowercase()

            if (!osName.contains("win") && !osName.contains("mac")) {
                loadError = "Motore audio nativo disabilitato su Linux: viene usato il backend FFmpeg."
                println("[AudioEngine] $loadError")
                return false
            }

            val osDir = when {
                osName.contains("win")  -> "windows"
                osName.contains("mac")  -> "macos"
                else                    -> "linux"
            }
            val archDir = when {
                osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
                else -> "x86_64"
            }

            val libName = when {
                osName.contains("win")  -> "audio_engine.dll"
                osName.contains("mac")  -> "libaudio_engine.dylib"
                else                    -> "libaudio_engine.so"
            }

            val resourcePath = "/native/$osDir/$archDir/$libName"

            return try {
                val stream: InputStream = AudioEngine::class.java
                    .getResourceAsStream(resourcePath)
                    ?: throw UnsatisfiedLinkError(
                        "Libreria nativa non trovata nel classpath: $resourcePath. " +
                                "Esegui 'gradle copyNativeLib' prima di avviare l'app."
                    )

                val extension = libName.substringAfterLast('.', "tmp")
                val tempFile = File.createTempFile("audio_engine_", ".$extension").apply {
                    deleteOnExit()
                }
                stream.use { input -> tempFile.outputStream().use { out -> input.copyTo(out) } }

                System.load(tempFile.absolutePath)
                libraryLoaded = true
                println("[AudioEngine] Libreria caricata da $resourcePath")
                true
            } catch (e: UnsatisfiedLinkError) {
                loadError = e.message ?: "UnsatisfiedLinkError senza messaggio"
                System.err.println("[AudioEngine] Impossibile caricare la libreria: $loadError")
                false
            } catch (e: Exception) {
                loadError = e.message ?: e.toString()
                System.err.println("[AudioEngine] Errore caricamento libreria: $loadError")
                false
            }
        }

        fun getLoadError(): String? = loadError
    }

    fun start(): Boolean {
        if (!libraryLoaded) {
            lastError = loadError ?: "Libreria nativa non caricata. Chiama AudioEngine.loadLibrary() prima."
            return false
        }
        if (started) return true

        val ok = nativeStart(sampleRate, channels, bufferFrames)
        if (!ok) {
            lastError = nativeGetError().ifEmpty { "Errore sconosciuto in nativeStart" }
            return false
        }
        started = true
        lastError = ""
        return true
    }

    fun stop() {
        if (!started) return
        started = false
        if (libraryLoaded) nativeStop()
    }

    suspend fun readFrame(): ShortArray? = withContext(Dispatchers.IO) {
        if (!started) {
            lastError = "AudioEngine non avviato"
            return@withContext null
        }

        val written = nativeRead(readBufShort, numSamplesPerRead)

        if (written < 0) {
            lastError = nativeGetError().ifEmpty { "Errore in nativeRead" }
            return@withContext null
        }

        if (written == 0) {
            delay(2)
            return@withContext ShortArray(0)
        }

        readBufShort.copyOf(written)
    }

    val isStarted: Boolean get() = started

    fun setMicMixEnabled(enabled: Boolean): Boolean {
        if (!libraryLoaded) return false
        return nativeMicSetMixEnabled(enabled)
    }

    fun setMicMixVolume(volume: Float) {
        if (!libraryLoaded) return
        nativeMicSetVolume(volume)
    }

    fun pushMicPcm(pcm: ShortArray, numSamples: Int): Int {
        if (!libraryLoaded) return 0
        if (numSamples <= 0) return 0
        val n = if (numSamples > pcm.size) pcm.size else numSamples
        return nativeMicPushPcm(pcm, n)
    }

    fun createVirtualSink(sampleRate: Int = this.sampleRate, channels: Int = this.channels): Boolean {
        if (!libraryLoaded) {
            lastError = loadError ?: "Libreria nativa non caricata."
            return false
        }
        val ok = nativeVirtualSinkCreate(sampleRate, channels)
        if (!ok) lastError = nativeGetError().ifEmpty { "Creazione virtual sink fallita." }
        return ok
    }

    fun destroyVirtualSink() {
        if (!libraryLoaded) return
        nativeVirtualSinkDestroy()
    }

    fun writeToVirtualSink(pcm: ShortArray, numSamples: Int): Int {
        if (!libraryLoaded) return -1
        if (numSamples <= 0) return 0
        val n = if (numSamples > pcm.size) pcm.size else numSamples
        return nativeVirtualSinkWrite(pcm, n)
    }

    fun virtualSinkName(): String {
        if (!libraryLoaded) return ""
        return nativeVirtualSinkName()
    }

    fun micSinkOpen(deviceName: String?, sampleRate: Int = this.sampleRate, channels: Int = this.channels): Boolean {
        if (!libraryLoaded) {
            lastError = loadError ?: "Libreria nativa non caricata."
            return false
        }
        val ok = nativeMicSinkOpen(deviceName, sampleRate, channels)
        if (!ok) lastError = nativeGetError().ifEmpty { "Apertura MicSink fallita." }
        return ok
    }

    fun micSinkWrite(pcm: ShortArray, numSamples: Int): Int {
        if (!libraryLoaded) return -1
        if (numSamples <= 0) return 0
        val n = if (numSamples > pcm.size) pcm.size else numSamples
        return nativeMicSinkWrite(pcm, n)
    }

    fun micSinkClose() {
        if (!libraryLoaded) return
        nativeMicSinkClose()
    }

    fun micSinkDeviceName(): String {
        if (!libraryLoaded) return ""
        return nativeMicSinkDeviceName()
    }
}

enum class MicRoutingMode {
    OFF,
    VIRTUAL_MIC,
    MIX_INTO_STREAM;

    companion object {
        fun fromStringSafe(s: String?): MicRoutingMode =
            runCatching { valueOf(s ?: "OFF") }.getOrDefault(OFF)
    }
}

object VirtualMicAutodetect {
    data class Detection(
        val mixerInfo: javax.sound.sampled.Mixer.Info?,
        val displayName: String,
        val nativeManaged: Boolean
    )

    private val WIN_MAC_KEYWORDS = arrayOf(
        "CABLE Input", "CABLE-A Input", "CABLE-B Input",
        "VB-Audio", "VoiceMeeter Input",
        "BlackHole", "Loopback", "Soundflower"
    )

    fun isLinux(): Boolean = System.getProperty("os.name").lowercase().contains("linux")
    fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
    fun isMac(): Boolean = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }

    fun detectManualCable(outputMixers: List<javax.sound.sampled.Mixer.Info>): Detection? {
        for (mixer in outputMixers) {
            val fullName = (mixer.name + " " + mixer.description).lowercase()
            for (kw in WIN_MAC_KEYWORDS) {
                if (fullName.contains(kw.lowercase())) {
                    return Detection(mixer, mixer.name, nativeManaged = false)
                }
            }
        }
        return null
    }
}
