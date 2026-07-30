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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CaptionSender(
    private val scope: CoroutineScope,
    private val port: Int,
    private val clientAddress: InetAddress,
    private val authKey: String,
    private val encrypting: Boolean,
    private val cnonceHex: String,
    private val snonceHex: String,
    private val languageTag: String,
    private val requireProof: Boolean,
    private val availability: () -> String?
) {

    private var socket: DatagramSocket? = null
    private var loopJob: Job? = null

    private val running = AtomicBoolean(false)
    private val streaming = AtomicBoolean(false)

    private var peer: InetSocketAddress? = null
    private var dir: WfasCrypto.Dir? = null

    private val capIdSeq = AtomicLong(1L)
    private var openCapId: Long = -1L
    private var openRev: Int = -1

    private val sendLock = Any()

    val isStreaming: Boolean get() = streaming.get()

    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        return try {
            socket = DatagramSocket(port).apply { soTimeout = 500 }
            loopJob = scope.launch(Dispatchers.IO) { receiveLoop() }
            AppDebug.log("[CAP][SERVER] listening on $port")
            true
        } catch (e: Exception) {
            AppDebug.log("[CAP][SERVER] bind failed on $port: ${e.message}")
            running.set(false)
            socket = null
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        streaming.set(false)
        peer = null
        dir = null
        loopJob?.cancel()
        loopJob = null
        runCatching { socket?.close() }
        socket = null
        AppDebug.log("[CAP][SERVER] stopped")
    }

    private suspend fun receiveLoop() {
        val buf = ByteArray(2048)
        while (scope.isActive && running.get()) {
            val s = socket ?: break
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running.get()) AppDebug.log("[CAP][SERVER] receive error: ${e.message}")
                continue
            }
            if (packet.address != clientAddress) {
                AppDebug.log("[CAP][SERVER] ignoring datagram from ${packet.address}")
                continue
            }
            val msg = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
            handleControl(msg, InetSocketAddress(packet.address, packet.port))
        }
    }

    private fun handleControl(msg: String, from: InetSocketAddress) {
        when {
            msg.startsWith(WfasCaptions.MSG_REQ) -> handleRequest(msg, from)
            msg.startsWith(WfasCaptions.MSG_STOP) -> {
                streaming.set(false)
                peer = null
                AppDebug.log("[CAP][SERVER] client asked to stop")
            }
        }
    }

    private fun handleRequest(msg: String, from: InetSocketAddress) {
        val version = WfasCaptions.token(msg, "v")?.toIntOrNull() ?: 0
        if (version != WfasCaptions.EXT_VERSION) {
            reply(WfasCaptions.buildUnavailable(WfasCaptions.REASON_DISABLED), from)
            return
        }

        if (requireProof) {
            val given = WfasCaptions.token(msg, "proof")
            val expected = WfasCaptions.proof(authKey, cnonceHex, snonceHex)
            if (given == null || !WfasCaptions.constantTimeEquals(given, expected)) {
                AppDebug.log("[CAP][SERVER] proof rejected")
                reply(WfasCaptions.buildUnavailable(WfasCaptions.REASON_DENIED), from)
                return
            }
        }

        val reason = availability()
        if (reason != null) {
            reply(WfasCaptions.buildUnavailable(reason), from)
            return
        }

        val requested = WfasCaptions.token(msg, "lang") ?: "auto"
        val effective = if (requested == "auto") languageTag else requested

        if (streaming.get() && peer == from) {
            reply(WfasCaptions.buildAck(effective, encrypting), from)
            AppDebug.log("[CAP][SERVER] duplicate request from $from, re-acking without rekey")
            return
        }

        synchronized(sendLock) {
            dir = if (encrypting) WfasCaptions.deriveUnicast(authKey, cnonceHex, snonceHex) else null
            openCapId = -1L
            openRev = -1
        }
        peer = from
        streaming.set(true)
        reply(WfasCaptions.buildAck(effective, encrypting), from)
        AppDebug.log("[CAP][SERVER] streaming captions to $from lang=$effective enc=$encrypting")
    }

    private fun reply(text: String, to: InetSocketAddress) {
        val s = socket ?: return
        val bytes = text.toByteArray(Charsets.US_ASCII)
        runCatching { s.send(DatagramPacket(bytes, bytes.size, to.address, to.port)) }
    }

    fun emit(text: String, samplePos: Long, durMs: Int, isFinal: Boolean) {
        if (!streaming.get()) return
        val caption = synchronized(sendLock) {
            if (openCapId < 0L) {
                openCapId = capIdSeq.getAndIncrement() and 0xFFFFFFFFL
                openRev = 0
            } else {
                openRev += 1
            }
            if (openRev > 0xFFFF) {
                openCapId = capIdSeq.getAndIncrement() and 0xFFFFFFFFL
                openRev = 0
            }
            val c = WfasCaptions.Caption(
                capId = openCapId,
                rev = openRev,
                samplePos = samplePos and 0xFFFFFFFFL,
                durMs = durMs.coerceIn(0, 0xFFFF),
                isFinal = isFinal,
                isClear = false,
                text = text
            )
            if (isFinal) {
                openCapId = -1L
                openRev = -1
            }
            c
        }
        transmit(caption)
    }

    fun clearScreen(samplePos: Long) {
        if (!streaming.get()) return
        val caption = synchronized(sendLock) {
            openCapId = -1L
            openRev = -1
            WfasCaptions.Caption(
                capId = capIdSeq.getAndIncrement() and 0xFFFFFFFFL,
                rev = 0,
                samplePos = samplePos and 0xFFFFFFFFL,
                durMs = 0,
                isFinal = true,
                isClear = true,
                text = ""
            )
        }
        transmit(caption)
    }

    private fun transmit(caption: WfasCaptions.Caption) {
        val target = peer ?: return
        val datagram = synchronized(sendLock) {
            runCatching { WfasCaptions.encode(dir, caption) }.getOrNull()
        } ?: return
        scope.launch(Dispatchers.IO) {
            repeat(WfasCaptions.REPEAT_COUNT) { attempt ->
                if (!streaming.get()) return@launch
                val s = socket ?: return@launch
                val sent = runCatching {
                    s.send(DatagramPacket(datagram, datagram.size, target.address, target.port))
                }
                if (sent.isFailure) return@launch
                if (attempt < WfasCaptions.REPEAT_COUNT - 1) delay(WfasCaptions.REPEAT_DELAY_MS)
            }
        }
    }

    suspend fun awaitStopped() = withContext(Dispatchers.IO) {
        loopJob?.join()
    }
}
