/* -----------------------------------------------------------------------------
 * WFAS v3 - tests of entry, handshake and pair state machines.
 *
 * Two (or three) devices in one process, joined by a simulated network that
 * can drop, duplicate, delay and reorder datagrams. Includes wfas.c directly
 * so that the pair state can be inspected.
 *
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Marco Morosi
 * ------------------------------------------------------------------------- */
#include "../wfas.c"

#include <stdio.h>

#define NDEV   3U          /* 0 = A, 1 = B, 2 = thief (a copy of A) */
#define NQ     512U
#define US(ms) ((uint64_t)(ms) * 1000U)  /* the core counts microseconds */

typedef struct packet {
    uint32_t used;
    uint32_t to;
    uint32_t from;
    size_t   len;
    uint64_t due;
    uint8_t  data[WFAS3_MAX_DATAGRAM];
} packet;

typedef struct world {
    uint64_t now;
    uint64_t rng;
    uint32_t drop_pct;
    uint32_t dup_pct;
    uint32_t delay_ms;
    uint32_t suspect[NDEV];
    uint32_t up[NDEV];
    uint32_t failed[NDEV];
    uint32_t refused[NDEV];
    uint32_t yielded[NDEV];
    uint32_t store_dev[NDEV];
    wfas3_config cfg[NDEV];
    uint32_t last_session[NDEV];
    uint32_t last_status[NDEV];
    uint32_t check_invariants;
    uint8_t  stored[NDEV][WFAS3_PAIR_RECORD_LEN];
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

/* The session with index i on device d, or NULL. A wrong index from the core
 * makes a check fail instead of crashing the test. */
static const wfas3_session *ses_at(uint32_t d, uint32_t i)
{
    return (i < WFAS3_MAX_SESSIONS) ? &g_dev[d].sessions[i] : NULL;
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

/* The adversary: drop, duplicate, delay. */
static void enqueue(uint32_t to, uint32_t from, const uint8_t *d, size_t len)
{
    uint32_t copies = 1U;
    uint32_t i;

    if ((rnd32() % 100U) < g_w.drop_pct) {
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

static void queue_clear(void)
{
    memset(g_q, 0, sizeof g_q);
}

static const uint8_t *slot_of(const wfas3_pair *p, uint32_t i)
{
    return p->psk[i];
}

/* The invariants of the bounded model, checked on the real state. */
static void invariants(void)
{
    const wfas3_pair *a = &g_dev[0].pairs[0];
    const wfas3_pair *b = &g_dev[1].pairs[0];
    const wfas3_pair *x;
    const wfas3_pair *y;
    uint32_t side;

    for (side = 0U; side < 2U; side++) {
        uint32_t held;
        uint32_t rep_ok;

        x = (side == 0U) ? a : b;
        y = (side == 0U) ? b : a;
        held = wfas3_ct_eq(slot_of(x, x->has_mine), slot_of(y, 0U), 32U);
        held |= y->has_mine & wfas3_ct_eq(slot_of(x, x->has_mine), slot_of(y, 1U), 32U);
        held |= y->has_theirs & wfas3_ct_eq(slot_of(x, x->has_mine), slot_of(y, 2U), 32U);
        check(held, "READY-PSK: the psk a side would use is held by its peer");
        rep_ok = (wfas3_rep_class(y, x->rep_fp[WFAS3_REP_LAST], x->rep_seq[WFAS3_REP_LAST])
                  == WFAS3_CLS_OK) ? 1U : 0U;
        check(rep_ok, "READY-REP: the report a side would send is acceptable to its peer");
    }
}

static void handle_out(uint32_t who, uint32_t peer);

static void deliver(uint32_t to, uint32_t from, const uint8_t *d, size_t len)
{
    wfas3_addr fa = addr_of(from);
    uint8_t ent[WFAS3_ENTROPY_LEN];
    wfas3_status st = wfas3_receive(&g_dev[to], d, len, &fa, &g_out);

    if (st == WFAS3_E_ENTROPY) {
        fill_rnd(ent, sizeof ent);
        (void)wfas3_add_entropy(&g_dev[to], ent);
        st = wfas3_receive(&g_dev[to], d, len, &fa, &g_out);
    }
    if (st == WFAS3_OK) {
        handle_out(to, from);
    }
    if (g_w.check_invariants == 1U) {
        invariants();
    }
}

/* Act on the output of a call made on device who; peer is where it sends. */
static void handle_out(uint32_t who, uint32_t peer)
{
    uint32_t ev = g_out.events;
    uint32_t ses = g_out.session;

    if ((ev & WFAS3_EV_STORE_PAIR) != 0U) {
        (void)wfas3_pair_export(&g_dev[who], g_out.pair, g_w.stored[who]);
    }
    if ((ev & WFAS3_EV_SUSPECT) != 0U) {
        g_w.suspect[who] += 1U;
    }
    if ((ev & WFAS3_EV_ATTEMPT_FAILED) != 0U) {
        g_w.failed[who] += 1U;
    }
    if ((ev & WFAS3_EV_REFUSED) != 0U) {
        g_w.refused[who] += 1U;
        g_w.last_status[who] = g_out.status;
        g_w.cfg[who] = g_out.config;
    }
    if ((ev & WFAS3_EV_ATTEMPT_YIELDED) != 0U) {
        g_w.yielded[who] += 1U;
    }
    if ((ev & WFAS3_EV_STORE_DEVICE) != 0U) {
        g_w.store_dev[who] += 1U;
    }
    if (g_out.len > 0U) {
        uint32_t to = (uint32_t)g_out.to.ip[15] - 10U;   /* addr_of(): 192.168.1.(10 + device) */

        enqueue((to < NDEV) ? to : peer, who, g_out.dgram, g_out.len);
    }
    if ((ev & WFAS3_EV_SESSION_UP) != 0U) {
        g_w.up[who] += 1U;
        g_w.last_session[who] = ses;
        g_w.cfg[who] = g_out.config;
    }
}

/* Run the timers of device d until it has nothing more to do. */
static void poll_all(uint32_t d)
{
    uint8_t ent[WFAS3_ENTROPY_LEN];
    uint32_t k;
    wfas3_status st;

    for (k = 0U; k < 64U; k++) {
        st = wfas3_poll(&g_dev[d], &g_out);
        if (st == WFAS3_E_ENTROPY) {
            fill_rnd(ent, sizeof ent);
            (void)wfas3_add_entropy(&g_dev[d], ent);
        } else if ((st != WFAS3_OK) || ((g_out.len == 0U) && (g_out.events == 0U))) {
            k = 64U;
        } else {
            handle_out(d, 1U - d);
        }
    }
}

/* Make device d build a control packet of session ses now, into g_out.
 * Other outputs of its timers are discarded. 1 if the packet was built. */
static uint32_t ctl_now(uint32_t d, uint32_t ses)
{
    uint8_t ent[WFAS3_ENTROPY_LEN];
    uint32_t got = 0U;
    uint32_t k;
    wfas3_status st;

    if (ses_at(d, ses) != NULL) {
        g_dev[d].sessions[ses].next_ctl_ms = 0U;
        for (k = 0U; (k < 32U) && (got == 0U); k++) {
            st = wfas3_poll(&g_dev[d], &g_out);
            if (st == WFAS3_E_ENTROPY) {
                fill_rnd(ent, sizeof ent);
                (void)wfas3_add_entropy(&g_dev[d], ent);
            } else if ((st == WFAS3_OK) && (g_out.len == WFAS3_LEN_CONTROL) && (g_out.session == ses)) {
                got = 1U;
            } else if ((st != WFAS3_OK) || ((g_out.len == 0U) && (g_out.events == 0U))) {
                k = 32U;
            } else {
                /* another output: discarded */
            }
        }
    }
    return got;
}

/* Advance time by ms, delivering what is due and running the timers. */
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
        for (d = 0U; d < 2U; d++) {
            poll_all(d);
        }
    }
}

/* Pass the pending output of g_out from device src (at address src_addr) to
 * device dst and back, up to steps times, adding entropy when a device asks
 * for it. Stops when a side has nothing to send. */
static void relay(uint32_t src, uint32_t dst, uint32_t steps)
{
    static uint8_t tmp[WFAS3_MAX_DATAGRAM];
    uint8_t ent[WFAS3_ENTROPY_LEN];
    uint32_t i;
    uint32_t a = src;
    uint32_t b = dst;

    for (i = 0U; (i < steps) && (g_out.len > 0U); i++) {
        wfas3_addr from = addr_of(a);
        size_t n = g_out.len;
        uint32_t t;
        wfas3_status st;

        memcpy(tmp, g_out.dgram, n);
        st = wfas3_receive(&g_dev[b], tmp, n, &from, &g_out);
        if (st == WFAS3_E_ENTROPY) {
            fill_rnd(ent, sizeof ent);
            (void)wfas3_add_entropy(&g_dev[b], ent);
            st = wfas3_receive(&g_dev[b], tmp, n, &from, &g_out);
        }
        if (st != WFAS3_OK) {
            g_out.len = 0U;
        }
        t = a;
        a = b;
        b = t;
    }
}

/* Make device dst a thief: it holds the stored record of victim's pair and
 * its entry key, with its counter set offset above the victim's. */
static void make_thief(uint32_t dst, uint32_t victim, const uint8_t *record, uint64_t offset)
{
    memcpy(g_dev[dst].entry_key, g_dev[victim].entry_key, 32U);
    memcpy(g_dev[dst].k_mac1, g_dev[victim].k_mac1, 32U);
    g_dev[dst].ectr_limit = g_dev[victim].ectr_limit + offset + (uint64_t)WFAS3_ECTR_BLOCK;
    g_dev[dst].ectr_high = g_dev[victim].pairs[0].ectr_send + offset;
    check((wfas3_pair_import(&g_dev[dst], 0U, record) == WFAS3_OK) ? 1U : 0U, "thief imports");
}

static wfas3_status do_connect(uint32_t who, uint32_t peer)
{
    wfas3_addr to = addr_of(peer);
    wfas3_config c = cfg_pcm();
    wfas3_status st = wfas3_connect(&g_dev[who], 0U, &to, &c, &g_out);

    if (st == WFAS3_OK) {
        handle_out(who, peer);
    }
    return st;
}

/* -- Setup ------------------------------------------------------------------- */

static void make_record(uint8_t *r, const uint8_t *own_priv, const uint8_t *peer_pub,
                        const uint8_t *k_fix, const uint8_t *psk0, const uint8_t *fp0,
                        const uint8_t *peer_entry, const uint8_t *own_entry, uint8_t perms)
{
    memset(r, 0, WFAS3_PAIR_RECORD_LEN);
    r[0] = 1U;
    r[2] = perms;
    memcpy(&r[4], own_priv, 32U);
    memcpy(&r[36], peer_pub, 32U);
    memcpy(&r[68], k_fix, 32U);
    memcpy(&r[100], psk0, 32U);
    memcpy(&r[196], peer_entry, 32U);
    memcpy(&r[228], own_entry, 32U);
    memcpy(&r[284], fp0, 16U);
    memcpy(&r[308], fp0, 16U);
}

/* Fresh world: A and B paired in slot 0, with two unrelated pairs each. */
static void setup(uint64_t seed)
{
    uint8_t s[NDEV][WFAS3_SEED_LEN];
    uint8_t rec[NDEV][WFAS3_DEVICE_RECORD_LEN];
    uint8_t priv_a[32];
    uint8_t priv_b[32];
    uint8_t pub_a[32];
    uint8_t pub_b[32];
    uint8_t k_fix[32];
    uint8_t psk0[32];
    uint8_t fp0[16];
    uint8_t junk[WFAS3_PAIR_RECORD_LEN];
    uint32_t d;
    uint32_t i;

    memset(&g_w, 0, sizeof g_w);
    queue_clear();
    g_w.rng = seed * 0x9E3779B97F4A7C15ULL + 1U;
    g_w.now = 1000U;
    for (d = 0U; d < NDEV; d++) {
        fill_rnd(s[d], WFAS3_SEED_LEN);
        check((wfas3_device_init(&g_dev[d], s[d], rec[d], 0U, US(g_w.now)) == WFAS3_OK) ? 1U : 0U,
              "device init");
        g_w.last_session[d] = WFAS3_NONE;
    }
    fill_rnd(priv_a, 32U);
    fill_rnd(priv_b, 32U);
    fill_rnd(k_fix, 32U);
    fill_rnd(psk0, 32U);
    fill_rnd(fp0, 16U);
    wfas_tp_x25519_public(pub_a, priv_a);
    wfas_tp_x25519_public(pub_b, priv_b);
    make_record(g_w.stored[0], priv_a, pub_b, k_fix, psk0, fp0, rec[1], rec[0],
                WFAS3_PERM_RECEIVE);
    make_record(g_w.stored[1], priv_b, pub_a, k_fix, psk0, fp0, rec[0], rec[1],
                WFAS3_PERM_RECEIVE);
    check((wfas3_pair_import(&g_dev[0], 0U, g_w.stored[0]) == WFAS3_OK) ? 1U : 0U, "import A");
    check((wfas3_pair_import(&g_dev[1], 0U, g_w.stored[1]) == WFAS3_OK) ? 1U : 0U, "import B");
    for (d = 0U; d < 2U; d++) {
        for (i = 1U; i < 3U; i++) {
            uint8_t jp[32];
            uint8_t jq[32];
            uint8_t jpub[32];

            fill_rnd(jp, 32U);
            fill_rnd(jq, 32U);
            wfas_tp_x25519_public(jpub, jq);
            fill_rnd(k_fix, 32U);
            make_record(junk, jp, jpub, k_fix, psk0, fp0, rec[d], rec[d], 0U);
            check((wfas3_pair_import(&g_dev[d], i, junk) == WFAS3_OK) ? 1U : 0U, "import decoy");
        }
    }
}

static uint32_t session_keys_match(void)
{
    const wfas3_session *sa = ses_at(0U, g_w.last_session[0]);
    const wfas3_session *sb = ses_at(1U, g_w.last_session[1]);
    uint32_t ok = 1U;

    if ((sa == NULL) || (sb == NULL)) {
        return 0U;
    }

    ok &= (sa->state == WFAS3_SES_UP) ? 1U : 0U;
    ok &= (sb->state == WFAS3_SES_UP) ? 1U : 0U;
    ok &= wfas3_ct_eq(sa->k_send, sb->k_recv, 32U);
    ok &= wfas3_ct_eq(sa->k_recv, sb->k_send, 32U);
    ok &= wfas3_ct_eq(sa->fp, sb->fp, 16U);
    ok &= (sa->send_index == sb->recv_index) ? 1U : 0U;
    ok &= (sa->recv_index == sb->send_index) ? 1U : 0U;
    return ok;
}

/* -- Tests ------------------------------------------------------------------- */

static void test_clean_handshakes(void)
{
    const wfas3_pair *a = &g_dev[0].pairs[0];
    const wfas3_pair *b = &g_dev[1].pairs[0];
    uint8_t first_psk[32];

    setup(1U);
    g_w.check_invariants = 1U;
    memcpy(first_psk, a->psk[0], 32U);
    check((do_connect(0U, 1U) == WFAS3_OK) ? 1U : 0U, "connect A to B");
    run(200U);
    check(((g_w.up[0] == 1U) && (g_w.up[1] == 1U)) ? 1U : 0U, "one session on each side");
    check(session_keys_match(), "both sides hold the same session keys");
    check(a->has_mine & b->has_theirs & wfas3_ct_eq(a->psk[1], b->psk[2], 32U),
          "next psk: mine on the initiator, theirs on the responder");
    check(wfas3_ct_eq(a->psk[0], first_psk, 32U) & wfas3_ct_eq(b->psk[0], first_psk, 32U),
          "current psk unchanged after the first handshake");
    check(wfas3_ct_eq(a->rep_fp[WFAS3_REP_LAST], b->rep_fp[WFAS3_REP_LAST], 16U),
          "both sides report the same last session after confirmation");
    check(((a->seq_hi == 1U) && (b->seq_hi == 1U)) ? 1U : 0U, "session sequence number 1");
    check(((g_w.cfg[0].flags & WFAS3_CF_SOURCE) != 0U) ? 1U : 0U, "the initiator is the source");
    check(((g_w.cfg[1].flags & WFAS3_CF_SOURCE) == 0U) ? 1U : 0U, "the responder is the receiver");
    check(((g_w.cfg[0].sample_rate == 48000U) && (g_w.cfg[1].payload_bytes == 960U)) ? 1U : 0U,
          "both sides report the agreed stream");

    run(3000U);
    check((do_connect(0U, 1U) == WFAS3_OK) ? 1U : 0U, "second connect A to B");
    run(200U);
    check(((g_w.up[0] == 2U) && (g_w.up[1] == 2U)) ? 1U : 0U, "second session established");
    check(session_keys_match(), "second session keys match");
    check(wfas3_ct_eq(a->psk[0], b->psk[0], 32U) & (1U ^ wfas3_ct_eq(a->psk[0], first_psk, 32U)),
          "both sides promoted to the new psk and dropped the old one");

    run(3000U);
    check((do_connect(1U, 0U) == WFAS3_OK) ? 1U : 0U, "connect B to A");
    run(200U);
    check(((g_w.up[0] == 3U) && (g_w.up[1] == 3U)) ? 1U : 0U, "session opened by the other side");
    check(session_keys_match(), "third session keys match");
    check(((g_w.suspect[0] + g_w.suspect[1]) == 0U) ? 1U : 0U, "no alert between honest devices");
    check(((a->seq_hi == 3U) && (b->seq_hi == 3U)) ? 1U : 0U, "session sequence number 3");
}

static void test_replay_and_stale(void)
{
    uint8_t creq1[64];
    uint8_t creq2[64];
    wfas3_addr fa = addr_of(0U);
    wfas3_addr tb = addr_of(1U);
    wfas3_config c = cfg_pcm();
    uint8_t before[WFAS3_PAIR_RECORD_LEN];
    uint8_t after[WFAS3_PAIR_RECORD_LEN];

    setup(2U);
    check((wfas3_connect(&g_dev[0], 0U, &tb, &c, &g_out) == WFAS3_OK) ? 1U : 0U, "connect");
    memcpy(creq1, g_out.dgram, 64U);
    check((wfas3_receive(&g_dev[1], creq1, 64U, &fa, &g_out) == WFAS3_OK) ? 1U : 0U,
          "fresh CookieRequest answered");
    check((g_out.len == 64U) ? 1U : 0U, "CookieReply is 64 bytes");
    (void)wfas3_pair_export(&g_dev[1], 0U, before);
    check((wfas3_receive(&g_dev[1], creq1, 64U, &fa, &g_out) == WFAS3_E_DROP) ? 1U : 0U,
          "duplicate of a live CookieRequest is dropped in silence");
    (void)wfas3_pair_export(&g_dev[1], 0U, after);
    check(wfas3_ct_eq(before, after, WFAS3_PAIR_RECORD_LEN), "duplicate changes no state");

    /* A retries: a newer request. The old one then takes the stale path. */
    g_w.now += 6000U;
    (void)wfas3_set_time(&g_dev[0], US(g_w.now));
    (void)wfas3_set_time(&g_dev[1], US(g_w.now));
    check((wfas3_poll(&g_dev[0], &g_out) == WFAS3_OK) ? 1U : 0U, "retry timer");
    check((g_out.len == 64U) ? 1U : 0U, "retry sends a new CookieRequest");
    memcpy(creq2, g_out.dgram, 64U);
    check(1U ^ wfas3_ct_eq(creq1, creq2, 64U), "retry uses a new request");
    check((wfas3_receive(&g_dev[1], creq2, 64U, &fa, &g_out) == WFAS3_OK) ? 1U : 0U,
          "newer CookieRequest answered");
    check((wfas3_receive(&g_dev[1], creq1, 64U, &fa, &g_out) == WFAS3_OK) ? 1U : 0U,
          "older CookieRequest gets a stale notice");
    check((g_out.len == 64U) ? 1U : 0U, "stale notice is 64 bytes");
    check((wfas3_receive(&g_dev[0], g_out.dgram, 64U, &tb, &g_out) == WFAS3_E_DROP) ? 1U : 0U,
          "stale notice for an abandoned request is ignored by the initiator");
    check(((g_dev[0].pairs[0].flags & WFAS3_PF_SUSPECT) == 0U) ? 1U : 0U, "no false alert");
    check((wfas3_receive(&g_dev[1], creq1, 64U, &fa, &g_out) == WFAS3_E_DROP) ? 1U : 0U,
          "second stale answer within 30 s is refused");
}

static void test_forgeries(void)
{
    uint8_t creq[64];
    uint8_t crep[64];
    static uint8_t init[1368];
    static uint8_t resp[1228];
    static uint8_t bad[1368];
    wfas3_addr fa = addr_of(0U);
    wfas3_addr fb = addr_of(1U);
    wfas3_addr other = addr_of(7U);
    wfas3_config c = cfg_pcm();
    uint8_t before[WFAS3_PAIR_RECORD_LEN];
    uint8_t after[WFAS3_PAIR_RECORD_LEN];
    uint32_t i;
    uint32_t all;

    setup(3U);
    (void)wfas3_connect(&g_dev[0], 0U, &fb, &c, &g_out);
    memcpy(creq, g_out.dgram, 64U);
    all = 1U;
    for (i = 4U; i < 64U; i++) {
        memcpy(bad, creq, 64U);
        bad[i] ^= 0x01U;
        all &= (wfas3_receive(&g_dev[1], bad, 64U, &fa, &g_out) == WFAS3_E_DROP) ? 1U : 0U;
    }
    check(all, "CookieRequest with any byte changed is dropped");
    check((g_dev[1].pairs[0].ectr_seen == 0U) ? 1U : 0U, "forged CookieRequest left no trace");
    (void)wfas3_receive(&g_dev[1], creq, 64U, &fa, &g_out);
    memcpy(crep, g_out.dgram, 64U);
    all = 1U;
    for (i = 4U; i < 64U; i++) {
        memcpy(bad, crep, 64U);
        bad[i] ^= 0x01U;
        all &= (wfas3_receive(&g_dev[0], bad, 64U, &fb, &g_out) == WFAS3_E_DROP) ? 1U : 0U;
    }
    check(all, "CookieReply with any byte changed is ignored");
    check((g_dev[0].attempts[0].state == WFAS3_ATT_REQ) ? 1U : 0U,
          "the attempt survives forged CookieReplies");
    check((wfas3_receive(&g_dev[0], crep, 64U, &fb, &g_out) == WFAS3_OK) ? 1U : 0U,
          "genuine CookieReply accepted afterwards");
    check((g_out.len == 1368U) ? 1U : 0U, "Init is 1368 bytes");
    memcpy(init, g_out.dgram, 1368U);

    (void)wfas3_pair_export(&g_dev[1], 0U, before);
    check((wfas3_receive(&g_dev[1], init, 1368U, &other, &g_out) == WFAS3_E_DROP) ? 1U : 0U,
          "Init from another address fails the cookie");
    all = 1U;
    for (i = 4U; i < 1368U; i += 7U) {
        memcpy(bad, init, 1368U);
        bad[i] ^= 0x80U;
        all &= (wfas3_receive(&g_dev[1], bad, 1368U, &fa, &g_out) == WFAS3_E_DROP) ? 1U : 0U;
    }
    check(all, "Init with a byte changed is dropped");
    (void)wfas3_pair_export(&g_dev[1], 0U, after);
    check(wfas3_ct_eq(before, after, WFAS3_PAIR_RECORD_LEN),
          "forged Inits change nothing: the cookie is still usable");
    check((wfas3_receive(&g_dev[1], init, 1368U, &fa, &g_out) == WFAS3_OK) ? 1U : 0U,
          "genuine Init accepted afterwards");
    check((g_out.len == 1228U) ? 1U : 0U, "Resp is 1228 bytes");
    check(((g_out.events & WFAS3_EV_STORE_PAIR) != 0U) ? 1U : 0U,
          "responder asks to store the pair before sending Resp");
    memcpy(resp, g_out.dgram, 1228U);
    check((wfas3_receive(&g_dev[1], init, 1368U, &fa, &g_out) == WFAS3_E_DROP) ? 1U : 0U,
          "replayed Init is dropped: the cookie was consumed");

    all = 1U;
    for (i = 4U; i < 1228U; i += 5U) {
        memcpy(bad, resp, 1228U);
        bad[i] ^= 0x04U;
        all &= (wfas3_receive(&g_dev[0], bad, 1228U, &fb, &g_out) == WFAS3_E_DROP) ? 1U : 0U;
    }
    check(all, "Resp with a byte changed is ignored");
    check((g_dev[0].attempts[0].state == WFAS3_ATT_OUT) ? 1U : 0U,
          "the attempt survives forged Resps");
    check((wfas3_receive(&g_dev[0], resp, 1228U, &fb, &g_out) == WFAS3_OK) ? 1U : 0U,
          "genuine Resp accepted afterwards");
    check(((g_out.events & WFAS3_EV_SESSION_UP) != 0U) ? 1U : 0U, "initiator session up");
    check((wfas3_receive(&g_dev[0], resp, 1228U, &fb, &g_out) == WFAS3_E_DROP) ? 1U : 0U,
          "replayed Resp is dropped");
}

static void test_entropy_and_limits(void)
{
    uint8_t creq[64];
    static uint8_t init[1368];
    wfas3_addr fa = addr_of(0U);
    wfas3_addr fb = addr_of(1U);
    wfas3_config c = cfg_pcm();
    uint8_t ent[32];
    uint8_t before[WFAS3_PAIR_RECORD_LEN];
    uint8_t after[WFAS3_PAIR_RECORD_LEN];

    setup(4U);
    fill_rnd(ent, sizeof ent);
    g_dev[0].entropy_fresh = 0U;
    g_dev[1].entropy_fresh = 0U;
    (void)wfas3_connect(&g_dev[0], 0U, &fb, &c, &g_out);
    memcpy(creq, g_out.dgram, 64U);
    (void)wfas3_receive(&g_dev[1], creq, 64U, &fa, &g_out);
    memcpy(init, g_out.dgram, 64U);
    check((wfas3_receive(&g_dev[0], init, 64U, &fb, &g_out) == WFAS3_E_ENTROPY) ? 1U : 0U,
          "initiator refuses to build Init without fresh entropy");
    check((g_dev[0].attempts[0].state == WFAS3_ATT_REQ) ? 1U : 0U, "attempt unchanged");
    (void)wfas3_add_entropy(&g_dev[0], ent);
    check((wfas3_receive(&g_dev[0], init, 64U, &fb, &g_out) == WFAS3_OK) ? 1U : 0U,
          "same CookieReply accepted after entropy was added");
    memcpy(init, g_out.dgram, 1368U);
    (void)wfas3_pair_export(&g_dev[1], 0U, before);
    check((wfas3_receive(&g_dev[1], init, 1368U, &fa, &g_out) == WFAS3_E_ENTROPY) ? 1U : 0U,
          "responder refuses to answer Init without fresh entropy");
    (void)wfas3_pair_export(&g_dev[1], 0U, after);
    check(wfas3_ct_eq(before, after, WFAS3_PAIR_RECORD_LEN), "responder state unchanged");
    (void)wfas3_add_entropy(&g_dev[1], ent);
    check((wfas3_receive(&g_dev[1], init, 1368U, &fa, &g_out) == WFAS3_OK) ? 1U : 0U,
          "same Init accepted after entropy was added");

    /* Quota: a second fresh request within 2 s is dropped and voids the cookie. */
    setup(5U);
    (void)wfas3_connect(&g_dev[0], 0U, &fb, &c, &g_out);
    (void)wfas3_receive(&g_dev[1], g_out.dgram, 64U, &fa, &g_out);
    g_w.now += 5000U;
    (void)wfas3_set_time(&g_dev[0], US(g_w.now));
    (void)wfas3_poll(&g_dev[0], &g_out);
    check((wfas3_receive(&g_dev[1], g_out.dgram, 64U, &fa, &g_out) == WFAS3_E_DROP) ? 1U : 0U,
          "second fresh CookieRequest within 2 s is dropped");
    check((g_dev[1].pairs[0].ectr_used == g_dev[1].pairs[0].ectr_seen) ? 1U : 0U,
          "a dropped fresh request leaves no usable cookie");

    /* Give up after 60 s. */
    g_w.now += 61000U;
    (void)wfas3_set_time(&g_dev[0], US(g_w.now));
    check(((wfas3_poll(&g_dev[0], &g_out) == WFAS3_OK) &&
           ((g_out.events & WFAS3_EV_ATTEMPT_FAILED) != 0U)) ? 1U : 0U,
          "attempt gives up after 60 s");
    check((g_dev[0].attempts[0].state == WFAS3_ATT_FREE) ? 1U : 0U, "attempt slot freed");
}

/* Rule C2 on its own. B issues a cookie to A; A's Init is delayed beyond the
 * 5 s of rule C5; B then opens its own attempt. The cookie must be void, so
 * the late Init fails at the cookie check and two handshakes never cross. */
static void test_late_init(void)
{
    static uint8_t late[WFAS3_LEN_INIT];
    wfas3_addr fa = addr_of(0U);
    wfas3_addr fb = addr_of(1U);
    wfas3_config c = cfg_pcm();
    wfas3_status st;

    setup(30U);
    check((wfas3_connect(&g_dev[0], 0U, &fb, &c, &g_out) == WFAS3_OK) ? 1U : 0U, "A connects");
    relay(0U, 1U, 2U);                          /* CookieRequest, CookieReply */
    check((g_out.len == WFAS3_LEN_INIT) ? 1U : 0U, "A built its Init");
    memcpy(late, g_out.dgram, sizeof late);    /* held back by the network */
    check((g_dev[1].pairs[0].ectr_used < g_dev[1].pairs[0].ectr_seen) ? 1U : 0U,
          "B holds an issued cookie");
    check((wfas3_connect(&g_dev[1], 0U, &fa, &c, &g_out) == WFAS3_E_STATE) ? 1U : 0U,
          "rule C5: B may not open an attempt right after issuing a cookie");
    g_w.now += 6000U;
    (void)wfas3_set_time(&g_dev[0], US(g_w.now));
    (void)wfas3_set_time(&g_dev[1], US(g_w.now));
    check((wfas3_connect(&g_dev[1], 0U, &fa, &c, &g_out) == WFAS3_OK) ? 1U : 0U,
          "after 5 s B opens its own attempt");
    check((g_dev[1].pairs[0].ectr_used == g_dev[1].pairs[0].ectr_seen) ? 1U : 0U,
          "rule C2: opening an attempt voids the cookie B had issued");
    g_dev[1].attempts[0].state = WFAS3_ATT_FREE;    /* isolate C2 from the attempt check */
    st = wfas3_receive(&g_dev[1], late, sizeof late, &fa, &g_out);
    check((st == WFAS3_E_DROP) ? 1U : 0U, "the late Init fails at the cookie check");
}

static void test_crossing(void)
{
    uint32_t seed;

    for (seed = 10U; seed < 30U; seed++) {
        setup(seed);
        g_w.check_invariants = 1U;
        g_w.delay_ms = 40U;
        check((do_connect(0U, 1U) == WFAS3_OK) ? 1U : 0U, "A connects");
        check((do_connect(1U, 0U) == WFAS3_OK) ? 1U : 0U, "B connects at the same time");
        run(400U);
        check(((g_w.suspect[0] + g_w.suspect[1]) == 0U) ? 1U : 0U, "crossing: no alert");
        check(((g_w.failed[0] + g_w.failed[1]) == 0U) ? 1U : 0U, "crossing: no attempt fails");
        check(((g_w.yielded[0] + g_w.yielded[1]) == 1U) ? 1U : 0U,
              "crossing: exactly one side yields");
        check((g_w.yielded[g_dev[0].pairs[0].is_low] == 1U) ? 1U : 0U,
              "crossing: the high side is the one that yields");
        g_w.delay_ms = 0U;
        run(12000U);
        check(((g_w.up[0] >= 1U) && (g_w.up[1] >= 1U)) ? 1U : 0U,
              "crossing: a session is established in the end");
        check(session_keys_match(), "crossing: the surviving session has matching keys");
    }
}

static void test_hostile_network(void)
{
    uint32_t seed;
    uint32_t round;
    uint32_t total_up = 0U;

    for (seed = 100U; seed < 140U; seed++) {
        setup(seed);
        g_w.check_invariants = 1U;
        g_w.drop_pct = 25U;
        g_w.dup_pct = 25U;
        g_w.delay_ms = 3000U;
        for (round = 0U; round < 12U; round++) {
            uint32_t who = rnd32() % 2U;

            (void)do_connect(who, 1U - who);
            if ((rnd32() % 3U) == 0U) {
                (void)do_connect(1U - who, who);
            }
            run(1000U + (rnd32() % 9000U));
        }
        check(((g_w.suspect[0] + g_w.suspect[1]) == 0U) ? 1U : 0U,
              "hostile network: no alert between honest devices");
        total_up += g_w.up[0];
        /* The network heals: a new handshake must succeed and promote. */
        g_w.drop_pct = 0U;
        g_w.dup_pct = 0U;
        g_w.delay_ms = 0U;
        run(70000U);
        queue_clear();
        g_w.up[0] = 0U;
        g_w.up[1] = 0U;
        check((do_connect(0U, 1U) == WFAS3_OK) ? 1U : 0U, "connect after the network healed");
        run(300U);
        check(((g_w.up[0] == 1U) && (g_w.up[1] == 1U)) ? 1U : 0U,
              "handshake succeeds after the network healed");
        check(session_keys_match(), "keys match after the network healed");
    }
    check((total_up > 40U) ? 1U : 0U, "hostile network: handshakes did complete under attack");
}

static void test_wrong_psk_and_reports(void)
{
    setup(50U);
    g_dev[1].pairs[0].psk[0][0] ^= 0x01U;
    (void)do_connect(0U, 1U);
    run(200U);
    check(((g_w.suspect[0] == 1U) && (g_w.suspect[1] == 1U)) ? 1U : 0U,
          "unknown psk: Reject, and both sides raise the alert");
    check(((g_w.up[0] + g_w.up[1]) == 0U) ? 1U : 0U, "unknown psk: no session");
    check((g_w.failed[0] == 1U) ? 1U : 0U, "unknown psk: the attempt ends");

    setup(51U);
    g_dev[0].pairs[0].rep_fp[WFAS3_REP_LAST][3] ^= 0x10U;
    (void)do_connect(0U, 1U);
    run(200U);
    check(((g_w.suspect[0] == 1U) && (g_w.suspect[1] == 1U)) ? 1U : 0U,
          "unknown report in Init: Reject, and both sides raise the alert");

    setup(52U);
    g_dev[1].pairs[0].rep_fp[WFAS3_REP_LAST][3] ^= 0x10U;
    (void)do_connect(0U, 1U);
    run(200U);
    check((g_w.suspect[0] == 1U) ? 1U : 0U, "unknown report in Resp: the initiator raises the alert");
    check((g_w.up[0] == 0U) ? 1U : 0U, "unknown report in Resp: no session");
}

/* A thief copies the stored record of A and uses it from another device. */
static void test_thief(void)
{
    uint8_t stolen[WFAS3_PAIR_RECORD_LEN];
    wfas3_addr tb = addr_of(1U);
    wfas3_config c = cfg_pcm();

    /* Case 1: the thief's counter is ahead: the real device gets a stale notice. */
    setup(60U);
    (void)do_connect(0U, 1U);
    run(200U);
    (void)wfas3_pair_export(&g_dev[0], 0U, stolen);
    make_thief(2U, 0U, stolen, 1000000U);
    run(3000U);
    check((wfas3_connect(&g_dev[2], 0U, &tb, &c, &g_out) == WFAS3_OK) ? 1U : 0U, "thief connects");
    relay(2U, 1U, 4U);
    check(((ses_at(2U, g_out.session) != NULL) &&
           (g_dev[2].sessions[g_out.session].state == WFAS3_SES_UP)) ? 1U : 0U,
          "the thief completes a handshake with stolen keys");
    run(3000U);
    g_w.suspect[0] = 0U;
    (void)do_connect(0U, 1U);
    run(200U);
    check((g_w.suspect[0] == 1U) ? 1U : 0U,
          "the real device is told: stale notice on its live request");

    /* Case 2: the real device's counter is ahead: the report gives the thief away. */
    setup(61U);
    (void)do_connect(0U, 1U);
    run(200U);
    (void)wfas3_pair_export(&g_dev[0], 0U, stolen);
    make_thief(2U, 0U, stolen, 0U);
    g_dev[0].pairs[0].ectr_send += 500U;
    run(3000U);
    check((wfas3_connect(&g_dev[2], 0U, &tb, &c, &g_out) == WFAS3_OK) ? 1U : 0U, "thief connects");
    relay(2U, 1U, 4U);
    check(((g_out.events & WFAS3_EV_SESSION_UP) != 0U) ? 1U : 0U, "thief session up");
    check(ctl_now(2U, g_out.session), "thief confirms the key");
    relay(2U, 1U, 1U);
    check(((g_out.events & WFAS3_EV_SESSION_UP) != 0U) ? 1U : 0U,
          "the peer concludes a session with the thief");
    run(3000U);
    g_w.suspect[0] = 0U;
    g_w.up[0] = 0U;
    (void)do_connect(0U, 1U);
    run(200U);
    check((g_w.suspect[0] == 1U) ? 1U : 0U,
          "the real device is told: the peer reports a session it never took part in");
    check((g_w.up[0] == 0U) ? 1U : 0U, "no session is created after the alert");
}

/* A copy of A taken earlier, whose psk is still accepted but whose report is
 * older than what B has seen: B rejects it as stale and raises the alert. */
static void test_stale_report(void)
{
    uint8_t copy[WFAS3_PAIR_RECORD_LEN];
    wfas3_addr tb = addr_of(1U);
    wfas3_config c = cfg_pcm();

    setup(62U);
    (void)do_connect(0U, 1U);                  /* 1: A opens */
    run(3000U);
    (void)wfas3_pair_export(&g_dev[0], 0U, copy);
    (void)do_connect(1U, 0U);                  /* 2: B opens */
    run(3000U);
    (void)do_connect(0U, 1U);                  /* 3: A opens */
    run(3000U);
    check(((g_w.up[0] == 3U) && (g_w.up[1] == 3U)) ? 1U : 0U, "three sessions");
    check(((g_w.suspect[0] + g_w.suspect[1]) == 0U) ? 1U : 0U, "no alert so far");
    make_thief(2U, 0U, copy, 1000U);
    check((wfas3_connect(&g_dev[2], 0U, &tb, &c, &g_out) == WFAS3_OK) ? 1U : 0U, "old copy connects");
    relay(2U, 1U, 3U);
    check((g_dev[1].pairs[0].flags & WFAS3_PF_SUSPECT) == WFAS3_PF_SUSPECT,
          "stale report: the responder raises the alert");
    check((g_out.len == WFAS3_LEN_REJECT) ? 1U : 0U, "stale report: the answer is a Reject");
    relay(1U, 2U, 1U);
    check((g_dev[2].pairs[0].flags & WFAS3_PF_SUSPECT) == WFAS3_PF_SUSPECT,
          "stale report: the holder of the old copy is told as well");
}

/* Reloading a pair while the device runs must not reuse counters. */
static void test_counters(void)
{
    uint8_t rec[WFAS3_PAIR_RECORD_LEN];
    uint8_t devrec[WFAS3_DEVICE_RECORD_LEN];
    uint8_t seed[WFAS3_SEED_LEN];
    uint64_t limit;

    setup(63U);
    (void)do_connect(0U, 1U);
    run(3000U);
    (void)do_connect(0U, 1U);
    run(3000U);
    (void)wfas3_pair_export(&g_dev[0], 0U, rec);
    check((wfas3_pair_import(&g_dev[0], 0U, rec) == WFAS3_OK) ? 1U : 0U, "reload the pair");
    (void)do_connect(0U, 1U);
    run(300U);
    check(((g_w.up[0] == 3U) && (g_w.suspect[0] == 0U)) ? 1U : 0U,
          "reloaded pair: no counter reuse, no alert");

    /* Crossing a reserve block asks to store the device record first. */
    g_dev[0].pairs[0].ectr_send = g_dev[0].ectr_limit - 1U;
    g_dev[0].ectr_high = g_dev[0].pairs[0].ectr_send;
    limit = g_dev[0].ectr_limit;
    run(3000U);
    g_w.store_dev[0] = 0U;
    (void)do_connect(0U, 1U);
    check((g_w.store_dev[0] == 1U) ? 1U : 0U, "reaching the reserve asks to store the device record");
    check((wfas3_device_export(&g_dev[0], devrec) == WFAS3_OK) ? 1U : 0U, "device export");
    check((wfas3_rd_be64(&devrec[32]) == (limit + (uint64_t)WFAS3_ECTR_BLOCK)) ? 1U : 0U,
          "the stored reserve moved by one block");
    run(300U);
    (void)wfas3_pair_export(&g_dev[0], 0U, rec);
    fill_rnd(seed, sizeof seed);
    check((wfas3_device_init(&g_dev[0], seed, devrec, 1U, US(g_w.now)) == WFAS3_OK) ? 1U : 0U,
          "restart from the stored records");
    (void)wfas3_pair_import(&g_dev[0], 0U, rec);
    check((g_dev[0].pairs[0].ectr_send >= limit) ? 1U : 0U,
          "after the restart the counter is above every value used");
    run(3000U);
    g_w.up[0] = 0U;
    (void)do_connect(0U, 1U);
    run(300U);
    check(((g_w.up[0] == 1U) && (g_w.suspect[0] == 0U)) ? 1U : 0U, "handshake after the restart");
}

/* The responder forgets a session that is never confirmed. */
static void test_unconfirmed(void)
{
    wfas3_addr tb = addr_of(1U);
    wfas3_config c = cfg_pcm();
    uint32_t i;
    uint32_t pending;

    setup(64U);
    check((wfas3_connect(&g_dev[0], 0U, &tb, &c, &g_out) == WFAS3_OK) ? 1U : 0U, "connect");
    relay(0U, 1U, 4U);
    check(((g_out.events & WFAS3_EV_SESSION_UP) != 0U) ? 1U : 0U, "initiator up, confirmation not sent");
    pending = 0U;
    for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
        pending += (g_dev[1].sessions[i].state == WFAS3_SES_UNCONFIRMED) ? 1U : 0U;
    }
    check((pending == 1U) ? 1U : 0U, "responder holds one unconfirmed session");
    g_w.drop_pct = 100U;                       /* the confirmation never arrives */
    run(16000U);
    g_w.drop_pct = 0U;
    pending = 0U;
    for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
        pending += (g_dev[1].sessions[i].state != WFAS3_SES_FREE) ? 1U : 0U;
    }
    check((pending == 0U) ? 1U : 0U, "unconfirmed session discarded after 15 s");
    check(((g_w.suspect[0] + g_w.suspect[1]) == 0U) ? 1U : 0U, "no alert");
    g_w.up[0] = 0U;
    (void)do_connect(0U, 1U);
    run(300U);
    check(((g_w.up[0] == 1U) && (g_w.up[1] == 1U)) ? 1U : 0U, "a new handshake works afterwards");

    /* All session slots taken, one of them by an unconfirmed session of this
     * pair: a new Init replaces it instead of being refused as busy. */
    setup(65U);
    check((wfas3_connect(&g_dev[0], 0U, &tb, &c, &g_out) == WFAS3_OK) ? 1U : 0U, "connect");
    relay(0U, 1U, 4U);
    for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
        if (g_dev[1].sessions[i].state == WFAS3_SES_FREE) {
            g_dev[1].sessions[i].state = WFAS3_SES_UNCONFIRMED;   /* held by another pair */
            g_dev[1].sessions[i].pair = 1U;
            g_dev[1].sessions[i].recv_index = 0x70000000U + i;
            g_dev[1].sessions[i].created_ms = g_w.now;
        }
    }
    g_w.drop_pct = 100U;                       /* keep the session of pair 0 unconfirmed */
    run(3000U);
    g_w.drop_pct = 0U;
    /* A loses its session (a restart): otherwise its periodic control packets
     * would confirm the one waiting on B, which then may not be replaced
     * before a new one is confirmed (specification 8.5). */
    for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
        if (g_dev[0].sessions[i].state == WFAS3_SES_UP) {
            wfas3_ses_free(&g_dev[0].sessions[i]);
        }
    }
    g_w.up[0] = 0U;
    g_w.refused[0] = 0U;
    (void)do_connect(0U, 1U);
    run(300U);
    check(((g_w.refused[0] == 0U) && (g_w.up[0] == 1U) && (g_w.up[1] == 1U)) ? 1U : 0U,
          "a full device replaces the unconfirmed session of the same pair");
}

