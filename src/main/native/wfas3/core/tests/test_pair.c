/* -----------------------------------------------------------------------------
 * WFAS v3 - tests of pairing (specification 13).
 *
 * Three devices in one process, joined by a simulated network that can drop,
 * duplicate, delay and reorder datagrams, and that delivers broadcasts to
 * every other device. Device 0 is the server, 1 the client, 2 a second
 * client or an attacker. Includes wfas.c directly so that the state can be
 * inspected.
 *
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Marco Morosi
 * ------------------------------------------------------------------------- */
#include "../wfas.c"

#include <stdio.h>

#define NDEV   3U
#define NQ     256U
#define US(ms) ((uint64_t)(ms) * 1000U)

typedef struct packet {
    uint32_t used;
    uint32_t to;
    uint32_t from;
    size_t   len;
    uint64_t due;
    uint8_t  data[WFAS3_MAX_DATAGRAM];
} packet;

/* What each device saw, and how its user answers. */
typedef struct who_st {
    uint32_t paired;
    uint32_t paired_pair;
    uint32_t paired_flags;
    uint8_t  paired_name[32];
    wfas3_addr paired_addr;
    uint32_t failed;
    uint32_t fail_why;
    uint32_t race;
    uint32_t asks;
    uint32_t ask_id;
    uint32_t ask_flags;
    uint32_t ask_code;
    uint32_t ask_pending;
    uint32_t ask_addr_ok;
    uint32_t codes;
    uint32_t code;
    uint32_t confirm_pending;
    uint32_t beacons;
    wfas3_beacon beacon;
    uint32_t closed;
    uint32_t stores;
    uint32_t erased;
    uint32_t store_dev;
    uint32_t up;
    uint32_t up_session;
    uint32_t down;
    uint32_t answer;        /* 0 manual, 1 yes, 2 no, 3 never */
    uint32_t answer_perms;
    uint32_t answer_guest;
    uint32_t confirm;       /* 0 manual, 1 yes, 2 no, 3 never */
    uint8_t  stored[WFAS3_MAX_PAIRS][WFAS3_PAIR_RECORD_LEN];
} who_st;

typedef struct world {
    uint64_t now;
    uint64_t rng;
    uint32_t drop_pct;
    uint32_t dup_pct;
    uint32_t delay_ms;
    uint32_t cut[NDEV];     /* 1: nothing reaches or leaves this device */
    uint32_t sent[NDEV];
    size_t   sent_bytes[NDEV];
    size_t   rcvd_bytes[NDEV];
    who_st   w[NDEV];
} world;

static wfas3_device g_dev[NDEV];
static packet g_q[NQ];
static wfas3_out g_out;
static world g_w;
static uint32_t g_run;
static uint32_t g_fail;

static void check(uint32_t cond, const char *what)
{
    g_run += 1U;
    if (cond != 1U) {
        g_fail += 1U;
        (void)printf("FAIL: %s\n", what);
    }
}

static uint32_t rnd32(void)
{
    g_w.rng ^= g_w.rng << 13U;
    g_w.rng ^= g_w.rng >> 7U;
    g_w.rng ^= g_w.rng << 17U;
    return (uint32_t)(g_w.rng >> 16U);
}

static void fill_rnd(uint8_t *p, size_t n)
{
    size_t i;

    for (i = 0U; i < n; i++) {
        p[i] = (uint8_t)(rnd32() & 0xFFU);
    }
}

static wfas3_addr addr_of(uint32_t who)
{
    wfas3_addr a;

    memset(&a, 0, sizeof a);
    a.ip[10] = 0xFFU;
    a.ip[11] = 0xFFU;
    a.ip[12] = 192U;
    a.ip[13] = 168U;
    a.ip[14] = 1U;
    a.ip[15] = (uint8_t)(10U + who);
    a.port = (uint16_t)WFAS3_UDP_PORT;
    return a;
}

static wfas3_config cfg_pcm(void)
{
    wfas3_config c;

    c.flags = WFAS3_CF_SOURCE;
    c.codec = WFAS3_CODEC_PCM;
    c.channels = 2U;
    c.bits = 16U;
    c.sample_rate = 48000U;
    c.frame_samples = 240U;
    c.payload_bytes = 960U;
    c.latency_ms = 20U;
    return c;
}

static void set_name(uint8_t *name, const char *s)
{
    size_t i;

    memset(name, 0, 32U);
    for (i = 0U; (s[i] != '\0') && (i < 32U); i++) {
        name[i] = (uint8_t)s[i];
    }
}

/* -- Network ----------------------------------------------------------------- */

static void enqueue_raw(uint32_t to, uint32_t from, const uint8_t *d, size_t len, uint64_t due)
{
    uint32_t i;
    uint32_t done = 0U;

    for (i = 0U; (i < NQ) && (done == 0U); i++) {
        if (g_q[i].used == 0U) {
            g_q[i].used = 1U;
            g_q[i].to = to;
            g_q[i].from = from;
            g_q[i].len = len;
            g_q[i].due = due;
            memcpy(g_q[i].data, d, len);
            done = 1U;
        }
    }
}

static void enqueue(uint32_t to, uint32_t from, const uint8_t *d, size_t len)
{
    uint32_t copies = 1U;
    uint32_t i;

    if ((g_w.cut[to] == 1U) || (g_w.cut[from] == 1U)) {
        copies = 0U;
    } else if ((rnd32() % 100U) < g_w.drop_pct) {
        copies = 0U;
    } else if ((rnd32() % 100U) < g_w.dup_pct) {
        copies = 2U;
    } else {
        copies = 1U;
    }
    for (i = 0U; i < copies; i++) {
        uint64_t delay = (g_w.delay_ms == 0U) ? 0U : (uint64_t)(rnd32() % g_w.delay_ms);

        enqueue_raw(to, from, d, len, g_w.now + delay);
    }
}

static void handle_out(uint32_t who);

static void add_entropy(uint32_t d)
{
    uint8_t ent[WFAS3_ENTROPY_LEN];

    fill_rnd(ent, sizeof ent);
    (void)wfas3_add_entropy(&g_dev[d], ent);
}

static void deliver(uint32_t to, uint32_t from, const uint8_t *d, size_t len)
{
    wfas3_addr fa = addr_of(from);
    wfas3_status st = wfas3_receive(&g_dev[to], d, len, &fa, &g_out);

    g_w.rcvd_bytes[to] += len;
    if (st == WFAS3_E_ENTROPY) {
        add_entropy(to);
        st = wfas3_receive(&g_dev[to], d, len, &fa, &g_out);
    }
    if (st == WFAS3_OK) {
        handle_out(to);
    }
}

/* Act on the output of a call made on device who. */
static void handle_out(uint32_t who)
{
    who_st *w = &g_w.w[who];
    uint32_t ev = g_out.events;
    uint32_t d;

    if ((ev & WFAS3_EV_STORE_PAIR) != 0U) {
        w->stores += 1U;
        check((wfas3_pair_export(&g_dev[who], g_out.pair, w->stored[g_out.pair % WFAS3_MAX_PAIRS])
               == WFAS3_OK) ? 1U : 0U, "a pair to store exports");
    }
    if ((ev & WFAS3_EV_STORE_DEVICE) != 0U) {
        w->store_dev += 1U;
    }
    if ((ev & WFAS3_EV_PAIR_ERASED) != 0U) {
        w->erased += 1U;
    }
    if ((ev & WFAS3_EV_PAIRED) != 0U) {
        w->paired += 1U;
        w->paired_pair = g_out.pair;
        w->paired_flags = g_out.pflags;
        memcpy(w->paired_name, g_out.name, 32U);
        w->paired_addr = g_out.addr;
    }
    if ((ev & WFAS3_EV_PAIR_FAILED) != 0U) {
        w->failed += 1U;
        w->fail_why = g_out.status;
    }
    if ((ev & WFAS3_EV_PAIR_RACE) != 0U) {
        w->race += 1U;
    }
    if ((ev & WFAS3_EV_PAIR_ASK) != 0U) {
        w->asks += 1U;
        w->ask_id = g_out.ask;
        w->ask_flags = g_out.pflags;
        w->ask_code = g_out.code;
        w->ask_pending = 1U;
        w->ask_addr_ok = (wfas3_addr_eq(&g_out.addr, &g_dev[who].pctx[0].addr) |
                          wfas3_addr_eq(&g_out.addr, &g_dev[who].pctx[1].addr)) &
                         (1U ^ wfas3_addr_eq(&g_out.addr, &(wfas3_addr){ { 0U }, 0U }));
    }
    if ((ev & WFAS3_EV_PAIR_CODE) != 0U) {
        w->codes += 1U;
        w->code = g_out.code;
        w->confirm_pending = (g_dev[who].pcli.mode == 2U) ? 1U : 0U;
    }
    if ((ev & WFAS3_EV_BEACON) != 0U) {
        w->beacons += 1U;
        w->beacon = g_out.beacon;
    }
    if ((ev & WFAS3_EV_WINDOW_CLOSED) != 0U) {
        w->closed += 1U;
    }
    if ((ev & WFAS3_EV_SESSION_UP) != 0U) {
        w->up += 1U;
        w->up_session = g_out.session;
    }
    if ((ev & WFAS3_EV_SESSION_DOWN) != 0U) {
        w->down += 1U;
    }
    if (g_out.len > 0U) {
        g_w.sent[who] += 1U;
        g_w.sent_bytes[who] += g_out.len;
        if (g_out.broadcast == 1U) {
            for (d = 0U; d < NDEV; d++) {
                if (d != who) {
                    enqueue(d, who, g_out.dgram, g_out.len);
                }
            }
        } else {
            uint32_t to = (uint32_t)g_out.to.ip[15] - 10U;

            if (to < NDEV) {
                enqueue(to, who, g_out.dgram, g_out.len);
            }
        }
    }
}

static void poll_all(uint32_t d)
{
    uint32_t k;
    wfas3_status st;

    for (k = 0U; k < 64U; k++) {
        st = wfas3_poll(&g_dev[d], &g_out);
        if (st == WFAS3_E_ENTROPY) {
            add_entropy(d);
        } else if ((st != WFAS3_OK) || ((g_out.len == 0U) && (g_out.events == 0U))) {
            k = 64U;
        } else {
            handle_out(d);
        }
    }
}

