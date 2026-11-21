#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Marco Morosi
#
# Interoperability test of multicast (section 14): the C core (through
# build/interop) against the second implementation in tests/peer_mcast.py,
# with the C device as group source and as receiver. GROUP_JOIN, GROUP_ACK,
# GROUP_LEAVE and NACK travel in real sessions; group packets are built and
# checked byte for byte on each side.
#
# Usage: python3 -I tests/interop_mcast.py build/interop

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import interop  # noqa: E402
import peer  # noqa: E402
import peer_mcast as pm  # noqa: E402
from interop import ST_OK, ST_DROP, expect  # noqa: E402
from peer import config, u32, u64  # noqa: E402

EV_UP, EV_AUDIO = 0x04, 0x100
EV_GROUP_JOIN, EV_GROUP_LEAVE = 0x8000000, 0x10000000
PB = 960
GROUP_IP = bytes(10) + b"\xff\xff" + bytes([239, 9, 9, 9])
GROUP_PORT = 14115


class MOut(interop.Out):
    def __init__(self, f):
        super().__init__(f)
        self.audio = None


def handshake(w, cfg, label):
    """Python opens a session with cfg; returns (Python session, C session)."""
    w.advance()
    o = w.c.recv(w.py.cookie_request())
    w.py.on_cookie_reply(o.dgram)
    o = w.c.recv(w.py.init(cfg))
    r = w.py.on_resp(o.dgram)
    if not expect(r[0] == "up", label + ": the session with bit2 is accepted"):
        return None, None
    o = w.c.recv(pm.control(r[1], []))
    if not expect(o.has(EV_UP), label + ": C confirms the session"):
        return None, None
    return r[1], o.session


def c_frames(w, sess, csess):
    """The frames of C's next control packet of csess, 100 ms on."""
    w.advance(100)
    o = w.c.control(csess)
    f = pm.on_transport(sess, o.dgram, PB)
    expect(f is not None, "C's control packet opens")
    return dict(f or [])


