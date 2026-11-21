#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Marco Morosi
#
# Writes tests/vectors.h from the reference in ref.py.
# Usage: python3 -I gen_vectors.py <path to ref.py directory> > vectors.h
#
# Official test data, copied from the documents named below, is checked
# against ref.py first: the script refuses to write anything if the reference
# disagrees with it.
#  - RFC 9380 appendix J.4.1 and J.4.2 (curve25519_XMD:SHA-512_ELL2_RO_ and
#    _NU_): field element u and the u-coordinate of Q = map_to_curve(u).
#  - draft-irtf-cfrg-cpace-21 appendix B.1 (CPace, X25519, SHA-512):
#    calculate_generator, Ya, Yb, K, ISK, and the low-order points.

import hashlib
import sys

sys.path.insert(0, sys.argv[1])
import ref  # noqa: E402


def arr(name, data):
    body = ", ".join("0x%02xU" % b for b in data)
    return "static const uint8_t %s[%d] = { %s };" % (name, len(data), body)


# RFC 9380 J.4: (u, x of map_to_curve(u)), big-endian hexadecimal.
RFC9380_ELL2 = [
    ("608d892b641f0328523802a6603427c26e55e6f27e71a91a478148d45b5093cd",
     "51125222da5e763d97f3c10fcc92ea6860b9ccbbd2eb1285728f566721c1e65b"),
    ("46f5b22494bfeaa7f232cc8d054be68561af50230234d7d1d63d1d9abeca8da5",
     "7d56d1e08cb0ccb92baf069c18c49bb5a0dcd927eff8dcf75ca921ef7f3e6eeb"),
    ("235fe40c443766ce7e18111c33862d66c3b33267efa50d50f9e8e5d252a40aaa",
     "3fbe66b9c9883d79e8407150e7c2a1c8680bee496c62fabe4619a72b3cabe90f"),
    ("001e92a544463bda9bd04ddbe3d6eed248f82de32f522669efc5ddce95f46f5b",
     "227e0bb89de700385d19ec40e857db6e6a3e634b1c32962f370d26f84ff19683"),
    ("1a68a1af9f663592291af987203393f707305c7bac9c8d63d6a729bdc553dc19",
     "3bcd651ee54d5f7b6013898aab251ee8ecc0688166fce6e9548d38472f6bd196"),
    ("005fe8a7b8fef0a16c105e6cadf5a6740b3365e18692a9c05bfbb4d97f645a6a",
     "36b4df0c864c64707cbf6cf36e9ee2c09a6cb93b28313c169be29561bb904f98"),
    ("1347edbec6a2b5d8c02e058819819bee177077c9d10a4ce165aab0fd0252261a",
     "3fa114783a505c0b2b2fbeef0102853c0b494e7757f2a089d0daae7ed9a0db2b"),
    ("49bed021c7a3748f09fa8cdfcac044089f7829d3531066ac9e74e0994e05bc7d",
     "16b3d86e056b7970fa00165f6f48d90b619ad618791661b7b5e1ec78be10eac1"),
    ("5c36525b663e63389d886105cee7ed712325d5a97e60e140aba7e2ce5ae851b6",
     "7ec29ddbf34539c40adfa98fcb39ec36368f47f30e8f888cc7e86f4d46e0c264"),
    ("6412b7485ba26d3d1b6c290a8e1435b2959f03721874939b21782df17323d160",
     "71de3dadfe268872326c35ac512164850860567aea0e7325e6b91a98f86533ad"),
    ("24c7b46c1c6d9a21d32f5707be1380ab82db1054fde82865d5c9e3d968f287b2",
     "5704069021f61e41779e2ba6b932268316d6d2a6f064f997a22fef16d1eaeaca"),
    ("5e123990f11bbb5586613ffabdb58d47f64bb5f2fa115f8ea8df0188e0c9e1b5",
     "7a94d45a198fb5daa381f45f2619ab279744efdd8bd8ed587fc5b65d6cea1df0"),
    ("5e8553eb00438a0bb1e7faa59dec6d8087f9c8011e5fb8ed9df31cb6c0d4ac19",
     "30506fb7a32136694abd61b6113770270debe593027a968a01f271e146e60c18"),
    ("20f481e85da7a3bf60ac0fb11ed1d0558fc6f941b3ac5469aa8b56ec883d6d7d",
     "02d606e2699b918ee36f2818f2bc5013e437e673c9f9b9cdc15fd0c5ee913970"),
    ("017d57fd257e9a78913999a23b52ca988157a81b09c5442501d07fed20869465",
     "38920e9b988d1ab7449c0fa9a6058192c0c797bb3d42ac345724341a1aa98745"),
]

