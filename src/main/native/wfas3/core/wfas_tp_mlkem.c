/* -----------------------------------------------------------------------------
 * WFAS v3 - glue to ML-KEM-768 from libcrux (see wfas_tp.h).
 *
 * The libcrux file is the single header that OpenSSH generates with
 * mlkem768.sh; it is used unmodified and pinned in third_party/MANIFEST.sha256.
 * This file adds no cryptography: it copies fixed-size buffers in and out.
 *
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Marco Morosi
 * ------------------------------------------------------------------------- */
#include "wfas_tp.h"

#include <stdbool.h>
#include <string.h>

/* The libcrux header expects the little-endian conversion helpers of
 * <endian.h>. They are defined here from the compiler's byte order, so that
 * the file builds the same way on Linux, ESP-IDF and other platforms. */
#if !defined(__BYTE_ORDER__)
#error "compiler does not report the byte order"
#elif __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#ifndef htole64
#define htole64(x) (x)
#define le64toh(x) (x)
#define le32toh(x) (x)
#endif
#elif __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
#ifndef htole64
#define htole64(x) __builtin_bswap64(x)
#define le64toh(x) __builtin_bswap64(x)
#define le32toh(x) __builtin_bswap32(x)
#endif
#else
#error "unsupported byte order"
#endif

/* The libcrux header reports an impossible internal state through fatal_f().
 * Fail closed: stop the program instead of continuing with bad key material. */
#define fatal_f(msg) __builtin_trap()
#include "libcrux_mlkem768_sha3.h"

/* Volatile writes, so that the compiler cannot remove the erasure. */
static void tp_wipe(void *p, size_t len)
{
    volatile uint8_t *v = (volatile uint8_t *)p;
    size_t i;

    for (i = 0U; i < len; i++) {
        v[i] = 0U;
    }
}

void wfas_tp_mlkem768_keygen(uint8_t pk[WFAS_TP_MLKEM_PK], uint8_t sk[WFAS_TP_MLKEM_SK],
                             const uint8_t rnd[64])
{
    uint8_t r[64];
    libcrux_ml_kem_mlkem768_MlKem768KeyPair kp;

    memcpy(r, rnd, sizeof r);
    kp = libcrux_ml_kem_mlkem768_portable_generate_key_pair(r);
    memcpy(pk, kp.pk.value, WFAS_TP_MLKEM_PK);
    memcpy(sk, kp.sk.value, WFAS_TP_MLKEM_SK);
    tp_wipe(&kp, sizeof kp);
    tp_wipe(r, sizeof r);
}

uint32_t wfas_tp_mlkem768_check_pk(const uint8_t pk[WFAS_TP_MLKEM_PK])
{
    libcrux_ml_kem_types_MlKemPublicKey_15 k;
    bool ok;

    memcpy(k.value, pk, WFAS_TP_MLKEM_PK);
    ok = libcrux_ml_kem_mlkem768_portable_validate_public_key(&k);
    return ok ? 1U : 0U;
}

void wfas_tp_mlkem768_encaps(uint8_t ct[WFAS_TP_MLKEM_CT], uint8_t ss[32],
                             const uint8_t pk[WFAS_TP_MLKEM_PK], const uint8_t rnd[32])
{
    libcrux_ml_kem_types_MlKemPublicKey_15 k;
    uint8_t r[32];
    tuple_3c res;

    memcpy(k.value, pk, WFAS_TP_MLKEM_PK);
    memcpy(r, rnd, sizeof r);
    res = libcrux_ml_kem_mlkem768_portable_encapsulate(&k, r);
    memcpy(ct, res.fst.value, WFAS_TP_MLKEM_CT);
    memcpy(ss, res.snd, 32U);
    tp_wipe(&res, sizeof res);
    tp_wipe(r, sizeof r);
}

void wfas_tp_mlkem768_decaps(uint8_t ss[32], const uint8_t ct[WFAS_TP_MLKEM_CT],
                             const uint8_t sk[WFAS_TP_MLKEM_SK])
{
    libcrux_ml_kem_types_MlKemPrivateKey_55 k;
    libcrux_ml_kem_mlkem768_MlKem768Ciphertext c;

    memcpy(k.value, sk, WFAS_TP_MLKEM_SK);
    memcpy(c.value, ct, WFAS_TP_MLKEM_CT);
    libcrux_ml_kem_mlkem768_portable_decapsulate(&k, &c, ss);
    tp_wipe(&k, sizeof k);
}
