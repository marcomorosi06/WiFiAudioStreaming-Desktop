/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
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

package wfas3

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What the server streams and how (the v3 controls of the server). */
data class Wfas3ServerSettings(
    val codec: Int = W3.CODEC_PCM,
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val bits: Int = 16,
    val opusKbps: Int = 256,
    val opusFrameSamples: Int = 480,
    val latencyMs: Int = 60,
    val sync: Boolean = false,
    val multicast: Boolean = false,
    val acceptMicrophone: Boolean = false,
    val acceptNew: Boolean = true,
) {
    /** The configuration of the main stream, seen from the source (bit0 set). */
    fun streamConfig(): Wfas3Config {
        val flags = W3.CF_SOURCE or (if (sync) W3.CF_SYNC else 0) or (if (multicast) W3.CF_MULTICAST else 0)
        return if (codec == W3.CODEC_OPUS) {
            Wfas3Config.opus(flags, channels.coerceIn(1, 2), opusKbps, opusFrameSamples, latencyMs)
        } else {
            Wfas3Config.pcm(flags, sampleRate, channels.coerceIn(1, 2), bits, latencyMs)
        }
    }

    /** The rate the capture must run at. */
    val captureRate: Int get() = if (codec == W3.CODEC_OPUS) 48000 else sampleRate

    companion object {
        const val GROUP_FLAGS = W3.CF_SYNC or W3.CF_MULTICAST
    }
}

/** A client of the server, for the user interface. */
data class Wfas3Peer(
    val session: Int,
    val pair: Int,
    val name: String,
    val streaming: Boolean,
    val microphone: Boolean,
    val synchronised: Boolean,
    val multicast: Boolean,
)

/**
 * Server role: answers the paired clients that connect, streams the captured
 * audio to every session that asked for it (or to the multicast group), and
 * plays their microphone when the user allows it.
 */
