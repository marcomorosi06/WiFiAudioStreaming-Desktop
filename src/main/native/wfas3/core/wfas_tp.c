/* -----------------------------------------------------------------------------
 * WFAS v3 - glue to the HACL* primitives and to the fiat-crypto field
 * arithmetic (see wfas_tp.h).
 * ML-KEM-768 is in wfas_tp_mlkem.c: the libcrux header and the HACL* headers
 * define the same macros and cannot share a translation unit. The fiat-crypto
 * file defines only fiat_25519_* names and shares this one.
 *
 * This file adds no cryptography. It adapts argument order and types, and
 * turns results into 1/0. The third-party files are used unmodified; their
 * versions and SHA-256 are pinned in third_party/MANIFEST.sha256.
 *
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Marco Morosi
 * ------------------------------------------------------------------------- */
#include "wfas_tp.h"

#include <stdbool.h>
#include <string.h>

#include "Hacl_Hash_SHA2.h"
#include "Hacl_HMAC.h"
#include "Hacl_HKDF.h"
#include "Hacl_Curve25519_51.h"
#include "Hacl_AEAD_Chacha20Poly1305.h"
#include "Hacl_HMAC_DRBG.h"

/* fiat-crypto ships its field arithmetic as a C file of static functions,
 * meant to be included. */
#include "curve25519_32.c"

/* The primitives take non-const pointers but do not write through their
 * inputs. This is the single place where the qualifier is dropped. */
static uint8_t *in_ptr(const uint8_t *p)
{
    return (uint8_t *)(uintptr_t)p;
}

void wfas_tp_sha256(uint8_t out[32], const uint8_t *in, uint32_t len)
{
    Hacl_Hash_SHA2_hash_256(out, in_ptr(in), len);
}

void wfas_tp_sha512(uint8_t out[64], const uint8_t *in, uint32_t len)
{
    Hacl_Hash_SHA2_hash_512(out, in_ptr(in), len);
}

void wfas_tp_hmac_sha256(uint8_t out[32], const uint8_t *key, uint32_t key_len,
                         const uint8_t *msg, uint32_t msg_len)
{
    Hacl_HMAC_compute_sha2_256(out, in_ptr(key), key_len, in_ptr(msg), msg_len);
}

void wfas_tp_hkdf_extract(uint8_t prk[32], const uint8_t *salt, uint32_t salt_len,
                          const uint8_t *ikm, uint32_t ikm_len)
{
    Hacl_HKDF_extract_sha2_256(prk, in_ptr(salt), salt_len, in_ptr(ikm), ikm_len);
}

void wfas_tp_hkdf_expand(uint8_t *okm, uint32_t okm_len, const uint8_t prk[32],
                         const uint8_t *info, uint32_t info_len)
{
    Hacl_HKDF_expand_sha2_256(okm, in_ptr(prk), 32U, in_ptr(info), info_len, okm_len);
}

void wfas_tp_x25519_public(uint8_t pub[32], const uint8_t priv[32])
{
    Hacl_Curve25519_51_secret_to_public(pub, in_ptr(priv));
}

uint32_t wfas_tp_x25519(uint8_t out[32], const uint8_t priv[32], const uint8_t pub[32])
{
    /* ecdh() compares the result with zero in constant time. */
    bool ok = Hacl_Curve25519_51_ecdh(out, in_ptr(priv), in_ptr(pub));
    return ok ? 1U : 0U;
}

void wfas_tp_aead_seal(uint8_t *ct, uint8_t tag[16], const uint8_t *pt, uint32_t len,
                       const uint8_t *ad, uint32_t ad_len,
                       const uint8_t key[32], const uint8_t nonce[12])
{
    Hacl_AEAD_Chacha20Poly1305_encrypt(ct, tag, in_ptr(pt), len, in_ptr(ad), ad_len,
                                       in_ptr(key), in_ptr(nonce));
}

uint32_t wfas_tp_aead_open(uint8_t *pt, const uint8_t *ct, uint32_t len,
                           const uint8_t *ad, uint32_t ad_len,
                           const uint8_t key[32], const uint8_t nonce[12],
                           const uint8_t tag[16])
{
    uint32_t r = Hacl_AEAD_Chacha20Poly1305_decrypt(pt, in_ptr(ct), len, in_ptr(ad), ad_len,
                                                    in_ptr(key), in_ptr(nonce), in_ptr(tag));
    return (r == 0U) ? 1U : 0U;
}

