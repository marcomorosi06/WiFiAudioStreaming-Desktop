#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Marco Morosi
#
# Interoperability test of pairing (section 13): the C core (through
# build/interop) against the second implementation in tests/peer_pair.py,
# in both roles and with the code, label and PIN methods. After each pairing
# the C record is compared with the Python one, and the new pair must then
# complete a session handshake with tests/peer.py.
#
# Usage: python3 -I tests/interop_pair.py build/interop

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import interop  # noqa: E402
import peer  # noqa: E402
import peer_pair  # noqa: E402
import ref  # noqa: E402
from interop import C_IP, PY_IP, PORT, ST_OK, ST_DROP, expect  # noqa: E402

PAIRF_KEY_C = 4

EV_STORE_PAIR, EV_STORE_DEVICE, EV_UP = 0x01, 0x02, 0x04
EV_ERASED = 0x4000
EV_BEACON, EV_ASK, EV_CODE, EV_PAIRED = 0x100000, 0x200000, 0x400000, 0x800000
EV_FAILED, EV_RACE, EV_CLOSED = 0x1000000, 0x2000000, 0x4000000
CODE, LABEL, PIN = 1, 2, 3
GUEST, MIC = 1, 2
NONE = 0xFFFFFFFF
PAIRF_KEY, PAIRF_REFUSED = 4, 3


class POut(interop.Out):
    def __init__(self, f):
        super().__init__(f)
        self.code, self.ask, self.pflags = int(f[20]), int(f[21]), int(f[22])
        self.name = bytes.fromhex(f[23])
        self.beacon = {"ip": bytes.fromhex(f[24]), "port": int(f[25]), "used": int(f[26]),
                       "nonce": bytes.fromhex(f[27]), "name": bytes.fromhex(f[28])}


class PDriver(interop.Driver):
    def __init__(self, path):
        super().__init__(path)
        self.rec = bytes.fromhex(self.cmd("init %s -" % os.urandom(48).hex())[1])
        self.entry = self.rec[:32]
        self.now = 1000
        self.time(0)

    def time(self, ms):
        self.now += ms
        self.cmd("time %d" % (self.now * 1000))

    def out(self, line):
        o = POut(self.cmd(line))
        if o.status == interop.ST_ENTROPY:
            self.cmd("entropy " + os.urandom(32).hex())
            o = POut(self.cmd(line))
        return o

    def record(self, slot):
        return bytes.fromhex(self.cmd("export %d" % slot)[1])

    def pair(self, kind, secret, flags=0, perms=3, replace=NONE, name=b"C phone",
             beacon_ip=bytes(16), beacon_port=0, nonce=b""):
        return self.out("pair %d %s %d %d %d %s %s %d %s" % (
            kind, secret.hex(), flags, perms, replace, name.hex(), beacon_ip.hex(), beacon_port,
            nonce.hex() if nonce else "-"))


def name32(n):
    return n.ljust(32, b"\0")


def same_record(c_rec, py, label, guest=False):
    """C record (layout of wfas.c) against the Python side of the same pair."""
    expect(ref.x25519_public(c_rec[4:36]) == py["peer_pub"], label + ": C static key is the one Python holds")
    expect(c_rec[36:68] == ref.x25519_public(py["s_priv"]), label + ": C holds Python's static key")
    expect(c_rec[68:100] == py["k_fix"], label + ": same K_fix")
    expect(c_rec[100:132] == py["psk0"], label + ": same psk0")
    expect(c_rec[284:300] == py["fp"] and c_rec[308:324] == py["fp"],
           label + ": report slots base and last hold the pair fingerprint")
    expect(c_rec[196:228] == py["own_entry"], label + ": C holds Python's entry key")
    expect(c_rec[228:260] == py["peer_entry"], label + ": Python holds the key C gave")
    expect(c_rec[380:388] == bytes(8), label + ": seq_hi 0")
    expect((c_rec[1] & 1) == (1 if guest else 0), label + ": guest flag")