# draft-irtf-cfrg-cpace-21 B.1, little-endian hexadecimal as printed there.
CPACE = {
    "PRS": b"Password",
    "CI": "0b415f696e69746961746f720b425f726573706f6e646572",
    "sid": "7e4b4791d6a8ef019b936c79fb7f2c57",
    "g": "d04bf6d41f6a289632a2e929fa29bebd51092512a7829fdde7d314b62f05a73f",
    "ya": "21b4f4bd9e64ed355c3eb676a28ebedaf6d8f17bdc365995b3190971" "53044080",
    "ADa": b"ADa",
    "Ya": "1d13c89278cdadd826f6d8d7f887701430f8380ddc17611cdd6dc989" "ce0c9f32",
    "yb": "848b0779ff415f0af4ea14df9dd1d3c29ac41d836c7808896c4eba19" "c51ac40a",
    "ADb": b"ADb",
    "Yb": "248cccf6d5cdc3646f0ad593f9e6cef4e69d4945f8372e623512ecea" "32185623",
    "K": "5b067effbdc0b2a0e1d907b21ebb25cfedb96a852179a847c37e43ee" "71322c6b",
    "ISK": "6e19b875f7a561d6b3ca3dbb9ef42ac55de3e717881018204b8922b4"
           "d5e53bb2aa82c300bea7b65d2b671da71922ddf6472301b79bc270ad"
           "fa8bf413285f2263",
}
CPACE_LOW_S = "af46e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449aff"
CPACE_LOW = [   # (u, q = scalar_mult_vfy(s, u)), all zero means abort
    ("0000000000000000000000000000000000000000000000000000000000000000", "00" * 32),
    ("0100000000000000000000000000000000000000000000000000000000000000", "00" * 32),
    ("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", "00" * 32),
    ("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800", "00" * 32),
    ("5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157", "00" * 32),
    ("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", "00" * 32),
    ("daffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
     "d8e2c776bbacd510d09fd9278b7edcd25fc5ae9adfba3b6e040e8d3b71b21806"),
    ("eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", "00" * 32),
    ("dbffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
     "c85c655ebe8be44ba9c0ffde69f2fe10194458d137f09bbff725ce58803cdb38"),
    ("d9ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
     "db64dafa9b8fdd136914e61461935fe92aa372cb056314e1231bc4ec12417456"),
    ("cdeb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b880",
     "e062dcd5376d58297be2618c7498f55baa07d7e03184e8aada20bca28888bf7a"),
    ("4c9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f11d7",
     "993c6ad11c4c29da9a56f7691fd0ff8d732e49de6250b6c2e80003ff4629a175"),
]


def x25519_vfy(priv, pub):
    """scalar_mult_vfy: X25519, with all zero for the abort case."""
    try:
        return ref.x25519(priv, pub)
    except ValueError:
        return bytes(32)


def official_checks():
    """The reference must agree with the official data before it is used."""
    for u, x in RFC9380_ELL2:
        assert ref.ell2_u(int(u, 16)) == int(x, 16).to_bytes(32, "little"), u
    c = {k: (bytes.fromhex(v) if isinstance(v, str) else v) for k, v in CPACE.items()}
    g = ref.cpace_generator(c["PRS"], c["CI"], c["sid"])
    assert g == c["g"]
    assert ref.x25519(c["ya"], g) == c["Ya"] and ref.x25519(c["yb"], g) == c["Yb"]
    assert ref.x25519(c["ya"], c["Yb"]) == c["K"] == ref.x25519(c["yb"], c["Ya"])
    assert ref.cpace_isk(c["sid"], c["K"], c["Ya"], c["ADa"], c["Yb"], c["ADb"]) == c["ISK"]
    for u, q in CPACE_LOW:
        assert x25519_vfy(bytes.fromhex(CPACE_LOW_S), bytes.fromhex(u)) == bytes.fromhex(q), u
    assert ref.prepend_len(bytes(range(128)))[:2] == b"\x80\x01"
    assert ref.lv_cat(b"1234", b"5", b"", b"678").hex() == "043132333401350003363738"
    return c