static Hacl_HMAC_DRBG_state drbg_view(wfas_tp_drbg *d)
{
    Hacl_HMAC_DRBG_state st;

    st.k = d->k;
    st.v = d->v;
    st.reseed_counter = &d->reseed_counter;
    return st;
}

void wfas_tp_drbg_instantiate(wfas_tp_drbg *d, const uint8_t seed[WFAS_TP_DRBG_SEED])
{
    static const uint8_t pers[5] = { 0x57U, 0x46U, 0x41U, 0x53U, 0x33U }; /* "WFAS3" */

    Hacl_HMAC_DRBG_instantiate(Spec_Hash_Definitions_SHA2_256, drbg_view(d),
                               32U, in_ptr(seed), 16U, in_ptr(&seed[32]),
                               (uint32_t)sizeof pers, in_ptr(pers));
}

void wfas_tp_drbg_reseed(wfas_tp_drbg *d, const uint8_t entropy[32])
{
    Hacl_HMAC_DRBG_reseed(Spec_Hash_Definitions_SHA2_256, drbg_view(d),
                          32U, in_ptr(entropy), 0U, in_ptr(entropy));
}

uint32_t wfas_tp_drbg_generate(wfas_tp_drbg *d, uint8_t *out, uint32_t len)
{
    bool ok = Hacl_HMAC_DRBG_generate(Spec_Hash_Definitions_SHA2_256, out, drbg_view(d),
                                      len, 0U, out);
    return ok ? 1U : 0U;
}

uint32_t wfas_tp_drbg_left(const wfas_tp_drbg *d)
{
    uint32_t limit = Hacl_HMAC_DRBG_reseed_interval;

    return (d->reseed_counter <= limit) ? ((limit - d->reseed_counter) + 1U) : 0U;
}

/* -- GF(2^255 - 19) ------------------------------------------------------------
 * fiat-crypto distinguishes tight and loose bounds. Tight values are within
 * the loose bounds, so every wrapper takes tight inputs and returns a tight
 * output: add, sub and neg are followed by a carry. Results go through a
 * local buffer, so that out may alias an input. */

static void fe_wipe(uint32_t *p, size_t n)
{
    volatile uint32_t *v = p;
    size_t i;

    for (i = 0U; i < n; i++) {
        v[i] = 0U;
    }
}

void wfas_tp_fe_frombytes(wfas_tp_fe *out, const uint8_t in[32])
{
    uint8_t b[32];

    memcpy(b, in, sizeof b);
    b[31] = (uint8_t)(b[31] & 0x7FU);          /* input bound of from_bytes */
    fiat_25519_from_bytes(out->v, b);
    memset(b, 0, sizeof b);
}

void wfas_tp_fe_tobytes(uint8_t out[32], const wfas_tp_fe *a)
{
    fiat_25519_to_bytes(out, a->v);
}

void wfas_tp_fe_add(wfas_tp_fe *out, const wfas_tp_fe *a, const wfas_tp_fe *b)
{
    fiat_25519_loose_field_element l;

    fiat_25519_add(l, a->v, b->v);
    fiat_25519_carry(out->v, l);
    fe_wipe(l, 10U);
}

void wfas_tp_fe_sub(wfas_tp_fe *out, const wfas_tp_fe *a, const wfas_tp_fe *b)
{
    fiat_25519_loose_field_element l;

    fiat_25519_sub(l, a->v, b->v);
    fiat_25519_carry(out->v, l);
    fe_wipe(l, 10U);
}

void wfas_tp_fe_mul(wfas_tp_fe *out, const wfas_tp_fe *a, const wfas_tp_fe *b)
{
    fiat_25519_tight_field_element t;

    fiat_25519_carry_mul(t, a->v, b->v);
    memcpy(out->v, t, sizeof t);
    fe_wipe(t, 10U);
}

void wfas_tp_fe_sq(wfas_tp_fe *out, const wfas_tp_fe *a)
{
    fiat_25519_tight_field_element t;

    fiat_25519_carry_square(t, a->v);
    memcpy(out->v, t, sizeof t);
    fe_wipe(t, 10U);
}

void wfas_tp_fe_cmov(wfas_tp_fe *out, const wfas_tp_fe *a, const wfas_tp_fe *b, uint32_t pick_b)
{
    uint32_t t[10];

    fiat_25519_selectznz(t, (fiat_25519_uint1)(pick_b & 1U), a->v, b->v);
    memcpy(out->v, t, sizeof t);
    fe_wipe(t, 10U);
}
