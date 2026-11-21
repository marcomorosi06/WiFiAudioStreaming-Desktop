/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 * ---------------------------------------------------------------------------
 * wfas3_jni.c - JNI shell around the WFAS v3 core (core/wfas.h).
 *
 * The core is used exactly as shipped. This file only:
 *  - keeps the device state outside the Java heap, in memory that is locked
 *    where the system allows it, excluded from core dumps and wiped on free;
 *  - gives the core its clock (one that keeps running in suspend) and its
 *    entropy (the system generator), so that neither ever passes through Java;
 *  - copies arguments in and results out, with every length checked, and wipes
 *    every buffer that held a secret on every path.
 *
 * Only JNI_OnLoad is exported: the methods are bound with RegisterNatives to
 * the class named by WFAS3_JNI_CLASS (set by the build).
 * ------------------------------------------------------------------------- */

#if defined(__linux__) || defined(__ANDROID__)
#define _GNU_SOURCE
#endif

#include <jni.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>

#include "wfas.h"
#include "wfas3_glue.h"

#if defined(_WIN32)
#include <windows.h>
#include <bcrypt.h>
#else
#include <errno.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>
#if defined(__APPLE__)
#include <sys/random.h>
#elif defined(__linux__) || defined(__ANDROID__)
#include <sys/syscall.h>
#endif
#endif

#ifndef WFAS3_JNI_CLASS
#error "WFAS3_JNI_CLASS must name the Java class of the natives"
#endif

#if defined(_WIN32)
#define W3_EXPORT __declspec(dllexport)    /* the only export: no auto-export */
#else
#define W3_EXPORT __attribute__((visibility("default")))
#endif

/* Layout of the output buffer shared with Kotlin (little-endian). */
#define W3_SER_HEADER   256
#define W3_SER_DGRAM    W3_SER_HEADER
#define W3_SER_AUDIO    (W3_SER_DGRAM + WFAS3_MAX_DATAGRAM)
#define W3_SER_LEN      (W3_SER_AUDIO + WFAS3_MAX_AUDIO_PAYLOAD)
#define W3_BEACON_LEN   72
#define W3_INFO_LONGS   40
#define W3_PAIR_INFO    48
#define W3_CODE_MAX     96

typedef struct w3_ctx {
    uint32_t     magic;
    volatile int busy;
    size_t       map_len;
    wfas3_device dev;
    wfas3_out    out;
    uint8_t      ser[W3_SER_HEADER];
} w3_ctx;

#define W3_MAGIC 0x57463343U

/* -- wiping and secure memory ------------------------------------------------ */

void w3_wipe(void *p, size_t n)
{
    volatile uint8_t *v = (volatile uint8_t *)p;
    size_t i;

    for (i = 0U; i < n; i++) {
        v[i] = 0U;
    }
}

static void *w3_secure_alloc(size_t len)
{
#if defined(_WIN32)
    void *p = VirtualAlloc(NULL, len, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);

    if (p != NULL) {
        (void)VirtualLock(p, len);                 /* best effort */
    }
    return p;
#else
    void *p = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);

    if (p == MAP_FAILED) {
        return NULL;
    }
#if defined(MADV_DONTDUMP)
    (void)madvise(p, len, MADV_DONTDUMP);         /* never in a core dump */
#endif
#if defined(MADV_WIPEONFORK)
    (void)madvise(p, len, MADV_WIPEONFORK);       /* a child never sees it */
#endif
    (void)mlock(p, len);                           /* best effort: not swapped */
    return p;
#endif
}

static void w3_secure_free(void *p, size_t len)
{
    if (p == NULL) {
        return;
    }
    w3_wipe(p, len);
#if defined(_WIN32)
    (void)VirtualUnlock(p, len);
    (void)VirtualFree(p, 0, MEM_RELEASE);
#else
    (void)munlock(p, len);
    (void)munmap(p, len);
#endif
}

/* -- clock and entropy ------------------------------------------------------- */

/* Microseconds on a monotonic clock that keeps counting in suspend. */
uint64_t w3_clock_us(void)
{
#if defined(_WIN32)
    typedef void (WINAPI *qitp_fn)(PULONGLONG);
    static qitp_fn qitp = NULL;
    static int qitp_checked = 0;
    ULONGLONG t = 0;

    if (qitp_checked == 0) {
        HMODULE k = GetModuleHandleW(L"kernelbase.dll");

        if (k != NULL) {
            qitp = (qitp_fn)(void (*)(void))GetProcAddress(k, "QueryInterruptTimePrecise");
        }
        qitp_checked = 1;
    }
    if (qitp != NULL) {
        qitp(&t);                                  /* 100 ns units, includes sleep */
        return (uint64_t)t / 10U;
    }
    return (uint64_t)GetTickCount64() * 1000U;     /* includes sleep, 1 ms steps */
#elif defined(__APPLE__)
    return clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1000U;   /* counts in sleep */
#else
    struct timespec ts;

    if (clock_gettime(CLOCK_BOOTTIME, &ts) != 0) {
        return 0U;
    }
    return ((uint64_t)ts.tv_sec * 1000000U) + ((uint64_t)ts.tv_nsec / 1000U);
#endif
}

/* n bytes from the system generator. 1 on success. */
int w3_random(uint8_t *p, size_t n)
{
#if defined(_WIN32)
    return (BCryptGenRandom(NULL, p, (ULONG)n, BCRYPT_USE_SYSTEM_PREFERRED_RNG) == 0) ? 1 : 0;
#elif defined(__APPLE__)
    size_t done = 0U;

    while (done < n) {
        size_t k = ((n - done) > 256U) ? 256U : (n - done);

        if (getentropy(&p[done], k) != 0) {
            return 0;
        }
        done += k;
    }
    return 1;
#else
    size_t done = 0U;
    int fd;

#if defined(SYS_getrandom)
    while (done < n) {
        long r = syscall(SYS_getrandom, &p[done], n - done, 0);

        if (r > 0) {
            done += (size_t)r;
        } else if ((r < 0) && (errno == EINTR)) {
            continue;
        } else {
            break;
        }
    }
    if (done == n) {
        return 1;
    }
#endif
    fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return 0;
    }
    while (done < n) {
        ssize_t r = read(fd, &p[done], n - done);

        if (r > 0) {
            done += (size_t)r;
        } else if ((r < 0) && (errno == EINTR)) {
            continue;
        } else {
            break;
        }
    }
    (void)close(fd);
    return (done == n) ? 1 : 0;
