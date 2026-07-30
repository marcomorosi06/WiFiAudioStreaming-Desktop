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
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

sealed class DownloadResult {
    class Done(val file: File) : DownloadResult()
    class Failed(val reason: String) : DownloadResult()
    object Cancelled : DownloadResult()
}

data class DownloadProgress(
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Long
) {
    val fraction: Float get() = if (bytesTotal <= 0L) 0f else (bytesDone.toDouble() / bytesTotal).toFloat()
}

class CaptionDownloader(private val rootDir: File) {

    private val cancelFlag = AtomicBoolean(false)

    fun cancel() = cancelFlag.set(true)

    fun modelsDir(): File = File(rootDir, "models").apply { mkdirs() }

    fun runtimesDir(): File = File(rootDir, "runtimes").apply { mkdirs() }

    fun localFileFor(model: CaptionModel): File {
        val safe = model.id.replace(':', '_').replace('/', '_')
        val ext = if (model.url.endsWith(".tar.bz2")) ".tar.bz2" else ".bin"
        return File(modelsDir(), "$safe$ext")
    }

    fun localDirFor(runtime: NativeRuntime): File = File(runtimesDir(), runtime.id)

    fun isModelInstalled(model: CaptionModel): Boolean {
        val f = localFileFor(model)
        if (model.engine == AsrEngineKind.SHERPA_ONNX) {
            return File(modelsDir(), model.id.replace(':', '_')).isDirectory
        }
        return f.isFile && f.length() > 0
    }

    fun isRuntimeInstalled(runtime: NativeRuntime): Boolean {
        val d = localDirFor(runtime)
        if (!d.isDirectory) return false
        return runtime.libNames.any { findFile(d, it) != null }
    }

    fun removeModel(model: CaptionModel): Boolean {
        val f = localFileFor(model)
        val d = File(modelsDir(), model.id.replace(':', '_'))
        var ok = true
        if (f.exists()) ok = f.delete() && ok
        if (d.exists()) ok = deleteTree(d) && ok
        return ok
    }

    fun removeRuntime(runtime: NativeRuntime): Boolean {
        val d = localDirFor(runtime)
        return if (d.exists()) deleteTree(d) else true
    }

    fun installedBytes(): Long = sizeOf(modelsDir()) + sizeOf(runtimesDir())

    fun downloadModel(model: CaptionModel, onProgress: (DownloadProgress) -> Unit): DownloadResult {
        if (model.fileSha1 == null && model.archiveSha256 == null) {
            return DownloadResult.Failed(ERR_NO_PIN)
        }
        val target = localFileFor(model)
        val res = fetch(model.url, target, model.sizeBytes, onProgress)
        if (res !is DownloadResult.Done) return res

        model.fileSha1?.let {
            val actual = digestHex(target, "SHA-1")
            if (!actual.equals(it, ignoreCase = true)) {
                target.delete()
                return DownloadResult.Failed("$ERR_HASH expected=$it actual=$actual")
            }
        }
        model.archiveSha256?.let {
            val actual = digestHex(target, "SHA-256")
            if (!actual.equals(it, ignoreCase = true)) {
                target.delete()
                return DownloadResult.Failed("$ERR_HASH expected=$it actual=$actual")
            }
        }
        return DownloadResult.Done(target)
    }

    fun downloadRuntime(runtime: NativeRuntime, onProgress: (DownloadProgress) -> Unit): DownloadResult {
        if (runtime.sha256 == null) return DownloadResult.Failed(ERR_NO_PIN)
        if (!runtime.url.endsWith(".zip", ignoreCase = true)) {
            return DownloadResult.Failed(ERR_ARCHIVE)
        }

        val dir = localDirFor(runtime).apply { mkdirs() }
        val archive = File(dir, "archive.tmp")
        val res = fetch(runtime.url, archive, runtime.sizeBytes, onProgress)
        if (res !is DownloadResult.Done) return res

        val actual = digestHex(archive, "SHA-256")
        if (!actual.equals(runtime.sha256, ignoreCase = true)) {
            archive.delete()
            return DownloadResult.Failed("$ERR_HASH expected=${runtime.sha256} actual=$actual")
        }

        val extracted = runCatching { unzip(archive, dir) }
        archive.delete()

        extracted.exceptionOrNull()?.let {
            deleteTree(dir)
            return DownloadResult.Failed("$ERR_EXTRACT:${it.message}")
        }

        if (!isRuntimeInstalled(runtime)) {
            deleteTree(dir)
            return DownloadResult.Failed("$ERR_INCOMPLETE:${runtime.libNames.firstOrNull()}")
        }
        if (runtime.serverExecutable != null && findFile(dir, runtime.serverExecutable) == null) {
            deleteTree(dir)
            return DownloadResult.Failed("$ERR_INCOMPLETE:${runtime.serverExecutable}")
        }
        return DownloadResult.Done(dir)
    }

    private fun unzip(archive: File, target: File) {
        val root = target.canonicalFile
        java.util.zip.ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(root, entry.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator) && out.path != root.path) {
                    throw IOException("unsafe-entry:${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { sink ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun fetch(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult {
        cancelFlag.set(false)
        val part = File(target.parentFile, target.name + ".part")
        var existing = if (part.isFile) part.length() else 0L

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("User-Agent", "WFAS-Captions")
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                existing = 0L
                part.delete()
            } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                return DownloadResult.Failed("http=$code")
            }

            val declared = conn.getHeaderFieldLong("Content-Length", -1L)
            val total = when {
                declared > 0L -> declared + existing
                expectedSize > 0L -> expectedSize
                else -> -1L
            }

            conn.inputStream.use { input ->
                java.io.RandomAccessFile(part, "rw").use { out ->
                    out.seek(existing)
                    val buf = ByteArray(1 shl 16)
                    var done = existing
                    var lastTick = System.nanoTime()
                    var lastDone = done
                    while (true) {
                        if (cancelFlag.get()) return DownloadResult.Cancelled
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        val dt = now - lastTick
                        if (dt >= 250_000_000L) {
                            val bps = ((done - lastDone) * 1_000_000_000L) / dt
                            onProgress(DownloadProgress(done, total, bps))
                            lastTick = now
                            lastDone = done
                        }
                    }
                    onProgress(DownloadProgress(done, if (total > 0) total else done, 0L))
                }
            }
        } catch (e: IOException) {
            return DownloadResult.Failed("io=${e.message}")
        } finally {
            conn?.disconnect()
        }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) return DownloadResult.Failed("rename")
        return DownloadResult.Done(target)
    }

    private fun digestHex(f: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        f.inputStream().use { stream: InputStream ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val h = "0123456789abcdef"
        val sb = StringBuilder()
        for (b in md.digest()) {
            val v = b.toInt() and 0xFF
            sb.append(h[v ushr 4]); sb.append(h[v and 15])
        }
        return sb.toString()
    }

    private fun findFile(dir: File, name: String): File? {
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.add(c) else if (c.name == name) return c
            }
        }
        return null
    }

    private fun deleteTree(f: File): Boolean {
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        return f.delete()
    }

    private fun sizeOf(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (c in children) if (c.isDirectory) stack.add(c) else total += c.length()
        }
        return total
    }

    companion object {
        const val ERR_NO_PIN = "unpinned"
        const val ERR_HASH = "hash-mismatch"
        const val ERR_ARCHIVE = "archive-unsupported"
        const val ERR_EXTRACT = "extract-failed"
        const val ERR_INCOMPLETE = "runtime-incomplete"
    }
}
