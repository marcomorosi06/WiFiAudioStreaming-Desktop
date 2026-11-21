/* -----------------------------------------------------------------------------
 * WFAS v3 - tests of multicast (specification 14).
 *
 * A source (device 0) and three receivers in one process, joined by a
 * simulated network that delivers multicast datagrams to the devices whose
 * application joined the group address (on WFAS3_EV_GROUP_JOIN, left on
 * WFAS3_EV_GROUP_LEAVE), with loss, duplication and delay per receiver.
 * Includes wfas.c directly so that the state can be inspected.
 *
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Marco Morosi
 * ------------------------------------------------------------------------- */
#include "../wfas.c"

#include <stdio.h>

#define NDEV   4U
#define NQ     2048U
#define US(ms) ((uint64_t)(ms) * 1000U)
#define PB     960U

typedef struct packet {
    uint32_t used;
    uint32_t to;
    uint32_t from;
    size_t   len;
    uint64_t due;
    uint8_t  data[WFAS3_MAX_DATAGRAM];
} packet;

typedef struct who_st {
    uint32_t   joined;          /* the application listens to the group      */
    wfas3_addr group;
    uint32_t   joins;
    uint32_t   leaves;
    uint32_t   up;
    uint32_t   up_session;
    uint32_t   down;
    uint32_t   refused;
    wfas3_config refused_cfg;
    uint32_t   audio;
    uint32_t   audio_bad;
    uint32_t   dup;
    uint64_t   last_pos;
    uint8_t    seen[4096];       /* sample positions delivered, by packet index */
    uint32_t   mc_drop_pct;      /* loss of group datagrams to this device    */
    uint32_t   cut;              /* nothing reaches or leaves this device     */
} who_st;

typedef struct world {
    uint64_t now;
    uint64_t rng;
    uint32_t delay_ms;
    uint32_t sending;
    uint64_t next_pos;
    uint32_t sent;
    uint32_t mc_bytes;
    who_st   w[NDEV];
} world;

static wfas3_device g_dev[NDEV];
static packet g_q[NQ];
static wfas3_out g_out;
static world g_w;
static uint32_t g_run;
static uint32_t g_fail;
static uint8_t g_rec[NDEV][WFAS3_DEVICE_RECORD_LEN];

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

static wfas3_addr group_addr(void)
{
    wfas3_addr a;

    memset(&a, 0, sizeof a);
    a.ip[10] = 0xFFU;
    a.ip[11] = 0xFFU;
    a.ip[12] = 239U;
    a.ip[13] = 1U;
    a.ip[14] = 2U;
    a.ip[15] = 3U;
    a.port = 14115U;
    return a;
}

/* The group configuration, seen from the source when source is 1. */
static wfas3_config cfg_group(uint32_t source)
{
    wfas3_config c;

    c.flags = (uint8_t)(WFAS3_CF_MULTICAST | ((source == 1U) ? WFAS3_CF_SOURCE : 0U));
    c.codec = WFAS3_CODEC_PCM;
    c.channels = 2U;
    c.bits = 16U;
    c.sample_rate = 48000U;
    c.frame_samples = 240U;
    c.payload_bytes = PB;
    c.latency_ms = 200U;
    return c;
}

/* -- Network ----------------------------------------------------------------- */