class Wfas3Server(
    private val engine: Wfas3Engine,
    initial: Wfas3ServerSettings,
) : Wfas3Listener {

    interface Events {
        fun onPeers(peers: List<Wfas3Peer>) {}
        /** Microphone audio of a client, decoded; [playUs] is its playback instant, 0 if none. */
        fun onMicrophone(session: Int, pcm: ShortArray, frames: Int, channels: Int, rate: Int, captureUs: Long, playUs: Long) {}
    }

    private class Peer(val session: Int, val pair: Int, var config: Wfas3Config) {
        @Volatile var started = false
        @Volatile var sent = 0L
        @Volatile var sendErrors = 0L
        var mic: Wfas3Decoder? = null
        var micConfig: Wfas3Config? = null
    }

    @Volatile var settings: Wfas3ServerSettings = initial
        private set
    @Volatile var events: Events? = null

    private val peers = ConcurrentHashMap<Int, Peer>()
    private val framerLock = Any()
    private var framer: Wfas3Framer? = null
    private var groupOpen = false
    private val micPcm = ShortArray(960 * 2)
    @Volatile private var running = false

    fun start() {
        Wfas3Trace.log { "server: start ${settings.streamConfig()}" }
        running = true
        engine.addListener(this)
        engine.audioSink = Wfas3AudioSink { s, stream, _, pos, cap, play, data, off, len -> onAudio(s, stream, pos, cap, play, data, off, len) }
        apply(settings, initialStart = true)
    }

    fun stop() {
        Wfas3Trace.log { "server: stop, closing sessions ${peers.keys}" }
        running = false
        for (p in peers.values) engine.closeSession(p.session, W3.BYE_NORMAL)
        if (groupOpen) { engine.groupClose(); groupOpen = false }
        engine.setPolicy(true, 0, 0, settings.streamConfig())
        engine.removeListener(this)
        engine.audioSink = null
        synchronized(framerLock) { framer?.close(); framer = null }
        for (p in peers.values) p.mic?.close()
        peers.clear()
        publish()
    }

    /**
     * Change the settings while running. Audio parameters follow at once
     * (PARAMS); sync and multicast are part of the session flags, so the
     * clients are asked to reconnect (BYE preempted) and come back with them.
     */
    fun update(next: Wfas3ServerSettings) {
        val prev = settings
        settings = next
        if (!running) return
        val flagsChanged = (prev.streamConfig().flags != next.streamConfig().flags)
        val groupChanged = next.multicast && prev.streamConfig() != next.streamConfig()
        Wfas3Trace.log { "server: update ${next.streamConfig()} flagsChanged=$flagsChanged groupChanged=$groupChanged peers=${peers.keys}" }
        if (flagsChanged || groupChanged) {
            for (p in peers.values) engine.closeSession(p.session, W3.BYE_PREEMPTED)
        }
        apply(next, initialStart = false)
        if (!flagsChanged && !groupChanged && prev.streamConfig() != next.streamConfig()) {
            for (p in peers.values) {
                if (!p.config.multicast) {
                    val c = next.streamConfig().withFlags(p.config.flags or W3.CF_SOURCE)
                    if (engine.streamParams(p.session, c) == W3.OK) { p.config = c; p.started = false }
                }
            }
        }
        if (prev.acceptMicrophone != next.acceptMicrophone) {
            for (p in peers.values) if (p.micConfig != null) engine.streamWant(p.session, next.acceptMicrophone)
        }
        publish()
    }

    private fun apply(s: Wfas3ServerSettings, initialStart: Boolean) {
        val cfg = s.streamConfig()
        synchronized(framerLock) {
            framer?.close()
            framer = Wfas3Framer(cfg) { pos, cap, payload, len -> send(pos, cap, payload, len) }
        }
        if (groupOpen) { engine.groupClose(); groupOpen = false }
        if (s.multicast) {
            groupOpen = engine.groupOpen(cfg, randomGroup()) == W3.OK
        }
        // Accept PCM and Opus with up to two channels: a different proposal is
        // accepted and moved to this configuration with PARAMS at once.
        engine.setPolicy(!s.acceptNew, (1 shl W3.CODEC_PCM) or (1 shl W3.CODEC_OPUS), 2, cfg)
        if (initialStart) publish()
    }

    private var nextCapUs = 0L
    private var paceChannels = 2
    private var silence = ShortArray(0)

    /** Captured PCM, 16-bit interleaved, at [Wfas3ServerSettings.captureRate]. */
    fun feed(pcm: ShortArray, off: Int, frames: Int, channels: Int, captureUs: Long) {
        if (!running || frames <= 0) return
        synchronized(framerLock) {
            nextCapUs = captureUs + frames * 1_000_000L / settings.captureRate
            paceChannels = channels
            if (peers.isEmpty()) return
            framer?.feed(pcm, off, frames, channels, captureUs)
        }
    }

    /**
     * Call often (every few ms) while the capture delivers nothing: a system
     * that plays nothing gives no samples, but a v3 stream keeps its cadence
     * during silence (specification 10.3), so the gap is filled with zeros.
     */
    fun pace(nowUs: Long) {
        if (!running) return
        synchronized(framerLock) {
            if (nextCapUs == 0L) { nextCapUs = nowUs; return }
            val behind = nowUs - nextCapUs
            if (behind < 30_000L) return
            val rate = settings.captureRate
            val us = minOf(behind - 10_000L, 100_000L)
            val frames = (us * rate / 1_000_000L).toInt()
            if (frames <= 0) return
            if (silence.size < frames * paceChannels) silence = ShortArray(frames * paceChannels)
            val start = nextCapUs
            nextCapUs = start + frames * 1_000_000L / rate
            if (peers.isEmpty()) return
            framer?.feed(silence, 0, frames, paceChannels, start)
        }
    }

    private fun send(pos: Long, cap: Long, payload: ByteArray, len: Int) {
        if (groupOpen && engine.groupMembers() > 0) engine.groupSend(pos, cap, payload, 0, len)
        for (p in peers.values) {
            if (p.started && !p.config.multicast) {
                val st = engine.sendAudio(p.session, pos, cap, payload, 0, len)
                if (st == W3.OK) p.sent++ else p.sendErrors++
            }
        }
        if (Wfas3Trace.on) {
            val now = Wfas3Engine.clockUs()
            if (now - lastStatsUs >= 5_000_000L) {
                lastStatsUs = now
                Wfas3Trace.log {
                    "server: packets=${packets} capture lag=${(now - cap) / 1000}ms peers=" + peers.values.joinToString { p ->
                        "[s${p.session} pair${p.pair} started=${p.started} sent=${p.sent} errors=${p.sendErrors}]"
                    }
                }
                for (p in peers.values) { p.sent = 0; p.sendErrors = 0 }
                packets = 0
            }
            packets++
        }
    }
    private var lastStatsUs = 0L
    private var packets = 0L

    private fun onAudio(session: Int, stream: Int, pos: Long, cap: Long, play: Long, data: ByteArray, off: Int, len: Int) {
        if (stream != W3.STREAM_MIC || !settings.acceptMicrophone) return
        val p = peers[session] ?: return
        val d = p.mic ?: return
        val ev = events ?: return
        val n = d.decode(pos, data, off, len, micPcm)
        if (n > 0) ev.onMicrophone(session, micPcm, n, d.outChannels, d.config.sampleRate, cap, play)
    }

    // -- engine events --------------------------------------------------------

    override fun onSessionUp(session: Int, pair: Int, config: Wfas3Config) {
        if (!running) { Wfas3Trace.log { "server: session $session up while not running, ignored" }; return }
        val info = engine.sessionInfo(session)
        if (info == null || !info.source) {
            // This device serves audio; a peer that wants to send its own is not taken.
            Wfas3Trace.log { "server: session $session pair $pair closed, not a source session (info=${info != null})" }
            engine.closeSession(session, W3.BYE_NORMAL)
            return
        }
        val want = settings.streamConfig()
        val agreed = config.flags and Wfas3ServerSettings.GROUP_FLAGS
        if (agreed != (want.flags and Wfas3ServerSettings.GROUP_FLAGS)) {
            // The client proposed old flags: ask it to come back with the current ones.
            Wfas3Trace.log { "server: session $session pair $pair preempted, flags $agreed instead of ${want.flags and Wfas3ServerSettings.GROUP_FLAGS}" }
            engine.closeSession(session, W3.BYE_PREEMPTED)
            return
        }
        Wfas3Trace.log { "server: session $session pair $pair added $config, peers before=${peers.keys}" }
        val peer = Peer(session, pair, config)
        peers[session] = peer
        if (!config.multicast) {
            val target = want.withFlags(config.flags or W3.CF_SOURCE)
            if (target != config && engine.streamParams(session, target) == W3.OK) peer.config = target
        }
        publish()
    }

    override fun onSessionDown(session: Int, pair: Int, reason: Int) {
        Wfas3Trace.log { "server: session $session pair $pair down reason=$reason, known=${peers[session]?.pair}" }
        val p = peers.remove(session) ?: return
        p.mic?.close()
        publish()
    }

    override fun onStreamStart(session: Int) {
        Wfas3Trace.log { "server: session $session stream start (known=${peers.containsKey(session)})" }
        peers[session]?.let { it.started = true; publish() }
    }

    override fun onStreamStop(session: Int) {
        Wfas3Trace.log { "server: session $session stream stop (known=${peers.containsKey(session)})" }
        peers[session]?.let { it.started = false; publish() }
    }

    /** The stream this device receives is the microphone of the client. */
    override fun onStreamParams(session: Int, config: Wfas3Config, on: Boolean) {
        val p = peers[session] ?: return
        p.mic?.close()
        p.mic = null
        p.micConfig = null
        if (on && config.isValid() && config.channels <= 2) {
            try {
                p.mic = Wfas3Decoder(config)
                p.micConfig = config
            } catch (_: Exception) { }
            engine.streamWant(session, settings.acceptMicrophone && p.mic != null)
        } else {
            engine.streamWant(session, false)
        }
        publish()
    }

    override fun onPairsChanged() = publish()

    fun peers(): List<Wfas3Peer> {
        val names = engine.pairs().associateBy { it.slot }
        return peers.values.sortedBy { it.session }.map {
            Wfas3Peer(it.session, it.pair, names[it.pair]?.name ?: "", it.started, it.micConfig != null && settings.acceptMicrophone,
                it.config.sync, it.config.multicast)
        }
    }

    private fun publish() {
        val ev = events ?: return
        try { ev.onPeers(peers()) } catch (_: Throwable) { }
    }

    private fun randomGroup(): Wfas3Addr {
        val r = SecureRandom()
        val ip = ByteArray(16)
        if (engine.ipv6Link) {
            // On a link without IPv4: a transient, link-scope IPv6 group
            // (ff12::/16, RFC 4291), 32 random bits, new at every opening.
            // Link scope: it can never be routed off the link.
            ip[0] = 0xFF.toByte(); ip[1] = 0x12
            for (i in 12 until 16) ip[i] = r.nextInt(256).toByte()
            // ff12:: itself (group ID 0) is reserved.
            if ((12 until 16).all { ip[it] == 0.toByte() }) ip[15] = 1
            return Wfas3Addr(ip, W3.GROUP_PORT)
        }
        // An administratively scoped IPv4 group (239.255.0.0/16), new at every opening.
        ip[10] = 0xFF.toByte(); ip[11] = 0xFF.toByte()
        ip[12] = 239.toByte(); ip[13] = 255.toByte()
        ip[14] = (1 + r.nextInt(254)).toByte(); ip[15] = (1 + r.nextInt(254)).toByte()
        return Wfas3Addr(ip, W3.GROUP_PORT)
    }
}

