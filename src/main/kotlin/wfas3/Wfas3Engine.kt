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

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.MembershipKey
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persistence of the v3 records. Implementations encrypt at rest, keep the
 * files out of backups and write atomically. [saveDevice] and [savePair]
 * must have completed (or thrown) when they return: the engine releases the
 * datagram of the same output only after that. The engine wipes every array
 * it passes or receives once it is done with it.
 */
interface Wfas3Store {
    fun loadDevice(): ByteArray?
    fun saveDevice(record: ByteArray)
    fun loadPair(slot: Int): ByteArray?
    fun savePair(slot: Int, record: ByteArray)
    fun erasePair(slot: Int)

    /** The stream configuration a peer last agreed to (not secret), or null. */
    fun loadHint(slot: Int): ByteArray?
    fun saveHint(slot: Int, data: ByteArray?)

    /** Erase every v3 record: device and pairs. */
    fun eraseAll()

    /**
     * Whether any pair record is stored, readable or not. A device record
     * that is missing while pair records exist is never replaced by a new
     * one: the new entry key and counters would make every healthy pair
     * look stolen (specification 7.3, 16).
     */
    fun hasPairRecords(): Boolean = (0 until W3.MAX_PAIRS).any { slot ->
        try { loadPair(slot)?.also { it.fill(0) } != null } catch (_: Exception) { true }
    }
}

/** Loads libwfas3 once. The platform sets [loader] before the first use. */
object Wfas3Library {
    @Volatile var loader: (() -> Unit)? = null
    @Volatile private var loaded = false

    @Synchronized
    fun ensure() {
        if (loaded) return
        val l = loader ?: throw IllegalStateException("wfas3: no library loader")
        l()
        loaded = true
    }

    fun isAvailable(): Boolean = try { ensure(); Wfas3Native.nSelftest() == W3.OK } catch (_: Throwable) { false }
}

/** Why the engine could not start or had to stop. */
enum class Wfas3Failure {
    LIBRARY,       // the native library is missing for this system
    SELFTEST,      // a primitive gives a wrong answer on this device
    DEVICE_RECORD, // the stored device record cannot be used
    STORAGE,       // a record could not be written
    PORT_IN_USE,   // another program holds the v3 port
    NETWORK,       // the socket could not be opened
    IN_USE,        // another process of this app holds the records
    STORE_KEY,     // records exist but the key that opens them is missing
}

class Wfas3Exception(val failure: Wfas3Failure, message: String) : Exception(message)

/** Events of the engine. Called on one event thread, in order. Default: nothing. */
interface Wfas3Listener {
    fun onSessionUp(session: Int, pair: Int, config: Wfas3Config) {}
    fun onSessionDown(session: Int, pair: Int, reason: Int) {}
    fun onSuspect(pair: Int) {}
    fun onAttemptFailed(pair: Int) {}
    fun onRefused(pair: Int, status: Int, config: Wfas3Config?) {}
    fun onAttemptYielded(pair: Int) {}
    fun onStreamStart(session: Int) {}
    fun onStreamStop(session: Int) {}
    fun onStreamParams(session: Int, config: Wfas3Config, on: Boolean) {}
    fun onPeerSilent(session: Int) {}
    fun onPeerBack(session: Int) {}
    fun onForgetAsked(pair: Int) {}
    fun onRekeyed(session: Int) {}
    fun onPathChanged(session: Int) {}
    fun onFound(pair: Int, addr: Wfas3Addr) {}
    fun onNotFound(pair: Int) {}
    fun onBeacon(beacon: Wfas3Beacon) {}
    /** [code]: the 6-digit verification code; show it only in PIN mode. */
    fun onPairAsk(ask: Int, name: String, flags: Int, code: String) {}
    /** As above, with the address the client asks from (proved by its step 3). */
    fun onPairAsk(ask: Int, name: String, flags: Int, code: String, from: Wfas3Addr) { onPairAsk(ask, name, flags, code) }
    fun onPairCode(code: String) {}
    fun onPaired(pair: Int, name: String, flags: Int) {}
    fun onPairFailed(reason: Int, race: Boolean) {}
    fun onWindowClosed() {}
    fun onGroupJoined(session: Int) {}
    fun onGroupLeft(session: Int) {}
    fun onPairsChanged() {}
    fun onFatal(failure: Wfas3Failure) {}
}

/**
 * Audio delivered by the core. Called on the network thread while the
 * engine lock is held: copy what is needed and return at once.
 */
fun interface Wfas3AudioSink {
    fun onAudio(session: Int, stream: Int, epoch: Int, pos: Long, captureUs: Long, playoutUs: Long,
                data: ByteArray, off: Int, len: Int)
}

/** The code a server shows: typed text and QR content, or a PIN. */
class Wfas3WindowCode(val text: String, val qr: String?)

/**
 * Driver of the WFAS v3 core: owns the UDP socket, the clock, the storage
 * calls and the event thread. One lock serialises every call into the core.
 *
 * Only one port is open: UDP 14114 (plus the group port while this device is
 * a member of a multicast group). Nothing is sent unless the application
 * asks for it: there are no periodic announcements.
 */