/* The users: answers given after a short think. */
static void users(void)
{
    uint32_t d;

    for (d = 0U; d < NDEV; d++) {
        who_st *w = &g_w.w[d];

        if ((w->ask_pending == 1U) && ((w->answer == 1U) || (w->answer == 2U))) {
            w->ask_pending = 0U;
            if (wfas3_pair_answer(&g_dev[d], w->ask_id, (w->answer == 1U) ? 1U : 0U,
                                  w->answer_perms, w->answer_guest, &g_out) == WFAS3_OK) {
                handle_out(d);
            }
        }
        if ((w->confirm_pending == 1U) && ((w->confirm == 1U) || (w->confirm == 2U))) {
            w->confirm_pending = 0U;
            if (wfas3_pair_confirm(&g_dev[d], (w->confirm == 1U) ? 1U : 0U, &g_out) == WFAS3_OK) {
                handle_out(d);
            }
        }
    }
}

static void run(uint32_t ms)
{
    uint64_t end = g_w.now + ms;
    uint32_t i;
    uint32_t d;

    while (g_w.now < end) {
        g_w.now += 10U;
        for (d = 0U; d < NDEV; d++) {
            (void)wfas3_set_time(&g_dev[d], US(g_w.now));
        }
        for (i = 0U; i < NQ; i++) {
            if ((g_q[i].used == 1U) && (g_q[i].due <= g_w.now)) {
                packet pk = g_q[i];

                g_q[i].used = 0U;
                deliver(pk.to, pk.from, pk.data, pk.len);
            }
        }
        users();
        for (d = 0U; d < NDEV; d++) {
            poll_all(d);
        }
    }
}

static void setup(uint64_t seed)
{
    uint8_t s[WFAS3_SEED_LEN];
    uint8_t rec[WFAS3_DEVICE_RECORD_LEN];
    uint32_t d;

    memset(&g_w, 0, sizeof g_w);
    memset(g_q, 0, sizeof g_q);
    g_w.rng = seed * 0x9E3779B97F4A7C15ULL + 7U;
    g_w.now = 1000U;
    for (d = 0U; d < NDEV; d++) {
        fill_rnd(s, sizeof s);
        check((wfas3_device_init(&g_dev[d], s, rec, 0U, US(g_w.now)) == WFAS3_OK) ? 1U : 0U,
              "device init");
        g_w.w[d].answer = 1U;
        g_w.w[d].answer_perms = WFAS3_PERM_RECEIVE | WFAS3_PERM_MICROPHONE;
        g_w.w[d].confirm = 1U;
        g_w.w[d].paired_pair = WFAS3_NONE;
    }
}

static wfas3_status open_window(uint32_t kind, uint32_t ask, uint8_t *code)
{
    wfas3_window_req r;
    wfas3_status st;

    memset(&r, 0, sizeof r);
    r.kind = kind;
    r.ask = ask;
    r.perms = WFAS3_PERM_RECEIVE | WFAS3_PERM_MICROPHONE;
    set_name(r.name, "Living room");
    st = wfas3_window_open(&g_dev[0], &r, code, &g_out);
    if (st == WFAS3_OK) {
        handle_out(0U);
    }
    return st;
}

static wfas3_status start_client(uint32_t who, uint32_t kind, const uint8_t *code,
                                 uint32_t flags, uint32_t replace)
{
    wfas3_pair_req r;
    wfas3_status st;

    memset(&r, 0, sizeof r);
    r.kind = kind;
    if (kind == WFAS3_PAIR_PIN) {
        memcpy(r.pin, code, WFAS3_PIN_LEN);
        r.beacon = g_w.w[who].beacon;
    } else {
        memcpy(r.code, code, WFAS3_CODE_LEN);
    }
    set_name(r.name, (who == 1U) ? "Phone" : "Other");
    r.flags = flags;
    r.perms = WFAS3_PERM_RECEIVE | WFAS3_PERM_MICROPHONE;
    r.replace = replace;
    st = wfas3_pair_start(&g_dev[who], &r, &g_out);
    if (st == WFAS3_OK) {
        handle_out(who);
    }
    return st;
}

/* Both records of a new pair agree with each other. */
static uint32_t pair_matches(uint32_t a, uint32_t pa, uint32_t b, uint32_t pb)
{
    const wfas3_pair *x = &g_dev[a].pairs[pa];
    const wfas3_pair *y = &g_dev[b].pairs[pb];
    uint32_t ok = (x->used == 1U) & (y->used == 1U);

    ok &= wfas3_ct_eq(x->s_pub, y->peer_pub, 32U);
    ok &= wfas3_ct_eq(y->s_pub, x->peer_pub, 32U);
    ok &= wfas3_ct_eq(x->k_fix, y->k_fix, 32U);
    ok &= wfas3_ct_eq(x->psk[0], y->psk[0], 32U);
    ok &= wfas3_ct_eq(x->rep_fp[WFAS3_REP_BASE], y->rep_fp[WFAS3_REP_BASE], 16U);
    ok &= wfas3_ct_eq(x->peer_entry, y->own_entry_held, 32U);
    ok &= wfas3_ct_eq(y->peer_entry, x->own_entry_held, 32U);
    ok &= (x->seq_hi == 0U) & (y->seq_hi == 0U);
    return ok;
}

/* A session between the two ends of a new pair comes up. */
static uint32_t session_up(uint32_t a, uint32_t pa, uint32_t b)
{
    wfas3_addr to = addr_of(b);
    wfas3_config c = cfg_pcm();
    uint32_t before = g_w.w[a].up;

    if (wfas3_connect(&g_dev[a], pa, &to, &c, &g_out) == WFAS3_OK) {
        handle_out(a);
    }
    run(3000U);
    return ((g_w.w[a].up > before) && (g_w.w[b].up > 0U)) ? 1U : 0U;
}

/* -- Tests ------------------------------------------------------------------- */

static void test_code_text(void)
{
    uint8_t code[WFAS3_CODE_LEN];
    uint8_t back[WFAS3_CODE_LEN];
    char text[WFAS3_CODE_TEXT_LEN];
    char qr[WFAS3_CODE_QR_LEN];
    char low[WFAS3_CODE_TEXT_LEN];
    char clean[WFAS3_NAME_TEXT_LEN];
    uint8_t name[32];
    uint32_t i;
    uint32_t k;
    uint32_t wrong = 0U;
    uint32_t refused = 0U;
    size_t n;

    setup(1U);
    for (k = 0U; k < 200U; k++) {
        fill_rnd(code, sizeof code);
        check((wfas3_code_format(code, 0U, text, sizeof text) == WFAS3_OK) ? 1U : 0U, "code format");
        check((strlen(text) == 64U) && (text[4] == '-') ? 1U : 0U, "code text: 13 groups of 4");
        check((wfas3_code_parse(text, strlen(text), back) == WFAS3_OK) &&
              (wfas3_ct_eq(code, back, sizeof code) == 1U) ? 1U : 0U, "code text round trip");
        check((wfas3_code_format(code, 1U, qr, sizeof qr) == WFAS3_OK) &&
              (strncmp(qr, "WFAS3:", 6U) == 0) && (strlen(qr) == 58U) ? 1U : 0U, "QR form");
        check((wfas3_code_parse(qr, strlen(qr), back) == WFAS3_OK) &&
              (wfas3_ct_eq(code, back, sizeof code) == 1U) ? 1U : 0U, "QR round trip");
        n = strlen(text);
        for (i = 0U; i <= n; i++) {
            char ch = text[i];

            low[i] = ((ch >= 'A') && (ch <= 'Z')) ? (char)(ch + 32) : ch;
            low[i] = (low[i] == '0') ? 'o' : low[i];
            low[i] = (low[i] == '1') ? 'L' : low[i];
            low[i] = (low[i] == '-') ? ' ' : low[i];
        }
        check((wfas3_code_parse(low, n, back) == WFAS3_OK) &&
              (wfas3_ct_eq(code, back, sizeof code) == 1U) ? 1U : 0U,
              "typed code: case, O, L and spaces read");
        i = rnd32() % 64U;
        if (text[i] != '-') {
            low[0] = text[i];
            text[i] = (text[i] == 'Z') ? 'Y' : 'Z';
            if (text[i] == low[0]) {
                text[i] = 'X';
            }
            wrong += 1U;
            refused += (wfas3_code_parse(text, n, back) != WFAS3_OK) ? 1U : 0U;
        }
    }
    /* 4 check bits: a wrong character passes 1 time in 16. */
    check((wrong > 100U) && ((refused * 100U) >= (wrong * 85U)) ? 1U : 0U,
          "most codes with one wrong character are refused");
    check((wfas3_code_parse("WFAS3:ABC", 9U, back) == WFAS3_E_ARG) ? 1U : 0U, "short code refused");
    check((wfas3_code_format(code, 0U, text, 10U) == WFAS3_E_ARG) ? 1U : 0U, "small buffer refused");
    set_name(name, "Caf\xC3\xA9\x07\xE2\x80\xAE" "x\xFF");
    wfas3_name_clean(name, clean);
    check((strcmp(clean, "Caf\xC3\xA9x?") == 0) ? 1U : 0U,
          "name clean: control and direction removed, bad UTF-8 as '?'");
    memset(name, 'A', 32U);
    wfas3_name_clean(name, clean);
    check((strlen(clean) == 32U) ? 1U : 0U, "name clean: 32 bytes, NUL terminated");
    /* Characters that show as nothing: Hangul fillers U+3164 and U+115F,
     * blank Braille U+2800, combining grapheme joiner U+034F, variation
     * selectors U+FE0F and U+E0100, tag U+E0041. */
    set_name(name, "a\xE3\x85\xA4" "b\xE1\x85\x9F" "c\xE2\xA0\x80" "d\xCD\x8F" "e\xEF\xB8\x8F"
                   "f\xF3\xA0\x84\x80" "g\xF3\xA0\x81\x81");
    wfas3_name_clean(name, clean);
    check((strcmp(clean, "abcdefg") == 0) ? 1U : 0U, "name clean: default-ignorable characters removed");
    set_name(name, "\xE3\x85\xA4\xE3\x85\xA4\xE2\xA0\x80");
    wfas3_name_clean(name, clean);
    check((clean[0] == '\0') ? 1U : 0U, "name clean: a name of fillers is empty");
    set_name(name, "\xE2\x80\x8C\xE3\x80\x80" "K");
    wfas3_name_clean(name, clean);
    check((strcmp(clean, "\xE3\x80\x80" "K") == 0) ? 1U : 0U, "name clean: a visible space stays");
}