def session_after(c, py, label, guest=False):
    """The new pair (slot 0 on the C side) completes a handshake with peer.py."""
    pair = peer.Pair(py["s_priv"], py["peer_pub"], py["k_fix"], py["psk0"], py["fp"],
                     py["own_entry"], py["peer_entry"])

    class W:
        pass
    w = W()
    w.c, w.py, w.now = c, pair, c.now

    def advance(ms=6000):
        w.now += ms
        c.now = w.now
        c.cmd("time %d" % (w.now * 1000))
    w.advance = advance
    if not guest:
        return expect(interop.py_opens(w, label) is True, label + ": a session comes up on the new pair")
    # A guest: the same handshake, but nothing is stored.
    w.advance()
    o = c.recv(pair.cookie_request())
    pair.on_cookie_reply(o.dgram)
    o = c.recv(pair.init(interop.CFG))
    expect(len(o.dgram) == 1228 and not o.has(EV_STORE_PAIR), label + ": C answers the guest's Init, stores nothing")
    r = pair.on_resp(o.dgram)
    o = c.recv(pair.control(r[1]))
    return expect(r[0] == "up" and o.has(EV_UP), label + ": a guest session comes up")


def c_server(path, kind, label, guest_req=False, answer_guest=0, refuse=False, ask=1):
    c = PDriver(path)
    lab = None
    if kind == LABEL:
        lab = bytes.fromhex(c.cmd("label")[1])
    o = c.out("window %d %d 3 %s" % (kind, ask, b"C speaker".hex()))
    expect(o.status == ST_OK and len(o.dgram) == 72 and o.broadcast == 1, label + ": C window, beacon broadcast")
    code = bytes.fromhex(c.cmd("code")[1])
    if kind == PIN:
        expect(len(code) == 8 and all(0x30 <= b <= 0x39 for b in code), label + ": eight ASCII digits")
        expect(o.dgram[4] == 2 and o.dgram[24:56] == name32(b"C speaker"), label + ": PIN beacon names C")
        cl = peer_pair.Client(name=b"Py phone", flags=(GUEST if guest_req else 0) | MIC, pin=code,
                              nonce=o.dgram[8:24])
    else:
        if kind == LABEL:
            expect(bytes.fromhex(c.cmd("labelcode")[1]) == code, label + ": the window shows the label")
            expect(code[:16] == lab[32:48] and code[16:] == peer_pair.fingerprint(ref.x25519_public(lab[:32])),
                   label + ": label code = secret || fp(W)")
        text = c.cmd("codetext %s 0" % code.hex())[1]
        expect(text == peer_pair.code_text(code), label + ": the same code text")
        expect(c.cmd("codetext %s 1" % code.hex())[1] == peer_pair.code_text(code, True), label + ": same QR text")
        expect(bytes.fromhex(c.cmd("codeparse " + peer_pair.code_text(code).lower())[1]) == code,
               label + ": C reads Python's text")
        cl = peer_pair.Client(name=b"Py phone", flags=(GUEST if guest_req else 0) | MIC, code=code)
        expect(o.dgram[6] == (1 if kind == CODE else 0), label + ": beacon flags say whether the secret is used once")
        expect(cl.on_beacon(o.dgram) == ("start",), label + ": Python recognises C's beacon")
    d1 = cl.step1()
    bad = d1[:-1] + bytes([d1[-1] ^ 1])
    expect(c.out("recv %s %d %s" % (PY_IP.hex(), PORT, bad.hex())).status == ST_DROP,
           label + ": a wrong filter is dropped")
    o = c.recv(d1)
    if not expect(len(o.dgram) == 48 and o.dgram[4] == peer_pair.RETRY and o.to == (PY_IP, PORT),
                  label + ": C answers step 1 without a token with a Retry"):
        return None
    r = cl.on_retry(o.dgram)
    if not expect(r[0] == "step1" and r[1][1264:1280] == o.dgram[16:32], label + ": Python takes the token"):
        return None
    d1 = r[1]
    o = c.out("recv %s %d %s" % (PY_IP.hex(), PORT + 1, d1.hex()))
    expect(len(o.dgram) == 48, label + ": the token is bound to the address and port")
    o = c.recv(d1)
    if not expect(len(o.dgram) == 1296, label + ": C answers step 1 with step 2"):
        return None
    r = cl.on_step2(o.dgram)
    if not expect(r[0] == "step3", label + ": Python accepts C's step 2"):
        return None
    tampered = r[1][:100] + bytes([r[1][100] ^ 1]) + r[1][101:]
    tampered = tampered[:-16] + peer.mac16(cl.k_f, tampered[:-16])
    expect(c.recv(tampered).status == ST_DROP, label + ": C drops a forged step 3")
    o = c.recv(r[1])
    if not expect(len(o.dgram) == 80, label + ": C answers step 3 with step 4"):
        return None
    r = cl.on_step4(o.dgram)
    o = c.recv(r[1])
    if not expect(len(o.dgram) == 80, label + ": C answers step 5 with step 6"):
        return None
    r = cl.on_step6(o.dgram)
    expect(r[0] == "code", label + ": Python checks C's commitment")
    if ask == 1:
        expect(o.has(EV_ASK) and o.name == name32(b"Py phone") and o.pflags == ((GUEST if guest_req else 0) | MIC),
               label + ": C asks its user with the name and flags")
        expect(o.code == (cl.code if kind == PIN else 0), label + ": the code is shown in PIN mode")
        expect(o.addr == (PY_IP, PORT), label + ": the question names the client's address")
        o = c.out("answer %d %d 3 %d" % (o.ask, 0 if refuse else 1, answer_guest))
    else:
        expect(not o.has(EV_ASK), label + ": a headless window asks nobody")
        for _ in range(8):
            o = c.out("poll")
            if len(o.dgram) == 120:
                break
    if not expect(len(o.dgram) == 120, label + ": C sends step 7"):
        return None
    r = cl.on_step7(o.dgram)
    if refuse:
        expect(r == ("refused",), label + ": Python sees the refusal")
        o = c.recv(cl.step8(0, bytes(32)))
        expect(o.has(EV_FAILED) and o.status == ST_OK, label + ": C ends the attempt")
        expect(c.cmd("wfailed")[1] == "0", label + ": its own no is not counted")
        c.close()
        return None
    expect(r[0] == "decision" and r[1]["name"] == name32(b"C speaker") and r[1]["perms"] == 3,
           label + ": Python reads C's decision")
    guest = 1 if (guest_req or answer_guest) else 0
    expect(r[1]["guest"] == guest, label + ": guest as requested or chosen")
    expect((r[1]["entry"] == c.entry) == (guest == 0), label + ": the device entry key only to a non-guest")
    py_entry = os.urandom(32)
    o = c.recv(cl.step8(1, py_entry))
    expect(len(o.dgram) == 52 and o.has(EV_PAIRED), label + ": C stores the pair and sends step 9")
    expect(o.has(EV_STORE_PAIR) == (guest == 0), label + ": STORE_PAIR only for a non-guest")
    expect(o.name == name32(b"Py phone") and o.addr == (PY_IP, PORT), label + ": PAIRED names the peer")
    slot = o.pair
    r = cl.on_step9(o.dgram)
    if not expect(r[0] == "paired", label + ": Python completes"):
        return None
    py = r[1]
    if guest:
        try:
            c.record(slot)
            expect(False, label + ": a guest record is never exported")
        except RuntimeError:
            expect(True, label + ": a guest record is never exported")
    else:
        same_record(c.record(slot), py, label)
    session_after(c, py, label, guest == 1)
    return c, py, slot


