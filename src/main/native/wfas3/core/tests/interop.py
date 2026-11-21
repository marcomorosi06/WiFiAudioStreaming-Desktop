#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Marco Morosi
#
# Interoperability test: the C core (through build/interop) against the
# second implementation in tests/peer.py, both written from
# WFAS_V3_PROTOCOL.md. Each side builds and checks the other's datagrams
# byte for byte, and after every handshake the stored pair state of the C
# side is compared with the state of the Python side. Probes and ProbeReplies
# (section 12) go both ways, with the counters compared on both sides.
#
# Usage: python3 -I tests/interop.py build/interop

import os
import random
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import peer  # noqa: E402
import ref  # noqa: E402
from peer import config, u64  # noqa: E402

EV_STORE_PAIR, EV_STORE_DEVICE, EV_UP, EV_DOWN = 0x01, 0x02, 0x04, 0x08
EV_SUSPECT, EV_FAILED, EV_REFUSED, EV_YIELDED = 0x10, 0x20, 0x40, 0x80
ST_OK, ST_DROP, ST_ENTROPY, ST_STATE = 0, 2, 4, 6

C_IP = bytes(10) + b"\xff\xff" + bytes([10, 0, 0, 1])
PY_IP = bytes(10) + b"\xff\xff" + bytes([10, 0, 0, 2])
PORT = 14114
C_SRC = C_IP + PORT.to_bytes(2, "big")       # what the Python side sees as source
CFG = config()

RUN = 0
FAILED = 0


def expect(cond, what):
    global RUN, FAILED
    RUN += 1
    if not cond:
        FAILED += 1
        print("FAIL:", what)
    return cond


class Out:
    def __init__(self, f):
        self.status, self.events, self.pair, self.session, self.answer = (int(x) for x in f[1:6])
        self.to = (bytes.fromhex(f[6]), int(f[7]))
        self.dgram = b"" if f[8] == "-" else bytes.fromhex(f[8])
        self.config = tuple(int(x) for x in f[9:17])
        self.broadcast = int(f[17])
        self.addr = (bytes.fromhex(f[18]), int(f[19]))

    def has(self, ev):
        return (self.events & ev) == ev


class Driver:
    """The C device behind tests/interop.c."""

    def __init__(self, path):
        self.p = subprocess.Popen([path], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  text=True, bufsize=1)

    def cmd(self, line):
        self.p.stdin.write(line + "\n")
        self.p.stdin.flush()
        f = self.p.stdout.readline().split()
        if not f or f[0] == "err":
            raise RuntimeError("driver: %r for %r" % (f, line[:60]))
        return f

    def out(self, line):
        o = Out(self.cmd(line))
        if o.status == ST_ENTROPY:                 # the core asks for fresh entropy
            self.cmd("entropy " + os.urandom(32).hex())
            o = Out(self.cmd(line))
        return o

    def recv(self, d):
        return self.out("recv %s %d %s" % (PY_IP.hex(), PORT, d.hex() if d else "-"))

    def connect(self, cfg=CFG):
        v = (cfg[0], cfg[1], cfg[2], cfg[3], u64(cfg[4:8]), u64(cfg[8:10]), u64(cfg[10:12]),
             u64(cfg[12:14]))
        return self.out("connect 0 %s %d %s" % (PY_IP.hex(), PORT, " ".join(str(x) for x in v)))

    def control(self, session):
        """The next control packet of a session, as wfas3_poll() builds it when
        it is due. Anything else with a datagram first is an error."""
        for _ in range(8):
            o = self.out("poll")
            if len(o.dgram) == 160 and o.session == session:
                return o
            if o.dgram or o.events == 0:
                break
        raise RuntimeError("no control packet from poll for session %d" % session)

    def export(self):
        return parse_c_record(bytes.fromhex(self.cmd("export 0")[1]))

    def close(self):
        self.p.stdin.write("quit\n")
        self.p.stdin.flush()
        self.p.wait(timeout=10)