/* Code pairing, the server's user says yes; then a session. */
static void test_code_pairing(uint64_t seed, uint32_t lossy)
{
    uint8_t code[WFAS3_CODE_LEN];
    char text[WFAS3_CODE_TEXT_LEN];
    uint8_t parsed[WFAS3_CODE_LEN];
    uint32_t ps;
    uint32_t pc;

    setup(seed);
    if (lossy == 1U) {
        g_w.drop_pct = 20U;
        g_w.dup_pct = 20U;
        g_w.delay_ms = 300U;
    }
    check((open_window(WFAS3_PAIR_CODE, 1U, code) == WFAS3_OK) ? 1U : 0U, "window opens");
    check((wfas3_code_format(code, 0U, text, sizeof text) == WFAS3_OK) &&
          (wfas3_code_parse(text, strlen(text), parsed) == WFAS3_OK) ? 1U : 0U, "code shown, typed");
    check((start_client(1U, WFAS3_PAIR_CODE, parsed, WFAS3_PAIR_MIC, WFAS3_NONE) == WFAS3_OK) ? 1U : 0U,
          "client starts");
    run(30000U);
    ps = g_w.w[0].paired_pair;
    pc = g_w.w[1].paired_pair;
    check((g_w.w[0].paired == 1U) && (g_w.w[1].paired == 1U) ? 1U : 0U, "code: both sides paired");
    check((g_w.w[0].asks == 1U) && ((g_w.w[0].ask_flags & WFAS3_PAIR_MIC) != 0U) ? 1U : 0U,
          "code: the server's user was asked once, with the microphone");
    check((g_w.w[0].failed == 0U) && (g_w.w[1].failed == 0U) && (g_w.w[1].race == 0U) ? 1U : 0U,
          "code: no failure, no warning");
    if ((ps < WFAS3_MAX_PAIRS) && (pc < WFAS3_MAX_PAIRS)) {
        check(pair_matches(0U, ps, 1U, pc), "code: the two records match");
        check((g_dev[0].pairs[ps].perms == (WFAS3_PERM_RECEIVE | WFAS3_PERM_MICROPHONE)) &&
              (g_dev[1].pairs[pc].perms == (WFAS3_PERM_RECEIVE | WFAS3_PERM_MICROPHONE)) ? 1U : 0U,
              "code: the microphone was asked and granted");
        check((memcmp(g_w.w[1].paired_name, "Living room", 11U) == 0) &&
              (memcmp(g_w.w[0].paired_name, "Phone", 5U) == 0) ? 1U : 0U, "code: names exchanged");
        check((wfas3_addr_eq(&g_w.w[0].paired_addr, &(wfas3_addr){ 0 }) == 0U) ? 1U : 0U,
              "code: PAIRED carries the peer address");
        check((g_w.w[0].stores >= 1U) && (g_w.w[1].stores >= 1U) ? 1U : 0U, "code: both stored");
        check(((g_dev[0].pairs[ps].flags & WFAS3_PF_GUEST) == 0U) ? 1U : 0U, "code: not a guest");
        check((g_w.w[1].codes == 1U) ? 1U : 0U, "code: the client was shown the 6-digit code");
        check((g_w.w[1].code < 1000000U) ? 1U : 0U, "code: six digits");
        g_w.drop_pct = 0U;
        g_w.dup_pct = 0U;
        g_w.delay_ms = 0U;
        run(3000U);
        check(session_up(1U, pc, 0U), "code: a session comes up with the new pair");
        check((g_dev[0].pairs[ps].seq_hi > 0U) ? 1U : 0U, "code: the pair is now used");
    }
    check((g_dev[0].win.used == 1U) ? 1U : 0U, "code: the window is used");
    run(125000U);
    check((g_w.w[0].closed == 1U) && (g_dev[0].win.open == 0U) ? 1U : 0U, "code: the window closes");
    check(((ps < WFAS3_MAX_PAIRS) && (g_dev[0].pairs[ps].used == 1U)) ? 1U : 0U,
          "code: a used pair stays");
}

/* A label, headless: the window is the approval. */
static void test_label_pairing(void)
{
    uint8_t lrec[WFAS3_LABEL_RECORD_LEN];
    uint8_t code[WFAS3_CODE_LEN];
    uint8_t shown[WFAS3_CODE_LEN];
    uint32_t ps;
    uint32_t pc;

    setup(11U);
    check((open_window(WFAS3_PAIR_LABEL, 0U, code) == WFAS3_E_STATE) ? 1U : 0U,
          "label: no label loaded");
    check((wfas3_label_create(&g_dev[0], lrec) == WFAS3_OK) &&
          (wfas3_label_load(&g_dev[0], lrec) == WFAS3_OK) &&
          (wfas3_label_code(&g_dev[0], code) == WFAS3_OK) ? 1U : 0U, "label made and loaded");
    check((open_window(WFAS3_PAIR_LABEL, 0U, shown) == WFAS3_OK) &&
          (wfas3_ct_eq(code, shown, sizeof code) == 1U) ? 1U : 0U, "label: the window shows the label");
    check((start_client(1U, WFAS3_PAIR_LABEL, code, 0U, WFAS3_NONE) == WFAS3_OK) ? 1U : 0U,
          "label: client starts");
    run(20000U);
    ps = g_w.w[0].paired_pair;
    pc = g_w.w[1].paired_pair;
    check((g_w.w[0].paired == 1U) && (g_w.w[1].paired == 1U) && (g_w.w[0].asks == 0U) ? 1U : 0U,
          "label: paired without asking");
    if ((ps < WFAS3_MAX_PAIRS) && (pc < WFAS3_MAX_PAIRS)) {
        check(pair_matches(0U, ps, 1U, pc), "label: the two records match");
        check((g_dev[1].pairs[pc].perms == WFAS3_PERM_RECEIVE) &&
              (g_dev[0].pairs[ps].perms == WFAS3_PERM_RECEIVE) ? 1U : 0U,
              "label: no microphone when not asked");
    }
    /* A second window with the same label, a different client. */
    run(125000U);
    check((open_window(WFAS3_PAIR_LABEL, 0U, shown) == WFAS3_OK) ? 1U : 0U, "label: a new window");
    check((start_client(2U, WFAS3_PAIR_LABEL, code, 0U, WFAS3_NONE) == WFAS3_OK) ? 1U : 0U,
          "label: second client starts");
    run(20000U);
    check((g_w.w[0].paired == 2U) && (g_w.w[2].paired == 1U) ? 1U : 0U, "label: second pairing");
}

/* PIN: beacons, the 6-digit code compared on both sides. */
static void test_pin_pairing(uint32_t server_yes, uint32_t client_yes)
{
    uint8_t pin[WFAS3_PIN_LEN];
    uint32_t i;
    uint32_t digits = 1U;
    uint32_t ps;
    uint32_t pc;

    setup(21U + server_yes + (2U * client_yes));
    g_w.w[0].answer = (server_yes == 1U) ? 1U : 2U;
    g_w.w[1].confirm = (client_yes == 1U) ? 1U : 2U;
    check((wfas3_beacons(&g_dev[1], 1U) == WFAS3_OK) ? 1U : 0U, "pin: client listens to beacons");
    check((open_window(WFAS3_PAIR_PIN, 1U, pin) == WFAS3_OK) ? 1U : 0U, "pin: window opens");
    for (i = 0U; i < WFAS3_PIN_LEN; i++) {
        digits &= ((pin[i] >= 0x30U) && (pin[i] <= 0x39U)) ? 1U : 0U;
    }
    check(digits, "pin: eight digits");
    run(100U);
    check((g_w.w[1].beacons >= 1U) && (memcmp(g_w.w[1].beacon.name, "Living room", 11U) == 0) &&
          (g_w.w[1].beacon.used == 0U) ? 1U : 0U, "pin: the beacon names the server");
    check((g_w.w[2].beacons == 0U) ? 1U : 0U, "pin: beacons only while asked for");
    check((start_client(1U, WFAS3_PAIR_PIN, pin, 0U, WFAS3_NONE) == WFAS3_OK) ? 1U : 0U,
          "pin: client starts");
    run(20000U);
    check((g_w.w[0].asks == 1U) && (g_w.w[1].codes == 1U) &&
          (g_w.w[0].ask_code == g_w.w[1].code) ? 1U : 0U, "pin: both users see the same code");
    ps = g_w.w[0].paired_pair;
    pc = g_w.w[1].paired_pair;
    if ((server_yes == 1U) && (client_yes == 1U)) {
        check((g_w.w[0].paired == 1U) && (g_w.w[1].paired == 1U) ? 1U : 0U, "pin: paired");
        check(((ps < WFAS3_MAX_PAIRS) && (pc < WFAS3_MAX_PAIRS)) ? pair_matches(0U, ps, 1U, pc) : 0U,
              "pin: the two records match");
    } else {
        check((g_w.w[0].paired == 0U) && (g_w.w[1].paired == 0U) ? 1U : 0U, "pin: refused, no pair");
        check((g_w.w[1].failed == 1U) && (g_w.w[0].failed == 1U) ? 1U : 0U, "pin: both report it");
        check((g_w.w[1].fail_why == ((server_yes == 0U) ? WFAS3_PAIRF_REFUSED : WFAS3_PAIRF_CODE)) ? 1U : 0U,
              "pin: the client knows why");
        check((g_w.w[1].race == 1U) ? 1U : 0U, "pin: the client warns after a valid step 2");
        check((g_dev[0].win.open == 1U) && (g_dev[0].win.used == 0U) ? 1U : 0U,
              "pin: the window stays open, unused");
        check((wfas3_window_failed(&g_dev[0]) == ((server_yes == 0U) ? 0U : 1U)) ? 1U : 0U,
              "pin: a refusal of the server is not counted, one of the client is");
    }
    for (i = 0U; i < WFAS3_MAX_PAIRS; i++) {
        check((g_dev[0].pctx[i % WFAS3_PAIR_CTX].state == WFAS3_PS_FREE) ||
              (g_dev[0].pctx[i % WFAS3_PAIR_CTX].state == WFAS3_PS_DONE) ? 1U : 0U,
              "pin: no context left");
    }
}