#endif
}

/* -- context ----------------------------------------------------------------- */

static w3_ctx *w3_get(jlong h)
{
    w3_ctx *c = (w3_ctx *)(uintptr_t)h;

    if ((c == NULL) || (c->magic != W3_MAGIC)) {
        return NULL;
    }
    return c;
}

/* One caller at a time. The Kotlin side holds a lock; this only turns a misuse
 * into an error instead of a corrupted state. */
static int w3_enter(w3_ctx *c)
{
    return (__sync_lock_test_and_set(&c->busy, 1) == 0) ? 1 : 0;
}

static void w3_leave(w3_ctx *c)
{
    __sync_lock_release(&c->busy);
}

static void w3_time(w3_ctx *c)
{
    (void)wfas3_set_time(&c->dev, w3_clock_us());
}

/* Fresh entropy for the core. 1 on success. */
static int w3_entropy(w3_ctx *c)
{
    uint8_t e[WFAS3_ENTROPY_LEN];
    int ok = w3_random(e, sizeof e);

    if (ok == 1) {
        ok = (wfas3_add_entropy(&c->dev, e) == WFAS3_OK) ? 1 : 0;
    }
    w3_wipe(e, sizeof e);
    return ok;
}

/* -- serialisation ----------------------------------------------------------- */

static void w3_put32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)(v & 0xFFU);
    p[1] = (uint8_t)((v >> 8) & 0xFFU);
    p[2] = (uint8_t)((v >> 16) & 0xFFU);
    p[3] = (uint8_t)((v >> 24) & 0xFFU);
}

static void w3_put64(uint8_t *p, uint64_t v)
{
    w3_put32(p, (uint32_t)(v & 0xFFFFFFFFU));
    w3_put32(&p[4], (uint32_t)(v >> 32));
}

static uint32_t w3_get32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void w3_put_addr(uint8_t *p, const wfas3_addr *a)
{
    memcpy(p, a->ip, 16U);
    w3_put32(&p[16], a->port);
}

static void w3_put_config(uint8_t *p, const wfas3_config *cfg)
{
    p[0] = cfg->flags;
    p[1] = cfg->codec;
    p[2] = cfg->channels;
    p[3] = cfg->bits;
    w3_put32(&p[4], cfg->sample_rate);
    w3_put32(&p[8], cfg->frame_samples);
    w3_put32(&p[12], cfg->payload_bytes);
    w3_put32(&p[16], cfg->latency_ms);
}

static void w3_put_beacon(uint8_t *p, const wfas3_beacon *b)
{
    w3_put_addr(p, &b->addr);                      /* 0..20 */
    w3_put32(&p[20], b->used);                     /* 20..24 */
    memcpy(&p[24], b->nonce, 16U);                 /* 24..40 */
    memcpy(&p[40], b->name, 32U);                  /* 40..72 */
}

static void w3_get_beacon(const uint8_t *p, wfas3_beacon *b)
{
    memset(b, 0, sizeof *b);
    memcpy(b->addr.ip, p, 16U);
    b->addr.port = (uint16_t)(w3_get32(&p[16]) & 0xFFFFU);
    b->used = w3_get32(&p[20]);
    memcpy(b->nonce, &p[24], 16U);
    memcpy(b->name, &p[40], 32U);
}

/* A config from Kotlin: flags, codec, channels, bits, rate, frame, payload, latency. */
static int w3_get_config(JNIEnv *env, jintArray a, wfas3_config *cfg)
{
    jint v[8];

    memset(cfg, 0, sizeof *cfg);
    if ((a == NULL) || ((*env)->GetArrayLength(env, a) != 8)) {
        return 0;
    }
    (*env)->GetIntArrayRegion(env, a, 0, 8, v);
    if ((*env)->ExceptionCheck(env)) {
        return 0;
    }
    if ((v[0] < 0) || (v[0] > 255) || (v[1] < 0) || (v[1] > 255) || (v[2] < 0) || (v[2] > 255) ||
        (v[3] < 0) || (v[3] > 255) || (v[4] < 0) || (v[5] < 0) || (v[5] > 65535) ||
        (v[6] < 0) || (v[6] > 65535) || (v[7] < 0) || (v[7] > 65535)) {
        return 0;
    }
    cfg->flags = (uint8_t)v[0];
    cfg->codec = (uint8_t)v[1];
    cfg->channels = (uint8_t)v[2];
    cfg->bits = (uint8_t)v[3];
    cfg->sample_rate = (uint32_t)v[4];
    cfg->frame_samples = (uint16_t)v[5];
    cfg->payload_bytes = (uint16_t)v[6];
    cfg->latency_ms = (uint16_t)v[7];
    return 1;
}

static int w3_get_addr(JNIEnv *env, jbyteArray ip, jint port, wfas3_addr *a)
{
    memset(a, 0, sizeof *a);
    if ((ip == NULL) || ((*env)->GetArrayLength(env, ip) != 16) || (port < 0) || (port > 65535)) {
        return 0;
    }
    (*env)->GetByteArrayRegion(env, ip, 0, 16, (jbyte *)a->ip);
    if ((*env)->ExceptionCheck(env)) {
        return 0;
    }
    a->port = (uint16_t)port;
    return 1;
}

/* Copy the status and the output of the last call into the Kotlin buffer. */
static jint w3_emit(JNIEnv *env, w3_ctx *c, wfas3_status st, jbyteArray outbuf)
{
    uint8_t *h = c->ser;
    const wfas3_out *o = &c->out;
    size_t dl = 0U;
    size_t al = 0U;

    if ((outbuf == NULL) || ((*env)->GetArrayLength(env, outbuf) < (jsize)W3_SER_LEN)) {
        return WFAS3_E_ARG;
    }
    memset(h, 0, W3_SER_HEADER);
    w3_put32(&h[0], (uint32_t)st);
    if (st == WFAS3_OK) {
        dl = (o->len <= WFAS3_MAX_DATAGRAM) ? o->len : 0U;
        al = ((o->events & WFAS3_EV_AUDIO) != 0U) && (o->audio_len <= WFAS3_MAX_AUDIO_PAYLOAD)
             ? o->audio_len : 0U;
        w3_put32(&h[4], o->events);
        w3_put32(&h[8], o->pair);
        w3_put32(&h[12], o->session);
        w3_put32(&h[16], o->status);
        w3_put32(&h[20], o->broadcast);
        w3_put32(&h[24], o->stream_on);
        w3_put32(&h[28], o->stream);
        w3_put32(&h[32], o->epoch);
        w3_put32(&h[36], o->code);
        w3_put32(&h[40], o->ask);
        w3_put32(&h[44], o->pflags);
        w3_put32(&h[48], (uint32_t)dl);
        w3_put32(&h[52], (uint32_t)al);
        w3_put64(&h[56], o->sample_pos);
        w3_put64(&h[64], o->capture_us);
        w3_put64(&h[72], o->playout_us);
        w3_put_addr(&h[80], &o->to);
        w3_put_addr(&h[100], &o->addr);
        w3_put_config(&h[120], &o->config);
        memcpy(&h[140], o->name, 32U);
        w3_put_beacon(&h[172], &o->beacon);
    }
    (*env)->SetByteArrayRegion(env, outbuf, 0, W3_SER_HEADER, (const jbyte *)h);
    if (dl > 0U) {
        (*env)->SetByteArrayRegion(env, outbuf, W3_SER_DGRAM, (jsize)dl, (const jbyte *)o->dgram);
    }
    if (al > 0U) {
        (*env)->SetByteArrayRegion(env, outbuf, W3_SER_AUDIO, (jsize)al, (const jbyte *)o->audio);
    }
    memset(h, 0, W3_SER_HEADER);
    return (jint)st;
}