def c_record(c_priv, py_pub, k_fix, psk0, fp0, py_entry, c_entry, perms=1):
    """A pair record in the layout documented in wfas.c."""
    r = bytearray(420)
    r[0], r[2] = 1, perms
    r[4:36], r[36:68], r[68:100], r[100:132] = c_priv, py_pub, k_fix, psk0
    r[196:228], r[228:260] = py_entry, c_entry
    r[284:300] = fp0            # base, seq 0
    r[308:324] = fp0            # last, seq 0
    return bytes(r)


def parse_c_record(r):
    def slot(i):
        o = 284 + 24 * i
        return (r[o:o + 16], u64(r[o + 16:o + 24]))
    pres = r[3]
    return {
        "flags": r[1], "cur": r[100:132],
        "mine": r[132:164] if pres & 1 else None,
        "theirs": r[164:196] if pres & 2 else None,
        "ectr_seen": u64(r[260:268]), "ectr_used": u64(r[268:276]),
        "probe_seen": u64(r[276:284]),
        "base": slot(0), "last": slot(1),
        "minec": slot(2) if pres & 4 else None,
        "respp": slot(3) if pres & 8 else None,
        "seq_hi": u64(r[380:388]),
    }


def c_classify(c, rep):
    if rep[1] < c["base"][1]:
        return "stale"
    if rep in (c["base"], c["minec"], c["respp"]):
        return "ok"
    return "unknown"


class World:
    """A fresh pair between a C device and a Python device."""

    def __init__(self, path, perms=1):
        self.c = Driver(path)
        rec = bytes.fromhex(self.c.cmd("init %s -" % os.urandom(48).hex())[1])
        c_entry = rec[:32]
        c_priv, py_priv, k_fix, psk0 = (os.urandom(32) for _ in range(4))
        self.fp0, py_entry = os.urandom(16), os.urandom(32)
        self.py = peer.Pair(py_priv, ref.x25519_public(c_priv), k_fix, psk0, self.fp0,
                            py_entry, c_entry)
        self.c.cmd("import 0 " + c_record(c_priv, self.py.own_pub, k_fix, psk0, self.fp0,
                                          py_entry, c_entry, perms).hex())
        self.c_is_low = not self.py.is_low
        self.now = 1000

    def advance(self, ms=6000):
        self.now += ms
        self.c.cmd("time %d" % (self.now * 1000))     # the core counts microseconds


# -- complete handshakes ------------------------------------------------------

def c_opens(w, label):
    w.advance()
    o = w.c.connect()
    if not expect(o.status == ST_OK and len(o.dgram) == 64, label + ": C sends a CookieRequest"):
        return None
    r = w.py.on_cookie_request(o.dgram, C_SRC)
    if not expect(r[0] == "reply", label + ": Python accepts C's CookieRequest"):
        return None
    o = w.c.recv(r[1])
    if not expect(len(o.dgram) == 1368, label + ": C accepts the CookieReply and sends Init"):
        return None
    r = w.py.on_init(o.dgram, C_SRC)
    if not expect(r[0] == "resp" and r[2] == 0, label + ": Python accepts C's Init"):
        return None
    o = w.c.recv(r[1])
    if not expect(o.has(EV_UP | EV_STORE_PAIR), label + ": C accepts Python's Resp"):
        return None
    expect(o.config[0] & 1 == 1, label + ": C reports itself as the source")
    o = w.c.control(o.session)
    r = w.py.on_transport(o.dgram)
    if not expect(r[0] == "confirmed", label + ": C's first packet confirms the key to Python"):
        return None
    o = w.c.recv(w.py.control(r[1]))
    expect(o.status == ST_OK, label + ": C accepts Python's packet")
    return True


def py_opens(w, label):
    w.advance()
    o = w.c.recv(w.py.cookie_request())
    if not expect(len(o.dgram) == 64, label + ": C answers Python's CookieRequest"):
        return None
    if not expect(w.py.on_cookie_reply(o.dgram)[0] == "cookie", label + ": Python opens the cookie"):
        return None
    o = w.c.recv(w.py.init(CFG))
    if not expect(len(o.dgram) == 1228 and o.has(EV_STORE_PAIR), label + ": C answers the Init"):
        return None
    r = w.py.on_resp(o.dgram)
    if not expect(r[0] == "up", label + ": Python accepts C's Resp"):
        return None
    o = w.c.recv(w.py.control(r[1]))
    if not expect(o.has(EV_UP), label + ": Python's first packet confirms the key to C"):
        return None
    expect(o.config[0] & 1 == 0, label + ": C reports itself as the receiver")
    o = w.c.control(o.session)
    expect(w.py.on_transport(o.dgram)[0] == "ok", label + ": Python accepts C's packet")
    return True