/* PIN pairing over a bad network, many runs. */
static void test_pin_lossy(uint64_t seed)
{
    uint8_t pin[WFAS3_PIN_LEN];

    setup(seed);
    g_w.drop_pct = 15U;
    g_w.dup_pct = 20U;
    g_w.delay_ms = 400U;
    (void)wfas3_beacons(&g_dev[1], 1U);
    check((open_window(WFAS3_PAIR_PIN, 1U, pin) == WFAS3_OK) ? 1U : 0U, "pin lossy: window");
    run(3000U);
    check((g_w.w[1].beacons >= 1U) ? 1U : 0U, "pin lossy: a beacon heard");
    check((start_client(1U, WFAS3_PAIR_PIN, pin, WFAS3_PAIR_MIC, WFAS3_NONE) == WFAS3_OK) ? 1U : 0U,
          "pin lossy: client starts");
    run(40000U);
    check((g_w.w[0].paired == 1U) && (g_w.w[1].paired == 1U) ? 1U : 0U, "pin lossy: paired");
    check((g_w.w[0].ask_code == g_w.w[1].code) ? 1U : 0U, "pin lossy: same code");
    if ((g_w.w[0].paired_pair < WFAS3_MAX_PAIRS) && (g_w.w[1].paired_pair < WFAS3_MAX_PAIRS)) {
        check(pair_matches(0U, g_w.w[0].paired_pair, 1U, g_w.w[1].paired_pair), "pin lossy: records match");
    }
}

/* A wrong PIN: step 2 never opens; the client gives up 5 s later. */
static void test_wrong_pin(void)
{
    uint8_t pin[WFAS3_PIN_LEN];

    setup(31U);
    (void)wfas3_beacons(&g_dev[1], 1U);
    check((open_window(WFAS3_PAIR_PIN, 1U, pin) == WFAS3_OK) ? 1U : 0U, "wrong pin: window");
    run(100U);
    pin[3] = (pin[3] == 0x30U) ? 0x31U : 0x30U;
    check((start_client(1U, WFAS3_PAIR_PIN, pin, 0U, WFAS3_NONE) == WFAS3_OK) ? 1U : 0U,
          "wrong pin: client starts");
    run(4000U);
    check((g_w.w[1].failed == 0U) ? 1U : 0U, "wrong pin: still waiting before 5 s");
    run(2000U);
    check((g_w.w[1].failed == 1U) && (g_w.w[1].fail_why == WFAS3_PAIRF_WRONG) ? 1U : 0U,
          "wrong pin: the client fails WRONG");
    check((g_w.w[1].race == 0U) && (g_w.w[0].asks == 0U) ? 1U : 0U,
          "wrong pin: no warning, nobody asked");
    run(10000U);
    check((wfas3_window_failed(&g_dev[0]) == 1U) && (g_w.w[0].failed == 1U) ? 1U : 0U,
          "wrong pin: the server counts one failure");
    check((g_dev[0].pctx[0].state == WFAS3_PS_FREE) && (g_dev[0].pctx[1].state == WFAS3_PS_FREE) ? 1U : 0U,
          "wrong pin: the context is erased");
    check((g_w.w[0].paired == 0U) && (g_w.w[1].paired == 0U) ? 1U : 0U, "wrong pin: no pair");
}

/* A code whose pairing key is not the server's: someone in the middle. The
 * step 2 is ignored like a forgery (the real server may still answer); the
 * run fails KEY, with the warning, 5 s later. */
static void test_wrong_key(void)
{
    uint8_t code[WFAS3_CODE_LEN];

    setup(41U);
    check((open_window(WFAS3_PAIR_CODE, 1U, code) == WFAS3_OK) ? 1U : 0U, "wrong key: window");
    code[20] ^= 0x01U;
    check((start_client(1U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE) == WFAS3_OK) ? 1U : 0U,
          "wrong key: client starts");
    run(3000U);
    check((g_w.w[1].failed == 0U) && (g_dev[1].pcli.key_bad == 1U) &&
          (g_dev[1].pcli.state == WFAS3_PC_S1) ? 1U : 0U, "wrong key: step 2 ignored, the client waits");
    run(3000U);
    check((g_w.w[1].failed == 1U) && (g_w.w[1].fail_why == WFAS3_PAIRF_KEY) &&
          (g_w.w[1].race == 1U) ? 1U : 0U, "wrong key: KEY failure with the warning");
    check((g_w.w[0].asks == 0U) && (g_w.w[0].paired == 0U) ? 1U : 0U, "wrong key: nothing on the server");
}

/* Someone else holds the code and answers first with its own pairing key:
 * the client ignores that step 2 and pairs with the real server. */
static void test_key_race(void)
{
    uint8_t code[WFAS3_CODE_LEN];
    uint8_t d1[WFAS3_LEN_INIT];
    wfas3_addr from = addr_of(0U);
    wfas3_addr a1 = addr_of(1U);
    wfas3_device *fake = &g_dev[2];
    wfas3_status st;

    setup(43U);
    check((open_window(WFAS3_PAIR_CODE, 0U, code) == WFAS3_OK) ? 1U : 0U, "key race: window");
    (void)start_client(1U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE);
    g_w.cut[0] = 1U;
    (void)wfas3_beacon_build(&g_dev[0], &g_out);
    st = wfas3_receive(&g_dev[1], g_out.dgram, WFAS3_LEN_PAIR_BEACON, &from, &g_out);
    check((st == WFAS3_OK) && (g_dev[1].pcli.state == WFAS3_PC_S1) ? 1U : 0U, "key race: step 1 sent");
    /* Device 2 plays a server with the same secret but its own W. */
    fake->win = g_dev[0].win;
    fake->win.w_priv[0] ^= 0x40U;
    wfas_tp_x25519_public(fake->win.w_pub, fake->win.w_priv);
    memcpy(d1, g_dev[1].pcli.tx, sizeof d1);
    (void)wfas3_pair_token(&d1[WFAS3_PAIR_TOKEN], &fake->win, &a1, &d1[8]);
    (void)wfas3_mac(&d1[1352], 16U, fake->win.k_f, d1, 1352U);
    add_entropy(2U);
    st = wfas3_receive(fake, d1, sizeof d1, &a1, &g_out);
    check((st == WFAS3_OK) && (g_out.len == 1296U) ? 1U : 0U, "key race: the impostor answers");
    st = wfas3_receive(&g_dev[1], g_out.dgram, g_out.len, &from, &g_out);
    check((st == WFAS3_E_DROP) && (g_dev[1].pcli.key_bad == 1U) && (g_w.w[1].failed == 0U) ? 1U : 0U,
          "key race: the wrong key is ignored");
    g_w.cut[0] = 0U;
    fake->win.open = 0U;
    run(4000U);
    check((g_w.w[1].paired == 1U) && (g_w.w[0].paired == 1U) && (g_w.w[1].race == 0U) ? 1U : 0U,
          "key race: the real server still pairs");
}

/* A wrong secret in the code: no beacon matches, the client waits. */
static void test_wrong_secret(void)
{
    uint8_t code[WFAS3_CODE_LEN];

    setup(42U);
    check((open_window(WFAS3_PAIR_CODE, 1U, code) == WFAS3_OK) ? 1U : 0U, "wrong secret: window");
    code[2] ^= 0x40U;
    check((start_client(1U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE) == WFAS3_OK) ? 1U : 0U,
          "wrong secret: client starts");
    run(10000U);
    check((g_w.sent[1] == 0U) && (g_w.w[1].failed == 0U) ? 1U : 0U, "wrong secret: nothing sent");
    run(115000U);
    check((g_w.w[1].failed == 1U) && (g_w.w[1].fail_why == WFAS3_PAIRF_TIMEOUT) ? 1U : 0U,
          "wrong secret: times out after the window");
    check((g_w.w[0].closed == 1U) ? 1U : 0U, "wrong secret: the window closed");
}

/* The server's user says no, or never answers. */
static void test_refused(uint32_t never)
{
    uint8_t code[WFAS3_CODE_LEN];

    setup(51U + never);
    g_w.w[0].answer = (never == 1U) ? 3U : 2U;
    check((open_window(WFAS3_PAIR_CODE, 1U, code) == WFAS3_OK) ? 1U : 0U, "refused: window");
    check((start_client(1U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE) == WFAS3_OK) ? 1U : 0U,
          "refused: client starts");
    run((never == 1U) ? 65000U : 5000U);
    check((g_w.w[1].failed == 1U) && (g_w.w[1].fail_why == WFAS3_PAIRF_REFUSED) &&
          (g_w.w[1].race == 1U) ? 1U : 0U, "refused: REFUSED on the client, with the warning");
    run(2000U);
    check((g_w.w[0].failed == 1U) && (wfas3_window_failed(&g_dev[0]) == 0U) ? 1U : 0U,
          "refused: the server's own no is not counted");
    check((g_w.w[0].paired == 0U) && (g_w.w[1].paired == 0U) ? 1U : 0U, "refused: no pair");
    check((wfas3_pair_answer(&g_dev[0], g_w.w[0].ask_id, 1U, 1U, 0U, &g_out) == WFAS3_E_STATE) ? 1U : 0U,
          "refused: a late yes is refused");
}