def py_server(path, kind, label, flags=MIC, confirm_early=False, wrong_w=False, replace_of=None, c=None):
    c = c or PDriver(path)
    if kind == LABEL:
        win = peer_pair.Window("label", name=b"Py speaker", secret=os.urandom(16), w_priv=os.urandom(32))
    else:
        win = peer_pair.Window("pin" if kind == PIN else "code", name=b"Py speaker")
    code = win.code()
    if wrong_w:
        code = code[:16] + peer_pair.fingerprint(ref.x25519_public(os.urandom(32)))
    if kind == PIN:
        expect(c.cmd("beacons 1") == ["ok"], label + ": C listens to beacons")
        o = c.recv(win.beacon())
        expect(o.has(EV_BEACON) and o.beacon["name"] == name32(b"Py speaker") and
               o.beacon["nonce"] == win.nonce and o.beacon["ip"] == PY_IP, label + ": C reports the beacon")
        o = c.pair(PIN, code, flags, 3, NONE if replace_of is None else replace_of[0],
                   beacon_ip=o.beacon["ip"], beacon_port=o.beacon["port"], nonce=o.beacon["nonce"])
    else:
        o = c.pair(kind, code, flags, 3, NONE if replace_of is None else replace_of[0])
        expect(o.status == ST_OK and not o.dgram, label + ": C waits for the beacon")
        other = peer_pair.Window("code")
        expect(not c.recv(other.beacon()).dgram, label + ": C ignores another window's beacon")
        if kind == LABEL:
            old = peer_pair.Window("label", name=b"Py speaker", secret=win.prs, w_priv=win.w_priv)
            old.used = 1
            o = c.recv(old.beacon())
            expect(not o.dgram and not o.has(EV_FAILED), label + ": C ignores an old used label beacon")
        o = c.recv(win.beacon())
    if not expect(len(o.dgram) == 1368 and o.to == (PY_IP, PORT), label + ": C sends step 1"):
        return None
    r = win.on_step1(o.dgram, C_IP, PORT)
    if not expect(r[0] == "retry", label + ": Python answers with a Retry"):
        return None
    o = c.recv(r[1])
    if not expect(len(o.dgram) == 1368 and o.dgram[1264:1280] == r[1][16:32],
                  label + ": C sends step 1 again at once, with the token"):
        return None
    r = win.on_step1(o.dgram, C_IP, PORT)
    if not expect(r[0] == "step2", label + ": Python accepts C's step 1"):
        return None
    forged = bytearray(r[1])
    forged[300] ^= 1
    forged[-16:] = peer.mac16(win.k_f, bytes(forged[:-16]))
    expect(c.recv(bytes(forged)).status == ST_DROP, label + ": C ignores a forged step 2")
    o = c.recv(r[1])
    if wrong_w:
        expect(o.status == ST_DROP and not o.has(EV_FAILED), label + ": C ignores a step 2 with a wrong key")
        c.time(5100)
        for _ in range(8):
            o = c.out("poll")
            if o.has(EV_FAILED):
                break
        expect(o.has(EV_FAILED | EV_RACE) and o.answer == PAIRF_KEY_C,
               label + ": C fails KEY, with the warning, 5 s later")
        c.close()
        return None
    if not expect(len(o.dgram) == 176, label + ": C answers step 2 with step 3"):
        return None
    r = win.on_step3(o.dgram)
    if not expect(r[0] == "step4", label + ": Python accepts C's step 3"):
        return None
    expect(win.ctx["name"] == name32(b"C phone") and win.ctx["flags"] == flags, label + ": C's name and flags")
    o = c.recv(r[1])
    if not expect(len(o.dgram) == 80, label + ": C answers step 4 with step 5"):
        return None
    r = win.on_step5(o.dgram)
    if not expect(r[0] == "step6", label + ": Python checks C's commitment"):
        return None
    o = c.recv(r[1])
    expect(o.has(EV_CODE) and o.code == r[2] and not o.dgram, label + ": C shows the same 6-digit code")
    if kind == PIN and confirm_early:
        o = c.out("confirm 1")
        expect(o.status == ST_OK and not o.dgram, label + ": C confirms before step 7")
    py_entry = os.urandom(32)
    guest = 1 if flags & GUEST else 0
    o = c.recv(win.step7(1, 3, guest, py_entry))
    if kind == PIN and not confirm_early:
        expect(not o.dgram, label + ": C waits for its user before step 8")
        o = c.out("confirm 1")
    if not expect(len(o.dgram) == 100, label + ": C sends step 8"):
        return None
    old = [replace_of[1]] if replace_of is not None else []
    r = win.on_step8(o.dgram, old)
    if not expect(r[0] == "paired", label + ": Python stores the pair"):
        return None
    py = r[2]
    if replace_of is not None:
        expect(py["replaced"] == 0, label + ": Python recognises the pair C replaces")
    expect((py["peer_entry"] == c.entry) == (guest == 0), label + ": C gives its entry key only to a non-guest")
    o = c.recv(r[1])
    expect(o.has(EV_PAIRED) and o.has(EV_STORE_PAIR) == (guest == 0), label + ": C completes")
    expect(o.name == name32(b"Py speaker"), label + ": C learned the server's name")
    slot = o.pair
    # Python as server: its record has the server's view; swap to the C view.
    if not guest:
        same_record(c.record(slot), py, label)
    if replace_of is None and not guest:
        session_after(c, py, label)
    return c, py, slot