def cross_check(w, label, concluded=True):
    c, py = w.c.export(), w.py
    c_choice = c["mine"] if c["mine"] is not None else c["cur"]
    py_choice = py.mine if py.mine is not None else py.cur
    expect(c_choice in (py.cur, py.mine, py.theirs), label + ": the psk C would use is held by Python")
    expect(py_choice in (c["cur"], c["mine"], c["theirs"]), label + ": the psk Python would use is held by C")
    expect(py.classify(c["last"]) == "ok", label + ": C's report is acceptable to Python")
    expect(c_classify(c, py.last) == "ok", label + ": Python's report is acceptable to C")
    expect(c["seq_hi"] == py.seq_hi, label + ": same highest session number")
    if concluded:
        expect(c["last"] == py.last, label + ": both report the same last session")
    expect(c["flags"] & 2 == 0 and not py.suspect, label + ": no alert on either side")


def flip(d, pos, bit=0x01):
    return d[:pos] + bytes([d[pos] ^ bit]) + d[pos + 1:]


# -- scenarios ----------------------------------------------------------------

def test_rounds(path):
    rng = random.Random(7)
    for world in range(4):
        w = World(path)
        for i in range(10):
            label = "world %d round %d" % (world, i)
            if rng.random() < 0.5:
                ok = c_opens(w, label + " (C opens)")
            else:
                ok = py_opens(w, label + " (Python opens)")
            if ok:
                cross_check(w, label)
        w.c.close()