/* A guest: never stored, ends with BYE 4 or 120 s without a session. */
static void test_guest(void)
{
    uint8_t code[WFAS3_CODE_LEN];
    uint8_t rec[WFAS3_PAIR_RECORD_LEN];
    uint32_t ps;
    uint32_t pc;
    uint32_t stores0;
    uint32_t i;

    setup(61U);
    check((open_window(WFAS3_PAIR_CODE, 1U, code) == WFAS3_OK) ? 1U : 0U, "guest: window");
    check((start_client(1U, WFAS3_PAIR_CODE, code, WFAS3_PAIR_GUEST, WFAS3_NONE) == WFAS3_OK) ? 1U : 0U,
          "guest: client starts");
    run(5000U);
    ps = g_w.w[0].paired_pair;
    pc = g_w.w[1].paired_pair;
    check((g_w.w[0].paired == 1U) && (g_w.w[1].paired == 1U) ? 1U : 0U, "guest: paired");
    check(((g_w.w[0].ask_flags & WFAS3_PAIR_GUEST) != 0U) ? 1U : 0U, "guest: the server saw the request");
    check(((g_w.w[0].paired_flags & WFAS3_PAIR_GUEST) != 0U) &&
          ((g_w.w[1].paired_flags & WFAS3_PAIR_GUEST) != 0U) ? 1U : 0U, "guest: both say guest");
    check((g_w.w[0].stores == 0U) && (g_w.w[1].stores == 0U) ? 1U : 0U, "guest: nothing stored");
    if ((ps >= WFAS3_MAX_PAIRS) || (pc >= WFAS3_MAX_PAIRS)) {
        return;
    }
    check(pair_matches(0U, ps, 1U, pc), "guest: the two records match");
    check((wfas3_ct_eq(g_dev[0].pairs[ps].own_entry_held, g_dev[0].entry_key, 32U) == 0U) &&
          (wfas3_ct_eq(g_dev[1].pairs[pc].own_entry_held, g_dev[1].entry_key, 32U) == 0U) ? 1U : 0U,
          "guest: no entry key given to a guest");
    check((wfas3_pair_export(&g_dev[0], ps, rec) == WFAS3_E_ARG) ? 1U : 0U, "guest: export refused");
    check(session_up(1U, pc, 0U), "guest: a session comes up");
    stores0 = g_w.w[0].stores + g_w.w[1].stores;
    run(5000U);
    check((g_w.w[0].stores + g_w.w[1].stores == stores0) ? 1U : 0U,
          "guest: no ENTRY_KEY exchange, nothing stored");
    check((wfas3_session_close(&g_dev[0], g_w.w[0].up_session, WFAS3_BYE_GUEST_ENDED, &g_out) == WFAS3_OK) ? 1U : 0U,
          "guest: the server ends the guest");
    handle_out(0U);
    run(3000U);
    check((g_dev[0].pairs[ps].used == 0U) && (g_dev[1].pairs[pc].used == 0U) ? 1U : 0U,
          "guest: BYE 4 erases both guest pairs");
    check((g_w.w[0].store_dev == 0U) && (g_w.w[1].store_dev == 0U) ? 1U : 0U,
          "guest: the entry key does not change");

    /* A guest without a session for 120 s ends by itself. */
    run(125000U);
    check((open_window(WFAS3_PAIR_CODE, 0U, code) == WFAS3_OK) ? 1U : 0U, "guest idle: window");
    check((start_client(1U, WFAS3_PAIR_CODE, code, WFAS3_PAIR_GUEST, WFAS3_NONE) == WFAS3_OK) ? 1U : 0U,
          "guest idle: client starts");
    run(5000U);
    ps = g_w.w[0].paired_pair;
    check((g_w.w[0].paired == 2U) && (ps < WFAS3_MAX_PAIRS) ? 1U : 0U, "guest idle: paired");
    run(110000U);
    check((ps < WFAS3_MAX_PAIRS) && (g_dev[0].pairs[ps].used == 1U) ? 1U : 0U, "guest idle: kept 115 s");
    run(10000U);
    for (i = 0U; i < WFAS3_MAX_PAIRS; i++) {
        check((g_dev[0].pairs[i].used == 0U) && (g_dev[1].pairs[i].used == 0U) ? 1U : 0U,
              "guest idle: erased after 120 s");
    }

    /* A non-guest pair may not be ended with BYE 4. */
    run(10000U);
}

/* The server makes a guest of a client that did not ask. */
static void test_server_guest(void)
{
    uint8_t code[WFAS3_CODE_LEN];
    uint32_t pc;

    setup(62U);
    g_w.w[0].answer_guest = 1U;
    check((open_window(WFAS3_PAIR_CODE, 1U, code) == WFAS3_OK) ? 1U : 0U, "server guest: window");
    check((start_client(1U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE) == WFAS3_OK) ? 1U : 0U,
          "server guest: client starts");
    run(5000U);
    pc = g_w.w[1].paired_pair;
    check((g_w.w[1].paired == 1U) && (pc < WFAS3_MAX_PAIRS) &&
          ((g_dev[1].pairs[pc % WFAS3_MAX_PAIRS].flags & WFAS3_PF_GUEST) != 0U) ? 1U : 0U,
          "server guest: the client's pair is a guest too");
    check((g_w.w[1].stores == 0U) && (g_w.w[0].stores == 0U) ? 1U : 0U, "server guest: nothing stored");
}

/* An unused pair is erased after 10 minutes, with a new entry key. */
static void test_unused(void)
{
    uint8_t code[WFAS3_CODE_LEN];
    uint8_t old_key[32];
    uint32_t ps;
    uint32_t pc;

    setup(71U);
    check((open_window(WFAS3_PAIR_CODE, 0U, code) == WFAS3_OK) ? 1U : 0U, "unused: window");
    check((start_client(1U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE) == WFAS3_OK) ? 1U : 0U,
          "unused: client starts");
    run(5000U);
    ps = g_w.w[0].paired_pair;
    pc = g_w.w[1].paired_pair;
    check((ps < WFAS3_MAX_PAIRS) && (pc < WFAS3_MAX_PAIRS) ? 1U : 0U, "unused: paired");
    if ((ps >= WFAS3_MAX_PAIRS) || (pc >= WFAS3_MAX_PAIRS)) {
        return;
    }
    memcpy(old_key, g_dev[0].entry_key, 32U);
    run(590000U);
    check((g_dev[0].pairs[ps].used == 1U) ? 1U : 0U, "unused: kept before 10 minutes");
    run(10000U);
    check((g_dev[0].pairs[ps].used == 0U) && (g_dev[1].pairs[pc].used == 0U) ? 1U : 0U,
          "unused: erased after 10 minutes");
    check((g_w.w[0].erased == 1U) && (g_w.w[0].store_dev == 1U) &&
          (wfas3_ct_eq(old_key, g_dev[0].entry_key, 32U) == 0U) ? 1U : 0U,
          "unused: removed as by 9.4");

    /* An imported unused record gets the same 10 minutes. */
    check((wfas3_pair_import(&g_dev[0], 5U, g_w.w[0].stored[ps]) == WFAS3_OK) ? 1U : 0U,
          "unused: import");
    run(601000U);
    check((g_dev[0].pairs[5].used == 0U) ? 1U : 0U, "unused: an imported unused pair is erased too");
}

/* A new pairing replaces an old pair (13.5): both sides drop it. */
static void test_replace(void)
{
    uint8_t code[WFAS3_CODE_LEN];
    uint32_t ps1;
    uint32_t pc1;
    uint32_t ps2;
    uint32_t pc2;

    setup(81U);
    check((open_window(WFAS3_PAIR_CODE, 0U, code) == WFAS3_OK) ? 1U : 0U, "replace: first window");
    (void)start_client(1U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE);
    run(5000U);
    ps1 = g_w.w[0].paired_pair;
    pc1 = g_w.w[1].paired_pair;
    check((ps1 < WFAS3_MAX_PAIRS) && (pc1 < WFAS3_MAX_PAIRS) ? 1U : 0U, "replace: first pair");
    if ((ps1 >= WFAS3_MAX_PAIRS) || (pc1 >= WFAS3_MAX_PAIRS)) {
        return;
    }
    check(session_up(1U, pc1, 0U), "replace: the first pair is used");
    run(125000U);
    check((open_window(WFAS3_PAIR_CODE, 0U, code) == WFAS3_OK) ? 1U : 0U, "replace: second window");
    check((start_client(1U, WFAS3_PAIR_CODE, code, 0U, pc1) == WFAS3_OK) ? 1U : 0U,
          "replace: client starts, replacing");
    run(5000U);
    ps2 = g_w.w[0].paired_pair;
    pc2 = g_w.w[1].paired_pair;
    check((g_w.w[0].paired == 2U) && (g_w.w[1].paired == 2U) ? 1U : 0U, "replace: second pair");
    check((ps2 != ps1) && (pc2 != pc1) ? 1U : 0U, "replace: in new slots");
    check((g_dev[0].pairs[ps1].used == 0U) && (g_dev[1].pairs[pc1].used == 0U) ? 1U : 0U,
          "replace: the old pair is gone on both sides");
    check((g_dev[0].pairs[ps2 % WFAS3_MAX_PAIRS].used == 1U) &&
          (g_dev[1].pairs[pc2 % WFAS3_MAX_PAIRS].used == 1U) ? 1U : 0U, "replace: the new pair stays");
    check((g_w.w[0].erased == 1U) && (g_w.w[1].erased == 1U) ? 1U : 0U, "replace: one erase each");
}

/* Two clients hold the code: one pairing per window; the loser is warned. */
static void test_race(uint64_t seed)
{
    uint8_t code[WFAS3_CODE_LEN];
    uint32_t total;

    setup(seed);
    g_w.delay_ms = 50U;
    check((open_window(WFAS3_PAIR_CODE, 0U, code) == WFAS3_OK) ? 1U : 0U, "race: window");
    (void)start_client(1U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE);
    (void)start_client(2U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE);
    run(40000U);
    total = g_w.w[1].paired + g_w.w[2].paired;
    check((g_w.w[0].paired == 1U) && (total == 1U) ? 1U : 0U, "race: exactly one pair");
    check(((g_w.w[1].failed + g_w.w[2].failed) == 1U) ? 1U : 0U, "race: the other fails");
    check((((g_w.w[1].paired == 0U) && (g_w.w[1].race == 1U)) ||
           ((g_w.w[2].paired == 0U) && (g_w.w[2].race == 1U))) ? 1U : 0U,
          "race: the loser is warned");
    check((g_w.w[1].race + g_w.w[2].race == 1U) ? 1U : 0U, "race: the winner is not warned");
}

/* A step 1 without its token gets a Retry, smaller, and changes nothing; a
 * client that only sends copies of step 1 gets no more than it sends; many
 * sources are served fairly, at most 4 a second. */