def test_c_server(path):
    c_server(path, CODE, "C server code")
    c_server(path, LABEL, "C server label", ask=0)
    c_server(path, PIN, "C server PIN")
    c_server(path, CODE, "C server guest", guest_req=True)
    c_server(path, CODE, "C server makes a guest", answer_guest=1)
    c_server(path, CODE, "C server refuses", refuse=True)


def test_py_server(path):
    py_server(path, CODE, "Py server code")
    py_server(path, LABEL, "Py server label", flags=0)
    py_server(path, PIN, "Py server PIN")
    py_server(path, PIN, "Py server PIN, early confirm", confirm_early=True)
    py_server(path, CODE, "Py server guest", flags=GUEST)
    py_server(path, CODE, "Py server wrong key", wrong_w=True)


def test_replace(path):
    # C client replaces its pair with a Python server.
    res = py_server(path, CODE, "replace 1")
    if res is None:
        return
    c, py, slot = res
    res2 = py_server(path, CODE, "replace 2", replace_of=(slot, py["k_fix"]), c=c)
    if res2 is None:
        return
    o = c.out("poll")
    expect(o.has(EV_ERASED) and o.pair == slot, "replace: C removes the old pair at the next poll")
    c.close()
    # A Python client replaces its pair with a C server.
    res = c_server(path, CODE, "replace 3")
    if res is None:
        return
    c, py, slot = res
    c.time(130000)
    for _ in range(20):
        if c.out("poll").has(EV_CLOSED):
            break
    o = c.out("window 1 0 1 -")
    code = bytes.fromhex(c.cmd("code")[1])
    cl = peer_pair.Client(name=b"Py phone", code=code)
    cl.on_beacon(o.dgram)
    o = c.recv(cl.step1())
    o = c.recv(cl.on_retry(o.dgram)[1])
    o = c.recv(cl.on_step2(o.dgram)[1])
    o = c.recv(cl.on_step4(o.dgram)[1])
    cl.on_step6(o.dgram)
    for _ in range(8):
        o = c.out("poll")
        if len(o.dgram) == 120:
            break
    cl.on_step7(o.dgram)
    o = c.recv(cl.step8(1, os.urandom(32), old_k_fix=py["k_fix"]))
    expect(o.has(EV_PAIRED) and o.pair != slot, "replace 4: C pairs again")
    o = c.out("poll")
    expect(o.has(EV_ERASED | EV_STORE_DEVICE) and o.pair == slot,
           "replace 4: the replaced pair is removed with an entry key rotation")
    c.close()


def main():
    path = sys.argv[1]
    for scenario in (test_c_server, test_py_server, test_replace):
        try:
            scenario(path)
        except Exception as exc:    # a broken step must fail the test, not stop it
            expect(False, "%s stopped: %s: %s" % (scenario.__name__, type(exc).__name__, exc))
    print("interop pairing: %d checks, %d failed" % (interop.RUN, interop.FAILED))
    return 0 if interop.FAILED == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
