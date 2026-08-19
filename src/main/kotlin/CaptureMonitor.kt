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

import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object CaptureMonitor {

    enum class Kind { MICROPHONE, SYSTEM_AUDIO }

    data class Session(
        val id: Long,
        val kind: Kind,
        val source: String,
        val peer: String,
        val startedAtMs: Long
    )

    private const val AUDIT_NAME = "capture-audit.log"
    private const val AUDIT_MAX_BYTES = 512_000L

    private val sessions = ConcurrentHashMap<Long, Session>()
    private val ids = AtomicLong(0)

    val active = MutableStateFlow<Set<Kind>>(emptySet())

    fun begin(kind: Kind, source: String, peer: String): Long {
        val id = ids.incrementAndGet()
        val session = Session(id, kind, source.ifBlank { "-" }, peer.ifBlank { "-" }, System.currentTimeMillis())
        sessions[id] = session
        publish()
        audit("START", session, null)
        return id
    }

    fun end(id: Long) {
        val session = sessions.remove(id) ?: return
        publish()
        audit("STOP", session, System.currentTimeMillis() - session.startedAtMs)
    }

    fun endKind(kind: Kind) {
        sessions.values.filter { it.kind == kind }.map { it.id }.forEach { end(it) }
    }

    fun clear() = sessions.keys.toList().forEach { end(it) }

    fun snapshot(): List<Session> = sessions.values.sortedBy { it.startedAtMs }

    fun isActive(): Boolean = sessions.isNotEmpty()

    fun has(kind: Kind): Boolean = sessions.values.any { it.kind == kind }

    fun summary(): String {
        val kinds = active.value
        if (kinds.isEmpty()) return Strings.get("capture_idle")
        val mic = kinds.contains(Kind.MICROPHONE)
        val sys = kinds.contains(Kind.SYSTEM_AUDIO)
        return when {
            mic && sys -> Strings.get("capture_both")
            mic        -> Strings.get("capture_mic")
            else       -> Strings.get("capture_system")
        }
    }

    fun detail(): String = snapshot().joinToString("\n") {
        val label = if (it.kind == Kind.MICROPHONE) Strings.get("capture_mic") else Strings.get("capture_system")
        "$label — ${it.source} → ${it.peer}"
    }

    private fun publish() {
        active.value = sessions.values.map { it.kind }.toSet()
    }

    fun auditFile(): File = File(ConfigPaths.configDir(), AUDIT_NAME)

    private fun audit(event: String, session: Session, durationMs: Long?) {
        runCatching {
            val file = auditFile()
            file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            if (file.length() > AUDIT_MAX_BYTES) {
                val keep = file.readLines().takeLast(1000)
                file.writeText(keep.joinToString("\n") + "\n")
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            val pid = ProcessHandle.current().pid()
            val duration = durationMs?.let { " duration=${it / 1000}s" } ?: ""
            file.appendText(
                "$stamp $event kind=${session.kind.name.lowercase()} " +
                    "source=\"${session.source}\" peer=\"${session.peer}\" pid=$pid$duration\n"
            )
        }
    }
}

object CaptureIcon {

    private val cache = HashMap<String, BufferedImage>()

    fun badge(base: BufferedImage?, kinds: Set<CaptureMonitor.Kind>): BufferedImage? {
        if (base == null) return null
        if (kinds.isEmpty()) return base
        val key = kinds.sortedBy { it.name }.joinToString(",") + "@" + base.width + "x" + base.height
        cache[key]?.let { return it }

        val out = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(base, 0, 0, null)

        val d = (base.width * 0.45f).toInt().coerceAtLeast(8)
        val x = base.width - d
        val y = base.height - d

        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f)
        g.color = Color(0, 0, 0, 140)
        g.fillOval(x - 2, y - 2, d + 4, d + 4)
        g.color = Color(220, 38, 38)
        g.fillOval(x, y, d, d)

        if (kinds.contains(CaptureMonitor.Kind.MICROPHONE)) {
            g.color = Color.WHITE
            val bw = (d * 0.24f).toInt().coerceAtLeast(2)
            val bh = (d * 0.42f).toInt().coerceAtLeast(3)
            g.fillRoundRect(x + (d - bw) / 2, y + (d - bh) / 2 - bh / 6, bw, bh, bw, bw)
            g.fillRect(x + d / 2 - bw / 4, y + (d + bh) / 2 - bh / 6, bw / 2, (d * 0.14f).toInt().coerceAtLeast(1))
        }
        g.dispose()
        cache[key] = out
        return out
    }
}