/** State of the client, for the user interface. */
sealed class Wfas3ClientState {
    object Idle : Wfas3ClientState()
    data class Searching(val pair: Int) : Wfas3ClientState()
    data class Connecting(val pair: Int) : Wfas3ClientState()
    data class Connected(val pair: Int, val session: Int, val config: Wfas3Config) : Wfas3ClientState()
    /** Not answering now; the client keeps looking until the user stops it. */
    data class Waiting(val pair: Int, val reason: Reason) : Wfas3ClientState()
    /** Stopped for a reason the user needs to know; no retry. */
    data class Stopped(val pair: Int, val reason: Reason) : Wfas3ClientState()

    enum class Reason { NOT_FOUND, BUSY, NOT_PERMITTED, SUSPECT, REMOVED, GUEST_ENDED, LOST, UNSUPPORTED }
}

/**
 * Client role: finds a paired server, opens the session (learning the
 * server's configuration the first time), plays its audio and, when allowed,
 * sends the microphone. Reconnects until the user disconnects.
 */
class Wfas3Client(
    private val engine: Wfas3Engine,
    private val outputFactory: (rate: Int, channels: Int) -> Wfas3PcmOutput,
) : Wfas3Listener {

    interface Events {
        fun onState(state: Wfas3ClientState) {}
        /** The server does not allow the microphone for this device. */
        fun onMicrophoneRefused() {}
    }

    @Volatile var events: Events? = null
    /**
     * The latency this client proposes before it knows the server. Once a
     * session is up the latency is the one of the session, which the server
     * sets (it moves every session to its own configuration, and PARAMS
     * carries later changes): see [playLatencyMs].
     */
    @Volatile var latencyMs: Int = 60

    /** A copy of every decoded block (interleaved, its channel count), e.g. for a level meter. */
    @Volatile var pcmTap: ((ShortArray, Int) -> Unit)? = null
    @Volatile var state: Wfas3ClientState = Wfas3ClientState.Idle
        private set

    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wfas3-client").apply { isDaemon = true } }
    private val lock = Any()
    private var target = -1
    private var wanted = false
    private var addr: Wfas3Addr? = null
    private var manualAddr = false
    @Volatile private var session = -1
    private var upAtUs = 0L
    private var lastProposal: Wfas3Config? = null
    private var asks = 0
    private var flipTries = 0
    private var retry: ScheduledFuture<*>? = null
    @Volatile private var decoder: Wfas3Decoder? = null
    @Volatile private var player: Wfas3Player? = null
    @Volatile private var playout: Wfas3Playout? = null
    private val pcm = ShortArray(1344 * 2)

    // Microphone
    @Volatile var micWanted = false
        private set
    private var micBlocked = false
    private var micConfig: Wfas3Config? = null
    @Volatile private var micFramer: Wfas3Framer? = null
    @Volatile private var micStarted = false

    fun attach() {
        if (Wfas3Trace.on) {
            // Also while no audio flows (e.g. synchronised, clocks not measured yet).
            timer.scheduleAtFixedRate({
                val s = session
                if (s >= 0) {
                    val i = engine.sessionInfo(s)
                    Wfas3Trace.log {
                        "client: s$s rxOn=${i?.rxOn} received=${i?.rxReceived} lost=${i?.rxLost} timeValid=${i?.timeValid} " +
                            "offset=${i?.offsetUs}us D=${i?.delayMs}ms silent=${i?.silent} sync=${i?.synchronised}"
                    }
                }
            }, 3, 3, TimeUnit.SECONDS)
        }
        engine.addListener(this)
        engine.audioSink = Wfas3AudioSink { s, stream, _, pos, cap, play, data, off, len -> onAudio(s, stream, pos, cap, play, data, off, len) }
        // A client never answers sessions opened by others.
        engine.setPolicy(true, 0, 0, Wfas3Config.pcm(W3.CF_SOURCE, 48000, 2, 16, 60))
    }

    fun detach() {
        disconnect()
        engine.removeListener(this)
        engine.audioSink = null
        timer.shutdownNow()
    }

    /** Connect to the server of [pair]; at [at] if the user typed an address, else find it. */
    fun connect(pair: Int, at: Wfas3Addr? = null) {
        synchronized(lock) {
            disconnectLocked(silent = true)
            target = pair
            wanted = true
            asks = 0
            flipTries = 0
            micBlocked = false
            manualAddr = at != null
            addr = at
            if (at != null) open(at) else search()
        }
    }

    fun disconnect() = synchronized(lock) { disconnectLocked(silent = false) }

    private fun disconnectLocked(silent: Boolean) {
        wanted = false
        retry?.cancel(false); retry = null
        if (session >= 0) engine.closeSession(session, W3.BYE_NORMAL)
        if (target >= 0) engine.find(target, false)
        stopAudio()
        session = -1
        val t = target
        target = -1
        if (!silent || t >= 0) setState(Wfas3ClientState.Idle)
    }

    fun setMicrophone(on: Boolean) {
        synchronized(lock) {
            micWanted = on
            val s = session
            if (s < 0) return
            val st = state as? Wfas3ClientState.Connected ?: return
            if (on) {
                if (!st.config.microphone) {
                    // bit3 is agreed at the opening: reopen the session with it.
                    if (!micBlocked) reopen()
                    return
                }
                startMic(s)
            } else {
                engine.micOff(s)
                stopMic()
            }
        }
    }

    /** Microphone PCM, 16-bit interleaved, 48 kHz. */
    fun feedMic(pcm: ShortArray, off: Int, frames: Int, channels: Int, captureUs: Long) {
        if (!micStarted) return
        val f = micFramer ?: return
        f.feed(pcm, off, frames, channels, captureUs)
    }

    // -- connection logic -----------------------------------------------------

    private fun search() {
        val t = target
        if (t < 0) return
        setState(Wfas3ClientState.Searching(t))
        if (engine.find(t, true) == W3.E_STATE) {
            engine.find(t, false)
            engine.find(t, true)
        }
    }

    private fun proposal(): Wfas3Config {
        val pairInfo = engine.pair(target)
        val micOk = micWanted && !micBlocked && pairInfo?.microphone == true
        val hint = engine.hint(target)
        val base = hint?.withFlags(hint.flags and (W3.CF_SOURCE or W3.CF_MICROPHONE).inv())
            ?: (if (asks == 0) GUESS else ASK).copy(latencyMs = latencyMs.coerceIn(0, 2000))
        // bit3 only where it can be agreed: never with a group (its flags are fixed).
        val mic = micOk && !base.multicast
        return base.withFlags(base.flags or (if (mic) W3.CF_MICROPHONE else 0))
    }

    private fun open(a: Wfas3Addr) {
        val t = target
        if (t < 0) return
        addr = a
        val p = proposal()
        lastProposal = p
        setState(Wfas3ClientState.Connecting(t))
        val st = engine.connect(t, a, p)
        if (st != W3.OK) later(2_000) { if (manualAddr) open(a) else search() }
    }

    private fun reopen() {
        val a = addr ?: return
        if (session >= 0) engine.closeSession(session, W3.BYE_NORMAL)
        session = -1
        stopAudio()
        later(HANDSHAKE_GAP_MS) { open(a) }
    }

    private fun later(ms: Long, f: () -> Unit) {
        retry?.cancel(false)
        retry = timer.schedule({ synchronized(lock) { if (wanted) f() } }, ms, TimeUnit.MILLISECONDS)
    }

    override fun onFound(pair: Int, addr: Wfas3Addr) {
        synchronized(lock) { if (pair == target && wanted && session < 0) open(addr) }
    }

    override fun onNotFound(pair: Int) {
        synchronized(lock) {
            if (pair != target || !wanted) return
            setState(Wfas3ClientState.Waiting(pair, Wfas3ClientState.Reason.NOT_FOUND))
            later(5_000) { search() }
        }
    }

    override fun onRefused(pair: Int, status: Int, config: Wfas3Config?) {
        synchronized(lock) {
            if (pair != target || !wanted) return
            val a = addr ?: return
            when (status) {
                W3.ST_UNSUPPORTED -> {
                    if (config != null && asks < 3) {
                        asks++
                        engine.saveHint(pair, config.withFlags(config.flags and W3.CF_SOURCE.inv()))
                        later(HANDSHAKE_GAP_MS) { open(a) }
                    } else {
                        setState(Wfas3ClientState.Stopped(pair, Wfas3ClientState.Reason.UNSUPPORTED))
                        wanted = false
                    }
                }
                W3.ST_BUSY -> {
                    setState(Wfas3ClientState.Waiting(pair, Wfas3ClientState.Reason.BUSY))
                    later(10_000) { if (manualAddr) open(a) else search() }
                }
                W3.ST_NOT_PERMITTED -> {
                    if (lastProposal?.microphone == true) {
                        micBlocked = true
                        events?.onMicrophoneRefused()
                        later(HANDSHAKE_GAP_MS) { open(a) }
                    } else {
                        setState(Wfas3ClientState.Stopped(pair, Wfas3ClientState.Reason.NOT_PERMITTED))
                        wanted = false
                    }
                }
            }
        }
    }

    override fun onAttemptFailed(pair: Int) {
        synchronized(lock) {
            if (pair != target || !wanted || session >= 0) return
            // A refusal comes with the end of its attempt and has already planned the next step.
            if (retry?.isDone == false) return
            if (state is Wfas3ClientState.Connecting) {
                setState(Wfas3ClientState.Waiting(pair, Wfas3ClientState.Reason.LOST))
                later(3_000) { if (manualAddr) addr?.let { open(it) } else search() }
            }
        }
    }

    override fun onSessionUp(session: Int, pair: Int, config: Wfas3Config) {
        synchronized(lock) {
            if (pair != target || !wanted) { engine.closeSession(session, W3.BYE_NORMAL); return }
            this.session = session
            upAtUs = Wfas3Engine.clockUs()
            engine.saveHint(pair, config.withFlags(config.flags and W3.CF_SOURCE.inv()))
            asks = 0
            if (!startAudio(config)) {
                engine.closeSession(session, W3.BYE_NORMAL)
                this.session = -1
                setState(Wfas3ClientState.Stopped(pair, Wfas3ClientState.Reason.UNSUPPORTED))
                wanted = false
                return
            }
            engine.streamWant(session, true)
            if (!config.sync) engine.setBufferMs(session, playLatencyMs(config))
            setState(Wfas3ClientState.Connected(pair, session, config))
            if (micWanted && config.microphone) startMic(session)
        }
    }

    override fun onSessionDown(session: Int, pair: Int, reason: Int) {
        synchronized(lock) {
            if (session != this.session) return
            this.session = -1
            stopAudio()
            if (!wanted) return
            val quick = Wfas3Engine.clockUs() - upAtUs < 5_000_000L
            if (!quick) flipTries = 0
            when (reason) {
                W3.BYE_PREEMPTED -> {
                    // The flags of the session (sync, multicast) are not those
                    // of the server: it changed them, or the proposal carried
                    // old ones. Every handshake costs 2 s (one per pair), so
                    // first try the likeliest fix, synchronisation switched;
                    // if that is preempted too, ask the server (status 3).
                    val old = engine.hint(pair)
                    if (old != null && flipTries == 0) {
                        engine.saveHint(pair, old.withFlags(old.flags xor W3.CF_SYNC))
                        flipTries = 1
                        asks = 0
                    } else {
                        engine.saveHint(pair, null)
                        flipTries = 0
                        asks = 1
                    }
                    Wfas3Trace.log { "client: preempted (quick=$quick), next proposal ${proposal()}" }
                    setState(Wfas3ClientState.Connecting(pair))
                    later(HANDSHAKE_GAP_MS) { addr?.let { open(it) } ?: search() }
                }
                W3.BYE_REVOKED, W3.DOWN_REMOVED -> {
                    setState(Wfas3ClientState.Stopped(pair, Wfas3ClientState.Reason.REMOVED)); wanted = false
                }
                W3.BYE_GUEST_ENDED -> {
                    setState(Wfas3ClientState.Stopped(pair, Wfas3ClientState.Reason.GUEST_ENDED)); wanted = false
                }
                W3.DOWN_LOCAL -> { }
                else -> {
                    setState(Wfas3ClientState.Waiting(pair, Wfas3ClientState.Reason.LOST))
                    later(1_000) { if (manualAddr) addr?.let { open(it) } else search() }
                }
            }
        }
    }

    override fun onSuspect(pair: Int) {
        synchronized(lock) {
            if (pair != target) return
            wanted = false
            retry?.cancel(false)
            setState(Wfas3ClientState.Stopped(pair, Wfas3ClientState.Reason.SUSPECT))
        }
    }

    /**
     * The main stream changed parameters (PARAMS): restart the decoder and the
     * output. When only the latency changed the audio itself is the same, so
     * both stay: the new latency applies to the next blocks (raising it leaves
     * a short pause, lowering it drops as much audio), instead of a gap of a
     * whole restart.
     */
    override fun onStreamParams(session: Int, config: Wfas3Config, on: Boolean) {
        synchronized(lock) {
            if (session != this.session) return
            val st0 = state as? Wfas3ClientState.Connected
            val cur = st0?.config
            if (on && st0 != null && cur != null && decoder != null && playout != null && sameAudio(cur, config)) {
                Wfas3Trace.log { "client: s$session latency ${cur.latencyMs} -> ${config.latencyMs} ms, audio kept" }
                setState(st0.copy(config = config.withFlags(cur.flags)))
                if (!cur.sync) engine.setBufferMs(session, playLatencyMs(config))
                return
            }
            stopAudioOnly()
            if (on && !startAudio(config)) {
                engine.closeSession(session, W3.BYE_NORMAL)
                return
            }
            val st = state
            if (st is Wfas3ClientState.Connected) {
                setState(st.copy(config = config.withFlags(st.config.flags)))
                // The server changed its latency (or moved the session to its own).
                if (!st.config.sync) engine.setBufferMs(session, playLatencyMs(config))
            }
        }
    }

    /** Same audio format, so the same decoder and output: only the latency may differ. */
    private fun sameAudio(a: Wfas3Config, b: Wfas3Config): Boolean =
        a.codec == b.codec && a.channels == b.channels && a.bits == b.bits &&
            a.sampleRate == b.sampleRate && a.frameSamples == b.frameSamples && a.payloadBytes == b.payloadBytes

    /**
     * The latency to play a session with: the one the server set for it.
     * Bounded, because it comes from the other side: a value out of range
     * (or none) must not make the buffer grow without limit, nor vanish.
     */
    private fun playLatencyMs(config: Wfas3Config?): Int {
        val ms = config?.latencyMs ?: 0
        return if (ms > 0) ms.coerceIn(MIN_PLAY_LATENCY_MS, MAX_PLAY_LATENCY_MS) else latencyMs.coerceIn(MIN_PLAY_LATENCY_MS, MAX_PLAY_LATENCY_MS)
    }

    /** STREAM_START / STOP on a client refer to the stream it sends: the microphone. */
    override fun onStreamStart(session: Int) { if (session == this.session) micStarted = true }
    override fun onStreamStop(session: Int) { if (session == this.session) micStarted = false }

    // -- audio ----------------------------------------------------------------

    private fun startAudio(c: Wfas3Config): Boolean {
        return try {
            val d = Wfas3Decoder(c)
            val sess = session
            // The report goes from another thread: the player thread must never
            // wait for the engine lock.
            val po = Wfas3Playout(d.outChannels, c.sampleRate) { late ->
                if (c.sync && sess >= 0) {
                    Wfas3Trace.log { "client: s$sess audio late by ${late / 1000}ms, reported" }
                    try { timer.execute { engine.audioLate(sess, late) } } catch (_: Exception) { }
                }
            }
            val out = outputFactory(c.sampleRate, d.outChannels)
            val pl = Wfas3Player(out, po)
            decoder = d; playout = po; player = pl
            pl.start()
            true
        } catch (_: Throwable) {
            stopAudioOnly()
            false
        }
    }

    private fun stopAudioOnly() {
        player?.stop(); player = null
        decoder?.close(); decoder = null
        playout = null
    }

    private fun stopAudio() {
        stopAudioOnly()
        stopMic()
    }

    private fun onAudio(session: Int, stream: Int, pos: Long, cap: Long, play: Long, data: ByteArray, off: Int, len: Int) {
        if (stream != W3.STREAM_MAIN || session != this.session) return
        val d = decoder ?: return
        val po = playout ?: return
        val sync = (state as? Wfas3ClientState.Connected)?.config?.sync == true
        val now = Wfas3Engine.clockUs()
        // The playout keeps at least the jitter of the network plus what the
        // output buffer holds: below that, every block would arrive late.
        val latUs = playLatencyMs((state as? Wfas3ClientState.Connected)?.config) * 1000L
        val outUs = player?.bufferUs ?: 0L
        d.conceal(pos) { buf, n, p ->
            val back = (pos - p) * 1_000_000L / d.config.sampleRate
            if (sync && play > 0) po.pushAt(play - back, buf, n) else po.pushArrived(cap - back, now, latUs, outUs, buf, n)
        }
        val n = d.decode(pos, data, off, len, pcm)
        if (n <= 0) return
        if (sync && play > 0) po.pushAt(play, pcm, n) else po.pushArrived(cap, now, latUs, outUs, pcm, n)
        if (Wfas3Trace.on) {
            rxPackets++
            if (now - lastStatsUs >= 5_000_000L) {
                lastStatsUs = now
                val info = engine.sessionInfo(session)
                Wfas3Trace.log {
                    "client: s$session packets=$rxPackets sync=$sync play=${if (play > 0) (play - now) / 1000 else 0}ms " +
                        "lost=${info?.rxLost} jitter=${info?.rxJitterUs}us timeValid=${info?.timeValid} D=${info?.delayMs}ms " +
                        "output=${outUs / 1000}ms playout: ${po.stats()}"
                }
                rxPackets = 0
            }
        }
        pcmTap?.let { tap -> try { tap(pcm.copyOf(n * d.outChannels), d.outChannels) } catch (_: Throwable) { } }
    }

    private var rxPackets = 0L
    private var lastStatsUs = 0L

    private fun startMic(s: Int) {
        // The core puts the flags of the session in MIC_STATE itself.
        if (engine.streamParams(s, MIC) != W3.OK) return
        micConfig = MIC
        micFramer?.close()
        micFramer = Wfas3Framer(MIC) { pos, cap, payload, len -> engine.sendAudio(s, pos, cap, payload, 0, len) }
    }

    private fun stopMic() {
        micStarted = false
        micFramer?.close(); micFramer = null
        micConfig = null
    }

    private fun setState(s: Wfas3ClientState) {
        Wfas3Trace.log { "client: state $s" }
        state = s
        val ev = events ?: return
        engine.post { try { ev.onState(s) } catch (_: Throwable) { } }
    }

    companion object {
        /** Between two handshakes with the same server (its per-pair quota is one every 2 s). */
        const val HANDSHAKE_GAP_MS = 2_100L

        /** The latency a session is played with stays in this range, whatever the server sends. */
        const val MIN_PLAY_LATENCY_MS = 20
        const val MAX_PLAY_LATENCY_MS = 1000

        /**
         * A first proposal that our servers answer with their configuration
         * (status 3): eight channels, more than they accept. A server that
         * accepts it is played on its first two channels.
         */
        val ASK: Wfas3Config = Wfas3Config.pcm(0, 48000, 8, 16, 60)

        /**
         * The first proposal to a server never reached: the defaults of our
         * servers (no sync, no multicast). They accept it and move it to their
         * stream with PARAMS, one handshake instead of two; a server with other
         * flags preempts it and the next attempt follows (see onSessionDown).
         */
        val GUESS: Wfas3Config = Wfas3Config.pcm(0, 48000, 2, 16, 60)

        /** Microphone: mono Opus, 10 ms, 48 kbit/s. */
        val MIC: Wfas3Config = Wfas3Config.opus(0, 1, 48, 480, 60)
    }
}