static void test_admission(void)
{
    uint8_t code[WFAS3_CODE_LEN];
    uint8_t d1[WFAS3_LEN_INIT];
    uint8_t retry[48];
    uint8_t retry2[48];
    wfas3_addr from;
    wfas3_addr other = addr_of(2U);
    uint32_t k;
    uint32_t answers = 0U;
    uint32_t src;
    uint32_t served[6];
    wfas3_status st;

    setup(91U);
    check((open_window(WFAS3_PAIR_CODE, 1U, code) == WFAS3_OK) ? 1U : 0U, "admission: window");
    memset(g_q, 0, sizeof g_q);
    (void)start_client(1U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE);
    check((wfas3_window_open(&g_dev[0], &(wfas3_window_req){ WFAS3_PAIR_CODE, { 0U }, 1U, 1U },
                             code, &g_out) == WFAS3_E_STATE) ? 1U : 0U, "admission: window is open");
    check((wfas3_beacon_build(&g_dev[0], &g_out) == WFAS3_OK) ? 1U : 0U, "admission: a beacon");
    memcpy(d1, g_out.dgram, WFAS3_LEN_PAIR_BEACON);
    from = addr_of(0U);
    st = wfas3_receive(&g_dev[1], d1, WFAS3_LEN_PAIR_BEACON, &from, &g_out);
    check((st == WFAS3_OK) && (g_out.len == WFAS3_LEN_INIT) &&
          (g_dev[1].pcli.state == WFAS3_PC_S1) &&
          (wfas3_all_zero(&g_out.dgram[WFAS3_PAIR_TOKEN], 88U) == 1U) ? 1U : 0U,
          "admission: the client sent step 1, without a token");
    memcpy(d1, g_dev[1].pcli.tx, sizeof d1);
    from = addr_of(1U);
    add_entropy(0U);
    st = wfas3_receive(&g_dev[0], d1, sizeof d1, &from, &g_out);
    check((st == WFAS3_OK) && (g_out.len == 48U) && (g_out.dgram[4] == WFAS3_PAIR_RETRY) &&
          (wfas3_addr_eq(&g_out.to, &from) == 1U) &&
          (g_dev[0].pctx[0].state == WFAS3_PS_FREE) && (g_dev[0].pctx[1].state == WFAS3_PS_FREE) &&
          (g_dev[0].win.src_used[0] == 0U) && (g_dev[0].win.s1_count == 0U) ? 1U : 0U,
          "admission: a step 1 without its token gets a Retry and changes nothing");
    memcpy(retry, g_out.dgram, sizeof retry);
    d1[WFAS3_PAIR_TOKEN] ^= 0x01U;
    (void)wfas3_mac(&d1[1352], 16U, g_dev[0].win.k_f, d1, 1352U);
    st = wfas3_receive(&g_dev[0], d1, sizeof d1, &other, &g_out);
    check((st == WFAS3_OK) && (g_out.len == 48U) && (g_dev[0].pctx[0].state == WFAS3_PS_FREE) ? 1U : 0U,
          "admission: a wrong token is no token");
    from = addr_of(1U);
    (void)wfas3_pair_token(&d1[WFAS3_PAIR_TOKEN], &g_dev[0].win, &from, &d1[8]);
    (void)wfas3_mac(&d1[1352], 16U, g_dev[0].win.k_f, d1, 1352U);
    st = wfas3_receive(&g_dev[0], d1, sizeof d1, &other, &g_out);
    check((st == WFAS3_OK) && (g_out.len == 48U) && (g_dev[0].pctx[0].state == WFAS3_PS_FREE) ? 1U : 0U,
          "admission: a token is bound to its address");
    st = wfas3_receive(&g_dev[1], retry, sizeof retry, &other, &g_out);
    check((st == WFAS3_E_DROP) && (wfas3_all_zero(&g_dev[1].pcli.tx[WFAS3_PAIR_TOKEN], 16U) == 1U) ? 1U : 0U,
          "admission: a Retry from another address is ignored");
    from = addr_of(0U);
    st = wfas3_receive(&g_dev[1], retry, sizeof retry, &from, &g_out);
    check((st == WFAS3_OK) && (g_out.len == WFAS3_LEN_INIT) &&
          (wfas3_ct_eq(&g_out.dgram[WFAS3_PAIR_TOKEN], &retry[16], 16U) == 1U) &&
          (wfas3_pair_filter_ok(g_out.dgram, g_out.len, g_dev[1].pcli.k_f) == 1U) ? 1U : 0U,
          "admission: the client answers the first Retry at once, with the token");
    memcpy(retry2, retry, sizeof retry2);
    retry2[16] ^= 0x01U;
    (void)wfas3_mac(&retry2[32], 16U, g_dev[1].pcli.k_f, retry2, 32U);
    st = wfas3_receive(&g_dev[1], retry2, sizeof retry2, &from, &g_out);
    check((g_out.len == 0U) && (g_dev[1].pcli.tx[WFAS3_PAIR_TOKEN] == retry2[16]) ? 1U : 0U,
          "admission: a later Retry only changes the next copy");
    (void)wfas3_receive(&g_dev[1], retry, sizeof retry, &from, &g_out);
    memcpy(d1, g_dev[1].pcli.tx, sizeof d1);
    from = addr_of(1U);
    for (k = 0U; k < 100U; k++) {
        g_w.now += 10U;
        (void)wfas3_set_time(&g_dev[0], US(g_w.now));
        st = wfas3_receive(&g_dev[0], d1, sizeof d1, &from, &g_out);
        if (st == WFAS3_E_ENTROPY) {
            add_entropy(0U);
            st = wfas3_receive(&g_dev[0], d1, sizeof d1, &from, &g_out);
        }
        answers += ((st == WFAS3_OK) && (g_out.len == 1296U)) ? 1U : 0U;
    }
    check((answers >= 1U) && (answers <= 2U) ? 1U : 0U,
          "admission: copies of step 1 answered at most once a second");

    /* Six sources, each sending a fresh step 1 every 100 ms for 3 s. */
    setup(92U);
    check((open_window(WFAS3_PAIR_CODE, 1U, code) == WFAS3_OK) ? 1U : 0U, "admission: window 2");
    memset(served, 0, sizeof served);
    for (k = 0U; k < 30U; k++) {
        g_w.now += 100U;
        (void)wfas3_set_time(&g_dev[0], US(g_w.now));
        for (src = 0U; src < 6U; src++) {
            from = addr_of(3U + src);
            g_dev[1].pcli.state = WFAS3_PC_FREE;
            g_dev[1].pcli.mode = 1U;
            g_dev[1].pcli.addr = addr_of(0U);
            memcpy(g_dev[1].pcli.nonce, g_dev[0].win.nonce, 16U);
            memcpy(g_dev[1].pcli.prs, g_dev[0].win.prs, 16U);
            g_dev[1].pcli.prs_len = 16U;
            memcpy(g_dev[1].pcli.k_f, g_dev[0].win.k_f, 32U);
            add_entropy(1U);
            (void)wfas3_set_time(&g_dev[1], US(g_w.now));
            if (wfas3_pc_step1(&g_dev[1], &g_out) == WFAS3_OK) {
                memcpy(d1, g_out.dgram, sizeof d1);
                (void)wfas3_pair_token(&d1[WFAS3_PAIR_TOKEN], &g_dev[0].win, &from, &d1[8]);
                (void)wfas3_mac(&d1[1352], 16U, g_dev[0].win.k_f, d1, 1352U);
                add_entropy(0U);
                st = wfas3_receive(&g_dev[0], d1, sizeof d1, &from, &g_out);
                served[src] += ((st == WFAS3_OK) && (g_out.len == 1296U)) ? 1U : 0U;
            }
        }
        g_dev[0].pctx[0].state = WFAS3_PS_FREE;   /* contexts freed, as by timeouts */
        g_dev[0].pctx[1].state = WFAS3_PS_FREE;
    }
    answers = 0U;
    for (src = 0U; src < 6U; src++) {
        answers += served[src];
        check((served[src] >= 1U) ? 1U : 0U, "admission: every source is served");
    }
    check((answers <= 13U) ? 1U : 0U, "admission: at most 4 a second in total");
}

/* -- Draft 7: beacons of a permanent secret, admission, tag budget, cookies - */

/* A recorded state-1 beacon of a label, played again in a later window,
 * no longer ends a run (13.2); a fresh state-1 beacon of the run still does.
 * A code beacon says that its secret is used once. */
static void test_beacon_replay(void)
{
    uint8_t lrec[WFAS3_LABEL_RECORD_LEN];
    uint8_t code[WFAS3_CODE_LEN];
    uint8_t shown[WFAS3_CODE_LEN];
    uint8_t old[WFAS3_LEN_PAIR_BEACON];
    uint8_t pin[WFAS3_PIN_LEN];
    wfas3_addr anywhere = addr_of(2U);
    wfas3_status st;

    setup(121U);
    (void)wfas3_label_create(&g_dev[0], lrec);
    (void)wfas3_label_load(&g_dev[0], lrec);
    (void)wfas3_label_code(&g_dev[0], code);
    check((open_window(WFAS3_PAIR_LABEL, 0U, shown) == WFAS3_OK) && (g_out.dgram[6] == 0U) ? 1U : 0U,
          "replay: a label beacon has flags 0");
    (void)start_client(1U, WFAS3_PAIR_LABEL, code, 0U, WFAS3_NONE);
    run(20000U);
    check((g_w.w[1].paired == 1U) && (g_dev[0].win.used == 1U) ? 1U : 0U, "replay: first label pairing");
    check((wfas3_beacon_build(&g_dev[0], &g_out) == WFAS3_OK) && (g_out.dgram[5] == 1U) ? 1U : 0U,
          "replay: a state-1 beacon recorded");
    memcpy(old, g_out.dgram, sizeof old);
    run(130000U);
    check((g_dev[0].win.open == 0U) ? 1U : 0U, "replay: that window closed");

    /* Later: a new client waits for the beacon; the old one is played. */
    (void)start_client(2U, WFAS3_PAIR_LABEL, code, 0U, WFAS3_NONE);
    st = wfas3_receive(&g_dev[2], old, sizeof old, &anywhere, &g_out);
    check((st != WFAS3_OK) && ((g_out.events & WFAS3_EV_PAIR_FAILED) == 0U) &&
          (g_dev[2].pcli.state == WFAS3_PC_BEACON) ? 1U : 0U,
          "replay: an old label beacon with state 1 is ignored while waiting");
    g_w.cut[0] = 1U;
    check((open_window(WFAS3_PAIR_LABEL, 0U, shown) == WFAS3_OK) ? 1U : 0U, "replay: a new window");
    {
        uint8_t fresh[WFAS3_LEN_PAIR_BEACON];
        wfas3_addr srv = addr_of(0U);

        memcpy(fresh, g_out.dgram, sizeof fresh);
        add_entropy(2U);
        (void)wfas3_receive(&g_dev[2], fresh, sizeof fresh, &srv, &g_out);
    }
    g_w.cut[0] = 0U;
    check((g_dev[2].pcli.state == WFAS3_PC_S1) ? 1U : 0U, "replay: the client started its run");
    st = wfas3_receive(&g_dev[2], old, sizeof old, &anywhere, &g_out);
    check(((g_out.events & WFAS3_EV_PAIR_FAILED) == 0U) ? 1U : 0U,
          "replay: an old label beacon with another nonce is ignored during the run");
    run(20000U);
    check((g_w.w[2].paired == 1U) && (g_w.w[2].race == 0U) ? 1U : 0U, "replay: the new client pairs");

    /* The genuine case: a label client whose run is overtaken. */
    run(130000U);
    (void)open_window(WFAS3_PAIR_LABEL, 0U, shown);
    g_w.cut[1] = 1U;
    run(1100U);
    (void)start_client(1U, WFAS3_PAIR_LABEL, code, 0U, WFAS3_NONE);
    (void)wfas3_beacon_build(&g_dev[0], &g_out);
    memcpy(old, g_out.dgram, sizeof old);
    anywhere = addr_of(0U);
    add_entropy(1U);
    (void)wfas3_receive(&g_dev[1], old, sizeof old, &anywhere, &g_out);   /* its run starts */
    check((g_dev[1].pcli.state == WFAS3_PC_S1) ? 1U : 0U, "replay: a label run starts");
    g_dev[0].win.used = 1U;                     /* as if someone else completed */
    (void)wfas3_beacon_build(&g_dev[0], &g_out);
    st = wfas3_receive(&g_dev[1], g_out.dgram, WFAS3_LEN_PAIR_BEACON, &anywhere, &g_out);
    check((st == WFAS3_OK) && ((g_out.events & WFAS3_EV_PAIR_FAILED) != 0U) &&
          ((g_out.events & WFAS3_EV_PAIR_RACE) != 0U) && (g_out.status == WFAS3_PAIRF_USED) ? 1U : 0U,
          "replay: state 1 with the nonce of the run ends it, with the warning");
    g_w.cut[1] = 0U;

    /* Code: flags 1; state 1 ends a client that is still waiting. */
    setup(122U);
    check((open_window(WFAS3_PAIR_CODE, 0U, code) == WFAS3_OK) && (g_out.dgram[6] == 1U) ? 1U : 0U,
          "replay: a code beacon has flags 1");
    g_dev[0].win.used = 1U;
    (void)wfas3_beacon_build(&g_dev[0], &g_out);
    memcpy(old, g_out.dgram, sizeof old);
    (void)start_client(1U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE);
    anywhere = addr_of(0U);
    st = wfas3_receive(&g_dev[1], old, sizeof old, &anywhere, &g_out);
    check((st == WFAS3_OK) && (g_out.status == WFAS3_PAIRF_USED) &&
          ((g_out.events & WFAS3_EV_PAIR_RACE) != 0U) ? 1U : 0U,
          "replay: a used code window ends a waiting client");
    old[6] = 2U;
    (void)start_client(2U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE);
    check((wfas3_receive(&g_dev[2], old, sizeof old, &anywhere, &g_out) == WFAS3_E_DROP) &&
          (g_dev[2].pcli.state == WFAS3_PC_BEACON) ? 1U : 0U, "replay: unknown flags are an error");
    setup(123U);
    check((open_window(WFAS3_PAIR_PIN, 1U, pin) == WFAS3_OK) && (g_out.dgram[6] == 0U) ? 1U : 0U,
          "replay: a PIN beacon has flags 0");
}