/* Clear the output before a call: no field of a previous one survives. */
static void w3_out_clear(w3_ctx *c)
{
    w3_wipe(&c->out, sizeof c->out);
}

/* -- natives ----------------------------------------------------------------- */

static jint JNICALL n_selftest(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return (jint)wfas3_selftest();
}

static jlong JNICALL n_clock_us(JNIEnv *env, jclass cls)
{
    (void)env;
    (void)cls;
    return (jlong)w3_clock_us();
}

static jint JNICALL n_layout(JNIEnv *env, jclass cls, jint what)
{
    (void)env;
    (void)cls;
    switch (what) {
    case 0: return W3_SER_LEN;
    case 1: return W3_SER_DGRAM;
    case 2: return W3_SER_AUDIO;
    case 3: return WFAS3_DEVICE_RECORD_LEN;
    case 4: return WFAS3_PAIR_RECORD_LEN;
    case 5: return WFAS3_MAX_PAIRS;
    case 6: return WFAS3_MAX_SESSIONS;
    case 7: return W3_BEACON_LEN;
    case 8: return W3_INFO_LONGS;
    case 9: return W3_PAIR_INFO;
    case 10: return WFAS3_UDP_PORT;
    default: return -1;
    }
}

/* Start a device. record: the stored device record or null. recout receives
 * the record to store before any other call. Returns a handle or 0. */
static jlong JNICALL n_create(JNIEnv *env, jclass cls, jbyteArray record, jbyteArray recout)
{
    uint8_t seed[WFAS3_SEED_LEN];
    uint8_t rec[WFAS3_DEVICE_RECORD_LEN];
    uint32_t valid = 0U;
    size_t len = sizeof(w3_ctx);
    w3_ctx *c;
    wfas3_status st;

    (void)cls;
    if ((recout == NULL) || ((*env)->GetArrayLength(env, recout) != (jsize)WFAS3_DEVICE_RECORD_LEN)) {
        return 0;
    }
    memset(rec, 0, sizeof rec);
    if (record != NULL) {
        if ((*env)->GetArrayLength(env, record) != (jsize)WFAS3_DEVICE_RECORD_LEN) {
            return 0;
        }
        (*env)->GetByteArrayRegion(env, record, 0, (jsize)WFAS3_DEVICE_RECORD_LEN, (jbyte *)rec);
        if ((*env)->ExceptionCheck(env)) {
            w3_wipe(rec, sizeof rec);
            return 0;
        }
        valid = 1U;
    }
    if (wfas3_selftest() != WFAS3_OK) {
        w3_wipe(rec, sizeof rec);
        return 0;
    }
    c = (w3_ctx *)w3_secure_alloc(len);
    if (c == NULL) {
        w3_wipe(rec, sizeof rec);
        return 0;
    }
    memset(c, 0, len);
    c->map_len = len;
    if (w3_random(seed, sizeof seed) != 1) {
        w3_wipe(rec, sizeof rec);
        w3_secure_free(c, len);
        return 0;
    }
    st = wfas3_device_init(&c->dev, seed, rec, valid, w3_clock_us());
    w3_wipe(seed, sizeof seed);
    if (st != WFAS3_OK) {
        w3_wipe(rec, sizeof rec);
        w3_secure_free(c, len);
        return 0;
    }
    (*env)->SetByteArrayRegion(env, recout, 0, (jsize)WFAS3_DEVICE_RECORD_LEN, (const jbyte *)rec);
    w3_wipe(rec, sizeof rec);
    if ((*env)->ExceptionCheck(env)) {
        w3_secure_free(c, len);
        return 0;
    }
    c->magic = W3_MAGIC;
    return (jlong)(uintptr_t)c;
}

static void JNICALL n_destroy(JNIEnv *env, jclass cls, jlong h)
{
    w3_ctx *c = w3_get(h);

    (void)env;
    (void)cls;
    if ((c != NULL) && (w3_enter(c) == 1)) {
        c->magic = 0U;
        w3_secure_free(c, c->map_len);
    }
}

static jlong JNICALL n_next_deadline(JNIEnv *env, jclass cls, jlong h)
{
    w3_ctx *c = w3_get(h);
    uint64_t d = UINT64_MAX;

    (void)env;
    (void)cls;
    if ((c != NULL) && (w3_enter(c) == 1)) {
        d = wfas3_next_deadline(&c->dev);
        w3_leave(c);
    }
    return (d > (uint64_t)INT64_MAX) ? (jlong)INT64_MAX : (jlong)d;
}

static jint JNICALL n_set_policy(JNIEnv *env, jclass cls, jlong h, jint busy, jint codec_mask,
                                 jint max_channels, jintArray suggest)
{
    w3_ctx *c = w3_get(h);
    wfas3_policy p;
    wfas3_status st = WFAS3_E_ARG;

    (void)cls;
    memset(&p, 0, sizeof p);
    if ((c == NULL) || (busy < 0) || (codec_mask < 0) || (max_channels < 0) ||
        (w3_get_config(env, suggest, &p.suggest) == 0)) {
        return WFAS3_E_ARG;
    }
    p.busy = (uint32_t)busy;
    p.codec_mask = (uint32_t)codec_mask;
    p.max_channels = (uint32_t)max_channels;
    if (w3_enter(c) == 1) {
        w3_time(c);
        st = wfas3_set_policy(&c->dev, &p);
        w3_leave(c);
    } else {
        st = WFAS3_E_STATE;
    }
    return (jint)st;
}