/* Secret randomness never reaches a public field. The generator is
 * deterministic, so a copy of the responder's state predicts its draws: the
 * Resp sender_index must be a draw of its own, not bytes of the ML-KEM
 * message m, and a colliding index must be drawn again. This pins the order
 * of draws in wfas3_resp_tokens(); update it if that order changes. */
static void test_randomness(void)
{
    static uint8_t init[WFAS3_LEN_INIT];
    wfas3_addr fa = addr_of(0U);
    wfas3_addr fb = addr_of(1U);
    wfas3_config c = cfg_pcm();
    uint8_t ent[WFAS3_ENTROPY_LEN];
    uint8_t first[4];
    uint8_t second[4];
    uint8_t rnd[64];
    uint8_t e_pub[32];
    wfas3_rng shadow;
    uint32_t round;

    for (round = 0U; round < 2U; round++) {
        setup(67U + round);
        (void)wfas3_connect(&g_dev[0], 0U, &fb, &c, &g_out);
        relay(0U, 1U, 2U);
        check((g_out.len == WFAS3_LEN_INIT) ? 1U : 0U, "Init ready");
        memcpy(init, g_out.dgram, sizeof init);
        fill_rnd(ent, sizeof ent);
        (void)wfas3_add_entropy(&g_dev[1], ent);
        shadow = g_dev[1].rng;
        (void)wfas3_rng_bytes(&shadow, first, sizeof first);
        if (round == 1U) {
            /* occupy the index the responder will draw first */
            g_dev[1].sessions[5].state = WFAS3_SES_UP;
            g_dev[1].sessions[5].pair = 1U;
            g_dev[1].sessions[5].recv_index = wfas3_rd_be32(first);
            (void)wfas3_rng_bytes(&shadow, second, sizeof second);
        }
        (void)wfas3_rng_bytes(&shadow, rnd, sizeof rnd);
        check((wfas3_receive(&g_dev[1], init, sizeof init, &fa, &g_out) == WFAS3_OK) ? 1U : 0U,
              "responder answers");
        check((g_out.len == WFAS3_LEN_RESP) ? 1U : 0U, "Resp built");
        wfas_tp_x25519_public(e_pub, rnd);
        check(wfas3_ct_eq(&g_out.dgram[12], e_pub, 32U), "the draws were predicted correctly");
        check(1U ^ wfas3_ct_eq(&g_out.dgram[4], &rnd[32], 4U), "sender_index is not part of the ML-KEM message");
        check(wfas3_ct_eq(&g_out.dgram[4], (round == 0U) ? first : second, 4U),
              (round == 0U) ? "sender_index is a draw of its own"
                            : "a colliding sender_index is drawn again");
    }
}

