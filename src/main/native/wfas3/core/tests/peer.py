#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Marco Morosi
#
# Second implementation of WFAS v3 entry, handshake, pair state machines and
# discovery (WFAS_V3_PROTOCOL.md sections 6, 7, 8, 9.1 to 9.3, 10.1 and 12),
# written from the specification text, not from wfas.c. Used only by
# tests/interop.py.
#
# It models one device with one pair. Local limits that the specification
# leaves to each device (quotas, rate caps, timers, cookie secret rotation)
# are left out, so that the test can drive every message by hand. Every
# function returns a tuple whose first element says what happened.

import copy
import hmac
import os

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

import ref
from ref import ML_KEM_768

T_PROBE = 0x01
T_PROBE_REPLY = 0x02
T_INIT = 0x10
T_RESP = 0x11
T_CREP = 0x12
T_CREQ = 0x14
T_REJECT = 0x15
T_TRANSPORT = 0x20

HS_NAME = b"WFAS3_KKpsk0hfs_25519+MLKEM768_ChaChaPoly_SHA256"
PCM_RATES = (44100, 48000, 88200, 96000, 176400, 192000)


def header(t):
    return bytes([0xA3, t, 0, 0])


def u16(b):
    return int.from_bytes(b, "big")


def u32(b):
    return int.from_bytes(b, "big")


def u64(b):
    return int.from_bytes(b, "big")


def p64(v):
    return v.to_bytes(8, "big")


def mac16(k, m):
    return ref.hmac256(k, m)[:16]


def mac8(k, m):
    return ref.hmac256(k, m)[:8]


def kdf(k, label, ctx=b"", n=32):
    return ref.kdf(k, label, ctx, n)


def xor(a, b):
    return bytes(x ^ y for x, y in zip(a, b))


def same(a, b):
    return hmac.compare_digest(a, b)


def aead_open(key, n, ad, ct):
    try:
        return ChaCha20Poly1305(key).decrypt(ref.nonce(n), ct, ad)
    except InvalidTag:
        return None


def dh(priv, pub):
    """X25519 (section 2): None when the result is all zero."""
    try:
        out = ref.x25519(priv, pub)
    except ValueError:
        return None
    return None if out == bytes(32) else out


def ek_valid(ek):
    """FIPS 203 section 7.2 modulus check of an ML-KEM-768 encapsulation key."""
    if len(ek) != 1184:
        return False
    for i in range(0, 1152, 3):
        x = ek[i] | (ek[i + 1] << 8) | (ek[i + 2] << 16)
        if (x & 0xFFF) >= 3329 or (x >> 12) >= 3329:
            return False
    return True


def derive(ck):
    """Section 6.3."""
    return {
        "i2r": kdf(ck, b"transport i2r"),
        "r2i": kdf(ck, b"transport r2i"),
        "fp": kdf(ck, b"session fingerprint", n=16),
        "next": kdf(ck, b"pair psk next"),
        "rekey": kdf(ck, b"rekey psk"),
    }


def config(flags=1, codec=1, channels=2, bits=16, rate=48000, frames=240, nbytes=960,
           latency=20, status=0):
    """StreamConfig of section 8.3."""
    return (bytes([flags, codec, channels, bits]) + rate.to_bytes(4, "big") +
            frames.to_bytes(2, "big") + nbytes.to_bytes(2, "big") +
            latency.to_bytes(2, "big") + bytes([status, 0]))