static jint JNICALL n_device_export(JNIEnv *env, jclass cls, jlong h, jbyteArray out)
{
    w3_ctx *c = w3_get(h);
    uint8_t rec[WFAS3_DEVICE_RECORD_LEN];
    wfas3_status st = WFAS3_E_ARG;

    (void)cls;
    if ((c == NULL) || (out == NULL) || ((*env)->GetArrayLength(env, out) != (jsize)sizeof rec)) {
        return WFAS3_E_ARG;
    }
    if (w3_enter(c) == 1) {
        st = wfas3_device_export(&c->dev, rec);
        w3_leave(c);
        if (st == WFAS3_OK) {
            (*env)->SetByteArrayRegion(env, out, 0, (jsize)sizeof rec, (const jbyte *)rec);
        }
    } else {
        st = WFAS3_E_STATE;
    }
    w3_wipe(rec, sizeof rec);
    return (jint)st;
}

static jint JNICALL n_pair_import(JNIEnv *env, jclass cls, jlong h, jint slot, jbyteArray record)
{
    w3_ctx *c = w3_get(h);
    uint8_t rec[WFAS3_PAIR_RECORD_LEN];
    wfas3_status st = WFAS3_E_ARG;

    (void)cls;
    if ((c == NULL) || (slot < 0) || (record == NULL) ||
        ((*env)->GetArrayLength(env, record) != (jsize)sizeof rec)) {
        return WFAS3_E_ARG;
    }
    (*env)->GetByteArrayRegion(env, record, 0, (jsize)sizeof rec, (jbyte *)rec);
    if (!(*env)->ExceptionCheck(env)) {
        if (w3_enter(c) == 1) {
            w3_time(c);
            st = wfas3_pair_import(&c->dev, (uint32_t)slot, rec);
            w3_leave(c);
        } else {
            st = WFAS3_E_STATE;
        }
    }
    w3_wipe(rec, sizeof rec);
    return (jint)st;
}

static jint JNICALL n_pair_export(JNIEnv *env, jclass cls, jlong h, jint slot, jbyteArray out)
{
    w3_ctx *c = w3_get(h);
    uint8_t rec[WFAS3_PAIR_RECORD_LEN];
    wfas3_status st = WFAS3_E_ARG;

    (void)cls;
    if ((c == NULL) || (slot < 0) || (out == NULL) ||
        ((*env)->GetArrayLength(env, out) != (jsize)sizeof rec)) {
        return WFAS3_E_ARG;
    }
    if (w3_enter(c) == 1) {
        st = wfas3_pair_export(&c->dev, (uint32_t)slot, rec);
        w3_leave(c);
        if (st == WFAS3_OK) {
            (*env)->SetByteArrayRegion(env, out, 0, (jsize)sizeof rec, (const jbyte *)rec);
        }
    } else {
        st = WFAS3_E_STATE;
    }
    w3_wipe(rec, sizeof rec);
    return (jint)st;
}

/* Public facts of a pair for display: used, flags, perms, 0, seq_hi (8),
 * 8 reserved, raw declared name (32). Nothing secret. Read the same way as
 * the reference client lists its pairs. */
static jint JNICALL n_pair_info(JNIEnv *env, jclass cls, jlong h, jint slot, jbyteArray out)
{
    w3_ctx *c = w3_get(h);
    uint8_t b[W3_PAIR_INFO];

    (void)cls;
    if ((c == NULL) || (slot < 0) || (slot >= (jint)WFAS3_MAX_PAIRS) || (out == NULL) ||
        ((*env)->GetArrayLength(env, out) != (jsize)sizeof b)) {
        return WFAS3_E_ARG;
    }
    memset(b, 0, sizeof b);
    if (w3_enter(c) == 0) {
        return WFAS3_E_STATE;
    }
    {
        const wfas3_pair *p = &c->dev.pairs[slot];

        if (p->used == 1U) {
            b[0] = 1U;
            b[1] = (uint8_t)(p->flags & 0xFFU);
            b[2] = (uint8_t)(p->perms & 0xFFU);
            w3_put64(&b[4], p->seq_hi);
            memcpy(&b[16], p->name, 32U);
        }
    }
    w3_leave(c);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)sizeof b, (const jbyte *)b);
    return WFAS3_OK;
}

/* The common shape of a call that fills an output: enter, set the time,
 * call, retry once with fresh entropy if asked, emit, leave. */
#define W3_CALL(c, env, outbuf, expr)                                   \
    do {                                                                \
        wfas3_status st_;                                               \
        jint r_;                                                        \
        if (w3_enter(c) == 0) {                                         \
            return WFAS3_E_STATE;                                       \
        }                                                               \
        w3_out_clear(c);                                                \
        w3_time(c);                                                     \
        st_ = (expr);                                                   \
        if ((st_ == WFAS3_E_ENTROPY) && (w3_entropy(c) == 1)) {         \
            w3_out_clear(c);                                            \
            w3_time(c);                                                 \
            st_ = (expr);                                               \
        }                                                               \
        r_ = w3_emit(env, c, st_, outbuf);                              \
        w3_leave(c);                                                    \
        return r_;                                                      \
    } while (0)

static jint JNICALL n_pair_remove(JNIEnv *env, jclass cls, jlong h, jint slot, jbyteArray outbuf)
{
    w3_ctx *c = w3_get(h);

    (void)cls;
    if ((c == NULL) || (slot < 0)) {
        return WFAS3_E_ARG;
    }
    W3_CALL(c, env, outbuf, wfas3_pair_remove(&c->dev, (uint32_t)slot, &c->out));
}

static jint JNICALL n_find(JNIEnv *env, jclass cls, jlong h, jint slot, jint on, jbyteArray outbuf)
{
    w3_ctx *c = w3_get(h);

    (void)cls;
    if ((c == NULL) || (slot < 0) || (on < 0) || (on > 1)) {
        return WFAS3_E_ARG;
    }
    W3_CALL(c, env, outbuf, wfas3_find(&c->dev, (uint32_t)slot, (uint32_t)on, &c->out));
}