def test_c_source(path):
    w = interop.World(path)
    expect(w.c.cmd("gopen 5 1 2 16 48000 240 %d 200 %s %d" % (PB, GROUP_IP.hex(), GROUP_PORT)) == ["ok"],
           "C source: group opens")
    sess, cs = handshake(w, config(flags=4, latency=200), "C source")
    if sess is None:
        return
    w.advance(10)
    o = w.c.recv(pm.control(sess, [(pm.START, bytes(4))]))
    expect(o.status == ST_OK, "C source: START accepted")
    expect(w.c.cmd("gmembers") == ["ok", "1"], "C source: Python is a member")
    f = c_frames(w, sess, cs)
    j = pm.parse_join(f.get(pm.GROUP_JOIN, b""))
    if not expect(j is not None, "C source: GROUP_JOIN in the next control packet"):
        return
    expect(j["ip"] == GROUP_IP and j["port"] == GROUP_PORT and j["epoch"] >= 1, "C source: group address")
    o = w.c.recv(pm.control(sess, [pm.report(), (pm.GROUP_ACK, j["group"].to_bytes(4, "big") +
                                                  j["epoch"].to_bytes(4, "big"))]))
    expect(o.status == ST_OK, "C source: GROUP_ACK and REPORT accepted")
    w.advance(5)
    data = bytes(range(256)) * 3 + bytes(PB - 768)
    pkts = []
    for i in range(4):
        o = w.c.out("gsend %d %d %s" % (240 * i, w.now * 1000, data.hex()))
        if not expect(o.status == ST_OK and len(o.dgram) == PB + 187 and o.to == (GROUP_IP, GROUP_PORT),
                      "C source: a group packet to the group address"):
            return
        pkts.append(o.dgram)
    for i, d in enumerate(pkts):
        r = pm.mcast_open(d, j["slot"], j["key"], j["tag_key"], PB)
        if not expect(r is not None, "C source: Python opens group packet %d" % i):
            continue
        epoch, ctr, content = r
        expect(u32(d[4:8]) == j["group"] and epoch == j["epoch"] and ctr == j["counter"] + i,
               "C source: group, epoch, counter")
        expect(u32(content[:4]) == 0 and u64(content[4:12]) == 240 * i and content[20:] == data,
               "C source: the AUDIO frame")
    other = (j["slot"] + 1) % 16
    expect(pm.mcast_open(pkts[0], other, j["key"], j["tag_key"], PB) is None,
           "C source: the tag of another slot is not ours")
    # NACK for counters 1 and 2: resent as audio in the session.
    o = w.c.recv(pm.control(sess, [pm.report(w.now * 1000),
                                    (pm.NACK, peer.p64(j["counter"] + 1) + (3).to_bytes(4, "big"))]))
    expect(o.status == ST_OK, "C source: NACK accepted")
    got = []
    for _ in range(6):
        o = w.c.out("poll")
        if len(o.dgram) == PB + 55:
            f = pm.on_transport(sess, o.dgram, PB)
            if f and f[0][0] == pm.AUDIO:
                got.append(u64(f[0][1][4:12]))
        elif not o.dgram and o.events == 0:
            break
    expect(sorted(got) == [240, 480], "C source: both packets resent in the session")
    # The packet resent is too late for its playback instant: nothing.
    w.advance(300)
    o = w.c.recv(pm.control(sess, [pm.report(w.now * 1000 - 100000),
                                    (pm.NACK, peer.p64(j["counter"] + 3) + (1).to_bytes(4, "big"))]))
    late = False
    for _ in range(6):
        o = w.c.out("poll")
        late |= len(o.dgram) == PB + 55
        if not o.dgram and o.events == 0:
            break
    expect(not late, "C source: no resend after the playback instant")
    # Python leaves.
    o = w.c.recv(pm.control(sess, [(pm.GROUP_LEAVE, j["group"].to_bytes(4, "big"))]))
    o = w.c.out("poll")
    expect(w.c.cmd("gmembers") == ["ok", "0"], "C source: GROUP_LEAVE ends the membership")
    w.c.close()