static uint32_t g_spoof;

/* A step 1 that passes the filter. pool == 0: from a new address each time,
 * an address that does not answer, so without a token. pool > 0: from one of
 * pool addresses at which the attacker does receive, so with a token. */
static void bogus_step1(uint32_t pool)
{
    uint8_t d[WFAS3_LEN_INIT];
    uint8_t sk[WFAS_TP_MLKEM_SK];
    uint8_t r64[64];
    uint8_t sid[8];
    uint32_t n = (pool == 0U) ? g_spoof : (1000U + (g_spoof % pool));
    wfas3_addr from;
    wfas3_status st;

    fill_rnd(d, sizeof d);
    fill_rnd(sid, sizeof sid);
    fill_rnd(r64, sizeof r64);
    wfas3_pair_head(d, 1U, g_dev[0].win.mode, sid);
    wfas_tp_mlkem768_keygen(&d[48], sk, r64);
    memset(&d[WFAS3_PAIR_TOKEN], 0, 88U);
    memset(&from, 0, sizeof from);
    from.ip[10] = 0xFFU;
    from.ip[11] = 0xFFU;
    from.ip[12] = 172U;
    from.ip[13] = 16U;
    from.ip[14] = (uint8_t)(n >> 8);
    from.ip[15] = (uint8_t)(n & 0xFFU);
    from.port = 50000U;
    g_spoof += 1U;
    if (pool > 0U) {
        (void)wfas3_pair_token(&d[WFAS3_PAIR_TOKEN], &g_dev[0].win, &from, sid);
    }
    (void)wfas3_mac(&d[1352], 16U, g_dev[0].win.k_f, d, 1352U);   /* public in PIN mode */
    st = wfas3_receive(&g_dev[0], d, sizeof d, &from, &g_out);
    if (st == WFAS3_E_ENTROPY) {
        add_entropy(0U);
        (void)wfas3_receive(&g_dev[0], d, sizeof d, &from, &g_out);
    }
}

/* PIN mode: anyone on the link passes the filter. Step 1 from addresses that
 * do not answer gets Retries only and takes no context; runs from a few
 * addresses that do answer give way after 3 s to the real client, which has
 * not been served yet (13.7). The client starts pre ticks into the attack. */
static void test_pin_hold(uint32_t pool, uint32_t per_tick, uint32_t pre, uint64_t seed)
{
    uint8_t pin[WFAS3_PIN_LEN];
    uint64_t t0;
    uint32_t tick;
    uint32_t k;

    setup(seed);
    g_spoof = 1000U;
    (void)wfas3_beacons(&g_dev[1], 1U);
    (void)open_window(WFAS3_PAIR_PIN, 1U, pin);
    run(1500U);
    for (tick = 0U; tick < pre; tick++) {
        for (k = 0U; k < per_tick; k++) {
            bogus_step1(pool);
        }
        run(10U);
    }
    (void)start_client(1U, WFAS3_PAIR_PIN, pin, 0U, WFAS3_NONE);
    t0 = g_w.now;
    for (tick = 0U; tick < 4000U; tick++) {
        for (k = 0U; k < per_tick; k++) {
            bogus_step1(pool);
        }
        run(10U);
        if ((g_w.w[1].paired != 0U) || (g_w.w[1].failed != 0U)) {
            tick = 4000U;
        }
    }
    if (pool == 0U) {
        check((g_w.w[1].paired == 1U) && (g_w.w[0].paired == 1U) && ((g_w.now - t0) <= 2000U) &&
              (g_dev[0].win.failed == 0U) ? 1U : 0U,
              "pin hold: step 1 from addresses that do not answer takes nothing");
    } else {
        check((g_w.w[1].paired == 1U) && (g_w.w[0].paired == 1U) && ((g_w.now - t0) <= 4500U) ? 1U : 0U,
              "pin hold: runs from a few answering addresses give way to the client");
    }
}

/* One paired device sends junk with a valid mac1 and bad tags, as fast as it
 * likes (7.2). From its own address, its share of the tag computations is
 * used up and the other pair connects even from an address it was never
 * seen at; from many addresses the global cap is used up, and the other pair
 * connects from the address it was last seen at. */
static void test_tag_budget(uint32_t spoof)
{
    uint8_t code[WFAS3_CODE_LEN];
    uint8_t d[WFAS3_LEN_COOKIE];
    wfas3_addr fa = addr_of(1U);
    uint32_t p1;
    uint32_t p2;
    uint32_t ps2;
    uint32_t tick;
    uint32_t i;
    uint32_t s;

    setup(131U + spoof);
    (void)open_window(WFAS3_PAIR_CODE, 0U, code);
    (void)start_client(1U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE);
    run(15000U);
    run(125000U);
    (void)open_window(WFAS3_PAIR_CODE, 0U, code);
    (void)start_client(2U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE);
    run(15000U);
    p1 = g_w.w[1].paired_pair;
    p2 = g_w.w[2].paired_pair;
    ps2 = g_w.w[0].paired_pair;
    check((g_w.w[0].paired == 2U) && (p1 < WFAS3_MAX_PAIRS) && (p2 < WFAS3_MAX_PAIRS) &&
          (ps2 < WFAS3_MAX_PAIRS) ? 1U : 0U, "tag budget: device 0 has two pairs");
    if ((p1 >= WFAS3_MAX_PAIRS) || (p2 >= WFAS3_MAX_PAIRS) || (ps2 >= WFAS3_MAX_PAIRS)) {
        return;
    }
    check(session_up(2U, p2, 0U), "tag budget: device 2 connects without attack");
    for (s = 0U; s < WFAS3_MAX_SESSIONS; s++) {
        if ((g_dev[2].sessions[s].state == WFAS3_SES_UP) &&
            (wfas3_session_close(&g_dev[2], s, WFAS3_BYE_NORMAL, &g_out) == WFAS3_OK)) {
            handle_out(2U);
        }
    }
    run(6000U);
    g_w.w[2].up = 0U;
    g_w.w[0].up = 0U;
    if (spoof == 0U) {
        g_dev[0].pairs[ps2].seen_valid = 0U;            /* as after a restart: no fast path */
    }
    for (tick = 0U; tick < 1000U; tick++) {
        if ((tick % 100U) == 0U) {
            for (i = 0U; i < 2048U; i++) {
                fill_rnd(d, sizeof d);
                d[0] = WFAS3_MARKER;
                d[1] = WFAS3_T_COOKIE_REQUEST;
                d[2] = 0U;
                d[3] = 0U;
                (void)wfas3_mac1_out(&d[48], &g_dev[1].pairs[p1], d, 48U);
                if (spoof == 1U) {
                    fa.ip[13] = (uint8_t)(rnd32() & 0xFFU);
                    fa.ip[14] = (uint8_t)(rnd32() & 0xFFU);
                }
                (void)wfas3_receive(&g_dev[0], d, sizeof d, &fa, &g_out);
            }
        }
        if (tick == 300U) {
            wfas3_addr to = addr_of(0U);
            wfas3_config c = cfg_pcm();

            if (wfas3_connect(&g_dev[2], p2, &to, &c, &g_out) == WFAS3_OK) {
                handle_out(2U);
            }
        }
        run(10U);
    }
    check((g_w.w[2].up >= 1U) && (g_w.w[0].up >= 1U) ? 1U : 0U,
          (spoof == 1U) ? "tag budget: the pair connects from its address during a spoofed flood"
                        : "tag budget: the pair connects, from anywhere, during a flood from one device");
    check((g_dev[0].tag_count <= WFAS3_CAP_TAGS) ? 1U : 0U, "tag budget: the global cap holds");
}

/* A cookie is accepted for at most 60 s from the creation of its secret,
 * with or without traffic (7.5, 15). */
