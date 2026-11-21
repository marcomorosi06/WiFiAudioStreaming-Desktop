#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Marco Morosi
#
# Second implementation of WFAS v3 pairing (WFAS_V3_PROTOCOL.md section 13),
# written from the specification text, not from wfas.c. Used only by
# tests/interop_pair.py. One server window and one client run, driven
# message by message; timers, admission and retransmission are left to the
# test. Every method returns a tuple whose first element says what happened.

import hashlib
import os

import ref
from peer import Sym, aead_open, dh, ek_valid, kdf, mac16, same
from ref import ML_KEM_768

T_PAIR_BEACON = 0x03
T_PAIR = 0x13
NAME = b"WFAS3_PAIR_25519+MLKEM768+CPace_ChaChaPoly_SHA256"
LENGTHS = {1: 1368, 2: 1296, 3: 176, 4: 80, 5: 80, 6: 80, 7: 120, 8: 100, 9: 52}
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def sha256(b):
    return hashlib.sha256(b).digest()


def fingerprint(w_pub):
    """13.1."""
    return sha256(b"WFAS3 pairing key" + w_pub)[:16]


def code_text(code, qr=False):
    """13.1: 52 Crockford characters, 13 groups of 4, or "WFAS3:" for QR."""
    bits = int.from_bytes(code, "big") << 4 | (sha256(b"WFAS3 code check" + code)[0] >> 4)
    chars = "".join(CROCKFORD[(bits >> (5 * (51 - i))) & 31] for i in range(52))
    if qr:
        return "WFAS3:" + chars
    return "-".join(chars[i:i + 4] for i in range(0, 52, 4))


def code_parse(text):
    t = text.upper()
    if t.startswith("WFAS3:"):
        t = t[6:]
    t = t.replace("-", "").replace(" ", "").replace("O", "0").replace("I", "1").replace("L", "1")
    if len(t) != 52 or any(c not in CROCKFORD for c in t):
        return None
    bits = 0
    for c in t:
        bits = bits << 5 | CROCKFORD.index(c)
    code = (bits >> 4).to_bytes(32, "big")
    if (bits & 15) != (sha256(b"WFAS3 code check" + code)[0] >> 4):
        return None
    return code


def head(step, mode, sid):
    return bytes([0xA3, T_PAIR, 0, 0, step, mode, 0, 0]) + sid


def seal(k, n, ad, pt):
    return ref.seal(k, n, ad, pt)


def sas(h, n_c, n_s):
    return int.from_bytes(sha256(b"WFAS3 sas" + h + n_c + n_s)[:8], "big") % 1000000


def cpace(prs, mode, sid, nonce):
    """13.3: CI = "WFAS3-PAIR" || mode, sid = pair_sid || window_nonce."""
    return ref.cpace_generator(prs, b"WFAS3-PAIR" + bytes([mode]), sid + nonce), sid + nonce


def out_keys(ck):
    return {
        "c2s": kdf(ck, b"pair c2s"), "s2c": kdf(ck, b"pair s2c"),
        "fix": kdf(ck, b"pair fixed"), "psk": kdf(ck, b"pair psk"),
        "fp": kdf(ck, b"pair fingerprint", n=16),
    }


def replaces(k_fix, h):
    """13.5 with K_replace of 6.1."""
    return mac16(kdf(k_fix, b"replace"), b"replace" + h)


def start_sym(mode, sid, nonce):
    s = Sym(NAME)
    s.mix_hash(bytes([mode]) + sid + nonce)
    return s


def filter_ok(k_f, d):
    return len(d) >= 32 and same(mac16(k_f, d[:-16]), d[-16:])


