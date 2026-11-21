#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Marco Morosi
#
# Independent reference for the cryptographic layer of WFAS v3, written from
# WFAS_V3_PROTOCOL.md (sections 2 and 2.1) and not from the C code. Used only
# by the tests: it produces the known answers that the C core must match.
#
# Primitives come from other implementations than the ones the core uses:
# hashlib/hmac (SHA-2, HMAC), 'cryptography' (X25519, ChaCha20-Poly1305) and
# 'kyber-py' (ML-KEM-768, FIPS 203). Elligator 2 and CPace are written here
# from the definitions of draft-irtf-cfrg-cpace-21 (appendix "Elligator 2
# reference implementation", with Python integers), not from the
# straight-line algorithm that the C core follows.

import hashlib
import hmac

from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives import serialization
from kyber_py.ml_kem import ML_KEM_768


def sha256(b):
    return hashlib.sha256(b).digest()


def hmac256(k, m):
    return hmac.new(k, m, hashlib.sha256).digest()


def hkdf_extract(salt, ikm):
    return hmac256(salt, ikm)


def hkdf_expand(prk, info, length):
    out, t, i = b"", b"", 1
    while len(out) < length:
        t = hmac256(prk, t + info + bytes([i]))
        out += t
        i += 1
    return out[:length]


def kdf(key, label, ctx=b"", length=32):
    return hkdf_expand(key, b"WFAS3 " + label + ctx, length)


def nonce(n):
    return b"\x00\x00\x00\x00" + n.to_bytes(8, "big")


def seal(key, n, ad, pt):
    return ChaCha20Poly1305(key).encrypt(nonce(n), pt, ad)


def x25519_public(priv):
    return X25519PrivateKey.from_private_bytes(priv).public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def x25519(priv, pub):
    return X25519PrivateKey.from_private_bytes(priv).exchange(X25519PublicKey.from_public_bytes(pub))


class Sym:
    """Handshake symmetric state, specification section 2.1."""

    def __init__(self, name):
        self.h = sha256(name)
        self.ck = self.h
        self.k = None
        self.n = 0
        self.mix_hash(b"WFAS3\x01")

    def mix_hash(self, d):
        self.h = sha256(self.h + d)

    def mix_key(self, ikm):
        okm = hkdf_expand(hkdf_extract(self.ck, ikm), b"", 64)
        self.ck, self.k, self.n = okm[:32], okm[32:], 0

    def mix_key_and_hash(self, ikm):
        okm = hkdf_expand(hkdf_extract(self.ck, ikm), b"", 96)
        self.ck = okm[:32]
        self.mix_hash(okm[32:64])
        self.k, self.n = okm[64:], 0

    def encrypt_and_hash(self, pt):
        ct = seal(self.k, self.n, self.h, pt)
        self.n += 1
        self.mix_hash(ct)
        return ct


class HmacDrbg:
    """HMAC_DRBG with SHA-256, SP 800-90A section 10.1.2."""

    def __init__(self, seed_material):
        self.k = b"\x00" * 32
        self.v = b"\x01" * 32
        self._update(seed_material)

    def _update(self, data):
        self.k = hmac256(self.k, self.v + b"\x00" + data)
        self.v = hmac256(self.k, self.v)
        if data:
            self.k = hmac256(self.k, self.v + b"\x01" + data)
            self.v = hmac256(self.k, self.v)

    def reseed(self, entropy):
        self._update(entropy)

    def generate(self, n):
        out = b""
        while len(out) < n:
            self.v = hmac256(self.k, self.v)
            out += self.v
        self._update(b"")
        return out[:n]


def fill(n, start):
    return bytes((start + i) & 0xFF for i in range(n))


def selftest_answer():
    """The chain of wfas3_selftest(), specification by construction."""
    s = Sym(b"WFAS3_SELFTEST")
    s.mix_key_and_hash(fill(32, 0x00))
    e_priv = fill(32, 0x20)
    e_pub = x25519_public(e_priv)
    s.mix_hash(e_pub)
    s.mix_key(e_pub)
    rnd = fill(64, 0x60)
    pk, sk = ML_KEM_768._keygen_internal(rnd[:32], rnd[32:])
    s.encrypt_and_hash(pk)
    s_priv = fill(32, 0x40)
    s.mix_key(x25519(e_priv, x25519_public(s_priv)))
    ss, ct = ML_KEM_768._encaps_internal(pk, fill(32, 0xA0))
    s.encrypt_and_hash(ct)
    assert ML_KEM_768.decaps(sk, ct) == ss
    s.mix_key(ss)
    seed = fill(48, 0xC0)
    drbg = HmacDrbg(seed[:32] + seed[32:] + b"WFAS3")
    r1 = drbg.generate(32)
    drbg.reseed(fill(32, 0x10))
    r2 = drbg.generate(32)
    s.mix_key(r1 + r2)
    s.mix_hash(hashlib.sha512(s.h).digest())
    key = kdf(s.ck, b"selftest", s.h)
    return hmac256(key, s.h)[:16]


# -- CPace, suite CPACE-X25519-SHA512 (draft-irtf-cfrg-cpace-21) --------------

P25519 = 2 ** 255 - 19
A25519 = 486662


def prepend_len(data):
    """LEB128 length followed by the data."""
    n = len(data)
    enc = b""
    while True:
        enc += bytes([(n & 0x7F) | (0x80 if n >= 128 else 0)])
        n >>= 7
        if n == 0:
            break
    return enc + data


def lv_cat(*args):
    return b"".join(prepend_len(a) for a in args)


def ell2_u(u):
    """Elligator 2 of the draft, Z = 2: u-coordinate (32 bytes, little-endian)
    of the point that the field element u (an integer) maps to."""
    q = P25519
    r = u % q
    v = (-A25519 * pow(1 + 2 * r * r, q - 2, q)) % q
    eps = pow((v * v * v + A25519 * v * v + v) % q, (q - 1) // 2, q)
    x = v if eps == 1 else (-v - A25519) % q
    return x.to_bytes(32, "little")


def cpace_generator(prs, ci, sid):
    dsi = b"CPace255"
    zpad = max(0, 128 - 1 - len(prepend_len(prs)) - len(prepend_len(dsi)))
    gen = lv_cat(dsi, prs, bytes(zpad), ci, sid)
    h = bytearray(hashlib.sha512(gen).digest()[:32])
    h[31] &= 0x7F
    return ell2_u(int.from_bytes(bytes(h), "little"))


def cpace_isk(sid, k, ya, ada, yb, adb):
    return hashlib.sha512(lv_cat(b"CPace255_ISK", sid, k) + lv_cat(ya, ada) + lv_cat(yb, adb)).digest()
