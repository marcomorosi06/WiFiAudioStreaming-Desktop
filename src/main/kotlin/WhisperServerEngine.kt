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

import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WhisperServerEngine(
    private val runtimeDir: File,
    private val serverExecutable: String,
    private val modelFile: File,
    private val backend: AsrBackend,
    private val language: String,
    private val inputRate: Int,
    private val inputChannels: Int,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
    private val partialIntervalMs: Long = 1500L,
    private val maxUtteranceMs: Int = 12000,
    private val onResult: (AsrResult) -> Unit,
    private val onFailure: (String) -> Unit
) : AsrEngine {

    private val started = AtomicBoolean(false)
    private var process: Process? = null
    private var port: Int = 0
    private var worker: Thread? = null
    private var drainOut: Thread? = null
    private var drainErr: Thread? = null

    private val downsampler = MonoDownsampler(inputRate, inputChannels)
    private val vad = EnergyVad()

    private val processLog = ArrayDeque<String>()

    private fun recordLine(line: String) {
        synchronized(processLog) {
            processLog.addLast(line)
            while (processLog.size > 20) processLog.removeFirst()
        }
    }

    fun processOutput(): String = synchronized(processLog) { processLog.joinToString(" | ") }

    private val queue = ArrayBlockingQueue<Chunk>(64)
    private var shutdownHook: Thread? = null

    private class Chunk(val pcm: ShortArray, val frames: Int, val samplePos: Long)

    override val isRunning: Boolean get() = started.get() && process?.isAlive == true

    override fun start(): AsrStartResult {
        if (started.get()) return AsrStartResult.Ok

        val exe = File(runtimeDir, serverExecutable).takeIf { it.isFile }
            ?: findRecursive(runtimeDir, serverExecutable)
            ?: return AsrStartResult.Failed("missing-executable:$serverExecutable")

        if (!modelFile.isFile) return AsrStartResult.Failed("missing-model")

        port = try {
            ServerSocket(0).use { it.localPort }
        } catch (e: Exception) {
            return AsrStartResult.Failed("no-port:${e.message}")
        }

        val cmd = mutableListOf(
            exe.absolutePath,
            "--model", modelFile.absolutePath,
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "--threads", threads.toString(),
            "--language", language,
            "--no-timestamps"
        )
        if (backend == AsrBackend.CPU) cmd.add("--no-gpu")

        val builder = ProcessBuilder(cmd)
            .directory(exe.parentFile)
            .redirectErrorStream(false)

        val libraryDirs = collectLibraryDirs(runtimeDir, exe.parentFile)
        if (libraryDirs.isNotEmpty()) {
            val env = builder.environment()
            val key = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
            val existing = env[key].orEmpty()
            env[key] = (libraryDirs + existing).filter { it.isNotBlank() }
                .joinToString(File.pathSeparator)
            AppDebug.log("[ASR] library path: ${libraryDirs.joinToString(File.pathSeparator)}")
        }

        val proc = try {
            builder.start()
        } catch (e: Exception) {
            return AsrStartResult.Failed("spawn:${e.message}")
        }
        process = proc

        drainOut = drain(proc.inputStream, "out")
        drainErr = drain(proc.errorStream, "err")

        if (!awaitReady(proc, 60_000L)) {
            val code = if (proc.isAlive) null else runCatching { proc.exitValue() }.getOrNull()
            val tail = processOutput()
            stop()
            val explained = code?.let { describeExit(it) } ?: "still running but never opened the port"
            return AsrStartResult.Failed(
                "server-never-ready ($explained)" + if (tail.isNotEmpty()) " :: $tail" else ""
            )
        }

        started.set(true)
        worker = Thread({ workerLoop() }, "wfas-asr-worker").apply {
            isDaemon = true
            start()
        }
        shutdownHook = Thread { runCatching { proc.destroyForcibly() } }.also {
            runCatching { Runtime.getRuntime().addShutdownHook(it) }
        }
        AppDebug.log("[ASR] whisper-server ready on 127.0.0.1:$port backend=$backend model=${modelFile.name}")
        return AsrStartResult.Ok
    }

    override fun stop() {
        if (!started.getAndSet(false)) {
            runCatching { process?.destroyForcibly() }
            process = null
            return
        }
        queue.clear()
        worker?.interrupt()
        worker = null
        val p = process
        process = null
        runCatching { p?.destroy() }
        if (p != null && !p.waitFor(3, TimeUnit.SECONDS)) runCatching { p.destroyForcibly() }
        drainOut?.interrupt(); drainOut = null
        drainErr?.interrupt(); drainErr = null
        shutdownHook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
        shutdownHook = null
        downsampler.reset()
        vad.reset()
        AppDebug.log("[ASR] stopped")
    }

    override fun feed(pcm: ShortArray, frames: Int, samplePos: Long) {
        if (!started.get()) return
        val copy = pcm.copyOf(frames * inputChannels)
        if (!queue.offer(Chunk(copy, frames, samplePos))) {
            queue.poll()
            queue.offer(Chunk(copy, frames, samplePos))
        }
    }

    override fun flush() {
        if (started.get()) queue.offer(Chunk(ShortArray(0), 0, -1L))
    }

    private fun drain(stream: java.io.InputStream, tag: String): Thread =
        Thread({
            runCatching {
                stream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (Thread.currentThread().isInterrupted) break
                        if (line.isNotBlank()) {
                            AppDebug.log("[ASR][$tag] $line")
                            recordLine(line.trim())
                        }
                    }
                }
            }
        }, "wfas-asr-$tag").apply {
            isDaemon = true
            start()
        }

    private fun awaitReady(proc: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) return false
            val open = runCatching {
                Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
                    true
                }
            }.getOrDefault(false)
            if (open) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun workerLoop() {
        val buffer = FloatArray(ASR_SAMPLE_RATE * (maxUtteranceMs / 1000 + 4))
        var length = 0
        var utteranceStart = -1L
        var lastPartial = 0L
        val scratch = ArrayList<Float>(4096)

        while (started.get() && !Thread.currentThread().isInterrupted) {
            val chunk = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            }

            if (chunk != null && chunk.frames > 0) {
                scratch.clear()
                downsampler.process(chunk.pcm, chunk.frames, scratch)
                if (scratch.isNotEmpty()) {
                    if (utteranceStart < 0L) utteranceStart = chunk.samplePos
                    val room = buffer.size - length
                    val take = minOf(room, scratch.size)
                    for (i in 0 until take) buffer[length + i] = scratch[i]
                    val previousLength = length
                    length += take
                    val closed = vad.feed(buffer, previousLength, length)
                    val elapsedMs = length * 1000L / ASR_SAMPLE_RATE

                    if (closed || elapsedMs >= maxUtteranceMs || length >= buffer.size) {
                        emitTranscription(buffer, length, utteranceStart, elapsedMs.toInt(), true)
                        length = 0
                        utteranceStart = -1L
                        lastPartial = 0L
                        continue
                    }

                    val now = System.currentTimeMillis()
                    if (vad.isSpeaking && elapsedMs >= 1000 && now - lastPartial >= partialIntervalMs) {
                        lastPartial = now
                        emitTranscription(buffer, length, utteranceStart, elapsedMs.toInt(), false)
                    }
                }
            } else if (chunk != null && chunk.samplePos == -1L && length > 0) {
                val elapsedMs = (length * 1000L / ASR_SAMPLE_RATE).toInt()
                emitTranscription(buffer, length, utteranceStart, elapsedMs, true)
                length = 0
                utteranceStart = -1L
                lastPartial = 0L
            }
        }
    }

    private fun emitTranscription(
        buffer: FloatArray,
        length: Int,
        samplePos: Long,
        durMs: Int,
        isFinal: Boolean
    ) {
        if (length <= 0 || samplePos < 0L) return
        val wav = WavWriter.mono16(buffer, length, ASR_SAMPLE_RATE)
        val text = transcribe(wav) ?: return
        if (AsrTextUtil.isBlank(text)) return
        onResult(AsrResult(text, samplePos, durMs.coerceIn(0, 0xFFFF), isFinal))
    }

    fun transcribe(wav: ByteArray): String? {
        val boundary = "----wfas" + java.lang.Long.toHexString(System.nanoTime())
        val conn = try {
            (URL("http://127.0.0.1:$port/inference").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5000
                readTimeout = 60000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
        } catch (e: Exception) {
            onFailure("connect:${e.message}")
            return null
        }

        return try {
            conn.outputStream.use { os ->
                writePart(os, boundary, "temperature", "0.0")
                writePart(os, boundary, "temperature_inc", "0.2")
                writePart(os, boundary, "response_format", "json")
                writeFilePart(os, boundary, "file", "chunk.wav", wav)
                os.write("--$boundary--\r\n".toByteArray(Charsets.US_ASCII))
                os.flush()
            }
            val code = conn.responseCode
            if (code != 200) {
                onFailure("http:$code")
                null
            } else {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                JsonText.extractText(body)
            }
        } catch (e: Exception) {
            onFailure("inference:${e.message}")
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun writePart(os: OutputStream, boundary: String, name: String, value: String) {
        val header = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n"
        os.write(header.toByteArray(Charsets.UTF_8))
        os.write(value.toByteArray(Charsets.UTF_8))
        os.write("\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun writeFilePart(
        os: OutputStream,
        boundary: String,
        name: String,
        fileName: String,
        data: ByteArray
    ) {
        val header = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; " +
            "filename=\"$fileName\"\r\nContent-Type: audio/wav\r\n\r\n"
        os.write(header.toByteArray(Charsets.UTF_8))
        os.write(data)
        os.write("\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun collectLibraryDirs(root: File, preferred: File?): List<String> {
        val dirs = LinkedHashSet<String>()
        preferred?.let { dirs.add(it.absolutePath) }
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            var hasLibrary = false
            for (c in children) {
                if (c.isDirectory) stack.add(c)
                else if (c.name.endsWith(".dll", ignoreCase = true)) hasLibrary = true
            }
            if (hasLibrary) dirs.add(d.absolutePath)
        }
        return dirs.toList()
    }

    private fun describeExit(code: Int): String = when (code) {
        -1073741515 -> "exit=0xC0000135 a required DLL was not found next to whisper-server.exe " +
            "(most often the Microsoft Visual C++ Redistributable x64, or CUDA runtime DLLs missing from the archive)"
        -1073741701 -> "exit=0xC000007B the executable and this system have mismatched architectures"
        -1073741502 -> "exit=0xC0000142 a DLL failed to initialise"
        -1073741819 -> "exit=0xC0000005 access violation while starting"
        else -> "exit=$code"
    }

    private fun findRecursive(dir: File, name: String): File? {
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.add(c) else if (c.name.equals(name, ignoreCase = true)) return c
            }
        }
        return null
    }
}