def test_c_responder_negative(path):
    w = World(path)
    w.advance()
    creq = w.py.cookie_request()
    expect(len(w.c.recv(creq).dgram) == 64, "C answers a fresh CookieRequest")
    o = w.c.recv(creq)
    expect(o.status == ST_DROP and not o.dgram, "C drops a duplicate CookieRequest in silence")
    w.advance()
    creq2 = w.py.cookie_request()
    expect(len(w.c.recv(creq2).dgram) == 64, "C answers a newer CookieRequest")
    w.py.att = {"state": "req", "index": creq[4:8], "rid": creq[8:24], "tag": creq[32:48]}
    o = w.c.recv(creq)
    expect(len(o.dgram) == 64, "C answers an older CookieRequest")
    expect(w.py.on_cookie_reply(o.dgram) == ("stale",), "the answer is a stale notice (stale key)")
    expect(w.c.export()["flags"] & 2 == 0, "a stale request raises no alert on C")
    o = w.c.recv(creq)
    expect(o.status == ST_DROP, "C sends at most one stale notice every 30 s")
    w.c.close()

    w = World(path)
    w.advance()
    o = w.c.recv(w.py.cookie_request())
    w.py.on_cookie_reply(o.dgram)
    init = w.py.init(CFG)
    drops = [w.c.recv(flip(init, p)).status == ST_DROP for p in (4, 9, 30, 41, 100, 1290, 1340, 1360)]
    expect(all(drops), "C drops an Init with any byte changed")
    o = w.c.recv(init)
    expect(len(o.dgram) == 1228, "C still accepts the genuine Init: forgeries did not consume the cookie")
    expect(w.c.recv(init).status == ST_DROP, "C drops a replayed Init")
    w.c.close()

    for label, kw, reason in (("unknown psk", {"psk": os.urandom(32)}, 1),
                              ("unknown report", {"report": (os.urandom(16), 0)}, 3)):
        w = World(path)
        w.advance()
        o = w.c.recv(w.py.cookie_request())
        w.py.on_cookie_reply(o.dgram)
        o = w.c.recv(w.py.init(CFG, **kw))
        expect(len(o.dgram) == 48 and o.has(EV_SUSPECT), "%s: C rejects and raises the alert" % label)
        expect(w.py.on_reject(o.dgram) == ("rejected", reason), "%s: Reject reason %d" % (label, reason))
        w.c.close()

    w = World(path)
    py_opens(w, "stale setup 1")
    py_opens(w, "stale setup 2")
    w.advance()
    o = w.c.recv(w.py.cookie_request())
    w.py.on_cookie_reply(o.dgram)
    o = w.c.recv(w.py.init(CFG, report=(w.fp0, 0)))
    expect(len(o.dgram) == 48 and o.has(EV_SUSPECT), "stale report: C rejects and raises the alert")
    expect(w.py.on_reject(o.dgram) == ("rejected", 2), "stale report: Reject reason 2")
    w.c.close()

    for label, kw in (("invalid encapsulation key", "ek"), ("all-zero ephemeral key", "e")):
        w = World(path)
        w.advance()
        o = w.c.recv(w.py.cookie_request())
        w.py.on_cookie_reply(o.dgram)
        if kw == "ek":
            ek, _ = peer.ML_KEM_768.keygen()
            init = w.py.init(CFG, ek=b"\xff\xff" + ek[2:])
        else:
            init = w.py.init(CFG, e_pub=bytes(32))
        before = w.c.export()
        o = w.c.recv(init)
        after = w.c.export()
        expect(o.status == ST_DROP and not o.dgram and o.events == 0, "%s: C drops the Init" % label)
        expect(after["ectr_used"] == after["ectr_seen"], "%s: the cookie is consumed" % label)
        same = all(after[k] == before[k] for k in ("cur", "mine", "theirs", "base", "last", "seq_hi"))
        expect(same, "%s: no key or report changed" % label)
        w.c.close()

    w = World(path)
    for label, cfg, status in (("44.1 kHz, 220 samples", config(rate=44100, frames=220, nbytes=880), 0),
                               ("44.1 kHz, 221 samples", config(rate=44100, frames=221, nbytes=884), 3),
                               ("7.1 at 16 bit", config(channels=8, frames=84, nbytes=1344), 0),
                               ("Opus not accepted", config(codec=2, frames=480, nbytes=120), 3)):
        w.advance()
        o = w.c.recv(w.py.cookie_request())
        w.py.on_cookie_reply(o.dgram)
        o = w.c.recv(w.py.init(cfg))
        r = w.py.on_resp(o.dgram)
        if status == 0:
            expect(r[0] == "up", "%s: C accepts the configuration" % label)
            if r[0] == "up":
                w.c.recv(w.py.control(r[1]))
        else:
            expect(r[0] == "refused" and r[1] == 3 and peer.config_valid(r[2][:14] + b"\x00\x00"),
                   "%s: C refuses with a valid suggestion" % label)
        cross_check(w, label, concluded=(status == 0))
    w.c.close()


def c_until_init(w):
    w.advance()
    o = w.c.connect()
    r = w.py.on_cookie_request(o.dgram, C_SRC)
    return w.c.recv(r[1]).dgram


def test_c_initiator_negative(path):
    w = World(path)
    w.advance()
    o = w.c.connect()
    o = w.c.recv(w.py.cookie_reply(o.dgram, C_SRC, None))
    expect(o.has(EV_SUSPECT | EV_FAILED), "stale notice on its live request: C raises the alert")
    w.c.close()

    w = World(path)
    init = c_until_init(w)
    o = w.c.recv(w.py.reject(init, 1))
    expect(o.has(EV_SUSPECT | EV_FAILED), "Reject: C raises the alert")
    w.c.close()

    w = World(path)
    init = c_until_init(w)
    r = w.py.on_init(init, C_SRC, report=(os.urandom(16), 0))
    o = w.c.recv(r[1])
    expect(o.has(EV_SUSPECT) and not o.has(EV_UP), "unknown report in Resp: C raises the alert")
    w.c.close()

    w = World(path)
    init = c_until_init(w)
    resp = w.py.on_init(init, C_SRC)[1]
    drops = [w.c.recv(flip(resp, p)).status == ST_DROP for p in (5, 10, 20, 500, 1150, 1227)]
    expect(all(drops), "C ignores a Resp with any byte changed")
    o = w.c.recv(resp)
    expect(o.has(EV_UP), "C still accepts the genuine Resp afterwards")
    expect(w.c.recv(resp).status == ST_DROP, "C drops a replayed Resp")
    w.c.close()

    w = World(path)
    init = c_until_init(w)
    o = w.c.recv(w.py.on_init(init, C_SRC, status=1)[1])
    expect(o.has(EV_REFUSED | EV_FAILED) and o.answer == 1, "busy: C reports the refusal")
    c = w.c.export()
    expect(c["mine"] == w.py.theirs, "busy: the psk rules still apply on both sides")
    cross_check(w, "after busy", concluded=False)
    w.c.close()

    w = World(path)
    c_opens(w, "sequence setup")
    init = c_until_init(w)
    o = w.c.recv(w.py.on_init(init, C_SRC, session_seq=1)[1])
    expect(o.has(EV_FAILED) and not o.has(EV_SUSPECT) and not o.has(EV_UP),
           "session number not above C's: C aborts without an alert")
    w.c.close()