static void test_cookie_age(void)
{
    wfas3_device *dev = &g_dev[0];
    uint8_t cookie[WFAS3_TAG_LEN];
    uint8_t rid[16];
    wfas3_addr a = addr_of(1U);
    uint64_t t0;

    setup(141U);
    fill_rnd(rid, sizeof rid);
    add_entropy(0U);
    check((wfas3_cookie_rotate(dev) == WFAS3_OK) && (dev->cookie_live == 1U) ? 1U : 0U,
          "cookie: a first secret");
    t0 = g_w.now;
    (void)wfas3_cookie_make(cookie, dev->cookie_secret[0], &a, rid, 7U);
    g_w.now = t0 + 29000U;
    (void)wfas3_set_time(dev, US(g_w.now));
    check((wfas3_cookie_live(dev, 0U) == 1U) && (wfas3_cookie_rotate(dev) == WFAS3_OK) &&
          (dev->cookie_live == 1U) ? 1U : 0U, "cookie: no rotation before 30 s");
    g_w.now = t0 + 31000U;
    (void)wfas3_set_time(dev, US(g_w.now));
    check((wfas3_cookie_rotate(dev) == WFAS3_OK) && (dev->cookie_live == 3U) &&
          (wfas3_ct_eq(dev->cookie_secret[1], dev->cookie_secret[0], 32U) == 0U) ? 1U : 0U,
          "cookie: rotated, the previous secret kept");
    g_w.now = t0 + 59000U;
    (void)wfas3_set_time(dev, US(g_w.now));
    check((wfas3_cookie_live(dev, 1U) == 1U) ? 1U : 0U, "cookie: the previous secret before 60 s");
    g_w.now = t0 + 60000U;
    (void)wfas3_set_time(dev, US(g_w.now));
    check((wfas3_cookie_live(dev, 1U) == 0U) && (wfas3_cookie_live(dev, 0U) == 1U) ? 1U : 0U,
          "cookie: refused at 60 s, without any traffic");
    check((wfas3_poll(dev, &g_out) == WFAS3_OK) && (dev->cookie_live == 1U) &&
          (wfas3_all_zero(dev->cookie_secret[1], 32U) == 1U) ? 1U : 0U, "cookie: erased at the next poll");
    check((wfas3_next_deadline(dev) <= US(t0 + 31000U + 60000U)) ? 1U : 0U,
          "cookie: the deadline covers the current secret");
    g_w.now = t0 + 600000U;
    (void)wfas3_set_time(dev, US(g_w.now));
    (void)wfas3_poll(dev, &g_out);
    check((dev->cookie_live == 0U) && (wfas3_all_zero(dev->cookie_secret[0], 32U) == 1U) ? 1U : 0U,
          "cookie: no secret left after an idle while");
}

/* The question to the server's user names the client's address (13.4). */
static void test_ask_addr(void)
{
    uint8_t code[WFAS3_CODE_LEN];
    uint32_t d;
    uint32_t got = 0U;
    wfas3_addr want = addr_of(1U);

    setup(151U);
    g_w.w[0].answer = 0U;
    (void)open_window(WFAS3_PAIR_CODE, 1U, code);
    (void)start_client(1U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE);
    for (d = 0U; (d < 300U) && (got == 0U); d++) {
        run(10U);
        if (g_w.w[0].asks == 1U) {
            got = 1U;
        }
    }
    check((got == 1U) && (wfas3_addr_eq(&g_dev[0].pctx[0].addr, &want) == 1U) ? 1U : 0U,
          "ask: the question was put");
    check((g_w.w[0].ask_addr_ok == 1U) ? 1U : 0U, "ask: PAIR_ASK carries the client's address");
}

/* API: arguments and states. */
static void test_api(void)
{
    uint8_t code[WFAS3_CODE_LEN];
    wfas3_window_req r;
    wfas3_pair_req q;

    setup(101U);
    memset(&r, 0, sizeof r);
    memset(&q, 0, sizeof q);
    r.kind = 4U;
    check((wfas3_window_open(&g_dev[0], &r, code, &g_out) == WFAS3_E_ARG) ? 1U : 0U, "api: bad kind");
    r.kind = WFAS3_PAIR_CODE;
    r.ask = 2U;
    check((wfas3_window_open(&g_dev[0], &r, code, &g_out) == WFAS3_E_ARG) ? 1U : 0U, "api: bad ask");
    r.ask = 1U;
    r.perms = 4U;
    check((wfas3_window_open(&g_dev[0], &r, code, &g_out) == WFAS3_E_ARG) ? 1U : 0U, "api: bad perms");
    r.perms = 1U;
    check((wfas3_window_open(&g_dev[0], &r, code, NULL) == WFAS3_E_ARG) ? 1U : 0U, "api: NULL out");
    check((wfas3_window_open(&g_dev[0], &r, code, &g_out) == WFAS3_OK) ? 1U : 0U, "api: window opens");
    check((g_out.len == WFAS3_LEN_PAIR_BEACON) && (g_out.broadcast == 1U) ? 1U : 0U,
          "api: the first beacon is broadcast");
    check((wfas3_window_open(&g_dev[0], &r, code, &g_out) == WFAS3_E_STATE) ? 1U : 0U,
          "api: one window at a time");
    q.kind = WFAS3_PAIR_CODE;
    q.replace = WFAS3_NONE;
    check((wfas3_pair_start(&g_dev[0], &q, &g_out) == WFAS3_E_STATE) ? 1U : 0U,
          "api: not server and client at once");
    check((wfas3_window_close(&g_dev[0], &g_out) == WFAS3_OK) &&
          ((g_out.events & WFAS3_EV_WINDOW_CLOSED) != 0U) ? 1U : 0U, "api: window closes");
    check((wfas3_window_close(&g_dev[0], &g_out) == WFAS3_E_STATE) ? 1U : 0U, "api: no window");
    check((wfas3_window_failed(&g_dev[0]) == WFAS3_NONE) ? 1U : 0U, "api: no window, no count");
    q.flags = 4U;
    check((wfas3_pair_start(&g_dev[1], &q, &g_out) == WFAS3_E_ARG) ? 1U : 0U, "api: bad flags");
    q.flags = 0U;
    q.replace = 3U;
    check((wfas3_pair_start(&g_dev[1], &q, &g_out) == WFAS3_E_ARG) ? 1U : 0U,
          "api: replace needs a pair");
    q.replace = WFAS3_NONE;
    q.kind = WFAS3_PAIR_PIN;
    memcpy(q.pin, "1234567A", 8U);
    check((wfas3_pair_start(&g_dev[1], &q, &g_out) == WFAS3_E_ARG) ? 1U : 0U, "api: PIN digits only");
    q.kind = WFAS3_PAIR_CODE;
    check((wfas3_pair_start(&g_dev[1], &q, &g_out) == WFAS3_OK) && (g_out.len == 0U) ? 1U : 0U,
          "api: code client waits for the beacon");
    check((wfas3_pair_start(&g_dev[1], &q, &g_out) == WFAS3_E_STATE) ? 1U : 0U, "api: one at a time");
    check((wfas3_pair_confirm(&g_dev[1], 1U, &g_out) == WFAS3_E_STATE) ? 1U : 0U,
          "api: confirm only in PIN mode, at its step");
    check((wfas3_pair_cancel(&g_dev[1]) == WFAS3_OK) && (g_dev[1].pcli.state == WFAS3_PC_FREE) ? 1U : 0U,
          "api: cancel");
    check((wfas3_pair_answer(&g_dev[0], 1U, 1U, 1U, 0U, &g_out) == WFAS3_E_STATE) ? 1U : 0U,
          "api: no question open");
    check((wfas3_beacons(&g_dev[1], 2U) == WFAS3_E_ARG) ? 1U : 0U, "api: beacons 0 or 1");
    check((wfas3_label_code(&g_dev[0], code) == WFAS3_E_STATE) ? 1U : 0U, "api: no label");
}

/* A forged step 2 does not stop the real one (13.3). */
static void test_forged_step2(void)
{
    uint8_t code[WFAS3_CODE_LEN];
    uint8_t fake[1296];
    wfas3_addr from = addr_of(0U);
    wfas3_status st;
    uint32_t k;

    setup(111U);
    check((open_window(WFAS3_PAIR_CODE, 0U, code) == WFAS3_OK) ? 1U : 0U, "forged 2: window");
    (void)start_client(1U, WFAS3_PAIR_CODE, code, 0U, WFAS3_NONE);
    g_w.cut[0] = 1U;
    run(1100U);
    g_w.cut[0] = 0U;
    for (k = 0U; k < 2U; k++) {
        g_w.cut[0] = 0U;
    }
    /* The beacon reached the client before the cut? Make sure step 1 left. */
    if (g_dev[1].pcli.state != WFAS3_PC_S1) {
        run(1100U);
    }
    check((g_dev[1].pcli.state == WFAS3_PC_S1) ? 1U : 0U, "forged 2: the client waits for step 2");
    fill_rnd(fake, sizeof fake);
    wfas3_pair_head(fake, 2U, 1U, g_dev[1].pcli.sid);
    (void)wfas3_mac(&fake[1280], 16U, g_dev[1].pcli.k_f, fake, 1280U);  /* the filter is known */
    st = wfas3_receive(&g_dev[1], fake, sizeof fake, &from, &g_out);
    check((st == WFAS3_E_DROP) && (g_dev[1].pcli.bad2 == 1U) ? 1U : 0U, "forged 2: dropped, counted");
    run(4000U);
    check((g_w.w[1].paired == 1U) && (g_w.w[0].paired == 1U) ? 1U : 0U,
          "forged 2: the real step 2 still pairs");
}

int main(void)
{
    uint64_t seed;

    test_code_text();
    test_code_pairing(1U, 0U);
    for (seed = 2U; seed < 14U; seed++) {
        test_code_pairing(seed, 1U);
    }
    test_label_pairing();
    test_pin_pairing(1U, 1U);
    test_pin_pairing(0U, 1U);
    test_pin_pairing(1U, 0U);
    for (seed = 300U; seed < 320U; seed++) {
        test_pin_lossy(seed);
    }
    for (seed = 400U; seed < 430U; seed++) {
        test_code_pairing(seed, 1U);
    }
    test_wrong_pin();
    test_wrong_key();
    test_wrong_secret();
    test_refused(0U);
    test_refused(1U);
    test_guest();
    test_server_guest();
    test_unused();
    test_replace();
    for (seed = 200U; seed < 206U; seed++) {
        test_race(seed);
    }
    test_admission();
    test_api();
    test_forged_step2();
    test_key_race();
    test_beacon_replay();
    test_pin_hold(0U, 1U, 200U, 160U);
    test_pin_hold(0U, 10U, 200U, 161U);
    for (seed = 0U; seed < 6U; seed++) {
        test_pin_hold(2U, 1U, 150U + (37U * (uint32_t)seed), 170U + seed);
    }
    test_tag_budget(0U);
    test_tag_budget(1U);
    test_cookie_age();
    test_ask_addr();
    (void)printf("%u checks, %u failed\n", g_run, g_fail);
    return (g_fail == 0U) ? 0 : 1;
}