class Window:
    """A server window, 13.2. kind: "code", "label" or "pin"."""

    def __init__(self, kind, name=b"", secret=None, w_priv=None):
        self.kind = kind
        self.mode = 2 if kind == "pin" else 1
        self.nonce = os.urandom(16)
        if kind == "pin":
            self.prs = "".join(str(b % 10) for b in os.urandom(8)).encode()
            self.k_f = kdf(self.nonce, b"pin filter")
        else:
            self.prs = secret if secret is not None else os.urandom(16)
            self.k_f = kdf(self.prs, b"pair filter")
            self.k_b = kdf(self.prs, b"pair beacon")
        self.w_priv = w_priv if w_priv is not None else os.urandom(32)
        self.w_pub = ref.x25519_public(self.w_priv)
        self.name = name.ljust(32, b"\0")
        self.used = 0
        self.ctx = None

    def code(self):
        return self.prs if self.mode == 2 else self.prs + fingerprint(self.w_pub)

    def beacon(self):
        b = bytes([0xA3, T_PAIR_BEACON, 0, 0, self.mode, self.used, 0, 0]) + self.nonce
        if self.mode == 2:
            return b + self.name + bytes(16)
        b += bytes(32)
        return b + mac16(self.k_b, b)

    def _send(self, d):
        return d + mac16(self.k_f, d)

    def on_step1(self, d):
        if len(d) != 1368 or d[:5] != bytes([0xA3, T_PAIR, 0, 0, 1]) or d[5] != self.mode:
            return ("drop", "header")
        if not filter_ok(self.k_f, d):
            return ("drop", "filter")
        if d[6:8] != bytes(2) or d[1264:1352] != bytes(88) or self.used:
            return ("drop", "body")
        e_c, ek, ya_pub = d[16:48], d[48:1232], d[1232:1264]
        if not ek_valid(ek):
            return ("drop", "ek")
        sid = d[8:16]
        s = start_sym(self.mode, sid, self.nonce)
        s.mix_hash(e_c)
        s.mix_hash(ek)
        s.mix_hash(ya_pub)
        e_priv = os.urandom(32)
        e_pub = ref.x25519_public(e_priv)
        r = head(2, self.mode, sid) + e_pub
        s.mix_hash(e_pub)                                  # e
        s.mix_key(dh(e_priv, e_c))                         # ee
        g, sid24 = cpace(self.prs, self.mode, sid, self.nonce)
        yb = os.urandom(32)
        yb_pub = dh(yb, g)
        k = dh(yb, ya_pub)
        if yb_pub is None or k is None:
            return ("drop", "cpace")
        r += yb_pub
        s.mix_hash(yb_pub)                                 # yb
        s.mix_key(ref.cpace_isk(sid24, k, ya_pub, e_c, yb_pub, e_pub))   # cpace
        ss, ct = ML_KEM_768._encaps_internal(ek, os.urandom(32))
        r += s.encrypt_and_hash(ct)                        # ekem1
        s.mix_key(ss)
        r += s.encrypt_and_hash(self.w_pub)                # w
        s.mix_key(dh(self.w_priv, e_c))                    # ew
        s_priv = os.urandom(32)
        r += s.encrypt_and_hash(ref.x25519_public(s_priv))  # s
        s.mix_key(dh(s_priv, e_c))                         # es
        self.ctx = {"sid": sid, "s": s, "e_priv": e_priv, "s_priv": s_priv, "state": 2}
        return ("step2", self._send(r))

    def _pair_dg(self, d, step):
        c = self.ctx
        if c is None or len(d) != LENGTHS[step] or d[:5] != bytes([0xA3, T_PAIR, 0, 0, step]):
            return None
        if d[5] != self.mode or d[6:8] != bytes(2) or d[8:16] != c["sid"]:
            return None
        if not filter_ok(self.k_f, d):
            return None
        return c

    def on_step3(self, d):
        c = self._pair_dg(d, 3)
        if c is None or c["state"] != 2:
            return ("drop",)
        s = c["s"]
        s_c = s.decrypt_and_hash(d[16:64])
        if s_c is None:
            return ("drop", "s")
        s.mix_key(dh(c["e_priv"], s_c))                    # se
        h3 = s.h
        pt = s.decrypt_and_hash(d[64:160])
        if pt is None or pt[32] > 3 or pt[33:48] != bytes(15):
            return ("drop", "payload")
        c.update(state=4, peer_s=s_c, h3=h3, h=s.h, keys=out_keys(s.ck), name=pt[:32],
                 flags=pt[32], commit_c=pt[48:80], n_s=os.urandom(32))
        commit_s = sha256(b"WFAS3 sas commit s" + c["h"] + c["n_s"])
        hd = head(4, self.mode, c["sid"])
        return ("step4", self._send(hd + seal(c["keys"]["s2c"], 4, hd, commit_s)))

    def on_step5(self, d):
        c = self._pair_dg(d, 5)
        if c is None or c["state"] != 4:
            return ("drop",)
        n_c = aead_open(c["keys"]["c2s"], 5, d[:16], d[16:64])
        if n_c is None:
            return ("drop", "open")
        if not same(sha256(b"WFAS3 sas commit c" + c["h3"] + n_c), c["commit_c"]):
            return ("failed", "commit")
        c.update(state=6, code=sas(c["h"], n_c, c["n_s"]))
        hd = head(6, self.mode, c["sid"])
        return ("step6", self._send(hd + seal(c["keys"]["s2c"], 6, hd, c["n_s"])), c["code"])

    def step7(self, decision, perms, guest, entry):
        c = self.ctx
        c.update(state=7, decision=decision, perms=perms, guest=guest, entry=entry)
        if decision:
            pt = bytes([1, perms, guest, 0]) + self.name + entry + bytes(4)
        else:
            pt = bytes(72)
        hd = head(7, self.mode, c["sid"])
        return self._send(hd + seal(c["keys"]["s2c"], 7, hd, pt))

    def on_step8(self, d, old_pairs=()):
        """old_pairs: K_fix of the pairs this server holds, for 13.5."""
        c = self._pair_dg(d, 8)
        if c is None or c["state"] != 7:
            return ("drop",)
        pt = aead_open(c["keys"]["c2s"], 8, d[:16], d[16:84])
        if pt is None or pt[0] > 1 or pt[1:4] != bytes(3):
            return ("drop", "open")
        if not pt[0] or not c["decision"]:
            self.ctx = None
            return ("refused",)
        replaced = None
        for i, k_fix in enumerate(old_pairs):
            if pt[36:52] != bytes(16) and same(replaces(k_fix, c["h"]), pt[36:52]):
                replaced = i
        self.used = 1
        hd = head(9, self.mode, c["sid"])
        rec = {"s_priv": c["s_priv"], "peer_pub": c["peer_s"], "k_fix": c["keys"]["fix"],
               "psk0": c["keys"]["psk"], "fp": c["keys"]["fp"], "peer_entry": pt[4:36],
               "own_entry": c["entry"], "name": c["name"], "perms": c["perms"],
               "guest": c["guest"], "replaced": replaced}
        c["state"] = 9
        return ("paired", self._send(hd + seal(c["keys"]["s2c"], 9, hd, bytes([1, 0, 0, 0]))), rec)