class Wfas3Engine(
    private val store: Wfas3Store,
    deviceName: String,
) {
    private val lock = ReentrantLock()
    private var h = 0L
    private val outBuf = ByteArray(Wfas3Out.SIZE)
    private val out = Wfas3Out(outBuf)
    private val name = nameBytes(deviceName)

    private var channel: DatagramChannel? = null
    private var selector: Selector? = null
    private var ioThread: Thread? = null
    @Volatile private var running = false
    private val events: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wfas3-events").apply { isDaemon = true }
    }

    private class Group(val addr: Wfas3Addr, val ch: DatagramChannel, val key: MembershipKey?, val sessions: MutableSet<Int>)
    private val groups = HashMap<Wfas3Addr, Group>()

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Wfas3Listener>()
    @Volatile var audioSink: Wfas3AudioSink? = null

    fun addListener(l: Wfas3Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Wfas3Listener) { listeners.remove(l) }

    /** Run [r] on the event thread, after the events already posted. */
    fun post(r: Runnable) { try { events.execute(r) } catch (_: Exception) { } }

    /** Where broadcast datagrams go: directed broadcasts of the chosen interface, or the limited broadcast. */
    @Volatile var broadcastTargets: List<InetAddress> = emptyList()

    /**
     * The interface of the link: broadcasts, multicast and, on a link without
     * IPv4, the IPv6 port follow it. Null: [defaultMulticastInterface]. When
     * the link moves between IPv4 and IPv6 while the port is open, the port
     * is opened again for the new link.
     */
    @Volatile var multicastInterface: NetworkInterface? = null
        set(value) {
            field = value
            lock.withLock {
                try { if (value != null) channel?.setOption(StandardSocketOptions.IP_MULTICAST_IF, value) } catch (_: Exception) { }
                relink()
            }
        }

    /** Local address the port is bound to; null: every interface (needed for broadcasts). */
    @Volatile var bindAddress: InetAddress? = null

    // -- IPv6 ----------------------------------------------------------------
    //
    // A link with IPv4 is served exactly as before: one IPv4 socket, IPv4
    // broadcasts. A link without IPv4 (an IPv6-only network) gets one IPv6
    // socket instead, and what the core sends to "the local broadcast
    // address" goes to ff02::1 on that link (specification 12, wfas.h).
    // On IPv6 the port is kept to the link: a global IPv6 address can be
    // reachable from the Internet with no NAT in between, so only datagrams
    // from the link itself (link-local on this interface, or an address in
    // one of its on-link prefixes) reach the core; the rest are dropped
    // before any processing. Multicast and ff02::1 never leave the link
    // (hop limit 1, link scope).

    /** True while the port is open on a link without IPv4, over IPv6. */
    @Volatile private var v6Link = false
    /** The interface the IPv6 port serves (set with [v6Link]). */
    @Volatile private var link6: NetworkInterface? = null
    /** On-link IPv6 prefixes of [link6]: address bytes and prefix length. */
    @Volatile private var prefixes6: List<Pair<ByteArray, Int>> = emptyList()
    @Volatile private var prefixesAtMs = 0L
    /** On-link IPv4 subnets of the interfaces that are up, for pairing datagrams. */
    @Volatile private var prefixes4: List<Pair<ByteArray, Int>> = emptyList()
    @Volatile private var prefixes4AtMs = 0L
    /** The [notLocal4] that [prefixes4] was computed without (I/O thread only). */
    @Volatile private var prefixes4Without: Set<String> = emptySet()

    /**
     * Names of the interfaces whose IPv4 subnets are not the local network
     * for pairing datagrams (see fromLink4). The platform sets them: on
     * Android the mobile data interfaces, whose subnet can span the carrier
     * network. Empty (the default): every interface that is up counts. A new
     * set takes effect at the next pairing datagram.
     */
    @Volatile var notLocal4: Set<String> = emptySet()

    /** Whether the port is open on an IPv6-only link (multicast groups are then IPv6 too). */
    val ipv6Link: Boolean get() = v6Link

    val isRunning: Boolean get() = running

    // -- life cycle -----------------------------------------------------------

    private var port = W3.UDP_PORT

    /** Whether the UDP port is open now. */
    val isListening: Boolean get() = lock.withLock { channel != null }

    /**
     * Open or close the UDP port. Closed, the device cannot be reached at all
     * and sends nothing; the records stay loaded. Throws [Wfas3Exception] if
     * the port cannot be opened.
     */
    fun listen(on: Boolean) {
        lock.withLock {
            if (h == 0L) return
            if (on && channel == null) openSocket(port)
            if (!on && channel != null) {
                closeGroups()
                try { channel?.close() } catch (_: Exception) { }
                channel = null
                v6Link = false
                link6 = null
                prefixes6 = emptyList()
            }
            selector?.wakeup()
        }
    }

    /**
     * Start: load the records and, with [listen], open the port. Throws
     * [Wfas3Exception].
     */
    fun start(port: Int = W3.UDP_PORT, listen: Boolean = true) {
        lock.withLock {
            check(h == 0L) { "already started" }
            try { Wfas3Library.ensure() } catch (t: Throwable) {
                throw Wfas3Exception(Wfas3Failure.LIBRARY, "native library not available")
            }
            if (Wfas3Native.nSelftest() != W3.OK) throw Wfas3Exception(Wfas3Failure.SELFTEST, "self-test failed")
            val stored = try { store.loadDevice() } catch (e: Wfas3Exception) {
                throw e
            } catch (e: Exception) {
                throw Wfas3Exception(Wfas3Failure.DEVICE_RECORD, "device record unreadable")
            }
            if (stored != null && stored.size != W3.DEVICE_RECORD_LEN) {
                stored.fill(0)
                throw Wfas3Exception(Wfas3Failure.DEVICE_RECORD, "device record has a wrong length")
            }
            // A new device record only on a store without pairs: with pairs,
            // a fresh entry key and fresh counters would turn every healthy
            // pair suspect at its next handshake. Nothing is written.
            val pairsStored = stored == null && try { store.hasPairRecords() } catch (e: Wfas3Exception) {
                throw e
            } catch (_: Exception) { true }
            if (pairsStored) throw Wfas3Exception(Wfas3Failure.DEVICE_RECORD, "device record missing, pair records present")
            val rec = ByteArray(W3.DEVICE_RECORD_LEN)
            val handle = Wfas3Native.nCreate(stored, rec)
            stored?.fill(0)
            if (handle == 0L) {
                rec.fill(0)
                throw Wfas3Exception(Wfas3Failure.DEVICE_RECORD, "device record refused")
            }
            try {
                store.saveDevice(rec)
            } catch (e: Exception) {
                rec.fill(0)
                Wfas3Native.nDestroy(handle)
                throw Wfas3Exception(Wfas3Failure.STORAGE, "device record not stored")
            }
            rec.fill(0)
            h = handle
            // Until a role sets its own policy, every handshake is answered
            // "busy": a device that is not serving must not look connected.
            Wfas3Native.nSetPolicy(h, 1, 0, 0, Wfas3Config.pcm(W3.CF_SOURCE, 48000, 2, 16, 60).toArray())
            for (slot in 0 until W3.MAX_PAIRS) {
                val p = try { store.loadPair(slot) } catch (_: Exception) { null } ?: continue
                if (p.size == W3.PAIR_RECORD_LEN) Wfas3Native.nPairImport(h, slot, p)
                p.fill(0)
            }
            this.port = port
            try {
                selector = Selector.open()
                if (listen) openSocket(port)
            } catch (e: Wfas3Exception) {
                try { selector?.close() } catch (_: Exception) { }
                selector = null
                Wfas3Native.nDestroy(h); h = 0L
                throw e
            } catch (e: IOException) {
                Wfas3Native.nDestroy(h); h = 0L
                throw Wfas3Exception(Wfas3Failure.NETWORK, "selector not available")
            }
            running = true
            ioThread = Thread({ ioLoop() }, "wfas3-io").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    /** Close every session with BYE, stop, wipe the state. */
    fun stop() {
        val t: Thread?
        lock.withLock {
            if (h == 0L) return
            for (s in 0 until W3.MAX_SESSIONS) {
                if (Wfas3Native.nSessionClose(h, s, W3.BYE_NORMAL, outBuf) == W3.OK) handleOut()
            }
            for (slot in 0 until W3.MAX_PAIRS) {
                if (Wfas3Native.nFind(h, slot, 0, outBuf) == W3.OK) handleOut()
            }
            if (Wfas3Native.nWindowClose(h, outBuf) == W3.OK) handleOut()
            Wfas3Native.nPairCancel(h)
            // The BYE of each session goes at once and in its next two control
            // packets, 100 ms apart: let them out before the state is wiped.
            drainFor(250_000L)
            running = false
            t = ioThread
            ioThread = null
            selector?.wakeup()
        }
        try { t?.join(2000) } catch (_: InterruptedException) { }
        lock.withLock {
            Wfas3Native.nDestroy(h)
            h = 0L
            closeSockets()
            outBuf.fill(0)
        }
    }

    fun shutdown() {
        stop()
        events.shutdown()
        try { events.awaitTermination(1, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
    }

    // -- configuration --------------------------------------------------------

    /** Responder policy: busy, codecs accepted, channels, suggestion (status 3). */
    fun setPolicy(busy: Boolean, codecMask: Int, maxChannels: Int, suggest: Wfas3Config): Int = call {
        Wfas3Native.nSetPolicy(h, if (busy) 1 else 0, codecMask, maxChannels, suggest.toArray())
    }

    // -- pairs ----------------------------------------------------------------

    fun pairs(): List<Wfas3PairInfo> = lock.withLock {
        if (h == 0L) return emptyList()
        val b = ByteArray(48)
        val list = ArrayList<Wfas3PairInfo>()
        for (slot in 0 until W3.MAX_PAIRS) {
            if (Wfas3Native.nPairInfo(h, slot, b) != W3.OK || b[0].toInt() != 1) continue
            val nm = Wfas3Native.nNameClean(b.copyOfRange(16, 48)) ?: ""
            list.add(Wfas3PairInfo(slot, nm, b[2].toInt() and 0xFF, b[1].toInt() and 0xFF))
        }
        b.fill(0)
        list
    }

    fun pair(slot: Int): Wfas3PairInfo? = pairs().firstOrNull { it.slot == slot }

    /** Remove a pair: its sessions close with FORGET and BYE, the record is erased. */
    fun removePair(slot: Int): Int = callOut { Wfas3Native.nPairRemove(h, slot, outBuf) }

    fun hint(slot: Int): Wfas3Config? {
        val b = try { store.loadHint(slot) } catch (_: Exception) { null } ?: return null
        if (b.size != 20) return null
        val c = Wfas3Config.read(ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN), 0)
        return if (c.isValid()) c else null
    }

    fun saveHint(slot: Int, c: Wfas3Config?) {
        try {
            if (c == null) { store.saveHint(slot, null); return }
            val bb = ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.put(c.flags.toByte()).put(c.codec.toByte()).put(c.channels.toByte()).put(c.bits.toByte())
            bb.putInt(c.sampleRate).putInt(c.frameSamples).putInt(c.payloadBytes).putInt(c.latencyMs)
            store.saveHint(slot, bb.array())
        } catch (_: Exception) { }
    }

    // -- discovery and sessions -----------------------------------------------

    fun find(slot: Int, on: Boolean = true): Int = callOut { Wfas3Native.nFind(h, slot, if (on) 1 else 0, outBuf) }

    fun connect(slot: Int, addr: Wfas3Addr, config: Wfas3Config): Int =
        callOut { Wfas3Native.nConnect(h, slot, addr.ip, addr.port, config.toArray(), outBuf) }

    fun closeSession(session: Int, reason: Int = W3.BYE_NORMAL): Int =
        callOut { Wfas3Native.nSessionClose(h, session, reason, outBuf) }

    fun streamWant(session: Int, want: Boolean): Int = call { Wfas3Native.nStreamWant(h, session, if (want) 1 else 0) }

    fun streamParams(session: Int, config: Wfas3Config): Int = call { Wfas3Native.nStreamParams(h, session, config.toArray()) }

    fun micOff(session: Int): Int = call { Wfas3Native.nMicOff(h, session) }

    fun audioLate(session: Int, lateUs: Long): Int = call { Wfas3Native.nAudioLate(h, session, lateUs) }

    fun setBufferMs(session: Int, ms: Int): Int = call { Wfas3Native.nSetBufferMs(h, session, ms) }

    fun sessionInfo(session: Int): Wfas3SessionInfo? = lock.withLock {
        if (h == 0L) return null
        val v = LongArray(40)
        if (Wfas3Native.nSessionInfo(h, session, v) != W3.OK || v[0] == 0L) return null
        Wfas3SessionInfo(
            session = session, pair = v[1].toInt(), initiator = v[2] != 0L, source = v[3] != 0L,
            microphone = v[4] != 0L, synchronised = v[5] != 0L, txOn = v[9] != 0L, txStarted = v[10] != 0L,
            peerReceived = v[12], peerLost = v[13], peerJitterUs = v[14], peerBufferMs = v[15],
            rxOn = v[17] != 0L, rxReceived = v[20], rxLost = v[21], rxJitterUs = v[22],
            timeValid = v[23] != 0L, offsetUs = v[24], delayMs = v[25], rekeys = v[26], silent = v[29] != 0L,
        )
    }

    /** Send one packet of audio on a session. Returns the core status. */
    fun sendAudio(session: Int, pos: Long, captureUs: Long, data: ByteArray, off: Int, len: Int): Int =
        callOut { Wfas3Native.nSendAudio(h, session, pos, captureUs, data, off, len, outBuf) }

    // -- multicast ------------------------------------------------------------

    fun groupOpen(config: Wfas3Config, addr: Wfas3Addr): Int = call { Wfas3Native.nGroupOpen(h, config.toArray(), addr.ip, addr.port) }

    fun groupClose(): Int = call { Wfas3Native.nGroupClose(h) }

    fun groupMembers(): Int = lock.withLock { if (h == 0L) 0 else Wfas3Native.nGroupMembers(h) }

    fun groupSend(pos: Long, captureUs: Long, data: ByteArray, off: Int, len: Int): Int =
        callOut { Wfas3Native.nGroupSend(h, pos, captureUs, data, off, len, outBuf) }

    // -- pairing --------------------------------------------------------------

    /**
     * Server: open a pairing window ([kind] PAIR_CODE or PAIR_PIN). With
     * [ask] every request needs the user's answer. [perms] are those of a
     * window without asking. Returns the code to show, or null.
     */
    fun openWindow(kind: Int, ask: Boolean, perms: Int): Wfas3WindowCode? = lock.withLock {
        if (h == 0L) return null
        val texts = arrayOfNulls<String>(2)
        val st = Wfas3Native.nWindowOpen(h, kind, name, if (ask) 1 else 0, perms, texts, outBuf)
        if (st != W3.OK) return null
        if (!handleOut()) return null
        pollAll()
        selector?.wakeup()
        val t = texts[0] ?: return null
        Wfas3WindowCode(t, texts[1])
    }

    fun closeWindow(): Int = callOut { Wfas3Native.nWindowClose(h, outBuf) }

    /** Server: answer a PAIR_ASK. */
    fun answer(ask: Int, yes: Boolean, perms: Int, guest: Boolean): Int =
        callOut { Wfas3Native.nPairAnswer(h, ask, if (yes) 1 else 0, perms, if (guest) 1 else 0, outBuf) }

    /** Server: the reason of the last attempt that failed in the window, or -1. */
    fun windowFailed(): Int = lock.withLock { if (h == 0L) -1 else Wfas3Native.nWindowFailed(h) }

    /** Client, PIN mode: report the PairBeacons heard. */
    fun beacons(on: Boolean): Int = call { Wfas3Native.nBeacons(h, if (on) 1 else 0) }

    fun codeValid(text: String): Boolean = Companion.codeValid(text)

    /**
     * Client: pair with a code (typed, scanned, or the label of a headless
     * device). The server plays to this device; it may receive this device's
     * audio only with [allowReceive]: by default a pairing made to listen does
     * not let the other side pull the audio of this computer.
     */
    fun pairWithCode(code: String, guest: Boolean, mic: Boolean, replace: Int = -1, allowReceive: Boolean = false): Int {
        val flags = (if (guest) W3.PAIR_GUEST else 0) or (if (mic) W3.PAIR_MIC else 0)
        val perms = (if (allowReceive) W3.PERM_RECEIVE else 0) or (if (mic) W3.PERM_MICROPHONE else 0)
        return callOut { Wfas3Native.nPairStart(h, W3.PAIR_CODE, code, null, null, name, flags, perms, replace, outBuf) }
    }

    /** Client: pair in PIN mode with the beacon the user chose and the PIN typed; see [pairWithCode]. */
    fun pairWithPin(beacon: Wfas3Beacon, pin: String, guest: Boolean, mic: Boolean, replace: Int = -1,
                    allowReceive: Boolean = false): Int {
        if (pin.length != 8 || pin.any { it !in '0'..'9' }) return W3.E_ARG
        val p = ByteArray(8) { pin[it].code.toByte() }
        val flags = (if (guest) W3.PAIR_GUEST else 0) or (if (mic) W3.PAIR_MIC else 0)
        val perms = (if (allowReceive) W3.PERM_RECEIVE else 0) or (if (mic) W3.PERM_MICROPHONE else 0)
        return try {
            callOut { Wfas3Native.nPairStart(h, W3.PAIR_PIN, null, p, beacon.raw, name, flags, perms, replace, outBuf) }
        } finally { p.fill(0) }
    }

    /** Client, PIN mode: the user compared the 6-digit codes. */
    fun confirm(yes: Boolean): Int = callOut { Wfas3Native.nPairConfirm(h, if (yes) 1 else 0, outBuf) }

    fun cancelPairing(): Int = call { Wfas3Native.nPairCancel(h) }

    // -- internals: calls -----------------------------------------------------

    private inline fun call(f: () -> Int): Int = lock.withLock {
        if (h == 0L) return W3.E_STATE
        val st = f()
        selector?.wakeup()
        st
    }

    private inline fun callOut(f: () -> Int): Int = lock.withLock {
        if (h == 0L) return W3.E_STATE
        val st = f()
        if (st == W3.OK) handleOut()
        selector?.wakeup()
        st
    }

    // -- internals: network ---------------------------------------------------

    private fun openSocket(port: Int) {
        val sel = selector ?: throw Wfas3Exception(Wfas3Failure.NETWORK, "not started")
        // A link without IPv4 is served over IPv6; every other link exactly as before.
        val ifc = fresh(multicastInterface) ?: defaultMulticastInterface()
        if (bindAddress == null && ifc != null && ipv6Only(ifc)) {
            openSocket6(sel, port, ifc)
            return
        }
        v6Link = false
        link6 = null
        val ch = try {
            DatagramChannel.open(StandardProtocolFamily.INET)
        } catch (e: Exception) {
            throw Wfas3Exception(Wfas3Failure.NETWORK, "socket not available")
        }
        try {
            ch.setOption(StandardSocketOptions.SO_REUSEADDR, false)
            ch.setOption(StandardSocketOptions.SO_BROADCAST, true)
            ch.setOption(StandardSocketOptions.SO_RCVBUF, 1 shl 20)
            ch.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 1)
            ch.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true)
            multicastInterface?.let { runCatching { ch.setOption(StandardSocketOptions.IP_MULTICAST_IF, it) } }
            // An IPv4 socket takes an IPv4 address only: the wildcard of the
            // platform is not one on Android ("::"), so it is named here.
            ch.bind(InetSocketAddress(bindAddress?.takeIf { it is Inet4Address } ?: ANY_V4, port))
            ch.configureBlocking(false)
            // Some selector implementations make register wait for a select in
            // progress: wake it first so that wait is short.
            sel.wakeup()
            ch.register(sel, SelectionKey.OP_READ)
            channel = ch
        } catch (e: java.net.BindException) {
            try { ch.close() } catch (_: Exception) { }
            throw Wfas3Exception(Wfas3Failure.PORT_IN_USE, "port in use")
        } catch (e: Exception) {
            // Any failure, run-time ones of the platform included, leaves no socket behind.
            try { ch.close() } catch (_: Exception) { }
            throw Wfas3Exception(Wfas3Failure.NETWORK, "socket not available")
        }
    }

    /** The IPv6 socket of a link without IPv4: every IPv6 address of the device, kept to the link by [fromLink]. */
    private fun openSocket6(sel: Selector, port: Int, ifc: NetworkInterface) {
        val ch = try {
            DatagramChannel.open(StandardProtocolFamily.INET6)
        } catch (e: Exception) {
            // IPv6 not available on this system: the link cannot be served at all.
            throw Wfas3Exception(Wfas3Failure.NETWORK, "IPv6 socket not available")
        }
        try {
            ch.setOption(StandardSocketOptions.SO_REUSEADDR, false)
            ch.setOption(StandardSocketOptions.SO_RCVBUF, 1 shl 20)
            // For an IPv6 socket this is the hop limit: ff02::1 and groups stay on the link.
            ch.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 1)
            ch.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true)
            ch.setOption(StandardSocketOptions.IP_MULTICAST_IF, ifc)
            ch.bind(InetSocketAddress(ANY_V6, port))
            ch.configureBlocking(false)
            sel.wakeup()
            ch.register(sel, SelectionKey.OP_READ)
            channel = ch
            link6 = ifc
            prefixes6 = onLinkPrefixes(ifc)
            prefixesAtMs = System.currentTimeMillis()
            v6Link = true
            Wfas3Trace.log { "port open over IPv6 on ${ifc.name} (a link without IPv4), ${prefixes6.size} on-link prefixes" }
        } catch (e: java.net.BindException) {
            try { ch.close() } catch (_: Exception) { }
            throw Wfas3Exception(Wfas3Failure.PORT_IN_USE, "port in use")
        } catch (e: Exception) {
            try { ch.close() } catch (_: Exception) { }
            throw Wfas3Exception(Wfas3Failure.NETWORK, "socket not available")
        }
    }

    /**
     * The link changed (lock held): if the open port no longer fits it (IPv4
     * against IPv6, or another IPv6 interface), open it again for the new one.
     * Groups are left: their sessions follow the new path or end.
     */
    private fun relink() {
        val ch = channel ?: return
        if (h == 0L || bindAddress != null) return
        val ifc = fresh(multicastInterface) ?: defaultMulticastInterface()
        val want6 = ifc != null && ipv6Only(ifc)
        if (want6 == v6Link && (!want6 || ifc?.index == link6?.index)) {
            if (want6 && ifc != null) { prefixes6 = onLinkPrefixes(ifc); prefixesAtMs = System.currentTimeMillis() }
            return
        }
        Wfas3Trace.log { "link changed: port reopened for ${if (want6) "IPv6 on ${ifc?.name}" else "IPv4"}" }
        closeGroups()
        try { ch.close() } catch (_: Exception) { }
        channel = null
        v6Link = false
        link6 = null
        try { openSocket(port) } catch (_: Exception) {
            // Not available on the new link: the port stays closed, as after a failed listen.
        }
        selector?.wakeup()
    }

    /**
     * Whether a datagram from [a] comes from the IPv6 link this port serves:
     * link-local on its interface, or an address in one of its on-link
     * prefixes. IPv4 (another interface, through the dual-stack socket),
     * loopback, multicast and everything routed in from elsewhere: no.
     */
    private fun fromLink(a: InetAddress): Boolean {
        val ifc = link6 ?: return false
        if (onLink6(a, ifc.index, prefixes6)) return true
        if (a !is Inet6Address || a.isLinkLocalAddress) return false
        // Addresses come and go (SLAAC, privacy addresses): look again, at most every 5 s.
        val now = System.currentTimeMillis()
        if (now - prefixesAtMs < 5_000L) return false
        prefixesAtMs = now
        prefixes6 = onLinkPrefixes(fresh(ifc) ?: ifc)
        return onLink6(a, ifc.index, prefixes6)
    }

    /**
     * Whether an IPv4 source is on one of this computer's links: loopback,
     * link-local, or in the subnet of an address of an interface that is up
     * and not in [notLocal4] (prefixes /8 to /32). Used for pairing datagrams
     * only.
     */
    private fun fromLink4(a: InetAddress): Boolean {
        if (a !is Inet4Address) return false
        if (a.isLoopbackAddress || a.isLinkLocalAddress) return true
        val without = notLocal4
        val same = without == prefixes4Without
        if (same && inPrefixes4(a.address, prefixes4)) return true
        val now = System.currentTimeMillis()
        if (same && now - prefixes4AtMs < 5_000L) return false
        prefixes4AtMs = now
        prefixes4 = onLinkPrefixes4(without)
        prefixes4Without = without
        return inPrefixes4(a.address, prefixes4)
    }

    /** [raw] as a destination on the IPv6 link: link-local and link-scope multicast get the link's interface. */
    private fun toLink6(raw: ByteArray): InetAddress? {
        val ifc = link6 ?: return null
        if (isMapped(raw)) return null
        return if (linkScope6(raw)) Inet6Address.getByAddress(null, raw, ifc) else InetAddress.getByAddress(raw)
    }

    private fun closeGroups() {
        for (g in groups.values) {
            try { g.key?.drop() } catch (_: Exception) { }
            try { g.ch.close() } catch (_: Exception) { }
        }
        groups.clear()
    }

    private fun closeSockets() {
        closeGroups()
        try { channel?.close() } catch (_: Exception) { }
        try { selector?.close() } catch (_: Exception) { }
        channel = null
        selector = null
        v6Link = false
        link6 = null
        prefixes6 = emptyList()
    }

    private fun ioLoop() {
        val rx = ByteBuffer.allocate(2048)
        val ip = ByteArray(16)
        while (running) {
            try {
                val sel = selector ?: break
                val waitMs = lock.withLock {
                    if (h == 0L) 0L else {
                        val d = Wfas3Native.nNextDeadline(h)
                        val now = Wfas3Native.nClockUs()
                        ((d - now + 999L) / 1000L).coerceIn(0L, 500L)
                    }
                }
                if (waitMs <= 0L) sel.selectNow() else sel.select(waitMs)
                if (!running) break
                val keys = sel.selectedKeys()
                val it = keys.iterator()
                while (it.hasNext()) {
                    val k = it.next()
                    it.remove()
                    if (!k.isValid || !k.isReadable) continue
                    val ch = k.channel() as DatagramChannel
                    var n = 0
                    while (n < 256) {
                        rx.clear()
                        val from = try { ch.receive(rx) } catch (_: IOException) { null } ?: break
                        n++
                        val isa = from as? InetSocketAddress ?: continue
                        val len = rx.position()
                        if (len <= 0 || len > W3.MAX_DATAGRAM) continue
                        val a = isa.address ?: continue
                        val type = rx.array()[1]
                        // A group socket is bound to its port on every address:
                        // only multicast datagrams are taken from it, so that it
                        // is never a second way into the core.
                        if (ch !== channel && type != T_MULTICAST) continue
                        // On IPv6 only the link itself is heard (see fromLink).
                        if (v6Link && !fromLink(a)) continue
                        // Pairing happens on the link: over IPv4, beacons and
                        // pairing steps come only from an on-link subnet.
                        if (!v6Link && (type == T_PAIR_BEACON || type == T_PAIR) && !fromLink4(a)) continue
                        toMapped(a, ip)
                        lock.withLock {
                            if (h != 0L && Wfas3Native.nReceive(h, rx.array(), len, ip, isa.port, outBuf) == W3.OK) handleOut()
                        }
                    }
                }
                lock.withLock { if (h != 0L) pollAll() }
            } catch (t: Throwable) {
                if (!running) break
                try { Thread.sleep(5) } catch (_: InterruptedException) { }
            }
        }
    }

    /** Poll the core until it has nothing to do (lock held). */
    private fun pollAll() {
        var k = 0
        while (k < 256 && h != 0L) {
            val st = Wfas3Native.nPoll(h, outBuf)
            if (st != W3.OK || out.isIdle()) break
            if (!handleOut()) break
            k++
        }
    }

    /** Keep the core running for [us] microseconds (lock held), to let closing packets out. */
    private fun drainFor(us: Long) {
        val end = Wfas3Native.nClockUs() + us
        while (Wfas3Native.nClockUs() < end) {
            pollAll()
            try { Thread.sleep(10) } catch (_: InterruptedException) { break }
        }
    }

    private fun send(off: Int, len: Int, to: Wfas3Addr, broadcast: Boolean) {
        val ch = channel ?: return
        if (v6Link) { send6(ch, off, len, to, broadcast); return }
        try {
            if (broadcast) {
                // An IPv4 socket reaches IPv4 addresses only.
                val targets = broadcastTargets.filter { it is Inet4Address }.ifEmpty { listOf(LIMITED_BROADCAST) }
                for (t in targets) sendOne(ch, off, len, InetSocketAddress(t, to.port))
            } else {
                if (!to.isV4Mapped()) return
                sendOne(ch, off, len, InetSocketAddress(InetAddress.getByAddress(to.v4()), to.port))
            }
        } catch (_: Exception) {
            // A datagram that cannot leave is a lost datagram: the protocol copes.
        }
    }

    /**
     * On a link without IPv4: "the local broadcast address" is ff02::1 on the
     * link, with any IPv6 address the user typed; IPv4 destinations are dropped.
     */
    private fun send6(ch: DatagramChannel, off: Int, len: Int, to: Wfas3Addr, broadcast: Boolean) {
        val ifc = link6 ?: return
        try {
            if (broadcast) {
                sendOne(ch, off, len, InetSocketAddress(Inet6Address.getByAddress(null, ALL_NODES, ifc), to.port))
                for (t in broadcastTargets) {
                    if (t !is Inet6Address || t.isMulticastAddress) continue
                    val dest = if (t.scopeId != 0) t else (toLink6(t.address) ?: continue)
                    sendOne(ch, off, len, InetSocketAddress(dest, to.port))
                }
            } else {
                val dest = toLink6(to.ip) ?: return
                sendOne(ch, off, len, InetSocketAddress(dest, to.port))
            }
        } catch (_: Exception) {
            // A datagram that cannot leave is a lost datagram: the protocol copes.
        }
    }

    /** One datagram; one destination that refuses it does not stop the next. */
    private fun sendOne(ch: DatagramChannel, off: Int, len: Int, to: InetSocketAddress) {
        try { ch.send(ByteBuffer.wrap(outBuf, off, len), to) } catch (_: IOException) { }
    }

    // -- internals: output ----------------------------------------------------

    /**
     * Act on one output (lock held): storage first, then the datagram, then
     * the events. Returns false if a store failed: the datagram is then not
     * sent and the engine stops.
     */
    private fun handleOut(): Boolean {
        val ev = out.events
        val pair = out.pair
        val session = out.session
        if ((ev and W3.EV_STORE_DEVICE) != 0) {
            val rec = ByteArray(W3.DEVICE_RECORD_LEN)
            try {
                if (Wfas3Native.nDeviceExport(h, rec) == W3.OK) store.saveDevice(rec)
            } catch (e: Exception) {
                rec.fill(0)
                fatal(Wfas3Failure.STORAGE)
                return false
            }
            rec.fill(0)
        }
        if ((ev and W3.EV_STORE_PAIR) != 0 && pair >= 0) {
            val rec = ByteArray(W3.PAIR_RECORD_LEN)
            try {
                if (Wfas3Native.nPairExport(h, pair, rec) == W3.OK) store.savePair(pair, rec)
            } catch (e: Exception) {
                rec.fill(0)
                fatal(Wfas3Failure.STORAGE)
                return false
            }
            rec.fill(0)
        }
        if ((ev and W3.EV_PAIR_ERASED) != 0 && pair >= 0) {
            try {
                store.erasePair(pair)
                store.saveHint(pair, null)
            } catch (e: Exception) {
                fatal(Wfas3Failure.STORAGE)
                return false
            }
        }
        val len = out.len
        if (len > 0) send(Wfas3Out.DGRAM, len, out.to, out.broadcast)

        if ((ev and W3.EV_AUDIO) != 0) {
            val sink = audioSink
            val alen = out.audioLen
            if (sink != null && alen > 0) {
                try {
                    sink.onAudio(session, out.stream, out.epoch, out.samplePos, out.captureUs, out.playoutUs,
                        outBuf, Wfas3Out.AUDIO, alen)
                } catch (_: Throwable) { }
            }
        }
        if ((ev and W3.EV_GROUP_JOIN) != 0) groupJoin(out.addr, session)
        if ((ev and W3.EV_GROUP_LEAVE) != 0) groupLeave(out.addr, session)
        if ((ev and W3.EV_SESSION_DOWN) != 0) {
            // A session that goes down leaves any group it joined.
            val gone = groups.values.filter { it.sessions.contains(session) }.map { it.addr }
            for (g in gone) groupLeave(g, session)
        }

        val rest = ev and (W3.EV_STORE_DEVICE or W3.EV_STORE_PAIR or W3.EV_AUDIO).inv()
        if (rest != 0) dispatch(rest)
        return true
    }

    /** Copy what the listener needs out of the shared buffer and post it. */
    private fun dispatch(ev: Int) {
        if (Wfas3Trace.on && (ev and TRACED) != 0) {
            Wfas3Trace.log { "event ${Wfas3Trace.events(ev and TRACED)} session=${out.session} pair=${out.pair} status=${out.ostatus}" }
        }
        if (listeners.isEmpty()) return
        val pair = out.pair
        val session = out.session
        val status = out.ostatus
        val config = out.config
        val addr = out.addr
        val streamOn = out.streamOn
        val code = out.code
        val ask = out.ask
        val pflags = out.pflags
        val rawName = out.rawName()
        val rawBeacon = if ((ev and W3.EV_BEACON) != 0) out.rawBeacon() else null
        post {
            for (l in listeners) try {
                val nm = if ((ev and (W3.EV_PAIR_ASK or W3.EV_PAIRED)) != 0) Wfas3Native.nNameClean(rawName) ?: "" else ""
                if ((ev and W3.EV_PAIRED) != 0) { l.onPaired(pair, nm, pflags); l.onPairsChanged() }
                if ((ev and W3.EV_SESSION_DOWN) != 0) l.onSessionDown(session, pair, status)
                if ((ev and W3.EV_SESSION_UP) != 0) l.onSessionUp(session, pair, config)
                if ((ev and W3.EV_SUSPECT) != 0) { l.onSuspect(pair); l.onPairsChanged() }
                if ((ev and W3.EV_REFUSED) != 0) l.onRefused(pair, status, if (status == W3.ST_UNSUPPORTED && config.isValid()) config else null)
                if ((ev and W3.EV_ATTEMPT_YIELDED) != 0) l.onAttemptYielded(pair)
                if ((ev and W3.EV_ATTEMPT_FAILED) != 0) l.onAttemptFailed(pair)
                if ((ev and W3.EV_STREAM_PARAMS) != 0) l.onStreamParams(session, config, streamOn)
                if ((ev and W3.EV_STREAM_START) != 0) l.onStreamStart(session)
                if ((ev and W3.EV_STREAM_STOP) != 0) l.onStreamStop(session)
                if ((ev and W3.EV_PEER_SILENT) != 0) l.onPeerSilent(session)
                if ((ev and W3.EV_PEER_BACK) != 0) l.onPeerBack(session)
                if ((ev and W3.EV_PAIR_ERASED) != 0) l.onPairsChanged()
                if ((ev and W3.EV_FORGET_ASKED) != 0) l.onForgetAsked(pair)
                if ((ev and W3.EV_REKEYED) != 0) l.onRekeyed(session)
                if ((ev and W3.EV_PATH_CHANGED) != 0) l.onPathChanged(session)
                if ((ev and W3.EV_FOUND) != 0) l.onFound(pair, addr)
                if ((ev and W3.EV_NOT_FOUND) != 0) l.onNotFound(pair)
                if (rawBeacon != null) {
                    val bnm = Wfas3Native.nNameClean(rawBeacon.copyOfRange(40, 72)) ?: ""
                    val ba = Wfas3Addr(rawBeacon.copyOfRange(0, 16),
                        ((rawBeacon[16].toInt() and 0xFF) or ((rawBeacon[17].toInt() and 0xFF) shl 8)))
                    l.onBeacon(Wfas3Beacon(rawBeacon, bnm, ba))
                }
                if ((ev and W3.EV_PAIR_ASK) != 0) l.onPairAsk(ask, nm, pflags, "%06d".format(code), addr)
                if ((ev and W3.EV_PAIR_CODE) != 0) l.onPairCode("%06d".format(code))
                if ((ev and W3.EV_PAIR_FAILED) != 0) l.onPairFailed(status, (ev and W3.EV_PAIR_RACE) != 0)
                if ((ev and W3.EV_WINDOW_CLOSED) != 0) l.onWindowClosed()
                if ((ev and W3.EV_GROUP_JOIN) != 0) l.onGroupJoined(session)
                if ((ev and W3.EV_GROUP_LEAVE) != 0) l.onGroupLeft(session)
            } catch (_: Throwable) { }
        }
    }

    private fun fatal(f: Wfas3Failure) {
        running = false
        post { for (l in listeners) try { l.onFatal(f) } catch (_: Throwable) { } }
    }

    private fun groupJoin(a: Wfas3Addr, session: Int) {
        groups[a]?.let { it.sessions.add(session); return }
        val sel = selector ?: return
        try {
            val v4 = a.isV4Mapped()
            // An IPv6 group only on the IPv6 link, with its interface; an IPv4 one as before.
            if (!v4 && !v6Link) return
            val group = if (v4) InetAddress.getByAddress(a.v4()) else (toLink6(a.ip) ?: return)
            if (!group.isMulticastAddress) return
            val ch = DatagramChannel.open(if (v4) StandardProtocolFamily.INET else StandardProtocolFamily.INET6)
            try {
                ch.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                ch.setOption(StandardSocketOptions.SO_RCVBUF, 1 shl 20)
                ch.bind(InetSocketAddress(if (v4) ANY_V4 else ANY_V6, a.port))
                val ifc = if (v4) (multicastInterface ?: defaultMulticastInterface()) else link6
                val key = if (ifc != null) ch.join(group, ifc) else null
                if (key == null) { ch.close(); return }
                ch.configureBlocking(false)
                sel.wakeup()
                ch.register(sel, SelectionKey.OP_READ)
                groups[a] = Group(a, ch, key, mutableSetOf(session))
            } catch (e: Exception) {
                try { ch.close() } catch (_: Exception) { }
                throw e
            }
        } catch (_: Exception) {
            // Without the group the session still works for control; the
            // source sees no GROUP_ACK and resends what it can.
        }
    }

    private fun groupLeave(a: Wfas3Addr, session: Int) {
        val g = groups[a] ?: return
        g.sessions.remove(session)
        if (g.sessions.isNotEmpty()) return
        groups.remove(a)
        try { g.key?.drop() } catch (_: Exception) { }
        try { g.ch.close() } catch (_: Exception) { }
    }

    companion object {
        private val LIMITED_BROADCAST: InetAddress = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
        private val ANY_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
        private val ANY_V6: InetAddress = InetAddress.getByAddress(ByteArray(16))
        /** ff02::1, all the nodes of the link. */
        private val ALL_NODES: ByteArray = ByteArray(16).also { it[0] = 0xFF.toByte(); it[1] = 0x02; it[15] = 0x01 }

        /**
         * The source check of the IPv6 port: [a] is link-local on the
         * interface [ifIndex], or in one of [prefixes]; never IPv4,
         * IPv4-mapped, loopback, multicast or unspecified.
         */
        internal fun onLink6(a: InetAddress, ifIndex: Int, prefixes: List<Pair<ByteArray, Int>>): Boolean {
            if (a !is Inet6Address) return false
            val raw = a.address
            if (raw.size != 16 || isMapped(raw)) return false
            if (a.isLoopbackAddress || a.isMulticastAddress || a.isAnyLocalAddress) return false
            if (a.isLinkLocalAddress) return a.scopeId == ifIndex
            return inPrefixes(raw, prefixes)
        }

        /** Whether [raw] needs the link's interface to be sent to: fe80::/10, or multicast of scope 0..2. */
        internal fun linkScope6(raw: ByteArray): Boolean =
            (raw[0] == 0xFE.toByte() && (raw[1].toInt() and 0xC0) == 0x80) ||
                (raw[0] == 0xFF.toByte() && (raw[1].toInt() and 0x0F) <= 2)

        internal fun isMapped(raw: ByteArray): Boolean {
            if (raw.size != 16) return false
            for (i in 0 until 10) if (raw[i] != 0.toByte()) return false
            return raw[10] == 0xFF.toByte() && raw[11] == 0xFF.toByte()
        }

        /** An IPv4 address that makes a link an IPv4 link: not loopback, not the 192.0.0.0/29 of 464XLAT (CLAT). */
        internal fun realIpv4(a: InetAddress): Boolean {
            if (a !is Inet4Address || a.isLoopbackAddress) return false
            val b = a.address
            return !(b[0] == 192.toByte() && b[1] == 0.toByte() && b[2] == 0.toByte() && (b[3].toInt() and 0xF8) == 0)
        }

        /**
         * [n] as it is now: a NetworkInterface is a snapshot, and its
         * addresses change while the app runs. Null if it is gone or down.
         */
        private fun fresh(n: NetworkInterface?): NetworkInterface? = try {
            if (n == null) null
            else (NetworkInterface.getByIndex(n.index) ?: NetworkInterface.getByName(n.name))?.takeIf { it.isUp }
        } catch (_: Exception) { null }

        /** Whether [n] is a link without IPv4 but with IPv6: it is then served over IPv6. */
        fun ipv6Only(n: NetworkInterface): Boolean = try {
            ipv6OnlyOf(n.inetAddresses.toList())
        } catch (_: Exception) { false }

        internal fun ipv6OnlyOf(all: List<InetAddress>): Boolean =
            all.none { realIpv4(it) } && all.any { it is Inet6Address && !it.isLoopbackAddress }

        private const val T_PAIR_BEACON: Byte = 0x03
        private const val T_PAIR: Byte = 0x13
        private const val T_MULTICAST: Byte = 0x30

        /** The IPv4 subnets of every interface that is up, loopback and [excluded] left out. */
        private fun onLinkPrefixes4(excluded: Set<String>): List<Pair<ByteArray, Int>> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback && it.name !in excluded }.getOrDefault(false) }
                .flatMap { n -> n.interfaceAddresses.map { it.address to it.networkPrefixLength.toInt() } }
                .let { prefixesOf4(it) }
        } catch (_: Exception) { emptyList() }

        internal fun prefixesOf4(list: List<Pair<InetAddress?, Int>>): List<Pair<ByteArray, Int>> = list.mapNotNull { (ad, len) ->
            if (ad !is Inet4Address || ad.isLoopbackAddress || ad.isAnyLocalAddress) return@mapNotNull null
            // Shorter than /8 is not a link.
            if (len !in 8..32) return@mapNotNull null
            ad.address to len
        }

        internal fun inPrefixes4(raw: ByteArray, list: List<Pair<ByteArray, Int>>): Boolean {
            if (raw.size != 4) return false
            for ((p, len) in list) {
                if (p.size != 4) continue
                val mask = if (len >= 32) -1 else ((-1) shl (32 - len))
                val x = java.nio.ByteBuffer.wrap(raw).int
                val y = java.nio.ByteBuffer.wrap(p).int
                if ((x and mask) == (y and mask)) return true
            }
            return false
        }

        /** The on-link IPv6 prefixes of [n] (link-local excluded: it is checked by scope). */
        private fun onLinkPrefixes(n: NetworkInterface): List<Pair<ByteArray, Int>> = try {
            prefixesOf(n.interfaceAddresses.map { it.address to it.networkPrefixLength.toInt() })
        } catch (_: Exception) { emptyList() }

        internal fun prefixesOf(list: List<Pair<InetAddress?, Int>>): List<Pair<ByteArray, Int>> = list.mapNotNull { (ad, len) ->
            if (ad !is Inet6Address || ad.isLinkLocalAddress || ad.isLoopbackAddress || ad.isMulticastAddress) return@mapNotNull null
            // Shorter than /48 is not a link: a whole provider block is never treated as local.
            if (len !in 48..128) return@mapNotNull null
            ad.address to len
        }

        internal fun inPrefixes(raw: ByteArray, list: List<Pair<ByteArray, Int>>): Boolean {
            for ((p, len) in list) {
                var ok = true
                var bits = len
                var i = 0
                while (bits > 0 && i < 16) {
                    val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                    if ((raw[i].toInt() and mask) != (p[i].toInt() and mask)) { ok = false; break }
                    bits -= 8
                    i++
                }
                if (ok) return true
            }
            return false
        }
        /** Events written to the trace (not audio, beacons or found/not found). */
        private const val TRACED = W3.EV_SESSION_UP or W3.EV_SESSION_DOWN or W3.EV_SUSPECT or W3.EV_ATTEMPT_FAILED or
            W3.EV_REFUSED or W3.EV_ATTEMPT_YIELDED or W3.EV_STREAM_START or W3.EV_STREAM_STOP or W3.EV_STREAM_PARAMS or
            W3.EV_PEER_SILENT or W3.EV_PEER_BACK or W3.EV_PAIR_ERASED or W3.EV_FORGET_ASKED or W3.EV_REKEYED or
            W3.EV_PATH_CHANGED or W3.EV_FOUND or W3.EV_NOT_FOUND or W3.EV_PAIRED or W3.EV_PAIR_FAILED or W3.EV_WINDOW_CLOSED or W3.EV_GROUP_JOIN or W3.EV_GROUP_LEAVE

        /** Microseconds on the core clock (keeps counting in suspend). */
        fun clockUs(): Long = Wfas3Native.nClockUs()

        /** Whether [text] is a well-formed pairing code (typed, QR, or label). Nothing is kept. */
        fun codeValid(text: String): Boolean = try {
            Wfas3Library.ensure()
            text.length in 1..80 && Wfas3Native.nCodeValid(text)
        } catch (_: Throwable) { false }

        /** The declared name: UTF-8, cut at a character boundary, zero padded to 32 bytes. */
        fun nameBytes(s: String): ByteArray {
            val outB = ByteArray(W3.NAME_LEN)
            var used = 0
            for (ch in s.codePoints().toArray()) {
                if (ch < 0x20 || ch == 0x7F) continue
                val enc = String(Character.toChars(ch)).toByteArray(Charsets.UTF_8)
                if (used + enc.size > W3.NAME_LEN) break
                System.arraycopy(enc, 0, outB, used, enc.size)
                used += enc.size
            }
            return outB
        }

        private fun toMapped(a: InetAddress, ip: ByteArray) {
            val raw = a.address
            if (raw.size == 4) {
                java.util.Arrays.fill(ip, 0, 10, 0)
                ip[10] = 0xFF.toByte(); ip[11] = 0xFF.toByte()
                System.arraycopy(raw, 0, ip, 12, 4)
            } else {
                System.arraycopy(raw, 0, ip, 0, 16)
            }
        }

        /**
         * The first up, non-loopback interface with IPv4 that supports
         * multicast; with none (an IPv6-only network), the first such
         * interface with an IPv6 address.
         */
        fun defaultMulticastInterface(): NetworkInterface? = try {
            val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { n ->
                n.isUp && !n.isLoopback && n.supportsMulticast() && !n.isVirtual
            }
            all.firstOrNull { n -> n.inetAddresses.toList().any { it is Inet4Address } }
                ?: all.firstOrNull { n -> n.inetAddresses.toList().any { it is Inet6Address && !it.isLoopbackAddress } }
        } catch (_: Exception) { null }

        /** The directed broadcast addresses of an interface (IPv4). */
        fun broadcastsOf(n: NetworkInterface?): List<InetAddress> = try {
            n?.interfaceAddresses?.mapNotNull { it.broadcast }?.distinct() ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
}