def test_c_receiver(path):
    w = interop.World(path)
    sess, cs = handshake(w, config(flags=5), "C receiver")
    if sess is None:
        return
    expect(w.c.cmd("want %d 1" % cs) == ["ok"], "C receiver: the application wants the stream")
    f = c_frames(w, sess, cs)
    expect(f.get(pm.START) == bytes(4), "C receiver: START(0)")
    group, slot = 0x01020304, 5
    key, tk = os.urandom(32), {i: os.urandom(32) for i in range(16)}
    c = pm.join_content(group, 1, 100, slot, GROUP_IP, GROUP_PORT, key, tk[slot])
    o = w.c.recv(pm.control(sess, [(pm.GROUP_JOIN, c)]))
    expect(o.has(EV_GROUP_JOIN) and o.addr == (GROUP_IP, GROUP_PORT), "C receiver: GROUP_JOIN event")
    f = c_frames(w, sess, cs)
    expect(f.get(pm.GROUP_ACK) == group.to_bytes(4, "big") + (1).to_bytes(4, "big"),
           "C receiver: GROUP_ACK for epoch 1")
    data = os.urandom(PB)

    def pkt(epoch, ctr, k, tags, pos):
        return pm.mcast_packet(group, epoch, ctr, k, tags, 0, pos, w.now * 1000, data)

    w.advance(5)
    o = w.c.recv(pkt(1, 100, key, tk, 0))
    expect(o.has(EV_AUDIO) and o.status == ST_OK, "C receiver: group audio delivered")
    expect(w.c.recv(pkt(1, 100, key, tk, 0)).status == ST_DROP, "C receiver: a replay is dropped")
    expect(w.c.recv(pkt(1, 99, key, tk, 960)).status == ST_DROP, "C receiver: before the join counter")
    bad = {i: tk[i] for i in range(16) if i != slot}
    expect(w.c.recv(pkt(1, 101, key, bad, 240)).status == ST_DROP, "C receiver: no tag of its slot")
    expect(w.c.recv(pkt(1, 101, os.urandom(32), tk, 240)).status == ST_DROP, "C receiver: wrong key")
    expect(w.c.recv(pkt(1, 101, key, tk, 240)).has(EV_AUDIO), "C receiver: next packet")
    # A gap: 102 and 103 are missing.
    expect(w.c.recv(pkt(1, 104, key, tk, 960)).has(EV_AUDIO), "C receiver: after a gap")
    f = c_frames(w, sess, cs)
    expect(f.get(pm.NACK) == peer.p64(102) + (3).to_bytes(4, "big"), "C receiver: NACK for 102 and 103")
    # The missing packet comes in the session; the group copy later is not delivered twice.
    pos = 480
    ad = pm.frame(pm.AUDIO, (0).to_bytes(4, "big") + peer.p64(pos) + peer.p64(w.now * 1000) + data)
    d = peer.header(pm.T_TRANSPORT) + sess.send_index + peer.p64(sess.send_ctr)
    d += peer.ref.seal(sess.k_send, sess.send_ctr, d, ad)
    sess.send_ctr += 1
    expect(w.c.recv(d).has(EV_AUDIO), "C receiver: a resent packet in the session")
    expect(not w.c.recv(pkt(1, 102, key, tk, pos)).has(EV_AUDIO), "C receiver: its group copy is not played again")
    # A new epoch: the old one still works for 2 s.
    key2, tk2 = os.urandom(32), {i: os.urandom(32) for i in range(16)}
    c = pm.join_content(group, 2, 105, slot, GROUP_IP, GROUP_PORT, key2, tk2[slot])
    o = w.c.recv(pm.control(sess, [(pm.GROUP_JOIN, c)]))
    expect(not o.has(EV_GROUP_JOIN), "C receiver: a new epoch is no new join")
    f = c_frames(w, sess, cs)
    expect(f.get(pm.GROUP_ACK) == group.to_bytes(4, "big") + (2).to_bytes(4, "big"),
           "C receiver: GROUP_ACK for epoch 2")
    expect(w.c.recv(pkt(1, 105, key, tk, 1200)).has(EV_AUDIO), "C receiver: the old epoch for a while")
    expect(w.c.recv(pkt(2, 106, key2, tk2, 1440)).has(EV_AUDIO), "C receiver: the new epoch")
    w.advance(2100)
    expect(w.c.recv(pkt(1, 107, key, tk, 1680)).status == ST_DROP, "C receiver: the old epoch ends after 2 s")
    expect(w.c.recv(pkt(2, 108, key2, tk2, 1920)).has(EV_AUDIO), "C receiver: the new epoch goes on")
    # The source sends it away.
    o = w.c.recv(pm.control(sess, [(pm.GROUP_LEAVE, group.to_bytes(4, "big"))]))
    expect(o.has(EV_GROUP_LEAVE) and o.addr == (GROUP_IP, GROUP_PORT), "C receiver: GROUP_LEAVE event")
    expect(w.c.recv(pkt(2, 109, key2, tk2, 2160)).status == ST_DROP, "C receiver: nothing after leaving")
    # Malformed GROUP_JOIN: not a multicast address -> protocol error, session closes.
    c = pm.join_content(group, 3, 200, slot, interop.PY_IP, GROUP_PORT, key2, tk2[slot])
    o = w.c.recv(pm.control(sess, [(pm.GROUP_JOIN, c)]))
    expect(o.has(0x08), "C receiver: a GROUP_JOIN with a unicast address closes the session")
    w.c.close()


def main():
    path = sys.argv[1]
    for scenario in (test_c_source, test_c_receiver):
        try:
            scenario(path)
        except Exception as exc:    # a broken step must fail the test, not stop it
            expect(False, "%s stopped: %s: %s" % (scenario.__name__, type(exc).__name__, exc))
    print("interop multicast: %d checks, %d failed" % (interop.RUN, interop.FAILED))
    return 0 if interop.FAILED == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