static jint JNICALL n_connect(JNIEnv *env, jclass cls, jlong h, jint slot, jbyteArray ip, jint port,
                              jintArray cfg, jbyteArray outbuf)
{
    w3_ctx *c = w3_get(h);
    wfas3_addr a;
    wfas3_config k;

    (void)cls;
    if ((c == NULL) || (slot < 0) || (w3_get_addr(env, ip, port, &a) == 0) ||
        (w3_get_config(env, cfg, &k) == 0)) {
        return WFAS3_E_ARG;
    }
    W3_CALL(c, env, outbuf, wfas3_connect(&c->dev, (uint32_t)slot, &a, &k, &c->out));
}

static jint JNICALL n_poll(JNIEnv *env, jclass cls, jlong h, jbyteArray outbuf)
{
    w3_ctx *c = w3_get(h);

    (void)cls;
    if (c == NULL) {
        return WFAS3_E_ARG;
    }
    W3_CALL(c, env, outbuf, wfas3_poll(&c->dev, &c->out));
}

static jint JNICALL n_receive(JNIEnv *env, jclass cls, jlong h, jbyteArray data, jint len,
                              jbyteArray ip, jint port, jbyteArray outbuf)
{
    w3_ctx *c = w3_get(h);
    uint8_t d[WFAS3_MAX_DATAGRAM];
    wfas3_addr a;

    (void)cls;
    if ((c == NULL) || (data == NULL) || (len <= 0) || (len > (jint)WFAS3_MAX_DATAGRAM) ||
        ((*env)->GetArrayLength(env, data) < len) || (w3_get_addr(env, ip, port, &a) == 0)) {
        return WFAS3_E_DROP;
    }
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)d);
    if ((*env)->ExceptionCheck(env)) {
        return WFAS3_E_DROP;
    }
    W3_CALL(c, env, outbuf, wfas3_receive(&c->dev, d, (size_t)len, &a, &c->out));
}

static jint JNICALL n_send_audio(JNIEnv *env, jclass cls, jlong h, jint session, jlong pos, jlong cap_us,
                                 jbyteArray data, jint off, jint len, jbyteArray outbuf)
{
    w3_ctx *c = w3_get(h);
    uint8_t d[WFAS3_MAX_AUDIO_PAYLOAD];

    (void)cls;
    if ((c == NULL) || (session < 0) || (pos < 0) || (cap_us < 0) || (data == NULL) || (off < 0) ||
        (len <= 0) || (len > (jint)WFAS3_MAX_AUDIO_PAYLOAD) ||
        ((*env)->GetArrayLength(env, data) - off < len)) {
        return WFAS3_E_ARG;
    }
    (*env)->GetByteArrayRegion(env, data, off, len, (jbyte *)d);
    if ((*env)->ExceptionCheck(env)) {
        return WFAS3_E_ARG;
    }
    W3_CALL(c, env, outbuf, wfas3_send_audio(&c->dev, (uint32_t)session, (uint64_t)pos,
                                             (uint64_t)cap_us, d, (size_t)len, &c->out));
}

static jint JNICALL n_group_send(JNIEnv *env, jclass cls, jlong h, jlong pos, jlong cap_us,
                                 jbyteArray data, jint off, jint len, jbyteArray outbuf)
{
    w3_ctx *c = w3_get(h);
    uint8_t d[WFAS3_MAX_MCAST_PAYLOAD];

    (void)cls;
    if ((c == NULL) || (pos < 0) || (cap_us < 0) || (data == NULL) || (off < 0) || (len <= 0) ||
        (len > (jint)WFAS3_MAX_MCAST_PAYLOAD) || ((*env)->GetArrayLength(env, data) - off < len)) {
        return WFAS3_E_ARG;
    }
    (*env)->GetByteArrayRegion(env, data, off, len, (jbyte *)d);
    if ((*env)->ExceptionCheck(env)) {
        return WFAS3_E_ARG;
    }
    W3_CALL(c, env, outbuf, wfas3_group_send(&c->dev, (uint64_t)pos, (uint64_t)cap_us, d,
                                             (size_t)len, &c->out));
}

/* Calls without an output: enter, set the time, call, leave. */
#define W3_SIMPLE(c, expr)                                              \
    do {                                                                \
        wfas3_status st_;                                               \
        if (w3_enter(c) == 0) {                                         \
            return WFAS3_E_STATE;                                       \
        }                                                               \
        w3_time(c);                                                     \
        st_ = (expr);                                                   \
        w3_leave(c);                                                    \
        return (jint)st_;                                               \
    } while (0)

static jint JNICALL n_stream_want(JNIEnv *env, jclass cls, jlong h, jint session, jint want)
{
    w3_ctx *c = w3_get(h);

    (void)env;
    (void)cls;
    if ((c == NULL) || (session < 0) || (want < 0) || (want > 1)) {
        return WFAS3_E_ARG;
    }
    W3_SIMPLE(c, wfas3_stream_want(&c->dev, (uint32_t)session, (uint32_t)want));
}

static jint JNICALL n_stream_params(JNIEnv *env, jclass cls, jlong h, jint session, jintArray cfg)
{
    w3_ctx *c = w3_get(h);
    wfas3_config k;

    (void)cls;
    if ((c == NULL) || (session < 0) || (w3_get_config(env, cfg, &k) == 0)) {
        return WFAS3_E_ARG;
    }
    W3_SIMPLE(c, wfas3_stream_params(&c->dev, (uint32_t)session, &k));
}

static jint JNICALL n_mic_off(JNIEnv *env, jclass cls, jlong h, jint session)
{
    w3_ctx *c = w3_get(h);

    (void)env;
    (void)cls;
    if ((c == NULL) || (session < 0)) {
        return WFAS3_E_ARG;
    }
    W3_SIMPLE(c, wfas3_mic_off(&c->dev, (uint32_t)session));
}

static jint JNICALL n_audio_late(JNIEnv *env, jclass cls, jlong h, jint session, jlong late_us)
{
    w3_ctx *c = w3_get(h);

    (void)env;
    (void)cls;
    if ((c == NULL) || (session < 0) || (late_us < 0)) {
        return WFAS3_E_ARG;
    }
    W3_SIMPLE(c, wfas3_audio_late(&c->dev, (uint32_t)session, (uint64_t)late_us));
}

static jint JNICALL n_set_buffer_ms(JNIEnv *env, jclass cls, jlong h, jint session, jint ms)
{
    w3_ctx *c = w3_get(h);

    (void)env;
    (void)cls;
    if ((c == NULL) || (session < 0) || (ms < 0)) {
        return WFAS3_E_ARG;
    }
    W3_SIMPLE(c, wfas3_set_buffer_ms(&c->dev, (uint32_t)session, (uint32_t)ms));
}

