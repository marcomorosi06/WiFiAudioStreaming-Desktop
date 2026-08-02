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

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.HybridBinarizer
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean

private class IntensitySource(
    private val width: Int,
    private val height: Int,
    private val luma: ByteArray
) : LuminanceSource(width, height) {

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val out = if (row != null && row.size >= width) row else ByteArray(width)
        System.arraycopy(luma, y * width, out, 0, width)
        return out
    }

    override fun getMatrix(): ByteArray = luma

    companion object {
        fun from(image: BufferedImage): IntensitySource {
            val w = image.width
            val h = image.height
            val luma = ByteArray(w * h)
            var i = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val rgb = image.getRGB(x, y)
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF
                    luma[i++] = (((r * 306) + (g * 601) + (b * 117)) shr 10).toByte()
                }
            }
            return IntensitySource(w, h, luma)
        }
    }
}

object QrDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    @Synchronized
    fun decode(image: BufferedImage): String? = runCatching {
        val bitmap = BinaryBitmap(HybridBinarizer(IntensitySource.from(image)))
        reader.decodeWithState(bitmap)?.text
    }.getOrNull().also { reader.reset() }
}

/**
 * Cattura webcam via FFmpeg. Isolata di proposito: la CLI e le build headless
 * non la toccano mai, quindi non aprono mai un dispositivo video.
 */
object WebcamSource {

    private val os = System.getProperty("os.name").lowercase()
    private val isWindows = os.contains("win")
    private val isMac = os.contains("mac") || os.contains("darwin")

    fun probablyAvailable(): Boolean = runCatching {
        when {
            isMac -> true
            isWindows -> true
            else -> java.io.File("/dev/video0").exists() ||
                    java.io.File("/dev/video1").exists()
        }
    }.getOrDefault(false)

    private fun openGrabber(): org.bytedeco.javacv.FFmpegFrameGrabber? {
        val candidates: List<Pair<String, String>> = when {
            isWindows -> listOf("dshow" to "video=Integrated Camera", "dshow" to "video=USB Camera")
            isMac -> listOf("avfoundation" to "0", "avfoundation" to "1")
            else -> listOf("video4linux2" to "/dev/video0", "video4linux2" to "/dev/video1")
        }
        for ((format, device) in candidates) {
            val grabber = runCatching {
                org.bytedeco.javacv.FFmpegFrameGrabber(device).apply {
                    setFormat(format)
                    setOption("probesize", "512")
                    setOption("analyzeduration", "0")
                    imageWidth = 1280
                    imageHeight = 720
                    start()
                }
            }.getOrNull()
            if (grabber != null) return grabber
        }
        return null
    }

    /**
     * Legge frame finche' [running] resta true o finche' [onFrame] non chiede di
     * fermarsi restituendo false. Bloccante: va chiamata da un thread dedicato.
     * Restituisce false se non e' stato possibile aprire nessun dispositivo.
     */
    fun capture(
        running: AtomicBoolean,
        onFrame: (BufferedImage) -> Boolean
    ): Boolean {
        val grabber = openGrabber() ?: return false
        val converter = org.bytedeco.javacv.Java2DFrameConverter()
        try {
            while (running.get()) {
                val frame = runCatching { grabber.grabImage() }.getOrNull() ?: continue
                val image = runCatching { converter.convert(frame) }.getOrNull() ?: continue
                if (!onFrame(image)) break
            }
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
            runCatching { converter.close() }
        }
        return true
    }
}
