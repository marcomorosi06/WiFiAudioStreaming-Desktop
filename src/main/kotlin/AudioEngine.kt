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

    companion object {
        private var libraryLoaded = false
        private var loadError: String? = null

        fun loadLibrary(): Boolean {
            if (libraryLoaded) return true
            loadError?.let { return false }

            val osName = System.getProperty("os.name").lowercase()
            val osArch = System.getProperty("os.arch").lowercase()

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
}