static void enqueue(uint32_t to, uint32_t from, const uint8_t *d, size_t len, uint32_t mc)
{
    uint32_t copies = 1U;
    uint32_t i;
    uint32_t k;

    if ((g_w.w[to].cut == 1U) || (g_w.w[from].cut == 1U)) {
        copies = 0U;
    } else if ((mc == 1U) && ((rnd32() % 100U) < g_w.w[to].mc_drop_pct)) {
        copies = 0U;
    } else {
        copies = 1U;
    }
    for (k = 0U; k < copies; k++) {
        uint32_t done = 0U;

        for (i = 0U; (i < NQ) && (done == 0U); i++) {
            if (g_q[i].used == 0U) {
                g_q[i].used = 1U;
                g_q[i].to = to;
                g_q[i].from = from;
                g_q[i].len = len;
                g_q[i].due = g_w.now + 1U + ((g_w.delay_ms == 0U) ? 0U : (rnd32() % g_w.delay_ms));
                memcpy(g_q[i].data, d, len);
                done = 1U;
            }
        }
        check(done, "network queue has room");
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

    if (st == WFAS3_E_ENTROPY) {
        add_entropy(to);
        st = wfas3_receive(&g_dev[to], d, len, &fa, &g_out);
    }
    if (st == WFAS3_OK) {
        handle_out(to);
    }
}

static void handle_out(uint32_t who)
{
    who_st *w = &g_w.w[who];
    uint32_t ev = g_out.events;
    uint32_t d;

    if ((ev & WFAS3_EV_GROUP_JOIN) != 0U) {
        w->joins += 1U;
        w->joined = 1U;
        w->group = g_out.addr;
    }
    if ((ev & WFAS3_EV_GROUP_LEAVE) != 0U) {
        w->leaves += 1U;
        w->joined = 0U;
    }
    if ((ev & WFAS3_EV_SESSION_UP) != 0U) {
        w->up += 1U;
        w->up_session = g_out.session;
    }
    if ((ev & WFAS3_EV_SESSION_DOWN) != 0U) {
        w->down += 1U;
        if (who != 0U) {
            w->joined = 0U;                              /* the application leaves the group */
        }
    }
    if ((ev & WFAS3_EV_REFUSED) != 0U) {
        w->refused += 1U;
        w->refused_cfg = g_out.config;
    }
    if ((ev & WFAS3_EV_AUDIO) != 0U) {
        uint64_t idx = g_out.sample_pos / 240U;

        w->audio += 1U;
        if ((g_out.audio_len != PB) || (g_out.audio[0] != (uint8_t)(g_out.sample_pos & 0xFFU)) ||
            (g_out.stream != WFAS3_STREAM_MAIN)) {
            w->audio_bad += 1U;
        }
        if (idx < sizeof w->seen) {
            w->dup += (w->seen[idx] != 0U) ? 1U : 0U;
            w->seen[idx] = 1U;
        }
        w->last_pos = g_out.sample_pos;
    }
    if (g_out.len > 0U) {
        wfas3_addr ga = group_addr();

        if (wfas3_addr_eq(&g_out.to, &ga) == 1U) {
            g_w.sent += 1U;
            for (d = 0U; d < NDEV; d++) {
                if ((d != who) && (g_w.w[d].joined == 1U)) {
                    enqueue(d, who, g_out.dgram, g_out.len, 1U);
                }
            }
        } else {
            uint32_t to = (uint32_t)g_out.to.ip[15] - 10U;

            if (to < NDEV) {
                enqueue(to, who, g_out.dgram, g_out.len, 0U);
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

/* The source's application: one packet every 5 ms while it is sending. */
static void source_tick(void)
{
    uint8_t data[PB];
    wfas3_status st;

    if ((g_w.sending == 1U) && ((g_w.now % 5U) == 0U)) {
        memset(data, (int)(g_w.next_pos & 0xFFU), sizeof data);
        st = wfas3_group_send(&g_dev[0], g_w.next_pos, US(g_w.now), data, sizeof data, &g_out);
        if (st == WFAS3_OK) {
            handle_out(0U);
        }
        g_w.next_pos += 240U;
    }
}

static void run(uint32_t ms)
{
    uint64_t end = g_w.now + ms;
    uint32_t i;
    uint32_t d;

    while (g_w.now < end) {
        g_w.now += 1U;
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
        source_tick();
        if ((g_w.now % 5U) == 0U) {
            for (d = 0U; d < NDEV; d++) {
                poll_all(d);
            }
        }
    }
}

static void make_record(uint8_t *r, const uint8_t *own_priv, const uint8_t *peer_pub,
                        const uint8_t *k_fix, const uint8_t *psk0, const uint8_t *fp0,
                        const uint8_t *peer_entry, const uint8_t *own_entry)
{
    memset(r, 0, WFAS3_PAIR_RECORD_LEN);
    r[0] = 1U;
    r[2] = WFAS3_PERM_RECEIVE;
    memcpy(&r[4], own_priv, 32U);
    memcpy(&r[36], peer_pub, 32U);
    memcpy(&r[68], k_fix, 32U);
    memcpy(&r[100], psk0, 32U);
    memcpy(&r[196], peer_entry, 32U);
    memcpy(&r[228], own_entry, 32U);
    memcpy(&r[284], fp0, 16U);
    memcpy(&r[308], fp0, 16U);
}

/* Source 0 paired with each receiver: slot r on the source, slot 0 on r. */
static void setup(uint64_t seed)
{
    uint8_t s[WFAS3_SEED_LEN];
    uint8_t rec[WFAS3_PAIR_RECORD_LEN];
    uint8_t ps[32];
    uint8_t pr[32];
    uint8_t pub_s[32];
    uint8_t pub_r[32];
    uint8_t k_fix[32];
    uint8_t psk0[32];
    uint8_t fp0[16];
    uint32_t d;

    memset(&g_w, 0, sizeof g_w);
    memset(g_q, 0, sizeof g_q);
    g_w.rng = seed * 0x9E3779B97F4A7C15ULL + 3U;
    g_w.now = 1000U;
    for (d = 0U; d < NDEV; d++) {
        fill_rnd(s, sizeof s);
        check((wfas3_device_init(&g_dev[d], s, g_rec[d], 0U, US(g_w.now)) == WFAS3_OK) ? 1U : 0U,
              "device init");
    }
    for (d = 1U; d < NDEV; d++) {
        fill_rnd(ps, 32U);
        fill_rnd(pr, 32U);
        fill_rnd(k_fix, 32U);
        fill_rnd(psk0, 32U);
        fill_rnd(fp0, 16U);
        wfas_tp_x25519_public(pub_s, ps);
        wfas_tp_x25519_public(pub_r, pr);
        make_record(rec, ps, pub_r, k_fix, psk0, fp0, g_rec[d], g_rec[0]);
        check((wfas3_pair_import(&g_dev[0], d, rec) == WFAS3_OK) ? 1U : 0U, "source imports");
        make_record(rec, pr, pub_s, k_fix, psk0, fp0, g_rec[0], g_rec[d]);
        check((wfas3_pair_import(&g_dev[d], 0U, rec) == WFAS3_OK) ? 1U : 0U, "receiver imports");
    }
}

static wfas3_status open_group(void)
{
    wfas3_config c = cfg_group(1U);
    wfas3_addr a = group_addr();

    return wfas3_group_open(&g_dev[0], &c, &a);
}

/* Receiver r opens a session with the source and wants the stream. */
static uint32_t join(uint32_t r)
{
    wfas3_addr to = addr_of(0U);
    wfas3_config c = cfg_group(0U);
    uint32_t before = g_w.w[r].up;
    wfas3_status st = wfas3_connect(&g_dev[r], 0U, &to, &c, &g_out);

    if (st == WFAS3_OK) {
        handle_out(r);
    }
    run(300U);
    if (g_w.w[r].up > before) {
        (void)wfas3_stream_want(&g_dev[r], g_w.w[r].up_session, 1U);
    }
    run(500U);
    return ((g_w.w[r].up > before) && (g_w.w[r].joined == 1U)) ? 1U : 0U;
}

static uint32_t count_seen(uint32_t r, uint64_t from_pos, uint64_t to_pos)
{
    uint32_t n = 0U;
    uint64_t i;

    for (i = from_pos / 240U; (i < (to_pos / 240U)) && (i < 4096U); i++) {
        n += g_w.w[r].seen[i];
    }
    return n;
}

/* -- Tests ------------------------------------------------------------------- */

static void test_api(void)
{
    wfas3_config c = cfg_group(1U);
    wfas3_addr a = group_addr();
    wfas3_addr u = addr_of(5U);
    uint8_t data[PB];

    setup(1U);
    memset(data, 0, sizeof data);
    c.flags = WFAS3_CF_SOURCE;
    check((wfas3_group_open(&g_dev[0], &c, &a) == WFAS3_E_ARG) ? 1U : 0U, "api: bit2 required");
    c = cfg_group(1U);
    check((wfas3_group_open(&g_dev[0], &c, &u) == WFAS3_E_ARG) ? 1U : 0U, "api: a multicast address");
    c.frame_samples = 320U;
    c.payload_bytes = 1280U;
    check((wfas3_group_open(&g_dev[0], &c, &a) == WFAS3_E_ARG) ? 1U : 0U, "api: payload at most 1213");
    check((wfas3_group_send(&g_dev[0], 0U, 0U, data, PB, &g_out) == WFAS3_E_STATE) ? 1U : 0U,
          "api: no group, no send");
    check((open_group() == WFAS3_OK) ? 1U : 0U, "api: group opens");
    check((open_group() == WFAS3_E_STATE) ? 1U : 0U, "api: one group");
    check((wfas3_group_send(&g_dev[0], 0U, 0U, data, PB, &g_out) == WFAS3_E_STATE) ? 1U : 0U,
          "api: nobody to send to");
    check((wfas3_group_members(&g_dev[0]) == 0U) ? 1U : 0U, "api: no members");
    check((wfas3_group_close(&g_dev[0]) == WFAS3_OK) ? 1U : 0U, "api: group closes");
    check((wfas3_group_close(&g_dev[0]) == WFAS3_E_STATE) ? 1U : 0U, "api: no group to close");
    c = cfg_group(1U);
    check((wfas3_connect(&g_dev[0], 1U, &u, &c, &g_out) == WFAS3_E_STATE) ? 1U : 0U,
          "api: a source connects only within its group");
}

/* Two receivers on a clean network: every packet arrives once. */
static void test_basic(void)
{
    uint64_t p0;
    uint32_t sess;
    uint8_t data[PB];

    setup(2U);
    check((open_group() == WFAS3_OK) ? 1U : 0U, "basic: group opens");
    check(join(1U), "basic: receiver 1 joins");
    check(join(2U), "basic: receiver 2 joins");
    check((wfas3_group_members(&g_dev[0]) == 2U) ? 1U : 0U, "basic: two members");
    check((g_dev[1].sessions[g_w.w[1].up_session].mrx.slot != g_dev[2].sessions[g_w.w[2].up_session].mrx.slot)
          ? 1U : 0U, "basic: different slots");
    g_w.sending = 1U;
    p0 = g_w.next_pos;
    run(3000U);
    check((count_seen(1U, p0, g_w.next_pos - 2400U) == (uint32_t)((g_w.next_pos - 2400U - p0) / 240U)) ? 1U : 0U,
          "basic: receiver 1 gets every packet");
    check((count_seen(2U, p0, g_w.next_pos - 2400U) == (uint32_t)((g_w.next_pos - 2400U - p0) / 240U)) ? 1U : 0U,
          "basic: receiver 2 gets every packet");
    check((g_w.w[1].dup == 0U) && (g_w.w[2].dup == 0U) ? 1U : 0U, "basic: nothing twice");
    check((g_w.w[1].audio_bad == 0U) && (g_w.w[2].audio_bad == 0U) ? 1U : 0U, "basic: right content");
    check((g_w.w[3].audio == 0U) ? 1U : 0U, "basic: a device outside the group gets nothing");
    sess = 0U;
    while ((sess < WFAS3_MAX_SESSIONS) && (g_dev[0].sessions[sess].pair != 1U)) {
        sess++;
    }
    memset(data, 0, sizeof data);
    check((sess < WFAS3_MAX_SESSIONS) &&
          (wfas3_send_audio(&g_dev[0], sess, 0U, 0U, data, PB, &g_out) == WFAS3_E_STATE) ? 1U : 0U,
          "basic: no unicast audio in a group session");
    check((sess < WFAS3_MAX_SESSIONS) &&
          (wfas3_stream_params(&g_dev[0], sess, &(wfas3_config){ 0 }) != WFAS3_OK) ? 1U : 0U,
          "basic: the group configuration is fixed");
    check((g_w.mc_bytes == 0U) ? 1U : 0U, "basic: harness");
}

/* Loss on the group: NACK, the packet comes again in the session. */
static void test_loss(void)
{
    uint64_t p0;
    uint32_t want;
    uint32_t got1;
    uint32_t got2;

    setup(3U);
    g_w.delay_ms = 3U;
    (void)open_group();
    check(join(1U) & join(2U), "loss: both join");
    g_w.w[1].mc_drop_pct = 10U;
    g_w.sending = 1U;
    p0 = g_w.next_pos;
    run(4000U);
    want = (uint32_t)((g_w.next_pos - 4800U - p0) / 240U);
    got1 = count_seen(1U, p0, g_w.next_pos - 4800U);
    got2 = count_seen(2U, p0, g_w.next_pos - 4800U);
    check((got2 == want) ? 1U : 0U, "loss: the receiver without loss gets everything");
    check((got1 * 100U >= want * 99U) ? 1U : 0U, "loss: resends fill the gaps of the other");
    check((g_w.w[1].dup == 0U) && (g_w.w[1].audio_bad == 0U) ? 1U : 0U, "loss: never twice, never wrong");
    (void)printf("loss: %u of %u with 10%% group loss\n", got1, want);
}

/* A receiver leaves: a new epoch; it no longer opens the group. */
static void test_leave(void)
{
    uint32_t s2;
    uint32_t e0;
    uint32_t a2;
    uint32_t a1;

    setup(4U);
    (void)open_group();
    check(join(1U) & join(2U), "leave: both join");
    g_w.sending = 1U;
    run(1000U);
    e0 = g_dev[0].grp.epoch;
    s2 = g_w.w[2].up_session;
    check((wfas3_stream_want(&g_dev[2], s2, 0U) == WFAS3_OK) ? 1U : 0U, "leave: receiver 2 stops wanting");
    g_w.w[2].joined = 0U;                                /* its application leaves the address */
    g_w.w[2].joined = 1U;                                /* ... but keeps listening, to test */
    run(300U);
    check((wfas3_group_members(&g_dev[0]) == 1U) ? 1U : 0U, "leave: one member left");
    check((g_dev[0].grp.epoch > e0) || (g_dev[0].grp.pend == 1U) ? 1U : 0U, "leave: a new epoch");
    run(2500U);
    check((g_dev[0].grp.epoch > e0) && (g_dev[0].grp.pend == 0U) ? 1U : 0U, "leave: the new epoch is in use");
    a2 = g_w.w[2].audio;
    a1 = g_w.w[1].audio;
    run(1000U);
    check((g_w.w[2].audio == a2) ? 1U : 0U, "leave: the receiver that left gets nothing");
    check((g_w.w[1].audio > a1 + 150U) ? 1U : 0U, "leave: the other one goes on");
    check((g_dev[2].sessions[s2].mrx.joined == 0U) ? 1U : 0U, "leave: its keys are gone");
}

/* A removed pair is revoked: the key changes at once. */
static void test_revoke(void)
{
    uint32_t e0;
    uint32_t a1;

    setup(5U);
    (void)open_group();
    check(join(1U) & join(2U), "revoke: both join");
    g_w.sending = 1U;
    run(1000U);
    e0 = g_dev[0].grp.epoch;
    check((wfas3_pair_remove(&g_dev[0], 2U, &g_out) == WFAS3_OK) ? 1U : 0U, "revoke: pair removed");
    handle_out(0U);
    poll_all(0U);
    check((g_dev[0].grp.epoch > e0) && (g_dev[0].grp.pend == 0U) ? 1U : 0U,
          "revoke: a new epoch in use at once");
    a1 = g_w.w[1].audio;
    run(2000U);
    check((g_w.w[1].audio > a1 + 300U) ? 1U : 0U, "revoke: the remaining receiver goes on");
    check((wfas3_group_members(&g_dev[0]) == 1U) ? 1U : 0U, "revoke: one member");
}

/* No REPORT: the receiver loses its slot; then nobody is sent to. */
static void test_consent(void)
{
    uint8_t data[PB];

    setup(6U);
    (void)open_group();
    check(join(1U), "consent: join");
    g_w.sending = 1U;
    run(500U);
    g_w.w[1].cut = 1U;
    run(2600U);
    check((wfas3_group_members(&g_dev[0]) == 0U) ? 1U : 0U, "consent: the slot is lost");
    memset(data, 0, sizeof data);
    check((wfas3_group_send(&g_dev[0], 0U, US(g_w.now), data, PB, &g_out) == WFAS3_E_STATE) ? 1U : 0U,
          "consent: nothing is sent without consent");
    g_w.w[1].cut = 0U;
    run(1500U);
    check((wfas3_group_members(&g_dev[0]) == 1U) ? 1U : 0U, "consent: START again, member again");
}

/* Every 10 minutes a new epoch, without a gap. */
static void test_rotation(void)
{
    uint32_t e0;
    uint64_t p0;
    uint32_t want;

    setup(7U);
    (void)open_group();
    check(join(1U) & join(2U), "rotation: join");
    g_w.sending = 1U;
    run(500U);
    e0 = g_dev[0].grp.epoch;
    g_dev[0].grp.epoch_ms = g_dev[0].now_ms - 600000U;
    p0 = g_w.next_pos;
    run(3000U);
    check((g_dev[0].grp.epoch == e0 + 1U) ? 1U : 0U, "rotation: next epoch");
    want = (uint32_t)((g_w.next_pos - 2400U - p0) / 240U);
    check((count_seen(1U, p0, g_w.next_pos - 2400U) == want) &&
          (count_seen(2U, p0, g_w.next_pos - 2400U) == want) ? 1U : 0U, "rotation: nothing lost");
}

/* Forgeries: a member can listen but not forge for another; replays fail. */
static void test_forgery(void)
{
    uint8_t d[WFAS3_MAX_DATAGRAM];
    uint8_t pt[WFAS3_MAX_DATAGRAM];
    size_t len = PB + WFAS3_MCAST_OVERHEAD;
    wfas3_addr fa = addr_of(0U);
    const wfas3_mrx *x2;
    uint32_t a1;
    uint32_t a2;
    uint32_t s1;
    uint32_t s2;
    size_t body = len - 128U;
    uint8_t data[PB];

    setup(8U);
    (void)open_group();
    check(join(1U) & join(2U), "forgery: join");
    s1 = g_w.w[1].up_session;
    s2 = g_w.w[2].up_session;
    memset(data, 7, sizeof data);
    check((wfas3_group_send(&g_dev[0], 7U, US(g_w.now), data, PB, &g_out) == WFAS3_OK) ? 1U : 0U,
          "forgery: a packet");
    memcpy(d, g_out.dgram, len);
    a1 = g_w.w[1].audio;
    check((wfas3_receive(&g_dev[1], d, len, &fa, &g_out) == WFAS3_OK) ? 1U : 0U, "forgery: the real one");
    check((wfas3_receive(&g_dev[1], d, len, &fa, &g_out) == WFAS3_E_DROP) ? 1U : 0U, "forgery: its replay");
    d[30] ^= 1U;
    check((wfas3_receive(&g_dev[2], d, len, &fa, &g_out) == WFAS3_E_DROP) ? 1U : 0U, "forgery: a flipped bit");
    d[30] ^= 1U;
    /* Receiver 2 holds the group key: it makes a new packet with its own tag. */
    x2 = &g_dev[2].sessions[s2].mrx;
    check((wfas3_open_e(pt, sizeof pt, x2->key, x2->epoch, wfas3_rd_be64(&d[12]), d, 20U, &d[20], body - 20U)
           == WFAS3_OK) ? 1U : 0U, "forgery: a member opens the group packet");
    pt[30] ^= 0x55U;
    wfas3_wr_be64(&d[12], wfas3_rd_be64(&d[12]) + 1000U);
    (void)wfas3_seal_e(&d[20], sizeof d - 20U, x2->key, x2->epoch, wfas3_rd_be64(&d[12]), d, 20U,
                       pt, body - 20U - 16U);
    (void)wfas3_mac(&d[body + (8U * (size_t)x2->slot)], 8U, x2->tag_key, d, body);
    a2 = g_w.w[2].audio;
    check((wfas3_receive(&g_dev[1], d, len, &fa, &g_out) == WFAS3_E_DROP) ? 1U : 0U,
          "forgery: another member cannot forge for receiver 1");
    check((g_w.w[1].audio == a1) && (g_w.w[2].audio == a2) ? 1U : 0U, "forgery: nothing delivered");
    check((g_dev[1].sessions[s1].mrx.joined == 1U) ? 1U : 0U, "forgery: receiver 1 still joined");
}

/* The source refuses a proposal that is not its group, and suggests it. */
static void test_suggest(void)
{
    wfas3_addr to = addr_of(0U);
    wfas3_config c = cfg_group(0U);

    setup(9U);
    (void)open_group();
    c.latency_ms = 60U;
    if (wfas3_connect(&g_dev[1], 0U, &to, &c, &g_out) == WFAS3_OK) {
        handle_out(1U);
    }
    run(300U);
    check((g_w.w[1].refused == 1U) && (g_w.w[1].up == 0U) ? 1U : 0U, "suggest: refused");
    check((g_w.w[1].refused_cfg.latency_ms == 200U) &&
          ((g_w.w[1].refused_cfg.flags & WFAS3_CF_MULTICAST) != 0U) ? 1U : 0U,
          "suggest: the group configuration is suggested");
    c = g_w.w[1].refused_cfg;
    c.flags = (uint8_t)(c.flags & (uint8_t)~WFAS3_CF_SOURCE);
    run(2500U);                                          /* one handshake per pair every 2 s */
    if (wfas3_connect(&g_dev[1], 0U, &to, &c, &g_out) == WFAS3_OK) {
        handle_out(1U);
    }
    run(500U);
    check((g_w.w[1].up == 1U) ? 1U : 0U, "suggest: the suggestion is accepted");
}

/* The source opens the sessions; the group closes; a session ends. */
static void test_source_side(void)
{
    wfas3_config c = cfg_group(1U);
    wfas3_addr to = addr_of(1U);
    uint32_t sess;

    setup(10U);
    (void)open_group();
    check((wfas3_connect(&g_dev[0], 1U, &to, &c, &g_out) == WFAS3_OK) ? 1U : 0U, "source side: connect");
    handle_out(0U);
    run(300U);
    check((g_w.w[1].up == 1U) ? 1U : 0U, "source side: up");
    (void)wfas3_stream_want(&g_dev[1], g_w.w[1].up_session, 1U);
    g_w.sending = 1U;
    run(800U);
    check((g_w.w[1].joined == 1U) && (g_w.w[1].audio > 50U) ? 1U : 0U, "source side: audio by the group");
    check(join(2U), "source side: a second receiver opens itself");
    check((wfas3_group_close(&g_dev[0]) == WFAS3_OK) ? 1U : 0U, "source side: close");
    run(500U);
    check((g_w.w[1].leaves == 1U) && (g_w.w[2].leaves == 1U) ? 1U : 0U,
          "source side: every receiver is told to leave");
    (void)open_group();
    run(3000U);                                          /* START again 2 s after the last audio */
    sess = g_w.w[2].up_session;
    check((wfas3_group_members(&g_dev[0]) == 2U) ? 1U : 0U, "source side: a new group, members again");
    check((wfas3_session_close(&g_dev[2], sess, WFAS3_BYE_NORMAL, &g_out) == WFAS3_OK) ? 1U : 0U,
          "source side: receiver 2 closes its session");
    handle_out(2U);
    run(500U);
    check((wfas3_group_members(&g_dev[0]) == 1U) ? 1U : 0U, "source side: it is no member any more");
}

int main(void)
{
    test_api();
    test_basic();
    test_loss();
    test_leave();
    test_revoke();
    test_consent();
    test_rotation();
    test_forgery();
    test_suggest();
    test_source_side();
    (void)printf("%u checks, %u failed\n", g_run, g_fail);
    return (g_fail == 0U) ? 0 : 1;
}