static void test_refusals(void)
{
    wfas3_policy pol;
    wfas3_addr tb = addr_of(1U);
    wfas3_config c = cfg_pcm();

    setup(70U);
    pol = g_dev[1].policy;
    pol.busy = 1U;
    check((wfas3_set_policy(&g_dev[1], &pol) == WFAS3_OK) ? 1U : 0U, "set policy");
    (void)do_connect(0U, 1U);
    run(200U);
    check(((g_w.refused[0] == 1U) && (g_w.last_status[0] == WFAS3_ST_BUSY)) ? 1U : 0U,
          "busy responder: refused with status busy");
    check(((g_w.up[0] + g_w.up[1]) == 0U) ? 1U : 0U, "busy: no session");
    check(((g_w.suspect[0] + g_w.suspect[1]) == 0U) ? 1U : 0U, "busy: no alert");
    check(g_dev[0].pairs[0].has_mine & g_dev[1].pairs[0].has_theirs,
          "busy: the psk rules still apply");

    setup(71U);
    g_dev[1].pairs[0].perms = 0U;
    c.flags = 0U;                       /* the initiator wants to receive */
    check((wfas3_connect(&g_dev[0], 0U, &tb, &c, &g_out) == WFAS3_OK) ? 1U : 0U, "connect");
    handle_out(0U, 1U);
    run(200U);
    check(((g_w.refused[0] == 1U) && (g_w.last_status[0] == WFAS3_ST_NOT_PERMITTED)) ? 1U : 0U,
          "receiving without permission: refused");

    setup(72U);
    c = cfg_pcm();
    c.codec = WFAS3_CODEC_OPUS;
    c.frame_samples = 240U;
    c.payload_bytes = 80U;
    check((wfas3_connect(&g_dev[0], 0U, &tb, &c, &g_out) == WFAS3_OK) ? 1U : 0U, "connect opus");
    handle_out(0U, 1U);
    run(200U);
    check(((g_w.refused[0] == 1U) && (g_w.last_status[0] == WFAS3_ST_UNSUPPORTED)) ? 1U : 0U,
          "codec not accepted: refused with a suggestion");
    check(((g_w.cfg[0].codec == WFAS3_CODEC_PCM) && (g_w.cfg[0].sample_rate == 48000U) &&
           ((g_w.cfg[0].flags & WFAS3_CF_SOURCE) == 0U)) ? 1U : 0U,
          "the suggestion is reported from the initiator's point of view");

    c = cfg_pcm();
    c.frame_samples = 241U;
    check((wfas3_connect(&g_dev[0], 0U, &tb, &c, &g_out) == WFAS3_E_ARG) ? 1U : 0U,
          "inconsistent configuration is rejected locally");
    c = cfg_pcm();
    c.channels = 8U;
    c.frame_samples = 84U;
    c.payload_bytes = 1344U;
    check((wfas3_connect(&g_dev[1], 0U, &tb, &c, &g_out) == WFAS3_OK) ? 1U : 0U,
          "7.1 at 16 bit: 84 samples, 1344 bytes is valid");
}