def ell2_inputs():
    """Inputs of the map for the C core: edge values, then pseudo-random ones,
    so that both branches (g(x1) square or not) are taken many times."""
    p = ref.P25519
    edge = [0, 1, 2, p - 1, p, p + 1, 2 ** 255 - 1, (p - 1) // 2, 486662, p - 486662]
    out = [v.to_bytes(32, "little") for v in edge]
    hi = bytearray(ref.fill(32, 0x40))
    hi[31] |= 0x80                       # bit 255 set: ignored, as in RFC 7748
    out.append(bytes(hi))
    for i in range(53):
        out.append(hashlib.sha256(b"WFAS3 test ell2 " + bytes([i])).digest())
    return out


def main():
    out = ["/* Generated by tests/gen_vectors.py from tests/ref.py. Do not edit. */",
           "#ifndef WFAS3_VECTORS_H", "#define WFAS3_VECTORS_H", "#include <stdint.h>"]
    msg = ref.fill(100, 0x30)
    key = ref.fill(32, 0x80)
    out.append(arr("v_msg", msg))
    out.append(arr("v_key", key))
    out.append(arr("v_sha256", ref.sha256(msg)))
    out.append(arr("v_sha512", hashlib.sha512(msg).digest()))
    out.append(arr("v_hmac", ref.hmac256(key, msg)))
    prk = ref.hkdf_extract(key, msg)
    out.append(arr("v_hkdf_prk", prk))
    out.append(arr("v_hkdf_okm", ref.hkdf_expand(prk, msg[:20], 80)))
    out.append(arr("v_kdf", ref.kdf(key, b"transport i2r")))
    out.append(arr("v_kdf_ctx", ref.kdf(key, b"cookie", ref.fill(24, 0x05))))
    out.append(arr("v_mac16", ref.hmac256(key, msg)[:16]))
    a_priv, b_priv = ref.fill(32, 0x01), ref.fill(32, 0x91)
    out.append(arr("v_x_a_priv", a_priv))
    out.append(arr("v_x_b_priv", b_priv))
    out.append(arr("v_x_a_pub", ref.x25519_public(a_priv)))
    out.append(arr("v_x_b_pub", ref.x25519_public(b_priv)))
    out.append(arr("v_x_shared", ref.x25519(a_priv, ref.x25519_public(b_priv))))
    ad = ref.fill(16, 0xA3)
    out.append(arr("v_ad", ad))
    out.append(arr("v_sealed", ref.seal(key, 0x0102030405060708, ad, msg)))
    out.append(arr("v_sealed_empty", ref.seal(key, 0, ad, b"")))
    rnd = ref.fill(64, 0x11)
    pk, sk = ref.ML_KEM_768._keygen_internal(rnd[:32], rnd[32:])
    ss, ct = ref.ML_KEM_768._encaps_internal(pk, ref.fill(32, 0x77))
    out.append(arr("v_kem_rnd", rnd))
    out.append(arr("v_kem_m", ref.fill(32, 0x77)))
    out.append(arr("v_kem_pk_hash", ref.sha256(pk)))
    out.append(arr("v_kem_sk_hash", ref.sha256(sk)))
    out.append(arr("v_kem_ct_hash", ref.sha256(ct)))
    out.append(arr("v_kem_ss", ss))
    bad = bytearray(ct)
    bad[0] ^= 1
    out.append(arr("v_kem_ss_reject", ref.ML_KEM_768.decaps(sk, bytes(bad))))
    seed = ref.fill(48, 0x21)
    d = ref.HmacDrbg(seed + b"WFAS3")
    out.append(arr("v_drbg_seed", seed))
    out.append(arr("v_drbg_1", d.generate(64)))
    d.reseed(ref.fill(32, 0x99))
    out.append(arr("v_drbg_2", d.generate(48)))
    s = ref.Sym(b"WFAS3_KKpsk0hfs_25519+MLKEM768_ChaChaPoly_SHA256")
    s.mix_key_and_hash(key)
    s.mix_hash(msg)
    s.mix_key(msg[:32])
    c = s.encrypt_and_hash(msg[:48])
    c2 = s.encrypt_and_hash(b"")
    out.append(arr("v_sym_ct", c))
    out.append(arr("v_sym_ct2", c2))
    out.append(arr("v_sym_h", s.h))
    out.append(arr("v_sym_ck", s.ck))
    out.append(arr("v_selftest", ref.selftest_answer()))
    c = official_checks()
    ins = ell2_inputs()
    mapped = []
    branch = set()
    for u in ins:
        v = bytearray(u)
        v[31] &= 0x7F
        x = ref.ell2_u(int.from_bytes(bytes(v), "little"))
        mapped.append(x)
        r = int.from_bytes(bytes(v), "little") % ref.P25519
        branch.add(x == ((-486662 * pow(1 + 2 * r * r, ref.P25519 - 2, ref.P25519)) % ref.P25519).to_bytes(32, "little"))
    assert branch == {True, False}
    out.append("#define V_ELL2_COUNT %dU" % len(ins))
    out.append(arr("v_ell2_in", b"".join(ins)))
    out.append(arr("v_ell2_out", b"".join(mapped)))
    out.append("#define V_ELL2_RFC_COUNT %dU" % len(RFC9380_ELL2))
    out.append(arr("v_ell2_rfc_in", b"".join(int(u, 16).to_bytes(32, "little") for u, _ in RFC9380_ELL2)))
    out.append(arr("v_ell2_rfc_out", b"".join(int(x, 16).to_bytes(32, "little") for _, x in RFC9380_ELL2)))
    for k in ("PRS", "CI", "sid", "g", "ya", "ADa", "Ya", "yb", "ADb", "Yb", "K", "ISK"):
        out.append(arr("v_cpace_" + k, c[k]))
    out.append("#define V_CPACE_LOW_COUNT %dU" % len(CPACE_LOW))
    out.append(arr("v_cpace_low_s", bytes.fromhex(CPACE_LOW_S)))
    out.append(arr("v_cpace_low_u", b"".join(bytes.fromhex(u) for u, _ in CPACE_LOW)))
    out.append(arr("v_cpace_low_q", b"".join(bytes.fromhex(q) for _, q in CPACE_LOW)))
    prs, ci, sid = ref.fill(16, 0xC0), b"WFAS3-PAIR\x01", ref.fill(24, 0x10)
    out.append(arr("v_pair_g16", ref.cpace_generator(prs, ci, sid)))
    out.append(arr("v_pair_g8", ref.cpace_generator(b"12345678", b"WFAS3-PAIR\x02", sid)))
    out.append("#endif")
    print("\n".join(out))


if __name__ == "__main__":
    main()
