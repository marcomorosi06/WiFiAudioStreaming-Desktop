#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Marco Morosi
#
# Second implementation of WFAS v3 transport frames (section 10.2) and
# multicast (section 14), written from the specification text, not from
# wfas.c. It works on the sessions of tests/peer.py. Used only by
# tests/interop_mcast.py.

import os

import ref
from peer import aead_open, header, mac8, p64, u16, u32, u64

T_TRANSPORT = 0x20
T_MULTICAST = 0x30

AUDIO, START, STOP, REPORT = 0x01, 0x03, 0x04, 0x05
GROUP_JOIN, GROUP_ACK, GROUP_LEAVE, NACK, BYE = 0x12, 0x13, 0x14, 0x15, 0x0F
FLEN = {3: 4, 4: 0, 5: 21, 6: 8, 7: 24, 8: 20, 9: 4, 10: 66, 11: 66, 12: 5, 13: 8, 14: 8,
        15: 1, 16: 32, 17: 8, 18: 104, 19: 8, 20: 4, 21: 12, 22: 21, 23: 0}
SLOTS = 16


def frame(t, content):
    return bytes([t]) + len(content).to_bytes(2, "big") + content


def parse_frames(pt, payload_bytes=None):
    """10.2: a list of (type, content), or None if not well formed."""
    out, pos, seen = [], 0, set()
    while pos < len(pt):
        t = pt[pos]
        if t == 0:
            return out if pt[pos:] == bytes(len(pt) - pos) else None
        if pos + 3 > len(pt) or t in seen or t > 0x17:
            return None
        n = u16(pt[pos + 1:pos + 3])
        want = 20 + payload_bytes if t in (1, 2) else FLEN.get(t)
        if want is None or n != want or pos + 3 + n > len(pt):
            return None
        out.append((t, pt[pos + 3:pos + 3 + n]))
        seen.add(t)
        pos += 3 + n
    return out


def control(sess, frames):
    """A control packet (10.1, 10.3) of the given frames, padded to 128."""
    pt = b"".join(frame(t, c) for t, c in frames)
    assert len(pt) <= 128
    pt += bytes(128 - len(pt))
    d = header(T_TRANSPORT) + sess.send_index + p64(sess.send_ctr)
    d += ref.seal(sess.k_send, sess.send_ctr, d, pt)
    sess.send_ctr += 1
    return d


def on_transport(sess, d, payload_bytes):
    """Any transport packet of sess: its frames, or None."""
    if d[:4] != header(T_TRANSPORT) or d[4:8] != sess.recv_index:
        return None
    if len(d) not in (160, payload_bytes + 55):
        return None
    ctr = u64(d[8:16])
    if ctr in sess.seen or ctr < sess.recv_max - 4095 or ctr >= 1 << 60:
        return None
    pt = aead_open(sess.k_recv, ctr, d[:16], d[16:])
    if pt is None:
        return None
    sess.seen.add(ctr)
    sess.recv_max = max(sess.recv_max, ctr)
    return parse_frames(pt, payload_bytes)


def report(last_capture_us=0, received=0, lost=0, jitter=0, buffer_ms=0):
    return (REPORT, p64(last_capture_us) + received.to_bytes(4, "big") + lost.to_bytes(4, "big") +
            jitter.to_bytes(4, "big") + bytes([buffer_ms]))


def join_content(group, epoch, counter, slot, ip, port, key, tag_key):
    """14.2."""
    return (group.to_bytes(4, "big") + epoch.to_bytes(4, "big") + p64(counter) + bytes([slot]) +
            bytes(3) + ip + port.to_bytes(2, "big") + bytes(2) + key + tag_key)


def parse_join(c):
    if len(c) != 104 or c[17:20] != bytes(3) or c[38:40] != bytes(2) or c[16] >= SLOTS:
        return None
    return {"group": u32(c[0:4]), "epoch": u32(c[4:8]), "counter": u64(c[8:16]), "slot": c[16],
            "ip": c[20:36], "port": u16(c[36:38]), "key": c[40:72], "tag_key": c[72:104]}


def mcast_nonce(epoch, counter):
    return epoch.to_bytes(4, "big") + p64(counter)


def mcast_packet(group, epoch, counter, key, tag_keys, stream_epoch, pos, cap, data):
    """14.1. tag_keys: slot -> key; the other slots get random bytes."""
    from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
    d = header(T_MULTICAST) + group.to_bytes(4, "big") + epoch.to_bytes(4, "big") + p64(counter)
    content = stream_epoch.to_bytes(4, "big") + p64(pos) + p64(cap) + data
    d += ChaCha20Poly1305(key).encrypt(mcast_nonce(epoch, counter), frame(AUDIO, content), d)
    tags = b"".join(mac8(tag_keys[i], d) if i in tag_keys else os.urandom(8) for i in range(SLOTS))
    return d + tags


def mcast_open(d, slot, key, tag_key, payload_bytes):
    """Receiver of 14.1: (epoch, counter, AUDIO content) or None."""
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
    if len(d) != payload_bytes + 187 or d[:4] != header(T_MULTICAST):
        return None
    body = d[:-128]
    if mac8(tag_key, body) != d[len(body) + 8 * slot:len(body) + 8 * slot + 8]:
        return None
    epoch, counter = u32(d[8:12]), u64(d[12:20])
    try:
        pt = ChaCha20Poly1305(key).decrypt(mcast_nonce(epoch, counter), body[20:], d[:20])
    except InvalidTag:
        return None
    f = parse_frames(pt, payload_bytes)
    if f is None or len(f) != 1 or f[0][0] != AUDIO:
        return None
    return epoch, counter, f[0][1]