static jint JNICALL n_session_close(JNIEnv *env, jclass cls, jlong h, jint session, jint reason,
                                    jbyteArray outbuf)
{
    w3_ctx *c = w3_get(h);

    (void)cls;
    if ((c == NULL) || (session < 0) || (reason < 0)) {
        return WFAS3_E_ARG;
    }
    W3_CALL(c, env, outbuf, wfas3_session_close(&c->dev, (uint32_t)session, (uint32_t)reason, &c->out));
}

static jint JNICALL n_session_info(JNIEnv *env, jclass cls, jlong h, jint session, jlongArray out)
{
    w3_ctx *c = w3_get(h);
    wfas3_info i;
    jlong v[W3_INFO_LONGS];
    wfas3_status st;

    (void)cls;
    if ((c == NULL) || (session < 0) || (out == NULL) ||
        ((*env)->GetArrayLength(env, out) != (jsize)W3_INFO_LONGS)) {
        return WFAS3_E_ARG;
    }
    memset(&i, 0, sizeof i);
    memset(v, 0, sizeof v);
    if (w3_enter(c) == 0) {
        return WFAS3_E_STATE;
    }
    w3_time(c);
    st = wfas3_session_info(&c->dev, (uint32_t)session, &i);
    w3_leave(c);
    if (st == WFAS3_OK) {
        v[0] = i.state;
        v[1] = i.pair;
        v[2] = i.initiator;
        v[3] = i.source;
        v[4] = i.microphone;
        v[5] = i.synchronised;
        v[6] = 0;                                   /* the address stays in the core */
        v[7] = 0;
        v[8] = i.tx_exists;
        v[9] = i.tx_on;
        v[10] = i.tx_started;
        v[11] = i.tx_epoch;
        v[12] = i.peer_received;
        v[13] = i.peer_lost;
        v[14] = i.peer_jitter_us;
        v[15] = i.peer_buffer_ms;
        v[16] = i.rx_exists;
        v[17] = i.rx_on;
        v[18] = i.rx_wanted;
        v[19] = i.rx_epoch;
        v[20] = i.rx_received;
        v[21] = i.rx_lost;
        v[22] = i.rx_jitter_us;
        v[23] = i.time_valid;
        v[24] = (jlong)i.offset_us;
        v[25] = i.delay_ms;
        v[26] = i.rekeys;
        v[27] = i.rekeying;
        v[28] = i.path_check;
        v[29] = i.silent;
        v[30] = (i.silence_ms > (uint64_t)INT64_MAX) ? INT64_MAX : (jlong)i.silence_ms;
        v[31] = i.tx_config.codec;
        v[32] = i.tx_config.sample_rate;
        v[33] = i.tx_config.channels;
        v[34] = i.rx_config.codec;
        v[35] = i.rx_config.sample_rate;
        v[36] = i.rx_config.channels;
        v[37] = i.rx_config.payload_bytes;
        v[38] = i.rx_config.frame_samples;
        v[39] = i.rx_config.bits;
        (*env)->SetLongArrayRegion(env, out, 0, (jsize)W3_INFO_LONGS, v);
    }
    return (jint)st;
}

/* -- pairing ----------------------------------------------------------------- */

static jstring w3_new_string(JNIEnv *env, char *text, size_t cap)
{
    jstring s;

    text[cap - 1U] = '\0';
    s = (*env)->NewStringUTF(env, text);
    w3_wipe(text, cap);
    return s;
}

/* Server: open a window. texts[0] receives the code as typed text (or the PIN),
 * texts[1] the QR content (null in PIN mode). The raw code never reaches Java. */
static jint JNICALL n_window_open(JNIEnv *env, jclass cls, jlong h, jint kind, jbyteArray name,
                                  jint ask, jint perms, jobjectArray texts, jbyteArray outbuf)
{
    w3_ctx *c = w3_get(h);
    wfas3_window_req r;
    uint8_t code[WFAS3_CODE_LEN];
    char text[W3_CODE_MAX];
    char qr[W3_CODE_MAX];
    wfas3_status st;
    jint res;

    (void)cls;
    if ((c == NULL) || (kind < (jint)WFAS3_PAIR_CODE) || (kind > (jint)WFAS3_PAIR_PIN) ||
        (ask < 0) || (ask > 1) || (perms < 0) || (name == NULL) ||
        ((*env)->GetArrayLength(env, name) != (jsize)WFAS3_NAME_LEN) || (texts == NULL) ||
        ((*env)->GetArrayLength(env, texts) != 2)) {
        return WFAS3_E_ARG;
    }
    memset(&r, 0, sizeof r);
    r.kind = (uint32_t)kind;
    r.ask = (uint32_t)ask;
    r.perms = (uint32_t)perms;
    (*env)->GetByteArrayRegion(env, name, 0, (jsize)WFAS3_NAME_LEN, (jbyte *)r.name);
    if ((*env)->ExceptionCheck(env)) {
        return WFAS3_E_ARG;
    }
    memset(code, 0, sizeof code);
    memset(text, 0, sizeof text);
    memset(qr, 0, sizeof qr);
    if (w3_enter(c) == 0) {
        return WFAS3_E_STATE;
    }
    w3_out_clear(c);
    w3_time(c);
    st = wfas3_window_open(&c->dev, &r, code, &c->out);
    if ((st == WFAS3_E_ENTROPY) && (w3_entropy(c) == 1)) {
        w3_out_clear(c);
        w3_time(c);
        st = wfas3_window_open(&c->dev, &r, code, &c->out);
    }
    if (st == WFAS3_OK) {
        if (kind == (jint)WFAS3_PAIR_PIN) {
            memcpy(text, code, WFAS3_PIN_LEN);
        } else if ((wfas3_code_format(code, 0U, text, sizeof text) != WFAS3_OK) ||
                   (wfas3_code_format(code, 1U, qr, sizeof qr) != WFAS3_OK)) {
            st = WFAS3_E_STATE;
        } else {
            /* formatted */
        }
    }
    w3_wipe(code, sizeof code);
    res = w3_emit(env, c, st, outbuf);
    w3_leave(c);
    if (st == WFAS3_OK) {
        (*env)->SetObjectArrayElement(env, texts, 0, w3_new_string(env, text, sizeof text));
        if (kind != (jint)WFAS3_PAIR_PIN) {
            (*env)->SetObjectArrayElement(env, texts, 1, w3_new_string(env, qr, sizeof qr));
        }
    }
    w3_wipe(text, sizeof text);
    w3_wipe(qr, sizeof qr);
    return res;
}