def config_valid(c, is_resp=False):
    """Section 8.3. frame_samples for PCM is floor(rate / 200) or what fits."""
    if len(c) != 16:
        return False
    flags, codec, ch, bits = c[0], c[1], c[2], c[3]
    rate, frames, nbytes = u32(c[4:8]), u16(c[8:10]), u16(c[10:12])
    if flags & 0xF0 or not 1 <= ch <= 8 or c[15] != 0 or not 1 <= nbytes <= 1344:
        return False
    if (c[14] > 3) if is_resp else (c[14] != 0):
        return False
    if codec == 1:
        if rate not in PCM_RATES or bits not in (16, 24):
            return False
        fb = ch * bits // 8
        want = min(rate // 200, 1344 // fb)
        return frames == want and nbytes == want * fb
    if codec == 2:
        return rate == 48000 and bits == 16 and frames in (120, 240, 480, 960)
    return False


def flip_source(c):
    return bytes([c[0] ^ 1]) + c[1:]


class Session:
    def __init__(self, initiator, keys, send_index, recv_index, seq, state):
        self.initiator = initiator
        self.k_send = keys["i2r"] if initiator else keys["r2i"]
        self.k_recv = keys["r2i"] if initiator else keys["i2r"]
        self.fp = keys["fp"]
        self.send_index = send_index
        self.recv_index = recv_index
        self.seq = seq
        self.state = state
        self.send_ctr = 0
        self.seen = set()
        self.recv_max = -1


class Pair:
    """One side of a pair (sections 5, 9.2, 9.3)."""

    def __init__(self, own_priv, peer_pub, k_fix, psk0, fp0, own_entry, peer_entry):
        self.own_priv = own_priv
        self.own_pub = ref.x25519_public(own_priv)
        self.peer_pub = peer_pub
        self.k_fix = k_fix
        self.cur, self.mine, self.theirs = psk0, None, None
        self.own_entry = own_entry          # mac1 of what this side receives
        self.peer_entry = peer_entry        # mac1 of what this side sends
        self.ectr_send = 0
        self.ectr_seen = 0
        self.ectr_used = 0
        self.probe_seen = 0
        self.probes = []                    # nonce || ctr_masked of a running search
        self.base = (fp0, 0)
        self.last = (fp0, 0)
        self.minec = None
        self.respp = None
        self.seq_hi = 0
        self.is_low = self.own_pub < peer_pub       # big-endian comparison
        self.cookie_secret = [os.urandom(32), os.urandom(32)]
        self.att = None
        self.sessions = []
        self.suspect = False

    # -- keys (6.1, 6.2) -----------------------------------------------------

    def key(self, name, outgoing):
        """K_name[d], d being the direction of the request the key protects or
        answers; outgoing is True when this side sends that request."""
        low_sender = self.is_low if outgoing else not self.is_low
        return kdf(self.k_fix, name + (b"LH" if low_sender else b"HL"))

    def mac1_out(self, d):
        return mac16(kdf(self.peer_entry, b"mac1"), d)

    def mac1_ok(self, d, field):
        return same(mac16(kdf(self.own_entry, b"mac1"), d), field)

    # -- reports (9.3) -------------------------------------------------------

    def classify(self, r):
        if r[1] < self.base[1]:
            return "stale"
        if r in (self.base, self.minec, self.respp):
            return "ok"
        return "unknown"

    # -- CookieRequest and CookieReply (7.3 to 7.5) --------------------------

    def cookie_request(self, ectr=None):
        self.ectr_used = self.ectr_seen                         # rule C2
        self.ectr_send += 1
        value = self.ectr_send if ectr is None else ectr
        index, rid = os.urandom(4), os.urandom(16)
        mask = mac8(self.key(b"entry counter ", True), bytes([T_CREQ]) + rid)
        d = header(T_CREQ) + index + rid + xor(p64(value), mask)
        tag = mac16(self.key(b"pair tag ", True), d)
        d += tag
        d += self.mac1_out(d)
        self.att = {"state": "req", "index": index, "rid": rid, "tag": tag}
        return d

    def cookie_reply(self, req, src, ectr):
        """ectr None: stale notice."""
        salt = os.urandom(24)
        k = self.key(b"cookie ", False)
        head = header(T_CREP) + req[4:8] + salt
        if ectr is None:
            key, pt = kdf(k, b"stale", salt), bytes(16)
        else:
            key = kdf(k, b"cookie", salt)
            pt = mac16(self.cookie_secret[0], src + req[8:24] + p64(ectr))
        return head + ref.seal(key, 0, head + req[32:48], pt)

    def on_cookie_request(self, d, src):
        if len(d) != 64 or d[:4] != header(T_CREQ) or not self.mac1_ok(d[:48], d[48:64]):
            return ("drop",)
        if not same(mac16(self.key(b"pair tag ", False), d[:32]), d[32:48]):
            return ("drop",)
        mask = mac8(self.key(b"entry counter ", False), bytes([T_CREQ]) + d[8:24])
        ectr = u64(xor(d[24:32], mask))
        if ectr == self.ectr_seen:
            return ("drop",)
        if ectr < self.ectr_seen:
            return ("stale", self.cookie_reply(d, src, None))
        self.ectr_seen = ectr
        if self.att is not None:
            if self.is_low:                                     # rule C3
                self.ectr_used = ectr
                return ("drop",)
            self.att = None
        return ("reply", self.cookie_reply(d, src, ectr))

    def on_cookie_reply(self, d):
        a = self.att
        if (len(d) != 64 or d[:4] != header(T_CREP) or a is None or a["state"] != "req"
                or d[4:8] != a["index"]):
            return ("drop",)
        k = self.key(b"cookie ", True)
        ad = d[:32] + a["tag"]
        cookie = aead_open(kdf(k, b"cookie", d[8:32]), 0, ad, d[32:64])
        if cookie is not None:
            a.update(state="cookie", cookie=cookie)
            return ("cookie",)
        notice = aead_open(kdf(k, b"stale", d[8:32]), 0, ad, d[32:64])
        if notice == bytes(16):
            self.suspect = True
            self.att = None
            return ("stale",)
        return ("drop",)

    # -- Probe and ProbeReply (12) --------------------------------------------
    #
    # K_probe[d] is keyed by the direction of travel: what this side sends uses
    # the outgoing direction, what it receives the incoming one.

    def probe(self):
        """The next Probe of a search. The entry counter is the one of 7.3,
        shared with CookieRequests; the mask uses the type byte 0x01."""
        self.ectr_send += 1
        nonce = os.urandom(16)
        mask = mac8(self.key(b"entry counter ", True), bytes([T_PROBE]) + nonce)
        d = header(T_PROBE) + nonce + xor(p64(self.ectr_send), mask)
        d += mac16(self.key(b"probe ", True), d)
        d += self.mac1_out(d)
        self.probes.append(d[4:28])
        return d

    def on_probe(self, d):
        """("fresh" or "stale", ProbeReply), or ("drop",). The quotas of 12
        are local limits and are left out."""
        if len(d) != 60 or d[:4] != header(T_PROBE) or not self.mac1_ok(d[:44], d[44:60]):
            return ("drop",)
        if not same(mac16(self.key(b"probe ", False), d[:28]), d[28:44]):
            return ("drop",)
        mask = mac8(self.key(b"entry counter ", False), bytes([T_PROBE]) + d[4:20])
        ctr = u64(xor(d[20:28], mask))
        if ctr == self.probe_seen:
            return ("drop",)
        kind = "stale" if ctr < self.probe_seen else "fresh"
        if kind == "fresh":
            self.probe_seen = ctr
        r = header(T_PROBE_REPLY) + d[4:28]
        r += mac16(self.key(b"probe ", True), r)
        r += self.mac1_out(r)
        return (kind, r)

    def on_probe_reply(self, d):
        """("found",) ends the search and forgets its nonces, or ("drop",)."""
        if len(d) != 60 or d[:4] != header(T_PROBE_REPLY) or d[4:28] not in self.probes:
            return ("drop",)
        if not self.mac1_ok(d[:44], d[44:60]):
            return ("drop",)
        if not same(mac16(self.key(b"probe ", False), d[:28]), d[28:44]):
            return ("drop",)
        self.probes = []
        return ("found",)

    # -- Init (8.1) ----------------------------------------------------------

    def init(self, cfg, psk=None, report=None, e_pub=None, ek=None):
        """Build the Init of the current attempt. The optional arguments
        replace one value, to build a datagram that must be refused."""
        a = self.att
        d = header(T_INIT) + a["index"] + a["rid"]
        d += mac16(self.key(b"pair tag ", True), d)
        s = ref_sym()
        s.mix_hash(self.own_pub)
        s.mix_hash(self.peer_pub)
        s.mix_hash(d)
        used = (self.mine if self.mine is not None else self.cur) if psk is None else psk
        s.mix_key_and_hash(used)                                # rule I1
        e_priv = os.urandom(32)
        e = ref.x25519_public(e_priv) if e_pub is None else e_pub
        s.mix_hash(e)
        s.mix_key(e)
        dk = None
        if ek is None:
            ek, dk = ML_KEM_768.keygen()
        e1 = s.encrypt_and_hash(ek)
        s.mix_key(dh(e_priv, self.peer_pub))
        s.mix_key(dh(self.own_priv, self.peer_pub))
        rep = self.last if report is None else report
        payload = rep[0] + p64(rep[1]) + p64(self.seq_hi) + cfg
        d += e + e1 + s.encrypt_and_hash(payload)
        d += self.mac1_out(d)
        d += mac16(a["cookie"], d)
        a.update(state="out", sym=s, e_priv=e_priv, dk=dk, used=used, cfg=cfg)
        return d

    def reject(self, init, reason):
        key = kdf(self.key(b"reject ", False), b"reject", init[8:24])
        head = header(T_REJECT) + init[4:8] + init[8:24]
        return head + ref.seal(key, 0, head, bytes([reason]) + bytes(7))

    def on_init(self, d, src, status=None, report=None, session_seq=None):
        """Steps 1 to 10 of 8.1. status, report and session_seq replace the
        answer of this side, to build a Resp that the peer must refuse."""
        if len(d) != 1368 or d[:4] != header(T_INIT) or not self.mac1_ok(d[:1336], d[1336:1352]):
            return ("drop",)
        if not same(mac16(self.key(b"pair tag ", False), d[:24]), d[24:40]):
            return ("drop",)
        if not self.ectr_used < self.ectr_seen:
            return ("drop",)
        if not any(same(mac16(mac16(sec, src + d[8:24] + p64(self.ectr_seen)), d[:1352]),
                        d[1352:1368]) for sec in self.cookie_secret):
            return ("drop",)
        self.ectr_used = self.ectr_seen                         # step 5
        base = ref_sym()
        base.mix_hash(self.peer_pub)
        base.mix_hash(self.own_pub)
        base.mix_hash(d[:40])
        s, ek, opened = None, None, None
        for slot in ("cur", "mine", "theirs"):                  # step 7, rule R1
            psk = getattr(self, slot)
            if psk is None:
                continue
            s = copy.copy(base)
            s.mix_key_and_hash(psk)
            s.mix_hash(d[40:72])
            s.mix_key(d[40:72])
            ek = s.decrypt_and_hash(d[72:1272])
            if ek is not None:
                opened = slot
                break
        if opened is None:
            self.suspect = True
            return ("reject", self.reject(d, 1), 1)
        es = dh(self.own_priv, d[40:72])                        # step 8
        if not ek_valid(ek) or es is None:
            return ("drop",)
        s.mix_key(es)
        s.mix_key(dh(self.own_priv, self.peer_pub))
        payload = s.decrypt_and_hash(d[1272:1336])
        if payload is None:
            return ("drop",)
        rep = (payload[:16], u64(payload[16:24]))
        cls = self.classify(rep)                                # step 9
        if cls != "ok":
            self.suspect = True
            reason = 2 if cls == "stale" else 3
            return ("reject", self.reject(d, reason), reason)
        proposal = payload[32:48]
        if status is None:
            status = 0 if config_valid(proposal) else 3
        if status == 0:
            answer = flip_source(proposal)
        elif status == 3:
            answer = config(flags=1)                            # a configuration this side accepts
        else:
            answer = proposal
        answer = answer[:14] + bytes([status]) + answer[15:]
        s_seq = max(u64(payload[24:32]), self.seq_hi) + 1 if session_seq is None else session_seq
        index = os.urandom(4)
        while any(x.recv_index == index for x in self.sessions):
            index = os.urandom(4)
        r = header(T_RESP) + index + d[4:8]
        s.mix_hash(r)
        er = os.urandom(32)
        er_pub = ref.x25519_public(er)
        s.mix_hash(er_pub)
        s.mix_key(er_pub)
        s.mix_key(dh(er, d[40:72]))
        ss, ct = ML_KEM_768._encaps_internal(ek, os.urandom(32))
        r += er_pub + s.encrypt_and_hash(ct)
        s.mix_key(ss)
        s.mix_key(dh(er, self.peer_pub))
        mine_rep = self.last if report is None else report
        r += s.encrypt_and_hash(mine_rep[0] + p64(mine_rep[1]) + p64(s_seq) + answer)
        r += mac16(self.key(b"resp ", False), d[8:24] + r)
        keys = derive(s.ck)
        if opened != "cur":                                     # rule R2
            self.cur, self.mine = getattr(self, opened), None
        self.theirs = keys["next"]                              # rule R3
        self.base = rep                                         # report rules
        if self.minec is not None and self.minec[1] <= rep[1]:
            self.minec = None
        self.seq_hi = s_seq
        self.respp = (keys["fp"], s_seq)
        self.sessions = [x for x in self.sessions if x.state == "up"]
        if status == 0:
            self.sessions.append(Session(False, keys, d[4:8], index, s_seq, "unconfirmed"))
        return ("resp", r, status)

    # -- Resp and Reject on the initiator (8.2, 8.4) -------------------------

    def on_resp(self, d):
        a = self.att
        if (len(d) != 1228 or d[:4] != header(T_RESP) or a is None or a["state"] != "out"
                or d[8:12] != a["index"]):
            return ("drop",)
        if not same(mac16(self.key(b"resp ", True), a["rid"] + d[:1212]), d[1212:1228]):
            return ("drop",)
        s = copy.copy(a["sym"])
        s.mix_hash(d[:12])
        s.mix_hash(d[12:44])
        s.mix_key(d[12:44])
        ee = dh(a["e_priv"], d[12:44])
        if ee is None:
            return ("drop",)
        s.mix_key(ee)
        ct = s.decrypt_and_hash(d[44:1148])
        if ct is None:
            return ("drop",)
        s.mix_key(ML_KEM_768.decaps(a["dk"], ct))
        s.mix_key(dh(self.own_priv, d[12:44]))
        payload = s.decrypt_and_hash(d[1148:1212])
        if payload is None:
            return ("drop",)
        rep, s_seq, answer = (payload[:16], u64(payload[16:24])), u64(payload[24:32]), payload[32:48]
        if self.classify(rep) != "ok":
            self.suspect = True
            self.att = None
            return ("suspect",)
        if s_seq <= self.seq_hi:
            self.att = None
            return ("abort",)
        keys = derive(s.ck)
        if a["used"] != self.cur:                               # rule I2
            self.cur, self.theirs = a["used"], None
        self.mine = keys["next"]
        self.base = rep
        self.minec = self.last = (keys["fp"], s_seq)
        self.respp = None
        self.seq_hi = max(self.seq_hi, s_seq)
        self.att = None
        if answer[14] == 0 and answer == flip_source(a["cfg"]):
            sess = Session(True, keys, d[4:8], a["index"], s_seq, "up")
            self.sessions = [sess]
            return ("up", sess)
        return ("refused", answer[14], answer)

    def on_reject(self, d):
        a = self.att
        if (len(d) != 48 or d[:4] != header(T_REJECT) or a is None or a["state"] != "out"
                or d[4:8] != a["index"] or d[8:24] != a["rid"]):
            return ("drop",)
        pt = aead_open(kdf(self.key(b"reject ", True), b"reject", a["rid"]), 0, d[:24], d[24:48])
        if pt is None or pt[0] not in (1, 2, 3) or pt[1:] != bytes(7):
            return ("drop",)
        self.suspect = True
        self.att = None
        return ("rejected", pt[0])

    # -- transport, padding only (10.1) --------------------------------------

    def control(self, sess):
        d = header(T_TRANSPORT) + sess.send_index + p64(sess.send_ctr)
        d += ref.seal(sess.k_send, sess.send_ctr, d, bytes(128))
        sess.send_ctr += 1
        return d

    def on_transport(self, d):
        if len(d) != 160 or d[:4] != header(T_TRANSPORT):
            return ("drop",)
        found = [x for x in self.sessions if x.recv_index == d[4:8]]
        if not found:
            return ("drop",)
        sess, ctr = found[0], u64(d[8:16])
        if ctr in sess.seen or ctr < sess.recv_max - 4095 or ctr >= 1 << 60:
            return ("drop",)
        pt = aead_open(sess.k_recv, ctr, d[:16], d[16:160])
        if pt is None:
            return ("drop",)
        sess.seen.add(ctr)
        sess.recv_max = max(sess.recv_max, ctr)
        if pt != bytes(128):
            return ("frames",)
        if sess.state == "unconfirmed":                         # 8.5
            sess.state = "up"
            if self.respp is not None and self.respp[0] == sess.fp:
                self.last = self.respp
            self.sessions = [sess]
            return ("confirmed", sess)
        return ("ok", sess)


def ref_sym():
    return Sym(HS_NAME)


class Sym(ref.Sym):
    """Section 2.1, with the DecryptAndHash that ref.Sym does not need."""

    def decrypt_and_hash(self, ct):
        pt = aead_open(self.k, self.n, self.h, ct)
        if pt is None:
            return None
        self.n += 1
        self.mix_hash(ct)
        return pt
