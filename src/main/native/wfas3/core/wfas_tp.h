/* -----------------------------------------------------------------------------
 * WFAS v3 - boundary between the core (wfas.c) and the third-party primitives.
 *
 * wfas_tp.c is the only file that includes third-party headers. The core calls
 * the functions below and nothing else, always with fixed or checked lengths.
 * No function here allocates memory.
 *
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Marco Morosi
 * ------------------------------------------------------------------------- */
#ifndef WFAS_TP_H
#define WFAS_TP_H

#include <stdint.h>
#include <stddef.h>

#define WFAS_TP_MLKEM_PK   1184U
#define WFAS_TP_MLKEM_SK   2400U
#define WFAS_TP_MLKEM_CT   1088U
#define WFAS_TP_DRBG_SEED  48U

/* HMAC-DRBG (SP 800-90A, SHA-256) state. Owned by the core, opaque to it. */
typedef struct wfas_tp_drbg {
    uint8_t  k[32];
    uint8_t  v[32];
    uint32_t reseed_counter;
} wfas_tp_drbg;

void wfas_tp_sha256(uint8_t out[32], const uint8_t *in, uint32_t len);
void wfas_tp_sha512(uint8_t out[64], const uint8_t *in, uint32_t len);
void wfas_tp_hmac_sha256(uint8_t out[32], const uint8_t *key, uint32_t key_len,
                         const uint8_t *msg, uint32_t msg_len);
void wfas_tp_hkdf_extract(uint8_t prk[32], const uint8_t *salt, uint32_t salt_len,
                          const uint8_t *ikm, uint32_t ikm_len);
void wfas_tp_hkdf_expand(uint8_t *okm, uint32_t okm_len, const uint8_t prk[32],
                         const uint8_t *info, uint32_t info_len);

/* X25519. wfas_tp_x25519 returns 1 on success, 0 if the output is all zero
 * (the output buffer is then zero and must not be used). */
void     wfas_tp_x25519_public(uint8_t pub[32], const uint8_t priv[32]);
uint32_t wfas_tp_x25519(uint8_t out[32], const uint8_t priv[32], const uint8_t pub[32]);

/* ChaCha20-Poly1305 (RFC 8439). open returns 1 if the tag is valid, else 0. */
void     wfas_tp_aead_seal(uint8_t *ct, uint8_t tag[16], const uint8_t *pt, uint32_t len,
                           const uint8_t *ad, uint32_t ad_len,
                           const uint8_t key[32], const uint8_t nonce[12]);
uint32_t wfas_tp_aead_open(uint8_t *pt, const uint8_t *ct, uint32_t len,
                           const uint8_t *ad, uint32_t ad_len,
                           const uint8_t key[32], const uint8_t nonce[12],
                           const uint8_t tag[16]);

/* ML-KEM-768 (FIPS 203). Randomness is supplied by the caller: 64 bytes (d||z)
 * for key generation, 32 bytes (m) for encapsulation. check_pk returns 1 if
 * the encapsulation key passes the FIPS 203 input check, else 0. */
void     wfas_tp_mlkem768_keygen(uint8_t pk[WFAS_TP_MLKEM_PK], uint8_t sk[WFAS_TP_MLKEM_SK],
                                 const uint8_t rnd[64]);
uint32_t wfas_tp_mlkem768_check_pk(const uint8_t pk[WFAS_TP_MLKEM_PK]);
void     wfas_tp_mlkem768_encaps(uint8_t ct[WFAS_TP_MLKEM_CT], uint8_t ss[32],
                                 const uint8_t pk[WFAS_TP_MLKEM_PK], const uint8_t rnd[32]);
void     wfas_tp_mlkem768_decaps(uint8_t ss[32], const uint8_t ct[WFAS_TP_MLKEM_CT],
                                 const uint8_t sk[WFAS_TP_MLKEM_SK]);

/* HMAC-DRBG. generate returns 1 on success, 0 if a reseed is required or the
 * request is too large (nothing usable is written). */
void     wfas_tp_drbg_instantiate(wfas_tp_drbg *d, const uint8_t seed[WFAS_TP_DRBG_SEED]);
void     wfas_tp_drbg_reseed(wfas_tp_drbg *d, const uint8_t entropy[32]);
uint32_t wfas_tp_drbg_generate(wfas_tp_drbg *d, uint8_t *out, uint32_t len);
/* Number of generate calls still allowed before a reseed is required. */
uint32_t wfas_tp_drbg_left(const wfas_tp_drbg *d);

/* Arithmetic in GF(2^255 - 19) from fiat-crypto (32-bit limbs), used only by
 * the Elligator 2 map of CPace. Every function takes and returns elements in
 * the tight bounds of fiat-crypto, so any output may be used as any input;
 * outputs may alias inputs. Bit 255 of the input of frombytes is ignored
 * (RFC 7748); tobytes writes the canonical encoding. cmov sets out to b when
 * pick_b is 1 and to a when it is 0, without a branch. */
typedef struct wfas_tp_fe {
    uint32_t v[10];
} wfas_tp_fe;

void wfas_tp_fe_frombytes(wfas_tp_fe *out, const uint8_t in[32]);
void wfas_tp_fe_tobytes(uint8_t out[32], const wfas_tp_fe *a);
void wfas_tp_fe_add(wfas_tp_fe *out, const wfas_tp_fe *a, const wfas_tp_fe *b);
void wfas_tp_fe_sub(wfas_tp_fe *out, const wfas_tp_fe *a, const wfas_tp_fe *b);
void wfas_tp_fe_mul(wfas_tp_fe *out, const wfas_tp_fe *a, const wfas_tp_fe *b);
void wfas_tp_fe_sq(wfas_tp_fe *out, const wfas_tp_fe *a);
void wfas_tp_fe_cmov(wfas_tp_fe *out, const wfas_tp_fe *a, const wfas_tp_fe *b, uint32_t pick_b);

#endif