static void test_transport_and_restart(void)
{
    static uint8_t p0[160];
    static uint8_t p1[160];
    static uint8_t p2[160];
    wfas3_addr fa = addr_of(0U);
    uint8_t seed[WFAS3_SEED_LEN];
    uint8_t rec[WFAS3_DEVICE_RECORD_LEN];
    uint32_t sa;

    setup(80U);
    (void)do_connect(0U, 1U);
    run(200U);
    sa = g_w.last_session[0];
    check(ctl_now(0U, sa), "control packet 0");
    memcpy(p0, g_out.dgram, 160U);
    check(ctl_now(0U, sa), "control packet 1");
    memcpy(p1, g_out.dgram, 160U);
    check(ctl_now(0U, sa), "control packet 2");
    memcpy(p2, g_out.dgram, 160U);
    check((wfas3_receive(&g_dev[1], p2, 160U, &fa, &g_out) == WFAS3_OK) ? 1U : 0U, "packet 2");
    check((wfas3_receive(&g_dev[1], p0, 160U, &fa, &g_out) == WFAS3_OK) ? 1U : 0U,
          "older packet inside the window accepted");
    check((wfas3_receive(&g_dev[1], p0, 160U, &fa, &g_out) == WFAS3_E_DROP) ? 1U : 0U,
          "replayed packet dropped");
    p1[40] ^= 0x01U;
    check((wfas3_receive(&g_dev[1], p1, 160U, &fa, &g_out) == WFAS3_E_DROP) ? 1U : 0U,
          "packet with a changed byte dropped");
    p1[40] ^= 0x01U;
    check((wfas3_receive(&g_dev[1], p1, 160U, &fa, &g_out) == WFAS3_OK) ? 1U : 0U,
          "the genuine packet is still accepted after the forgery");
    check(ctl_now(1U, g_w.last_session[1]), "responder sends after confirmation");

    /* Restart of A from what it stored: counters must not go back. */
    (void)wfas3_pair_export(&g_dev[0], 0U, g_w.stored[0]);
    memcpy(rec, g_dev[0].entry_key, 32U);
    wfas3_wr_be64(&rec[32], g_dev[0].ectr_limit);
    fill_rnd(seed, sizeof seed);
    check((wfas3_device_init(&g_dev[0], seed, rec, 1U, US(g_w.now)) == WFAS3_OK) ? 1U : 0U, "restart A");
    check((wfas3_pair_import(&g_dev[0], 0U, g_w.stored[0]) == WFAS3_OK) ? 1U : 0U, "reload pair");
    run(3000U);
    g_w.up[0] = 0U;
    g_w.up[1] = 0U;
    (void)do_connect(0U, 1U);
    run(200U);
    check(((g_w.up[0] == 1U) && (g_w.up[1] == 1U)) ? 1U : 0U, "handshake after a restart");
    check(((g_w.suspect[0] + g_w.suspect[1]) == 0U) ? 1U : 0U, "restart: no alert");

    /* A store rolled back to an older copy is reported, not hidden. */
    setup(81U);
    (void)wfas3_pair_export(&g_dev[0], 0U, g_w.stored[2]);
    (void)do_connect(0U, 1U);
    run(3000U);
    (void)do_connect(0U, 1U);
    run(3000U);
    memcpy(rec, g_dev[0].entry_key, 32U);
    wfas3_wr_be64(&rec[32], 0U);
    (void)wfas3_device_init(&g_dev[0], seed, rec, 1U, US(g_w.now));
    (void)wfas3_pair_import(&g_dev[0], 0U, g_w.stored[2]);
    g_w.suspect[0] = 0U;
    (void)do_connect(0U, 1U);
    run(300U);
    check((g_w.suspect[0] == 1U) ? 1U : 0U, "rolled-back store: the alert is raised");
}