static jint JNICALL n_window_close(JNIEnv *env, jclass cls, jlong h, jbyteArray outbuf)
{
    w3_ctx *c = w3_get(h);

    (void)cls;
    if (c == NULL) {
        return WFAS3_E_ARG;
    }
    W3_CALL(c, env, outbuf, wfas3_window_close(&c->dev, &c->out));
}

static jint JNICALL n_pair_answer(JNIEnv *env, jclass cls, jlong h, jint ask, jint yes, jint perms,
                                  jint guest, jbyteArray outbuf)
{
    w3_ctx *c = w3_get(h);

    (void)cls;
    if ((c == NULL) || (ask < 0) || (yes < 0) || (yes > 1) || (perms < 0) || (guest < 0) || (guest > 1)) {
        return WFAS3_E_ARG;
    }
    W3_CALL(c, env, outbuf, wfas3_pair_answer(&c->dev, (uint32_t)ask, (uint32_t)yes, (uint32_t)perms,
                                              (uint32_t)guest, &c->out));
}

static jint JNICALL n_window_failed(JNIEnv *env, jclass cls, jlong h)
{
    w3_ctx *c = w3_get(h);
    uint32_t n = WFAS3_NONE;

    (void)env;
    (void)cls;
    if ((c != NULL) && (w3_enter(c) == 1)) {
        n = wfas3_window_failed(&c->dev);
        w3_leave(c);
    }
    return (n == WFAS3_NONE) ? -1 : (jint)n;
}

static jint JNICALL n_beacons(JNIEnv *env, jclass cls, jlong h, jint on)
{
    w3_ctx *c = w3_get(h);

    (void)env;
    (void)cls;
    if ((c == NULL) || (on < 0) || (on > 1)) {
        return WFAS3_E_ARG;
    }
    W3_SIMPLE(c, wfas3_beacons(&c->dev, (uint32_t)on));
}

/* Read a typed or scanned code into code[32]. 1 if valid. text is copied
 * into a local buffer first and wiped. */
static int w3_parse_code(JNIEnv *env, jstring s, uint8_t *code)
{
    char buf[W3_CODE_MAX];
    jsize n;
    jsize ul;
    int ok = 0;

    if (s == NULL) {
        return 0;
    }
    n = (*env)->GetStringLength(env, s);
    if ((n <= 0) || (n > 80)) {
        return 0;
    }
    ul = (*env)->GetStringUTFLength(env, s);
    if ((ul <= 0) || (ul >= (jsize)sizeof buf)) {
        return 0;
    }
    memset(buf, 0, sizeof buf);
    (*env)->GetStringUTFRegion(env, s, 0, n, buf);
    if (!(*env)->ExceptionCheck(env)) {
        ok = (wfas3_code_parse(buf, (size_t)ul, code) == WFAS3_OK) ? 1 : 0;
    }
    w3_wipe(buf, sizeof buf);
    return ok;
}

static jboolean JNICALL n_code_valid(JNIEnv *env, jclass cls, jstring s)
{
    uint8_t code[WFAS3_CODE_LEN];
    int ok;

    (void)cls;
    ok = w3_parse_code(env, s, code);
    w3_wipe(code, sizeof code);
    return (ok == 1) ? JNI_TRUE : JNI_FALSE;
}

/* Client: start pairing. kind CODE or LABEL with codeText, or PIN with pin
 * (8 ASCII digits) and the chosen beacon (72 bytes as emitted). */
static jint JNICALL n_pair_start(JNIEnv *env, jclass cls, jlong h, jint kind, jstring code_text,
                                 jbyteArray pin, jbyteArray beacon, jbyteArray name, jint flags,
                                 jint perms, jint replace, jbyteArray outbuf)
{
    w3_ctx *c = w3_get(h);
    wfas3_pair_req r;
    uint8_t b[W3_BEACON_LEN];
    wfas3_status st;
    jint res;

    (void)cls;
    if ((c == NULL) || (kind < (jint)WFAS3_PAIR_CODE) || (kind > (jint)WFAS3_PAIR_PIN) || (flags < 0) ||
        (perms < 0) || (name == NULL) || ((*env)->GetArrayLength(env, name) != (jsize)WFAS3_NAME_LEN)) {
        return WFAS3_E_ARG;
    }
    memset(&r, 0, sizeof r);
    r.kind = (uint32_t)kind;
    r.flags = (uint32_t)flags;
    r.perms = (uint32_t)perms;
    r.replace = (replace < 0) ? WFAS3_NONE : (uint32_t)replace;
    (*env)->GetByteArrayRegion(env, name, 0, (jsize)WFAS3_NAME_LEN, (jbyte *)r.name);
    if ((*env)->ExceptionCheck(env)) {
        return WFAS3_E_ARG;
    }
    if (kind == (jint)WFAS3_PAIR_PIN) {
        if ((pin == NULL) || ((*env)->GetArrayLength(env, pin) != (jsize)WFAS3_PIN_LEN) ||
            (beacon == NULL) || ((*env)->GetArrayLength(env, beacon) != (jsize)W3_BEACON_LEN)) {
            return WFAS3_E_ARG;
        }
        (*env)->GetByteArrayRegion(env, pin, 0, (jsize)WFAS3_PIN_LEN, (jbyte *)r.pin);
        (*env)->GetByteArrayRegion(env, beacon, 0, (jsize)W3_BEACON_LEN, (jbyte *)b);
        if ((*env)->ExceptionCheck(env)) {
            w3_wipe(&r, sizeof r);
            return WFAS3_E_ARG;
        }
        w3_get_beacon(b, &r.beacon);
    } else if (w3_parse_code(env, code_text, r.code) == 0) {
        w3_wipe(&r, sizeof r);
        return WFAS3_E_ARG;
    } else {
        /* code read */
    }
    if (w3_enter(c) == 0) {
        w3_wipe(&r, sizeof r);
        return WFAS3_E_STATE;
    }
    w3_out_clear(c);
    w3_time(c);
    st = wfas3_pair_start(&c->dev, &r, &c->out);
    if ((st == WFAS3_E_ENTROPY) && (w3_entropy(c) == 1)) {
        w3_out_clear(c);
        w3_time(c);
        st = wfas3_pair_start(&c->dev, &r, &c->out);
    }
    w3_wipe(&r, sizeof r);
    res = w3_emit(env, c, st, outbuf);
    w3_leave(c);
    return res;
}