/**
 * Diagnostic trace of the v3 roles: events, sessions, what the server sends
 * and how the client plays. Off unless the app sets [sink] (desktop: --debug;
 * Android: debug builds, logcat tag WFAS3). It never holds keys, codes, PINs
 * or audio: session and pair numbers, statuses, configurations and counters.
 */
object Wfas3Trace {
    @Volatile var sink: ((String) -> Unit)? = null
    val on: Boolean get() = sink != null

    inline fun log(msg: () -> String) {
        val s = sink ?: return
        try { s(msg()) } catch (_: Throwable) { }
    }

    private val NAMES = listOf(
        W3.EV_SESSION_UP to "SESSION_UP", W3.EV_SESSION_DOWN to "SESSION_DOWN", W3.EV_SUSPECT to "SUSPECT",
        W3.EV_ATTEMPT_FAILED to "ATTEMPT_FAILED", W3.EV_REFUSED to "REFUSED", W3.EV_ATTEMPT_YIELDED to "ATTEMPT_YIELDED",
        W3.EV_STREAM_START to "STREAM_START", W3.EV_STREAM_STOP to "STREAM_STOP", W3.EV_STREAM_PARAMS to "STREAM_PARAMS",
        W3.EV_PEER_SILENT to "PEER_SILENT", W3.EV_PEER_BACK to "PEER_BACK", W3.EV_PAIR_ERASED to "PAIR_ERASED",
        W3.EV_FORGET_ASKED to "FORGET_ASKED", W3.EV_REKEYED to "REKEYED", W3.EV_PATH_CHANGED to "PATH_CHANGED",
        W3.EV_PAIRED to "PAIRED", W3.EV_PAIR_FAILED to "PAIR_FAILED", W3.EV_WINDOW_CLOSED to "WINDOW_CLOSED",
        W3.EV_GROUP_JOIN to "GROUP_JOIN", W3.EV_GROUP_LEAVE to "GROUP_LEAVE",
    )

    fun events(ev: Int): String = NAMES.filter { (ev and it.first) != 0 }.joinToString("|") { it.second }
}