class Client:
    """A client run, 13.2 to 13.5. For a code or label, code is the 32 bytes;
    for a PIN, pin is the 8 ASCII digits and nonce the beacon's."""

    def __init__(self, name=b"", flags=0, code=None, pin=None, nonce=None):
        self.name = name.ljust(32, b"\0")
        self.flags = flags
        if pin is not None:
            self.mode, self.prs, self.nonce = 2, pin, nonce
            self.k_f = kdf(nonce, b"pin filter")
            self.fp_w = None
        else:
            self.mode, self.prs, self.fp_w = 1, code[:16], code[16:]
            self.nonce = None
            self.k_f = kdf(self.prs, b"pair filter")
            self.k_b = kdf(self.prs, b"pair beacon")
        self.state = 0

    def _send(self, d):
        return d + mac16(self.k_f, d)

    def on_beacon(self, b):
        """Mode 1: a beacon of this code starts the run; state 1 means used."""
        if len(b) != 72 or b[:4] != bytes([0xA3, T_PAIR_BEACON, 0, 0]) or b[4] != 1:
            return ("drop",)
        if not same(mac16(self.k_b, b[:56]), b[56:72]):
            return ("drop", "tag")
        if b[5] == 1:
            return ("used",)
        if self.state != 0:
            return ("drop", "busy")
        self.nonce = b[8:24]
        return ("start",)

    def step1(self):
        self.sid = os.urandom(8)
        self.e_priv = os.urandom(32)
        self.e_pub = ref.x25519_public(self.e_priv)
        self.ek, self.dk = ML_KEM_768.keygen()
        g, self.sid24 = cpace(self.prs, self.mode, self.sid, self.nonce)
        self.ya = os.urandom(32)
        self.ya_pub = dh(self.ya, g)
        self.s = start_sym(self.mode, self.sid, self.nonce)
        for x in (self.e_pub, self.ek, self.ya_pub):
            self.s.mix_hash(x)
        self.state = 1
        return self._send(head(1, self.mode, self.sid) + self.e_pub + self.ek + self.ya_pub + bytes(88))

    def _dg(self, d, step):
        if len(d) != LENGTHS[step] or d[:5] != bytes([0xA3, T_PAIR, 0, 0, step]):
            return False
        if d[5] != self.mode or d[6:8] != bytes(2) or d[8:16] != self.sid:
            return False
        return filter_ok(self.k_f, d)

    def on_step2(self, d):
        if self.state != 1 or not self._dg(d, 2):
            return ("drop",)
        s = Sym.__new__(Sym)
        s.__dict__.update(self.s.__dict__)                 # a copy: a forgery changes nothing
        e_s, yb_pub = d[16:48], d[48:80]
        s.mix_hash(e_s)
        k = dh(self.e_priv, e_s)
        if k is None:
            return ("drop", "ee")
        s.mix_key(k)
        s.mix_hash(yb_pub)
        kk = dh(self.ya, yb_pub)
        if kk is None:
            return ("drop", "cpace")
        s.mix_key(ref.cpace_isk(self.sid24, kk, self.ya_pub, self.e_pub, yb_pub, e_s))
        ct = s.decrypt_and_hash(d[80:1184])
        if ct is None:
            return ("drop", "ekem1")
        s.mix_key(ML_KEM_768.decaps(self.dk, ct))
        w_pub = s.decrypt_and_hash(d[1184:1232])
        if w_pub is None:
            return ("drop", "w")
        if self.mode == 1 and not same(fingerprint(w_pub), self.fp_w):
            return ("key",)
        s.mix_key(dh(self.e_priv, w_pub))                  # ew
        s_s = s.decrypt_and_hash(d[1232:1280])
        if s_s is None:
            return ("drop", "s")
        s.mix_key(dh(self.e_priv, s_s))                    # es
        self.s_priv = os.urandom(32)
        r = head(3, self.mode, self.sid) + s.encrypt_and_hash(ref.x25519_public(self.s_priv))
        s.mix_key(dh(self.s_priv, e_s))                    # se
        self.h3 = s.h
        self.n_c = os.urandom(32)
        commit_c = sha256(b"WFAS3 sas commit c" + self.h3 + self.n_c)
        r += s.encrypt_and_hash(self.name + bytes([self.flags]) + bytes(15) + commit_c)
        self.h = s.h
        self.keys = out_keys(s.ck)
        self.peer_s = s_s
        self.state = 3
        return ("step3", self._send(r))

    def on_step4(self, d):
        if self.state != 3 or not self._dg(d, 4):
            return ("drop",)
        cs = aead_open(self.keys["s2c"], 4, d[:16], d[16:64])
        if cs is None:
            return ("drop", "open")
        self.commit_s = cs
        self.state = 5
        hd = head(5, self.mode, self.sid)
        return ("step5", self._send(hd + seal(self.keys["c2s"], 5, hd, self.n_c)))

    def on_step6(self, d):
        if self.state != 5 or not self._dg(d, 6):
            return ("drop",)
        n_s = aead_open(self.keys["s2c"], 6, d[:16], d[16:64])
        if n_s is None:
            return ("drop", "open")
        if not same(sha256(b"WFAS3 sas commit s" + self.h + n_s), self.commit_s):
            return ("failed", "commit")
        self.code = sas(self.h, self.n_c, n_s)
        self.state = 6
        return ("code", self.code)

    def on_step7(self, d):
        if self.state != 6 or not self._dg(d, 7):
            return ("drop",)
        pt = aead_open(self.keys["s2c"], 7, d[:16], d[16:104])
        if pt is None:
            return ("drop", "open")
        if pt[0] == 0:
            return ("refused",)
        self.peer = {"perms": pt[1], "guest": pt[2], "name": pt[4:36], "entry": pt[36:68]}
        self.state = 7
        return ("decision", self.peer)

    def step8(self, accept, entry, old_k_fix=None):
        rep = replaces(old_k_fix, self.h) if old_k_fix is not None else bytes(16)
        pt = bytes([accept, 0, 0, 0]) + (entry if accept else bytes(32)) + (rep if accept else bytes(16))
        self.own_entry = entry
        self.state = 8
        hd = head(8, self.mode, self.sid)
        return self._send(hd + seal(self.keys["c2s"], 8, hd, pt))

    def on_step9(self, d):
        if self.state != 8 or not self._dg(d, 9):
            return ("drop",)
        pt = aead_open(self.keys["s2c"], 9, d[:16], d[16:36])
        if pt is None or pt != bytes([1, 0, 0, 0]):
            return ("drop", "open")
        self.state = 9
        return ("paired", {"s_priv": self.s_priv, "peer_pub": self.peer_s, "k_fix": self.keys["fix"],
                           "psk0": self.keys["psk"], "fp": self.keys["fp"],
                           "peer_entry": self.peer["entry"], "own_entry": self.own_entry,
                           "name": self.peer["name"], "guest": self.peer["guest"]})
