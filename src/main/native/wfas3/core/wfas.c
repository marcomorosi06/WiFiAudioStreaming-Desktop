/* -----------------------------------------------------------------------------
 * WFAS v3 - core library (see wfas.h and WFAS_V3_PROTOCOL.md).
 *
 * Build stage: pairing and multicast. Contents:
 *   - byte order, constant-time compare, secret erasure
 *   - KDF, MAC16/MAC8, Seal/Open            (specification section 2)
 *   - handshake symmetric state             (specification section 2.1)
 *   - random generator wrapper
 *   - datagram classifier                   (specification section 3)
 *   - pair records                          (specification section 5)
 *   - entry: mac1, pair tag, counter, cookie (specification section 7)
 *   - handshake: Init, Resp, Reject         (specification section 8)
 *   - attempts, pair psk, session reports   (specification 9.1 to 9.3)
 *   - entry key rotation, pair removal      (specification 9.4)
 *   - transport: frames, audio, consent, presence, closing, path change,
 *     synchronised playback                 (specification 10)
 *   - rekey                                 (specification 11)
 *   - discovery: Probe, ProbeReply          (specification 12)
 *   - Elligator 2 (RFC 9380 G.2.1) on fiat-crypto field arithmetic, CPace
 *   - pairing: window, beacon, nine steps, codes, labels, PIN, guests,
 *     replacement, admission                (specification 13)
 *   - multicast: groups, members, key epochs, NACK and resends (14)
 *   - power-on self-test
 *
 * Rules followed by this file: C99 without extensions; no allocation, no
 * recursion, no goto, no function pointers, no unions, no globals that can be
 * written; fixed-width types; every loop bounded by a constant; no branch or
 * index that depends on a secret; of <string.h> only memcpy and memset.
 * All cryptography is in the third-party files reached through wfas_tp.h,
 * except the Elligator 2 map, written here on the field arithmetic of
 * fiat-crypto and checked against RFC 9380 and draft-irtf-cfrg-cpace-21.
 *
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Marco Morosi
 * ------------------------------------------------------------------------- */
#include "wfas.h"
#include "wfas_tp.h"

#include <string.h>

/* The code reads and writes the wire one octet at a time. */
typedef char wfas3_assert_octet[(sizeof(uint8_t) == 1U) ? 1 : -1];
typedef char wfas3_assert_dgram[(WFAS3_LEN_INIT <= WFAS3_MAX_DATAGRAM) ? 1 : -1];

#define WFAS3_KEY_LEN      32U
#define WFAS3_HASH_LEN     32U
#define WFAS3_TAG_LEN      16U
#define WFAS3_NONCE_LEN    12U
#define WFAS3_INFO_MAX     96U     /* "WFAS3 " + label + context              */
#define WFAS3_KDF_OUT_MAX  96U
#define WFAS3_MIX_MAX      1280U   /* largest input of MixHash                */
#define WFAS3_SEAL_MAX     1384U   /* largest plaintext of one AEAD call      */
#define WFAS3_CMP_MAX      2400U   /* largest constant-time comparison        */
#define WFAS3_WIPE_MAX     65536U  /* largest erasure (a whole session)       */

/* Label literal -> (pointer, length without the terminator). */
#define WFAS3_LBL(s) (s), (sizeof(s) - 1U)

/* -- Byte order -------------------------------------------------------------- */

static void wfas3_wr_be64(uint8_t out[8], uint64_t v)
{
    uint32_t i;

    for (i = 0U; i < 8U; i++) {
        out[i] = (uint8_t)(v >> (56U - (8U * i)));
    }
}

/* -- Secrets ----------------------------------------------------------------- */

/* Erase with volatile writes, so that the compiler cannot drop the erasure. */
static void wfas3_wipe(uint8_t *p, size_t len)
{
    volatile uint8_t *v = p;
    size_t i;

    for (i = 0U; (i < len) && (i < (size_t)WFAS3_WIPE_MAX); i++) {
        v[i] = 0U;
    }
}

/* Constant-time equality. Returns 1 if equal, 0 otherwise. The only function
 * used to compare MACs, tags and keys. */
static uint32_t wfas3_ct_eq(const uint8_t *a, const uint8_t *b, size_t len)
{
    uint32_t diff = 0U;
    uint32_t bad_len = (len > (size_t)WFAS3_CMP_MAX) ? 1U : 0U;
    size_t i;

    for (i = 0U; (i < len) && (i < (size_t)WFAS3_CMP_MAX); i++) {
        diff |= (uint32_t)a[i] ^ (uint32_t)b[i];
    }
    diff |= bad_len;
    return 1U & ((diff - 1U) >> 8U);
}

/* -- KDF and MAC ------------------------------------------------------------- */

/* out = HKDF-Expand(key, "WFAS3 " || label || ctx, out_len). */
static wfas3_status wfas3_kdf(uint8_t *out, size_t out_len,
                              const uint8_t key[WFAS3_KEY_LEN],
                              const char *label, size_t label_len,
                              const uint8_t *ctx, size_t ctx_len)
{
    static const uint8_t prefix[6] = { 0x57U, 0x46U, 0x41U, 0x53U, 0x33U, 0x20U };
    uint8_t info[WFAS3_INFO_MAX];
    wfas3_status st = WFAS3_E_ARG;
    size_t i;

    if ((out_len > 0U) && (out_len <= (size_t)WFAS3_KDF_OUT_MAX) &&
        (label_len <= 40U) && (ctx_len <= 48U)) {
        memcpy(info, prefix, sizeof prefix);
        for (i = 0U; i < label_len; i++) {
            info[sizeof prefix + i] = (uint8_t)label[i];
        }
        if (ctx_len > 0U) {
            memcpy(&info[sizeof prefix + label_len], ctx, ctx_len);
        }
        wfas_tp_hkdf_expand(out, (uint32_t)out_len, key, info,
                            (uint32_t)(sizeof prefix + label_len + ctx_len));
        st = WFAS3_OK;
    }
    wfas3_wipe(info, sizeof info);
    return st;
}

/* out = first out_len bytes (8 or 16) of HMAC-SHA256(key, msg). */
static wfas3_status wfas3_mac_k(uint8_t *out, size_t out_len,
                                const uint8_t *key, size_t key_len,
                                const uint8_t *msg, size_t msg_len)
{
    uint8_t full[WFAS3_HASH_LEN];
    wfas3_status st = WFAS3_E_ARG;

    if (((out_len == 8U) || (out_len == 16U)) && (key_len >= 16U) &&
        (key_len <= (size_t)WFAS3_KEY_LEN) && (msg_len <= (size_t)WFAS3_MAX_DATAGRAM)) {
        wfas_tp_hmac_sha256(full, key, (uint32_t)key_len, msg, (uint32_t)msg_len);
        memcpy(out, full, out_len);
        st = WFAS3_OK;
    }
    wfas3_wipe(full, sizeof full);
    return st;
}

static wfas3_status wfas3_mac(uint8_t *out, size_t out_len,
                              const uint8_t key[WFAS3_KEY_LEN],
                              const uint8_t *msg, size_t msg_len)
{
    return wfas3_mac_k(out, out_len, key, WFAS3_KEY_LEN, msg, msg_len);
}

/* -- AEAD -------------------------------------------------------------------- */

/* 4 bytes hi, then the u64 n: hi is 0 except in a multicast packet, where it
 * is the key epoch (14.1). */
static void wfas3_nonce(uint8_t nonce[WFAS3_NONCE_LEN], uint32_t hi, uint64_t n)
{
    nonce[0] = (uint8_t)(hi >> 24U);
    nonce[1] = (uint8_t)(hi >> 16U);
    nonce[2] = (uint8_t)(hi >> 8U);
    nonce[3] = (uint8_t)hi;
    wfas3_wr_be64(&nonce[4], n);
}

/* out = ciphertext || tag; writes pt_len + 16 bytes. */
static wfas3_status wfas3_seal_e(uint8_t *out, size_t out_cap,
                                 const uint8_t key[WFAS3_KEY_LEN], uint32_t hi, uint64_t n,
                                 const uint8_t *ad, size_t ad_len,
                                 const uint8_t *pt, size_t pt_len)
{
    uint8_t nonce[WFAS3_NONCE_LEN];
    wfas3_status st = WFAS3_E_ARG;

    if ((pt_len <= (size_t)WFAS3_SEAL_MAX) && (ad_len <= 64U) &&
        (out_cap >= (pt_len + (size_t)WFAS3_TAG_LEN))) {
        wfas3_nonce(nonce, hi, n);
        wfas_tp_aead_seal(out, &out[pt_len], pt, (uint32_t)pt_len,
                          ad, (uint32_t)ad_len, key, nonce);
        st = WFAS3_OK;
    }
    return st;
}

static wfas3_status wfas3_seal(uint8_t *out, size_t out_cap,
                               const uint8_t key[WFAS3_KEY_LEN], uint64_t n,
                               const uint8_t *ad, size_t ad_len,
                               const uint8_t *pt, size_t pt_len)
{
    return wfas3_seal_e(out, out_cap, key, 0U, n, ad, ad_len, pt, pt_len);
}

/* in = ciphertext || tag of in_len bytes; writes in_len - 16 bytes. On any
 * failure the output is erased and WFAS3_E_AUTH or WFAS3_E_ARG is returned. */
static wfas3_status wfas3_open_e(uint8_t *out, size_t out_cap,
                                 const uint8_t key[WFAS3_KEY_LEN], uint32_t hi, uint64_t n,
                                 const uint8_t *ad, size_t ad_len,
                                 const uint8_t *in, size_t in_len)
{
    uint8_t nonce[WFAS3_NONCE_LEN];
    wfas3_status st = WFAS3_E_ARG;
    size_t pt_len;
    uint32_t ok;

    if ((in_len >= (size_t)WFAS3_TAG_LEN) &&
        (in_len <= ((size_t)WFAS3_SEAL_MAX + (size_t)WFAS3_TAG_LEN)) && (ad_len <= 64U)) {
        pt_len = in_len - (size_t)WFAS3_TAG_LEN;
        if (out_cap >= pt_len) {
            wfas3_nonce(nonce, hi, n);
            ok = wfas_tp_aead_open(out, in, (uint32_t)pt_len, ad, (uint32_t)ad_len,
                                   key, nonce, &in[pt_len]);
            if (ok == 1U) {
                st = WFAS3_OK;
            } else {
                wfas3_wipe(out, pt_len);
                st = WFAS3_E_AUTH;
            }
        }
    }
    return st;
}

static wfas3_status wfas3_open(uint8_t *out, size_t out_cap,
                               const uint8_t key[WFAS3_KEY_LEN], uint64_t n,
                               const uint8_t *ad, size_t ad_len,
                               const uint8_t *in, size_t in_len)
{
    return wfas3_open_e(out, out_cap, key, 0U, n, ad, ad_len, in, in_len);
}

/* -- Handshake symmetric state (specification 2.1) --------------------------- */

static wfas3_status wfas3_sym_mix_hash(wfas3_sym *s, const uint8_t *d, size_t len)
{
    uint8_t buf[WFAS3_HASH_LEN + WFAS3_MIX_MAX];
    wfas3_status st = WFAS3_E_ARG;

    if (len <= (size_t)WFAS3_MIX_MAX) {
        memcpy(buf, s->h, WFAS3_HASH_LEN);
        if (len > 0U) {
            memcpy(&buf[WFAS3_HASH_LEN], d, len);
        }
        wfas_tp_sha256(s->h, buf, (uint32_t)((size_t)WFAS3_HASH_LEN + len));
        st = WFAS3_OK;
    }
    return st;
}

static wfas3_status wfas3_sym_init(wfas3_sym *s, const char *name, size_t name_len)
{
    static const uint8_t prologue[6] = { 0x57U, 0x46U, 0x41U, 0x53U, 0x33U, 0x01U };
    uint8_t buf[64];
    wfas3_status st = WFAS3_E_ARG;
    size_t i;

    if ((name_len > 0U) && (name_len <= sizeof buf)) {
        for (i = 0U; i < name_len; i++) {
            buf[i] = (uint8_t)name[i];
        }
        wfas_tp_sha256(s->h, buf, (uint32_t)name_len);
        memcpy(s->ck, s->h, WFAS3_KEY_LEN);
        memset(s->k, 0, WFAS3_KEY_LEN);
        s->n = 0U;
        s->has_key = 0U;
        st = wfas3_sym_mix_hash(s, prologue, sizeof prologue);
    }
    return st;
}

/* out = HKDF(salt = ck, ikm, info = empty, out_len). */
static wfas3_status wfas3_sym_hkdf(const wfas3_sym *s, const uint8_t *ikm, size_t ikm_len,
                                   uint8_t *out, size_t out_len)
{
    uint8_t prk[WFAS3_KEY_LEN];
    wfas3_status st = WFAS3_E_ARG;

    if ((ikm_len > 0U) && (ikm_len <= 64U) && (out_len <= (size_t)WFAS3_KDF_OUT_MAX)) {
        wfas_tp_hkdf_extract(prk, s->ck, WFAS3_KEY_LEN, ikm, (uint32_t)ikm_len);
        wfas_tp_hkdf_expand(out, (uint32_t)out_len, prk, prk, 0U);
        st = WFAS3_OK;
    }
    wfas3_wipe(prk, sizeof prk);
    return st;
}

static wfas3_status wfas3_sym_mix_key(wfas3_sym *s, const uint8_t *ikm, size_t ikm_len)
{
    uint8_t okm[64];
    wfas3_status st = wfas3_sym_hkdf(s, ikm, ikm_len, okm, sizeof okm);

    if (st == WFAS3_OK) {
        memcpy(s->ck, okm, WFAS3_KEY_LEN);
        memcpy(s->k, &okm[32], WFAS3_KEY_LEN);
        s->n = 0U;
        s->has_key = 1U;
    }
    wfas3_wipe(okm, sizeof okm);
    return st;
}

static wfas3_status wfas3_sym_mix_key_and_hash(wfas3_sym *s, const uint8_t ikm[WFAS3_KEY_LEN])
{
    uint8_t okm[96];
    wfas3_status st = wfas3_sym_hkdf(s, ikm, WFAS3_KEY_LEN, okm, sizeof okm);

    if (st == WFAS3_OK) {
        memcpy(s->ck, okm, WFAS3_KEY_LEN);
        st = wfas3_sym_mix_hash(s, &okm[32], WFAS3_HASH_LEN);
        memcpy(s->k, &okm[64], WFAS3_KEY_LEN);
        s->n = 0U;
        s->has_key = 1U;
    }
    wfas3_wipe(okm, sizeof okm);
    return st;
}

/* out = Seal(k, n, h, pt); n = n + 1; MixHash(out). Writes len + 16 bytes. */
static wfas3_status wfas3_sym_encrypt(wfas3_sym *s, uint8_t *out, size_t out_cap,
                                      const uint8_t *pt, size_t len)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((s->has_key == 1U) && (s->n < UINT64_MAX) &&
        (len <= ((size_t)WFAS3_MIX_MAX - (size_t)WFAS3_TAG_LEN))) {
        st = wfas3_seal(out, out_cap, s->k, s->n, s->h, WFAS3_HASH_LEN, pt, len);
        if (st == WFAS3_OK) {
            s->n += 1U;
            st = wfas3_sym_mix_hash(s, out, len + (size_t)WFAS3_TAG_LEN);
        }
    }
    return st;
}

/* out = Open(k, n, h, ct); n = n + 1; MixHash(ct). Writes ct_len - 16 bytes.
 * On failure the state is left as it was and the output is erased. */
static wfas3_status wfas3_sym_decrypt(wfas3_sym *s, uint8_t *out, size_t out_cap,
                                      const uint8_t *ct, size_t ct_len)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((s->has_key == 1U) && (s->n < UINT64_MAX) && (ct_len <= (size_t)WFAS3_MIX_MAX)) {
        st = wfas3_open(out, out_cap, s->k, s->n, s->h, WFAS3_HASH_LEN, ct, ct_len);
        if (st == WFAS3_OK) {
            s->n += 1U;
            st = wfas3_sym_mix_hash(s, ct, ct_len);
        }
    }
    return st;
}

static void wfas3_sym_wipe(wfas3_sym *s)
{
    wfas3_wipe(s->h, WFAS3_HASH_LEN);
    wfas3_wipe(s->ck, WFAS3_KEY_LEN);
    wfas3_wipe(s->k, WFAS3_KEY_LEN);
    s->n = 0U;
    s->has_key = 0U;
}

/* -- Random generator -------------------------------------------------------- */

/* The DRBG lives inside the core: callers supply entropy, never nonces or
 * ephemeral keys. Without entropy at start nothing can be generated. */
static void wfas3_rng_start(wfas3_rng *r, const uint8_t seed[WFAS_TP_DRBG_SEED])
{
    wfas_tp_drbg_instantiate(&r->drbg, seed);
    r->ready = 1U;
}

static wfas3_status wfas3_rng_reseed(wfas3_rng *r, const uint8_t entropy[32])
{
    wfas3_status st = WFAS3_E_ENTROPY;

    if (r->ready == 1U) {
        wfas_tp_drbg_reseed(&r->drbg, entropy);
        st = WFAS3_OK;
    }
    return st;
}

static wfas3_status wfas3_rng_bytes(wfas3_rng *r, uint8_t *out, size_t len)
{
    wfas3_status st = WFAS3_E_ENTROPY;

    if ((r->ready == 1U) && (len > 0U) && (len <= 64U)) {
        if (wfas_tp_drbg_generate(&r->drbg, out, (uint32_t)len) == 1U) {
            st = WFAS3_OK;
        } else {
            wfas3_wipe(out, len);
        }
    }
    return st;
}

/* -- Datagram classifier (specification 3) ----------------------------------- */

static uint32_t wfas3_pair_len_ok(size_t len)
{
    uint32_t ok;

    switch (len) {
    case 1368U:
    case 1296U:
    case 176U:
    case 120U:
    case 100U:
    case 80U:
    case 52U:
        ok = 1U;
        break;
    default:
        ok = 0U;
        break;
    }
    return ok;
}

static uint32_t wfas3_len_ok(uint8_t type, size_t len)
{
    uint32_t ok = 0U;

    switch (type) {
    case WFAS3_T_PROBE:
    case WFAS3_T_PROBE_REPLY:
        ok = (len == (size_t)WFAS3_LEN_PROBE) ? 1U : 0U;
        break;
    case WFAS3_T_PAIR_BEACON:
        ok = (len == (size_t)WFAS3_LEN_PAIR_BEACON) ? 1U : 0U;
        break;
    case WFAS3_T_INIT:
        ok = (len == (size_t)WFAS3_LEN_INIT) ? 1U : 0U;
        break;
    case WFAS3_T_RESP:
        ok = (len == (size_t)WFAS3_LEN_RESP) ? 1U : 0U;
        break;
    case WFAS3_T_COOKIE_REPLY:
    case WFAS3_T_COOKIE_REQUEST:
        ok = (len == (size_t)WFAS3_LEN_COOKIE) ? 1U : 0U;
        break;
    case WFAS3_T_REJECT:
        ok = (len == (size_t)WFAS3_LEN_REJECT) ? 1U : 0U;
        break;
    case WFAS3_T_PAIR:
        ok = wfas3_pair_len_ok(len);
        break;
    case WFAS3_T_TRANSPORT:
        ok = ((len == (size_t)WFAS3_LEN_CONTROL) ||
              ((len > (size_t)WFAS3_AUDIO_OVERHEAD) &&
               (len <= ((size_t)WFAS3_AUDIO_OVERHEAD + (size_t)WFAS3_MAX_AUDIO_PAYLOAD)))) ? 1U : 0U;
        break;
    case WFAS3_T_MULTICAST:
        ok = ((len > (size_t)WFAS3_MCAST_OVERHEAD) &&
              (len <= ((size_t)WFAS3_MCAST_OVERHEAD + (size_t)WFAS3_MAX_MCAST_PAYLOAD))) ? 1U : 0U;
        break;
    default:
        ok = 0U;
        break;
    }
    return ok;
}

wfas3_status wfas3_classify(const uint8_t *dgram, size_t len, uint8_t *type)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dgram != NULL) && (type != NULL)) {
        st = WFAS3_E_DROP;
        if ((len >= (size_t)WFAS3_HEADER_LEN) && (len <= (size_t)WFAS3_MAX_DATAGRAM)) {
            if ((dgram[0] == WFAS3_MARKER) && (dgram[2] == 0U) && (dgram[3] == 0U) &&
                (wfas3_len_ok(dgram[1], len) == 1U)) {
                *type = dgram[1];
                st = WFAS3_OK;
            }
        }
    }
    return st;
}

/* -- Constants of this stage ------------------------------------------------- */

typedef char wfas3_assert_pairs[(WFAS3_MAX_PAIRS <= 64U) ? 1 : -1];

#define WFAS3_ATT_FREE         0U
#define WFAS3_ATT_REQ          1U   /* CookieRequest sent, waiting CookieReply */
#define WFAS3_ATT_OUT          2U   /* Init sent, waiting Resp                 */
#define WFAS3_SES_FREE         0U
#define WFAS3_SES_UNCONFIRMED  1U   /* responder, before the first packet      */
#define WFAS3_SES_UP           2U
#define WFAS3_SES_CLOSING      3U   /* sending its last BYE packets            */

#define WFAS3_DROP_ALL         0U   /* every session of the pair               */
#define WFAS3_DROP_UNCONFIRMED 1U   /* unconfirmed sessions only               */
#define WFAS3_DROP_OPEN        2U   /* all but those already closing           */

#define WFAS3_REP_BASE         0U
#define WFAS3_REP_LAST         1U
#define WFAS3_REP_MINEC        2U
#define WFAS3_REP_RESPP        3U
#define WFAS3_CLS_OK           0U
#define WFAS3_CLS_STALE        1U
#define WFAS3_CLS_UNKNOWN      2U

#define WFAS3_COOKIE_GAP_MS    2000U    /* one fresh CookieRequest per pair    */
#define WFAS3_STALE_GAP_MS     30000U   /* one stale answer per pair           */
#define WFAS3_HS_GAP_MS        2000U    /* one handshake per pair              */
#define WFAS3_COOKIE_LIFE_MS   30000U   /* cookie secret rotation              */
#define WFAS3_COOKIE_HOLD_MS   5000U    /* rule C5                             */
#define WFAS3_RETRY_MS         5000U
#define WFAS3_GIVE_UP_MS       60000U
#define WFAS3_UNCONFIRMED_MS   15000U   /* responder waits this long for the key confirmation */
#define WFAS3_ECTR_BLOCK       65536U
#define WFAS3_WINDOW_BITS      4096U
#define WFAS3_CTR_LIMIT        ((uint64_t)1U << 60U)
#define WFAS3_RNG_MARGIN       16U
#define WFAS3_GUEST_IDLE_MS    120000U  /* guest pair without a session         */
#define WFAS3_UNUSED_MS        600000U  /* new pair without a session handshake */

/* Global caps per second. Placeholders: the specification requires them to be
 * set from measurement on the slowest supported device (section 15). */
#define WFAS3_CAP_TAGS         4096U
#define WFAS3_CAP_STALE        2U
#define WFAS3_CAP_HS           8U
#define WFAS3_CAP_PROBE        10U      /* fresh answers to Probes             */
#define WFAS3_CAP_PSTALE       2U       /* stale answers to Probes             */

#define WFAS3_PROBE_GAP_MS     2000U    /* one Probe, one fresh answer per pair */
#define WFAS3_PROBE_TRIES      15U      /* Probes of one search                 */
#define WFAS3_PROBE_NONCE_MS   4000U    /* a ProbeReply answers a recent Probe  */
#define WFAS3_STORE_GAP_MS     60000U   /* seen values alone: one write a minute */

static const uint8_t wfas3_zero[128] = { 0U };

/* -- Transport: constants (specification 10, 11) ---------------------------- */

#define WFAS3_F_PADDING        0x00U
#define WFAS3_F_AUDIO          0x01U
#define WFAS3_F_MIC_AUDIO      0x02U
#define WFAS3_F_START          0x03U
#define WFAS3_F_STOP           0x04U
#define WFAS3_F_REPORT         0x05U
#define WFAS3_F_TIME_REQ       0x06U
#define WFAS3_F_TIME_RESP      0x07U
#define WFAS3_F_PARAMS         0x08U
#define WFAS3_F_PARAMS_ACK     0x09U
#define WFAS3_F_REKEY_INIT     0x0AU
#define WFAS3_F_REKEY_RESP     0x0BU
#define WFAS3_F_REKEY_ACK      0x0CU
#define WFAS3_F_PATH_CHALLENGE 0x0DU
#define WFAS3_F_PATH_RESPONSE  0x0EU
#define WFAS3_F_BYE            0x0FU
#define WFAS3_F_ENTRY_KEY      0x10U
#define WFAS3_F_ENTRY_KEY_ACK  0x11U
#define WFAS3_F_GROUP_JOIN     0x12U
#define WFAS3_F_GROUP_ACK      0x13U
#define WFAS3_F_GROUP_LEAVE    0x14U
#define WFAS3_F_NACK           0x15U
#define WFAS3_F_MIC_STATE      0x16U
#define WFAS3_F_FORGET         0x17U
#define WFAS3_F_COUNT          0x18U
#define WFAS3_FLEN_VAR         0xFFFFU

/* Content length of every frame type; AUDIO and MIC_AUDIO depend on the stream. */
static const uint16_t wfas3_flen[WFAS3_F_COUNT] = {
    0U, WFAS3_FLEN_VAR, WFAS3_FLEN_VAR, 4U, 0U, 21U, 8U, 24U, 20U, 4U, 66U, 66U,
    5U, 8U, 8U, 1U, 32U, 8U, 104U, 8U, 4U, 12U, 21U, 0U
};

#define WFAS3_KIND_CONTROL     0U
#define WFAS3_KIND_AUDIO       1U
#define WFAS3_CTL_FRAMES       128U
#define WFAS3_AUDIO_HEAD       20U      /* epoch, sample_pos, capture_us */

#define WFAS3_RK_IDLE          0U
#define WFAS3_RK_I_SEND        1U       /* initiator sends REKEY_INIT           */
#define WFAS3_RK_I_RECV        2U       /* initiator receives REKEY_RESP        */
#define WFAS3_RK_I_SWITCH      3U       /* initiator sends with the new keys    */
#define WFAS3_RK_R_RECV        4U       /* responder receives REKEY_INIT        */
#define WFAS3_RK_R_COMPUTE     5U       /* responder holds it, answer pending   */
#define WFAS3_RK_R_SEND        6U       /* responder sends REKEY_RESP           */
#define WFAS3_RK_INIT_LEN      1300U
#define WFAS3_RK_RESP_LEN      1204U
#define WFAS3_RK_INIT_CHUNKS   21U
#define WFAS3_RK_RESP_CHUNKS   19U
#define WFAS3_RK_CHUNK         64U

#define WFAS3_CTL_MS           100U
#define WFAS3_SILENT_MS        1000U
#define WFAS3_DEAD_MS          15000U
#define WFAS3_CONSENT_MS       2000U
#define WFAS3_CONSENT_US       2000000U
#define WFAS3_PATH_MS          1000U
#define WFAS3_PATH_TRIES       3U
#define WFAS3_BYE_COUNT        3U
#define WFAS3_REKEY_EVERY_MS   120000U
#define WFAS3_REKEY_PHASE_MS   30000U
#define WFAS3_REKEY_LIMIT_MS   60000U
#define WFAS3_REKEY_GAP_MS     100U
#define WFAS3_REKEY_GRACE_MS   1000U    /* the old receive key, after the switch */
#define WFAS3_TREQ_FAST_MS     250U
#define WFAS3_TREQ_SLOW_MS     1000U
#define WFAS3_SYNC_FAST_MS     10000U
#define WFAS3_RTT_MAX_US       15000
#define WFAS3_D_START_MS       60U
#define WFAS3_D_MIN_MS         30U
#define WFAS3_D_MAX_MS         200U
#define WFAS3_D_STEP_MS        5U
#define WFAS3_D_WAIT_MS        30000U
#define WFAS3_TIME_MAX         ((uint64_t)1U << 61U)    /* later times are refused */

typedef char wfas3_assert_chunks[((WFAS3_RK_INIT_CHUNKS * WFAS3_RK_CHUNK) <= 1344U) ? 1 : -1];

/* -- Elligator 2 for curve25519 (RFC 9380, appendix G.2.1) ------------------
 * The only cryptographic code written for this project (specification 17):
 * the straight-line map, of which CPace needs only the u-coordinate, and the
 * two exponentiations it uses. The field arithmetic is fiat-crypto, through
 * wfas_tp.h. The input is derived from the PRS, a secret: no branch or index
 * depends on it, and every intermediate value is erased. */

static const uint8_t wfas3_fe_j[32] = {                 /* J = 486662 */
    0x06U, 0x6DU, 0x07U
};
static const uint8_t wfas3_fe_one[32] = { 0x01U };
static const uint8_t wfas3_fe_sqrtm1[32] = {            /* 2^((p - 1) / 4), RFC 9380 c3 */
    0xB0U, 0xA0U, 0x0EU, 0x4AU, 0x27U, 0x1BU, 0xEEU, 0xC4U, 0x78U, 0xE4U, 0x2FU, 0xADU,
    0x06U, 0x18U, 0x43U, 0x2FU, 0xA7U, 0xD7U, 0xFBU, 0x3DU, 0x99U, 0x00U, 0x4DU, 0x2BU,
    0x0BU, 0xDFU, 0xC1U, 0x4FU, 0x80U, 0x24U, 0x83U, 0x2BU
};

static void wfas3_fe_wipe(wfas_tp_fe *a)
{
    wfas3_wipe((uint8_t *)a, sizeof *a);
}

/* out = a^(2^n); n is a constant of the caller. */
static void wfas3_fe_sqn(wfas_tp_fe *out, const wfas_tp_fe *a, uint32_t n)
{
    uint32_t i;

    *out = *a;
    for (i = 0U; (i < n) && (i < 128U); i++) {
        wfas_tp_fe_sq(out, out);
    }
}

/* out = z^(2^250 - 1) and z11 = z^11: the part shared by both chains. */
static void wfas3_fe_pow250(wfas_tp_fe *out, wfas_tp_fe *z11, const wfas_tp_fe *z)
{
    wfas_tp_fe t0;
    wfas_tp_fe t1;
    wfas_tp_fe t2;

    wfas_tp_fe_sq(&t0, z);                       /* 2 */
    wfas3_fe_sqn(&t1, &t0, 2U);                  /* 8 */
    wfas_tp_fe_mul(&t1, z, &t1);                 /* 9 */
    wfas_tp_fe_mul(z11, &t0, &t1);               /* 11 */
    wfas_tp_fe_sq(&t0, z11);                     /* 22 */
    wfas_tp_fe_mul(&t0, &t1, &t0);               /* 2^5 - 1 */
    wfas3_fe_sqn(&t1, &t0, 5U);
    wfas_tp_fe_mul(&t0, &t1, &t0);               /* 2^10 - 1 */
    wfas3_fe_sqn(&t1, &t0, 10U);
    wfas_tp_fe_mul(&t1, &t1, &t0);               /* 2^20 - 1 */
    wfas3_fe_sqn(&t2, &t1, 20U);
    wfas_tp_fe_mul(&t1, &t2, &t1);               /* 2^40 - 1 */
    wfas3_fe_sqn(&t1, &t1, 10U);
    wfas_tp_fe_mul(&t0, &t1, &t0);               /* 2^50 - 1 */
    wfas3_fe_sqn(&t1, &t0, 50U);
    wfas_tp_fe_mul(&t1, &t1, &t0);               /* 2^100 - 1 */
    wfas3_fe_sqn(&t2, &t1, 100U);
    wfas_tp_fe_mul(&t1, &t2, &t1);               /* 2^200 - 1 */
    wfas3_fe_sqn(&t1, &t1, 50U);
    wfas_tp_fe_mul(out, &t1, &t0);               /* 2^250 - 1 */
    wfas3_fe_wipe(&t0);
    wfas3_fe_wipe(&t1);
    wfas3_fe_wipe(&t2);
}

/* out = z^(p - 2) = z^(2^255 - 21), the inverse of z (0 for z = 0). */
static void wfas3_fe_invert(wfas_tp_fe *out, const wfas_tp_fe *z)
{
    wfas_tp_fe t;
    wfas_tp_fe z11;

    wfas3_fe_pow250(&t, &z11, z);
    wfas3_fe_sqn(&t, &t, 5U);                    /* 2^255 - 32 */
    wfas_tp_fe_mul(out, &t, &z11);               /* 2^255 - 21 */
    wfas3_fe_wipe(&t);
    wfas3_fe_wipe(&z11);
}

/* out = z^((p - 5) / 8) = z^(2^252 - 3). */
static void wfas3_fe_pow22523(wfas_tp_fe *out, const wfas_tp_fe *z)
{
    wfas_tp_fe t;
    wfas_tp_fe z11;

    wfas3_fe_pow250(&t, &z11, z);
    wfas3_fe_sqn(&t, &t, 2U);                    /* 2^252 - 4 */
    wfas_tp_fe_mul(out, &t, z);                  /* 2^252 - 3 */
    wfas3_fe_wipe(&t);
    wfas3_fe_wipe(&z11);
}

/* 1 if a == b in the field, without a branch. */
static uint32_t wfas3_fe_eq(const wfas_tp_fe *a, const wfas_tp_fe *b)
{
    uint8_t x[32];
    uint8_t y[32];
    uint32_t eq;

    wfas_tp_fe_tobytes(x, a);
    wfas_tp_fe_tobytes(y, b);
    eq = wfas3_ct_eq(x, y, sizeof x);
    wfas3_wipe(x, sizeof x);
    wfas3_wipe(y, sizeof y);
    return eq;
}

/* Steps 1 to 15 of G.2.1: tv1, xd, x1n, gxd, gx1, tv3 and tv2 = gx1 * gxd^7. */
typedef struct wfas3_ell2_st {
    wfas_tp_fe tv1;
    wfas_tp_fe tv2;
    wfas_tp_fe tv3;
    wfas_tp_fe xd;
    wfas_tp_fe x1n;
    wfas_tp_fe gxd;
    wfas_tp_fe gx1;
} wfas3_ell2_st;

static void wfas3_ell2_g(wfas3_ell2_st *e, const wfas_tp_fe *u)
{
    wfas_tp_fe one;
    wfas_tp_fe j;
    wfas_tp_fe zero;

    wfas_tp_fe_frombytes(&one, wfas3_fe_one);
    wfas_tp_fe_frombytes(&j, wfas3_fe_j);
    wfas_tp_fe_frombytes(&zero, wfas3_zero);
    wfas_tp_fe_sq(&e->tv1, u);                   /*  1. tv1 = u^2           */
    wfas_tp_fe_add(&e->tv1, &e->tv1, &e->tv1);   /*  2. tv1 = 2 * tv1       */
    wfas_tp_fe_add(&e->xd, &e->tv1, &one);       /*  3. xd = tv1 + 1        */
    wfas_tp_fe_sub(&e->x1n, &zero, &j);          /*  4. x1n = -J            */
    wfas_tp_fe_sq(&e->tv2, &e->xd);              /*  5. tv2 = xd^2          */
    wfas_tp_fe_mul(&e->gxd, &e->tv2, &e->xd);    /*  6. gxd = tv2 * xd      */
    wfas_tp_fe_mul(&e->gx1, &j, &e->tv1);        /*  7. gx1 = J * tv1       */
    wfas_tp_fe_mul(&e->gx1, &e->gx1, &e->x1n);   /*  8. gx1 = gx1 * x1n     */
    wfas_tp_fe_add(&e->gx1, &e->gx1, &e->tv2);   /*  9. gx1 = gx1 + tv2     */
    wfas_tp_fe_mul(&e->gx1, &e->gx1, &e->x1n);   /* 10. gx1 = gx1 * x1n     */
    wfas_tp_fe_sq(&e->tv3, &e->gxd);             /* 11. tv3 = gxd^2         */
    wfas_tp_fe_sq(&e->tv2, &e->tv3);             /* 12. tv2 = tv3^2         */
    wfas_tp_fe_mul(&e->tv3, &e->tv3, &e->gxd);   /* 13. tv3 = tv3 * gxd     */
    wfas_tp_fe_mul(&e->tv3, &e->tv3, &e->gx1);   /* 14. tv3 = tv3 * gx1     */
    wfas_tp_fe_mul(&e->tv2, &e->tv2, &e->tv3);   /* 15. tv2 = tv2 * tv3     */
}

/* out = the u-coordinate of the point that the field element in (32 bytes,
 * little-endian, bit 255 ignored) maps to. Steps 16 to 23 and 32 to 35 of
 * G.2.1 choose x1 or x2; the y-coordinate is not computed. */
static void wfas3_ell2(uint8_t out[32], const uint8_t in[32])
{
    wfas3_ell2_st e;
    wfas_tp_fe u;
    wfas_tp_fe c3;
    wfas_tp_fe y11;
    wfas_tp_fe y12;
    wfas_tp_fe y1;
    wfas_tp_fe x2n;
    wfas_tp_fe t;
    uint32_t e1;
    uint32_t e3;

    wfas_tp_fe_frombytes(&u, in);
    wfas_tp_fe_frombytes(&c3, wfas3_fe_sqrtm1);
    wfas3_ell2_g(&e, &u);
    wfas3_fe_pow22523(&y11, &e.tv2);             /* 16. y11 = tv2^c4        */
    wfas_tp_fe_mul(&y11, &y11, &e.tv3);          /* 17. y11 = y11 * tv3     */
    wfas_tp_fe_mul(&y12, &y11, &c3);             /* 18. y12 = y11 * c3      */
    wfas_tp_fe_sq(&t, &y11);                     /* 19. tv2 = y11^2         */
    wfas_tp_fe_mul(&t, &t, &e.gxd);              /* 20. tv2 = tv2 * gxd     */
    e1 = wfas3_fe_eq(&t, &e.gx1);                /* 21. e1 = tv2 == gx1     */
    wfas_tp_fe_cmov(&y1, &y12, &y11, e1);        /* 22. y1 = CMOV(y12, y11, e1) */
    wfas_tp_fe_mul(&x2n, &e.x1n, &e.tv1);        /* 23. x2n = x1n * tv1     */
    wfas_tp_fe_sq(&t, &y1);                      /* 32. tv2 = y1^2          */
    wfas_tp_fe_mul(&t, &t, &e.gxd);              /* 33. tv2 = tv2 * gxd     */
    e3 = wfas3_fe_eq(&t, &e.gx1);                /* 34. e3 = tv2 == gx1     */
    wfas_tp_fe_cmov(&t, &x2n, &e.x1n, e3);       /* 35. xn = CMOV(x2n, x1n, e3) */
    wfas3_fe_invert(&y1, &e.xd);
    wfas_tp_fe_mul(&t, &t, &y1);                 /* u = xn / xd             */
    wfas_tp_fe_tobytes(out, &t);
    wfas3_wipe((uint8_t *)&e, sizeof e);
    wfas3_fe_wipe(&u);
    wfas3_fe_wipe(&y11);
    wfas3_fe_wipe(&y12);
    wfas3_fe_wipe(&y1);
    wfas3_fe_wipe(&x2n);
    wfas3_fe_wipe(&t);
}

/* -- CPace, suite CPACE-X25519-SHA512 (draft-irtf-cfrg-cpace-21) -------------
 * Only inputs shorter than 128 bytes occur, so every LEB128 length is one
 * byte. DSI = "CPace255", s_in_bytes = 128, field size 32 bytes, 255 bits. */

#define WFAS3_CPACE_IN_MAX   64U    /* longest PRS, CI, sid or AD accepted    */
#define WFAS3_CPACE_GEN_MAX  384U

static const uint8_t wfas3_cpace_dsi[8] = { 0x43U, 0x50U, 0x61U, 0x63U, 0x65U, 0x32U, 0x35U, 0x35U };
static const uint8_t wfas3_cpace_dsi_isk[12] = {
    0x43U, 0x50U, 0x61U, 0x63U, 0x65U, 0x32U, 0x35U, 0x35U, 0x5FU, 0x49U, 0x53U, 0x4BU
};

/* prepend_len(d) at out, for len < 128. Returns the bytes written. */
static size_t wfas3_lv(uint8_t *out, const uint8_t *d, size_t len)
{
    out[0] = (uint8_t)len;
    if (len > 0U) {
        memcpy(&out[1], d, len);
    }
    return len + 1U;
}

/* g = calculate_generator(H, PRS, CI, sid): the generator string, SHA-512,
 * the first 32 bytes decoded as a 255-bit u-coordinate, Elligator 2. */
static wfas3_status wfas3_cpace_gen(uint8_t g[32], const uint8_t *prs, size_t prs_len,
                                    const uint8_t *ci, size_t ci_len,
                                    const uint8_t *sid, size_t sid_len)
{
    uint8_t buf[WFAS3_CPACE_GEN_MAX];
    uint8_t h[64];
    size_t pos = 0U;
    size_t zpad;
    wfas3_status st = WFAS3_E_ARG;

    if ((prs_len <= (size_t)WFAS3_CPACE_IN_MAX) && (ci_len <= (size_t)WFAS3_CPACE_IN_MAX) &&
        (sid_len <= (size_t)WFAS3_CPACE_IN_MAX)) {
        /* len_zpad = s_in_bytes - 1 - len(prepend_len(PRS)) - len(prepend_len(DSI)) */
        zpad = 128U - 1U - (prs_len + 1U) - (sizeof wfas3_cpace_dsi + 1U);
        pos += wfas3_lv(&buf[pos], wfas3_cpace_dsi, sizeof wfas3_cpace_dsi);
        pos += wfas3_lv(&buf[pos], prs, prs_len);
        pos += wfas3_lv(&buf[pos], wfas3_zero, zpad);
        pos += wfas3_lv(&buf[pos], ci, ci_len);
        pos += wfas3_lv(&buf[pos], sid, sid_len);
        wfas_tp_sha512(h, buf, (uint32_t)pos);
        h[31] = (uint8_t)(h[31] & 0x7FU);        /* decodeUCoordinate, 255 bits */
        wfas3_ell2(g, h);
        st = WFAS3_OK;
    }
    wfas3_wipe(buf, sizeof buf);
    wfas3_wipe(h, sizeof h);
    return st;
}

/* ISK = SHA-512(lv_cat(DSI_ISK, sid, K) || lv_cat(Ya, ADa) || lv_cat(Yb, ADb)),
 * the initiator-responder transcript. */
static wfas3_status wfas3_cpace_isk(uint8_t isk[64], const uint8_t *sid, size_t sid_len,
                                    const uint8_t k[32], const uint8_t ya[32],
                                    const uint8_t *ada, size_t ada_len,
                                    const uint8_t yb[32], const uint8_t *adb, size_t adb_len)
{
    uint8_t buf[WFAS3_CPACE_GEN_MAX];
    size_t pos = 0U;
    wfas3_status st = WFAS3_E_ARG;

    if ((sid_len <= (size_t)WFAS3_CPACE_IN_MAX) && (ada_len <= (size_t)WFAS3_CPACE_IN_MAX) &&
        (adb_len <= (size_t)WFAS3_CPACE_IN_MAX)) {
        pos += wfas3_lv(&buf[pos], wfas3_cpace_dsi_isk, sizeof wfas3_cpace_dsi_isk);
        pos += wfas3_lv(&buf[pos], sid, sid_len);
        pos += wfas3_lv(&buf[pos], k, 32U);
        pos += wfas3_lv(&buf[pos], ya, 32U);
        pos += wfas3_lv(&buf[pos], ada, ada_len);
        pos += wfas3_lv(&buf[pos], yb, 32U);
        pos += wfas3_lv(&buf[pos], adb, adb_len);
        wfas_tp_sha512(isk, buf, (uint32_t)pos);
        st = WFAS3_OK;
    }
    wfas3_wipe(buf, sizeof buf);
    return st;
}


/* -- Byte order (continued) -------------------------------------------------- */

static void wfas3_wr_be16(uint8_t out[2], uint16_t v)
{
    out[0] = (uint8_t)(v >> 8U);
    out[1] = (uint8_t)(v & 0xFFU);
}

static void wfas3_wr_be32(uint8_t out[4], uint32_t v)
{
    uint32_t i;

    for (i = 0U; i < 4U; i++) {
        out[i] = (uint8_t)(v >> (24U - (8U * i)));
    }
}

static uint16_t wfas3_rd_be16(const uint8_t in[2])
{
    return (uint16_t)(((uint32_t)in[0] << 8U) | (uint32_t)in[1]);
}

static uint32_t wfas3_rd_be32(const uint8_t in[4])
{
    uint32_t v = 0U;
    uint32_t i;

    for (i = 0U; i < 4U; i++) {
        v = (v << 8U) | (uint32_t)in[i];
    }
    return v;
}

static uint64_t wfas3_rd_be64(const uint8_t in[8])
{
    uint64_t v = 0U;
    uint32_t i;

    for (i = 0U; i < 8U; i++) {
        v = (v << 8U) | (uint64_t)in[i];
    }
    return v;
}

/* -- Small helpers ----------------------------------------------------------- */

static void wfas3_out_clear(wfas3_out *out)
{
    out->len = 0U;
    out->events = 0U;
    out->pair = WFAS3_NONE;
    out->session = WFAS3_NONE;
    out->status = 0U;
    memset(&out->to, 0, sizeof out->to);
    out->broadcast = 0U;
    memset(&out->config, 0, sizeof out->config);
    out->stream_on = 0U;
    memset(&out->addr, 0, sizeof out->addr);
    out->stream = 0U;
    out->epoch = 0U;
    out->sample_pos = 0U;
    out->capture_us = 0U;
    out->playout_us = 0U;
    out->audio_len = 0U;
    out->code = 0U;
    out->ask = 0U;
    out->pflags = 0U;
    memset(out->name, 0, sizeof out->name);
    memset(&out->beacon, 0, sizeof out->beacon);
}

static uint64_t wfas3_min64(uint64_t a, uint64_t b)
{
    return (a < b) ? a : b;
}

/* At most cap events per second. Returns 1 if this one is allowed. */
static uint32_t wfas3_rate_take(uint64_t *win_ms, uint32_t *count, uint64_t now, uint32_t cap)
{
    uint32_t ok = 0U;

    if ((now - *win_ms) >= 1000U) {
        *win_ms = now;
        *count = 0U;
    }
    if (*count < cap) {
        *count += 1U;
        ok = 1U;
    }
    return ok;
}

/* 1 if a < b, both read as big-endian numbers. Inputs are public keys. */
static uint32_t wfas3_key_less(const uint8_t a[32], const uint8_t b[32])
{
    uint32_t lt = 0U;
    uint32_t decided = 0U;
    uint32_t i;

    for (i = 0U; i < 32U; i++) {
        uint32_t is_lt = (a[i] < b[i]) ? 1U : 0U;
        uint32_t is_gt = (a[i] > b[i]) ? 1U : 0U;

        lt |= is_lt & (1U ^ decided);
        decided |= is_lt | is_gt;
    }
    return lt;
}

/* Directional key of specification 6.1: KDF(k_fix, name || "LH" or "HL").
 * low_sender is 1 when the request the key protects travels low to high. */
static wfas3_status wfas3_dir_key(uint8_t out[WFAS3_KEY_LEN], const uint8_t k_fix[WFAS3_KEY_LEN],
                                  const char *name, size_t name_len, uint32_t low_sender)
{
    char label[32];
    wfas3_status st = WFAS3_E_ARG;
    size_t i;

    if (name_len <= 30U) {
        for (i = 0U; i < name_len; i++) {
            label[i] = name[i];
        }
        label[name_len] = (low_sender == 1U) ? 'L' : 'H';
        label[name_len + 1U] = (low_sender == 1U) ? 'H' : 'L';
        st = wfas3_kdf(out, WFAS3_KEY_LEN, k_fix, label, name_len + 2U, NULL, 0U);
    }
    return st;
}

/* Ask for the record of a pair to be written. It carries ectr_seen,
 * ectr_used and probe_seen too, so these are no longer waiting (7.3). */
static void wfas3_store_pair(wfas3_device *dev, uint32_t pair, wfas3_out *out)
{
    wfas3_pair *p = &dev->pairs[pair];

    if ((p->flags & WFAS3_PF_GUEST) == 0U) {       /* guest records are never stored (13.6) */
        out->events |= WFAS3_EV_STORE_PAIR;
        out->pair = pair;
    }
    p->seen_dirty = 0U;
    p->next_store_ms = dev->now_ms + (uint64_t)WFAS3_STORE_GAP_MS;
}

static void wfas3_suspect(wfas3_device *dev, uint32_t pair, wfas3_out *out)
{
    dev->pairs[pair].flags |= WFAS3_PF_SUSPECT;
    out->events |= WFAS3_EV_SUSPECT;
    wfas3_store_pair(dev, pair, out);
}

/* -- Attempts and sessions --------------------------------------------------- */

/* 1 if v is the receive index of a live attempt or session of this device. */
static uint32_t wfas3_index_used(const wfas3_device *dev, uint32_t v)
{
    uint32_t used = 0U;
    uint32_t i;

    for (i = 0U; i < WFAS3_MAX_ATTEMPTS; i++) {
        if ((dev->attempts[i].state != WFAS3_ATT_FREE) && (dev->attempts[i].index == v)) {
            used = 1U;
        }
    }
    for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
        const wfas3_session *s = &dev->sessions[i];
        uint32_t rk_holds = ((s->rk.state == WFAS3_RK_I_SEND) || (s->rk.state == WFAS3_RK_I_RECV) ||
                             (s->rk.state == WFAS3_RK_I_SWITCH) || (s->rk.state == WFAS3_RK_R_SEND))
                            ? 1U : 0U;

        if ((s->state != WFAS3_SES_FREE) &&
            ((s->recv_index == v) || ((rk_holds == 1U) && (s->rk.new_index == v)) ||
             ((s->old_valid == 1U) && (s->recv_index_old == v)))) {
            used = 1U;
        }
    }
    return used;
}

/* A fresh random receive index, distinct from every index in use, so that a
 * datagram is never matched to the wrong attempt or session. */
static wfas3_status wfas3_index_new(wfas3_device *dev, uint32_t *index)
{
    uint8_t b[4];
    uint32_t tries;
    uint32_t done = 0U;
    wfas3_status st = WFAS3_E_FULL;

    for (tries = 0U; (tries < 8U) && (done == 0U); tries++) {
        if (wfas3_rng_bytes(&dev->rng, b, sizeof b) != WFAS3_OK) {
            st = WFAS3_E_ENTROPY;
            done = 1U;
        } else if (wfas3_index_used(dev, wfas3_rd_be32(b)) == 0U) {
            *index = wfas3_rd_be32(b);
            st = WFAS3_OK;
            done = 1U;
        } else {
            /* collision: draw again */
        }
    }
    return st;
}

static uint32_t wfas3_att_find_pair(const wfas3_device *dev, uint32_t pair)
{
    uint32_t found = WFAS3_NONE;
    uint32_t i;

    for (i = 0U; i < WFAS3_MAX_ATTEMPTS; i++) {
        if ((dev->attempts[i].state != WFAS3_ATT_FREE) && (dev->attempts[i].pair == pair)) {
            found = i;
        }
    }
    return found;
}

static uint32_t wfas3_att_find_index(const wfas3_device *dev, uint32_t index, uint32_t state)
{
    uint32_t found = WFAS3_NONE;
    uint32_t i;

    for (i = 0U; i < WFAS3_MAX_ATTEMPTS; i++) {
        if ((dev->attempts[i].state == state) && (dev->attempts[i].index == index)) {
            found = i;
        }
    }
    return found;
}

static uint32_t wfas3_att_alloc(const wfas3_device *dev)
{
    uint32_t found = WFAS3_NONE;
    uint32_t i;

    for (i = 0U; i < WFAS3_MAX_ATTEMPTS; i++) {
        if ((dev->attempts[i].state == WFAS3_ATT_FREE) && (found == WFAS3_NONE)) {
            found = i;
        }
    }
    return found;
}

/* Erase the handshake secrets of an attempt, keep what a retry needs. */
static void wfas3_att_scrub(wfas3_attempt *a)
{
    wfas3_wipe(a->e_priv, sizeof a->e_priv);
    wfas3_wipe(a->kem_sk, sizeof a->kem_sk);
    wfas3_sym_wipe(&a->sym);
}

static void wfas3_find_free(wfas3_search *f)
{
    wfas3_wipe((uint8_t *)f, sizeof *f);
    f->state = 0U;
    f->pair = WFAS3_NONE;
}

/* End the search for a pair that is removed or replaced, without an event. */
static void wfas3_find_drop_pair(wfas3_device *dev, uint32_t pair)
{
    uint32_t i;

    for (i = 0U; i < WFAS3_MAX_FINDS; i++) {
        if ((dev->finds[i].state == 1U) && (dev->finds[i].pair == pair)) {
            wfas3_find_free(&dev->finds[i]);
        }
    }
}

static void wfas3_att_free(wfas3_attempt *a)
{
    wfas3_att_scrub(a);
    a->state = WFAS3_ATT_FREE;
    a->pair = WFAS3_NONE;
}

/* Erase everything a session holds: keys, rekey state, buffers. */
static void wfas3_ses_free(wfas3_session *s)
{
    wfas3_wipe((uint8_t *)s, sizeof *s);
    s->state = WFAS3_SES_FREE;
    s->pair = WFAS3_NONE;
}

static uint32_t wfas3_ses_alloc(const wfas3_device *dev)
{
    uint32_t found = WFAS3_NONE;
    uint32_t i;

    for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
        if ((dev->sessions[i].state == WFAS3_SES_FREE) && (found == WFAS3_NONE)) {
            found = i;
        }
    }
    return found;
}

/* Free the sessions of a pair, as mode (WFAS3_DROP_*) says. The session keep
 * (or WFAS3_NONE) is never freed. */
static void wfas3_ses_drop_pair(wfas3_device *dev, uint32_t pair, uint32_t mode, uint32_t keep)
{
    uint32_t i;

    for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
        wfas3_session *s = &dev->sessions[i];

        if ((s->state != WFAS3_SES_FREE) && (s->pair == pair) && (i != keep)) {
            if ((mode == WFAS3_DROP_ALL) ||
                ((mode == WFAS3_DROP_UNCONFIRMED) && (s->state == WFAS3_SES_UNCONFIRMED)) ||
                ((mode == WFAS3_DROP_OPEN) && (s->state != WFAS3_SES_CLOSING))) {
                wfas3_ses_free(s);
            }
        }
    }
}

/* -- Anti-replay window (specification 10.1) --------------------------------- */

static uint32_t wfas3_win_bit(const wfas3_window *w, uint64_t ctr)
{
    uint32_t pos = (uint32_t)(ctr & (uint64_t)(WFAS3_WINDOW_BITS - 1U));

    return (uint32_t)((w->bits[pos >> 6U] >> (pos & 63U)) & 1U);
}

/* 1 if the counter may be accepted. Does not change the window. */
static uint32_t wfas3_win_check(const wfas3_window *w, uint64_t ctr)
{
    uint32_t ok = 1U;

    if (ctr >= WFAS3_CTR_LIMIT) {
        ok = 0U;
    } else if ((w->any == 1U) && (ctr <= w->max)) {
        if ((w->max - ctr) >= (uint64_t)WFAS3_WINDOW_BITS) {
            ok = 0U;
        } else {
            ok = 1U ^ wfas3_win_bit(w, ctr);
        }
    } else {
        ok = 1U;
    }
    return ok;
}

/* Record an authenticated counter. Returns 1 if it is the highest so far. */
static uint32_t wfas3_win_mark(wfas3_window *w, uint64_t ctr)
{
    uint32_t advanced = 0U;
    uint32_t pos;
    uint64_t i;

    if ((w->any == 0U) || (ctr > w->max)) {
        uint64_t diff = (w->any == 0U) ? (uint64_t)WFAS3_WINDOW_BITS : (ctr - w->max);

        if (diff >= (uint64_t)WFAS3_WINDOW_BITS) {
            memset(w->bits, 0, sizeof w->bits);
        } else {
            for (i = 1U; (i < diff) && (i < (uint64_t)WFAS3_WINDOW_BITS); i++) {
                pos = (uint32_t)((w->max + i) & (uint64_t)(WFAS3_WINDOW_BITS - 1U));
                w->bits[pos >> 6U] &= ~((uint64_t)1U << (pos & 63U));
            }
        }
        w->max = ctr;
        w->any = 1U;
        advanced = 1U;
    }
    pos = (uint32_t)(ctr & (uint64_t)(WFAS3_WINDOW_BITS - 1U));
    w->bits[pos >> 6U] |= ((uint64_t)1U << (pos & 63U));
    return advanced;
}

/* -- Stream configuration (specification 8.3) -------------------------------- */

static void wfas3_config_encode(uint8_t c[16], const wfas3_config *cfg, uint8_t status)
{
    c[0] = cfg->flags;
    c[1] = cfg->codec;
    c[2] = cfg->channels;
    c[3] = cfg->bits;
    wfas3_wr_be32(&c[4], cfg->sample_rate);
    wfas3_wr_be16(&c[8], cfg->frame_samples);
    wfas3_wr_be16(&c[10], cfg->payload_bytes);
    wfas3_wr_be16(&c[12], cfg->latency_ms);
    c[14] = status;
    c[15] = 0U;
}

static void wfas3_config_decode(wfas3_config *cfg, const uint8_t c[16])
{
    cfg->flags = c[0];
    cfg->codec = c[1];
    cfg->channels = c[2];
    cfg->bits = c[3];
    cfg->sample_rate = wfas3_rd_be32(&c[4]);
    cfg->frame_samples = wfas3_rd_be16(&c[8]);
    cfg->payload_bytes = wfas3_rd_be16(&c[10]);
    cfg->latency_ms = wfas3_rd_be16(&c[12]);
}

static uint32_t wfas3_pcm_ok(uint32_t rate, uint32_t ch, uint32_t bits, uint32_t frames,
                             uint32_t bytes)
{
    uint32_t ok = 0U;
    uint32_t rate_ok;

    switch (rate) {
    case 44100U:
    case 48000U:
    case 88200U:
    case 96000U:
    case 176400U:
    case 192000U:
        rate_ok = 1U;
        break;
    default:
        rate_ok = 0U;
        break;
    }
    if ((rate_ok == 1U) && ((bits == 16U) || (bits == 24U)) && (ch >= 1U) && (ch <= 8U)) {
        uint32_t frame_bytes = ch * (bits / 8U);
        uint32_t five_ms = rate / 200U;
        uint32_t fit = WFAS3_MAX_AUDIO_PAYLOAD / frame_bytes;
        uint32_t want = (five_ms < fit) ? five_ms : fit;

        ok = ((frames == want) && (bytes == (want * frame_bytes))) ? 1U : 0U;
    }
    return ok;
}

static uint32_t wfas3_opus_ok(uint32_t rate, uint32_t bits, uint32_t frames)
{
    uint32_t frames_ok = ((frames == 120U) || (frames == 240U) || (frames == 480U) ||
                          (frames == 960U)) ? 1U : 0U;

    return ((rate == 48000U) && (bits == 16U)) ? frames_ok : 0U;
}

/* 1 if the 16 bytes are a well-formed StreamConfig. */
static uint32_t wfas3_config_valid(const uint8_t c[16], uint32_t is_resp)
{
    uint32_t rate = wfas3_rd_be32(&c[4]);
    uint32_t frames = wfas3_rd_be16(&c[8]);
    uint32_t bytes = wfas3_rd_be16(&c[10]);
    uint32_t ok = 1U;

    ok &= ((c[0] & 0xF0U) == 0U) ? 1U : 0U;
    ok &= ((c[2] >= 1U) && (c[2] <= 8U)) ? 1U : 0U;
    ok &= (c[15] == 0U) ? 1U : 0U;
    ok &= ((bytes >= 1U) && (bytes <= WFAS3_MAX_AUDIO_PAYLOAD)) ? 1U : 0U;
    ok &= (((c[0] & WFAS3_CF_MULTICAST) == 0U) || (bytes <= WFAS3_MAX_MCAST_PAYLOAD)) ? 1U : 0U;
    if (is_resp == 1U) {
        ok &= (c[14] <= WFAS3_ST_UNSUPPORTED) ? 1U : 0U;
    } else {
        ok &= (c[14] == 0U) ? 1U : 0U;
    }
    if (ok == 1U) {
        if (c[1] == WFAS3_CODEC_PCM) {
            ok = wfas3_pcm_ok(rate, c[2], c[3], frames, bytes);
        } else if (c[1] == WFAS3_CODEC_OPUS) {
            ok = wfas3_opus_ok(rate, c[3], frames);
        } else {
            ok = 0U;
        }
    }
    return ok;
}

/* -- Pair records (specification 5) ------------------------------------------ */

/* Layout of the stored record, WFAS3_PAIR_RECORD_LEN bytes:
 *   0 version   1 flags   2 permissions   3 presence bits
 *   4 own static private key     36 peer static public key   68 K_fix
 * 100 psk cur  132 psk mine     164 psk theirs
 * 196 peer entry key            228 own entry key held by the peer
 * 260 ectr_seen  268 ectr_used  276 probe_seen
 * 284 report slots base, last, mine_c, resp_p: 4 x (fingerprint 16, seq 8)
 * 380 seq_hi   388 name (32) */
#define WFAS3_REC_VERSION 1U

static void wfas3_pair_groups(wfas3_device *dev)
{
    uint32_t i;
    uint32_t j;

    for (i = 0U; i < WFAS3_MAX_PAIRS; i++) {
        wfas3_pair *p = &dev->pairs[i];

        if (p->used == 1U) {
            p->held_current = wfas3_ct_eq(p->own_entry_held, dev->entry_key, WFAS3_KEY_LEN);
            p->held_group = i;
            for (j = 0U; j < i; j++) {
                if ((dev->pairs[j].used == 1U) && (p->held_group == i) &&
                    (wfas3_ct_eq(p->own_entry_held, dev->pairs[j].own_entry_held,
                                 WFAS3_KEY_LEN) == 1U)) {
                    p->held_group = j;
                }
            }
        }
    }
}

static void wfas3_pair_parse(wfas3_pair *p, const uint8_t *r)
{
    uint32_t i;

    p->flags = r[1];
    p->perms = r[2];
    p->has_mine = (uint32_t)r[3] & 1U;
    p->has_theirs = ((uint32_t)r[3] >> 1U) & 1U;
    memcpy(p->s_priv, &r[4], 32U);
    memcpy(p->peer_pub, &r[36], 32U);
    memcpy(p->k_fix, &r[68], 32U);
    memcpy(p->psk[0], &r[100], 32U);
    memcpy(p->psk[1], &r[132], 32U);
    memcpy(p->psk[2], &r[164], 32U);
    memcpy(p->peer_entry, &r[196], 32U);
    memcpy(p->own_entry_held, &r[228], 32U);
    p->ectr_seen = wfas3_rd_be64(&r[260]);
    p->ectr_used = wfas3_rd_be64(&r[268]);
    p->probe_seen = wfas3_rd_be64(&r[276]);
    for (i = 0U; i < 4U; i++) {
        memcpy(p->rep_fp[i], &r[284U + (24U * i)], 16U);
        p->rep_seq[i] = wfas3_rd_be64(&r[300U + (24U * i)]);
    }
    p->rep_has[WFAS3_REP_BASE] = 1U;
    p->rep_has[WFAS3_REP_LAST] = 1U;
    p->rep_has[WFAS3_REP_MINEC] = ((uint32_t)r[3] >> 2U) & 1U;
    p->rep_has[WFAS3_REP_RESPP] = ((uint32_t)r[3] >> 3U) & 1U;
    p->seq_hi = wfas3_rd_be64(&r[380]);
    memcpy(p->name, &r[388], 32U);
}

/* Keys derived from a record, shared with wfas3_pair_import. On failure the
 * slot is erased. */
static wfas3_status wfas3_pair_setup(wfas3_device *dev, wfas3_pair *p)
{
    wfas3_status st;

    wfas_tp_x25519_public(p->s_pub, p->s_priv);
    p->is_low = wfas3_key_less(p->s_pub, p->peer_pub);
    st = wfas3_dir_key(p->k_tag_in, p->k_fix, WFAS3_LBL("pair tag "), 1U ^ p->is_low);
    if (st == WFAS3_OK) {
        st = wfas3_dir_key(p->k_probe_in, p->k_fix, WFAS3_LBL("probe "), 1U ^ p->is_low);
    }
    if (st == WFAS3_OK) {
        st = wfas3_kdf(p->k_mac1_held, WFAS3_KEY_LEN, p->own_entry_held, WFAS3_LBL("mac1"), NULL, 0U);
    }
    if ((st == WFAS3_OK) && (wfas3_ct_eq(p->s_pub, p->peer_pub, WFAS3_KEY_LEN) == 0U)) {
        p->ectr_send = dev->ectr_high;                   /* never below a value used in this run */
        p->used = 1U;
        wfas3_pair_groups(dev);
    } else {
        wfas3_wipe((uint8_t *)p, sizeof *p);             /* every key, derived ones too */
        p->used = 0U;
        st = WFAS3_E_ARG;
    }
    return st;
}

wfas3_status wfas3_pair_import(wfas3_device *dev, uint32_t pair, const uint8_t *record)
{
    wfas3_status st = WFAS3_E_ARG;
    wfas3_pair *p;
    uint32_t ai;

    if ((dev != NULL) && (record != NULL) && (dev->started == 1U) &&
        (pair < WFAS3_MAX_PAIRS) && (record[0] == WFAS3_REC_VERSION) &&
        ((record[1] & ~WFAS3_PF_SUSPECT) == 0U) &&      /* a guest is never stored (13.6) */
        ((record[3] & 0xF0U) == 0U)) {
        p = &dev->pairs[pair];
        ai = wfas3_att_find_pair(dev, pair);
        if (ai != WFAS3_NONE) {
            wfas3_att_free(&dev->attempts[ai]);
        }
        wfas3_ses_drop_pair(dev, pair, WFAS3_DROP_ALL, WFAS3_NONE);
        wfas3_find_drop_pair(dev, pair);
        wfas3_wipe((uint8_t *)p, sizeof *p);
        wfas3_pair_parse(p, record);
        st = wfas3_pair_setup(dev, p);
        if ((st == WFAS3_OK) && (p->seq_hi == 0U)) {
            p->unused_until_ms = dev->now_ms + (uint64_t)WFAS3_UNUSED_MS;  /* 13.4 */
        }
    }
    return st;
}

wfas3_status wfas3_pair_export(const wfas3_device *dev, uint32_t pair, uint8_t *record)
{
    wfas3_status st = WFAS3_E_ARG;
    const wfas3_pair *p;
    uint32_t i;

    if ((dev != NULL) && (record != NULL) && (pair < WFAS3_MAX_PAIRS) &&
        (dev->pairs[pair].used == 1U) && ((dev->pairs[pair].flags & WFAS3_PF_GUEST) == 0U)) {
        p = &dev->pairs[pair];
        record[0] = WFAS3_REC_VERSION;
        record[1] = (uint8_t)(p->flags & 0xFFU);
        record[2] = (uint8_t)(p->perms & 0xFFU);
        record[3] = (uint8_t)(p->has_mine | (p->has_theirs << 1U) |
                              (p->rep_has[WFAS3_REP_MINEC] << 2U) |
                              (p->rep_has[WFAS3_REP_RESPP] << 3U));
        memcpy(&record[4], p->s_priv, 32U);
        memcpy(&record[36], p->peer_pub, 32U);
        memcpy(&record[68], p->k_fix, 32U);
        memcpy(&record[100], p->psk[0], 32U);
        memcpy(&record[132], p->psk[1], 32U);
        memcpy(&record[164], p->psk[2], 32U);
        memcpy(&record[196], p->peer_entry, 32U);
        memcpy(&record[228], p->own_entry_held, 32U);
        wfas3_wr_be64(&record[260], p->ectr_seen);
        wfas3_wr_be64(&record[268], p->ectr_used);
        wfas3_wr_be64(&record[276], p->probe_seen);
        for (i = 0U; i < 4U; i++) {
            memcpy(&record[284U + (24U * i)], p->rep_fp[i], 16U);
            wfas3_wr_be64(&record[300U + (24U * i)], p->rep_seq[i]);
        }
        wfas3_wr_be64(&record[380], p->seq_hi);
        memcpy(&record[388], p->name, 32U);
        st = WFAS3_OK;
    }
    return st;
}

/* -- Entry: mac1, pair tag, counter (specification 7.1 to 7.3) --------------- */

static wfas3_status wfas3_mac1_out(uint8_t out[WFAS3_TAG_LEN], const wfas3_pair *p,
                                   const uint8_t *d, size_t len)
{
    uint8_t k[WFAS3_KEY_LEN];
    wfas3_status st = wfas3_kdf(k, sizeof k, p->peer_entry, WFAS3_LBL("mac1"), NULL, 0U);

    if (st == WFAS3_OK) {
        st = wfas3_mac(out, WFAS3_TAG_LEN, k, d, len);
    }
    wfas3_wipe(k, sizeof k);
    return st;
}

/* Bit i of the result is set if pair i may have sent the datagram, judging by
 * mac1 alone. d[len .. len+16) is the mac1 field. Zero: drop. */
static uint64_t wfas3_mac1_candidates(const wfas3_device *dev, const uint8_t *d, size_t len)
{
    uint8_t m[WFAS3_TAG_LEN];
    uint64_t mask = 0U;
    uint32_t i;
    uint32_t j;

    if ((wfas3_mac(m, sizeof m, dev->k_mac1, d, len) == WFAS3_OK) &&
        (wfas3_ct_eq(m, &d[len], sizeof m) == 1U)) {
        for (i = 0U; i < WFAS3_MAX_PAIRS; i++) {
            if (dev->pairs[i].used == 1U) {
                mask |= ((uint64_t)1U << i);
            }
        }
    } else {
        for (i = 0U; i < WFAS3_MAX_PAIRS; i++) {
            const wfas3_pair *p = &dev->pairs[i];

            if ((mask == 0U) && (p->used == 1U) && (p->held_current == 0U) &&
                (p->held_group == i) &&
                (wfas3_mac(m, sizeof m, p->k_mac1_held, d, len) == WFAS3_OK) &&
                (wfas3_ct_eq(m, &d[len], sizeof m) == 1U)) {
                for (j = 0U; j < WFAS3_MAX_PAIRS; j++) {
                    if ((dev->pairs[j].used == 1U) && (dev->pairs[j].held_group == i)) {
                        mask |= ((uint64_t)1U << j);
                    }
                }
            }
        }
    }
    return mask;
}

/* The pair whose tag key (K_tag, or K_probe when probe == 1) for the incoming
 * direction verifies tag over d[0 .. len), or WFAS3_NONE. */
static uint32_t wfas3_tag_select(wfas3_device *dev, uint64_t cand, const uint8_t *d, size_t len,
                                 const uint8_t *tag, uint32_t probe)
{
    uint8_t m[WFAS3_TAG_LEN];
    uint32_t found = WFAS3_NONE;
    uint32_t i;

    for (i = 0U; i < WFAS3_MAX_PAIRS; i++) {
        if ((((cand >> i) & 1U) == 1U) && (found == WFAS3_NONE) &&
            (wfas3_rate_take(&dev->tag_win_ms, &dev->tag_count, dev->now_ms,
                             WFAS3_CAP_TAGS) == 1U) &&
            (wfas3_mac(m, sizeof m, (probe == 1U) ? dev->pairs[i].k_probe_in : dev->pairs[i].k_tag_in,
                       d, len) == WFAS3_OK) &&
            (wfas3_ct_eq(m, tag, sizeof m) == 1U)) {
            found = i;
        }
    }
    return found;
}

/* mask = MAC8(K_ctr[d], type || nonce16). */
static wfas3_status wfas3_ctr_mask(uint8_t mask[8], const wfas3_pair *p, uint32_t low_sender,
                                   uint8_t type, const uint8_t nonce[16])
{
    uint8_t k[WFAS3_KEY_LEN];
    uint8_t in[17];
    wfas3_status st = wfas3_dir_key(k, p->k_fix, WFAS3_LBL("entry counter "), low_sender);

    if (st == WFAS3_OK) {
        in[0] = type;
        memcpy(&in[1], nonce, 16U);
        st = wfas3_mac(mask, 8U, k, in, sizeof in);
    }
    wfas3_wipe(k, sizeof k);
    return st;
}

static uint64_t wfas3_ectr_next(wfas3_device *dev, wfas3_pair *p, wfas3_out *out)
{
    p->ectr_send += 1U;
    if (p->ectr_send >= dev->ectr_limit) {
        dev->ectr_limit += (uint64_t)WFAS3_ECTR_BLOCK;
        out->events |= WFAS3_EV_STORE_DEVICE;
    }
    if (p->ectr_send > dev->ectr_high) {
        dev->ectr_high = p->ectr_send;
    }
    return p->ectr_send;
}

/* -- Cookies (specification 7.5) --------------------------------------------- */

static wfas3_status wfas3_cookie_rotate(wfas3_device *dev)
{
    uint8_t fresh[WFAS3_KEY_LEN];
    wfas3_status st = WFAS3_OK;

    if ((dev->cookie_ready == 0U) || (dev->now_ms >= dev->cookie_next_ms)) {
        st = wfas3_rng_bytes(&dev->rng, fresh, sizeof fresh);
        if (st == WFAS3_OK) {
            if (dev->cookie_ready == 0U) {
                memcpy(dev->cookie_secret[1], fresh, WFAS3_KEY_LEN);
            } else {
                memcpy(dev->cookie_secret[1], dev->cookie_secret[0], WFAS3_KEY_LEN);
            }
            memcpy(dev->cookie_secret[0], fresh, WFAS3_KEY_LEN);
            dev->cookie_ready = 1U;
            dev->cookie_next_ms = dev->now_ms + (uint64_t)WFAS3_COOKIE_LIFE_MS;
        }
    }
    wfas3_wipe(fresh, sizeof fresh);
    return st;
}

/* cookie = MAC16(S, address || port || request_id || counter). */
static wfas3_status wfas3_cookie_make(uint8_t out[WFAS3_TAG_LEN], const uint8_t secret[WFAS3_KEY_LEN],
                                      const wfas3_addr *a, const uint8_t rid[16], uint64_t ectr)
{
    uint8_t in[42];

    memcpy(in, a->ip, 16U);
    wfas3_wr_be16(&in[16], a->port);
    memcpy(&in[18], rid, 16U);
    wfas3_wr_be64(&in[34], ectr);
    return wfas3_mac(out, WFAS3_TAG_LEN, secret, in, sizeof in);
}

/* -- CookieRequest: initiator (specification 7.4) ---------------------------- */

static wfas3_status wfas3_creq_build(wfas3_device *dev, wfas3_attempt *a, wfas3_out *out)
{
    wfas3_pair *p = &dev->pairs[a->pair];
    uint8_t *d = out->dgram;
    uint8_t k[WFAS3_KEY_LEN];
    uint8_t mask[8];
    uint32_t i;
    wfas3_status st = wfas3_index_new(dev, &a->index);

    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, a->request_id, sizeof a->request_id);
    }
    if (st == WFAS3_OK) {
        p->ectr_used = p->ectr_seen;                     /* rule C2 */
        p->seen_dirty = 1U;
        a->ectr = wfas3_ectr_next(dev, p, out);
        d[0] = WFAS3_MARKER;
        d[1] = WFAS3_T_COOKIE_REQUEST;
        d[2] = 0U;
        d[3] = 0U;
        wfas3_wr_be32(&d[4], a->index);
        memcpy(&d[8], a->request_id, 16U);
        st = wfas3_ctr_mask(mask, p, p->is_low, WFAS3_T_COOKIE_REQUEST, a->request_id);
    }
    if (st == WFAS3_OK) {
        wfas3_wr_be64(&d[24], a->ectr);
        for (i = 0U; i < 8U; i++) {
            d[24U + i] = (uint8_t)(d[24U + i] ^ mask[i]);
        }
        st = wfas3_dir_key(k, p->k_fix, WFAS3_LBL("pair tag "), p->is_low);
    }
    if (st == WFAS3_OK) {
        st = wfas3_mac(&d[32], WFAS3_TAG_LEN, k, d, 32U);
    }
    if (st == WFAS3_OK) {
        memcpy(a->pair_tag, &d[32], WFAS3_TAG_LEN);
        st = wfas3_mac1_out(&d[48], p, d, 48U);
    }
    if (st == WFAS3_OK) {
        out->len = WFAS3_LEN_COOKIE;
        out->to = a->addr;
        out->pair = a->pair;
        a->state = WFAS3_ATT_REQ;
        a->last_tx_ms = dev->now_ms;
    }
    wfas3_wipe(k, sizeof k);
    wfas3_wipe(mask, sizeof mask);
    return st;
}

/* -- CookieRequest: responder (specification 7.4, 7.5) ----------------------- */

/* Build a CookieReply (stale = 0) or a stale notice (stale = 1). */
static wfas3_status wfas3_crep_build(wfas3_device *dev, uint32_t pair, const uint8_t *req,
                                     const wfas3_addr *from, uint32_t stale, uint64_t ectr,
                                     wfas3_out *out)
{
    const wfas3_pair *p = &dev->pairs[pair];
    uint8_t *r = out->dgram;
    uint8_t kc[WFAS3_KEY_LEN];
    uint8_t key[WFAS3_KEY_LEN];
    uint8_t ad[48];
    uint8_t pt[WFAS3_TAG_LEN];
    wfas3_status st = wfas3_cookie_rotate(dev);

    memset(pt, 0, sizeof pt);
    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, &r[8], 24U);
    }
    if (st == WFAS3_OK) {
        r[0] = WFAS3_MARKER;
        r[1] = WFAS3_T_COOKIE_REPLY;
        r[2] = 0U;
        r[3] = 0U;
        memcpy(&r[4], &req[4], 4U);
        st = wfas3_dir_key(kc, p->k_fix, WFAS3_LBL("cookie "), 1U ^ p->is_low);
    }
    if ((st == WFAS3_OK) && (stale == 1U)) {
        st = wfas3_kdf(key, sizeof key, kc, WFAS3_LBL("stale"), &r[8], 24U);
    } else if (st == WFAS3_OK) {
        st = wfas3_kdf(key, sizeof key, kc, WFAS3_LBL("cookie"), &r[8], 24U);
        if (st == WFAS3_OK) {
            st = wfas3_cookie_make(pt, dev->cookie_secret[0], from, &req[8], ectr);
        }
    } else {
        st = WFAS3_E_ENTROPY;
    }
    if (st == WFAS3_OK) {
        memcpy(ad, r, 32U);
        memcpy(&ad[32], &req[32], WFAS3_TAG_LEN);
        st = wfas3_seal(&r[32], 32U, key, 0U, ad, sizeof ad, pt, sizeof pt);
    }
    if (st == WFAS3_OK) {
        out->len = WFAS3_LEN_COOKIE;
        out->to = *from;
        out->pair = pair;
    }
    wfas3_wipe(kc, sizeof kc);
    wfas3_wipe(key, sizeof key);
    wfas3_wipe(pt, sizeof pt);
    return st;
}

/* A request with a counter above every one seen before. */
static wfas3_status wfas3_creq_fresh(wfas3_device *dev, uint32_t pair, uint64_t ectr,
                                     const uint8_t *d, const wfas3_addr *from, wfas3_out *out)
{
    wfas3_pair *p = &dev->pairs[pair];
    uint32_t ai = wfas3_att_find_pair(dev, pair);
    uint32_t answer = 1U;
    wfas3_status st = WFAS3_E_DROP;

    p->ectr_seen = ectr;
    p->seen_dirty = 1U;
    if (ai != WFAS3_NONE) {
        if (p->is_low == 1U) {
            answer = 0U;                                 /* rule C3: low side keeps its attempt */
        } else {
            wfas3_att_free(&dev->attempts[ai]);          /* rule C3: high side yields */
            out->events |= WFAS3_EV_ATTEMPT_YIELDED;
        }
    }
    if ((answer == 1U) && (dev->now_ms < p->next_cookie_ms)) {
        answer = 0U;                                     /* quota */
    }
    if (answer == 1U) {
        st = wfas3_crep_build(dev, pair, d, from, 0U, ectr, out);
    }
    if (st == WFAS3_OK) {
        p->next_cookie_ms = dev->now_ms + (uint64_t)WFAS3_COOKIE_GAP_MS;
        p->cookie_hold_ms = dev->now_ms + (uint64_t)WFAS3_COOKIE_HOLD_MS;
    } else {
        p->ectr_used = ectr;                             /* no cookie is considered issued */
        st = (out->events != 0U) ? WFAS3_OK : WFAS3_E_DROP;
        out->len = 0U;
        out->pair = pair;
    }
    return st;
}

/* A request with a counter strictly below the highest seen. */
static wfas3_status wfas3_creq_stale(wfas3_device *dev, uint32_t pair, const uint8_t *d,
                                     const wfas3_addr *from, wfas3_out *out)
{
    wfas3_pair *p = &dev->pairs[pair];
    wfas3_status st = WFAS3_E_DROP;

    if ((dev->now_ms >= p->next_stale_ms) &&
        (wfas3_rate_take(&dev->stale_win_ms, &dev->stale_count, dev->now_ms,
                         WFAS3_CAP_STALE) == 1U)) {
        st = wfas3_crep_build(dev, pair, d, from, 1U, 0U, out);
        if (st == WFAS3_OK) {
            p->next_stale_ms = dev->now_ms + (uint64_t)WFAS3_STALE_GAP_MS;
        } else {
            st = WFAS3_E_DROP;
        }
    }
    return st;
}

static wfas3_status wfas3_creq_handle(wfas3_device *dev, const uint8_t *d,
                                      const wfas3_addr *from, wfas3_out *out)
{
    uint64_t cand = wfas3_mac1_candidates(dev, d, 48U);
    uint32_t pair = WFAS3_NONE;
    uint8_t mask[8];
    uint8_t raw[8];
    uint64_t ectr;
    uint32_t i;
    wfas3_status st = WFAS3_E_DROP;

    if (cand != 0U) {
        pair = wfas3_tag_select(dev, cand, d, 32U, &d[32], 0U);
    }
    if ((pair != WFAS3_NONE) &&
        (wfas3_ctr_mask(mask, &dev->pairs[pair], 1U ^ dev->pairs[pair].is_low,
                        WFAS3_T_COOKIE_REQUEST, &d[8]) == WFAS3_OK)) {
        for (i = 0U; i < 8U; i++) {
            raw[i] = (uint8_t)(d[24U + i] ^ mask[i]);
        }
        ectr = wfas3_rd_be64(raw);
        if (ectr > dev->pairs[pair].ectr_seen) {
            st = wfas3_creq_fresh(dev, pair, ectr, d, from, out);
        } else if (ectr < dev->pairs[pair].ectr_seen) {
            st = wfas3_creq_stale(dev, pair, d, from, out);
        } else {
            st = WFAS3_E_DROP;                           /* duplicate: silent */
        }
    }
    return st;
}

/* -- Handshake: common parts (specification 8) ------------------------------- */

typedef struct wfas3_hs_keys {
    uint8_t i2r[WFAS3_KEY_LEN];
    uint8_t r2i[WFAS3_KEY_LEN];
    uint8_t next_psk[WFAS3_KEY_LEN];
    uint8_t rekey_psk[WFAS3_KEY_LEN];
    uint8_t fp[16];
} wfas3_hs_keys;

static wfas3_status wfas3_hs_start(wfas3_sym *s, const uint8_t init_static[32],
                                   const uint8_t resp_static[32])
{
    wfas3_status st =
        wfas3_sym_init(s, WFAS3_LBL("WFAS3_KKpsk0hfs_25519+MLKEM768_ChaChaPoly_SHA256"));

    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(s, init_static, 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(s, resp_static, 32U);
    }
    return st;
}

/* MixKey(DH(priv, pub)). An all-zero result fails. */
static wfas3_status wfas3_hs_dh(wfas3_sym *s, const uint8_t priv[32], const uint8_t pub[32])
{
    uint8_t dh[32];
    wfas3_status st = WFAS3_E_AUTH;

    if (wfas_tp_x25519(dh, priv, pub) == 1U) {
        st = wfas3_sym_mix_key(s, dh, sizeof dh);
    }
    wfas3_wipe(dh, sizeof dh);
    return st;
}

/* Outputs of a completed handshake (specification 6.3). */
static wfas3_status wfas3_hs_keys_derive(wfas3_hs_keys *k, const wfas3_sym *s)
{
    wfas3_status st = wfas3_kdf(k->i2r, WFAS3_KEY_LEN, s->ck, WFAS3_LBL("transport i2r"), NULL, 0U);

    if (st == WFAS3_OK) {
        st = wfas3_kdf(k->r2i, WFAS3_KEY_LEN, s->ck, WFAS3_LBL("transport r2i"), NULL, 0U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_kdf(k->fp, sizeof k->fp, s->ck, WFAS3_LBL("session fingerprint"), NULL, 0U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_kdf(k->next_psk, WFAS3_KEY_LEN, s->ck, WFAS3_LBL("pair psk next"), NULL, 0U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_kdf(k->rekey_psk, WFAS3_KEY_LEN, s->ck, WFAS3_LBL("rekey psk"), NULL, 0U);
    }
    return st;
}

static void wfas3_hs_keys_wipe(wfas3_hs_keys *k)
{
    wfas3_wipe(k->i2r, sizeof k->i2r);
    wfas3_wipe(k->r2i, sizeof k->r2i);
    wfas3_wipe(k->next_psk, sizeof k->next_psk);
    wfas3_wipe(k->rekey_psk, sizeof k->rekey_psk);
}

/* Classify a received report (specification 9.3). */
static uint32_t wfas3_rep_class(const wfas3_pair *p, const uint8_t fp[16], uint64_t seq)
{
    static const uint32_t slots[3] = { WFAS3_REP_BASE, WFAS3_REP_MINEC, WFAS3_REP_RESPP };
    uint32_t cls = WFAS3_CLS_UNKNOWN;
    uint32_t i;

    if (seq < p->rep_seq[WFAS3_REP_BASE]) {
        cls = WFAS3_CLS_STALE;
    } else {
        for (i = 0U; i < 3U; i++) {
            uint32_t sl = slots[i];

            if ((p->rep_has[sl] == 1U) && (p->rep_seq[sl] == seq) &&
                (wfas3_ct_eq(p->rep_fp[sl], fp, 16U) == 1U)) {
                cls = WFAS3_CLS_OK;
            }
        }
    }
    return cls;
}

static void wfas3_rep_set(wfas3_pair *p, uint32_t slot, const uint8_t fp[16], uint64_t seq)
{
    memcpy(p->rep_fp[slot], fp, 16U);
    p->rep_seq[slot] = seq;
    p->rep_has[slot] = 1U;
}

/* A new session; config is the agreed configuration seen from this device. */
static void wfas3_ses_fill(wfas3_device *dev, wfas3_session *s, uint32_t pair, uint32_t initiator,
                           const wfas3_hs_keys *k, uint64_t seq, const wfas3_addr *addr,
                           const uint8_t config[16])
{
    uint8_t main_cfg[16];
    uint8_t rnd[4];
    uint32_t mic = ((config[0] & WFAS3_CF_MICROPHONE) != 0U) ? 1U : 0U;
    uint64_t phase = 0U;

    memset(s, 0, sizeof *s);
    s->pair = pair;
    s->initiator = initiator;
    s->seq = seq;
    dev->pairs[pair].seen_addr = *addr;                  /* last seen address (12) */
    dev->pairs[pair].seen_valid = 1U;
    s->addr = *addr;
    memcpy(s->k_send, (initiator == 1U) ? k->i2r : k->r2i, WFAS3_KEY_LEN);
    memcpy(s->k_recv, (initiator == 1U) ? k->r2i : k->i2r, WFAS3_KEY_LEN);
    memcpy(s->rekey_psk, k->rekey_psk, WFAS3_KEY_LEN);
    memcpy(s->fp, k->fp, 16U);
    memcpy(s->config, config, 16U);
    s->source = config[0] & WFAS3_CF_SOURCE;
    s->flags = (uint8_t)(config[0] | WFAS3_CF_SOURCE);
    memcpy(main_cfg, config, 16U);
    main_cfg[0] = s->flags;
    main_cfg[14] = 0U;
    if (s->source == 1U) {
        s->tx.exists = 1U;
        s->tx.on = 1U;
        memcpy(s->tx.config, main_cfg, 16U);
        s->rx.exists = mic;
    } else {
        s->rx.exists = 1U;
        s->rx.on = 1U;
        memcpy(s->rx.config, main_cfg, 16U);
        s->tx.exists = mic;
    }
    s->rx.d_ms = WFAS3_D_START_MS;
    s->rx.sync_start_ms = dev->now_ms;
    s->rx.next_treq_ms = dev->now_ms;
    s->rx.last_late_ms = dev->now_ms;
    s->rx.last_dec_ms = dev->now_ms;
    s->created_ms = dev->now_ms;
    s->next_ctl_ms = dev->now_ms;
    s->last_rx_ms = dev->now_ms;
    if (wfas3_rng_bytes(&dev->rng, rnd, sizeof rnd) == WFAS3_OK) {
        phase = (uint64_t)(wfas3_rd_be32(rnd) % (WFAS3_REKEY_PHASE_MS + 1U));
    }
    s->next_rekey_ms = dev->now_ms + (uint64_t)WFAS3_REKEY_EVERY_MS + phase;
}

/* -- Init: initiator (specification 8.1) ------------------------------------- */

/* Header, pair tag, start of the transcript, psk and ephemeral key. */
static wfas3_status wfas3_init_head(wfas3_device *dev, wfas3_attempt *a, uint8_t *d)
{
    const wfas3_pair *p = &dev->pairs[a->pair];
    uint8_t kt[WFAS3_KEY_LEN];
    wfas3_status st = wfas3_dir_key(kt, p->k_fix, WFAS3_LBL("pair tag "), p->is_low);

    d[0] = WFAS3_MARKER;
    d[1] = WFAS3_T_INIT;
    d[2] = 0U;
    d[3] = 0U;
    wfas3_wr_be32(&d[4], a->index);
    memcpy(&d[8], a->request_id, 16U);
    if (st == WFAS3_OK) {
        st = wfas3_mac(&d[24], WFAS3_TAG_LEN, kt, d, 24U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_start(&a->sym, p->s_pub, p->peer_pub);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(&a->sym, d, 40U);
    }
    if (st == WFAS3_OK) {
        a->psk_slot = p->has_mine;                       /* rule I1 */
        st = wfas3_sym_mix_key_and_hash(&a->sym, p->psk[a->psk_slot]);
    }
    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, a->e_priv, sizeof a->e_priv);
    }
    if (st == WFAS3_OK) {
        wfas_tp_x25519_public(&d[40], a->e_priv);
        st = wfas3_sym_mix_hash(&a->sym, &d[40], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key(&a->sym, &d[40], 32U);
    }
    wfas3_wipe(kt, sizeof kt);
    return st;
}

/* e1, es, ss and the payload. */
static wfas3_status wfas3_init_body(wfas3_device *dev, wfas3_attempt *a, uint8_t *d)
{
    const wfas3_pair *p = &dev->pairs[a->pair];
    uint8_t pk[WFAS_TP_MLKEM_PK];
    uint8_t rnd[64];
    uint8_t payload[48];
    wfas3_status st = wfas3_rng_bytes(&dev->rng, rnd, sizeof rnd);

    if (st == WFAS3_OK) {
        wfas_tp_mlkem768_keygen(pk, a->kem_sk, rnd);
        st = wfas3_sym_encrypt(&a->sym, &d[72], 1200U, pk, sizeof pk);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&a->sym, a->e_priv, p->peer_pub);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&a->sym, p->s_priv, p->peer_pub);
    }
    if (st == WFAS3_OK) {
        memcpy(payload, p->rep_fp[WFAS3_REP_LAST], 16U);
        wfas3_wr_be64(&payload[16], p->rep_seq[WFAS3_REP_LAST]);
        wfas3_wr_be64(&payload[24], p->seq_hi);
        memcpy(&payload[32], a->config, 16U);
        st = wfas3_sym_encrypt(&a->sym, &d[1272], 64U, payload, sizeof payload);
    }
    wfas3_wipe(rnd, sizeof rnd);
    return st;
}

static wfas3_status wfas3_init_build(wfas3_device *dev, uint32_t ai,
                                     const uint8_t cookie[WFAS3_TAG_LEN], wfas3_out *out)
{
    wfas3_attempt *a = &dev->attempts[ai];
    uint8_t *d = out->dgram;
    wfas3_status st = WFAS3_E_ENTROPY;

    if (dev->entropy_fresh == 1U) {
        st = wfas3_init_head(dev, a, d);
        if (st == WFAS3_OK) {
            st = wfas3_init_body(dev, a, d);
        }
        if (st == WFAS3_OK) {
            st = wfas3_mac1_out(&d[1336], &dev->pairs[a->pair], d, 1336U);
        }
        if (st == WFAS3_OK) {
            st = wfas3_mac_k(&d[1352], WFAS3_TAG_LEN, cookie, WFAS3_TAG_LEN, d, 1352U);
        }
        if (st == WFAS3_OK) {
            dev->entropy_fresh = 0U;
            a->state = WFAS3_ATT_OUT;
            a->last_tx_ms = dev->now_ms;
            out->len = WFAS3_LEN_INIT;
            out->to = a->addr;
            out->pair = a->pair;
        } else {
            wfas3_att_scrub(a);
            st = WFAS3_E_DROP;
        }
    }
    return st;
}

/* -- CookieReply: initiator (specification 7.5) ------------------------------ */

static wfas3_status wfas3_crep_handle(wfas3_device *dev, const uint8_t *d, wfas3_out *out)
{
    uint32_t ai = wfas3_att_find_index(dev, wfas3_rd_be32(&d[4]), WFAS3_ATT_REQ);
    uint8_t kc[WFAS3_KEY_LEN];
    uint8_t key[WFAS3_KEY_LEN];
    uint8_t ad[48];
    uint8_t pt[WFAS3_TAG_LEN];
    wfas3_status st = WFAS3_E_DROP;

    if (ai != WFAS3_NONE) {
        wfas3_attempt *a = &dev->attempts[ai];
        const wfas3_pair *p = &dev->pairs[a->pair];

        memcpy(ad, d, 32U);
        memcpy(&ad[32], a->pair_tag, WFAS3_TAG_LEN);
        if (wfas3_dir_key(kc, p->k_fix, WFAS3_LBL("cookie "), p->is_low) != WFAS3_OK) {
            st = WFAS3_E_DROP;
        } else if ((wfas3_kdf(key, sizeof key, kc, WFAS3_LBL("cookie"), &d[8], 24U) == WFAS3_OK) &&
                   (wfas3_open(pt, sizeof pt, key, 0U, ad, sizeof ad, &d[32], 32U) == WFAS3_OK)) {
            st = wfas3_init_build(dev, ai, pt, out);
        } else if ((wfas3_kdf(key, sizeof key, kc, WFAS3_LBL("stale"), &d[8], 24U) == WFAS3_OK) &&
                   (wfas3_open(pt, sizeof pt, key, 0U, ad, sizeof ad, &d[32], 32U) == WFAS3_OK) &&
                   (wfas3_ct_eq(pt, wfas3_zero, sizeof pt) == 1U)) {
            wfas3_suspect(dev, a->pair, out);            /* stale notice for a waiting attempt */
            out->events |= WFAS3_EV_ATTEMPT_FAILED;
            wfas3_att_free(a);
            st = WFAS3_OK;
        } else {
            st = WFAS3_E_DROP;
        }
    }
    wfas3_wipe(kc, sizeof kc);
    wfas3_wipe(key, sizeof key);
    wfas3_wipe(pt, sizeof pt);
    return st;
}

/* -- Reject (specification 8.4) ---------------------------------------------- */

static wfas3_status wfas3_reject_key(uint8_t key[WFAS3_KEY_LEN], const wfas3_pair *p,
                                     uint32_t low_sender, const uint8_t rid[16])
{
    uint8_t kr[WFAS3_KEY_LEN];
    wfas3_status st = wfas3_dir_key(kr, p->k_fix, WFAS3_LBL("reject "), low_sender);

    if (st == WFAS3_OK) {
        st = wfas3_kdf(key, WFAS3_KEY_LEN, kr, WFAS3_LBL("reject"), rid, 16U);
    }
    wfas3_wipe(kr, sizeof kr);
    return st;
}

static wfas3_status wfas3_reject_build(const wfas3_device *dev, uint32_t pair, const uint8_t *d,
                                       const wfas3_addr *from, uint8_t reason, wfas3_out *out)
{
    const wfas3_pair *p = &dev->pairs[pair];
    uint8_t *r = out->dgram;
    uint8_t key[WFAS3_KEY_LEN];
    uint8_t pt[8];
    wfas3_status st = wfas3_reject_key(key, p, 1U ^ p->is_low, &d[8]);

    memset(pt, 0, sizeof pt);
    pt[0] = reason;
    r[0] = WFAS3_MARKER;
    r[1] = WFAS3_T_REJECT;
    r[2] = 0U;
    r[3] = 0U;
    memcpy(&r[4], &d[4], 4U);
    memcpy(&r[8], &d[8], 16U);
    if (st == WFAS3_OK) {
        st = wfas3_seal(&r[24], 24U, key, 0U, r, 24U, pt, sizeof pt);
    }
    if (st == WFAS3_OK) {
        out->len = WFAS3_LEN_REJECT;
        out->to = *from;
        out->pair = pair;
    }
    wfas3_wipe(key, sizeof key);
    return st;
}

static wfas3_status wfas3_reject_handle(wfas3_device *dev, const uint8_t *d, wfas3_out *out)
{
    uint32_t ai = wfas3_att_find_index(dev, wfas3_rd_be32(&d[4]), WFAS3_ATT_OUT);
    uint8_t key[WFAS3_KEY_LEN];
    uint8_t pt[8];
    wfas3_status st = WFAS3_E_DROP;

    if (ai != WFAS3_NONE) {
        wfas3_attempt *a = &dev->attempts[ai];
        const wfas3_pair *p = &dev->pairs[a->pair];

        if ((wfas3_ct_eq(&d[8], a->request_id, 16U) == 1U) &&
            (wfas3_reject_key(key, p, p->is_low, a->request_id) == WFAS3_OK) &&
            (wfas3_open(pt, sizeof pt, key, 0U, d, 24U, &d[24], 24U) == WFAS3_OK) &&
            (pt[0] >= 1U) && (pt[0] <= 3U) &&
            (wfas3_ct_eq(&pt[1], wfas3_zero, 7U) == 1U)) {
            wfas3_suspect(dev, a->pair, out);
            out->events |= WFAS3_EV_ATTEMPT_FAILED;
            wfas3_att_free(a);
            st = WFAS3_OK;
        }
    }
    wfas3_wipe(key, sizeof key);
    return st;
}

/* -- Init: responder (specification 8.1, 8.2) -------------------------------- */

typedef struct wfas3_resp_ctx {
    wfas3_sym s;
    uint8_t   pk[WFAS_TP_MLKEM_PK];
    uint8_t   rfp[16];          /* report of the initiator                    */
    uint8_t   cfg[16];          /* proposal, then the decision                */
    uint64_t  rseq;
    uint64_t  session_seq;
    uint32_t  slot;             /* psk slot that opened e1                    */
    uint32_t  status;
} wfas3_resp_ctx;

/* Step 4: mac2 with the cookie recomputed from the current and previous secret. */
static uint32_t wfas3_init_mac2_ok(const wfas3_device *dev, const wfas3_pair *p,
                                   const uint8_t *d, const wfas3_addr *from)
{
    uint8_t cookie[WFAS3_TAG_LEN];
    uint8_t m[WFAS3_TAG_LEN];
    uint32_t ok = 0U;
    uint32_t i;

    for (i = 0U; i < 2U; i++) {
        if ((dev->cookie_ready == 1U) &&
            (wfas3_cookie_make(cookie, dev->cookie_secret[i], from, &d[8], p->ectr_seen)
             == WFAS3_OK) &&
            (wfas3_mac_k(m, sizeof m, cookie, sizeof cookie, d, 1352U) == WFAS3_OK)) {
            ok |= wfas3_ct_eq(m, &d[1352], sizeof m);
        }
    }
    wfas3_wipe(cookie, sizeof cookie);
    return ok;
}

/* Step 7: find the psk that opens e1. Returns the slot or WFAS3_NONE. */
static uint32_t wfas3_init_psk_trial(const wfas3_pair *p, const uint8_t *d, wfas3_resp_ctx *c)
{
    wfas3_sym base;
    uint32_t found = WFAS3_NONE;
    uint32_t slot;
    wfas3_status st = wfas3_hs_start(&base, p->peer_pub, p->s_pub);

    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(&base, d, 40U);
    }
    for (slot = 0U; slot < 3U; slot++) {
        uint32_t present = (slot == 0U) ? 1U : ((slot == 1U) ? p->has_mine : p->has_theirs);

        if ((st == WFAS3_OK) && (present == 1U) && (found == WFAS3_NONE)) {
            c->s = base;
            if ((wfas3_sym_mix_key_and_hash(&c->s, p->psk[slot]) == WFAS3_OK) &&
                (wfas3_sym_mix_hash(&c->s, &d[40], 32U) == WFAS3_OK) &&
                (wfas3_sym_mix_key(&c->s, &d[40], 32U) == WFAS3_OK) &&
                (wfas3_sym_decrypt(&c->s, c->pk, sizeof c->pk, &d[72], 1200U) == WFAS3_OK)) {
                found = slot;
            }
        }
    }
    wfas3_sym_wipe(&base);
    return found;
}

/* Step 8: key check, es, ss, payload. Fills the report and the proposal. */
static wfas3_status wfas3_init_tokens(const wfas3_pair *p, const uint8_t *d, wfas3_resp_ctx *c,
                                      uint64_t *peer_seq_hi)
{
    uint8_t payload[48];
    wfas3_status st = WFAS3_E_AUTH;

    if (wfas_tp_mlkem768_check_pk(c->pk) == 1U) {
        st = wfas3_hs_dh(&c->s, p->s_priv, &d[40]);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&c->s, p->s_priv, p->peer_pub);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_decrypt(&c->s, payload, sizeof payload, &d[1272], 64U);
    }
    if (st == WFAS3_OK) {
        memcpy(c->rfp, payload, 16U);
        c->rseq = wfas3_rd_be64(&payload[16]);
        *peer_seq_hi = wfas3_rd_be64(&payload[24]);
        memcpy(c->cfg, &payload[32], 16U);
    }
    return st;
}

/* 1 if a new unconfirmed session of this pair can be stored: there is a free
 * slot, or the pair holds an unconfirmed session that it would replace. An
 * established session of the pair is kept until the new one is confirmed. */
static uint32_t wfas3_ses_available(const wfas3_device *dev, const wfas3_pair *p)
{
    uint32_t ok = (wfas3_ses_alloc(dev) != WFAS3_NONE) ? 1U : 0U;
    uint32_t i;

    for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
        if ((dev->sessions[i].state == WFAS3_SES_UNCONFIRMED) &&
            (&dev->pairs[dev->sessions[i].pair] == p)) {
            ok = 1U;
        }
    }
    return ok;
}

/* The answer of this device to a proposal (specification 8.3). */
static uint32_t wfas3_grp_fits(const wfas3_device *dev, const uint8_t cfg[16]);
static uint32_t wfas3_grp_sessions(const wfas3_device *dev);

static uint32_t wfas3_decide(const wfas3_device *dev, const wfas3_pair *p, const uint8_t cfg[16])
{
    uint32_t status;

    uint32_t group_src = (((cfg[0] & WFAS3_CF_MULTICAST) != 0U) && ((cfg[0] & WFAS3_CF_SOURCE) == 0U))
                         ? 1U : 0U;                      /* this device would be the group source */

    if ((wfas3_config_valid(cfg, 0U) == 0U) || ((group_src == 1U) && (wfas3_grp_fits(dev, cfg) == 0U))) {
        status = WFAS3_ST_UNSUPPORTED;
    } else if ((dev->policy.busy == 1U) || (wfas3_ses_available(dev, p) == 0U) ||
               ((group_src == 1U) && (wfas3_grp_sessions(dev) >= WFAS3_GROUP_SLOTS))) {
        status = WFAS3_ST_BUSY;
    } else if (((cfg[0] & WFAS3_CF_SOURCE) == 0U) && ((p->perms & WFAS3_PERM_RECEIVE) == 0U)) {
        status = WFAS3_ST_NOT_PERMITTED;
    } else if (((cfg[0] & WFAS3_CF_MICROPHONE) != 0U) &&
               ((p->perms & WFAS3_PERM_MICROPHONE) == 0U)) {
        status = WFAS3_ST_NOT_PERMITTED;
    } else if ((((dev->policy.codec_mask >> cfg[1]) & 1U) == 0U) ||
               ((uint32_t)cfg[2] > dev->policy.max_channels)) {
        status = WFAS3_ST_UNSUPPORTED;
    } else {
        status = WFAS3_ST_ACCEPTED;
    }
    return status;
}

/* Tokens of the Resp: e, ee, ekem1, se. Writes bytes 0..1148 of r. */
static wfas3_status wfas3_resp_tokens(wfas3_device *dev, const wfas3_pair *p, const uint8_t *d,
                                      wfas3_resp_ctx *c, uint8_t *r)
{
    uint8_t rnd[64];            /* e_r private key (0..32), ML-KEM message m (32..64) */
    uint8_t ct[WFAS_TP_MLKEM_CT];
    uint8_t ss[32];
    uint32_t index = 0U;
    wfas3_status st = wfas3_index_new(dev, &index);

    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, rnd, sizeof rnd);
    }
    if (st == WFAS3_OK) {
        r[0] = WFAS3_MARKER;
        r[1] = WFAS3_T_RESP;
        r[2] = 0U;
        r[3] = 0U;
        wfas3_wr_be32(&r[4], index);
        memcpy(&r[8], &d[4], 4U);
        st = wfas3_sym_mix_hash(&c->s, r, 12U);
    }
    if (st == WFAS3_OK) {
        wfas_tp_x25519_public(&r[12], rnd);
        st = wfas3_sym_mix_hash(&c->s, &r[12], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key(&c->s, &r[12], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&c->s, rnd, &d[40]);
    }
    if (st == WFAS3_OK) {
        wfas_tp_mlkem768_encaps(ct, ss, c->pk, &rnd[32]);
        st = wfas3_sym_encrypt(&c->s, &r[44], 1104U, ct, sizeof ct);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key(&c->s, ss, sizeof ss);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&c->s, rnd, p->peer_pub);
    }
    wfas3_wipe(rnd, sizeof rnd);
    wfas3_wipe(ss, sizeof ss);
    return st;
}

/* Payload and pair_mac of the Resp. Writes bytes 1148..1228 of r. */
static wfas3_status wfas3_resp_tail(const wfas3_pair *p, const uint8_t *d, wfas3_resp_ctx *c,
                                    uint8_t *r)
{
    uint8_t payload[48];
    uint8_t buf[1228];
    uint8_t km[WFAS3_KEY_LEN];
    wfas3_status st;

    memcpy(payload, p->rep_fp[WFAS3_REP_LAST], 16U);
    wfas3_wr_be64(&payload[16], p->rep_seq[WFAS3_REP_LAST]);
    wfas3_wr_be64(&payload[24], c->session_seq);
    memcpy(&payload[32], c->cfg, 16U);
    st = wfas3_sym_encrypt(&c->s, &r[1148], 64U, payload, sizeof payload);
    if (st == WFAS3_OK) {
        st = wfas3_dir_key(km, p->k_fix, WFAS3_LBL("resp "), 1U ^ p->is_low);
    }
    if (st == WFAS3_OK) {
        memcpy(buf, &d[8], 16U);
        memcpy(&buf[16], r, 1212U);
        st = wfas3_mac(&r[1212], WFAS3_TAG_LEN, km, buf, sizeof buf);
    }
    wfas3_wipe(km, sizeof km);
    return st;
}

/* Step 10: rules R2, R3, the report rules, and the unconfirmed session. */
static void wfas3_resp_commit(wfas3_device *dev, uint32_t pair, const uint8_t *d,
                              const wfas3_addr *from, const wfas3_resp_ctx *c,
                              const wfas3_hs_keys *k, const uint8_t *r)
{
    wfas3_pair *p = &dev->pairs[pair];
    uint32_t si;

    if (c->slot != 0U) {                                 /* rule R2 */
        memcpy(p->psk[0], p->psk[c->slot], WFAS3_KEY_LEN);
        wfas3_wipe(p->psk[1], WFAS3_KEY_LEN);
        p->has_mine = 0U;
    }
    memcpy(p->psk[2], k->next_psk, WFAS3_KEY_LEN);       /* rule R3 */
    p->has_theirs = 1U;
    wfas3_rep_set(p, WFAS3_REP_BASE, c->rfp, c->rseq);
    if ((p->rep_has[WFAS3_REP_MINEC] == 1U) && (p->rep_seq[WFAS3_REP_MINEC] <= c->rseq)) {
        p->rep_has[WFAS3_REP_MINEC] = 0U;
    }
    p->seq_hi = c->session_seq;
    wfas3_rep_set(p, WFAS3_REP_RESPP, k->fp, c->session_seq);
    wfas3_ses_drop_pair(dev, pair, WFAS3_DROP_UNCONFIRMED, WFAS3_NONE);
    si = wfas3_ses_alloc(dev);
    if ((c->status == WFAS3_ST_ACCEPTED) && (si != WFAS3_NONE)) {
        wfas3_session *s = &dev->sessions[si];

        wfas3_ses_fill(dev, s, pair, 0U, k, c->session_seq, from, c->cfg);
        s->recv_index = wfas3_rd_be32(&r[4]);
        s->send_index = wfas3_rd_be32(&d[4]);
        s->state = WFAS3_SES_UNCONFIRMED;
    }
}

/* Steps 7 to 10 for an Init that passed the entry. */
static wfas3_status wfas3_init_run(wfas3_device *dev, uint32_t pair, const uint8_t *d,
                                   const wfas3_addr *from, wfas3_out *out)
{
    wfas3_pair *p = &dev->pairs[pair];
    wfas3_resp_ctx c;
    wfas3_hs_keys k;
    uint64_t peer_seq_hi = 0U;
    uint32_t cls;
    wfas3_status st = WFAS3_E_DROP;

    c.slot = wfas3_init_psk_trial(p, d, &c);
    if (c.slot == WFAS3_NONE) {
        st = wfas3_reject_build(dev, pair, d, from, 1U, out);
        wfas3_suspect(dev, pair, out);
    } else if (wfas3_init_tokens(p, d, &c, &peer_seq_hi) != WFAS3_OK) {
        st = WFAS3_E_DROP;
    } else {
        cls = wfas3_rep_class(p, c.rfp, c.rseq);
        if (cls == WFAS3_CLS_STALE) {
            st = wfas3_reject_build(dev, pair, d, from, 2U, out);
            wfas3_suspect(dev, pair, out);
        } else if (cls == WFAS3_CLS_UNKNOWN) {
            st = wfas3_reject_build(dev, pair, d, from, 3U, out);
            wfas3_suspect(dev, pair, out);
        } else {
            c.status = wfas3_decide(dev, p, c.cfg);
            c.session_seq = ((peer_seq_hi > p->seq_hi) ? peer_seq_hi : p->seq_hi) + 1U;
            if ((c.status == WFAS3_ST_UNSUPPORTED) && (dev->grp.open == 1U) &&
                ((c.cfg[0] & (WFAS3_CF_MULTICAST | WFAS3_CF_SOURCE)) == WFAS3_CF_MULTICAST)) {
                memcpy(c.cfg, dev->grp.config, 16U);    /* the group, seen from the initiator */
                c.cfg[0] = (uint8_t)(c.cfg[0] & (uint8_t)~WFAS3_CF_SOURCE);
            } else if (c.status == WFAS3_ST_UNSUPPORTED) {
                memcpy(c.cfg, dev->suggest, 16U);
            } else if (c.status == WFAS3_ST_ACCEPTED) {
                c.cfg[0] = (uint8_t)(c.cfg[0] ^ WFAS3_CF_SOURCE);
            } else {
                /* busy or not permitted: the proposal is echoed with the status */
            }
            c.cfg[14] = (uint8_t)c.status;
            st = wfas3_resp_tokens(dev, p, d, &c, out->dgram);
            if (st == WFAS3_OK) {
                st = wfas3_resp_tail(p, d, &c, out->dgram);
            }
            if (st == WFAS3_OK) {
                st = wfas3_hs_keys_derive(&k, &c.s);
            }
            if (st == WFAS3_OK) {
                wfas3_resp_commit(dev, pair, d, from, &c, &k, out->dgram);
                dev->entropy_fresh = 0U;
                out->len = WFAS3_LEN_RESP;
                out->to = *from;
                wfas3_store_pair(dev, pair, out);
            } else {
                st = WFAS3_E_DROP;
            }
            wfas3_hs_keys_wipe(&k);
        }
    }
    wfas3_sym_wipe(&c.s);
    return st;
}

/* Steps 1 to 6. */
static wfas3_status wfas3_init_handle(wfas3_device *dev, const uint8_t *d,
                                      const wfas3_addr *from, wfas3_out *out)
{
    uint64_t cand = wfas3_mac1_candidates(dev, d, 1336U);
    uint32_t pair = WFAS3_NONE;
    wfas3_status st = WFAS3_E_DROP;

    if (cand != 0U) {
        pair = wfas3_tag_select(dev, cand, d, 24U, &d[24], 0U);
    }
    if (pair != WFAS3_NONE) {
        wfas3_pair *p = &dev->pairs[pair];

        if ((p->ectr_used < p->ectr_seen) &&
            (wfas3_att_find_pair(dev, pair) == WFAS3_NONE) &&
            (wfas3_init_mac2_ok(dev, p, d, from) == 1U)) {
            if (dev->entropy_fresh == 0U) {
                st = WFAS3_E_ENTROPY;                    /* nothing changed yet */
            } else {
                p->ectr_used = p->ectr_seen;             /* step 5: cookie consumed */
                p->seen_dirty = 1U;
                if ((dev->now_ms >= p->next_hs_ms) &&
                    (wfas3_rate_take(&dev->hs_win_ms, &dev->hs_count, dev->now_ms,
                                     WFAS3_CAP_HS) == 1U)) {
                    p->next_hs_ms = dev->now_ms + (uint64_t)WFAS3_HS_GAP_MS;
                    st = wfas3_init_run(dev, pair, d, from, out);
                }
            }
        }
    }
    return st;
}

/* -- Resp: initiator (specification 8.2) ------------------------------------- */

static uint32_t wfas3_resp_mac_ok(const wfas3_pair *p, const wfas3_attempt *a, const uint8_t *d)
{
    uint8_t buf[1228];
    uint8_t km[WFAS3_KEY_LEN];
    uint8_t m[WFAS3_TAG_LEN];
    uint32_t ok = 0U;

    memcpy(buf, a->request_id, 16U);
    memcpy(&buf[16], d, 1212U);
    if ((wfas3_dir_key(km, p->k_fix, WFAS3_LBL("resp "), p->is_low) == WFAS3_OK) &&
        (wfas3_mac(m, sizeof m, km, buf, sizeof buf) == WFAS3_OK)) {
        ok = wfas3_ct_eq(m, &d[1212], sizeof m);
    }
    wfas3_wipe(km, sizeof km);
    return ok;
}

/* Run the responder tokens on a copy of the attempt's transcript. */
static wfas3_status wfas3_resp_open(const wfas3_attempt *a, const wfas3_pair *p,
                                    const uint8_t *d, wfas3_sym *s, uint8_t payload[48])
{
    uint8_t ct[WFAS_TP_MLKEM_CT];
    uint8_t ss[32];
    wfas3_status st = wfas3_sym_mix_hash(s, d, 12U);

    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(s, &d[12], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key(s, &d[12], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(s, a->e_priv, &d[12]);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_decrypt(s, ct, sizeof ct, &d[44], 1104U);
    }
    if (st == WFAS3_OK) {
        wfas_tp_mlkem768_decaps(ss, ct, a->kem_sk);      /* only after its tag verified */
        st = wfas3_sym_mix_key(s, ss, sizeof ss);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(s, p->s_priv, &d[12]);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_decrypt(s, payload, 48U, &d[1148], 64U);
    }
    wfas3_wipe(ss, sizeof ss);
    return st;
}

/* 1 if the decision is "accepted" and equals the proposal with bit0 inverted. */
static uint32_t wfas3_resp_accepts(const wfas3_attempt *a, const uint8_t cfg[16])
{
    uint8_t want[16];

    memcpy(want, a->config, 16U);
    want[0] = (uint8_t)(want[0] ^ WFAS3_CF_SOURCE);
    return wfas3_ct_eq(want, cfg, 16U);
}

/* Rule I2, report rules, session. The Resp is authentic and its report ok. */
static void wfas3_resp_commit_i(wfas3_device *dev, uint32_t ai, const uint8_t *d,
                                const uint8_t payload[48], const wfas3_hs_keys *k,
                                wfas3_out *out)
{
    wfas3_attempt *a = &dev->attempts[ai];
    wfas3_pair *p = &dev->pairs[a->pair];
    uint64_t sseq = wfas3_rd_be64(&payload[24]);
    uint32_t accepted = ((payload[46] == WFAS3_ST_ACCEPTED) &&
                         (wfas3_resp_accepts(a, &payload[32]) == 1U)) ? 1U : 0U;
    uint8_t sugg[16];
    uint32_t si = WFAS3_NONE;

    if (a->psk_slot != 0U) {                             /* rule I2 */
        memcpy(p->psk[0], p->psk[1], WFAS3_KEY_LEN);
        wfas3_wipe(p->psk[2], WFAS3_KEY_LEN);
        p->has_theirs = 0U;
    }
    memcpy(p->psk[1], k->next_psk, WFAS3_KEY_LEN);
    p->has_mine = 1U;
    wfas3_rep_set(p, WFAS3_REP_BASE, payload, wfas3_rd_be64(&payload[16]));
    wfas3_rep_set(p, WFAS3_REP_MINEC, k->fp, sseq);
    wfas3_rep_set(p, WFAS3_REP_LAST, k->fp, sseq);
    p->rep_has[WFAS3_REP_RESPP] = 0U;
    p->seq_hi = (sseq > p->seq_hi) ? sseq : p->seq_hi;
    wfas3_store_pair(dev, a->pair, out);
    if (accepted == 1U) {
        wfas3_ses_drop_pair(dev, a->pair, WFAS3_DROP_OPEN, WFAS3_NONE);   /* replaced by the new one */
        si = wfas3_ses_alloc(dev);
    }
    if (si != WFAS3_NONE) {
        wfas3_session *s = &dev->sessions[si];

        wfas3_ses_fill(dev, s, a->pair, 1U, k, sseq, &a->addr, a->config);
        s->recv_index = a->index;
        s->send_index = wfas3_rd_be32(&d[4]);
        s->state = WFAS3_SES_UP;
        out->session = si;
        out->events |= WFAS3_EV_SESSION_UP;
        wfas3_config_decode(&out->config, a->config);
    } else {
        out->events |= WFAS3_EV_ATTEMPT_FAILED;
        memcpy(sugg, &payload[32], sizeof sugg);
        sugg[14] = 0U;
        if ((payload[46] == WFAS3_ST_BUSY) || (payload[46] == WFAS3_ST_NOT_PERMITTED)) {
            out->events |= WFAS3_EV_REFUSED;
            out->status = payload[46];
        } else if ((payload[46] == WFAS3_ST_UNSUPPORTED) && (wfas3_config_valid(sugg, 0U) == 1U)) {
            out->events |= WFAS3_EV_REFUSED;
            out->status = payload[46];
            sugg[0] = (uint8_t)(sugg[0] ^ WFAS3_CF_SOURCE);   /* to this device's point of view */
            wfas3_config_decode(&out->config, sugg);
        } else {
            /* inconsistent answer: the attempt is aborted (specification 8.3) */
        }
    }
    wfas3_att_free(a);
}

static wfas3_status wfas3_resp_handle(wfas3_device *dev, const uint8_t *d, wfas3_out *out)
{
    uint32_t ai = wfas3_att_find_index(dev, wfas3_rd_be32(&d[8]), WFAS3_ATT_OUT);
    wfas3_sym s;
    wfas3_hs_keys k;
    uint8_t payload[48];
    wfas3_status st = WFAS3_E_DROP;

    if (ai != WFAS3_NONE) {
        wfas3_attempt *a = &dev->attempts[ai];
        const wfas3_pair *p = &dev->pairs[a->pair];

        s = a->sym;
        if ((wfas3_resp_mac_ok(p, a, d) == 1U) &&
            (wfas3_resp_open(a, p, d, &s, payload) == WFAS3_OK) &&
            (wfas3_hs_keys_derive(&k, &s) == WFAS3_OK)) {
            st = WFAS3_OK;
            if (wfas3_rep_class(p, payload, wfas3_rd_be64(&payload[16])) != WFAS3_CLS_OK) {
                wfas3_suspect(dev, a->pair, out);
                out->events |= WFAS3_EV_ATTEMPT_FAILED;
                wfas3_att_free(a);
            } else if (wfas3_rd_be64(&payload[24]) <= p->seq_hi) {
                out->pair = a->pair;                     /* inconsistent sequence: abort */
                out->events |= WFAS3_EV_ATTEMPT_FAILED;
                wfas3_att_free(a);
            } else {
                wfas3_resp_commit_i(dev, ai, d, payload, &k, out);
            }
            wfas3_hs_keys_wipe(&k);
        }
        wfas3_sym_wipe(&s);
    }
    return st;
}

/* -- Transport: small helpers ------------------------------------------------ */

static uint32_t wfas3_addr_eq(const wfas3_addr *a, const wfas3_addr *b)
{
    return ((a->port == b->port) && (wfas3_ct_eq(a->ip, b->ip, sizeof a->ip) == 1U)) ? 1U : 0U;
}

static uint32_t wfas3_cfg_payload(const uint8_t c[16])
{
    return wfas3_rd_be16(&c[10]);
}

static uint32_t wfas3_cfg_frames(const uint8_t c[16])
{
    return wfas3_rd_be16(&c[8]);
}

static uint32_t wfas3_all_zero(const uint8_t *p, size_t len)
{
    uint32_t acc = 0U;
    size_t i;

    for (i = 0U; (i < len) && (i < (size_t)WFAS3_MAX_DATAGRAM); i++) {
        acc |= (uint32_t)p[i];
    }
    return (acc == 0U) ? 1U : 0U;
}

static uint32_t wfas3_sat_add32(uint32_t a, uint64_t b)
{
    uint64_t s = (uint64_t)a + b;

    return (s > 0xFFFFFFFFULL) ? 0xFFFFFFFFU : (uint32_t)s;
}

/* Audio packets lost so far in the current epoch of the received stream. */
static uint64_t wfas3_rx_lost_now(const wfas3_rx *rx)
{
    uint64_t frames = wfas3_cfg_frames(rx->config);
    uint64_t lost = 0U;

    if ((rx->ep_delivered > 0U) && (frames > 0U)) {
        uint64_t expected = ((rx->high_pos - rx->first_pos) / frames) + 1U;

        lost = (expected > (uint64_t)rx->ep_delivered) ? (expected - (uint64_t)rx->ep_delivered) : 0U;
    }
    return lost;
}

/* 1 when the send counter leaves no room for one more packet and the BYE
 * packets of the closing (10.1, 10.4). */
static uint32_t wfas3_ctr_spent(const wfas3_session *s)
{
    return ((s->send_ctr + (uint64_t)WFAS3_BYE_COUNT + 1U) >= WFAS3_CTR_LIMIT) ? 1U : 0U;
}

/* Audio packet length of the stream this device receives, 0 if none flows. */
static size_t wfas3_rx_audio_len(const wfas3_session *s)
{
    size_t len = 0U;

    if ((s->rx.exists == 1U) && (s->rx.on == 1U)) {
        len = (size_t)WFAS3_AUDIO_OVERHEAD + (size_t)wfas3_cfg_payload(s->rx.config);
    }
    return len;
}

/* -- Transport: session end -------------------------------------------------- */

/* The session ends at once, without BYE. */
static void wfas3_ses_down(wfas3_device *dev, uint32_t si, uint32_t status, wfas3_out *out)
{
    out->events |= WFAS3_EV_SESSION_DOWN;
    out->session = si;
    out->pair = dev->sessions[si].pair;
    out->status = status;
    wfas3_ses_free(&dev->sessions[si]);
}

static void wfas3_ctl_build(wfas3_device *dev, uint32_t si, wfas3_out *out);
static wfas3_status wfas3_pair_drop(wfas3_device *dev, uint32_t pair, wfas3_out *out);

/* The session closes: BYE now and in the next two control packets (10.4). */
static void wfas3_ses_close(wfas3_device *dev, uint32_t si, uint32_t reason, uint32_t forget,
                            uint32_t status, wfas3_out *out)
{
    wfas3_session *s = &dev->sessions[si];

    out->events |= WFAS3_EV_SESSION_DOWN;
    out->session = si;
    out->pair = s->pair;
    out->status = status;
    if (s->state == WFAS3_SES_UP) {
        wfas3_wipe(s->k_recv, sizeof s->k_recv);         /* nothing is accepted any more */
        wfas3_wipe(s->k_recv_old, sizeof s->k_recv_old);
        wfas3_wipe(s->rekey_psk, sizeof s->rekey_psk);
        s->old_valid = 0U;
        wfas3_sym_wipe(&s->rk.sym);
        wfas3_wipe(s->rk.e_priv, sizeof s->rk.e_priv);
        wfas3_wipe(s->rk.kem_seed, sizeof s->rk.kem_seed);
        wfas3_wipe(s->rk.k_recv_new, sizeof s->rk.k_recv_new);
        wfas3_wipe(s->rk.k_send_new, sizeof s->rk.k_send_new);
        wfas3_wipe(s->rk.psk_new, sizeof s->rk.psk_new);
        s->rk.state = WFAS3_RK_IDLE;
        s->state = WFAS3_SES_CLOSING;
        s->bye_reason = reason;
        s->forget = forget;
        s->bye_left = WFAS3_BYE_COUNT;
        wfas3_ctl_build(dev, si, out);
    } else {
        wfas3_ses_free(s);
    }
}

/* -- Transport: building control packets (10.3) ------------------------------ */

static uint32_t wfas3_fr_put(uint8_t *pt, size_t *pos, uint8_t type, const uint8_t *c, size_t clen)
{
    uint32_t ok = 0U;

    if ((*pos + 3U + clen) <= (size_t)WFAS3_CTL_FRAMES) {
        pt[*pos] = type;
        wfas3_wr_be16(&pt[*pos + 1U], (uint16_t)clen);
        if (clen > 0U) {
            memcpy(&pt[*pos + 3U], c, clen);
        }
        *pos += 3U + clen;
        ok = 1U;
    }
    return ok;
}

/* START, STOP and REPORT for the stream received (10.4). */
static void wfas3_ctl_receiver(const wfas3_device *dev, const wfas3_session *s, uint8_t *pt,
                               size_t *pos)
{
    const wfas3_rx *rx = &s->rx;
    uint32_t sync = ((s->flags & WFAS3_CF_SYNC) != 0U) ? 1U : 0U;
    uint8_t c[4];

    memset(c, 0, sizeof c);
    if ((rx->exists == 1U) && (rx->on == 1U) && (rx->want == 1U) &&
        ((sync == 0U) || (rx->time_valid == 1U)) &&
        ((rx->got_audio == 0U) || ((dev->now_ms - rx->ep_audio_ms) > (uint64_t)WFAS3_CONSENT_MS))) {
        wfas3_wr_be32(c, rx->epoch);
        (void)wfas3_fr_put(pt, pos, WFAS3_F_START, c, 4U);
    } else if ((rx->exists == 1U) && (rx->want == 0U) && (rx->heard == 1U) &&
               ((dev->now_ms - rx->audio_ms) <= (uint64_t)WFAS3_CONSENT_MS)) {
        (void)wfas3_fr_put(pt, pos, WFAS3_F_STOP, c, 0U);
    } else {
        /* nothing to say about consent */
    }
}

static void wfas3_ctl_report(const wfas3_session *s, uint8_t *pt, size_t *pos)
{
    const wfas3_rx *rx = &s->rx;
    uint8_t c[21];
    uint32_t buffer;

    if ((rx->exists == 1U) && (rx->on == 1U) && (rx->want == 1U)) {
        wfas3_wr_be64(c, (rx->got_audio == 1U) ? rx->last_capture_us : 0U);
        wfas3_wr_be32(&c[8], rx->received);
        wfas3_wr_be32(&c[12], wfas3_sat_add32(rx->lost_done, wfas3_rx_lost_now(rx)));
        wfas3_wr_be32(&c[16], rx->jitter_us);
        buffer = ((s->flags & WFAS3_CF_SYNC) != 0U) ? rx->d_ms : rx->buffer_ms;
        c[20] = (uint8_t)((buffer > 255U) ? 255U : buffer);
        (void)wfas3_fr_put(pt, pos, WFAS3_F_REPORT, c, sizeof c);
    }
}

/* The chunk of the message being sent that comes next, or WFAS3_NONE. */
static uint32_t wfas3_rk_pick(wfas3_rekey *rk, uint32_t chunks)
{
    uint32_t found = WFAS3_NONE;
    uint32_t k;

    for (k = 0U; k < chunks; k++) {
        uint32_t i = (rk->next_chunk + k) % chunks;

        if ((found == WFAS3_NONE) && (((rk->bits_ack >> i) & 1U) == 0U)) {
            found = i;
        }
    }
    if (found != WFAS3_NONE) {
        rk->next_chunk = (found + 1U) % chunks;
    }
    return found;
}

static void wfas3_ctl_rekey(wfas3_session *s, uint8_t *pt, size_t *pos)
{
    wfas3_rekey *rk = &s->rk;
    uint8_t c[66];
    uint32_t chunk;

    if (((s->initiator == 1U) && (rk->state == WFAS3_RK_I_RECV)) ||
        ((s->initiator == 0U) && ((rk->state == WFAS3_RK_R_RECV) || (rk->state == WFAS3_RK_R_COMPUTE)))) {
        c[0] = (uint8_t)rk->id;
        wfas3_wr_be32(&c[1], rk->bits_rx);
        (void)wfas3_fr_put(pt, pos, WFAS3_F_REKEY_ACK, c, 5U);
    }
    if (((s->initiator == 1U) && (rk->state == WFAS3_RK_I_SEND)) ||
        ((s->initiator == 0U) && (rk->state == WFAS3_RK_R_SEND))) {
        if ((*pos + 69U) <= (size_t)WFAS3_CTL_FRAMES) {
            chunk = wfas3_rk_pick(rk, (s->initiator == 1U) ? WFAS3_RK_INIT_CHUNKS : WFAS3_RK_RESP_CHUNKS);
            if (chunk != WFAS3_NONE) {
                c[0] = (uint8_t)rk->id;
                c[1] = (uint8_t)chunk;
                memcpy(&c[2], &rk->buf[chunk * WFAS3_RK_CHUNK], WFAS3_RK_CHUNK);
                (void)wfas3_fr_put(pt, pos, (s->initiator == 1U) ? WFAS3_F_REKEY_INIT : WFAS3_F_REKEY_RESP,
                                   c, sizeof c);
            }
        }
    }
}

/* TIME_RESP and TIME_REQ (10.6). */
static void wfas3_ctl_time(const wfas3_device *dev, wfas3_session *s, uint8_t *pt, size_t *pos)
{
    wfas3_rx *rx = &s->rx;
    uint8_t c[24];
    uint32_t fast;

    if (s->tx.tresp == 1U) {
        wfas3_wr_be64(c, s->tx.tresp_t1);
        wfas3_wr_be64(&c[8], s->tx.tresp_t2);
        wfas3_wr_be64(&c[16], dev->now_us);
        if (wfas3_fr_put(pt, pos, WFAS3_F_TIME_RESP, c, 24U) == 1U) {
            s->tx.tresp = 0U;
        }
    }
    if (((s->flags & WFAS3_CF_SYNC) != 0U) && (rx->exists == 1U) && (rx->on == 1U) &&
        (dev->now_ms >= rx->next_treq_ms)) {
        wfas3_wr_be64(c, dev->now_us);
        if (wfas3_fr_put(pt, pos, WFAS3_F_TIME_REQ, c, 8U) == 1U) {
            rx->treq_t1[rx->treq_next & 3U] = dev->now_us;
            rx->treq_valid |= (1U << (rx->treq_next & 3U));
            rx->treq_next += 1U;
            fast = ((dev->now_ms - rx->sync_start_ms) < (uint64_t)WFAS3_SYNC_FAST_MS) ? 1U : 0U;
            rx->next_treq_ms = dev->now_ms + ((fast == 1U) ? WFAS3_TREQ_FAST_MS : WFAS3_TREQ_SLOW_MS);
        }
    }
}

/* The 128 bytes of frames of the next control packet. */
static void wfas3_ctl_group(wfas3_device *dev, uint32_t si, uint8_t *pt, size_t *pos, uint32_t phase);

static void wfas3_ctl_frames(wfas3_device *dev, uint32_t si, uint8_t pt[WFAS3_CTL_FRAMES])
{
    wfas3_session *s = &dev->sessions[si];
    size_t pos = 0U;
    uint8_t c[32];

    memset(c, 0, sizeof c);
    memset(pt, 0, WFAS3_CTL_FRAMES);
    if (s->state == WFAS3_SES_CLOSING) {
        if (s->forget == 1U) {
            (void)wfas3_fr_put(pt, &pos, WFAS3_F_FORGET, c, 0U);
        }
        c[0] = (uint8_t)s->bye_reason;
        (void)wfas3_fr_put(pt, &pos, WFAS3_F_BYE, c, 1U);
    } else {
        if ((s->path_resp == 1U) &&
            (wfas3_fr_put(pt, &pos, WFAS3_F_PATH_RESPONSE, s->path_resp_data, 8U) == 1U)) {
            s->path_resp = 0U;
        }
        wfas3_ctl_receiver(dev, s, pt, &pos);
        if (s->tx.announce == 1U) {
            if (s->source == 1U) {
                wfas3_wr_be32(c, s->tx.epoch);
                memcpy(&c[4], s->tx.config, 16U);
                (void)wfas3_fr_put(pt, &pos, WFAS3_F_PARAMS, c, 20U);
            } else {
                c[0] = (uint8_t)s->tx.on;
                wfas3_wr_be32(&c[1], s->tx.epoch);
                memcpy(&c[5], s->tx.config, 16U);
                (void)wfas3_fr_put(pt, &pos, WFAS3_F_MIC_STATE, c, 21U);
            }
        }
        if (s->rx.ack == 1U) {
            wfas3_wr_be32(c, s->rx.ack_epoch);
            if (wfas3_fr_put(pt, &pos, WFAS3_F_PARAMS_ACK, c, 4U) == 1U) {
                s->rx.ack = 0U;
            }
        }
        wfas3_ctl_group(dev, si, pt, &pos, 0U);
        wfas3_ctl_report(s, pt, &pos);
        wfas3_ctl_group(dev, si, pt, &pos, 1U);
        wfas3_ctl_rekey(s, pt, &pos);
        wfas3_ctl_time(dev, s, pt, &pos);
        if ((s->pair < WFAS3_MAX_PAIRS) && (dev->pairs[s->pair].used == 1U) &&
            (dev->pairs[s->pair].held_current == 0U) &&
            ((dev->pairs[s->pair].flags & WFAS3_PF_GUEST) == 0U)) {   /* a guest keeps its key */
            (void)wfas3_fr_put(pt, &pos, WFAS3_F_ENTRY_KEY, dev->entry_key, 32U);
        }
        if ((s->ekack == 1U) &&
            (wfas3_fr_put(pt, &pos, WFAS3_F_ENTRY_KEY_ACK, s->ekack_hash, 8U) == 1U)) {
            s->ekack = 0U;
        }
    }
}

/* Seal 128 bytes of frames into a control packet for address to. */
static void wfas3_ctl_seal(wfas3_session *s, const uint8_t pt[WFAS3_CTL_FRAMES],
                           const wfas3_addr *to, wfas3_out *out)
{
    uint8_t *d = out->dgram;

    d[0] = WFAS3_MARKER;
    d[1] = WFAS3_T_TRANSPORT;
    d[2] = 0U;
    d[3] = 0U;
    wfas3_wr_be32(&d[4], s->send_index);
    wfas3_wr_be64(&d[8], s->send_ctr);
    if (wfas3_seal(&d[16], (size_t)WFAS3_LEN_CONTROL - 16U, s->k_send, s->send_ctr, d, 16U,
                   pt, WFAS3_CTL_FRAMES) == WFAS3_OK) {
        s->send_ctr += 1U;
        out->len = WFAS3_LEN_CONTROL;
        out->to = *to;
    }
}

static void wfas3_ctl_build(wfas3_device *dev, uint32_t si, wfas3_out *out)
{
    wfas3_session *s = &dev->sessions[si];
    uint8_t pt[WFAS3_CTL_FRAMES];

    wfas3_ctl_frames(dev, si, pt);
    wfas3_ctl_seal(s, pt, &s->addr, out);
    out->session = si;
    out->pair = s->pair;
    s->next_ctl_ms = dev->now_ms + (uint64_t)WFAS3_CTL_MS;
    if (s->state == WFAS3_SES_CLOSING) {
        s->bye_left = (s->bye_left > 0U) ? (s->bye_left - 1U) : 0U;
        if (s->bye_left == 0U) {
            wfas3_ses_free(s);
        }
    }
    wfas3_wipe(pt, sizeof pt);
}

/* A path challenge packet to the candidate address (10.5). */
static void wfas3_path_build(wfas3_device *dev, uint32_t si, wfas3_out *out)
{
    wfas3_session *s = &dev->sessions[si];
    uint8_t pt[WFAS3_CTL_FRAMES];
    size_t pos = 0U;

    memset(pt, 0, sizeof pt);
    (void)wfas3_fr_put(pt, &pos, WFAS3_F_PATH_CHALLENGE, s->path_data, 8U);
    wfas3_ctl_seal(s, pt, &s->path_addr, out);
    out->session = si;
    out->pair = s->pair;
    s->path_tries += 1U;
    s->path_next_ms = dev->now_ms + (uint64_t)WFAS3_PATH_MS;
}

/* -- Transport: reading frames (10.2) ---------------------------------------- */

typedef struct wfas3_frames {
    uint32_t n;
    uint8_t  type[WFAS3_F_COUNT];
    size_t   off[WFAS3_F_COUNT];
    size_t   len[WFAS3_F_COUNT];
} wfas3_frames;

/* Split the plaintext into frames. 0 if it is not well formed. */
static uint32_t wfas3_fr_parse(const uint8_t *pt, size_t len, wfas3_frames *f)
{
    size_t pos = 0U;
    uint32_t seen = 0U;
    uint32_t ok = 1U;
    uint32_t done = 0U;
    uint32_t iter;

    f->n = 0U;
    for (iter = 0U; (iter <= WFAS3_F_COUNT) && (ok == 1U) && (done == 0U); iter++) {
        if (pos >= len) {
            done = 1U;
        } else if (pt[pos] == WFAS3_F_PADDING) {
            ok = wfas3_all_zero(&pt[pos], len - pos);
            done = 1U;
        } else if ((pt[pos] >= WFAS3_F_COUNT) || ((pos + 3U) > len)) {
            ok = 0U;
        } else {
            uint8_t t = pt[pos];
            size_t flen = wfas3_rd_be16(&pt[pos + 1U]);

            if (((pos + 3U + flen) > len) || (((seen >> t) & 1U) == 1U) || (f->n >= WFAS3_F_COUNT)) {
                ok = 0U;
            } else {
                f->type[f->n] = t;
                f->off[f->n] = pos + 3U;
                f->len[f->n] = flen;
                f->n += 1U;
                seen |= (1U << t);
                pos += 3U + flen;
            }
        }
    }
    return ok & done;
}

/* StreamConfig of PARAMS or MIC_STATE: valid, with the flags of the session. */
static uint32_t wfas3_fr_cfg_ok(const wfas3_session *s, const uint8_t cfg[16])
{
    return ((wfas3_config_valid(cfg, 0U) == 1U) && (cfg[0] == s->flags)) ? 1U : 0U;
}

static uint32_t wfas3_fr_rekey_ok(const wfas3_session *s, uint8_t t, const uint8_t *c)
{
    const wfas3_rekey *rk = &s->rk;
    uint32_t ok = 1U;
    uint32_t next_id = (rk->last_id + 1U) & 0xFFU;
    uint32_t id = c[0];
    uint32_t count;

    if (t == WFAS3_F_REKEY_INIT) {
        ok = ((s->initiator == 0U) && (c[1] < WFAS3_RK_INIT_CHUNKS)) ? 1U : 0U;
        if (rk->state == WFAS3_RK_IDLE) {
            ok &= ((id == next_id) || (id == rk->last_id)) ? 1U : 0U;
        } else {
            ok &= ((id == rk->id) || (id == rk->last_id)) ? 1U : 0U;
        }
    } else if (t == WFAS3_F_REKEY_RESP) {
        ok = ((s->initiator == 1U) && (c[1] < WFAS3_RK_RESP_CHUNKS)) ? 1U : 0U;
    } else {
        count = (s->initiator == 1U) ? WFAS3_RK_INIT_CHUNKS : WFAS3_RK_RESP_CHUNKS;
        ok = ((wfas3_rd_be32(&c[1]) & ~((1U << count) - 1U)) == 0U) ? 1U : 0U;
    }
    return ok;
}

/* Rules of 10.2 for one frame whose length is already right. */
static uint32_t wfas3_join_ok(const uint8_t *c);

static uint32_t wfas3_fr_role_ok(const wfas3_session *s, uint8_t t, const uint8_t *c)
{
    uint32_t sync = ((s->flags & WFAS3_CF_SYNC) != 0U) ? 1U : 0U;
    uint32_t mic = ((s->flags & WFAS3_CF_MICROPHONE) != 0U) ? 1U : 0U;
    uint32_t mc = ((s->flags & WFAS3_CF_MULTICAST) != 0U) ? 1U : 0U;
    uint32_t ok;

    switch (t) {
    case WFAS3_F_AUDIO:
        ok = (s->source == 0U) ? 1U : 0U;
        break;
    case WFAS3_F_MIC_AUDIO:
        ok = ((s->source == 1U) && (mic == 1U)) ? 1U : 0U;
        break;
    case WFAS3_F_START:
    case WFAS3_F_STOP:
    case WFAS3_F_REPORT:
    case WFAS3_F_PARAMS_ACK:
        ok = s->tx.exists;
        break;
    case WFAS3_F_TIME_REQ:
        ok = s->tx.exists & sync;
        break;
    case WFAS3_F_TIME_RESP:
        ok = s->rx.exists & sync;
        break;
    case WFAS3_F_PARAMS:
        /* a group has one configuration for its whole life (14) */
        ok = ((s->source == 0U) && (mc == 0U) && (wfas3_fr_cfg_ok(s, &c[4]) == 1U)) ? 1U : 0U;
        break;
    case WFAS3_F_GROUP_JOIN:
        ok = ((s->source == 0U) && (mc == 1U)) ? wfas3_join_ok(c) : 0U;
        break;
    case WFAS3_F_GROUP_ACK:
    case WFAS3_F_NACK:
        ok = ((s->source == 1U) && (mc == 1U)) ? 1U : 0U;
        break;
    case WFAS3_F_GROUP_LEAVE:
        ok = mc;
        break;
    case WFAS3_F_MIC_STATE:
        ok = ((s->source == 1U) && (mic == 1U) && (c[0] <= 1U)) ? 1U : 0U;
        if (ok == 1U) {
            ok = (c[0] == 1U) ? wfas3_fr_cfg_ok(s, &c[5]) : wfas3_all_zero(&c[5], 16U);
        }
        break;
    case WFAS3_F_REKEY_INIT:
    case WFAS3_F_REKEY_RESP:
    case WFAS3_F_REKEY_ACK:
        ok = wfas3_fr_rekey_ok(s, t, c);
        break;
    case WFAS3_F_BYE:
        ok = (c[0] <= WFAS3_BYE_REKEY_FAILED) ? 1U : 0U;
        break;
    case WFAS3_F_PATH_CHALLENGE:
    case WFAS3_F_PATH_RESPONSE:
    case WFAS3_F_ENTRY_KEY:
    case WFAS3_F_ENTRY_KEY_ACK:
    case WFAS3_F_FORGET:
        ok = 1U;
        break;
    default:
        ok = 0U;                                         /* excluded by wfas3_fr_parse() */
        break;
    }
    return ok;
}

/* All frames valid for this session and packet kind? (10.2) */
static uint32_t wfas3_fr_check(const wfas3_session *s, const uint8_t *pt, const wfas3_frames *f,
                               uint32_t kind)
{
    uint32_t ok = (f->n > 0U) ? 1U : (kind == WFAS3_KIND_CONTROL) ? 1U : 0U;
    uint32_t i;

    for (i = 0U; (i < f->n) && (i < WFAS3_F_COUNT); i++) {
        uint8_t t = f->type[i];
        size_t want = wfas3_flen[t];
        uint32_t is_audio = ((t == WFAS3_F_AUDIO) || (t == WFAS3_F_MIC_AUDIO)) ? 1U : 0U;

        if (is_audio == 1U) {
            want = (size_t)WFAS3_AUDIO_HEAD + (size_t)wfas3_cfg_payload(s->rx.config);
            ok &= ((kind == WFAS3_KIND_AUDIO) && (i == 0U) && (f->n == 1U)) ? 1U : 0U;
        } else {
            ok &= (kind == WFAS3_KIND_CONTROL) ? 1U : 0U;
        }
        if ((ok == 1U) && (f->len[i] == want)) {
            ok &= wfas3_fr_role_ok(s, t, &pt[f->off[i]]);
        } else {
            ok = 0U;
        }
    }
    return ok;
}

/* -- Transport: synchronisation (10.6) --------------------------------------- */

static void wfas3_d_late(wfas3_rx *rx, uint64_t late_us, uint64_t now_ms)
{
    uint64_t add = ((late_us + 999U) / 1000U) + (uint64_t)WFAS3_D_STEP_MS;
    uint64_t d = (uint64_t)rx->d_ms + add;

    rx->d_ms = (d > (uint64_t)WFAS3_D_MAX_MS) ? WFAS3_D_MAX_MS : (uint32_t)d;
    rx->last_late_ms = now_ms;
}

static void wfas3_sync_sample(const wfas3_device *dev, wfas3_rx *rx, const uint8_t *c)
{
    uint64_t t1 = wfas3_rd_be64(c);
    uint64_t t2 = wfas3_rd_be64(&c[8]);
    uint64_t t3 = wfas3_rd_be64(&c[16]);
    uint64_t t4 = dev->now_us;
    uint32_t match = WFAS3_NONE;
    uint32_t i;

    for (i = 0U; i < 4U; i++) {
        if ((((rx->treq_valid >> i) & 1U) == 1U) && (rx->treq_t1[i] == t1)) {
            match = i;
        }
    }
    if ((match != WFAS3_NONE) && (t1 < WFAS3_TIME_MAX) && (t2 < WFAS3_TIME_MAX) &&
        (t3 < WFAS3_TIME_MAX) && (t4 < WFAS3_TIME_MAX) && (t3 >= t2) && (t4 >= t1)) {
        int64_t rtt = (int64_t)(t4 - t1) - (int64_t)(t3 - t2);
        int64_t off = (((int64_t)t2 - (int64_t)t1) + ((int64_t)t3 - (int64_t)t4)) / 2;

        rx->treq_valid &= ~(1U << match);
        if ((rtt >= 0) && (rtt < WFAS3_RTT_MAX_US)) {
            if ((dev->now_ms - rx->sync_start_ms) < (uint64_t)WFAS3_SYNC_FAST_MS) {
                if ((rx->time_valid == 0U) || (rtt < rx->best_rtt_us)) {
                    rx->offset_us = off;
                    rx->best_rtt_us = rtt;
                }
            } else if (rx->time_valid == 0U) {
                rx->offset_us = off;
            } else {
                int64_t step = (int64_t)((t4 - rx->last_update_us) / 2000U);
                int64_t delta = off - rx->offset_us;

                delta = (delta > step) ? step : ((delta < -step) ? -step : delta);
                rx->offset_us += delta;
            }
            rx->time_valid = 1U;
            rx->last_update_us = t4;
        }
    }
}

/* -- Transport: applying frames ---------------------------------------------- */

static void wfas3_rx_epoch_end(wfas3_rx *rx)
{
    rx->lost_done = wfas3_sat_add32(rx->lost_done, wfas3_rx_lost_now(rx));
    rx->ep_delivered = 0U;
    rx->got_audio = 0U;
    rx->have_prev = 0U;
}

/* PARAMS (on = 1) or MIC_STATE for the stream received. */
static void wfas3_rx_params(const wfas3_device *dev, wfas3_session *s, uint32_t epoch,
                            const uint8_t cfg[16], uint32_t on, wfas3_out *out)
{
    wfas3_rx *rx = &s->rx;

    if (epoch > rx->epoch) {
        wfas3_rx_epoch_end(rx);
        rx->epoch = epoch;
        memcpy(rx->config, cfg, 16U);
        if ((on == 1U) && (rx->on == 0U) && (rx->time_valid == 0U)) {
            rx->sync_start_ms = dev->now_ms;
            rx->next_treq_ms = dev->now_ms;
        }
        rx->on = on;
        out->events |= WFAS3_EV_STREAM_PARAMS;
        out->stream_on = on;
        wfas3_config_decode(&out->config, cfg);
        out->config.flags = (uint8_t)(out->config.flags & (uint8_t)~WFAS3_CF_SOURCE);
    }
    if (epoch >= rx->epoch) {
        rx->ack = 1U;
        rx->ack_epoch = epoch;
    }
}

static void wfas3_tx_start(const wfas3_device *dev, wfas3_session *s, uint32_t epoch,
                           uint32_t advanced, wfas3_out *out)
{
    wfas3_tx *tx = &s->tx;

    if ((advanced == 1U) && (tx->on == 1U) && (epoch == tx->epoch)) {
        tx->announce = 0U;                           /* the receiver has the parameters */
        if (tx->started == 0U) {
            tx->started = 1U;
            tx->start_ms = dev->now_ms;
            tx->consent_ms = dev->now_ms + (uint64_t)WFAS3_CONSENT_MS;
            out->events |= WFAS3_EV_STREAM_START;
        }
    }
}

static void wfas3_tx_report(const wfas3_device *dev, wfas3_session *s, const uint8_t *c,
                            uint32_t advanced)
{
    wfas3_tx *tx = &s->tx;
    uint64_t lc = wfas3_rd_be64(c);

    tx->rep_received = wfas3_rd_be32(&c[8]);
    tx->rep_lost = wfas3_rd_be32(&c[12]);
    tx->rep_jitter_us = wfas3_rd_be32(&c[16]);
    tx->rep_buffer_ms = c[20];
    if ((advanced == 1U) && (tx->started == 1U)) {
        /* 0 is accepted during the first 2 s after START (10.4) */
        uint32_t fresh = (lc == 0U)
            ? (((dev->now_ms - tx->start_ms) <= (uint64_t)WFAS3_CONSENT_MS) ? 1U : 0U)
            : (((lc <= dev->now_us) && ((dev->now_us - lc) <= (uint64_t)WFAS3_CONSENT_US)) ? 1U : 0U);

        if (fresh == 1U) {
            tx->consent_ms = dev->now_ms + (uint64_t)WFAS3_CONSENT_MS;
        }
    }
}

static uint32_t wfas3_dedup(wfas3_mrx *x, uint32_t epoch, uint64_t pos);

/* AUDIO or MIC_AUDIO: deliver it to the application or discard it (10.4). */
static void wfas3_audio_in(const wfas3_device *dev, wfas3_session *s, const uint8_t *c,
                           size_t flen, wfas3_out *out)
{
    wfas3_rx *rx = &s->rx;
    uint32_t epoch = wfas3_rd_be32(c);
    uint64_t pos = wfas3_rd_be64(&c[4]);
    uint64_t cap = wfas3_rd_be64(&c[12]);
    uint64_t playout = 0U;
    uint32_t deliver = 1U;

    rx->audio_ms = dev->now_ms;
    rx->heard = 1U;
    if ((rx->want == 0U) || (rx->on == 0U) || (epoch != rx->epoch) || (cap >= WFAS3_TIME_MAX)) {
        deliver = 0U;
    } else if ((s->flags & WFAS3_CF_SYNC) != 0U) {
        if (rx->time_valid == 0U) {
            deliver = 0U;
        } else {
            int64_t po = ((int64_t)cap - rx->offset_us) + ((int64_t)rx->d_ms * 1000);

            if (po < 0) {
                deliver = 0U;
            } else if (dev->now_us > (uint64_t)po) {
                wfas3_d_late(rx, dev->now_us - (uint64_t)po, dev->now_ms);
                deliver = 0U;
            } else {
                playout = (uint64_t)po;
            }
        }
    } else {
        playout = 0U;
    }
    if ((deliver == 1U) && ((s->flags & WFAS3_CF_MULTICAST) != 0U) && (s->source == 0U) &&
        (wfas3_dedup(&s->mrx, epoch, pos) == 1U)) {
        deliver = 0U;                                    /* came by the group and the session */
    }
    if (deliver == 1U) {
        out->events |= WFAS3_EV_AUDIO;
        out->stream = (s->source == 1U) ? WFAS3_STREAM_MIC : WFAS3_STREAM_MAIN;
        out->epoch = epoch;
        out->sample_pos = pos;
        out->capture_us = cap;
        out->playout_us = playout;
        out->audio_len = flen - (size_t)WFAS3_AUDIO_HEAD;
        memcpy(out->audio, &c[WFAS3_AUDIO_HEAD], out->audio_len);
        if (rx->ep_delivered == 0U) {
            rx->first_pos = pos;
            rx->high_pos = pos;
        } else {
            rx->first_pos = (pos < rx->first_pos) ? pos : rx->first_pos;
            rx->high_pos = (pos > rx->high_pos) ? pos : rx->high_pos;
        }
        rx->ep_delivered += 1U;
        rx->received = wfas3_sat_add32(rx->received, 1U);
        rx->got_audio = 1U;
        rx->ep_audio_ms = dev->now_ms;
        rx->last_capture_us = cap;
        if (rx->have_prev == 1U) {
            int64_t dd = ((int64_t)dev->now_us - (int64_t)rx->prev_arr_us) -
                         ((int64_t)cap - (int64_t)rx->prev_cap_us);
            int64_t ad = (dd < 0) ? -dd : dd;
            int64_t j = (int64_t)rx->jitter_us;

            ad = (ad > 0x7FFFFFFF) ? 0x7FFFFFFF : ad;
            j += (ad - j) / 16;
            rx->jitter_us = (j < 0) ? 0U : (uint32_t)j;
        }
        rx->prev_arr_us = dev->now_us;
        rx->prev_cap_us = cap;
        rx->have_prev = 1U;
    }
}

/* -- Multicast (specification 14) -------------------------------------------- */

static wfas3_status wfas3_audio_seal(wfas3_device *dev, uint32_t si, uint32_t epoch, uint64_t sample_pos,
                                     uint64_t capture_us, const uint8_t *data, size_t pb, wfas3_out *out);

#define WFAS3_GROUP_ROTATE_MS  600000U  /* a new key epoch every 10 minutes     */
#define WFAS3_GROUP_OLD_MS     2000U    /* the previous epoch after a leave      */
#define WFAS3_GROUP_JOIN_MS    300U     /* GROUP_JOIN at most this often         */
#define WFAS3_GROUP_LEAVES     3U       /* control packets carrying GROUP_LEAVE  */
#define WFAS3_GROUP_STREAM_EP  0U       /* stream epoch of a group: never changes */
#define WFAS3_GNEED_NONE       0U
#define WFAS3_GNEED_LEAVE      1U
#define WFAS3_GNEED_REVOKE     2U

typedef char wfas3_assert_mcast[(WFAS3_MAX_MCAST_PAYLOAD + WFAS3_MCAST_OVERHEAD <= WFAS3_MAX_DATAGRAM)
                                ? 1 : -1];

/* An IPv6 multicast address (ff00::/8), or IPv4-mapped 224.0.0.0/4. */
static uint32_t wfas3_is_mcast(const wfas3_addr *a)
{
    uint32_t v4 = (wfas3_all_zero(a->ip, 10U) == 1U) && (a->ip[10] == 0xFFU) && (a->ip[11] == 0xFFU)
                  ? 1U : 0U;
    uint32_t ok = (v4 == 1U) ? (((a->ip[12] & 0xF0U) == 0xE0U) ? 1U : 0U)
                             : ((a->ip[0] == 0xFFU) ? 1U : 0U);

    return ok & ((a->port != 0U) ? 1U : 0U);
}

/* 1 if session si is the live session of member m. */
static uint32_t wfas3_member_live(const wfas3_device *dev, const wfas3_member *m)
{
    const wfas3_session *s = &dev->sessions[m->session % WFAS3_MAX_SESSIONS];

    return ((m->used == 1U) && (m->session < WFAS3_MAX_SESSIONS) && (s->state == WFAS3_SES_UP) &&
            (s->seq == m->seq) && (s->pair == m->pair)) ? 1U : 0U;
}

/* The member slot of session si, or WFAS3_NONE. */
static uint32_t wfas3_member_of(const wfas3_device *dev, uint32_t si)
{
    uint32_t found = WFAS3_NONE;
    uint32_t i;

    for (i = 0U; i < WFAS3_GROUP_SLOTS; i++) {
        if ((dev->grp.m[i].used == 1U) && (dev->grp.m[i].session == si) &&
            (wfas3_member_live(dev, &dev->grp.m[i]) == 1U)) {
            found = i;
        }
    }
    return found;
}

/* The epoch a member is told about next: the current one until it holds
 * it, then the one being given out. */
static uint32_t wfas3_member_target(const wfas3_group *g, const wfas3_member *m)
{
    uint32_t t = g->epoch;

    if ((g->pend == 1U) && (m->acked == 1U) && (m->acked_epoch == g->epoch)) {
        t = g->pend_epoch;
    }
    return t;
}

/* The keys of a new epoch: group key and a tag key for every slot, so that
 * free slots carry tags nobody can tell from random. */
static wfas3_status wfas3_grp_keys(wfas3_device *dev, uint8_t key[32],
                                   uint8_t tags[WFAS3_GROUP_SLOTS][32])
{
    wfas3_status st = wfas3_rng_bytes(&dev->rng, key, 32U);
    uint32_t i;

    for (i = 0U; (i < WFAS3_GROUP_SLOTS) && (st == WFAS3_OK); i += 2U) {
        st = wfas3_rng_bytes(&dev->rng, tags[i], 64U);
    }
    return st;
}

static void wfas3_grp_switch(wfas3_group *g, uint64_t now)
{
    g->epoch = g->pend_epoch;
    memcpy(g->key, g->pend_key, 32U);
    memcpy(g->tag_keys, g->pend_tag_keys, sizeof g->tag_keys);
    wfas3_wipe(g->pend_key, sizeof g->pend_key);
    wfas3_wipe((uint8_t *)g->pend_tag_keys, sizeof g->pend_tag_keys);
    g->pend = 0U;
    g->epoch_ms = now;
}

static uint32_t wfas3_grp_count(const wfas3_device *dev)
{
    uint32_t n = 0U;
    uint32_t i;

    for (i = 0U; i < WFAS3_GROUP_SLOTS; i++) {
        n += wfas3_member_live(dev, &dev->grp.m[i]);
    }
    return n;
}

/* A new epoch, as needed (14.2): after a revocation it is in use at once;
 * after a leave or every 10 minutes it is given out first, and the old key
 * stays in use until every member holds the new one, 2 s at most. */
static wfas3_status wfas3_grp_rotate(wfas3_device *dev, uint32_t need)
{
    wfas3_group *g = &dev->grp;
    wfas3_status st = WFAS3_OK;

    if (need == WFAS3_GNEED_REVOKE) {
        st = wfas3_grp_keys(dev, g->pend_key, g->pend_tag_keys);
        if (st == WFAS3_OK) {
            g->pend_epoch = ((g->pend == 1U) ? g->pend_epoch : g->epoch) + 1U;
            wfas3_grp_switch(g, dev->now_ms);            /* the old keys stop now */
        }
    } else if (need == WFAS3_GNEED_LEAVE) {
        if (g->pend == 1U) {
            wfas3_grp_switch(g, dev->now_ms);            /* one epoch given out at a time */
        }
        st = wfas3_grp_keys(dev, g->pend_key, g->pend_tag_keys);
        if (st == WFAS3_OK) {
            g->pend_epoch = g->epoch + 1U;
            g->pend = 1U;
            g->pend_ms = dev->now_ms;
        }
    } else {
        /* nothing needed */
    }
    if ((st == WFAS3_OK) && (g->pend == 1U) && (wfas3_grp_count(dev) == 0U)) {
        wfas3_grp_switch(g, dev->now_ms);                /* nobody to wait for */
    }
    return st;
}

/* The epoch given out is in use once every member holds it, or 2 s later. */
static void wfas3_grp_maybe_switch(wfas3_device *dev)
{
    wfas3_group *g = &dev->grp;
    uint32_t all = 1U;
    uint32_t i;

    if (g->pend == 1U) {
        for (i = 0U; i < WFAS3_GROUP_SLOTS; i++) {
            const wfas3_member *m = &g->m[i];

            if ((wfas3_member_live(dev, m) == 1U) &&
                ((m->acked == 0U) || (m->acked_epoch != g->pend_epoch))) {
                all = 0U;
            }
        }
        if ((all == 1U) || ((dev->now_ms - g->pend_ms) >= (uint64_t)WFAS3_GROUP_OLD_MS)) {
            wfas3_grp_switch(g, dev->now_ms);
        }
    }
}

/* Member k leaves: told so in its session if it is still up; the key
 * changes as after a leave, or at once after a revocation. */
static wfas3_status wfas3_member_drop(wfas3_device *dev, uint32_t k, uint32_t revoke)
{
    wfas3_member *m = &dev->grp.m[k];

    if (wfas3_member_live(dev, m) == 1U) {
        wfas3_mrx *x = &dev->sessions[m->session].mrx;

        x->leave_left = WFAS3_GROUP_LEAVES;
        x->leave_group = dev->grp.id;
    }
    memset(m, 0, sizeof *m);
    return wfas3_grp_rotate(dev, (revoke == 1U) ? WFAS3_GNEED_REVOKE : WFAS3_GNEED_LEAVE);
}

/* START of a group session: the receiver becomes a member. */
static void wfas3_member_add(wfas3_device *dev, uint32_t si)
{
    const wfas3_session *s = &dev->sessions[si];
    uint32_t k = WFAS3_NONE;
    uint32_t i;

    if ((dev->grp.open == 1U) && (wfas3_member_of(dev, si) == WFAS3_NONE)) {
        for (i = 0U; i < WFAS3_GROUP_SLOTS; i++) {
            if ((k == WFAS3_NONE) && (dev->grp.m[i].used == 0U)) {
                k = i;                                   /* a slot left by a member is freed, with a
                                                            new epoch, by the poll first */
            }
        }
        if (k != WFAS3_NONE) {
            wfas3_member *m = &dev->grp.m[k];

            memset(m, 0, sizeof *m);
            m->used = 1U;
            m->session = si;
            m->seq = s->seq;
            m->pair = s->pair;
            m->join_next_ms = dev->now_ms;
            dev->sessions[si].mrx.leave_left = 0U;
        }
    }
}

/* GROUP_JOIN content (14.2) for member k. */
static void wfas3_join_build(const wfas3_device *dev, uint32_t k, uint8_t c[104])
{
    const wfas3_group *g = &dev->grp;
    uint32_t t = wfas3_member_target(g, &g->m[k]);
    uint32_t pend = ((g->pend == 1U) && (t == g->pend_epoch)) ? 1U : 0U;

    memset(c, 0, 104U);
    wfas3_wr_be32(c, g->id);
    wfas3_wr_be32(&c[4], t);
    wfas3_wr_be64(&c[8], g->counter);
    c[16] = (uint8_t)k;
    memcpy(&c[20], g->addr.ip, 16U);
    wfas3_wr_be16(&c[36], g->addr.port);
    memcpy(&c[40], (pend == 1U) ? g->pend_key : g->key, 32U);
    memcpy(&c[72], (pend == 1U) ? g->pend_tag_keys[k] : g->tag_keys[k], 32U);
}

/* Frames of 14.2 in a control packet. phase 0: GROUP_JOIN, after
 * PARAMS_ACK. phase 1: GROUP_ACK, GROUP_LEAVE, NACK, after REPORT. */
static void wfas3_ctl_group(wfas3_device *dev, uint32_t si, uint8_t *pt, size_t *pos, uint32_t phase)
{
    wfas3_session *s = &dev->sessions[si];
    wfas3_mrx *x = &s->mrx;
    uint8_t c[104];
    uint32_t k;

    if ((s->flags & WFAS3_CF_MULTICAST) == 0U) {
        /* not a group session */
    } else if (phase == 0U) {
        k = wfas3_member_of(dev, si);
        if ((s->source == 1U) && (k != WFAS3_NONE) && (dev->grp.open == 1U)) {
            wfas3_member *m = &dev->grp.m[k];
            uint32_t t = wfas3_member_target(&dev->grp, m);

            if (((m->acked == 0U) || (m->acked_epoch != t)) && (dev->now_ms >= m->join_next_ms)) {
                wfas3_join_build(dev, k, c);
                if (wfas3_fr_put(pt, pos, WFAS3_F_GROUP_JOIN, c, 104U) == 1U) {
                    m->join_next_ms = dev->now_ms + (uint64_t)WFAS3_GROUP_JOIN_MS;
                }
            }
        }
    } else {
        if (x->ack_due == 1U) {
            wfas3_wr_be32(c, x->group);
            wfas3_wr_be32(&c[4], x->ack_epoch);
            if (wfas3_fr_put(pt, pos, WFAS3_F_GROUP_ACK, c, 8U) == 1U) {
                x->ack_due = 0U;
            }
        }
        if (x->leave_left > 0U) {
            wfas3_wr_be32(c, x->leave_group);
            if (wfas3_fr_put(pt, pos, WFAS3_F_GROUP_LEAVE, c, 4U) == 1U) {
                x->leave_left -= 1U;
            }
        }
        if (x->nack_due == 1U) {
            wfas3_wr_be64(c, x->nack_first);
            wfas3_wr_be32(&c[8], x->nack_bits);
            if (wfas3_fr_put(pt, pos, WFAS3_F_NACK, c, 12U) == 1U) {
                x->nack_due = 0U;
            }
        }
    }
    wfas3_wipe(c, sizeof c);
}

/* Content rules of GROUP_JOIN. */
static uint32_t wfas3_join_ok(const uint8_t *c)
{
    wfas3_addr a;
    uint32_t ok;

    memcpy(a.ip, &c[20], 16U);
    a.port = wfas3_rd_be16(&c[36]);
    ok = (c[16] < WFAS3_GROUP_SLOTS) ? 1U : 0U;
    ok &= wfas3_all_zero(&c[17], 3U);
    ok &= wfas3_all_zero(&c[38], 2U);
    ok &= wfas3_is_mcast(&a);
    ok &= (wfas3_rd_be64(&c[8]) < WFAS3_CTR_LIMIT) ? 1U : 0U;
    return ok;
}

/* Receiver: the group keys (14.2). A new group, or the next epoch; an older
 * epoch is a late copy and changes nothing. */
static void wfas3_join_in(wfas3_device *dev, uint32_t si, const uint8_t *c, wfas3_out *out)
{
    wfas3_session *s = &dev->sessions[si];
    wfas3_mrx *x = &s->mrx;
    wfas3_addr a;
    uint32_t group = wfas3_rd_be32(c);
    uint32_t epoch = wfas3_rd_be32(&c[4]);

    memcpy(a.ip, &c[20], 16U);
    a.port = wfas3_rd_be16(&c[36]);
    if ((x->joined == 0U) || (x->group != group) || (wfas3_addr_eq(&a, &x->addr) == 0U)) {
        wfas3_wipe((uint8_t *)x, sizeof *x);
        x->joined = 1U;
        x->group = group;
        x->addr = a;
        x->epoch = epoch;
        x->slot = c[16];
        x->floor = wfas3_rd_be64(&c[8]);
        memcpy(x->key, &c[40], 32U);
        memcpy(x->tag_key, &c[72], 32U);
        out->events |= WFAS3_EV_GROUP_JOIN;
        out->addr = a;
        x->ack_due = 1U;
        x->ack_epoch = epoch;
    } else if (epoch > x->epoch) {
        x->old_valid = 1U;
        x->old_epoch = x->epoch;
        x->old_until_ms = dev->now_ms + (uint64_t)WFAS3_GROUP_OLD_MS;
        memcpy(x->old_key, x->key, 32U);
        memcpy(x->old_tag_key, x->tag_key, 32U);
        x->epoch = epoch;
        x->slot = c[16];
        memcpy(x->key, &c[40], 32U);
        memcpy(x->tag_key, &c[72], 32U);
        x->ack_due = 1U;
        x->ack_epoch = epoch;
    } else if (epoch == x->epoch) {
        x->ack_due = 1U;                                 /* our GROUP_ACK was lost */
        x->ack_epoch = epoch;
    } else {
        /* a late copy */
    }
}

/* Receiver: the group keys are gone. */
static void wfas3_mrx_leave(wfas3_session *s, uint32_t si, wfas3_out *out)
{
    wfas3_mrx *x = &s->mrx;

    if (x->joined == 1U) {
        out->events |= WFAS3_EV_GROUP_LEAVE;
        out->addr = x->addr;
        out->session = si;
    }
    wfas3_wipe((uint8_t *)x, sizeof *x);
}

/* Source: GROUP_ACK from member of si. */
static void wfas3_gack_in(wfas3_device *dev, uint32_t si, const uint8_t *c)
{
    uint32_t k = wfas3_member_of(dev, si);
    uint32_t epoch = wfas3_rd_be32(&c[4]);

    if ((k != WFAS3_NONE) && (wfas3_rd_be32(c) == dev->grp.id) &&
        ((epoch == dev->grp.epoch) || ((dev->grp.pend == 1U) && (epoch == dev->grp.pend_epoch)))) {
        dev->grp.m[k].acked = 1U;
        dev->grp.m[k].acked_epoch = epoch;
        wfas3_grp_maybe_switch(dev);
    }
}

/* GROUP_LEAVE: from a receiver, it leaves; from the source, we must. */
static void wfas3_gleave_in(wfas3_device *dev, uint32_t si, const uint8_t *c, wfas3_out *out)
{
    wfas3_session *s = &dev->sessions[si];
    uint32_t k;

    if (s->source == 1U) {
        k = wfas3_member_of(dev, si);
        if ((k != WFAS3_NONE) && (wfas3_rd_be32(c) == dev->grp.id)) {
            (void)wfas3_member_drop(dev, k, 0U);
            s->mrx.leave_left = 0U;                      /* it knows: no GROUP_LEAVE back */
        }
    } else if ((s->mrx.joined == 1U) && (wfas3_rd_be32(c) == s->mrx.group)) {
        wfas3_mrx_leave(s, si, out);
    } else {
        /* not our group */
    }
}

/* Source: NACK from member of si; the packets are resent by wfas3_poll(). */
static void wfas3_nack_in(wfas3_device *dev, uint32_t si, const uint8_t *c)
{
    uint32_t k = wfas3_member_of(dev, si);

    if ((k != WFAS3_NONE) && (dev->grp.m[k].acked == 1U)) {
        dev->grp.m[k].resend = (wfas3_rd_be32(&c[8]) != 0U) ? 1U : 0U;
        dev->grp.m[k].resend_first = wfas3_rd_be64(c);
        dev->grp.m[k].resend_bits = wfas3_rd_be32(&c[8]);
    }
}

/* Receiver: 1 if (epoch, pos) was delivered already on a group session;
 * otherwise it is remembered (14.3). */
static uint32_t wfas3_dedup(wfas3_mrx *x, uint32_t epoch, uint64_t pos)
{
    uint32_t seen = 0U;
    uint32_t i;

    for (i = 0U; (i < x->dedup_n) && (i < WFAS3_MCAST_DEDUP); i++) {
        seen |= ((x->dedup_epoch[i] == epoch) && (x->dedup_pos[i] == pos)) ? 1U : 0U;
    }
    if (seen == 0U) {
        x->dedup_epoch[x->dedup_next] = epoch;
        x->dedup_pos[x->dedup_next] = pos;
        x->dedup_next = (x->dedup_next + 1U) % WFAS3_MCAST_DEDUP;
        x->dedup_n = (x->dedup_n < WFAS3_MCAST_DEDUP) ? (x->dedup_n + 1U) : WFAS3_MCAST_DEDUP;
    }
    return seen;
}

/* Receiver: the counters from first up to ctr were missed; they become a
 * NACK in the next control packet. first is the counter after the highest
 * accepted, or the counter of the join before any was accepted. */
static void wfas3_nack_note(wfas3_mrx *x, uint64_t first, uint64_t ctr)
{
    uint64_t n;
    uint32_t bits;

    if (ctr > first) {
        n = ctr - first;
        n = (n > 32U) ? 32U : n;
        bits = (n == 32U) ? 0xFFFFFFFFU : ((1U << (uint32_t)n) - 1U);
        if ((x->nack_due == 1U) && (first >= x->nack_first) && ((first - x->nack_first) < 32U) &&
            ((first - x->nack_first) + n <= 32U)) {
            x->nack_bits |= bits << (uint32_t)(first - x->nack_first);
        } else {
            x->nack_due = 1U;
            x->nack_first = first;
            x->nack_bits = bits;
        }
    }
}

/* A multicast packet (14.1). Every group session with this group id is
 * tried: ids are not unique, the tag decides. */
static wfas3_status wfas3_mcast_handle(wfas3_device *dev, const uint8_t *d, size_t len,
                                       wfas3_out *out)
{
    uint8_t pt[WFAS3_MAX_DATAGRAM];
    uint8_t tag[8];
    uint32_t group = wfas3_rd_be32(&d[4]);
    uint32_t epoch = wfas3_rd_be32(&d[8]);
    uint64_t ctr = wfas3_rd_be64(&d[12]);
    size_t body = len - 128U;                            /* bytes before the tags */
    uint32_t done = 0U;
    uint32_t i;
    wfas3_status st = WFAS3_E_DROP;

    for (i = 0U; (i < WFAS3_MAX_SESSIONS) && (done == 0U); i++) {
        wfas3_session *s = &dev->sessions[i];
        wfas3_mrx *x = &s->mrx;
        size_t pb = wfas3_cfg_payload(s->rx.config);
        const uint8_t *key = x->key;
        const uint8_t *tk = x->tag_key;
        uint32_t ok = ((s->state == WFAS3_SES_UP) && (s->source == 0U) &&
                       ((s->flags & WFAS3_CF_MULTICAST) != 0U) && (x->joined == 1U) &&
                       (x->group == group) && (len == (pb + (size_t)WFAS3_MCAST_OVERHEAD))) ? 1U : 0U;

        if ((ok == 1U) && (epoch != x->epoch)) {
            ok = ((x->old_valid == 1U) && (epoch == x->old_epoch) && (dev->now_ms < x->old_until_ms)) ? 1U : 0U;
            key = x->old_key;
            tk = x->old_tag_key;
        }
        ok &= ((ctr >= x->floor) && (wfas3_win_check(&x->win, ctr) == 1U)) ? 1U : 0U;
        if ((ok == 1U) && (wfas3_mac(tag, sizeof tag, tk, d, body) == WFAS3_OK) &&
            (wfas3_ct_eq(tag, &d[body + (8U * (size_t)x->slot)], sizeof tag) == 1U) &&
            (wfas3_open_e(pt, sizeof pt, key, epoch, ctr, d, 20U, &d[20], body - 20U) == WFAS3_OK)) {
            uint64_t first = (x->win.any == 1U) ? (x->win.max + 1U) : x->floor;

            done = 1U;
            st = WFAS3_OK;
            (void)wfas3_win_mark(&x->win, ctr);
            if ((pt[0] == WFAS3_F_AUDIO) &&
                ((size_t)wfas3_rd_be16(&pt[1]) == ((size_t)WFAS3_AUDIO_HEAD + pb))) {
                wfas3_nack_note(x, first, ctr);
                out->session = i;
                out->pair = s->pair;
                wfas3_audio_in(dev, s, &pt[3], (size_t)WFAS3_AUDIO_HEAD + pb, out);
            }
        }
    }
    wfas3_wipe(pt, sizeof pt);
    return st;
}

/* -- Multicast: the source ---------------------------------------------------- */

wfas3_status wfas3_group_open(wfas3_device *dev, const wfas3_config *config,
                              const wfas3_addr *group_addr)
{
    wfas3_status st = WFAS3_E_ARG;
    uint8_t cfg[16];
    uint8_t id[4];

    if ((dev != NULL) && (config != NULL) && (group_addr != NULL) && (dev->started == 1U)) {
        wfas3_config_encode(cfg, config, 0U);
        if ((wfas3_config_valid(cfg, 0U) == 0U) ||
            ((cfg[0] & (WFAS3_CF_SOURCE | WFAS3_CF_MULTICAST)) != (WFAS3_CF_SOURCE | WFAS3_CF_MULTICAST)) ||
            (wfas3_is_mcast(group_addr) == 0U)) {
            st = WFAS3_E_ARG;
        } else if (dev->grp.open == 1U) {
            st = WFAS3_E_STATE;
        } else if (wfas_tp_drbg_left(&dev->rng.drbg) < WFAS3_RNG_MARGIN) {
            st = WFAS3_E_ENTROPY;
        } else {
            wfas3_wipe((uint8_t *)&dev->grp, sizeof dev->grp);
            st = wfas3_rng_bytes(&dev->rng, id, sizeof id);
            if (st == WFAS3_OK) {
                st = wfas3_grp_keys(dev, dev->grp.key, dev->grp.tag_keys);
            }
            if (st == WFAS3_OK) {
                dev->grp.id = wfas3_rd_be32(id);
                memcpy(dev->grp.config, cfg, 16U);
                dev->grp.addr = *group_addr;
                dev->grp.epoch = 1U;
                dev->grp.epoch_ms = dev->now_ms;
                dev->grp.open = 1U;
            } else {
                wfas3_wipe((uint8_t *)&dev->grp, sizeof dev->grp);
            }
        }
    }
    return st;
}

wfas3_status wfas3_group_close(wfas3_device *dev)
{
    wfas3_status st = WFAS3_E_ARG;
    uint32_t i;

    if ((dev != NULL) && (dev->started == 1U)) {
        st = WFAS3_E_STATE;
        if (dev->grp.open == 1U) {
            for (i = 0U; i < WFAS3_GROUP_SLOTS; i++) {
                const wfas3_member *m = &dev->grp.m[i];

                if (wfas3_member_live(dev, m) == 1U) {
                    dev->sessions[m->session].mrx.leave_left = WFAS3_GROUP_LEAVES;
                    dev->sessions[m->session].mrx.leave_group = dev->grp.id;
                }
            }
            wfas3_wipe((uint8_t *)&dev->grp, sizeof dev->grp);
            dev->grp.open = 0U;
            st = WFAS3_OK;
        }
    }
    return st;
}

uint32_t wfas3_group_members(const wfas3_device *dev)
{
    return ((dev != NULL) && (dev->grp.open == 1U)) ? wfas3_grp_count(dev) : 0U;
}

/* 1 if member k may receive audio: it holds a group key and has consent in
 * its session. */
static uint32_t wfas3_member_ready(const wfas3_device *dev, uint32_t k)
{
    const wfas3_member *m = &dev->grp.m[k];
    uint32_t ok = wfas3_member_live(dev, m) & m->acked;

    if (ok == 1U) {
        const wfas3_session *s = &dev->sessions[m->session];

        ok = ((s->tx.started == 1U) && (dev->now_ms <= s->tx.consent_ms)) ? 1U : 0U;
    }
    return ok;
}

wfas3_status wfas3_group_send(wfas3_device *dev, uint64_t sample_pos, uint64_t capture_us,
                              const uint8_t *data, size_t len, wfas3_out *out)
{
    wfas3_status st = WFAS3_E_ARG;
    uint8_t pt[3U + WFAS3_AUDIO_HEAD + WFAS3_MAX_MCAST_PAYLOAD];
    uint32_t ready = 0U;
    uint32_t i;

    if ((dev != NULL) && (data != NULL) && (out != NULL) && (dev->started == 1U)) {
        wfas3_group *g = &dev->grp;
        size_t pb = wfas3_cfg_payload(g->config);
        uint8_t *d = out->dgram;
        size_t body = (size_t)20U + 3U + (size_t)WFAS3_AUDIO_HEAD + pb + (size_t)WFAS3_TAG_LEN;

        wfas3_out_clear(out);
        for (i = 0U; (i < WFAS3_GROUP_SLOTS) && (g->open == 1U); i++) {
            ready |= wfas3_member_ready(dev, i);
        }
        if ((g->open == 0U) || (ready == 0U) || (g->counter >= WFAS3_CTR_LIMIT)) {
            st = WFAS3_E_STATE;
        } else if ((len != pb) || (capture_us >= WFAS3_TIME_MAX)) {
            st = WFAS3_E_ARG;
        } else {
            wfas3_grp_maybe_switch(dev);
            pt[0] = WFAS3_F_AUDIO;
            wfas3_wr_be16(&pt[1], (uint16_t)(WFAS3_AUDIO_HEAD + pb));
            wfas3_wr_be32(&pt[3], WFAS3_GROUP_STREAM_EP);
            wfas3_wr_be64(&pt[7], sample_pos);
            wfas3_wr_be64(&pt[15], capture_us);
            memcpy(&pt[23], data, pb);
            d[0] = WFAS3_MARKER;
            d[1] = WFAS3_T_MULTICAST;
            d[2] = 0U;
            d[3] = 0U;
            wfas3_wr_be32(&d[4], g->id);
            wfas3_wr_be32(&d[8], g->epoch);
            wfas3_wr_be64(&d[12], g->counter);
            st = wfas3_seal_e(&d[20], sizeof out->dgram - 20U, g->key, g->epoch, g->counter, d, 20U,
                              pt, 23U + pb);
            for (i = 0U; (i < WFAS3_GROUP_SLOTS) && (st == WFAS3_OK); i++) {
                st = wfas3_mac(&d[body + (8U * (size_t)i)], 8U, g->tag_keys[i], d, body);
            }
            if (st == WFAS3_OK) {
                wfas3_hist *h = &g->h[g->h_next];

                h->valid = 1U;
                h->counter = g->counter;
                h->sample_pos = sample_pos;
                h->capture_us = capture_us;
                memcpy(h->data, data, pb);
                g->h_next = (g->h_next + 1U) % WFAS3_MCAST_HISTORY;
                g->counter += 1U;
                out->len = pb + (size_t)WFAS3_MCAST_OVERHEAD;
                out->to = g->addr;
            } else {
                wfas3_out_clear(out);
            }
        }
    }
    wfas3_wipe(pt, sizeof pt);
    return st;
}

/* One packet asked for with NACK, resent in the member's session while it
 * can still be played (14.3). 1 when out holds it. */
static uint32_t wfas3_grp_resend(wfas3_device *dev, wfas3_out *out)
{
    wfas3_group *g = &dev->grp;
    uint64_t late_us = (uint64_t)wfas3_rd_be16(&g->config[12]) * 1000U;
    size_t pb = wfas3_cfg_payload(g->config);
    uint32_t did = 0U;
    uint32_t k;
    uint32_t b;
    uint32_t j;

    for (k = 0U; (k < WFAS3_GROUP_SLOTS) && (did == 0U); k++) {
        wfas3_member *m = &g->m[k];

        for (b = 0U; (b < 32U) && (m->resend == 1U) && (did == 0U); b++) {
            if (((m->resend_bits >> b) & 1U) == 1U) {
                uint64_t want = m->resend_first + (uint64_t)b;

                m->resend_bits &= ~(1U << b);
                for (j = 0U; (j < WFAS3_MCAST_HISTORY) && (did == 0U); j++) {
                    const wfas3_hist *h = &g->h[j];

                    if ((h->valid == 1U) && (h->counter == want) &&
                        ((h->capture_us + late_us) > dev->now_us) &&
                        (wfas3_member_ready(dev, k) == 1U) &&
                        (wfas3_audio_seal(dev, m->session, WFAS3_GROUP_STREAM_EP, h->sample_pos,
                                          h->capture_us, h->data, pb, out) == WFAS3_OK)) {
                        did = 1U;
                    }
                }
            }
        }
        if (m->resend_bits == 0U) {
            m->resend = 0U;
        }
    }
    return did;
}

/* Timers and membership of the group. 1 when out holds a result. */
static uint32_t wfas3_grp_poll(wfas3_device *dev, wfas3_out *out, wfas3_status *st)
{
    wfas3_group *g = &dev->grp;
    uint32_t did = 0U;
    uint32_t k;

    if (g->open == 1U) {
        for (k = 0U; k < WFAS3_GROUP_SLOTS; k++) {
            const wfas3_member *m = &g->m[k];

            if ((m->used == 1U) && (wfas3_member_live(dev, m) == 0U)) {
                /* Its session ended: a removed pair is revoked, the rest leave. */
                uint32_t revoke = ((m->pair >= WFAS3_MAX_PAIRS) || (dev->pairs[m->pair].used == 0U)) ? 1U : 0U;

                *st = wfas3_member_drop(dev, k, revoke);
            } else if ((m->used == 1U) && (dev->sessions[m->session].tx.started == 0U)) {
                *st = wfas3_member_drop(dev, k, 0U);      /* no consent: it loses its slot */
            } else {
                /* still a member, or a free slot */
            }
        }
        if ((g->pend == 0U) && ((dev->now_ms - g->epoch_ms) >= (uint64_t)WFAS3_GROUP_ROTATE_MS)) {
            *st = wfas3_grp_rotate(dev, WFAS3_GNEED_LEAVE);
        }
        wfas3_grp_maybe_switch(dev);
        did = wfas3_grp_resend(dev, out);
    }
    return did;
}

static uint64_t wfas3_grp_deadline(const wfas3_device *dev)
{
    const wfas3_group *g = &dev->grp;
    uint64_t t = UINT64_MAX;
    uint32_t k;

    if (g->open == 1U) {
        t = (g->pend == 1U) ? (g->pend_ms + (uint64_t)WFAS3_GROUP_OLD_MS)
                            : (g->epoch_ms + (uint64_t)WFAS3_GROUP_ROTATE_MS);
        for (k = 0U; k < WFAS3_GROUP_SLOTS; k++) {
            const wfas3_member *m = &g->m[k];

            /* what wfas3_grp_poll() acts on at once */
            if ((m->resend == 1U) ||
                ((m->used == 1U) && ((wfas3_member_live(dev, m) == 0U) ||
                                     (dev->sessions[m->session].tx.started == 0U)))) {
                t = wfas3_min64(t, dev->now_ms);
            }
        }
    }
    return t;
}

/* 1 if a proposal with bit2 matches the open group (bit0 aside). */
static uint32_t wfas3_grp_fits(const wfas3_device *dev, const uint8_t cfg[16])
{
    uint32_t ok = (dev->grp.open == 1U) ? 1U : 0U;

    ok &= (((uint32_t)cfg[0] | WFAS3_CF_SOURCE) == (uint32_t)dev->grp.config[0]) ? 1U : 0U;
    ok &= wfas3_ct_eq(&cfg[1], &dev->grp.config[1], 13U);
    return ok;
}

/* Sessions of this device as source of its group. */
static uint32_t wfas3_grp_sessions(const wfas3_device *dev)
{
    uint32_t n = 0U;
    uint32_t i;

    for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
        const wfas3_session *s = &dev->sessions[i];

        n += ((s->state != WFAS3_SES_FREE) && (s->source == 1U) &&
              ((s->flags & WFAS3_CF_MULTICAST) != 0U)) ? 1U : 0U;
    }
    return n;
}

/* ENTRY_KEY_ACK: the peer now holds the current entry key (9.4). */
static void wfas3_entry_ack_in(wfas3_device *dev, const wfas3_session *s, const uint8_t *c,
                               wfas3_out *out)
{
    if ((s->pair < WFAS3_MAX_PAIRS) && (dev->pairs[s->pair].used == 1U) &&
        ((dev->pairs[s->pair].flags & WFAS3_PF_GUEST) == 0U) &&
        (dev->pairs[s->pair].held_current == 0U) && (wfas3_ct_eq(c, dev->entry_hash, 8U) == 1U)) {
        wfas3_pair *p = &dev->pairs[s->pair];

        memcpy(p->own_entry_held, dev->entry_key, WFAS3_KEY_LEN);
        (void)wfas3_kdf(p->k_mac1_held, WFAS3_KEY_LEN, p->own_entry_held, WFAS3_LBL("mac1"), NULL, 0U);
        wfas3_pair_groups(dev);
        wfas3_store_pair(dev, s->pair, out);
    }
}

static void wfas3_entry_key_in(wfas3_device *dev, wfas3_session *s, const uint8_t *c,
                               wfas3_out *out)
{
    uint8_t h[WFAS3_HASH_LEN];

    if ((s->pair < WFAS3_MAX_PAIRS) && (dev->pairs[s->pair].used == 1U) &&
        ((dev->pairs[s->pair].flags & WFAS3_PF_GUEST) == 0U)) {     /* ignored from a guest */
        wfas3_pair *p = &dev->pairs[s->pair];

        if (wfas3_ct_eq(p->peer_entry, c, WFAS3_KEY_LEN) == 0U) {
            memcpy(p->peer_entry, c, WFAS3_KEY_LEN);
            wfas3_store_pair(dev, s->pair, out);         /* stored before the ACK leaves */
        }
        wfas_tp_sha256(h, c, WFAS3_KEY_LEN);
        memcpy(s->ekack_hash, h, 8U);
        s->ekack = 1U;
    }
}

static void wfas3_rk_chunk_store(wfas3_rekey *rk, const uint8_t *c, uint32_t chunks)
{
    uint32_t chunk = c[1];

    if (chunk < chunks) {
        memcpy(&rk->buf[chunk * WFAS3_RK_CHUNK], &c[2], WFAS3_RK_CHUNK);
        rk->bits_rx |= (1U << chunk);
    }
}

static wfas3_status wfas3_rk_resp_verify(wfas3_device *dev, uint32_t si);

/* REKEY_INIT, REKEY_RESP, REKEY_ACK (11). 0 if the session must close. */
static uint32_t wfas3_rekey_in(wfas3_device *dev, uint32_t si, uint8_t t, const uint8_t *c)
{
    wfas3_session *s = &dev->sessions[si];
    wfas3_rekey *rk = &s->rk;
    uint32_t id = c[0];
    uint32_t keep = 1U;
    uint32_t init_all = (1U << WFAS3_RK_INIT_CHUNKS) - 1U;
    uint32_t resp_all = (1U << WFAS3_RK_RESP_CHUNKS) - 1U;

    if (t == WFAS3_F_REKEY_INIT) {
        if ((rk->state == WFAS3_RK_IDLE) && (id == ((rk->last_id + 1U) & 0xFFU))) {
            rk->state = WFAS3_RK_R_RECV;
            rk->id = id;
            rk->started_ms = dev->now_ms;
            rk->bits_rx = 0U;
            rk->bits_ack = 0U;
            rk->next_chunk = 0U;
            memset(rk->buf, 0, sizeof rk->buf);
        }
        if ((rk->state == WFAS3_RK_R_RECV) && (id == rk->id)) {
            wfas3_rk_chunk_store(rk, c, WFAS3_RK_INIT_CHUNKS);
            if (rk->bits_rx == init_all) {
                rk->state = WFAS3_RK_R_COMPUTE;
            }
        }
    } else if (t == WFAS3_F_REKEY_RESP) {
        if ((id == rk->id) && ((rk->state == WFAS3_RK_I_SEND) || (rk->state == WFAS3_RK_I_RECV))) {
            if (rk->state == WFAS3_RK_I_SEND) {
                rk->state = WFAS3_RK_I_RECV;             /* acknowledges the whole REKEY_INIT */
                rk->bits_rx = 0U;
                memset(rk->buf, 0, sizeof rk->buf);
            }
            wfas3_rk_chunk_store(rk, c, WFAS3_RK_RESP_CHUNKS);
            if ((rk->bits_rx == resp_all) && (wfas3_rk_resp_verify(dev, si) != WFAS3_OK)) {
                keep = 0U;
            }
        }
    } else if (id == rk->id) {
        if (rk->state == WFAS3_RK_I_SEND) {
            rk->bits_ack |= wfas3_rd_be32(&c[1]);
            if (rk->bits_ack == init_all) {
                rk->state = WFAS3_RK_I_RECV;
                rk->bits_rx = 0U;
                memset(rk->buf, 0, sizeof rk->buf);
            }
        } else if (rk->state == WFAS3_RK_R_SEND) {
            rk->bits_ack |= wfas3_rd_be32(&c[1]);
        } else {
            /* nothing is being sent */
        }
    } else {
        /* an acknowledgement of another rekey */
    }
    return keep;
}

/* Apply one valid frame. 1 when the session ended on it. */
static uint32_t wfas3_fr_apply(wfas3_device *dev, uint32_t si, uint8_t t, const uint8_t *c,
                               size_t flen, uint32_t advanced, const wfas3_addr *from,
                               wfas3_out *out)
{
    wfas3_session *s = &dev->sessions[si];
    uint32_t ended = 0U;
    uint32_t pair;

    switch (t) {
    case WFAS3_F_AUDIO:
    case WFAS3_F_MIC_AUDIO:
        wfas3_audio_in(dev, s, c, flen, out);
        break;
    case WFAS3_F_START:
        wfas3_tx_start(dev, s, wfas3_rd_be32(c), advanced, out);
        if (((s->flags & WFAS3_CF_MULTICAST) != 0U) && (s->source == 1U) && (s->tx.started == 1U)) {
            wfas3_member_add(dev, si);                   /* it gets the group keys (14.2) */
        }
        break;
    case WFAS3_F_GROUP_JOIN:
        wfas3_join_in(dev, si, c, out);
        break;
    case WFAS3_F_GROUP_ACK:
        wfas3_gack_in(dev, si, c);
        break;
    case WFAS3_F_GROUP_LEAVE:
        wfas3_gleave_in(dev, si, c, out);
        break;
    case WFAS3_F_NACK:
        wfas3_nack_in(dev, si, c);
        break;
    case WFAS3_F_STOP:
        if (s->tx.started == 1U) {
            s->tx.started = 0U;
            out->events |= WFAS3_EV_STREAM_STOP;
        }
        break;
    case WFAS3_F_REPORT:
        wfas3_tx_report(dev, s, c, advanced);
        break;
    case WFAS3_F_TIME_REQ:
        s->tx.tresp = 1U;
        s->tx.tresp_t1 = wfas3_rd_be64(c);
        s->tx.tresp_t2 = dev->now_us;
        break;
    case WFAS3_F_TIME_RESP:
        wfas3_sync_sample(dev, &s->rx, c);
        break;
    case WFAS3_F_PARAMS:
        wfas3_rx_params(dev, s, wfas3_rd_be32(c), &c[4], 1U, out);
        break;
    case WFAS3_F_MIC_STATE:
        wfas3_rx_params(dev, s, wfas3_rd_be32(&c[1]), &c[5], c[0], out);
        break;
    case WFAS3_F_PARAMS_ACK:
        if (wfas3_rd_be32(c) == s->tx.epoch) {
            s->tx.announce = 0U;
        }
        break;
    case WFAS3_F_REKEY_INIT:
    case WFAS3_F_REKEY_RESP:
    case WFAS3_F_REKEY_ACK:
        if (wfas3_rekey_in(dev, si, t, c) == 0U) {
            wfas3_ses_close(dev, si, WFAS3_BYE_REKEY_FAILED, 0U, WFAS3_DOWN_REKEY, out);
            ended = 1U;
        }
        break;
    case WFAS3_F_PATH_CHALLENGE:
        memcpy(s->path_resp_data, c, 8U);
        s->path_resp = 1U;
        break;
    case WFAS3_F_PATH_RESPONSE:
        if ((s->path_active == 1U) && (wfas3_addr_eq(from, &s->path_addr) == 1U) &&
            (wfas3_ct_eq(c, s->path_data, 8U) == 1U)) {
            s->addr = s->path_addr;
            s->path_active = 0U;
            dev->pairs[s->pair].seen_addr = s->path_addr;
            out->events |= WFAS3_EV_PATH_CHANGED;
        }
        break;
    case WFAS3_F_BYE:
        pair = s->pair;
        wfas3_ses_down(dev, si, c[0], out);
        if ((c[0] == WFAS3_BYE_GUEST_ENDED) && (pair < WFAS3_MAX_PAIRS) &&
            (dev->pairs[pair].used == 1U) && ((dev->pairs[pair].flags & WFAS3_PF_GUEST) != 0U)) {
            (void)wfas3_pair_drop(dev, pair, out);       /* the guest pair ends (13.6) */
        }
        ended = 1U;
        break;
    case WFAS3_F_FORGET:
        out->events |= WFAS3_EV_FORGET_ASKED;
        wfas3_ses_down(dev, si, WFAS3_BYE_REVOKED, out);
        ended = 1U;
        break;
    case WFAS3_F_ENTRY_KEY:
        wfas3_entry_key_in(dev, s, c, out);
        break;
    case WFAS3_F_ENTRY_KEY_ACK:
        wfas3_entry_ack_in(dev, s, c, out);
        break;
    default:
        break;                                       /* excluded by wfas3_fr_check() */
    }
    return ended;
}

/* -- Transport: rekey (11) --------------------------------------------------- */

/* Name, static keys and MixHash(rekey_id || initiator index || responder index). */
static wfas3_status wfas3_rk_sym_start(const wfas3_device *dev, const wfas3_session *s, wfas3_sym *sym)
{
    const wfas3_pair *p = &dev->pairs[s->pair];
    uint8_t ctx[9];
    wfas3_status st = wfas3_sym_init(sym, WFAS3_LBL("WFAS3_KKpsk0hfs_rekey_25519+MLKEM768_ChaChaPoly_SHA256"));

    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(sym, (s->initiator == 1U) ? p->s_pub : p->peer_pub, 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(sym, (s->initiator == 1U) ? p->peer_pub : p->s_pub, 32U);
    }
    if (st == WFAS3_OK) {
        ctx[0] = (uint8_t)s->rk.id;
        wfas3_wr_be32(&ctx[1], (s->initiator == 1U) ? s->recv_index : s->send_index);
        wfas3_wr_be32(&ctx[5], (s->initiator == 1U) ? s->send_index : s->recv_index);
        st = wfas3_sym_mix_hash(sym, ctx, sizeof ctx);
    }
    return st;
}

static wfas3_status wfas3_rk_keys(const wfas3_sym *sym, uint8_t i2r[32], uint8_t r2i[32], uint8_t psk[32])
{
    wfas3_status st = wfas3_kdf(i2r, WFAS3_KEY_LEN, sym->ck, WFAS3_LBL("transport i2r"), NULL, 0U);

    if (st == WFAS3_OK) {
        st = wfas3_kdf(r2i, WFAS3_KEY_LEN, sym->ck, WFAS3_LBL("transport r2i"), NULL, 0U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_kdf(psk, WFAS3_KEY_LEN, sym->ck, WFAS3_LBL("rekey psk"), NULL, 0U);
    }
    return st;
}

/* Initiator: build REKEY_INIT (needs fresh entropy). */
static wfas3_status wfas3_rk_start(wfas3_device *dev, uint32_t si)
{
    wfas3_session *s = &dev->sessions[si];
    wfas3_rekey *rk = &s->rk;
    const wfas3_pair *p = &dev->pairs[s->pair];
    uint8_t pk[WFAS_TP_MLKEM_PK];
    uint8_t sk[WFAS_TP_MLKEM_SK];
    uint8_t zero48[48];
    uint8_t *b = rk->buf;
    wfas3_status st;

    memset(zero48, 0, sizeof zero48);
    memset(rk->buf, 0, sizeof rk->buf);
    rk->id = (rk->last_id + 1U) & 0xFFU;
    st = wfas3_index_new(dev, &rk->new_index);
    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, rk->kem_seed, sizeof rk->kem_seed);
    }
    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, rk->e_priv, sizeof rk->e_priv);
    }
    if (st == WFAS3_OK) {
        st = wfas3_rk_sym_start(dev, s, &rk->sym);
    }
    if (st == WFAS3_OK) {
        wfas3_wr_be32(b, rk->new_index);
        st = wfas3_sym_mix_hash(&rk->sym, b, 4U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key_and_hash(&rk->sym, s->rekey_psk);
    }
    if (st == WFAS3_OK) {
        wfas_tp_x25519_public(&b[4], rk->e_priv);
        st = wfas3_sym_mix_hash(&rk->sym, &b[4], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key(&rk->sym, &b[4], 32U);
    }
    if (st == WFAS3_OK) {
        wfas_tp_mlkem768_keygen(pk, sk, rk->kem_seed);
        st = wfas3_sym_encrypt(&rk->sym, &b[36], 1200U, pk, sizeof pk);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&rk->sym, rk->e_priv, p->peer_pub);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&rk->sym, p->s_priv, p->peer_pub);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_encrypt(&rk->sym, &b[1236], 64U, zero48, sizeof zero48);
    }
    if (st == WFAS3_OK) {
        rk->state = WFAS3_RK_I_SEND;
        rk->started_ms = dev->now_ms;
        rk->bits_ack = 0U;
        rk->bits_rx = 0U;
        rk->next_chunk = 0U;
        s->next_rekey_ms = dev->now_ms + (uint64_t)WFAS3_REKEY_EVERY_MS;
    }
    wfas3_wipe(sk, sizeof sk);
    return st;
}

/* Responder: check REKEY_INIT, build REKEY_RESP (needs fresh entropy). */
static wfas3_status wfas3_rk_answer(wfas3_device *dev, uint32_t si)
{
    wfas3_session *s = &dev->sessions[si];
    wfas3_rekey *rk = &s->rk;
    const wfas3_pair *p = &dev->pairs[s->pair];
    uint8_t pk[WFAS_TP_MLKEM_PK];
    uint8_t pay[48];
    uint8_t e_i[32];
    uint8_t rnd[64];            /* e_r private key (0..32), ML-KEM message m (32..64) */
    uint8_t ct[WFAS_TP_MLKEM_CT];
    uint8_t ss[32];
    uint8_t i2r[32];
    uint8_t *b = rk->buf;
    wfas3_status st = (wfas3_all_zero(&b[WFAS3_RK_INIT_LEN], sizeof rk->buf - WFAS3_RK_INIT_LEN) == 1U)
                      ? wfas3_rk_sym_start(dev, s, &rk->sym) : WFAS3_E_AUTH;

    if (st == WFAS3_OK) {
        rk->peer_new_index = wfas3_rd_be32(b);
        st = wfas3_sym_mix_hash(&rk->sym, b, 4U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key_and_hash(&rk->sym, s->rekey_psk);
    }
    if (st == WFAS3_OK) {
        memcpy(e_i, &b[4], 32U);
        st = wfas3_sym_mix_hash(&rk->sym, e_i, 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key(&rk->sym, e_i, 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_decrypt(&rk->sym, pk, sizeof pk, &b[36], 1200U);
    }
    if ((st == WFAS3_OK) && (wfas_tp_mlkem768_check_pk(pk) != 1U)) {
        st = WFAS3_E_AUTH;
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&rk->sym, p->s_priv, e_i);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&rk->sym, p->s_priv, p->peer_pub);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_decrypt(&rk->sym, pay, sizeof pay, &b[1236], 64U);
    }
    if ((st == WFAS3_OK) && (wfas3_all_zero(pay, sizeof pay) != 1U)) {
        st = WFAS3_E_AUTH;
    }
    /* REKEY_RESP */
    if (st == WFAS3_OK) {
        st = wfas3_index_new(dev, &rk->new_index);
    }
    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, rnd, sizeof rnd);
    }
    if (st == WFAS3_OK) {
        memset(rk->buf, 0, sizeof rk->buf);
        wfas3_wr_be32(b, rk->new_index);
        st = wfas3_sym_mix_hash(&rk->sym, b, 4U);
    }
    if (st == WFAS3_OK) {
        wfas_tp_x25519_public(&b[4], rnd);
        st = wfas3_sym_mix_hash(&rk->sym, &b[4], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key(&rk->sym, &b[4], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&rk->sym, rnd, e_i);
    }
    if (st == WFAS3_OK) {
        wfas_tp_mlkem768_encaps(ct, ss, pk, &rnd[32]);
        st = wfas3_sym_encrypt(&rk->sym, &b[36], 1104U, ct, sizeof ct);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key(&rk->sym, ss, sizeof ss);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&rk->sym, rnd, p->peer_pub);
    }
    if (st == WFAS3_OK) {
        memset(pay, 0, sizeof pay);
        st = wfas3_sym_encrypt(&rk->sym, &b[1140], 64U, pay, sizeof pay);
    }
    if (st == WFAS3_OK) {
        st = wfas3_rk_keys(&rk->sym, i2r, rk->k_send_new, rk->psk_new);
    }
    if (st == WFAS3_OK) {
        memcpy(rk->k_recv_new, i2r, WFAS3_KEY_LEN);
        memset(&rk->win_new, 0, sizeof rk->win_new);
        rk->state = WFAS3_RK_R_SEND;
        rk->bits_ack = 0U;
        rk->next_chunk = 0U;
    }
    wfas3_sym_wipe(&rk->sym);
    wfas3_wipe(rnd, sizeof rnd);
    wfas3_wipe(ss, sizeof ss);
    wfas3_wipe(i2r, sizeof i2r);
    return st;
}

/* Initiator: the whole REKEY_RESP has arrived; verify it and switch sending. */
static wfas3_status wfas3_rk_resp_verify(wfas3_device *dev, uint32_t si)
{
    wfas3_session *s = &dev->sessions[si];
    wfas3_rekey *rk = &s->rk;
    const wfas3_pair *p = &dev->pairs[s->pair];
    uint8_t pk[WFAS_TP_MLKEM_PK];
    uint8_t sk[WFAS_TP_MLKEM_SK];
    uint8_t ct[WFAS_TP_MLKEM_CT];
    uint8_t ss[32];
    uint8_t pay[48];
    uint8_t i2r[32];
    uint8_t *b = rk->buf;
    wfas3_status st = (wfas3_all_zero(&b[WFAS3_RK_RESP_LEN],
                                      (WFAS3_RK_RESP_CHUNKS * WFAS3_RK_CHUNK) - WFAS3_RK_RESP_LEN) == 1U)
                      ? wfas3_sym_mix_hash(&rk->sym, b, 4U) : WFAS3_E_AUTH;

    if (st == WFAS3_OK) {
        rk->peer_new_index = wfas3_rd_be32(b);
        st = wfas3_sym_mix_hash(&rk->sym, &b[4], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key(&rk->sym, &b[4], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&rk->sym, rk->e_priv, &b[4]);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_decrypt(&rk->sym, ct, sizeof ct, &b[36], 1104U);
    }
    if (st == WFAS3_OK) {
        wfas_tp_mlkem768_keygen(pk, sk, rk->kem_seed);   /* the same key as in REKEY_INIT */
        wfas_tp_mlkem768_decaps(ss, ct, sk);
        st = wfas3_sym_mix_key(&rk->sym, ss, sizeof ss);
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&rk->sym, p->s_priv, &b[4]);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_decrypt(&rk->sym, pay, sizeof pay, &b[1140], 64U);
    }
    if ((st == WFAS3_OK) && (wfas3_all_zero(pay, sizeof pay) != 1U)) {
        st = WFAS3_E_AUTH;
    }
    if (st == WFAS3_OK) {
        st = wfas3_rk_keys(&rk->sym, i2r, rk->k_recv_new, rk->psk_new);
    }
    if (st == WFAS3_OK) {
        memcpy(s->k_send, i2r, WFAS3_KEY_LEN);            /* switch: send with the new keys */
        s->send_index = rk->peer_new_index;
        s->send_ctr = 0U;
        memcpy(s->rekey_psk, rk->psk_new, WFAS3_KEY_LEN);
        memset(&rk->win_new, 0, sizeof rk->win_new);
        rk->state = WFAS3_RK_I_SWITCH;
        s->next_ctl_ms = dev->now_ms;                       /* tell the responder at once */
    }
    wfas3_sym_wipe(&rk->sym);
    wfas3_wipe(rk->e_priv, sizeof rk->e_priv);
    wfas3_wipe(rk->kem_seed, sizeof rk->kem_seed);
    wfas3_wipe(sk, sizeof sk);
    wfas3_wipe(ss, sizeof ss);
    wfas3_wipe(i2r, sizeof i2r);
    return st;
}

/* A packet arrived on the new receive index: finish the switch (11). The old
 * receive key stays for WFAS3_REKEY_GRACE_MS, for packets that the network
 * delivers late, and never beyond 60 s after the rekey began. */
static void wfas3_rk_switched(const wfas3_device *dev, wfas3_session *s, wfas3_out *out)
{
    wfas3_rekey *rk = &s->rk;
    uint64_t until = dev->now_ms + (uint64_t)WFAS3_REKEY_GRACE_MS;
    uint64_t limit = rk->started_ms + (uint64_t)WFAS3_REKEY_LIMIT_MS;

    memcpy(s->k_recv_old, s->k_recv, WFAS3_KEY_LEN);
    s->recv_index_old = s->recv_index;
    s->win_old = s->win;
    s->old_until_ms = (until < limit) ? until : limit;
    s->old_valid = 1U;
    if (s->initiator == 0U) {
        memcpy(s->k_send, rk->k_send_new, WFAS3_KEY_LEN);
        s->send_index = rk->peer_new_index;
        s->send_ctr = 0U;
        memcpy(s->rekey_psk, rk->psk_new, WFAS3_KEY_LEN);
    }
    memcpy(s->k_recv, rk->k_recv_new, WFAS3_KEY_LEN);
    s->recv_index = rk->new_index;
    s->win = rk->win_new;
    rk->last_id = rk->id;
    rk->count += 1U;
    rk->state = WFAS3_RK_IDLE;
    wfas3_wipe(rk->k_send_new, sizeof rk->k_send_new);
    wfas3_wipe(rk->k_recv_new, sizeof rk->k_recv_new);
    wfas3_wipe(rk->psk_new, sizeof rk->psk_new);
    wfas3_wipe(rk->buf, sizeof rk->buf);
    out->events |= WFAS3_EV_REKEYED;
}

/* -- Transport: receiving (10.1) --------------------------------------------- */

/* The session whose receive index is idx; *is_new is 1 for the index it takes
 * after a rekey, 2 for the index it had before the last rekey. */
static uint32_t wfas3_ses_find_recv(const wfas3_device *dev, uint32_t idx, uint32_t *is_new)
{
    uint32_t found = WFAS3_NONE;
    uint32_t i;

    *is_new = 0U;
    for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
        const wfas3_session *s = &dev->sessions[i];

        if (s->state != WFAS3_SES_FREE) {
            if (s->recv_index == idx) {
                found = i;
                *is_new = 0U;
            } else if ((s->rk.new_index == idx) &&
                       ((s->rk.state == WFAS3_RK_I_SWITCH) || (s->rk.state == WFAS3_RK_R_SEND))) {
                found = i;
                *is_new = 1U;
            } else if ((s->old_valid == 1U) && (s->recv_index_old == idx) &&
                       (dev->now_ms < s->old_until_ms)) {
                found = i;
                *is_new = 2U;
            } else {
                /* another session */
            }
        }
    }
    return found;
}

/* First authentic packet on an unconfirmed session: the key is confirmed. */
static void wfas3_ses_confirm(wfas3_device *dev, uint32_t si, wfas3_out *out)
{
    wfas3_session *s = &dev->sessions[si];
    wfas3_pair *p = &dev->pairs[s->pair];

    s->state = WFAS3_SES_UP;
    s->next_ctl_ms = dev->now_ms;
    s->last_rx_ms = dev->now_ms;
    s->rx.sync_start_ms = dev->now_ms;
    s->rx.next_treq_ms = dev->now_ms;
    s->rx.last_late_ms = dev->now_ms;
    s->rx.last_dec_ms = dev->now_ms;
    if ((p->rep_has[WFAS3_REP_RESPP] == 1U) &&
        (wfas3_ct_eq(p->rep_fp[WFAS3_REP_RESPP], s->fp, 16U) == 1U)) {
        wfas3_rep_set(p, WFAS3_REP_LAST, s->fp, p->rep_seq[WFAS3_REP_RESPP]);
        wfas3_store_pair(dev, s->pair, out);
    }
    wfas3_ses_drop_pair(dev, s->pair, WFAS3_DROP_OPEN, si);
    out->events |= WFAS3_EV_SESSION_UP;
    wfas3_config_decode(&out->config, s->config);
}

/* Decrypted and authentic: rekey switch, confirmation, frames, timers. */
static void wfas3_transport_apply(wfas3_device *dev, uint32_t si, const uint8_t *pt, size_t pt_len,
                                  size_t len, uint32_t is_new, uint32_t advanced,
                                  const wfas3_addr *from, wfas3_out *out)
{
    wfas3_session *s = &dev->sessions[si];
    wfas3_frames f;
    uint32_t kind = ((pt[0] == WFAS3_F_AUDIO) || (pt[0] == WFAS3_F_MIC_AUDIO))
                    ? WFAS3_KIND_AUDIO : WFAS3_KIND_CONTROL;
    uint32_t ok;
    uint32_t ended = 0U;
    uint32_t i;

    out->pair = s->pair;
    out->session = si;
    if (is_new == 1U) {
        wfas3_rk_switched(dev, s, out);
    }
    if (s->state == WFAS3_SES_UNCONFIRMED) {
        wfas3_ses_confirm(dev, si, out);
    }
    ok = wfas3_fr_parse(pt, pt_len, &f);
    if (ok == 1U) {
        ok = (kind == WFAS3_KIND_AUDIO) ? ((len == wfas3_rx_audio_len(s)) ? 1U : 0U)
                                        : ((len == (size_t)WFAS3_LEN_CONTROL) ? 1U : 0U);
    }
    if (ok == 1U) {
        ok = wfas3_fr_check(s, pt, &f, kind);
    }
    if (ok == 0U) {
        wfas3_ses_close(dev, si, WFAS3_BYE_PROTOCOL, 0U, WFAS3_DOWN_PROTOCOL, out);
        ended = 1U;
    }
    for (i = 0U; (i < f.n) && (i < WFAS3_F_COUNT) && (ended == 0U); i++) {
        ended = wfas3_fr_apply(dev, si, f.type[i], &pt[f.off[i]], f.len[i], advanced, from, out);
    }
    if ((ended == 0U) && (advanced == 1U)) {
        s->last_rx_ms = dev->now_ms;
        if (s->silent == 1U) {
            s->silent = 0U;
            out->events |= WFAS3_EV_PEER_BACK;
        }
        if ((s->path_active == 0U) && (wfas3_addr_eq(from, &s->addr) == 0U) &&
            (wfas3_rng_bytes(&dev->rng, s->path_data, sizeof s->path_data) == WFAS3_OK)) {
            s->path_active = 1U;
            s->path_addr = *from;
            s->path_tries = 0U;
            s->path_next_ms = dev->now_ms;
        }
    }
}

static wfas3_status wfas3_transport_handle(wfas3_device *dev, const uint8_t *d, size_t len,
                                           const wfas3_addr *from, wfas3_out *out)
{
    uint32_t is_new = 0U;
    uint32_t si = wfas3_ses_find_recv(dev, wfas3_rd_be32(&d[4]), &is_new);
    uint64_t ctr = wfas3_rd_be64(&d[8]);
    uint8_t pt[WFAS3_MAX_DATAGRAM];
    wfas3_status st = WFAS3_E_DROP;

    if (si != WFAS3_NONE) {
        wfas3_session *s = &dev->sessions[si];
        wfas3_window *w = (is_new == 1U) ? &s->rk.win_new : ((is_new == 2U) ? &s->win_old : &s->win);
        const uint8_t *key = (is_new == 1U) ? s->rk.k_recv_new
                                            : ((is_new == 2U) ? s->k_recv_old : s->k_recv);
        size_t alen = wfas3_rx_audio_len(s);

        if (((s->state == WFAS3_SES_UP) || (s->state == WFAS3_SES_UNCONFIRMED)) &&
            ((len == (size_t)WFAS3_LEN_CONTROL) || ((alen > 0U) && (len == alen))) &&
            (wfas3_win_check(w, ctr) == 1U) &&
            (wfas3_open(pt, sizeof pt, key, ctr, d, 16U, &d[16], len - 16U) == WFAS3_OK)) {
            /* A packet under the old key of a finished rekey is older than every
             * packet under the new one: it never advances the counter (11). */
            uint32_t advanced = wfas3_win_mark(w, ctr) & ((is_new == 2U) ? 0U : 1U);

            st = WFAS3_OK;
            wfas3_transport_apply(dev, si, pt, len - 32U, len, is_new, advanced, from, out);
        }
    }
    wfas3_wipe(pt, sizeof pt);
    return st;
}

/* -- Transport: sending audio and local controls ------------------------------ */

/* An audio packet of the stream session si sends (10.3). */
static wfas3_status wfas3_audio_seal(wfas3_device *dev, uint32_t si, uint32_t epoch, uint64_t sample_pos,
                                     uint64_t capture_us, const uint8_t *data, size_t pb, wfas3_out *out)
{
    wfas3_session *s = &dev->sessions[si];
    uint8_t pt[3U + WFAS3_AUDIO_HEAD + WFAS3_MAX_AUDIO_PAYLOAD];
    uint8_t *d = out->dgram;
    wfas3_status st;

    pt[0] = (s->source == 1U) ? WFAS3_F_AUDIO : WFAS3_F_MIC_AUDIO;
    wfas3_wr_be16(&pt[1], (uint16_t)(WFAS3_AUDIO_HEAD + pb));
    wfas3_wr_be32(&pt[3], epoch);
    wfas3_wr_be64(&pt[7], sample_pos);
    wfas3_wr_be64(&pt[15], capture_us);
    memcpy(&pt[23], data, pb);
    d[0] = WFAS3_MARKER;
    d[1] = WFAS3_T_TRANSPORT;
    d[2] = 0U;
    d[3] = 0U;
    wfas3_wr_be32(&d[4], s->send_index);
    wfas3_wr_be64(&d[8], s->send_ctr);
    st = wfas3_seal(&d[16], sizeof out->dgram - 16U, s->k_send, s->send_ctr, d, 16U, pt, 23U + pb);
    if (st == WFAS3_OK) {
        s->send_ctr += 1U;
        out->len = (size_t)WFAS3_AUDIO_OVERHEAD + pb;
        out->to = s->addr;
        out->session = si;
        out->pair = s->pair;
    }
    wfas3_wipe(pt, sizeof pt);
    return st;
}

wfas3_status wfas3_send_audio(wfas3_device *dev, uint32_t session, uint64_t sample_pos,
                              uint64_t capture_us, const uint8_t *data, size_t len,
                              wfas3_out *out)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (out != NULL) && (data != NULL) && (dev->started == 1U) &&
        (session < WFAS3_MAX_SESSIONS)) {
        const wfas3_session *s = &dev->sessions[session];
        size_t pb = wfas3_cfg_payload(s->tx.config);
        uint32_t group = (((s->flags & WFAS3_CF_MULTICAST) != 0U) && (s->source == 1U)) ? 1U : 0U;

        wfas3_out_clear(out);
        st = WFAS3_E_STATE;
        /* A lapsed consent or a spent counter is reported by wfas3_poll(). The
         * main stream of a group goes by wfas3_group_send(). */
        if ((s->state == WFAS3_SES_UP) && (s->tx.exists == 1U) && (s->tx.on == 1U) && (group == 0U) &&
            (s->tx.started == 1U) && (dev->now_ms <= s->tx.consent_ms) && (wfas3_ctr_spent(s) == 0U)) {
            if ((len != pb) || (pb == 0U)) {
                st = WFAS3_E_ARG;
            } else {
                st = wfas3_audio_seal(dev, session, s->tx.epoch, sample_pos, capture_us, data, pb, out);
            }
        }
    }
    return st;
}

static wfas3_session *wfas3_ses_up(wfas3_device *dev, uint32_t session)
{
    wfas3_session *s = NULL;

    if ((dev != NULL) && (dev->started == 1U) && (session < WFAS3_MAX_SESSIONS) &&
        (dev->sessions[session].state == WFAS3_SES_UP)) {
        s = &dev->sessions[session];
    }
    return s;
}

wfas3_status wfas3_stream_want(wfas3_device *dev, uint32_t session, uint32_t want)
{
    wfas3_session *s = wfas3_ses_up(dev, session);
    wfas3_status st = WFAS3_E_STATE;

    if (want > 1U) {
        st = WFAS3_E_ARG;
    } else if ((s != NULL) && (s->rx.exists == 1U)) {
        s->rx.want = want;
        if ((want == 0U) && (s->source == 0U) && (s->mrx.joined == 1U)) {
            uint32_t group = s->mrx.group;

            /* It leaves the group: the application leaves the address too. */
            wfas3_wipe((uint8_t *)&s->mrx, sizeof s->mrx);
            s->mrx.leave_left = WFAS3_GROUP_LEAVES;
            s->mrx.leave_group = group;
        }
        st = WFAS3_OK;
    } else {
        st = WFAS3_E_STATE;
    }
    return st;
}

wfas3_status wfas3_stream_params(wfas3_device *dev, uint32_t session, const wfas3_config *config)
{
    wfas3_session *s = wfas3_ses_up(dev, session);
    wfas3_status st = WFAS3_E_STATE;
    uint8_t c[16];

    if (config == NULL) {
        st = WFAS3_E_ARG;
    } else if ((s != NULL) && (s->source == 1U) && ((s->flags & WFAS3_CF_MULTICAST) != 0U)) {
        st = WFAS3_E_STATE;                              /* a group keeps its configuration */
    } else if ((s != NULL) && (s->tx.exists == 1U) && (s->tx.epoch < 0xFFFFFFFFU)) {
        wfas3_config_encode(c, config, 0U);
        c[0] = s->flags;
        if (wfas3_config_valid(c, 0U) == 0U) {
            st = WFAS3_E_ARG;
        } else if ((s->source == 0U) &&
                   ((dev->pairs[s->pair].perms & WFAS3_PERM_MICROPHONE) == 0U)) {
            st = WFAS3_E_PERM;
        } else {
            s->tx.epoch += 1U;
            memcpy(s->tx.config, c, sizeof c);
            s->tx.on = 1U;
            s->tx.started = 0U;
            s->tx.announce = 1U;
            st = WFAS3_OK;
        }
    } else {
        st = WFAS3_E_STATE;
    }
    return st;
}

wfas3_status wfas3_mic_off(wfas3_device *dev, uint32_t session)
{
    wfas3_session *s = wfas3_ses_up(dev, session);
    wfas3_status st = WFAS3_E_STATE;

    if ((s != NULL) && (s->source == 0U) && (s->tx.exists == 1U) && (s->tx.on == 1U) &&
        (s->tx.epoch < 0xFFFFFFFFU)) {
        s->tx.epoch += 1U;
        s->tx.on = 0U;
        s->tx.started = 0U;
        memset(s->tx.config, 0, sizeof s->tx.config);
        s->tx.announce = 1U;
        st = WFAS3_OK;
    }
    return st;
}

wfas3_status wfas3_audio_late(wfas3_device *dev, uint32_t session, uint64_t late_us)
{
    wfas3_session *s = wfas3_ses_up(dev, session);
    wfas3_status st = WFAS3_E_STATE;

    if ((s != NULL) && (s->rx.exists == 1U) && ((s->flags & WFAS3_CF_SYNC) != 0U)) {
        wfas3_d_late(&s->rx, late_us, dev->now_ms);
        st = WFAS3_OK;
    }
    return st;
}

wfas3_status wfas3_set_buffer_ms(wfas3_device *dev, uint32_t session, uint32_t ms)
{
    wfas3_session *s = wfas3_ses_up(dev, session);
    wfas3_status st = WFAS3_E_STATE;

    if ((s != NULL) && (s->rx.exists == 1U)) {
        s->rx.buffer_ms = (ms > 255U) ? 255U : ms;
        st = WFAS3_OK;
    }
    return st;
}

wfas3_status wfas3_session_close(wfas3_device *dev, uint32_t session, uint32_t reason,
                                 wfas3_out *out)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (out != NULL) && (dev->started == 1U) && (session < WFAS3_MAX_SESSIONS) &&
        (reason <= WFAS3_BYE_GUEST_ENDED) && (reason != WFAS3_BYE_REVOKED) &&
        (reason != WFAS3_BYE_PROTOCOL)) {
        const wfas3_session *s = &dev->sessions[session];
        uint32_t pair = s->pair;
        uint32_t guest = ((pair < WFAS3_MAX_PAIRS) && (dev->pairs[pair].used == 1U) &&
                          ((dev->pairs[pair].flags & WFAS3_PF_GUEST) != 0U)) ? 1U : 0U;

        wfas3_out_clear(out);
        st = WFAS3_E_STATE;
        if ((reason == WFAS3_BYE_GUEST_ENDED) && (guest == 0U)) {
            st = WFAS3_E_ARG;                            /* only a guest pair ends this way */
        } else if (s->state == WFAS3_SES_UP) {
            wfas3_ses_close(dev, session, reason, 0U, WFAS3_DOWN_LOCAL, out);
            if (reason == WFAS3_BYE_GUEST_ENDED) {
                (void)wfas3_pair_drop(dev, pair, out);
            }
            st = WFAS3_OK;
        } else {
            /* not up */
        }
    }
    return st;
}

static void wfas3_info_cfg(wfas3_config *cfg, const uint8_t c[16], uint32_t is_source)
{
    wfas3_config_decode(cfg, c);
    if (is_source == 0U) {
        cfg->flags = (uint8_t)(cfg->flags & (uint8_t)~WFAS3_CF_SOURCE);
    }
}

wfas3_status wfas3_session_info(const wfas3_device *dev, uint32_t session, wfas3_info *info)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (info != NULL) && (session < WFAS3_MAX_SESSIONS)) {
        const wfas3_session *s = &dev->sessions[session];

        memset(info, 0, sizeof *info);
        info->state = s->state;
        info->pair = s->pair;
        st = WFAS3_OK;
        if (s->state != WFAS3_SES_FREE) {
            info->initiator = s->initiator;
            info->source = s->source;
            info->microphone = ((s->flags & WFAS3_CF_MICROPHONE) != 0U) ? 1U : 0U;
            info->synchronised = ((s->flags & WFAS3_CF_SYNC) != 0U) ? 1U : 0U;
            info->addr = s->addr;
            info->tx_exists = s->tx.exists;
            info->tx_on = s->tx.on;
            info->tx_started = s->tx.started;
            info->tx_epoch = s->tx.epoch;
            wfas3_info_cfg(&info->tx_config, s->tx.config, 1U);
            info->peer_received = s->tx.rep_received;
            info->peer_lost = s->tx.rep_lost;
            info->peer_jitter_us = s->tx.rep_jitter_us;
            info->peer_buffer_ms = s->tx.rep_buffer_ms;
            info->rx_exists = s->rx.exists;
            info->rx_on = s->rx.on;
            info->rx_wanted = s->rx.want;
            info->rx_epoch = s->rx.epoch;
            wfas3_info_cfg(&info->rx_config, s->rx.config, 0U);
            info->rx_received = s->rx.received;
            info->rx_lost = wfas3_sat_add32(s->rx.lost_done, wfas3_rx_lost_now(&s->rx));
            info->rx_jitter_us = s->rx.jitter_us;
            info->time_valid = s->rx.time_valid;
            info->offset_us = s->rx.offset_us;
            info->delay_ms = s->rx.d_ms;
            info->rekeys = s->rk.count;
            info->rekeying = (s->rk.state != WFAS3_RK_IDLE) ? 1U : 0U;
            info->path_check = s->path_active;
            info->silent = s->silent;
            info->silence_ms = dev->now_ms - s->last_rx_ms;
        }
    }
    return st;
}

/* -- Pair removal and entry key rotation (9.4) -------------------------------- */

static wfas3_status wfas3_entry_key_set(wfas3_device *dev, const uint8_t key[WFAS3_KEY_LEN])
{
    uint8_t h[WFAS3_HASH_LEN];
    wfas3_status st;

    memcpy(dev->entry_key, key, WFAS3_KEY_LEN);
    wfas_tp_sha256(h, key, WFAS3_KEY_LEN);
    memcpy(dev->entry_hash, h, 8U);
    st = wfas3_kdf(dev->k_mac1, WFAS3_KEY_LEN, dev->entry_key, WFAS3_LBL("mac1"), NULL, 0U);
    wfas3_pair_groups(dev);
    return st;
}

/* A pair ends here. A pair closes its session with FORGET and BYE, and the
 * entry key changes (9.4); a guest pair closes with BYE 4 and nothing else
 * changes, since it never held the entry key (13.6). */
static wfas3_status wfas3_pair_drop(wfas3_device *dev, uint32_t pair, wfas3_out *out)
{
    wfas3_status st = WFAS3_OK;
    uint8_t key[WFAS3_KEY_LEN];
    uint32_t guest = ((dev->pairs[pair].flags & WFAS3_PF_GUEST) != 0U) ? 1U : 0U;
    uint32_t sent = 0U;
    uint32_t i;

    memset(key, 0, sizeof key);
    if (guest == 0U) {
        st = wfas3_rng_bytes(&dev->rng, key, sizeof key);
    }
    if (st == WFAS3_OK) {
        i = wfas3_att_find_pair(dev, pair);
        if (i != WFAS3_NONE) {
            wfas3_att_free(&dev->attempts[i]);
        }
        /* A pair has at most one session up (8.5); an unconfirmed one is erased. */
        for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
            wfas3_session *s = &dev->sessions[i];

            if ((s->pair == pair) && (s->state == WFAS3_SES_UP) && (sent == 0U)) {
                wfas3_ses_close(dev, i, (guest == 1U) ? WFAS3_BYE_GUEST_ENDED : WFAS3_BYE_REVOKED,
                                1U ^ guest, WFAS3_DOWN_REMOVED, out);
                sent = 1U;
            } else if ((s->pair == pair) &&
                       ((s->state == WFAS3_SES_UP) || (s->state == WFAS3_SES_UNCONFIRMED))) {
                wfas3_ses_free(s);
            } else {
                /* another pair, or already closing */
            }
        }
        wfas3_find_drop_pair(dev, pair);
        wfas3_wipe((uint8_t *)&dev->pairs[pair], sizeof dev->pairs[pair]);
        dev->pairs[pair].used = 0U;
        if (guest == 0U) {
            st = wfas3_entry_key_set(dev, key);
            out->events |= WFAS3_EV_STORE_DEVICE;
        } else {
            wfas3_pair_groups(dev);
        }
        out->events |= WFAS3_EV_PAIR_ERASED;
        out->pair = pair;
        if (dev->replace_pending == pair) {
            dev->replace_pending = WFAS3_NONE;
        }
    }
    wfas3_wipe(key, sizeof key);
    return st;
}

wfas3_status wfas3_pair_remove(wfas3_device *dev, uint32_t pair, wfas3_out *out)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (out != NULL) && (dev->started == 1U) && (pair < WFAS3_MAX_PAIRS) &&
        (dev->pairs[pair].used == 1U)) {
        wfas3_out_clear(out);
        st = wfas3_pair_drop(dev, pair, out);
    }
    return st;
}

/* -- Discovery (specification 12) -------------------------------------------- */

/* The local broadcast address: ::ffff:255.255.255.255, port 14114. */
static void wfas3_broadcast_addr(wfas3_addr *a)
{
    memset(a, 0, sizeof *a);
    a->ip[10] = 0xFFU;
    a->ip[11] = 0xFFU;
    a->ip[12] = 0xFFU;
    a->ip[13] = 0xFFU;
    a->ip[14] = 0xFFU;
    a->ip[15] = 0xFFU;
    a->port = (uint16_t)WFAS3_UDP_PORT;
}

static uint32_t wfas3_find_of(const wfas3_device *dev, uint32_t pair)
{
    uint32_t found = WFAS3_NONE;
    uint32_t i;

    for (i = 0U; i < WFAS3_MAX_FINDS; i++) {
        if ((dev->finds[i].state == 1U) && (dev->finds[i].pair == pair)) {
            found = i;
        }
    }
    return found;
}

/* Tag of a Probe or ProbeReply this device sends to the peer of p: K_probe
 * for the direction of travel, over bytes 0..28. */
static wfas3_status wfas3_probe_tag_out(uint8_t tag[WFAS3_TAG_LEN], const wfas3_pair *p,
                                        const uint8_t *d)
{
    uint8_t k[WFAS3_KEY_LEN];
    wfas3_status st = wfas3_dir_key(k, p->k_fix, WFAS3_LBL("probe "), p->is_low);

    if (st == WFAS3_OK) {
        st = wfas3_mac(tag, WFAS3_TAG_LEN, k, d, 28U);
    }
    wfas3_wipe(k, sizeof k);
    return st;
}

/* The next Probe of search f. On failure nothing has changed. */
static wfas3_status wfas3_probe_send(wfas3_device *dev, wfas3_search *f, wfas3_out *out)
{
    wfas3_pair *p = &dev->pairs[f->pair];
    uint8_t *d = out->dgram;
    uint8_t mask[8];
    uint32_t slot = f->next_slot % 3U;
    uint32_t i;
    wfas3_status st = wfas3_rng_bytes(&dev->rng, &d[4], 16U);

    if (st == WFAS3_OK) {
        d[0] = WFAS3_MARKER;
        d[1] = WFAS3_T_PROBE;
        d[2] = 0U;
        d[3] = 0U;
        st = wfas3_ctr_mask(mask, p, p->is_low, WFAS3_T_PROBE, &d[4]);
    }
    if (st == WFAS3_OK) {
        wfas3_wr_be64(&d[20], wfas3_ectr_next(dev, p, out));
        for (i = 0U; i < 8U; i++) {
            d[20U + i] = (uint8_t)(d[20U + i] ^ mask[i]);
        }
        st = wfas3_probe_tag_out(&d[28], p, d);
    }
    if (st == WFAS3_OK) {
        st = wfas3_mac1_out(&d[44], p, d, 44U);
    }
    if (st == WFAS3_OK) {
        if ((p->seen_valid == 1U) && ((f->tries % 2U) == 0U)) {
            out->to = p->seen_addr;                      /* Probes 1, 3, 5... */
        } else {
            wfas3_broadcast_addr(&out->to);
            out->broadcast = 1U;
        }
        out->len = WFAS3_LEN_PROBE;
        out->pair = f->pair;
        memcpy(f->nonce[slot], &d[4], 16U);
        memcpy(f->ctr[slot], &d[20], 8U);
        f->sent_ms[slot] = dev->now_ms;
        f->valid[slot] = 1U;
        f->next_slot = (slot + 1U) % 3U;
        f->tries += 1U;
        f->next_ms = dev->now_ms + (uint64_t)WFAS3_PROBE_GAP_MS;
        p->next_probe_ms = dev->now_ms + (uint64_t)WFAS3_PROBE_GAP_MS;
    } else {
        out->len = 0U;
        out->events = 0U;
    }
    wfas3_wipe(mask, sizeof mask);
    return st;
}

/* The answer to a Probe: nonce and ctr_masked echoed, to its source address. */
static wfas3_status wfas3_preply_build(const wfas3_device *dev, uint32_t pair, const uint8_t *probe,
                                       const wfas3_addr *from, wfas3_out *out)
{
    const wfas3_pair *p = &dev->pairs[pair];
    uint8_t *r = out->dgram;
    wfas3_status st;

    r[0] = WFAS3_MARKER;
    r[1] = WFAS3_T_PROBE_REPLY;
    r[2] = 0U;
    r[3] = 0U;
    memcpy(&r[4], &probe[4], 24U);
    st = wfas3_probe_tag_out(&r[28], p, r);
    if (st == WFAS3_OK) {
        st = wfas3_mac1_out(&r[44], p, r, 44U);
    }
    if (st == WFAS3_OK) {
        out->len = WFAS3_LEN_PROBE;
        out->to = *from;
        out->pair = pair;
    }
    return st;
}

/* Receiver of a Probe: mac1, tag, counter against probe_seen (12). */
static wfas3_status wfas3_probe_handle(wfas3_device *dev, const uint8_t *d, const wfas3_addr *from,
                                       wfas3_out *out)
{
    uint64_t cand = wfas3_mac1_candidates(dev, d, 44U);
    uint32_t pair = WFAS3_NONE;
    uint32_t answer = 0U;
    uint8_t mask[8];
    uint8_t raw[8];
    uint32_t i;
    wfas3_status st = WFAS3_E_DROP;

    if (cand != 0U) {
        pair = wfas3_tag_select(dev, cand, d, 28U, &d[28], 1U);
    }
    if ((pair != WFAS3_NONE) &&
        (wfas3_ctr_mask(mask, &dev->pairs[pair], 1U ^ dev->pairs[pair].is_low, WFAS3_T_PROBE,
                        &d[4]) == WFAS3_OK)) {
        wfas3_pair *p = &dev->pairs[pair];
        uint64_t ctr;

        for (i = 0U; i < 8U; i++) {
            raw[i] = (uint8_t)(d[20U + i] ^ mask[i]);
        }
        ctr = wfas3_rd_be64(raw);
        if (ctr > p->probe_seen) {
            p->probe_seen = ctr;                         /* at once, whatever follows (7.3) */
            p->seen_dirty = 1U;
            if ((dev->now_ms >= p->next_preply_ms) &&
                (wfas3_rate_take(&dev->probe_win_ms, &dev->probe_count, dev->now_ms,
                                 WFAS3_CAP_PROBE) == 1U)) {
                p->next_preply_ms = dev->now_ms + (uint64_t)WFAS3_PROBE_GAP_MS;
                answer = 1U;
            }
        } else if (ctr < p->probe_seen) {
            /* stale: its own budget, apart from fresh answers and stale notices */
            if ((dev->now_ms >= p->next_pstale_ms) &&
                (wfas3_rate_take(&dev->pstale_win_ms, &dev->pstale_count, dev->now_ms,
                                 WFAS3_CAP_PSTALE) == 1U)) {
                p->next_pstale_ms = dev->now_ms + (uint64_t)WFAS3_STALE_GAP_MS;
                answer = 1U;
            }
        } else {
            /* equal: a copy of a Probe already seen; no answer of any kind */
        }
        if ((answer == 1U) && (wfas3_preply_build(dev, pair, d, from, out) == WFAS3_OK)) {
            st = WFAS3_OK;
        }
    }
    wfas3_wipe(mask, sizeof mask);
    wfas3_wipe(raw, sizeof raw);
    return st;
}

/* Receiver of a ProbeReply: nonce and ctr_masked of a Probe of a running
 * search sent in the last 4 s, mac1, tag (12). */
static wfas3_status wfas3_preply_handle(wfas3_device *dev, const uint8_t *d, const wfas3_addr *from,
                                        wfas3_out *out)
{
    uint8_t m[WFAS3_TAG_LEN];
    uint32_t fi = WFAS3_NONE;
    uint32_t i;
    uint32_t j;
    wfas3_status st = WFAS3_E_DROP;

    for (i = 0U; i < WFAS3_MAX_FINDS; i++) {
        const wfas3_search *f = &dev->finds[i];

        for (j = 0U; j < 3U; j++) {
            if ((f->state == 1U) && (f->valid[j] == 1U) && (fi == WFAS3_NONE) &&
                ((dev->now_ms - f->sent_ms[j]) <= (uint64_t)WFAS3_PROBE_NONCE_MS) &&
                ((wfas3_ct_eq(f->nonce[j], &d[4], 16U) & wfas3_ct_eq(f->ctr[j], &d[20], 8U)) == 1U)) {
                fi = i;
            }
        }
    }
    if (fi != WFAS3_NONE) {
        uint32_t pair = dev->finds[fi].pair;
        wfas3_pair *p = &dev->pairs[pair];

        if ((((wfas3_mac1_candidates(dev, d, 44U) >> pair) & 1U) == 1U) &&
            (wfas3_rate_take(&dev->tag_win_ms, &dev->tag_count, dev->now_ms, WFAS3_CAP_TAGS) == 1U) &&
            (wfas3_mac(m, sizeof m, p->k_probe_in, d, 28U) == WFAS3_OK) &&
            (wfas3_ct_eq(m, &d[28], sizeof m) == 1U)) {
            p->seen_addr = *from;
            p->seen_valid = 1U;
            wfas3_find_free(&dev->finds[fi]);            /* the nonces are forgotten */
            out->events |= WFAS3_EV_FOUND;
            out->pair = pair;
            out->addr = *from;
            st = WFAS3_OK;
        }
    }
    return st;
}

wfas3_status wfas3_find(wfas3_device *dev, uint32_t pair, uint32_t on, wfas3_out *out)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (out != NULL) && (dev->started == 1U) && (pair < WFAS3_MAX_PAIRS) &&
        (dev->pairs[pair].used == 1U) && (on <= 1U)) {
        uint32_t fi = wfas3_find_of(dev, pair);
        uint32_t i;

        wfas3_out_clear(out);
        if (on == 0U) {
            if (fi != WFAS3_NONE) {
                wfas3_find_free(&dev->finds[fi]);
            }
            st = WFAS3_OK;
        } else if (fi != WFAS3_NONE) {
            st = WFAS3_E_STATE;
        } else if (wfas_tp_drbg_left(&dev->rng.drbg) < WFAS3_RNG_MARGIN) {
            st = WFAS3_E_ENTROPY;
        } else {
            st = WFAS3_E_FULL;
            for (i = 0U; (i < WFAS3_MAX_FINDS) && (fi == WFAS3_NONE); i++) {
                if (dev->finds[i].state == 0U) {
                    fi = i;
                }
            }
            if (fi != WFAS3_NONE) {
                wfas3_search *f = &dev->finds[fi];

                f->state = 1U;
                f->pair = pair;
                f->next_ms = dev->pairs[pair].next_probe_ms;
                st = WFAS3_OK;
                if (dev->now_ms >= dev->pairs[pair].next_probe_ms) {
                    st = wfas3_probe_send(dev, f, out);
                    if (st != WFAS3_OK) {
                        wfas3_find_free(f);              /* as before the call */
                    }
                }
            }
        }
    }
    return st;
}


/* -- Pairing: constants and helpers (specification 13) ---------------------- */

#define WFAS3_PS_FREE          0U
#define WFAS3_PS_S2            1U   /* step 2 sent, waiting step 3              */
#define WFAS3_PS_S4            2U   /* step 4 sent, waiting step 5              */
#define WFAS3_PS_ASK           3U   /* step 6 sent, waiting the user's answer   */
#define WFAS3_PS_S7            4U   /* step 7 sent, waiting step 8              */
#define WFAS3_PS_DONE          5U   /* step 9 sent, answering copies of step 8  */

#define WFAS3_PC_FREE          0U
#define WFAS3_PC_BEACON        1U   /* code or label: waiting for its beacon    */
#define WFAS3_PC_S1            2U   /* step 1 sent, waiting step 2              */
#define WFAS3_PC_S3            3U
#define WFAS3_PC_S5            4U
#define WFAS3_PC_S6            5U   /* step 6 received, waiting step 7          */
#define WFAS3_PC_CONFIRM       6U   /* step 7 received, waiting the user (PIN)  */
#define WFAS3_PC_S8            7U

#define WFAS3_PAIR_WINDOW_MS   120000U
#define WFAS3_PAIR_RETRY_MS    1000U
#define WFAS3_PAIR_STEP_MS     10000U   /* a step the peer sends without asking */
#define WFAS3_PAIR_ASK_MS      60000U   /* a question to a user                 */
#define WFAS3_PAIR_USER_MS     70000U   /* a step that may wait for the other user */
#define WFAS3_PAIR_WAIT2_MS    30000U   /* client: step 2 from a busy server    */
#define WFAS3_PAIR_BAD2_MS     5000U    /* client: after a step 2 that did not open */
#define WFAS3_PAIR_DONE_MS     10000U   /* server: copies of step 8 answered    */
#define WFAS3_PAIR_S1_CAP      4U       /* step 1 served per second (13.7)      */
#define WFAS3_PAIR_SRC         8U       /* source addresses remembered          */
#define WFAS3_PAIR_TURN_MS     2000U    /* an address still waiting its turn    */
#define WFAS3_PAIR_NAME        "WFAS3_PAIR_25519+MLKEM768+CPace_ChaChaPoly_SHA256"

typedef char wfas3_assert_pctx[(WFAS3_PAIR_CTX >= 1U) ? 1 : -1];

/* Length of each step, 13.4. Index 0 is unused. */
static const uint16_t wfas3_pair_len[10] = {
    0U, 1368U, 1296U, 176U, 80U, 80U, 80U, 120U, 100U, 52U
};

/* KDF(k, label) of section 2 for a 16-byte k (the secret, the window nonce):
 * HKDF-Expand with L = 32 is one HMAC of info || 0x01. */
static wfas3_status wfas3_kdf_k16(uint8_t out[WFAS3_KEY_LEN], const uint8_t key[16],
                                  const char *label, size_t label_len)
{
    static const uint8_t prefix[6] = { 0x57U, 0x46U, 0x41U, 0x53U, 0x33U, 0x20U };
    uint8_t info[48];
    wfas3_status st = WFAS3_E_ARG;
    size_t i;

    if (label_len <= 40U) {
        memcpy(info, prefix, sizeof prefix);
        for (i = 0U; i < label_len; i++) {
            info[sizeof prefix + i] = (uint8_t)label[i];
        }
        info[sizeof prefix + label_len] = 0x01U;
        wfas_tp_hmac_sha256(out, key, 16U, info, (uint32_t)(sizeof prefix + label_len + 1U));
        st = WFAS3_OK;
    }
    return st;
}

/* out = SHA-256(label || a || b || c), each part at most 32 bytes. */
static void wfas3_hash3(uint8_t out[WFAS3_HASH_LEN], const char *label, size_t label_len,
                        const uint8_t *a, size_t a_len, const uint8_t *b, size_t b_len,
                        const uint8_t *c, size_t c_len)
{
    uint8_t buf[128];
    size_t pos;
    size_t i;

    memset(out, 0, WFAS3_HASH_LEN);
    if ((label_len <= 32U) && (a_len <= 32U) && (b_len <= 32U) && (c_len <= 32U)) {
        for (i = 0U; i < label_len; i++) {
            buf[i] = (uint8_t)label[i];
        }
        pos = label_len;
        if (a_len > 0U) {
            memcpy(&buf[pos], a, a_len);
            pos += a_len;
        }
        if (b_len > 0U) {
            memcpy(&buf[pos], b, b_len);
            pos += b_len;
        }
        if (c_len > 0U) {
            memcpy(&buf[pos], c, c_len);
            pos += c_len;
        }
        wfas_tp_sha256(out, buf, (uint32_t)pos);
    }
    wfas3_wipe(buf, sizeof buf);
}

/* fp = SHA-256("WFAS3 pairing key" || W_public)[0..16] (13.1). */
static void wfas3_w_fp(uint8_t fp[16], const uint8_t w_pub[32])
{
    uint8_t h[WFAS3_HASH_LEN];

    wfas3_hash3(h, WFAS3_LBL("WFAS3 pairing key"), w_pub, 32U, NULL, 0U, NULL, 0U);
    memcpy(fp, h, 16U);
}

/* 1 if a == b, for values below 2^31, without a branch. */
static uint32_t wfas3_eq_u32(uint32_t a, uint32_t b)
{
    return (((a ^ b) - 1U) >> 31U) & 1U;
}

/* 1 if lo <= x <= hi, for values below 2^31, without a branch. */
static uint32_t wfas3_in_range(uint32_t x, uint32_t lo, uint32_t hi)
{
    return 1U ^ ((((x - lo) | (hi - x)) >> 31U) & 1U);
}

/* -- Pairing code text (13.1) --------------------------------------------------
 * 260 bits, read 5 at a time from the most significant bit of byte 0: the
 * 32 bytes of the code, then the top 4 bits of SHA-256("WFAS3 code check" ||
 * code). The code holds the secret: no table and no branch on its value. */

static uint32_t wfas3_code_sym(const uint8_t bits[33], uint32_t i)
{
    uint32_t bit = 5U * i;
    uint32_t byte = bit / 8U;
    uint32_t w = ((uint32_t)bits[byte] << 8U) | ((byte < 32U) ? (uint32_t)bits[byte + 1U] : 0U);

    return (w >> (11U - (bit % 8U))) & 31U;
}

static void wfas3_code_put(uint8_t bits[33], uint32_t i, uint32_t v)
{
    uint32_t bit = 5U * i;
    uint32_t byte = bit / 8U;
    uint32_t w = (v & 31U) << (11U - (bit % 8U));

    bits[byte] = (uint8_t)((uint32_t)bits[byte] | (w >> 8U));
    if (byte < 32U) {
        bits[byte + 1U] = (uint8_t)((uint32_t)bits[byte + 1U] | (w & 0xFFU));
    }
}

/* Crockford base32: 0..9, then A..Z without I, L, O, U. */
static uint8_t wfas3_b32_char(uint32_t v)
{
    uint32_t c = v + 0x30U;

    c += 7U & (0U - (((9U - v) >> 31U) & 1U));          /* v >= 10: letters */
    c += ((17U - v) >> 31U) & 1U;                         /* v >= 18: after I */
    c += ((19U - v) >> 31U) & 1U;                         /* v >= 20: after L */
    c += ((21U - v) >> 31U) & 1U;                         /* v >= 22: after O */
    c += ((26U - v) >> 31U) & 1U;                         /* v >= 27: after U */
    return (uint8_t)c;
}

/* Value of a character read; *ok = 0 if it is not a code character. O is
 * read as 0, I and L as 1, lower case as upper case. */
static uint32_t wfas3_b32_value(uint32_t c, uint32_t *ok)
{
    uint32_t u = c & 0xDFU;
    uint32_t d = wfas3_in_range(c, 0x30U, 0x39U);
    uint32_t r1 = wfas3_in_range(u, 0x41U, 0x48U);
    uint32_t r2 = wfas3_in_range(u, 0x4AU, 0x4BU);
    uint32_t r3 = wfas3_in_range(u, 0x4DU, 0x4EU);
    uint32_t r4 = wfas3_in_range(u, 0x50U, 0x54U);
    uint32_t r5 = wfas3_in_range(u, 0x56U, 0x5AU);
    uint32_t one = wfas3_eq_u32(u, 0x49U) | wfas3_eq_u32(u, 0x4CU);
    uint32_t zero = wfas3_eq_u32(u, 0x4FU);
    uint32_t v = ((0U - d) & (c - 0x30U)) | ((0U - r1) & (u - 0x37U)) | ((0U - r2) & (u - 0x38U)) |
                 ((0U - r3) & (u - 0x39U)) | ((0U - r4) & (u - 0x3AU)) | ((0U - r5) & (u - 0x3BU)) | one;

    *ok = d | r1 | r2 | r3 | r4 | r5 | one | zero;
    return v & 31U;
}

wfas3_status wfas3_code_format(const uint8_t *code, uint32_t qr, char *text, size_t cap)
{
    static const char qr_prefix[6] = { 'W', 'F', 'A', 'S', '3', ':' };
    uint8_t bits[33];
    uint8_t h[WFAS3_HASH_LEN];
    size_t pos = 0U;
    uint32_t i;
    wfas3_status st = WFAS3_E_ARG;

    if ((code != NULL) && (text != NULL) && (qr <= 1U) &&
        (cap >= ((qr == 1U) ? (size_t)WFAS3_CODE_QR_LEN : (size_t)WFAS3_CODE_TEXT_LEN))) {
        memcpy(bits, code, 32U);
        wfas3_hash3(h, WFAS3_LBL("WFAS3 code check"), code, 32U, NULL, 0U, NULL, 0U);
        bits[32] = (uint8_t)(h[0] & 0xF0U);
        if (qr == 1U) {
            memcpy(text, qr_prefix, sizeof qr_prefix);
            pos = sizeof qr_prefix;
        }
        for (i = 0U; i < 52U; i++) {
            if ((qr == 0U) && (i > 0U) && ((i % 4U) == 0U)) {
                text[pos] = '-';
                pos += 1U;
            }
            text[pos] = (char)wfas3_b32_char(wfas3_code_sym(bits, i));
            pos += 1U;
        }
        text[pos] = '\0';
        wfas3_wipe(bits, sizeof bits);
        wfas3_wipe(h, sizeof h);
        st = WFAS3_OK;
    }
    return st;
}

/* Length of an optional "WFAS3:" prefix, any case. */
static size_t wfas3_code_prefix(const char *text, size_t len)
{
    static const uint8_t want[6] = { 0x57U, 0x46U, 0x41U, 0x53U, 0x33U, 0x3AU };
    uint32_t same = 1U;
    uint32_t i;

    if (len < 6U) {
        same = 0U;
    }
    for (i = 0U; (i < 6U) && (same == 1U); i++) {
        uint32_t c = (uint32_t)(uint8_t)text[i];

        if ((c != (uint32_t)want[i]) && ((c & 0xDFU) != (uint32_t)want[i])) {
            same = 0U;
        }
    }
    return (same == 1U) ? 6U : 0U;
}

wfas3_status wfas3_code_parse(const char *text, size_t len, uint8_t *code)
{
    uint8_t bits[33];
    uint8_t h[WFAS3_HASH_LEN];
    uint32_t n = 0U;
    uint32_t bad = 0U;
    uint32_t ok = 0U;
    size_t i;
    wfas3_status st = WFAS3_E_ARG;

    if ((text != NULL) && (code != NULL) && (len <= 256U)) {
        memset(bits, 0, sizeof bits);
        for (i = wfas3_code_prefix(text, len); i < len; i++) {
            uint32_t c = (uint32_t)(uint8_t)text[i];

            if ((c == 0x2DU) || (c == 0x20U)) {
                /* a separator */
            } else if (n < 52U) {
                uint32_t v = wfas3_b32_value(c, &ok);

                bad |= 1U ^ ok;
                wfas3_code_put(bits, n, v);
                n += 1U;
            } else {
                bad = 1U;
            }
        }
        if (n == 52U) {
            wfas3_hash3(h, WFAS3_LBL("WFAS3 code check"), bits, 32U, NULL, 0U, NULL, 0U);
            bad |= ((((uint32_t)h[0] ^ (uint32_t)bits[32]) & 0xF0U) + 0xFFU) >> 8U;
            if (bad == 0U) {
                memcpy(code, bits, 32U);
                st = WFAS3_OK;
            }
        }
        wfas3_wipe(bits, sizeof bits);
        wfas3_wipe(h, sizeof h);
    }
    return st;
}

/* -- Declared names (13.2) ----------------------------------------------------- */

/* Code points removed before display: controls, and format and direction
 * characters that can hide or reorder text. */
static uint32_t wfas3_cp_hidden(uint32_t cp)
{
    return ((cp <= 0x1FU) || ((cp >= 0x7FU) && (cp <= 0x9FU)) || (cp == 0xADU) ||
            (cp == 0x61CU) || (cp == 0x180EU) || ((cp >= 0x200BU) && (cp <= 0x200FU)) ||
            ((cp >= 0x2028U) && (cp <= 0x202EU)) || ((cp >= 0x2060U) && (cp <= 0x206FU)) ||
            (cp == 0xFEFFU) || ((cp >= 0xFFF9U) && (cp <= 0xFFFBU)) ||
            ((cp >= 0xE0000U) && (cp <= 0xE007FU))) ? 1U : 0U;
}

/* Length (1 to 4) of the well-formed UTF-8 sequence at n[i] within len
 * bytes (RFC 3629: no overlong form, no surrogate, at most U+10FFFF), with
 * its code point; 0 if it is not well formed. */
static uint32_t wfas3_utf8_seq(const uint8_t *n, size_t i, size_t len, uint32_t *cp)
{
    uint32_t b0 = n[i];
    uint32_t want = 0U;
    uint32_t lo = 0x80U;
    uint32_t hi = 0xBFU;
    uint32_t k;
    uint32_t v;

    if (b0 < 0x80U) {
        want = 1U;
    } else if ((b0 >= 0xC2U) && (b0 <= 0xDFU)) {
        want = 2U;
    } else if ((b0 >= 0xE0U) && (b0 <= 0xEFU)) {
        want = 3U;
        lo = (b0 == 0xE0U) ? 0xA0U : 0x80U;
        hi = (b0 == 0xEDU) ? 0x9FU : 0xBFU;
    } else if ((b0 >= 0xF0U) && (b0 <= 0xF4U)) {
        want = 4U;
        lo = (b0 == 0xF0U) ? 0x90U : 0x80U;
        hi = (b0 == 0xF4U) ? 0x8FU : 0xBFU;
    } else {
        want = 0U;
    }
    v = (want == 1U) ? b0 : (b0 & (0x7FU >> want));
    if ((want > 1U) && ((i + want) > len)) {
        want = 0U;
    }
    for (k = 1U; (k < want) && (k < 4U); k++) {
        uint32_t b = n[i + k];

        if ((b < ((k == 1U) ? lo : 0x80U)) || (b > ((k == 1U) ? hi : 0xBFU))) {
            want = 0U;
        } else {
            v = (v << 6U) | (b & 0x3FU);
        }
    }
    *cp = v;
    return want;
}

void wfas3_name_clean(const uint8_t *name, char *text)
{
    size_t i = 0U;
    size_t o = 0U;
    uint32_t cp = 0U;
    uint32_t n;
    uint32_t k;

    if ((name != NULL) && (text != NULL)) {
        while ((i < (size_t)WFAS3_NAME_LEN) && (name[i] != 0U)) {
            n = wfas3_utf8_seq(name, i, WFAS3_NAME_LEN, &cp);
            if ((n == 0U) || (cp == 0U)) {
                text[o] = '?';
                o += 1U;
                i += 1U;
            } else {
                if (wfas3_cp_hidden(cp) == 0U) {
                    for (k = 0U; k < n; k++) {
                        text[o + k] = (char)name[i + k];
                    }
                    o += n;
                }
                i += n;
            }
        }
        text[o] = '\0';
    }
}

/* -- Labels of headless devices (5, 13.1) -------------------------------------- */

wfas3_status wfas3_label_create(wfas3_device *dev, uint8_t *record)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (record != NULL) && (dev->started == 1U)) {
        st = wfas3_rng_bytes(&dev->rng, record, 32U);
        if (st == WFAS3_OK) {
            st = wfas3_rng_bytes(&dev->rng, &record[32], 16U);
        }
        if (st != WFAS3_OK) {
            wfas3_wipe(record, WFAS3_LABEL_RECORD_LEN);
        }
    }
    return st;
}

wfas3_status wfas3_label_load(wfas3_device *dev, const uint8_t *record)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (record != NULL) && (dev->started == 1U) && (dev->win.open == 0U)) {
        memcpy(dev->label_priv, record, 32U);
        memcpy(dev->label_secret, &record[32], 16U);
        dev->label_ok = 1U;
        st = WFAS3_OK;
    }
    return st;
}

wfas3_status wfas3_label_code(const wfas3_device *dev, uint8_t *code)
{
    uint8_t pub[32];
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (code != NULL) && (dev->started == 1U)) {
        st = WFAS3_E_STATE;
        if (dev->label_ok == 1U) {
            wfas_tp_x25519_public(pub, dev->label_priv);
            memcpy(code, dev->label_secret, 16U);
            wfas3_w_fp(&code[16], pub);
            st = WFAS3_OK;
        }
    }
    return st;
}

/* Eight random decimal digits, without a branch or an index on their value:
 * every nibble below 10 is a digit until eight are taken. */
static wfas3_status wfas3_pin_new(wfas3_device *dev, uint8_t pin[8])
{
    uint8_t r[64];
    uint32_t count = 0U;
    uint32_t round;
    uint32_t i;
    uint32_t j;
    wfas3_status st = WFAS3_OK;

    memset(pin, 0, 8U);
    for (round = 0U; (round < 2U) && (st == WFAS3_OK); round++) {
        st = wfas3_rng_bytes(&dev->rng, r, sizeof r);
        for (i = 0U; (i < 128U) && (st == WFAS3_OK); i++) {
            uint32_t v = ((uint32_t)r[i / 2U] >> (4U * (i % 2U))) & 15U;
            uint32_t take = (((v - 10U) >> 31U) & 1U) & (((count - 8U) >> 31U) & 1U);

            for (j = 0U; j < 8U; j++) {
                uint32_t m = 0U - (take & wfas3_eq_u32(count, j));

                pin[j] = (uint8_t)(((uint32_t)pin[j] & ~m) | ((0x30U + v) & m));
            }
            count += take;
        }
    }
    if ((st == WFAS3_OK) && (count < 8U)) {
        st = WFAS3_E_ENTROPY;                            /* 2^-100 or less */
    }
    if (st != WFAS3_OK) {
        wfas3_wipe(pin, 8U);
    }
    wfas3_wipe(r, sizeof r);
    return st;
}

/* -- Pairing: datagrams (13.4) ------------------------------------------------ */

/* A3 13 00 00 | step | mode | 00 00 | pair_sid. */
static void wfas3_pair_head(uint8_t *d, uint32_t step, uint32_t mode, const uint8_t sid[8])
{
    d[0] = WFAS3_MARKER;
    d[1] = WFAS3_T_PAIR;
    d[2] = 0U;
    d[3] = 0U;
    d[4] = (uint8_t)step;
    d[5] = (uint8_t)mode;
    d[6] = 0U;
    d[7] = 0U;
    memcpy(&d[8], sid, 8U);
}

static uint32_t wfas3_pair_filter_ok(const uint8_t *d, size_t len, const uint8_t k_f[WFAS3_KEY_LEN])
{
    uint8_t m[WFAS3_TAG_LEN];

    return ((wfas3_mac(m, sizeof m, k_f, d, len - (size_t)WFAS3_TAG_LEN) == WFAS3_OK) &&
            (wfas3_ct_eq(m, &d[len - (size_t)WFAS3_TAG_LEN], sizeof m) == 1U)) ? 1U : 0U;
}

/* Steps 4 to 9: header, Seal(k, step, bytes 0..16, pt), filter. */
static wfas3_status wfas3_pair_seal(uint8_t *d, uint32_t step, uint32_t mode, const uint8_t sid[8],
                                    const uint8_t k[WFAS3_KEY_LEN], const uint8_t k_f[WFAS3_KEY_LEN],
                                    const uint8_t *pt, size_t pt_len)
{
    size_t len = wfas3_pair_len[step];
    wfas3_status st;

    wfas3_pair_head(d, step, mode, sid);
    st = wfas3_seal(&d[16], len - 32U, k, (uint64_t)step, d, 16U, pt, pt_len);
    if (st == WFAS3_OK) {
        st = wfas3_mac(&d[len - 16U], WFAS3_TAG_LEN, k_f, d, len - 16U);
    }
    return st;
}

static wfas3_status wfas3_pair_open(uint8_t *pt, size_t pt_cap, const uint8_t *d, uint32_t step,
                                    const uint8_t k[WFAS3_KEY_LEN])
{
    size_t len = wfas3_pair_len[step];

    return wfas3_open(pt, pt_cap, k, (uint64_t)step, d, 16U, &d[16], len - 32U);
}

/* Init(name), then MixHash(mode || pair_sid || window_nonce) (13.3). */
static wfas3_status wfas3_pair_sym(wfas3_sym *s, uint32_t mode, const uint8_t sid[8],
                                   const uint8_t nonce[16])
{
    uint8_t ctx[25];
    wfas3_status st = wfas3_sym_init(s, WFAS3_LBL(WFAS3_PAIR_NAME));

    ctx[0] = (uint8_t)mode;
    memcpy(&ctx[1], sid, 8U);
    memcpy(&ctx[9], nonce, 16U);
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(s, ctx, sizeof ctx);
    }
    return st;
}

/* CPace generator of a run: CI = "WFAS3-PAIR" || mode, sid = pair_sid ||
 * window_nonce. */
static wfas3_status wfas3_pair_gen(uint8_t g[32], const uint8_t *prs, size_t prs_len, uint32_t mode,
                                   const uint8_t sid[8], const uint8_t nonce[16], uint8_t sid24[24])
{
    uint8_t ci[11] = { 0x57U, 0x46U, 0x41U, 0x53U, 0x33U, 0x2DU, 0x50U, 0x41U, 0x49U, 0x52U, 0x00U };

    ci[10] = (uint8_t)mode;
    memcpy(sid24, sid, 8U);
    memcpy(&sid24[8], nonce, 16U);
    return wfas3_cpace_gen(g, prs, prs_len, ci, sizeof ci, sid24, 24U);
}

/* Keys after the third message (13.3). */
static wfas3_status wfas3_pair_keys(const wfas3_sym *s, uint8_t c2s[32], uint8_t s2c[32],
                                    uint8_t k_fix[32], uint8_t psk0[32], uint8_t fp[16])
{
    wfas3_status st = wfas3_kdf(c2s, WFAS3_KEY_LEN, s->ck, WFAS3_LBL("pair c2s"), NULL, 0U);

    if (st == WFAS3_OK) {
        st = wfas3_kdf(s2c, WFAS3_KEY_LEN, s->ck, WFAS3_LBL("pair s2c"), NULL, 0U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_kdf(k_fix, WFAS3_KEY_LEN, s->ck, WFAS3_LBL("pair fixed"), NULL, 0U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_kdf(psk0, WFAS3_KEY_LEN, s->ck, WFAS3_LBL("pair psk"), NULL, 0U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_kdf(fp, 16U, s->ck, WFAS3_LBL("pair fingerprint"), NULL, 0U);
    }
    return st;
}

/* The code compared by the users: u64(SHA-256("WFAS3 sas" || h || n_c ||
 * n_s)[0..8]) mod 1,000,000. Not a secret. */
static uint32_t wfas3_pair_sas(const uint8_t h[32], const uint8_t n_c[32], const uint8_t n_s[32])
{
    uint8_t d[WFAS3_HASH_LEN];

    wfas3_hash3(d, WFAS3_LBL("WFAS3 sas"), h, 32U, n_c, 32U, n_s, 32U);
    return (uint32_t)(wfas3_rd_be64(d) % 1000000U);
}

/* replaces = MAC16(K_replace of pair p, "replace" || h) (13.5). */
static wfas3_status wfas3_pair_replaces(uint8_t out[16], const wfas3_pair *p, const uint8_t h[32])
{
    uint8_t k[WFAS3_KEY_LEN];
    uint8_t m[39] = { 0x72U, 0x65U, 0x70U, 0x6CU, 0x61U, 0x63U, 0x65U };
    wfas3_status st = wfas3_kdf(k, sizeof k, p->k_fix, WFAS3_LBL("replace"), NULL, 0U);

    memcpy(&m[7], h, 32U);
    if (st == WFAS3_OK) {
        st = wfas3_mac(out, 16U, k, m, sizeof m);
    }
    wfas3_wipe(k, sizeof k);
    return st;
}

static uint32_t wfas3_pair_slot_free(const wfas3_device *dev)
{
    uint32_t found = WFAS3_NONE;
    uint32_t i;

    for (i = 0U; i < WFAS3_MAX_PAIRS; i++) {
        if ((dev->pairs[i].used == 0U) && (found == WFAS3_NONE)) {
            found = i;
        }
    }
    return found;
}

/* -- Pairing: the new pair (13.4) ------------------------------------------- */

typedef struct wfas3_pnew {
    uint8_t  s_priv[32];
    uint8_t  peer_pub[32];
    uint8_t  k_fix[32];
    uint8_t  psk0[32];
    uint8_t  fp[16];
    uint8_t  peer_entry[32];
    uint8_t  own_entry[32];
    uint8_t  name[32];
    uint32_t perms;
    uint32_t guest;
} wfas3_pnew;

/* The record of a pair just made: report slots base = last = (pair
 * fingerprint, 0), seq_hi 0, counters 0 (9.3). */
static wfas3_status wfas3_pair_create(wfas3_device *dev, uint32_t slot, const wfas3_pnew *n,
                                      const wfas3_addr *peer, wfas3_out *out)
{
    wfas3_pair *p = &dev->pairs[slot];
    wfas3_status st;

    wfas3_ses_drop_pair(dev, slot, WFAS3_DROP_ALL, WFAS3_NONE);
    wfas3_find_drop_pair(dev, slot);
    wfas3_wipe((uint8_t *)p, sizeof *p);
    memcpy(p->s_priv, n->s_priv, 32U);
    memcpy(p->peer_pub, n->peer_pub, 32U);
    memcpy(p->k_fix, n->k_fix, 32U);
    memcpy(p->psk[0], n->psk0, 32U);
    memcpy(p->peer_entry, n->peer_entry, 32U);
    memcpy(p->own_entry_held, n->own_entry, 32U);
    memcpy(p->name, n->name, 32U);
    memcpy(p->rep_fp[WFAS3_REP_BASE], n->fp, 16U);
    memcpy(p->rep_fp[WFAS3_REP_LAST], n->fp, 16U);
    p->rep_has[WFAS3_REP_BASE] = 1U;
    p->rep_has[WFAS3_REP_LAST] = 1U;
    p->perms = n->perms & (WFAS3_PERM_RECEIVE | WFAS3_PERM_MICROPHONE);
    p->flags = (n->guest == 1U) ? WFAS3_PF_GUEST : 0U;
    st = wfas3_pair_setup(dev, p);
    if (st == WFAS3_OK) {
        p->unused_until_ms = dev->now_ms + (uint64_t)WFAS3_UNUSED_MS;
        p->guest_seen_ms = dev->now_ms;
        wfas3_store_pair(dev, slot, out);                /* not for a guest */
        out->events |= WFAS3_EV_PAIRED;
        out->pair = slot;
        memcpy(out->name, n->name, 32U);
        out->pflags = ((n->guest == 1U) ? WFAS3_PAIR_GUEST : 0U) |
                      (((p->perms & WFAS3_PERM_MICROPHONE) != 0U) ? WFAS3_PAIR_MIC : 0U);
        out->addr = *peer;
    }
    return st;
}

static void wfas3_pair_failed(wfas3_out *out, uint32_t why, uint32_t race)
{
    out->events |= WFAS3_EV_PAIR_FAILED | ((race == 1U) ? WFAS3_EV_PAIR_RACE : 0U);
    out->status = why;
}

/* -- Pairing: server (13.2 to 13.7) ------------------------------------------ */

static void wfas3_pctx_free(wfas3_pctx *c)
{
    wfas3_wipe((uint8_t *)c, sizeof *c);
    c->state = WFAS3_PS_FREE;
}

/* The datagram a context sends, to its client. */
static void wfas3_pctx_send(const wfas3_device *dev, wfas3_pctx *c, const uint8_t *d, size_t len,
                            wfas3_out *out)
{
    memcpy(out->dgram, d, len);
    out->len = len;
    out->to = c->addr;
    c->next_tx_ms = dev->now_ms + (uint64_t)WFAS3_PAIR_RETRY_MS;
}

/* A context ends without a pair; counted for 13.7 unless this side refused. */
static void wfas3_pctx_fail(wfas3_device *dev, wfas3_pctx *c, uint32_t why, uint32_t count,
                            wfas3_out *out)
{
    if (count == 1U) {
        dev->win.failed += 1U;
    }
    wfas3_pctx_free(c);
    wfas3_pair_failed(out, why, 0U);
}

static wfas3_status wfas3_beacon_build(const wfas3_device *dev, wfas3_out *out)
{
    const wfas3_pwin *w = &dev->win;
    uint8_t *d = out->dgram;
    wfas3_status st = WFAS3_OK;

    d[0] = WFAS3_MARKER;
    d[1] = WFAS3_T_PAIR_BEACON;
    d[2] = 0U;
    d[3] = 0U;
    d[4] = (uint8_t)w->mode;
    d[5] = (uint8_t)w->used;
    d[6] = 0U;
    d[7] = 0U;
    memcpy(&d[8], w->nonce, 16U);
    if (w->mode == 2U) {
        memcpy(&d[24], w->name, 32U);
        memset(&d[56], 0, 16U);
    } else {
        memset(&d[24], 0, 32U);
        st = wfas3_mac(&d[56], WFAS3_TAG_LEN, w->k_b, d, 56U);
    }
    if (st == WFAS3_OK) {
        out->len = WFAS3_LEN_PAIR_BEACON;
        wfas3_broadcast_addr(&out->to);
        out->broadcast = 1U;
    }
    return st;
}

/* Secret, pairing key W, nonce and keys of a new window; code to show. */
static wfas3_status wfas3_win_keys(wfas3_device *dev, uint32_t kind, uint8_t *code)
{
    wfas3_pwin *w = &dev->win;
    wfas3_status st = wfas3_rng_bytes(&dev->rng, w->nonce, 16U);   /* public */

    if ((st == WFAS3_OK) && (kind == WFAS3_PAIR_LABEL)) {
        memcpy(w->prs, dev->label_secret, 16U);
        memcpy(w->w_priv, dev->label_priv, 32U);
    } else if (st == WFAS3_OK) {
        st = (kind == WFAS3_PAIR_PIN) ? wfas3_pin_new(dev, w->prs) : wfas3_rng_bytes(&dev->rng, w->prs, 16U);
        if (st == WFAS3_OK) {
            st = wfas3_rng_bytes(&dev->rng, w->w_priv, 32U);
        }
    } else {
        /* no randomness */
    }
    w->prs_len = (kind == WFAS3_PAIR_PIN) ? WFAS3_PIN_LEN : 16U;
    if (st == WFAS3_OK) {
        wfas_tp_x25519_public(w->w_pub, w->w_priv);
        if (kind == WFAS3_PAIR_PIN) {
            st = wfas3_kdf_k16(w->k_f, w->nonce, WFAS3_LBL("pin filter"));
            memcpy(code, w->prs, WFAS3_PIN_LEN);
        } else {
            st = wfas3_kdf_k16(w->k_f, w->prs, WFAS3_LBL("pair filter"));
            if (st == WFAS3_OK) {
                st = wfas3_kdf_k16(w->k_b, w->prs, WFAS3_LBL("pair beacon"));
            }
            memcpy(code, w->prs, 16U);
            wfas3_w_fp(&code[16], w->w_pub);
        }
    }
    return st;
}

wfas3_status wfas3_window_open(wfas3_device *dev, const wfas3_window_req *req, uint8_t *code,
                               wfas3_out *out)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (req != NULL) && (code != NULL) && (out != NULL) && (dev->started == 1U) &&
        (req->kind >= WFAS3_PAIR_CODE) && (req->kind <= WFAS3_PAIR_PIN) && (req->ask <= 1U) &&
        (req->perms <= (WFAS3_PERM_RECEIVE | WFAS3_PERM_MICROPHONE))) {
        wfas3_out_clear(out);
        if ((dev->win.open == 1U) || (dev->pcli.state != WFAS3_PC_FREE) ||
            ((req->kind == WFAS3_PAIR_LABEL) && (dev->label_ok == 0U))) {
            st = WFAS3_E_STATE;
        } else if (wfas3_pair_slot_free(dev) == WFAS3_NONE) {
            st = WFAS3_E_FULL;
        } else if (wfas_tp_drbg_left(&dev->rng.drbg) < WFAS3_RNG_MARGIN) {
            st = WFAS3_E_ENTROPY;
        } else {
            wfas3_wipe((uint8_t *)&dev->win, sizeof dev->win);
            st = wfas3_win_keys(dev, req->kind, code);
            if (st == WFAS3_OK) {
                dev->win.kind = req->kind;
                dev->win.mode = (req->kind == WFAS3_PAIR_PIN) ? 2U : 1U;
                dev->win.ask = req->ask;
                dev->win.perms = req->perms;
                memcpy(dev->win.name, req->name, 32U);
                dev->win.opened_ms = dev->now_ms;
                dev->win.next_beacon_ms = dev->now_ms + (uint64_t)WFAS3_PAIR_RETRY_MS;
                dev->win.ask_next = 1U;
                st = wfas3_beacon_build(dev, out);
            }
            if (st == WFAS3_OK) {
                dev->win.open = 1U;
            } else {
                wfas3_wipe((uint8_t *)&dev->win, sizeof dev->win);
                wfas3_wipe(code, (req->kind == WFAS3_PAIR_PIN) ? WFAS3_PIN_LEN : WFAS3_CODE_LEN);
                wfas3_out_clear(out);
            }
        }
    }
    return st;
}

/* The window ends: every context is erased with it. */
static void wfas3_win_end(wfas3_device *dev, wfas3_out *out)
{
    uint32_t i;

    for (i = 0U; i < WFAS3_PAIR_CTX; i++) {
        wfas3_pctx_free(&dev->pctx[i]);
    }
    out->status = dev->win.used;
    wfas3_wipe((uint8_t *)&dev->win, sizeof dev->win);
    dev->win.open = 0U;
    out->events |= WFAS3_EV_WINDOW_CLOSED;
}

wfas3_status wfas3_window_close(wfas3_device *dev, wfas3_out *out)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (out != NULL) && (dev->started == 1U)) {
        wfas3_out_clear(out);
        st = WFAS3_E_STATE;
        if (dev->win.open == 1U) {
            wfas3_win_end(dev, out);
            st = WFAS3_OK;
        }
    }
    return st;
}

uint32_t wfas3_window_failed(const wfas3_device *dev)
{
    return ((dev != NULL) && (dev->win.open == 1U)) ? dev->win.failed : WFAS3_NONE;
}

/* Whether a step 1 from this address is served now (13.7): one per second
 * per address, WFAS3_PAIR_S1_CAP per second in total, a free context, and
 * no other address still waiting that was served less recently. */
static uint32_t wfas3_s1_admit(wfas3_device *dev, const wfas3_addr *from)
{
    wfas3_pwin *w = &dev->win;
    uint64_t now = dev->now_ms;
    uint32_t k = WFAS3_NONE;
    uint32_t free_ctx = 0U;
    uint32_t turn = 1U;
    uint32_t admit = 0U;
    uint32_t i;

    for (i = 0U; i < WFAS3_PAIR_SRC; i++) {
        if ((w->src_used[i] != 0U) && (wfas3_addr_eq(&w->src[i], from) == 1U)) {
            k = i;
        }
    }
    if (k == WFAS3_NONE) {
        k = 0U;
        for (i = 1U; i < WFAS3_PAIR_SRC; i++) {
            if ((w->src_used[i] == 0U) ||
                ((w->src_used[k] != 0U) && (w->src_seen_ms[i] < w->src_seen_ms[k]))) {
                k = (w->src_used[k] == 0U) ? k : i;
            }
        }
        w->src[k] = *from;
        w->src_used[k] = 1U;                             /* 1 seen, 2 served */
        w->src_waiting[k] = 0U;
    }
    w->src_seen_ms[k] = now;
    for (i = 0U; i < WFAS3_PAIR_CTX; i++) {
        free_ctx |= (dev->pctx[i].state == WFAS3_PS_FREE) ? 1U : 0U;
    }
    for (i = 0U; i < WFAS3_PAIR_SRC; i++) {
        if ((i != k) && (w->src_used[i] != 0U) && (w->src_waiting[i] == 1U) &&
            ((now - w->src_seen_ms[i]) <= (uint64_t)WFAS3_PAIR_TURN_MS) &&
            (((w->src_used[i] == 1U) && (w->src_used[k] == 2U)) ||
             ((w->src_used[i] == 2U) && (w->src_used[k] == 2U) &&
              (w->src_served_ms[i] < w->src_served_ms[k])))) {
            turn = 0U;                                   /* its turn first */
        }
    }
    if ((w->src_used[k] == 2U) && ((now - w->src_served_ms[k]) < (uint64_t)WFAS3_PAIR_RETRY_MS)) {
        admit = 0U;                                      /* one per second per address */
    } else if ((free_ctx == 1U) && (turn == 1U) &&
               (wfas3_rate_take(&w->s1_win_ms, &w->s1_count, now, WFAS3_PAIR_S1_CAP) == 1U)) {
        w->src_used[k] = 2U;
        w->src_served_ms[k] = now;
        w->src_waiting[k] = 0U;
        admit = 1U;
    } else {
        w->src_waiting[k] = 1U;
    }
    return admit;
}

/* Tokens e, ee, yb, cpace of the second message. */
static wfas3_status wfas3_ps_cpace(wfas3_device *dev, wfas3_pctx *c, const uint8_t *d, uint8_t *r)
{
    const wfas3_pwin *w = &dev->win;
    uint8_t yb[32];
    uint8_t g[32];
    uint8_t k[32];
    uint8_t isk[64];
    uint8_t sid24[24];
    wfas3_status st = wfas3_rng_bytes(&dev->rng, c->e_priv, 32U);

    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, yb, sizeof yb);
    }
    if (st == WFAS3_OK) {
        wfas_tp_x25519_public(&r[16], c->e_priv);
        st = wfas3_sym_mix_hash(&c->sym, &r[16], 32U);                       /* e */
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&c->sym, c->e_priv, &d[16]);                         /* ee */
    }
    if (st == WFAS3_OK) {
        st = wfas3_pair_gen(g, w->prs, w->prs_len, w->mode, c->sid, w->nonce, sid24);
    }
    if ((st == WFAS3_OK) && ((wfas_tp_x25519(&r[48], yb, g) & wfas_tp_x25519(k, yb, &d[1232])) != 1U)) {
        st = WFAS3_E_AUTH;                                                    /* K all zero */
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(&c->sym, &r[48], 32U);                       /* yb */
    }
    if (st == WFAS3_OK) {
        st = wfas3_cpace_isk(isk, sid24, sizeof sid24, k, &d[1232], &d[16], 32U, &r[48], &r[16], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key(&c->sym, isk, sizeof isk);                    /* cpace */
    }
    wfas3_wipe(yb, sizeof yb);
    wfas3_wipe(g, sizeof g);
    wfas3_wipe(k, sizeof k);
    wfas3_wipe(isk, sizeof isk);
    return st;
}

/* Step 2: e 32, yb 32, ekem1 1104, w 48, s 48, filter. */
static wfas3_status wfas3_ps_step2(wfas3_device *dev, wfas3_pctx *c, const uint8_t *d, uint8_t *r)
{
    const wfas3_pwin *w = &dev->win;
    uint8_t m[32];
    uint8_t ct[WFAS_TP_MLKEM_CT];
    uint8_t ss[32];
    uint8_t s_pub[32];
    wfas3_status st = (wfas_tp_mlkem768_check_pk(&d[48]) == 1U) ? WFAS3_OK : WFAS3_E_DROP;

    if (st == WFAS3_OK) {
        st = wfas3_pair_sym(&c->sym, w->mode, c->sid, w->nonce);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(&c->sym, &d[16], 32U);                       /* e */
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(&c->sym, &d[48], WFAS_TP_MLKEM_PK);          /* e1 */
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(&c->sym, &d[1232], 32U);                     /* ya */
    }
    if (st == WFAS3_OK) {
        wfas3_pair_head(r, 2U, w->mode, c->sid);
        st = wfas3_ps_cpace(dev, c, d, r);
    }
    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, m, sizeof m);
    }
    if (st == WFAS3_OK) {
        wfas_tp_mlkem768_encaps(ct, ss, &d[48], m);
        st = wfas3_sym_encrypt(&c->sym, &r[80], 1104U, ct, sizeof ct);      /* ekem1 */
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key(&c->sym, ss, sizeof ss);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_encrypt(&c->sym, &r[1184], 48U, w->w_pub, 32U);       /* w */
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&c->sym, w->w_priv, &d[16]);                         /* ew */
    }
    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, c->s_priv, 32U);
    }
    if (st == WFAS3_OK) {
        wfas_tp_x25519_public(s_pub, c->s_priv);
        st = wfas3_sym_encrypt(&c->sym, &r[1232], 48U, s_pub, 32U);          /* s */
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&c->sym, c->s_priv, &d[16]);                         /* es */
    }
    if (st == WFAS3_OK) {
        st = wfas3_mac(&r[1280], WFAS3_TAG_LEN, w->k_f, r, 1280U);
    }
    wfas3_wipe(m, sizeof m);
    wfas3_wipe(ss, sizeof ss);
    return st;
}

static uint32_t wfas3_pctx_find(const wfas3_device *dev, const uint8_t sid[8], const wfas3_addr *from)
{
    uint32_t found = WFAS3_NONE;
    uint32_t i;

    for (i = 0U; i < WFAS3_PAIR_CTX; i++) {
        const wfas3_pctx *c = &dev->pctx[i];

        if ((c->state != WFAS3_PS_FREE) && (wfas3_ct_eq(c->sid, sid, 8U) == 1U) &&
            (wfas3_addr_eq(&c->addr, from) == 1U)) {
            found = i;
        }
    }
    return found;
}

/* Step 1. A copy is answered with the stored step 2, once a second: until
 * step 3 the server never sends more than it receives. */
static wfas3_status wfas3_ps_step1(wfas3_device *dev, const uint8_t *d, const wfas3_addr *from,
                                   wfas3_out *out)
{
    uint8_t h[WFAS3_HASH_LEN];
    uint32_t ci = wfas3_pctx_find(dev, &d[8], from);
    uint32_t i;
    wfas3_status st = WFAS3_E_DROP;

    wfas_tp_sha256(h, d, WFAS3_LEN_INIT);
    if (wfas3_all_zero(&d[1264], 88U) == 0U) {
        st = WFAS3_E_DROP;
    } else if (ci != WFAS3_NONE) {
        wfas3_pctx *c = &dev->pctx[ci];

        if ((c->state == WFAS3_PS_S2) && (wfas3_ct_eq(h, c->rx_hash, sizeof h) == 1U) &&
            (dev->now_ms >= c->next_tx_ms)) {
            wfas3_pctx_send(dev, c, c->tx, c->tx_len, out);
            st = WFAS3_OK;
        }
    } else if ((dev->win.used == 1U) || (wfas3_pair_slot_free(dev) == WFAS3_NONE)) {
        st = WFAS3_E_DROP;                               /* one pairing per window */
    } else if (dev->entropy_fresh == 0U) {
        st = WFAS3_E_ENTROPY;                            /* nothing changed yet */
    } else if (wfas3_s1_admit(dev, from) == 1U) {
        ci = WFAS3_NONE;
        for (i = 0U; i < WFAS3_PAIR_CTX; i++) {
            if ((dev->pctx[i].state == WFAS3_PS_FREE) && (ci == WFAS3_NONE)) {
                ci = i;
            }
        }
        if (ci != WFAS3_NONE) {
            wfas3_pctx *c = &dev->pctx[ci];

            wfas3_pctx_free(c);
            memcpy(c->sid, &d[8], 8U);
            c->addr = *from;
            st = wfas3_ps_step2(dev, c, d, c->tx);
            if (st == WFAS3_OK) {
                dev->entropy_fresh = 0U;
                c->state = WFAS3_PS_S2;
                c->tx_len = 1296U;
                c->wait_ms = dev->now_ms;
                memcpy(c->rx_hash, h, sizeof h);
                wfas3_pctx_send(dev, c, c->tx, c->tx_len, out);
            } else {
                wfas3_pctx_free(c);
                st = WFAS3_E_DROP;
            }
        }
    } else {
        /* refused for now: the client sends it again in a second */
    }
    return st;
}

/* Step 3: s, se, payload; then step 4 with commit_s. */
static wfas3_status wfas3_ps_step3(wfas3_device *dev, wfas3_pctx *c, const uint8_t *d, wfas3_out *out)
{
    const wfas3_pwin *w = &dev->win;
    wfas3_sym t = c->sym;
    uint8_t pt[80];
    uint8_t commit_s[WFAS3_HASH_LEN];
    wfas3_status st = wfas3_sym_decrypt(&t, c->peer_s, 32U, &d[16], 48U);   /* s */

    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(&t, c->e_priv, c->peer_s);                          /* se */
    }
    if (st == WFAS3_OK) {
        memcpy(c->h3, t.h, 32U);
        st = wfas3_sym_decrypt(&t, pt, sizeof pt, &d[64], 96U);
    }
    if ((st == WFAS3_OK) && ((pt[32] > 3U) || (wfas3_all_zero(&pt[33], 15U) == 0U))) {
        st = WFAS3_E_DROP;
    }
    if (st == WFAS3_OK) {
        st = wfas3_pair_keys(&t, c->k_c2s, c->k_s2c, c->k_fix, c->psk0, c->fp);
    }
    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, c->n_s, 32U);
    }
    if (st == WFAS3_OK) {
        memcpy(c->h, t.h, 32U);
        wfas3_hash3(commit_s, WFAS3_LBL("WFAS3 sas commit s"), c->h, 32U, c->n_s, 32U, NULL, 0U);
        st = wfas3_pair_seal(c->tx, 4U, w->mode, c->sid, c->k_s2c, w->k_f, commit_s, 32U);
    }
    if (st == WFAS3_OK) {
        memcpy(c->name, pt, 32U);
        c->flags = pt[32];
        memcpy(c->commit_c, &pt[48], 32U);
        wfas3_sym_wipe(&c->sym);
        wfas3_wipe(c->e_priv, sizeof c->e_priv);
        c->state = WFAS3_PS_S4;
        c->tx_len = 80U;
        c->wait_ms = dev->now_ms;
        wfas3_pctx_send(dev, c, c->tx, c->tx_len, out);
    } else {
        wfas3_wipe(c->peer_s, sizeof c->peer_s);         /* a forgery: keep waiting */
        wfas3_wipe(c->h3, sizeof c->h3);
        st = WFAS3_E_DROP;
    }
    wfas3_sym_wipe(&t);
    wfas3_wipe(pt, sizeof pt);
    return st;
}

/* 1 if a question to the user is open for some context. */
static uint32_t wfas3_ask_open(const wfas3_device *dev)
{
    uint32_t open = 0U;
    uint32_t i;

    for (i = 0U; i < WFAS3_PAIR_CTX; i++) {
        if ((dev->pctx[i].state == WFAS3_PS_ASK) && (dev->pctx[i].asked == 1U) &&
            (dev->pctx[i].decided == 0U)) {
            open = 1U;
        }
    }
    return open;
}

/* Put the question of context c to the user (13.4: 60 s, no answer is no). */
static void wfas3_ask(wfas3_device *dev, wfas3_pctx *c, wfas3_out *out)
{
    c->asked = 1U;
    c->ask_id = dev->win.ask_next;
    dev->win.ask_next = (dev->win.ask_next == 0xFFFFFFFEU) ? 1U : (dev->win.ask_next + 1U);
    c->wait_ms = dev->now_ms;
    out->events |= WFAS3_EV_PAIR_ASK;
    out->ask = c->ask_id;
    out->pflags = c->flags;
    memcpy(out->name, c->name, 32U);
    out->code = (dev->win.mode == 2U) ? c->code : 0U;
}

/* Step 5: n_c against commit_c, the code, step 6; then the question. */
static wfas3_status wfas3_ps_step5(wfas3_device *dev, wfas3_pctx *c, const uint8_t *d, wfas3_out *out)
{
    const wfas3_pwin *w = &dev->win;
    uint8_t n_c[32];
    uint8_t want[WFAS3_HASH_LEN];
    wfas3_status st = wfas3_pair_open(n_c, sizeof n_c, d, 5U, c->k_c2s);

    if (st == WFAS3_OK) {
        wfas3_hash3(want, WFAS3_LBL("WFAS3 sas commit c"), c->h3, 32U, n_c, 32U, NULL, 0U);
        if (wfas3_ct_eq(want, c->commit_c, sizeof want) == 0U) {
            wfas3_pctx_fail(dev, c, WFAS3_PAIRF_PROTOCOL, 1U, out);
        } else {
            st = wfas3_pair_seal(c->tx, 6U, w->mode, c->sid, c->k_s2c, w->k_f, c->n_s, 32U);
            if (st == WFAS3_OK) {
                c->code = wfas3_pair_sas(c->h, n_c, c->n_s);
                memcpy(c->tx6, c->tx, 80U);
                c->state = WFAS3_PS_ASK;
                c->tx_len = 80U;
                c->wait_ms = dev->now_ms;
                wfas3_pctx_send(dev, c, c->tx, c->tx_len, out);
                if (w->ask == 0U) {
                    c->decided = 1U;                     /* the open window is the approval */
                    c->decision = 1U;
                    c->guest = c->flags & WFAS3_PAIR_GUEST;
                    c->perms = w->perms & (((c->flags & WFAS3_PAIR_MIC) != 0U) ? 0xFFU : ~WFAS3_PERM_MICROPHONE);
                } else if (wfas3_ask_open(dev) == 0U) {
                    wfas3_ask(dev, c, out);
                } else {
                    /* asked when the open question ends */
                }
            }
        }
        st = WFAS3_OK;
    }
    wfas3_wipe(n_c, sizeof n_c);
    return st;
}

/* Step 7: the decision. A refusal carries no name and no entry key. */
static wfas3_status wfas3_ps_step7(wfas3_device *dev, wfas3_pctx *c, wfas3_out *out)
{
    const wfas3_pwin *w = &dev->win;
    uint8_t pt[72];
    wfas3_status st = WFAS3_OK;

    memset(pt, 0, sizeof pt);
    if (c->decision == 1U) {
        if (c->guest == 1U) {
            st = wfas3_rng_bytes(&dev->rng, c->entry, 32U);     /* a key for this guest only */
        } else {
            memcpy(c->entry, dev->entry_key, 32U);
        }
        pt[0] = 1U;
        pt[1] = (uint8_t)c->perms;
        pt[2] = (uint8_t)c->guest;
        memcpy(&pt[4], w->name, 32U);
        memcpy(&pt[36], c->entry, 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_pair_seal(c->tx, 7U, w->mode, c->sid, c->k_s2c, w->k_f, pt, sizeof pt);
    }
    if (st == WFAS3_OK) {
        c->state = WFAS3_PS_S7;
        c->tx_len = 120U;
        c->wait_ms = dev->now_ms;
        wfas3_pctx_send(dev, c, c->tx, c->tx_len, out);
    }
    wfas3_wipe(pt, sizeof pt);
    return st;
}

wfas3_status wfas3_pair_answer(wfas3_device *dev, uint32_t ask, uint32_t yes, uint32_t perms,
                               uint32_t guest, wfas3_out *out)
{
    wfas3_status st = WFAS3_E_ARG;
    uint32_t i;

    if ((dev != NULL) && (out != NULL) && (dev->started == 1U) && (yes <= 1U) && (guest <= 1U) &&
        (perms <= (WFAS3_PERM_RECEIVE | WFAS3_PERM_MICROPHONE))) {
        wfas3_out_clear(out);
        st = WFAS3_E_STATE;
        for (i = 0U; (i < WFAS3_PAIR_CTX) && (st == WFAS3_E_STATE); i++) {
            wfas3_pctx *c = &dev->pctx[i];

            if ((dev->win.open == 1U) && (c->state == WFAS3_PS_ASK) && (c->asked == 1U) &&
                (c->decided == 0U) && (c->ask_id == ask)) {
                c->decided = 1U;
                c->decision = yes;
                c->guest = guest | (c->flags & WFAS3_PAIR_GUEST);
                c->perms = perms & (((c->flags & WFAS3_PAIR_MIC) != 0U) ? 0xFFU : ~WFAS3_PERM_MICROPHONE);
                st = wfas3_ps_step7(dev, c, out);
            }
        }
    }
    return st;
}

/* Step 8: the client's answer; the pair is stored before step 9 leaves. */
static wfas3_status wfas3_ps_step8(wfas3_device *dev, wfas3_pctx *c, const uint8_t *d, wfas3_out *out)
{
    wfas3_pwin *w = &dev->win;
    wfas3_pnew n;
    uint8_t pt[52];
    uint8_t m[16];
    uint8_t done[4] = { 1U, 0U, 0U, 0U };
    uint32_t slot = wfas3_pair_slot_free(dev);
    uint32_t i;
    wfas3_status st = wfas3_pair_open(pt, sizeof pt, d, 8U, c->k_c2s);

    if ((st == WFAS3_OK) && ((pt[0] > 1U) || (wfas3_all_zero(&pt[1], 3U) == 0U))) {
        st = WFAS3_E_DROP;
    }
    if (st != WFAS3_OK) {
        st = WFAS3_E_DROP;
    } else if ((c->decision == 0U) || (pt[0] == 0U)) {
        wfas3_pctx_fail(dev, c, WFAS3_PAIRF_REFUSED, c->decision, out);
    } else if (slot == WFAS3_NONE) {
        wfas3_pctx_fail(dev, c, WFAS3_PAIRF_FULL, 1U, out);
    } else {
        memcpy(n.s_priv, c->s_priv, 32U);
        memcpy(n.peer_pub, c->peer_s, 32U);
        memcpy(n.k_fix, c->k_fix, 32U);
        memcpy(n.psk0, c->psk0, 32U);
        memcpy(n.fp, c->fp, 16U);
        memcpy(n.peer_entry, &pt[4], 32U);
        memcpy(n.own_entry, c->entry, 32U);
        memcpy(n.name, c->name, 32U);
        n.perms = c->perms;
        n.guest = c->guest;
        st = wfas3_pair_seal(c->tx, 9U, w->mode, c->sid, c->k_s2c, w->k_f, done, sizeof done);
        if (st == WFAS3_OK) {
            st = wfas3_pair_create(dev, slot, &n, &c->addr, out);
        }
        if (st == WFAS3_OK) {
            for (i = 0U; (i < WFAS3_MAX_PAIRS) && (wfas3_all_zero(&pt[36], 16U) == 0U); i++) {
                if ((i != slot) && (dev->pairs[i].used == 1U) &&
                    ((dev->pairs[i].flags & WFAS3_PF_GUEST) == 0U) &&
                    (wfas3_pair_replaces(m, &dev->pairs[i], c->h) == WFAS3_OK) &&
                    (wfas3_ct_eq(m, &pt[36], 16U) == 1U)) {
                    dev->replace_pending = i;            /* removed at the next poll (13.5) */
                }
            }
            w->used = 1U;
            for (i = 0U; i < WFAS3_PAIR_CTX; i++) {
                if (&dev->pctx[i] != c) {
                    wfas3_pctx_free(&dev->pctx[i]);      /* one pairing per window */
                }
            }
            wfas_tp_sha256(c->rx_hash, d, 100U);
            wfas3_wipe(c->s_priv, sizeof c->s_priv);
            wfas3_wipe(c->k_fix, sizeof c->k_fix);
            wfas3_wipe(c->psk0, sizeof c->psk0);
            c->state = WFAS3_PS_DONE;
            c->tx_len = 52U;
            c->wait_ms = dev->now_ms;
            wfas3_pctx_send(dev, c, c->tx, c->tx_len, out);
        } else {
            wfas3_pctx_fail(dev, c, WFAS3_PAIRF_PROTOCOL, 1U, out);
            st = WFAS3_OK;
        }
    }
    wfas3_wipe((uint8_t *)&n, sizeof n);
    wfas3_wipe(pt, sizeof pt);
    return st;
}

/* A Pair datagram from a client: steps 1, 3, 5 and 8. */
static wfas3_status wfas3_ps_handle(wfas3_device *dev, const uint8_t *d, size_t len,
                                    const wfas3_addr *from, wfas3_out *out)
{
    uint32_t step = d[4];
    uint32_t ci;
    uint8_t h[WFAS3_HASH_LEN];
    wfas3_status st = WFAS3_E_DROP;

    if ((dev->win.open == 1U) && ((uint32_t)d[5] == dev->win.mode) &&
        (wfas3_pair_filter_ok(d, len, dev->win.k_f) == 1U)) {
        if (step == 1U) {
            st = wfas3_ps_step1(dev, d, from, out);
        } else {
            ci = wfas3_pctx_find(dev, &d[8], from);
            if (ci != WFAS3_NONE) {
                wfas3_pctx *c = &dev->pctx[ci];

                if ((step == 3U) && (c->state == WFAS3_PS_S2)) {
                    st = wfas3_ps_step3(dev, c, d, out);
                } else if ((step == 5U) && (c->state == WFAS3_PS_S4)) {
                    st = wfas3_ps_step5(dev, c, d, out);
                } else if ((step == 5U) && (c->state == WFAS3_PS_S7) &&
                           (wfas3_pair_open(h, sizeof h, d, 5U, c->k_c2s) == WFAS3_OK)) {
                    memcpy(out->dgram, c->tx6, 80U);     /* step 6 was lost: one for one */
                    out->len = 80U;
                    out->to = c->addr;
                    st = WFAS3_OK;
                } else if ((step == 8U) && (c->state == WFAS3_PS_S7)) {
                    st = wfas3_ps_step8(dev, c, d, out);
                } else if ((step == 8U) && (c->state == WFAS3_PS_DONE) && (dev->now_ms >= c->next_tx_ms)) {
                    wfas_tp_sha256(h, d, 100U);
                    if (wfas3_ct_eq(h, c->rx_hash, sizeof h) == 1U) {
                        wfas3_pctx_send(dev, c, c->tx, c->tx_len, out);
                        st = WFAS3_OK;
                    }
                } else {
                    /* a copy or a step out of order */
                }
            }
        }
    }
    return st;
}

/* Timers of the window and of its contexts. 1 when out holds a result. */
static uint32_t wfas3_ps_poll(wfas3_device *dev, wfas3_out *out, wfas3_status *st)
{
    wfas3_pwin *w = &dev->win;
    uint64_t now = dev->now_ms;
    uint32_t did = 0U;
    uint32_t i;

    if ((w->open == 1U) && ((now - w->opened_ms) >= (uint64_t)WFAS3_PAIR_WINDOW_MS)) {
        wfas3_win_end(dev, out);
        did = 1U;
    }
    for (i = 0U; (i < WFAS3_PAIR_CTX) && (did == 0U) && (w->open == 1U); i++) {
        wfas3_pctx *c = &dev->pctx[i];
        uint64_t waited = now - c->wait_ms;

        did = 1U;
        if (((c->state == WFAS3_PS_S2) || (c->state == WFAS3_PS_S4)) &&
            (waited >= (uint64_t)WFAS3_PAIR_STEP_MS)) {
            wfas3_pctx_fail(dev, c, WFAS3_PAIRF_TIMEOUT, 1U, out);
        } else if ((c->state == WFAS3_PS_S7) && (waited >= (uint64_t)WFAS3_PAIR_USER_MS)) {
            wfas3_pctx_fail(dev, c, WFAS3_PAIRF_TIMEOUT, c->decision, out);
        } else if ((c->state == WFAS3_PS_DONE) && (waited >= (uint64_t)WFAS3_PAIR_DONE_MS)) {
            wfas3_pctx_free(c);
            did = 0U;
        } else if ((c->state == WFAS3_PS_ASK) && (c->decided == 0U) && (c->asked == 1U) &&
                   (waited >= (uint64_t)WFAS3_PAIR_ASK_MS)) {
            c->decided = 1U;                             /* no answer means no */
            c->decision = 0U;
            *st = wfas3_ps_step7(dev, c, out);
        } else if ((c->state == WFAS3_PS_ASK) && (c->decided == 1U)) {
            *st = wfas3_ps_step7(dev, c, out);
        } else if ((c->state == WFAS3_PS_ASK) && (c->asked == 0U) && (w->ask == 1U) &&
                   (wfas3_ask_open(dev) == 0U)) {
            wfas3_ask(dev, c, out);
        } else if (((c->state == WFAS3_PS_S4) || (c->state == WFAS3_PS_ASK) || (c->state == WFAS3_PS_S7)) &&
                   (now >= c->next_tx_ms)) {
            wfas3_pctx_send(dev, c, c->tx, c->tx_len, out);
        } else {
            did = 0U;
        }
    }
    if ((did == 0U) && (w->open == 1U) && (now >= w->next_beacon_ms)) {
        w->next_beacon_ms = now + (uint64_t)WFAS3_PAIR_RETRY_MS;
        *st = wfas3_beacon_build(dev, out);
        did = 1U;
    }
    return did;
}

static uint64_t wfas3_ps_deadline(const wfas3_device *dev)
{
    const wfas3_pwin *w = &dev->win;
    uint64_t t = UINT64_MAX;
    uint32_t i;

    if (w->open == 1U) {
        t = wfas3_min64(w->next_beacon_ms, w->opened_ms + (uint64_t)WFAS3_PAIR_WINDOW_MS);
        for (i = 0U; i < WFAS3_PAIR_CTX; i++) {
            const wfas3_pctx *c = &dev->pctx[i];

            if ((c->state == WFAS3_PS_S2) || (c->state == WFAS3_PS_S4)) {
                t = wfas3_min64(t, c->wait_ms + (uint64_t)WFAS3_PAIR_STEP_MS);
            }
            if ((c->state == WFAS3_PS_S4) || (c->state == WFAS3_PS_ASK) || (c->state == WFAS3_PS_S7)) {
                t = wfas3_min64(t, c->next_tx_ms);
            }
            if (c->state == WFAS3_PS_S7) {
                t = wfas3_min64(t, c->wait_ms + (uint64_t)WFAS3_PAIR_USER_MS);
            }
            if (c->state == WFAS3_PS_DONE) {
                t = wfas3_min64(t, c->wait_ms + (uint64_t)WFAS3_PAIR_DONE_MS);
            }
            if ((c->state == WFAS3_PS_ASK) &&
                ((c->decided == 1U) ||
                 ((c->asked == 0U) && (w->ask == 1U) && (wfas3_ask_open(dev) == 0U)))) {
                t = wfas3_min64(t, dev->now_ms);
            }
            if ((c->state == WFAS3_PS_ASK) && (c->asked == 1U)) {
                t = wfas3_min64(t, c->wait_ms + (uint64_t)WFAS3_PAIR_ASK_MS);
            }
        }
    }
    return t;
}

/* -- Pairing: client (13.2 to 13.6) ------------------------------------------ */

static void wfas3_pcli_free(wfas3_pcli *c)
{
    wfas3_wipe((uint8_t *)c, sizeof *c);
    c->state = WFAS3_PC_FREE;
}

static void wfas3_pcli_send(const wfas3_device *dev, wfas3_pcli *c, wfas3_out *out)
{
    memcpy(out->dgram, c->tx, c->tx_len);
    out->len = c->tx_len;
    out->to = c->addr;
    c->next_tx_ms = dev->now_ms + (uint64_t)WFAS3_PAIR_RETRY_MS;
}

/* The attempt ends without a pair. After a valid step 2 the user MUST be
 * warned that another device may have been paired in its place (13.2). */
static void wfas3_pcli_fail(wfas3_device *dev, uint32_t why, wfas3_out *out)
{
    uint32_t race = ((dev->pcli.answered == 1U) || (why == WFAS3_PAIRF_USED)) &&
                    (why != WFAS3_PAIRF_CANCELLED) ? 1U : 0U;

    wfas3_pcli_free(&dev->pcli);
    wfas3_pair_failed(out, why, race);
}

/* Step 1: e, e1, ya and 88 zero bytes. */
static wfas3_status wfas3_pc_step1(wfas3_device *dev, wfas3_out *out)
{
    wfas3_pcli *c = &dev->pcli;
    uint8_t *d = c->tx;
    uint8_t rnd[64];
    uint8_t g[32];
    uint8_t sid24[24];
    wfas3_status st = wfas3_rng_bytes(&dev->rng, c->sid, 8U);          /* public */

    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, c->e_priv, 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, rnd, sizeof rnd);
    }
    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, c->ya, 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_pair_gen(g, c->prs, c->prs_len, c->mode, c->sid, c->nonce, sid24);
    }
    if ((st == WFAS3_OK) && (wfas_tp_x25519(c->ya_pub, c->ya, g) != 1U)) {
        st = WFAS3_E_DROP;
    }
    if (st == WFAS3_OK) {
        wfas3_pair_head(d, 1U, c->mode, c->sid);
        wfas_tp_x25519_public(c->e_pub, c->e_priv);
        memcpy(&d[16], c->e_pub, 32U);
        wfas_tp_mlkem768_keygen(&d[48], c->kem_sk, rnd);
        memcpy(&d[1232], c->ya_pub, 32U);
        memset(&d[1264], 0, 88U);
        st = wfas3_pair_sym(&c->sym, c->mode, c->sid, c->nonce);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(&c->sym, &d[16], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(&c->sym, &d[48], WFAS_TP_MLKEM_PK);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(&c->sym, &d[1232], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_mac(&d[1352], WFAS3_TAG_LEN, c->k_f, d, 1352U);
    }
    if (st == WFAS3_OK) {
        dev->entropy_fresh = 0U;
        c->state = WFAS3_PC_S1;
        c->tx_len = WFAS3_LEN_INIT;
        c->wait_ms = dev->now_ms;
        wfas3_pcli_send(dev, c, out);
    }
    wfas3_wipe(rnd, sizeof rnd);
    wfas3_wipe(g, sizeof g);
    return st;
}

wfas3_status wfas3_pair_start(wfas3_device *dev, const wfas3_pair_req *req, wfas3_out *out)
{
    wfas3_status st = WFAS3_E_ARG;
    uint32_t bad = 0U;
    uint32_t i;

    if ((dev != NULL) && (req != NULL) && (out != NULL) && (dev->started == 1U) &&
        (req->kind >= WFAS3_PAIR_CODE) && (req->kind <= WFAS3_PAIR_PIN) &&
        (req->flags <= (WFAS3_PAIR_GUEST | WFAS3_PAIR_MIC)) &&
        (req->perms <= (WFAS3_PERM_RECEIVE | WFAS3_PERM_MICROPHONE)) &&
        ((req->replace == WFAS3_NONE) ||
         ((req->replace < WFAS3_MAX_PAIRS) && (dev->pairs[req->replace].used == 1U) &&
          ((dev->pairs[req->replace].flags & WFAS3_PF_GUEST) == 0U)))) {
        for (i = 0U; (i < WFAS3_PIN_LEN) && (req->kind == WFAS3_PAIR_PIN); i++) {
            bad |= 1U ^ wfas3_in_range(req->pin[i], 0x30U, 0x39U);
        }
        wfas3_out_clear(out);
        if (bad == 1U) {
            st = WFAS3_E_ARG;
        } else if ((dev->win.open == 1U) || (dev->pcli.state != WFAS3_PC_FREE)) {
            st = WFAS3_E_STATE;
        } else if (wfas3_pair_slot_free(dev) == WFAS3_NONE) {
            st = WFAS3_E_FULL;
        } else if ((wfas_tp_drbg_left(&dev->rng.drbg) < WFAS3_RNG_MARGIN) ||
                   ((req->kind == WFAS3_PAIR_PIN) && (dev->entropy_fresh == 0U))) {
            st = WFAS3_E_ENTROPY;
        } else {
            wfas3_pcli *c = &dev->pcli;

            wfas3_pcli_free(c);
            c->kind = req->kind;
            c->mode = (req->kind == WFAS3_PAIR_PIN) ? 2U : 1U;
            c->flags = req->flags;
            c->perms = req->perms;
            c->replace = req->replace;
            memcpy(c->name, req->name, 32U);
            c->started_ms = dev->now_ms;
            c->wait_ms = dev->now_ms;
            if (req->kind == WFAS3_PAIR_PIN) {
                memcpy(c->prs, req->pin, WFAS3_PIN_LEN);
                c->prs_len = WFAS3_PIN_LEN;
                memcpy(c->nonce, req->beacon.nonce, 16U);
                c->addr = req->beacon.addr;
                st = wfas3_kdf_k16(c->k_f, c->nonce, WFAS3_LBL("pin filter"));
                if (st == WFAS3_OK) {
                    st = wfas3_pc_step1(dev, out);
                }
            } else {
                memcpy(c->prs, req->code, 16U);
                c->prs_len = 16U;
                memcpy(c->fp_w, &req->code[16], 16U);
                st = wfas3_kdf_k16(c->k_f, c->prs, WFAS3_LBL("pair filter"));
                if (st == WFAS3_OK) {
                    st = wfas3_kdf_k16(c->k_b, c->prs, WFAS3_LBL("pair beacon"));
                }
                c->state = WFAS3_PC_BEACON;
            }
            if (st != WFAS3_OK) {
                wfas3_pcli_free(c);
                wfas3_out_clear(out);
            }
        }
    }
    return st;
}

wfas3_status wfas3_pair_cancel(wfas3_device *dev)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (dev->started == 1U)) {
        wfas3_pcli_free(&dev->pcli);
        st = WFAS3_OK;
    }
    return st;
}

wfas3_status wfas3_beacons(wfas3_device *dev, uint32_t on)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (dev->started == 1U) && (on <= 1U)) {
        dev->beacons_on = on;
        st = WFAS3_OK;
    }
    return st;
}

/* Receiver of a PairBeacon (13.2). */
static wfas3_status wfas3_beacon_handle(wfas3_device *dev, const uint8_t *d, const wfas3_addr *from,
                                        wfas3_out *out)
{
    wfas3_pcli *c = &dev->pcli;
    uint8_t m[WFAS3_TAG_LEN];
    uint32_t mode = d[4];
    wfas3_status st = WFAS3_E_DROP;

    if (((mode != 1U) && (mode != 2U)) || (d[5] > 1U) || (d[6] != 0U) || (d[7] != 0U) ||
        ((mode == 1U) && (wfas3_all_zero(&d[24], 32U) == 0U)) ||
        ((mode == 2U) && (wfas3_all_zero(&d[56], 16U) == 0U))) {
        st = WFAS3_E_DROP;
    } else if ((mode == 1U) && (c->state != WFAS3_PC_FREE) && (c->mode == 1U)) {
        if ((wfas3_mac(m, sizeof m, c->k_b, d, 56U) == WFAS3_OK) && (wfas3_ct_eq(m, &d[56], 16U) == 1U)) {
            if ((d[5] == 1U) && ((c->state == WFAS3_PC_BEACON) ||
                                 ((c->state != WFAS3_PC_S8) && (wfas3_ct_eq(c->nonce, &d[8], 16U) == 1U)))) {
                wfas3_pcli_fail(dev, WFAS3_PAIRF_USED, out);    /* someone else used the window */
                st = WFAS3_OK;
            } else if ((d[5] == 0U) && (c->state == WFAS3_PC_BEACON)) {
                if (dev->entropy_fresh == 0U) {
                    st = WFAS3_E_ENTROPY;
                } else {
                    memcpy(c->nonce, &d[8], 16U);
                    c->addr = *from;
                    st = wfas3_pc_step1(dev, out);
                    if (st != WFAS3_OK) {
                        c->state = WFAS3_PC_BEACON;
                        st = WFAS3_E_DROP;
                    }
                }
            } else {
                /* a beacon of the window in use */
            }
        }
    } else if ((mode == 2U) && (dev->beacons_on == 1U)) {
        out->events |= WFAS3_EV_BEACON;
        out->beacon.addr = *from;
        out->beacon.used = d[5];
        memcpy(out->beacon.nonce, &d[8], 16U);
        memcpy(out->beacon.name, &d[24], 32U);
        st = WFAS3_OK;
    } else {
        /* not for this device */
    }
    return st;
}

/* Tokens e, ee, yb, cpace, ekem1, w (and the fingerprint check), ew, s, es
 * of the second message, on the copy t of the state. */
static wfas3_status wfas3_pc_tokens2(wfas3_pcli *c, wfas3_sym *t, const uint8_t *d, uint32_t *key_bad)
{
    uint8_t k[32];
    uint8_t isk[64];
    uint8_t ct[WFAS_TP_MLKEM_CT];
    uint8_t ss[32];
    uint8_t w_pub[32];
    uint8_t fp[16];
    uint8_t sid24[24];
    wfas3_status st = wfas3_sym_mix_hash(t, &d[16], 32U);                    /* e */

    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(t, c->e_priv, &d[16]);                                /* ee */
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_hash(t, &d[48], 32U);                              /* yb */
    }
    if ((st == WFAS3_OK) && (wfas_tp_x25519(k, c->ya, &d[48]) != 1U)) {
        st = WFAS3_E_AUTH;
    }
    if (st == WFAS3_OK) {
        memcpy(sid24, c->sid, 8U);
        memcpy(&sid24[8], c->nonce, 16U);
        st = wfas3_cpace_isk(isk, sid24, sizeof sid24, k, c->ya_pub, c->e_pub, 32U, &d[48], &d[16], 32U);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_mix_key(t, isk, sizeof isk);                           /* cpace */
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_decrypt(t, ct, sizeof ct, &d[80], 1104U);              /* ekem1, tag first */
    }
    if (st == WFAS3_OK) {
        wfas_tp_mlkem768_decaps(ss, ct, c->kem_sk);
        st = wfas3_sym_mix_key(t, ss, sizeof ss);
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_decrypt(t, w_pub, sizeof w_pub, &d[1184], 48U);        /* w */
    }
    if ((st == WFAS3_OK) && (c->mode == 1U)) {
        wfas3_w_fp(fp, w_pub);
        if (wfas3_ct_eq(fp, c->fp_w, sizeof fp) == 0U) {
            *key_bad = 1U;                                                     /* 13.3: abort */
            st = WFAS3_E_AUTH;
        }
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(t, c->e_priv, w_pub);                                 /* ew */
    }
    if (st == WFAS3_OK) {
        st = wfas3_sym_decrypt(t, c->peer_s, 32U, &d[1232], 48U);            /* s */
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(t, c->e_priv, c->peer_s);                             /* es */
    }
    wfas3_wipe(k, sizeof k);
    wfas3_wipe(isk, sizeof isk);
    wfas3_wipe(ss, sizeof ss);
    return st;
}

/* Step 3: s, se, EncryptAndHash(name, flags, 15 zero, commit_c). */
static wfas3_status wfas3_pc_step3(wfas3_device *dev, wfas3_sym *t, const uint8_t *d2)
{
    wfas3_pcli *c = &dev->pcli;
    uint8_t *d = c->tx;
    uint8_t s_pub[32];
    uint8_t pt[80];
    wfas3_status st = wfas3_rng_bytes(&dev->rng, c->s_priv, 32U);

    if (st == WFAS3_OK) {
        st = wfas3_rng_bytes(&dev->rng, c->n_c, 32U);
    }
    if (st == WFAS3_OK) {
        wfas3_pair_head(d, 3U, c->mode, c->sid);
        wfas_tp_x25519_public(s_pub, c->s_priv);
        st = wfas3_sym_encrypt(t, &d[16], 48U, s_pub, 32U);                   /* s */
    }
    if (st == WFAS3_OK) {
        st = wfas3_hs_dh(t, c->s_priv, &d2[16]);                               /* se */
    }
    if (st == WFAS3_OK) {
        memcpy(c->h3, t->h, 32U);
        memset(pt, 0, sizeof pt);
        memcpy(pt, c->name, 32U);
        pt[32] = (uint8_t)c->flags;
        wfas3_hash3(&pt[48], WFAS3_LBL("WFAS3 sas commit c"), c->h3, 32U, c->n_c, 32U, NULL, 0U);
        st = wfas3_sym_encrypt(t, &d[64], 96U, pt, sizeof pt);
    }
    if (st == WFAS3_OK) {
        memcpy(c->h, t->h, 32U);
        st = wfas3_pair_keys(t, c->k_c2s, c->k_s2c, c->k_fix, c->psk0, c->fp);
    }
    if (st == WFAS3_OK) {
        st = wfas3_mac(&d[160], WFAS3_TAG_LEN, c->k_f, d, 160U);
    }
    return st;
}

/* Step 2. One that does not open is ignored: it may be a forgery, and the
 * real one may still come; the attempt fails WFAS3_PAIR_BAD2_MS later. */
static wfas3_status wfas3_pc_step2(wfas3_device *dev, const uint8_t *d, wfas3_out *out)
{
    wfas3_pcli *c = &dev->pcli;
    wfas3_sym t = c->sym;
    uint32_t key_bad = 0U;
    wfas3_status st = wfas3_pc_tokens2(c, &t, d, &key_bad);

    if (st == WFAS3_OK) {
        c->answered = 1U;
        st = wfas3_pc_step3(dev, &t, d);
        if (st == WFAS3_OK) {
            wfas3_sym_wipe(&c->sym);
            wfas3_wipe(c->e_priv, sizeof c->e_priv);
            wfas3_wipe(c->kem_sk, sizeof c->kem_sk);
            wfas3_wipe(c->ya, sizeof c->ya);
            c->state = WFAS3_PC_S3;
            c->tx_len = 176U;
            c->wait_ms = dev->now_ms;
            wfas3_pcli_send(dev, c, out);
        } else {
            wfas3_pcli_fail(dev, WFAS3_PAIRF_PROTOCOL, out);
            st = WFAS3_OK;
        }
    } else if (key_bad == 1U) {
        wfas3_pcli_fail(dev, WFAS3_PAIRF_KEY, out);
        out->events &= ~WFAS3_EV_PAIR_RACE;
        out->events |= WFAS3_EV_PAIR_RACE;               /* someone holds the code */
        st = WFAS3_OK;
    } else {
        wfas3_wipe(c->peer_s, sizeof c->peer_s);
        if (c->bad2 == 0U) {
            c->bad2 = 1U;
            c->bad2_ms = dev->now_ms;
        }
        st = WFAS3_E_DROP;
    }
    wfas3_sym_wipe(&t);
    return st;
}

/* Step 8: accept, 3 zero, entry key, replaces. accept = 0 is sent once. */
static wfas3_status wfas3_pc_step8(wfas3_device *dev, uint32_t accept, wfas3_out *out)
{
    wfas3_pcli *c = &dev->pcli;
    uint8_t pt[52];
    wfas3_status st = WFAS3_OK;

    memset(pt, 0, sizeof pt);
    pt[0] = (uint8_t)accept;
    if ((accept == 1U) && (c->guest == 1U)) {
        st = wfas3_rng_bytes(&dev->rng, c->own_entry, 32U);   /* a key for this pair only */
    } else if (accept == 1U) {
        memcpy(c->own_entry, dev->entry_key, 32U);
    } else {
        /* nothing given with a refusal */
    }
    if ((st == WFAS3_OK) && (accept == 1U)) {
        memcpy(&pt[4], c->own_entry, 32U);
        if ((c->replace < WFAS3_MAX_PAIRS) && (dev->pairs[c->replace].used == 1U)) {
            st = wfas3_pair_replaces(&pt[36], &dev->pairs[c->replace], c->h);
        }
    }
    if (st == WFAS3_OK) {
        st = wfas3_pair_seal(c->tx, 8U, c->mode, c->sid, c->k_c2s, c->k_f, pt, sizeof pt);
    }
    if (st == WFAS3_OK) {
        c->state = WFAS3_PC_S8;
        c->tx_len = 100U;
        c->wait_ms = dev->now_ms;
        wfas3_pcli_send(dev, c, out);
    }
    wfas3_wipe(pt, sizeof pt);
    return st;
}

/* Step 7: the server's decision. */
static wfas3_status wfas3_pc_step7(wfas3_device *dev, const uint8_t *d, wfas3_out *out)
{
    wfas3_pcli *c = &dev->pcli;
    uint8_t pt[72];
    wfas3_status st = wfas3_pair_open(pt, sizeof pt, d, 7U, c->k_s2c);

    if ((st == WFAS3_OK) && ((pt[0] > 1U) || (pt[1] > 3U) || (pt[2] > 1U) || (pt[3] != 0U) ||
                             (wfas3_all_zero(&pt[68], 4U) == 0U))) {
        st = WFAS3_E_DROP;
    }
    if (st != WFAS3_OK) {
        st = WFAS3_E_DROP;
    } else if ((pt[0] == 0U) || (((c->flags & WFAS3_PAIR_GUEST) != 0U) && (pt[2] == 0U))) {
        uint32_t why = (pt[0] == 0U) ? WFAS3_PAIRF_REFUSED : WFAS3_PAIRF_PROTOCOL;

        if (wfas3_pc_step8(dev, 0U, out) == WFAS3_OK) {
            c->state = WFAS3_PC_FREE;
        }
        wfas3_pcli_fail(dev, why, out);
    } else {
        c->guest = pt[2];
        c->peer_perms = pt[1];
        memcpy(c->peer_name, &pt[4], 32U);
        memcpy(c->peer_entry, &pt[36], 32U);
        if ((c->mode == 2U) && (c->confirmed == 0U)) {
            c->state = WFAS3_PC_CONFIRM;                 /* the user compares the codes */
            c->wait_ms = dev->now_ms;
        } else {
            st = wfas3_pc_step8(dev, 1U, out);
        }
    }
    wfas3_wipe(pt, sizeof pt);
    return st;
}

/* Step 9: the server stored the pair; so does the client. */
static wfas3_status wfas3_pc_step9(wfas3_device *dev, const uint8_t *d, wfas3_out *out)
{
    wfas3_pcli *c = &dev->pcli;
    wfas3_pnew n;
    uint8_t pt[4];
    uint32_t slot = wfas3_pair_slot_free(dev);
    wfas3_status st = wfas3_pair_open(pt, sizeof pt, d, 9U, c->k_s2c);

    if (st != WFAS3_OK) {
        st = WFAS3_E_DROP;
    } else if ((pt[0] != 1U) || (wfas3_all_zero(&pt[1], 3U) == 0U)) {
        wfas3_pcli_fail(dev, WFAS3_PAIRF_PROTOCOL, out);
    } else if (slot == WFAS3_NONE) {
        wfas3_pcli_fail(dev, WFAS3_PAIRF_FULL, out);
    } else {
        memcpy(n.s_priv, c->s_priv, 32U);
        memcpy(n.peer_pub, c->peer_s, 32U);
        memcpy(n.k_fix, c->k_fix, 32U);
        memcpy(n.psk0, c->psk0, 32U);
        memcpy(n.fp, c->fp, 16U);
        memcpy(n.peer_entry, c->peer_entry, 32U);
        memcpy(n.own_entry, c->own_entry, 32U);
        memcpy(n.name, c->peer_name, 32U);
        n.guest = c->guest;
        n.perms = c->perms;
        if (((c->peer_perms & WFAS3_PERM_MICROPHONE) == 0U) || ((c->flags & WFAS3_PAIR_MIC) == 0U)) {
            n.perms &= ~WFAS3_PERM_MICROPHONE;           /* granted only when asked and given */
        }
        if (wfas3_pair_create(dev, slot, &n, &c->addr, out) != WFAS3_OK) {
            wfas3_pcli_fail(dev, WFAS3_PAIRF_PROTOCOL, out);
        } else {
            if ((c->replace < WFAS3_MAX_PAIRS) && (dev->pairs[c->replace].used == 1U)) {
                dev->replace_pending = c->replace;       /* removed at the next poll (13.5) */
            }
            wfas3_pcli_free(c);
        }
    }
    wfas3_wipe((uint8_t *)&n, sizeof n);
    return st;
}

wfas3_status wfas3_pair_confirm(wfas3_device *dev, uint32_t yes, wfas3_out *out)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (out != NULL) && (dev->started == 1U) && (yes <= 1U)) {
        wfas3_pcli *c = &dev->pcli;

        wfas3_out_clear(out);
        st = WFAS3_E_STATE;
        if ((c->mode == 2U) && ((c->state == WFAS3_PC_S6) || (c->state == WFAS3_PC_CONFIRM))) {
            st = WFAS3_OK;
            if (yes == 0U) {
                if (c->state == WFAS3_PC_CONFIRM) {
                    (void)wfas3_pc_step8(dev, 0U, out);
                }
                wfas3_pcli_fail(dev, WFAS3_PAIRF_CODE, out);
            } else if (c->state == WFAS3_PC_CONFIRM) {
                c->confirmed = 1U;
                st = wfas3_pc_step8(dev, 1U, out);
            } else {
                c->confirmed = 1U;                       /* step 8 leaves with step 7 */
            }
        }
    }
    return st;
}

/* A Pair datagram from the server: steps 2, 4, 6, 7 and 9. */
static wfas3_status wfas3_pc_handle(wfas3_device *dev, const uint8_t *d, size_t len,
                                    const wfas3_addr *from, wfas3_out *out)
{
    wfas3_pcli *c = &dev->pcli;
    uint32_t step = d[4];
    uint8_t pt[32];
    uint8_t want[WFAS3_HASH_LEN];
    wfas3_status st = WFAS3_E_DROP;

    if ((c->state > WFAS3_PC_BEACON) && ((uint32_t)d[5] == c->mode) &&
        (wfas3_ct_eq(&d[8], c->sid, 8U) == 1U) && (wfas3_addr_eq(from, &c->addr) == 1U) &&
        (wfas3_pair_filter_ok(d, len, c->k_f) == 1U)) {
        if ((step == 2U) && (c->state == WFAS3_PC_S1)) {
            st = wfas3_pc_step2(dev, d, out);
        } else if ((step == 4U) && (c->state == WFAS3_PC_S3) &&
                   (wfas3_pair_open(c->commit_s, 32U, d, 4U, c->k_s2c) == WFAS3_OK)) {
            st = wfas3_pair_seal(c->tx, 5U, c->mode, c->sid, c->k_c2s, c->k_f, c->n_c, 32U);
            if (st == WFAS3_OK) {
                c->state = WFAS3_PC_S5;
                c->tx_len = 80U;
                c->wait_ms = dev->now_ms;
                wfas3_pcli_send(dev, c, out);
            }
        } else if ((step == 6U) && (c->state == WFAS3_PC_S5) &&
                   (wfas3_pair_open(pt, sizeof pt, d, 6U, c->k_s2c) == WFAS3_OK)) {
            wfas3_hash3(want, WFAS3_LBL("WFAS3 sas commit s"), c->h, 32U, pt, 32U, NULL, 0U);
            if (wfas3_ct_eq(want, c->commit_s, sizeof want) == 0U) {
                wfas3_pcli_fail(dev, WFAS3_PAIRF_PROTOCOL, out);
            } else {
                c->code = wfas3_pair_sas(c->h, c->n_c, pt);
                c->state = WFAS3_PC_S6;
                c->wait_ms = dev->now_ms;
                out->events |= WFAS3_EV_PAIR_CODE;
                out->code = c->code;
            }
            st = WFAS3_OK;
        } else if ((step == 7U) && (c->state == WFAS3_PC_S6)) {
            st = wfas3_pc_step7(dev, d, out);
        } else if ((step == 9U) && (c->state == WFAS3_PC_S8)) {
            st = wfas3_pc_step9(dev, d, out);
        } else {
            /* a copy or a step out of order */
        }
    }
    wfas3_wipe(pt, sizeof pt);
    return st;
}

/* Timers of the client. 1 when out holds a result. */
static uint32_t wfas3_pc_poll(wfas3_device *dev, wfas3_out *out)
{
    wfas3_pcli *c = &dev->pcli;
    uint64_t now = dev->now_ms;
    uint64_t waited = now - c->wait_ms;
    uint32_t did = 1U;

    if ((c->state == WFAS3_PC_BEACON) && (waited >= (uint64_t)WFAS3_PAIR_WINDOW_MS)) {
        wfas3_pcli_fail(dev, WFAS3_PAIRF_TIMEOUT, out);
    } else if ((c->state == WFAS3_PC_S1) && (c->bad2 == 1U) &&
               ((now - c->bad2_ms) >= (uint64_t)WFAS3_PAIR_BAD2_MS)) {
        wfas3_pcli_fail(dev, WFAS3_PAIRF_WRONG, out);
    } else if ((c->state == WFAS3_PC_S1) && (waited >= (uint64_t)WFAS3_PAIR_WAIT2_MS)) {
        wfas3_pcli_fail(dev, WFAS3_PAIRF_TIMEOUT, out);
    } else if (((c->state == WFAS3_PC_S3) || (c->state == WFAS3_PC_S5) || (c->state == WFAS3_PC_S8)) &&
               (waited >= (uint64_t)WFAS3_PAIR_STEP_MS)) {
        wfas3_pcli_fail(dev, WFAS3_PAIRF_TIMEOUT, out);
    } else if ((c->state == WFAS3_PC_S6) && (waited >= (uint64_t)WFAS3_PAIR_USER_MS)) {
        wfas3_pcli_fail(dev, WFAS3_PAIRF_TIMEOUT, out);
    } else if ((c->state == WFAS3_PC_CONFIRM) && (waited >= (uint64_t)WFAS3_PAIR_ASK_MS)) {
        (void)wfas3_pc_step8(dev, 0U, out);
        wfas3_pcli_fail(dev, WFAS3_PAIRF_TIMEOUT, out);
    } else if (((c->state == WFAS3_PC_S1) || (c->state == WFAS3_PC_S3) || (c->state == WFAS3_PC_S5) ||
                (c->state == WFAS3_PC_S8)) && (now >= c->next_tx_ms)) {
        wfas3_pcli_send(dev, c, out);
    } else {
        did = 0U;
    }
    return did;
}

static uint64_t wfas3_pc_deadline(const wfas3_device *dev)
{
    const wfas3_pcli *c = &dev->pcli;
    uint64_t t = UINT64_MAX;

    switch (c->state) {
    case WFAS3_PC_BEACON:
        t = c->wait_ms + (uint64_t)WFAS3_PAIR_WINDOW_MS;
        break;
    case WFAS3_PC_S1:
        t = wfas3_min64(c->next_tx_ms, c->wait_ms + (uint64_t)WFAS3_PAIR_WAIT2_MS);
        if (c->bad2 == 1U) {
            t = wfas3_min64(t, c->bad2_ms + (uint64_t)WFAS3_PAIR_BAD2_MS);
        }
        break;
    case WFAS3_PC_S3:
    case WFAS3_PC_S5:
    case WFAS3_PC_S8:
        t = wfas3_min64(c->next_tx_ms, c->wait_ms + (uint64_t)WFAS3_PAIR_STEP_MS);
        break;
    case WFAS3_PC_S6:
        t = c->wait_ms + (uint64_t)WFAS3_PAIR_USER_MS;
        break;
    case WFAS3_PC_CONFIRM:
        t = c->wait_ms + (uint64_t)WFAS3_PAIR_ASK_MS;
        break;
    default:
        t = UINT64_MAX;
        break;
    }
    return t;
}

/* A Pair datagram: steps 1, 3, 5, 8 go to the server, the others to the
 * client; the length must be that of its step. */
static wfas3_status wfas3_pair_handle(wfas3_device *dev, const uint8_t *d, size_t len,
                                      const wfas3_addr *from, wfas3_out *out)
{
    uint32_t step = d[4];
    wfas3_status st = WFAS3_E_DROP;

    if ((step >= 1U) && (step <= 9U) && (len == (size_t)wfas3_pair_len[step]) &&
        (d[6] == 0U) && (d[7] == 0U)) {
        if ((step == 1U) || (step == 3U) || (step == 5U) || (step == 8U)) {
            st = wfas3_ps_handle(dev, d, len, from, out);
        } else {
            st = wfas3_pc_handle(dev, d, len, from, out);
        }
    }
    return st;
}

/* -- Pairing: pairs that end by themselves (13.4 to 13.6) ------------------- */

static uint32_t wfas3_pair_has_session(const wfas3_device *dev, uint32_t pair)
{
    uint32_t has = 0U;
    uint32_t i;

    for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
        if ((dev->sessions[i].pair == pair) &&
            ((dev->sessions[i].state == WFAS3_SES_UP) || (dev->sessions[i].state == WFAS3_SES_UNCONFIRMED))) {
            has = 1U;
        }
    }
    return has;
}

/* The pair a new pairing replaced; a pair never used for a session within
 * WFAS3_UNUSED_MS; a guest pair without a session for WFAS3_GUEST_IDLE_MS. */
static uint32_t wfas3_pair_poll(wfas3_device *dev, wfas3_out *out, wfas3_status *st)
{
    uint32_t did = 0U;
    uint32_t i;

    if ((dev->replace_pending < WFAS3_MAX_PAIRS) && (dev->pairs[dev->replace_pending].used == 1U)) {
        *st = wfas3_pair_drop(dev, dev->replace_pending, out);
        did = 1U;
    }
    dev->replace_pending = (did == 1U) ? dev->replace_pending : WFAS3_NONE;
    for (i = 0U; (i < WFAS3_MAX_PAIRS) && (did == 0U); i++) {
        wfas3_pair *p = &dev->pairs[i];

        if ((p->used == 1U) && ((p->flags & WFAS3_PF_GUEST) != 0U) &&
            (wfas3_pair_has_session(dev, i) == 1U)) {
            p->guest_seen_ms = dev->now_ms;
        } else if ((p->used == 1U) && ((p->flags & WFAS3_PF_GUEST) != 0U) &&
                   ((dev->now_ms - p->guest_seen_ms) >= (uint64_t)WFAS3_GUEST_IDLE_MS)) {
            *st = wfas3_pair_drop(dev, i, out);
            did = 1U;
        } else if ((p->used == 1U) && (p->seq_hi == 0U) && (p->unused_until_ms != 0U) &&
                   (dev->now_ms >= p->unused_until_ms) && (wfas3_pair_has_session(dev, i) == 0U)) {
            *st = wfas3_pair_drop(dev, i, out);
            did = 1U;
        } else {
            /* nothing due */
        }
    }
    return did;
}

static uint64_t wfas3_pair_deadline(const wfas3_device *dev)
{
    uint64_t t = UINT64_MAX;
    uint32_t i;

    if (dev->replace_pending != WFAS3_NONE) {
        t = dev->now_ms;
    }
    for (i = 0U; i < WFAS3_MAX_PAIRS; i++) {
        const wfas3_pair *p = &dev->pairs[i];

        if ((p->used == 1U) && ((p->flags & WFAS3_PF_GUEST) != 0U)) {
            t = wfas3_min64(t, (wfas3_pair_has_session(dev, i) == 1U) ? (dev->now_ms + 1000U) :
                                   (p->guest_seen_ms + (uint64_t)WFAS3_GUEST_IDLE_MS));
        } else if ((p->used == 1U) && (p->seq_hi == 0U) && (p->unused_until_ms != 0U)) {
            t = wfas3_min64(t, p->unused_until_ms);
        } else {
            /* no timer */
        }
    }
    return t;
}

/* -- Public entry points ----------------------------------------------------- */

static void wfas3_policy_default(wfas3_device *dev)
{
    dev->policy.busy = 0U;
    dev->policy.codec_mask = ((uint32_t)1U << WFAS3_CODEC_PCM);
    dev->policy.max_channels = 8U;
    dev->policy.suggest.flags = WFAS3_CF_SOURCE;
    dev->policy.suggest.codec = WFAS3_CODEC_PCM;
    dev->policy.suggest.channels = 2U;
    dev->policy.suggest.bits = 16U;
    dev->policy.suggest.sample_rate = 48000U;
    dev->policy.suggest.frame_samples = 240U;
    dev->policy.suggest.payload_bytes = 960U;
    dev->policy.suggest.latency_ms = 20U;
    wfas3_config_encode(dev->suggest, &dev->policy.suggest, WFAS3_ST_UNSUPPORTED);
}

wfas3_status wfas3_device_init(wfas3_device *dev, const uint8_t *seed, uint8_t *record,
                               uint32_t record_valid, uint64_t now_us)
{
    wfas3_status st = WFAS3_E_ARG;
    uint8_t key[WFAS3_KEY_LEN];

    if ((dev != NULL) && (seed != NULL) && (record != NULL) && (record_valid <= 1U)) {
        memset(dev, 0, sizeof *dev);
        wfas3_rng_start(&dev->rng, seed);
        dev->now_us = now_us;
        dev->now_ms = now_us / 1000U;
        if (record_valid == 1U) {
            memcpy(key, record, WFAS3_KEY_LEN);
            dev->ectr_limit = wfas3_rd_be64(&record[32]);
            st = WFAS3_OK;
        } else {
            st = wfas3_rng_bytes(&dev->rng, key, WFAS3_KEY_LEN);
        }
        if ((st == WFAS3_OK) && (dev->ectr_limit < (WFAS3_CTR_LIMIT * 4U))) {
            dev->ectr_high = dev->ectr_limit;          /* the stored reserve R */
            dev->ectr_limit += (uint64_t)WFAS3_ECTR_BLOCK;
            st = wfas3_entry_key_set(dev, key);
        } else {
            st = WFAS3_E_STATE;
        }
        if (st == WFAS3_OK) {
            memcpy(record, dev->entry_key, WFAS3_KEY_LEN);
            wfas3_wr_be64(&record[32], dev->ectr_limit);
            wfas3_policy_default(dev);
            dev->replace_pending = WFAS3_NONE;
            dev->entropy_fresh = 1U;
            dev->started = 1U;
        }
    }
    wfas3_wipe(key, sizeof key);
    return st;
}

wfas3_status wfas3_device_export(const wfas3_device *dev, uint8_t *record)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (record != NULL) && (dev->started == 1U)) {
        memcpy(record, dev->entry_key, WFAS3_KEY_LEN);
        wfas3_wr_be64(&record[32], dev->ectr_limit);
        st = WFAS3_OK;
    }
    return st;
}

wfas3_status wfas3_add_entropy(wfas3_device *dev, const uint8_t *entropy)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (entropy != NULL) && (dev->started == 1U)) {
        st = wfas3_rng_reseed(&dev->rng, entropy);
        if (st == WFAS3_OK) {
            dev->entropy_fresh = 1U;
        }
    }
    return st;
}

wfas3_status wfas3_set_time(wfas3_device *dev, uint64_t now_us)
{
    wfas3_status st = WFAS3_E_ARG;

    if ((dev != NULL) && (dev->started == 1U)) {
        if (now_us > dev->now_us) {
            dev->now_us = now_us;
            dev->now_ms = now_us / 1000U;
        }
        st = WFAS3_OK;
    }
    return st;
}


/* Earliest timer of one session, in milliseconds. */
static uint64_t wfas3_ses_deadline(const wfas3_device *dev, const wfas3_session *s)
{
    uint64_t t = UINT64_MAX;

    if (s->state == WFAS3_SES_UNCONFIRMED) {
        t = s->created_ms + (uint64_t)WFAS3_UNCONFIRMED_MS;
    } else if (s->state == WFAS3_SES_CLOSING) {
        t = s->next_ctl_ms;
    } else if (s->state == WFAS3_SES_UP) {
        t = wfas3_min64(s->next_ctl_ms,
                        s->last_rx_ms + ((s->silent == 1U) ? WFAS3_DEAD_MS : WFAS3_SILENT_MS));
        if (s->rk.state != WFAS3_RK_IDLE) {
            t = wfas3_min64(t, s->rk.started_ms + (uint64_t)WFAS3_REKEY_LIMIT_MS);
        }
        if ((s->initiator == 1U) && (s->rk.state == WFAS3_RK_IDLE)) {
            t = wfas3_min64(t, (dev->rekey_gap_ms > s->next_rekey_ms) ? dev->rekey_gap_ms
                                                                       : s->next_rekey_ms);
        }
        if (s->rk.state == WFAS3_RK_R_COMPUTE) {
            t = wfas3_min64(t, (dev->rekey_gap_ms > dev->now_ms) ? dev->rekey_gap_ms : dev->now_ms);
        }
        if (s->tx.started == 1U) {
            t = wfas3_min64(t, s->tx.consent_ms + 1U);
        }
        if (s->path_active == 1U) {
            t = wfas3_min64(t, s->path_next_ms);
        }
        if (s->old_valid == 1U) {
            t = wfas3_min64(t, s->old_until_ms);
        }
    } else {
        t = UINT64_MAX;
    }
    return t;
}

uint64_t wfas3_next_deadline(const wfas3_device *dev)
{
    uint64_t t = UINT64_MAX;
    uint32_t i;

    if ((dev != NULL) && (dev->started == 1U)) {
        for (i = 0U; i < WFAS3_MAX_ATTEMPTS; i++) {
            const wfas3_attempt *a = &dev->attempts[i];

            if (a->state != WFAS3_ATT_FREE) {
                t = wfas3_min64(t, wfas3_min64(a->started_ms + (uint64_t)WFAS3_GIVE_UP_MS,
                                               a->last_tx_ms + (uint64_t)WFAS3_RETRY_MS));
            }
        }
        for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
            t = wfas3_min64(t, wfas3_ses_deadline(dev, &dev->sessions[i]));
        }
        for (i = 0U; i < WFAS3_MAX_FINDS; i++) {
            const wfas3_search *f = &dev->finds[i];

            if ((f->state == 1U) && (f->tries >= WFAS3_PROBE_TRIES)) {
                t = wfas3_min64(t, f->next_ms);
            } else if (f->state == 1U) {
                uint64_t np = dev->pairs[f->pair].next_probe_ms;

                t = wfas3_min64(t, (np > f->next_ms) ? np : f->next_ms);
            } else {
                /* free slot */
            }
        }
        for (i = 0U; i < WFAS3_MAX_PAIRS; i++) {
            if ((dev->pairs[i].used == 1U) && (dev->pairs[i].seen_dirty == 1U)) {
                t = wfas3_min64(t, dev->pairs[i].next_store_ms);
            }
        }
        t = wfas3_min64(t, wfas3_pair_deadline(dev));
        t = wfas3_min64(t, wfas3_grp_deadline(dev));
        t = wfas3_min64(t, wfas3_ps_deadline(dev));
        t = wfas3_min64(t, wfas3_pc_deadline(dev));
        t = (t >= (UINT64_MAX / 1000U)) ? UINT64_MAX : (t * 1000U);
    }
    return t;
}

wfas3_status wfas3_set_policy(wfas3_device *dev, const wfas3_policy *policy)
{
    wfas3_status st = WFAS3_E_ARG;
    uint8_t enc[16];

    if ((dev != NULL) && (policy != NULL) && (dev->started == 1U) && (policy->busy <= 1U)) {
        wfas3_config_encode(enc, &policy->suggest, WFAS3_ST_UNSUPPORTED);
        if (wfas3_config_valid(enc, 1U) == 1U) {
            dev->policy = *policy;
            memcpy(dev->suggest, enc, sizeof enc);
            st = WFAS3_OK;
        }
    }
    return st;
}

wfas3_status wfas3_connect(wfas3_device *dev, uint32_t pair, const wfas3_addr *to,
                           const wfas3_config *config, wfas3_out *out)
{
    wfas3_status st = WFAS3_E_ARG;
    uint8_t cfg[16];
    uint32_t ai;

    if ((dev != NULL) && (to != NULL) && (config != NULL) && (out != NULL) &&
        (dev->started == 1U) && (pair < WFAS3_MAX_PAIRS) && (dev->pairs[pair].used == 1U)) {
        const wfas3_pair *p = &dev->pairs[pair];

        wfas3_out_clear(out);
        wfas3_config_encode(cfg, config, 0U);
        ai = wfas3_att_alloc(dev);
        if (wfas3_config_valid(cfg, 0U) == 0U) {
            st = WFAS3_E_ARG;
        } else if (((cfg[0] & (WFAS3_CF_MULTICAST | WFAS3_CF_SOURCE)) ==
                    (WFAS3_CF_MULTICAST | WFAS3_CF_SOURCE)) &&
                   ((wfas3_grp_fits(dev, cfg) == 0U) || (wfas3_grp_sessions(dev) >= WFAS3_GROUP_SLOTS))) {
            st = WFAS3_E_STATE;                          /* the source opens only sessions of its group */
        } else if (((cfg[0] & WFAS3_CF_MICROPHONE) != 0U) &&
                   ((p->perms & WFAS3_PERM_MICROPHONE) == 0U)) {
            st = WFAS3_E_PERM;
        } else if (wfas_tp_drbg_left(&dev->rng.drbg) < WFAS3_RNG_MARGIN) {
            st = WFAS3_E_ENTROPY;
        } else if (wfas3_att_find_pair(dev, pair) != WFAS3_NONE) {
            st = WFAS3_E_STATE;
        } else if ((p->ectr_used < p->ectr_seen) && (dev->now_ms < p->cookie_hold_ms)) {
            st = WFAS3_E_STATE;                          /* rule C5 */
        } else if (ai == WFAS3_NONE) {
            st = WFAS3_E_FULL;
        } else {
            wfas3_attempt *a = &dev->attempts[ai];

            wfas3_ses_drop_pair(dev, pair, WFAS3_DROP_UNCONFIRMED, WFAS3_NONE);
            a->pair = pair;
            a->addr = *to;
            a->started_ms = dev->now_ms;
            memcpy(a->config, cfg, sizeof cfg);
            st = wfas3_creq_build(dev, a, out);
            if (st != WFAS3_OK) {
                wfas3_att_free(a);
                wfas3_out_clear(out);
            }
        }
    }
    return st;
}

/* Timers of one session (10.3 to 10.6, 11). 1 when out holds a result. */
static uint32_t wfas3_ses_poll(wfas3_device *dev, uint32_t si, wfas3_out *out)
{
    wfas3_session *s = &dev->sessions[si];
    uint64_t now = dev->now_ms;
    uint32_t did = 0U;

    if (s->state == WFAS3_SES_UNCONFIRMED) {
        if ((now - s->created_ms) >= (uint64_t)WFAS3_UNCONFIRMED_MS) {
            wfas3_ses_free(s);                           /* never confirmed: no event */
        }
    } else if (s->state == WFAS3_SES_CLOSING) {
        if (now >= s->next_ctl_ms) {
            wfas3_ctl_build(dev, si, out);
            did = 1U;
        }
    } else if (s->state == WFAS3_SES_UP) {
        if ((s->old_valid == 1U) && (now >= s->old_until_ms)) {
            wfas3_wipe(s->k_recv_old, sizeof s->k_recv_old);
            s->old_valid = 0U;                           /* no output: go on */
        }
        if ((s->mrx.old_valid == 1U) && (now >= s->mrx.old_until_ms)) {
            wfas3_wipe(s->mrx.old_key, sizeof s->mrx.old_key);
            wfas3_wipe(s->mrx.old_tag_key, sizeof s->mrx.old_tag_key);
            s->mrx.old_valid = 0U;                       /* the previous group epoch ends */
        }
        if ((now - s->last_rx_ms) >= (uint64_t)WFAS3_DEAD_MS) {
            wfas3_ses_down(dev, si, WFAS3_DOWN_TIMEOUT, out);
            did = 1U;
        } else if ((s->rk.state != WFAS3_RK_IDLE) &&
                   ((now - s->rk.started_ms) >= (uint64_t)WFAS3_REKEY_LIMIT_MS)) {
            wfas3_ses_close(dev, si, WFAS3_BYE_REKEY_FAILED, 0U, WFAS3_DOWN_REKEY, out);
            did = 1U;
        } else if ((s->silent == 0U) && ((now - s->last_rx_ms) >= (uint64_t)WFAS3_SILENT_MS)) {
            s->silent = 1U;
            out->events |= WFAS3_EV_PEER_SILENT;
            out->session = si;
            out->pair = s->pair;
            did = 1U;
        } else if ((s->tx.started == 1U) && (now > s->tx.consent_ms)) {
            s->tx.started = 0U;
            out->events |= WFAS3_EV_STREAM_STOP;
            out->session = si;
            out->pair = s->pair;
            did = 1U;
        } else if (wfas3_ctr_spent(s) == 1U) {
            wfas3_ses_close(dev, si, WFAS3_BYE_NORMAL, 0U, WFAS3_DOWN_LIMIT, out);
            did = 1U;
        } else if ((s->path_active == 1U) && (now >= s->path_next_ms)) {
            if (s->path_tries < WFAS3_PATH_TRIES) {
                wfas3_path_build(dev, si, out);
                did = 1U;
            } else {
                s->path_active = 0U;                     /* the candidate is discarded */
            }
        } else if (now >= s->next_ctl_ms) {
            wfas3_ctl_build(dev, si, out);
            did = 1U;
        } else {
            /* nothing due */
        }
        if ((s->state == WFAS3_SES_UP) && ((s->flags & WFAS3_CF_SYNC) != 0U) &&
            (s->rx.exists == 1U) && (s->rx.d_ms > WFAS3_D_MIN_MS)) {
            uint64_t base = (s->rx.last_late_ms > s->rx.last_dec_ms) ? s->rx.last_late_ms
                                                                     : s->rx.last_dec_ms;

            if (now >= (base + (uint64_t)WFAS3_D_WAIT_MS)) {
                s->rx.d_ms = ((s->rx.d_ms - WFAS3_D_STEP_MS) < WFAS3_D_MIN_MS)
                             ? WFAS3_D_MIN_MS : (s->rx.d_ms - WFAS3_D_STEP_MS);
                s->rx.last_dec_ms = now;
            }
        }
    } else {
        /* free slot */
    }
    return did;
}

/* A rekey message to compute (11). 1 when one was handled; *st as for wfas3_poll(). */
static uint32_t wfas3_rk_poll(wfas3_device *dev, wfas3_out *out, wfas3_status *st)
{
    uint32_t did = 0U;
    uint32_t i;

    for (i = 0U; (i < WFAS3_MAX_SESSIONS) && (did == 0U); i++) {
        wfas3_session *s = &dev->sessions[i];
        uint32_t start = ((s->state == WFAS3_SES_UP) && (s->initiator == 1U) &&
                          (s->rk.state == WFAS3_RK_IDLE) && (dev->now_ms >= s->next_rekey_ms)) ? 1U : 0U;
        uint32_t answer = ((s->state == WFAS3_SES_UP) && (s->rk.state == WFAS3_RK_R_COMPUTE)) ? 1U : 0U;

        if (((start == 1U) || (answer == 1U)) && (dev->now_ms >= dev->rekey_gap_ms)) {
            did = 1U;
            if (dev->entropy_fresh == 0U) {
                *st = WFAS3_E_ENTROPY;                   /* nothing has changed */
            } else {
                wfas3_status r = (start == 1U) ? wfas3_rk_start(dev, i) : wfas3_rk_answer(dev, i);

                if (r == WFAS3_E_ENTROPY) {
                    *st = WFAS3_E_ENTROPY;               /* the state is as before the call */
                } else {
                    dev->entropy_fresh = 0U;
                    dev->rekey_gap_ms = dev->now_ms + (uint64_t)WFAS3_REKEY_GAP_MS;
                    if (r != WFAS3_OK) {
                        wfas3_ses_close(dev, i, WFAS3_BYE_REKEY_FAILED, 0U, WFAS3_DOWN_REKEY, out);
                    }
                }
            }
        }
    }
    return did;
}

wfas3_status wfas3_poll(wfas3_device *dev, wfas3_out *out)
{
    wfas3_status st = WFAS3_E_ARG;
    uint32_t done = 0U;
    uint32_t k;
    uint32_t i;

    if ((dev != NULL) && (out != NULL) && (dev->started == 1U)) {
        wfas3_out_clear(out);
        st = WFAS3_OK;
        if (wfas_tp_drbg_left(&dev->rng.drbg) < WFAS3_RNG_MARGIN) {
            st = WFAS3_E_ENTROPY;
            done = 1U;
        }
        for (k = 0U; (k < WFAS3_MAX_SESSIONS) && (done == 0U); k++) {
            i = (dev->poll_next + k) % WFAS3_MAX_SESSIONS;
            if (wfas3_ses_poll(dev, i, out) == 1U) {
                dev->poll_next = (i + 1U) % WFAS3_MAX_SESSIONS;
                done = 1U;
            }
        }
        for (i = 0U; (i < WFAS3_MAX_ATTEMPTS) && (done == 0U); i++) {
            wfas3_attempt *a = &dev->attempts[i];

            if (a->state == WFAS3_ATT_FREE) {
                /* nothing to do for a free slot */
            } else if ((dev->now_ms - a->started_ms) >= (uint64_t)WFAS3_GIVE_UP_MS) {
                out->pair = a->pair;
                out->events |= WFAS3_EV_ATTEMPT_FAILED;
                wfas3_att_free(a);
                done = 1U;
            } else if ((dev->now_ms - a->last_tx_ms) >= (uint64_t)WFAS3_RETRY_MS) {
                wfas3_att_scrub(a);
                st = wfas3_creq_build(dev, a, out);
                done = 1U;
            } else {
                /* not due yet */
            }
        }
        for (i = 0U; (i < WFAS3_MAX_FINDS) && (done == 0U); i++) {
            wfas3_search *f = &dev->finds[i];

            if ((f->state == 1U) && (dev->now_ms >= f->next_ms)) {
                if (f->tries >= WFAS3_PROBE_TRIES) {
                    out->pair = f->pair;
                    out->events |= WFAS3_EV_NOT_FOUND;
                    wfas3_find_free(f);
                    done = 1U;
                } else if (dev->now_ms >= dev->pairs[f->pair].next_probe_ms) {
                    st = wfas3_probe_send(dev, f, out);
                    done = 1U;
                } else {
                    f->next_ms = dev->pairs[f->pair].next_probe_ms;
                }
            }
        }
        for (i = 0U; (i < WFAS3_MAX_PAIRS) && (done == 0U); i++) {
            if ((dev->pairs[i].used == 1U) && (dev->pairs[i].seen_dirty == 1U) &&
                (dev->now_ms >= dev->pairs[i].next_store_ms)) {
                wfas3_store_pair(dev, i, out);           /* seen values, at most once a minute */
                done = 1U;
            }
        }
        if (done == 0U) {
            done = wfas3_grp_poll(dev, out, &st);
        }
        if (done == 0U) {
            done = wfas3_pair_poll(dev, out, &st);
        }
        if (done == 0U) {
            done = wfas3_ps_poll(dev, out, &st);
        }
        if (done == 0U) {
            done = wfas3_pc_poll(dev, out);
        }
        if (done == 0U) {
            (void)wfas3_rk_poll(dev, out, &st);
        }
    }
    return st;
}

wfas3_status wfas3_receive(wfas3_device *dev, const uint8_t *dgram, size_t len,
                           const wfas3_addr *from, wfas3_out *out)
{
    wfas3_status st = WFAS3_E_ARG;
    uint8_t type = 0U;

    if ((dev != NULL) && (dgram != NULL) && (from != NULL) && (out != NULL) &&
        (dev->started == 1U)) {
        wfas3_out_clear(out);
        st = wfas3_classify(dgram, len, &type);
        if ((st == WFAS3_OK) && (wfas_tp_drbg_left(&dev->rng.drbg) < WFAS3_RNG_MARGIN)) {
            st = WFAS3_E_ENTROPY;
        }
        if (st == WFAS3_OK) {
            switch (type) {
            case WFAS3_T_COOKIE_REQUEST:
                st = wfas3_creq_handle(dev, dgram, from, out);
                break;
            case WFAS3_T_COOKIE_REPLY:
                st = wfas3_crep_handle(dev, dgram, out);
                break;
            case WFAS3_T_INIT:
                st = wfas3_init_handle(dev, dgram, from, out);
                break;
            case WFAS3_T_RESP:
                st = wfas3_resp_handle(dev, dgram, out);
                break;
            case WFAS3_T_REJECT:
                st = wfas3_reject_handle(dev, dgram, out);
                break;
            case WFAS3_T_TRANSPORT:
                st = wfas3_transport_handle(dev, dgram, len, from, out);
                break;
            case WFAS3_T_PROBE:
                st = wfas3_probe_handle(dev, dgram, from, out);
                break;
            case WFAS3_T_PROBE_REPLY:
                st = wfas3_preply_handle(dev, dgram, from, out);
                break;
            case WFAS3_T_PAIR_BEACON:
                st = wfas3_beacon_handle(dev, dgram, from, out);
                break;
            case WFAS3_T_PAIR:
                st = wfas3_pair_handle(dev, dgram, len, from, out);
                break;
            case WFAS3_T_MULTICAST:
                st = wfas3_mcast_handle(dev, dgram, len, out);
                break;
            default:
                st = WFAS3_E_DROP;                       /* not implemented at this stage */
                break;
            }
        }
        if (st != WFAS3_OK) {
            out->len = 0U;
            out->events = 0U;
        }
    }
    return st;
}

/* -- Power-on self-test ------------------------------------------------------ */

/* Fixed inputs: byte i of a buffer is (start + i) mod 256. */
static void wfas3_st_fill(uint8_t *p, size_t len, uint8_t start)
{
    size_t i;

    for (i = 0U; (i < len) && (i < (size_t)WFAS3_CMP_MAX); i++) {
        p[i] = (uint8_t)((uint32_t)start + (uint32_t)i);
    }
}

typedef struct wfas3_st_buf {
    uint8_t pk[WFAS_TP_MLKEM_PK];
    uint8_t sk[WFAS_TP_MLKEM_SK];
    uint8_t ct[WFAS_TP_MLKEM_PK + WFAS3_TAG_LEN];
    uint8_t back[WFAS_TP_MLKEM_PK];
    uint8_t e_priv[32];
    uint8_t e_pub[32];
    uint8_t s_priv[32];
    uint8_t s_pub[32];
    uint8_t dh1[32];
    uint8_t dh2[32];
    uint8_t ss1[32];
    uint8_t ss2[32];
    uint8_t rnd[64];
    uint8_t wide[64];
} wfas3_st_buf;

/* psk, ephemeral key, sealed KEM key; also checks Open and a forged tag. */
static uint32_t wfas3_st_part1(wfas3_sym *s, wfas3_st_buf *b)
{
    wfas3_sym copy;
    uint32_t ok = 1U;

    wfas3_st_fill(b->rnd, 32U, 0x00U);
    ok &= (wfas3_sym_init(s, WFAS3_LBL("WFAS3_SELFTEST")) == WFAS3_OK) ? 1U : 0U;
    ok &= (wfas3_sym_mix_key_and_hash(s, b->rnd) == WFAS3_OK) ? 1U : 0U;
    wfas3_st_fill(b->e_priv, 32U, 0x20U);
    wfas_tp_x25519_public(b->e_pub, b->e_priv);
    ok &= (wfas3_sym_mix_hash(s, b->e_pub, 32U) == WFAS3_OK) ? 1U : 0U;
    ok &= (wfas3_sym_mix_key(s, b->e_pub, 32U) == WFAS3_OK) ? 1U : 0U;
    wfas3_st_fill(b->rnd, 64U, 0x60U);
    wfas_tp_mlkem768_keygen(b->pk, b->sk, b->rnd);
    ok &= wfas_tp_mlkem768_check_pk(b->pk);
    copy = *s;
    ok &= (wfas3_sym_encrypt(s, b->ct, sizeof b->ct, b->pk, sizeof b->pk) == WFAS3_OK) ? 1U : 0U;
    ok &= (wfas3_sym_decrypt(&copy, b->back, sizeof b->back, b->ct, sizeof b->ct) == WFAS3_OK) ? 1U : 0U;
    ok &= wfas3_ct_eq(b->back, b->pk, sizeof b->pk);
    ok &= wfas3_ct_eq(copy.h, s->h, WFAS3_HASH_LEN);
    b->ct[7] ^= 0x01U;
    copy.n = 0U;
    ok &= (wfas3_open(b->back, sizeof b->back, copy.k, 0U, copy.h, WFAS3_HASH_LEN,
                      b->ct, sizeof b->ct) == WFAS3_E_AUTH) ? 1U : 0U;
    b->ct[7] ^= 0x01U;
    wfas3_sym_wipe(&copy);
    return ok;
}

/* static DH both ways, low-order point, KEM round trip, DRBG, SHA-512. */
static uint32_t wfas3_st_part2(wfas3_sym *s, wfas3_st_buf *b)
{
    wfas3_rng rng;
    uint32_t ok = 1U;

    wfas3_st_fill(b->s_priv, 32U, 0x40U);
    wfas_tp_x25519_public(b->s_pub, b->s_priv);
    ok &= wfas_tp_x25519(b->dh1, b->e_priv, b->s_pub);
    ok &= wfas_tp_x25519(b->dh2, b->s_priv, b->e_pub);
    ok &= wfas3_ct_eq(b->dh1, b->dh2, 32U);
    ok &= (wfas3_sym_mix_key(s, b->dh1, 32U) == WFAS3_OK) ? 1U : 0U;
    memset(b->wide, 0, 32U);
    ok &= 1U ^ wfas_tp_x25519(b->dh2, b->e_priv, b->wide);
    wfas3_st_fill(b->rnd, 32U, 0xA0U);
    wfas_tp_mlkem768_encaps(b->back, b->ss1, b->pk, b->rnd);
    ok &= (wfas3_sym_encrypt(s, b->ct, sizeof b->ct, b->back, WFAS_TP_MLKEM_CT) == WFAS3_OK) ? 1U : 0U;
    wfas_tp_mlkem768_decaps(b->ss2, b->back, b->sk);
    ok &= wfas3_ct_eq(b->ss1, b->ss2, 32U);
    ok &= (wfas3_sym_mix_key(s, b->ss1, 32U) == WFAS3_OK) ? 1U : 0U;
    rng.ready = 0U;
    ok &= (wfas3_rng_bytes(&rng, b->wide, 32U) == WFAS3_E_ENTROPY) ? 1U : 0U;
    wfas3_st_fill(b->rnd, WFAS_TP_DRBG_SEED, 0xC0U);
    wfas3_rng_start(&rng, b->rnd);
    ok &= (wfas3_rng_bytes(&rng, b->wide, 32U) == WFAS3_OK) ? 1U : 0U;
    wfas3_st_fill(b->rnd, 32U, 0x10U);
    ok &= (wfas3_rng_reseed(&rng, b->rnd) == WFAS3_OK) ? 1U : 0U;
    ok &= (wfas3_rng_bytes(&rng, &b->wide[32], 32U) == WFAS3_OK) ? 1U : 0U;
    ok &= (wfas3_sym_mix_key(s, b->wide, 64U) == WFAS3_OK) ? 1U : 0U;
    wfas_tp_sha512(b->wide, s->h, WFAS3_HASH_LEN);
    ok &= (wfas3_sym_mix_hash(s, b->wide, 64U) == WFAS3_OK) ? 1U : 0U;
    wfas3_wipe(rng.drbg.k, sizeof rng.drbg.k);
    wfas3_wipe(rng.drbg.v, sizeof rng.drbg.v);
    return ok;
}

wfas3_status wfas3_selftest(void)
{
    /* Known answer, produced by the independent reference in tests/ref.py. */
    static const uint8_t expect[16] = {
        0xF8U, 0x51U, 0x0AU, 0x52U, 0xD7U, 0xE5U, 0x44U, 0xA6U,
        0xF9U, 0x8BU, 0x51U, 0x7FU, 0x3CU, 0xC7U, 0x12U, 0x86U
    };
    wfas3_st_buf b;
    wfas3_sym s;
    uint8_t key[32];
    uint8_t tag[16];
    uint32_t ok;

    ok = wfas3_st_part1(&s, &b);
    ok &= wfas3_st_part2(&s, &b);
    ok &= (wfas3_kdf(key, sizeof key, s.ck, WFAS3_LBL("selftest"), s.h, WFAS3_HASH_LEN)
           == WFAS3_OK) ? 1U : 0U;
    ok &= (wfas3_mac(tag, sizeof tag, key, s.h, WFAS3_HASH_LEN) == WFAS3_OK) ? 1U : 0U;
    ok &= wfas3_ct_eq(tag, expect, sizeof tag);
    wfas3_sym_wipe(&s);
    wfas3_wipe(key, sizeof key);
    wfas3_wipe(b.sk, sizeof b.sk);
    wfas3_wipe(b.e_priv, sizeof b.e_priv);
    wfas3_wipe(b.s_priv, sizeof b.s_priv);
    return (ok == 1U) ? WFAS3_OK : WFAS3_E_SELFTEST;
}