def test_crossing(path):
    for i in range(6):
        w = World(path)
        w.advance()
        creq_c = w.c.connect().dgram
        creq_p = w.py.cookie_request()
        oc = w.c.recv(creq_p)
        rp = w.py.on_cookie_request(creq_c, C_SRC)
        if w.c_is_low:
            expect(oc.status == ST_DROP and rp[0] == "reply", "crossing %d: the high side (Python) yields" % i)
            o = w.c.recv(rp[1])
            r = w.py.on_init(o.dgram, C_SRC)
            o = w.c.recv(r[1])
            r = w.py.on_transport(w.c.control(o.session).dgram)
            expect(r[0] == "confirmed", "crossing %d: C's handshake completes" % i)
        else:
            expect(oc.has(EV_YIELDED) and len(oc.dgram) == 64 and rp[0] == "drop",
                   "crossing %d: the high side (C) yields" % i)
            w.py.on_cookie_reply(oc.dgram)
            r = w.py.on_resp(w.c.recv(w.py.init(CFG)).dgram)
            o = w.c.recv(w.py.control(r[1]))
            expect(o.has(EV_UP), "crossing %d: Python's handshake completes" % i)
        cross_check(w, "crossing %d" % i)
        w.c.close()


def test_transport_and_strangers(path):
    w = World(path)
    py_opens(w, "transport setup")
    sess = w.py.sessions[0]
    pkt = w.py.control(sess)
    expect(w.c.recv(pkt).status == ST_OK, "C accepts a control packet")
    expect(w.c.recv(pkt).status == ST_DROP, "C drops a replayed control packet")
    expect(w.c.recv(flip(w.py.control(sess), 50)).status == ST_DROP, "C drops a changed control packet")
    rng = random.Random(11)
    silent = True
    for i in range(300):
        t, n = rng.choice(((0x14, 64), (0x12, 64), (0x10, 1368), (0x11, 1228), (0x15, 48), (0x20, 160),
                           (0x01, 60), (0x02, 60)))
        o = w.c.recv(bytes([0xA3, t, 0, 0]) + rng.randbytes(n - 4))
        silent = silent and o.status == ST_DROP and not o.dgram and o.events == 0
    expect(silent, "C drops 300 random datagrams of every type without answering")
    w.c.close()


# -- discovery (12) -----------------------------------------------------------

EV_FOUND, EV_NOT_FOUND = 0x40000, 0x80000
ST_FULL = 7
BCAST = (bytes(10) + b"\xff\xff" + b"\xff\xff\xff\xff", PORT)


def drain(w, limit=200):
    """Every output of C's timers until it has nothing to do."""
    outs = []
    for _ in range(limit):
        o = w.c.out("poll")
        if not o.dgram and o.events == 0:
            break
        outs.append(o)
    return outs


def probes_in(outs):
    return [o for o in outs if len(o.dgram) == 60]


def py_counter(w, probe):
    """The entry counter inside a Probe from C, unmasked as Python would."""
    mask = peer.mac8(w.py.key(b"entry counter ", False), bytes([peer.T_PROBE]) + probe[4:20])
    return peer.u64(peer.xor(probe[20:28], mask))