static jint JNICALL n_pair_confirm(JNIEnv *env, jclass cls, jlong h, jint yes, jbyteArray outbuf)
{
    w3_ctx *c = w3_get(h);

    (void)cls;
    if ((c == NULL) || (yes < 0) || (yes > 1)) {
        return WFAS3_E_ARG;
    }
    W3_CALL(c, env, outbuf, wfas3_pair_confirm(&c->dev, (uint32_t)yes, &c->out));
}

static jint JNICALL n_pair_cancel(JNIEnv *env, jclass cls, jlong h)
{
    w3_ctx *c = w3_get(h);

    (void)env;
    (void)cls;
    if (c == NULL) {
        return WFAS3_E_ARG;
    }
    W3_SIMPLE(c, wfas3_pair_cancel(&c->dev));
}

static jstring JNICALL n_name_clean(JNIEnv *env, jclass cls, jbyteArray raw)
{
    uint8_t n[WFAS3_NAME_LEN];
    char text[WFAS3_NAME_TEXT_LEN];
    jstring s;

    (void)cls;
    memset(n, 0, sizeof n);
    if ((raw == NULL) || ((*env)->GetArrayLength(env, raw) != (jsize)WFAS3_NAME_LEN)) {
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, raw, 0, (jsize)WFAS3_NAME_LEN, (jbyte *)n);
    if ((*env)->ExceptionCheck(env)) {
        return NULL;
    }
    wfas3_name_clean(n, text);
    text[WFAS3_NAME_TEXT_LEN - 1U] = '\0';
    s = (*env)->NewStringUTF(env, text);
    return s;
}

/* -- multicast --------------------------------------------------------------- */

static jint JNICALL n_group_open(JNIEnv *env, jclass cls, jlong h, jintArray cfg, jbyteArray ip, jint port)
{
    w3_ctx *c = w3_get(h);
    wfas3_config k;
    wfas3_addr a;

    (void)cls;
    if ((c == NULL) || (w3_get_config(env, cfg, &k) == 0) || (w3_get_addr(env, ip, port, &a) == 0)) {
        return WFAS3_E_ARG;
    }
    W3_SIMPLE(c, wfas3_group_open(&c->dev, &k, &a));
}

static jint JNICALL n_group_close(JNIEnv *env, jclass cls, jlong h)
{
    w3_ctx *c = w3_get(h);

    (void)env;
    (void)cls;
    if (c == NULL) {
        return WFAS3_E_ARG;
    }
    W3_SIMPLE(c, wfas3_group_close(&c->dev));
}

static jint JNICALL n_group_members(JNIEnv *env, jclass cls, jlong h)
{
    w3_ctx *c = w3_get(h);
    uint32_t n = 0U;

    (void)env;
    (void)cls;
    if ((c != NULL) && (w3_enter(c) == 1)) {
        n = wfas3_group_members(&c->dev);
        w3_leave(c);
    }
    return (jint)n;
}

/* -- registration ------------------------------------------------------------ */

static const JNINativeMethod w3_methods[] = {
    { "nSelftest", "()I", (void *)n_selftest },
    { "nClockUs", "()J", (void *)n_clock_us },
    { "nLayout", "(I)I", (void *)n_layout },
    { "nCreate", "([B[B)J", (void *)n_create },
    { "nDestroy", "(J)V", (void *)n_destroy },
    { "nNextDeadline", "(J)J", (void *)n_next_deadline },
    { "nSetPolicy", "(JIII[I)I", (void *)n_set_policy },
    { "nDeviceExport", "(J[B)I", (void *)n_device_export },
    { "nPairImport", "(JI[B)I", (void *)n_pair_import },
    { "nPairExport", "(JI[B)I", (void *)n_pair_export },
    { "nPairInfo", "(JI[B)I", (void *)n_pair_info },
    { "nPairRemove", "(JI[B)I", (void *)n_pair_remove },
    { "nFind", "(JII[B)I", (void *)n_find },
    { "nConnect", "(JI[BI[I[B)I", (void *)n_connect },
    { "nPoll", "(J[B)I", (void *)n_poll },
    { "nReceive", "(J[BI[BI[B)I", (void *)n_receive },
    { "nSendAudio", "(JIJJ[BII[B)I", (void *)n_send_audio },
    { "nGroupSend", "(JJJ[BII[B)I", (void *)n_group_send },
    { "nStreamWant", "(JII)I", (void *)n_stream_want },
    { "nStreamParams", "(JI[I)I", (void *)n_stream_params },
    { "nMicOff", "(JI)I", (void *)n_mic_off },
    { "nAudioLate", "(JIJ)I", (void *)n_audio_late },
    { "nSetBufferMs", "(JII)I", (void *)n_set_buffer_ms },
    { "nSessionClose", "(JII[B)I", (void *)n_session_close },
    { "nSessionInfo", "(JI[J)I", (void *)n_session_info },
    { "nWindowOpen", "(JI[BII[Ljava/lang/String;[B)I", (void *)n_window_open },
    { "nWindowClose", "(J[B)I", (void *)n_window_close },
    { "nPairAnswer", "(JIIII[B)I", (void *)n_pair_answer },
    { "nWindowFailed", "(J)I", (void *)n_window_failed },
    { "nBeacons", "(JI)I", (void *)n_beacons },
    { "nCodeValid", "(Ljava/lang/String;)Z", (void *)n_code_valid },
    { "nPairStart", "(JILjava/lang/String;[B[B[BIII[B)I", (void *)n_pair_start },
    { "nPairConfirm", "(JI[B)I", (void *)n_pair_confirm },
    { "nPairCancel", "(J)I", (void *)n_pair_cancel },
    { "nNameClean", "([B)Ljava/lang/String;", (void *)n_name_clean },
    { "nGroupOpen", "(J[I[BI)I", (void *)n_group_open },
    { "nGroupClose", "(J)I", (void *)n_group_close },
    { "nGroupMembers", "(J)I", (void *)n_group_members },
};

W3_EXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    JNIEnv *env = NULL;
    jclass cls;

    (void)reserved;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    cls = (*env)->FindClass(env, WFAS3_JNI_CLASS);
    if (cls == NULL) {
        return JNI_ERR;
    }
    if ((*env)->RegisterNatives(env, cls, w3_methods,
                                (jint)(sizeof w3_methods / sizeof w3_methods[0])) != 0) {
        return JNI_ERR;
    }
    if (w3_opus_register(env) != 0) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