static void test_strangers(void)
{
    static uint8_t d[1368];
    wfas3_addr fa = addr_of(5U);
    uint32_t i;
    uint32_t all = 1U;

    setup(90U);
    for (i = 0U; i < 200U; i++) {
        size_t len = ((i % 3U) == 0U) ? 64U : (((i % 3U) == 1U) ? 1368U : 160U);

        fill_rnd(d, len);
        d[0] = WFAS3_MARKER;
        d[1] = ((i % 3U) == 0U) ? WFAS3_T_COOKIE_REQUEST
                                : (((i % 3U) == 1U) ? WFAS3_T_INIT : WFAS3_T_TRANSPORT);
        d[2] = 0U;
        d[3] = 0U;
        all &= (wfas3_receive(&g_dev[1], d, len, &fa, &g_out) == WFAS3_E_DROP) ? 1U : 0U;
        all &= (g_out.len == 0U) ? 1U : 0U;
    }
    check(all, "random datagrams from a stranger: dropped, never answered");
    check((g_dev[1].tag_count == 0U) ? 1U : 0U, "a stranger never reaches the pair tag check");
}

int main(void)
{
    check((wfas3_selftest() == WFAS3_OK) ? 1U : 0U, "self-test");
    test_clean_handshakes();
    test_replay_and_stale();
    test_forgeries();
    test_entropy_and_limits();
    test_late_init();
    test_crossing();
    test_hostile_network();
    test_wrong_psk_and_reports();
    test_thief();
    test_stale_report();
    test_counters();
    test_unconfirmed();
    test_randomness();
    test_refusals();
    test_transport_and_restart();
    test_strangers();
    (void)printf("%u checks, %u failed\n", (unsigned)g_run, (unsigned)g_fail);
    return (g_fail == 0U) ? 0 : 1;
}