def test_discovery(path):
    # C looks for Python: no address seen yet, so the Probe is broadcast.
    w = World(path)
    w.advance()
    o = w.c.out("find 0 1")
    expect(o.status == ST_OK and len(o.dgram) == 60 and o.dgram[:4] == b"\xa3\x01\x00\x00",
           "C starts a search with a Probe")
    expect(o.broadcast == 1 and o.to == BCAST, "the first Probe without a seen address is broadcast")
    probe1 = o.dgram
    r = w.py.on_probe(probe1)
    expect(r[0] == "fresh", "Python accepts C's Probe as fresh")
    expect(w.py.probe_seen == py_counter(w, probe1), "Python stores C's counter")
    expect(w.c.out("find 0 1").status == ST_STATE, "a second search for the same pair is refused")
    o = w.c.recv(r[1])
    expect(o.status == ST_OK and o.has(EV_FOUND) and o.addr == (PY_IP, PORT) and not o.dgram,
           "C accepts Python's ProbeReply and reports where Python answered from")
    expect(w.c.recv(r[1]).status == ST_DROP, "a second copy of the ProbeReply is dropped")
    expect(w.py.on_probe(probe1) == ("drop",), "Python drops a copy of the Probe")
    # The counter is shared with CookieRequests: still strictly increasing.
    o = w.c.connect()
    r = w.py.on_cookie_request(o.dgram, C_SRC)
    expect(r[0] == "reply", "after a Probe, C's CookieRequest is still fresh for Python")
    expect(w.py.ectr_seen > w.py.probe_seen, "the CookieRequest counter is above the Probe's")
    w.c.close()

    # Python looks for C.
    w = World(path)
    w.advance()
    pr = w.py.probe()
    o = w.c.recv(pr)
    expect(o.status == ST_OK and len(o.dgram) == 60 and o.dgram[:4] == b"\xa3\x02\x00\x00",
           "C answers Python's Probe with a ProbeReply")
    expect(o.to == (PY_IP, PORT) and o.broadcast == 0, "to the source address of the Probe")
    expect(w.c.export()["probe_seen"] == w.py.ectr_send, "C keeps Python's counter in probe_seen")
    expect(w.c.export()["ectr_seen"] == 0, "a Probe does not touch ectr_seen")
    expect(w.py.on_probe_reply(o.dgram) == ("found",), "Python accepts C's ProbeReply")
    expect(w.py.on_probe_reply(o.dgram) == ("drop",), "Python forgets the nonce after the reply")
    o = w.c.recv(pr)
    expect(o.status == ST_DROP and not o.dgram, "C drops a copy of the Probe in silence")
    pr2 = w.py.probe()
    o = w.c.recv(pr2)
    expect(o.status == ST_DROP and not o.dgram, "C answers one fresh Probe per pair every 2 s")
    expect(w.c.export()["probe_seen"] == w.py.ectr_send, "but stores its counter at once")
    w.advance(2000)
    o = w.c.recv(w.py.probe())
    expect(len(o.dgram) == 60, "C answers a fresh Probe 2 s later")
    o = w.c.recv(pr)
    expect(len(o.dgram) == 60, "C answers a stale Probe")
    expect(w.c.export()["probe_seen"] == w.py.ectr_send, "a stale Probe changes no counter")
    expect(w.c.export()["flags"] & 2 == 0, "a stale Probe raises no alert")
    o = w.c.recv(pr2)
    expect(o.status == ST_DROP, "C answers at most one stale Probe every 30 s per pair")
    # The stale answers to Probes have their own budget: a stale notice is
    # still sent within 30 s of a stale ProbeReply.
    w.advance(6000)
    old = w.py.cookie_request()
    o = w.c.recv(old)
    w.advance(6000)
    o = w.c.recv(w.py.cookie_request())
    w.py.att = {"state": "req", "index": old[4:8], "rid": old[8:24], "tag": old[32:48]}
    o = w.c.recv(old)
    expect(len(o.dgram) == 64 and w.py.on_cookie_reply(o.dgram) == ("stale",),
           "a stale notice is still sent after a stale ProbeReply")
    w.advance(30000)
    o = w.c.recv(pr2)
    expect(len(o.dgram) == 60, "a stale Probe is answered again after 30 s")
    w.c.close()

    # What C accepts as a ProbeReply.
    w = World(path)
    w.advance()
    o = w.c.out("find 0 1")
    probe = o.dgram
    r = w.py.on_probe(probe)
    reply = r[1]
    bad = [w.c.recv(flip(reply, p)).status == ST_DROP for p in range(4, 60)]
    expect(all(bad), "C drops a ProbeReply with any byte from 4 to 59 changed")
    expect(w.c.recv(probe).status == ST_DROP, "C drops its own Probe sent back to it")
    hdr = bytes([0xA3, 0x02, 0x00, 0x00])
    expect(w.c.recv(hdr + probe[4:]).status == ST_DROP, "its own Probe relabelled as a reply is dropped")
    o = w.c.recv(reply)
    expect(o.has(EV_FOUND), "the intact ProbeReply is still accepted")
    # A reply older than 4 s, or of a search that ended, is not.
    w.advance(2000)
    o = w.c.out("find 0 1")
    late = w.py.on_probe(o.dgram)
    expect(late[0] == "fresh", "Python answers the Probe of a new search")
    w.advance(4001)
    for _ in range(3):
        w.c.out("poll")
    expect(w.c.recv(late[1]).status == ST_DROP, "C drops a ProbeReply that comes after 4 s")
    expect(w.c.out("find 0 0").status == ST_OK, "C stops the search")
    w.c.close()

    # Cadence and alternation: after a session, the seen address comes first.
    w = World(path)
    py_opens(w, "discovery setup")
    w.advance()
    o = w.c.out("find 0 1")
    expect(o.broadcast == 0 and o.to == (PY_IP, PORT), "Probe 1 goes to the address seen in the session")
    seen = [(o.broadcast, o.to)]
    counters = [py_counter(w, o.dgram)]
    expect(not probes_in(drain(w)), "no second Probe before 2 s")
    for k in range(14):
        w.advance(1999)
        early = probes_in(drain(w))
        w.advance(1)
        ps = probes_in(drain(w))
        if not expect(not early and len(ps) == 1, "Probe %d of the search, 2 s after the last" % (k + 2)):
            break
        seen.append((ps[0].broadcast, ps[0].to))
        counters.append(py_counter(w, ps[0].dgram))
    expect(all(b == (i % 2) for i, (b, _) in enumerate(seen)), "Probes alternate: seen address, broadcast")
    expect(all(t == ((PY_IP, PORT) if i % 2 == 0 else BCAST) for i, (_, t) in enumerate(seen)),
           "to the seen address and to 255.255.255.255:14114")
    expect(all(b > a for a, b in zip(counters, counters[1:])), "each Probe has a higher counter")
    w.advance(1999)
    expect(not [o for o in drain(w) if o.has(EV_NOT_FOUND)], "the search still runs 1.999 s after Probe 15")
    w.advance(1)
    outs = drain(w)
    expect([o for o in outs if o.has(EV_NOT_FOUND) and o.pair == 0], "after 15 Probes the search ends: not found")
    expect(not probes_in(outs), "and sends no 16th Probe")
    w.advance(2000)
    expect(not probes_in(drain(w)), "nothing more is sent")
    # A new search right away waits for the 2 s gap of the pair, then goes on.
    o = w.c.out("find 0 1")
    expect(o.status == ST_OK and len(o.dgram) == 60, "a new search may start once 2 s have passed")
    o2 = w.c.out("find 0 0")
    o3 = w.c.out("find 0 1")
    expect(o2.status == ST_OK and o3.status == ST_OK and not o3.dgram,
           "a search restarted within 2 s sends nothing at once")
    w.advance(1999)
    expect(not probes_in(drain(w)), "not before the 2 s")
    w.advance(1)
    expect(len(probes_in(drain(w))) == 1, "its first Probe leaves when the 2 s have passed")
    w.c.close()


def main():
    path = sys.argv[1]
    for scenario in (test_rounds, test_c_responder_negative, test_c_initiator_negative,
                     test_crossing, test_transport_and_strangers, test_discovery):
        try:
            scenario(path)
        except Exception as exc:    # a broken step must fail the test, not stop it
            expect(False, "%s stopped: %s: %s" % (scenario.__name__, type(exc).__name__, exc))
    print("interop: %d checks, %d failed" % (RUN, FAILED))
    return 0 if FAILED == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
