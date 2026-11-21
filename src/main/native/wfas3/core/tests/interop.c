/* -----------------------------------------------------------------------------
 * WFAS v3 - interoperability driver. Test code only.
 *
 * Runs one device of the C core behind a line protocol on stdin and stdout,
 * so that tests/interop.py can make it talk to the second implementation in
 * tests/peer.py. Uses only the public interface of wfas.h. All values are
 * decimal, byte strings are hexadecimal, '-' is an empty byte string. Times
 * are microseconds, as in the core; control packets come from poll.
 *
 *   init <seed> <device record or ->     -> ok <device record>
 *   entropy <32 bytes>                   -> ok
 *   time <us>                            -> ok
 *   import <slot> <pair record>          -> ok
 *   export <slot>                        -> ok <pair record>
 *   devrec                               -> ok <device record>
 *   policy <busy> <codec mask> <max ch>  -> ok
 *   connect <slot> <ip> <port> <flags> <codec> <ch> <bits> <rate> <frames>
 *           <bytes> <latency>            -> out ...
 *   recv <ip> <port> <datagram>          -> out ...
 *   poll                                 -> out ...
 *   find <pair> <on>                     -> out ...
 *   close <session> <reason>             -> out ...
 *   remove <pair>                        -> out ...
 * Pairing (section 13):
 *   window <kind> <ask> <perms> <name>   -> out ... (then "code" gives the code)
 *   code                                 -> ok <code or PIN of the last window>
 *   wclose                               -> out ...
 *   wfailed                              -> ok <count>
 *   answer <ask> <yes> <perms> <guest>   -> out ...
 *   beacons <on>                         -> ok
 *   pair <kind> <code or PIN> <flags> <perms> <replace> <name> <beacon ip>
 *        <beacon port> <beacon nonce>    -> out ...
 *   confirm <yes>                        -> out ...
 *   label                                -> ok <label record> (created, loaded)
 *   labelcode                            -> ok <code>
 *   codetext <code> <qr>                 -> ok <text>
 *   codeparse <text>                     -> ok <code>
 * Multicast (section 14):
 *   gopen <flags> <codec> <ch> <bits> <rate> <frames> <bytes> <latency>
 *         <ip> <port>                    -> ok
 *   gclose                               -> ok
 *   gsend <sample_pos> <capture_us> <data> -> out ...
 *   gmembers                             -> ok <count>
 *   want <session> <on>                  -> ok
 *
 * out <status> <events> <pair> <session> <answer status> <ip> <port>
 *     <datagram> <flags> <codec> <ch> <bits> <rate> <frames> <bytes> <latency>
 *     <broadcast> <found ip> <found port> <code> <ask> <pflags> <name>
 *     <beacon ip> <beacon port> <beacon used> <beacon nonce> <beacon name>
 * Any error in the command itself: err <reason>.
 *
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Marco Morosi
 * ------------------------------------------------------------------------- */
#include "../wfas.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static wfas3_device g_dev;
static wfas3_out g_out;
static char g_line[16384];
static uint8_t g_buf[4096];
static uint8_t g_rec[WFAS3_PAIR_RECORD_LEN];
static uint8_t g_code[WFAS3_CODE_LEN];
static size_t g_code_len;

static int hexval(char c)
{
    int v = -1;

    if ((c >= '0') && (c <= '9')) {
        v = c - '0';
    } else if ((c >= 'a') && (c <= 'f')) {
        v = (c - 'a') + 10;
    } else if ((c >= 'A') && (c <= 'F')) {
        v = (c - 'A') + 10;
    }
    return v;
}

/* Decode hex into out. Returns the byte count, or -1 on any error. */
static long unhex(const char *s, uint8_t *out, size_t cap)
{
    size_t n;
    size_t i;

    if ((s == NULL) || (strcmp(s, "-") == 0)) {
        return 0;
    }
    n = strlen(s);
    if (((n % 2U) != 0U) || ((n / 2U) > cap)) {
        return -1;
    }
    for (i = 0U; i < (n / 2U); i++) {
        int hi = hexval(s[2U * i]);
        int lo = hexval(s[(2U * i) + 1U]);

        if ((hi < 0) || (lo < 0)) {
            return -1;
        }
        out[i] = (uint8_t)((hi << 4) | lo);
    }
    return (long)(n / 2U);
}

static void put_hex(const uint8_t *p, size_t n)
{
    size_t i;

    if (n == 0U) {
        (void)putchar('-');
    }
    for (i = 0U; i < n; i++) {
        (void)printf("%02x", (unsigned)p[i]);
    }
}

static int get_u64(const char *s, unsigned long long *v)
{
    char *end = NULL;

    if (s == NULL) {
        return -1;
    }
    *v = strtoull(s, &end, 10);
    return ((end != s) && (*end == '\0')) ? 0 : -1;
}

static int get_addr(const char *ip, const char *port, wfas3_addr *a)
{
    unsigned long long p = 0U;

    memset(a, 0, sizeof *a);
    if ((unhex(ip, a->ip, sizeof a->ip) != 16) || (get_u64(port, &p) != 0) || (p > 65535U)) {
        return -1;
    }
    a->port = (uint16_t)p;
    return 0;
}

static void print_out(wfas3_status st)
{
    const wfas3_config *c = &g_out.config;

    (void)printf("out %d %u %u %u %u ", (int)st, (unsigned)g_out.events, (unsigned)g_out.pair,
                 (unsigned)g_out.session, (unsigned)g_out.status);
    put_hex(g_out.to.ip, sizeof g_out.to.ip);
    (void)printf(" %u ", (unsigned)g_out.to.port);
    put_hex(g_out.dgram, g_out.len);
    (void)printf(" %u %u %u %u %lu %u %u %u %u ", (unsigned)c->flags, (unsigned)c->codec,
                 (unsigned)c->channels, (unsigned)c->bits, (unsigned long)c->sample_rate,
                 (unsigned)c->frame_samples, (unsigned)c->payload_bytes, (unsigned)c->latency_ms,
                 (unsigned)g_out.broadcast);
    put_hex(g_out.addr.ip, sizeof g_out.addr.ip);
    (void)printf(" %u %u %u %u ", (unsigned)g_out.addr.port, (unsigned)g_out.code,
                 (unsigned)g_out.ask, (unsigned)g_out.pflags);
    put_hex(g_out.name, sizeof g_out.name);
    (void)putchar(' ');
    put_hex(g_out.beacon.addr.ip, sizeof g_out.beacon.addr.ip);
    (void)printf(" %u %u ", (unsigned)g_out.beacon.addr.port, (unsigned)g_out.beacon.used);
    put_hex(g_out.beacon.nonce, sizeof g_out.beacon.nonce);
    (void)putchar(' ');
    put_hex(g_out.beacon.name, sizeof g_out.beacon.name);
    (void)putchar('\n');
}

static void ok_hex(const uint8_t *p, size_t n);

/* name: hex, at most 32 bytes, zero padded. */
static int get_name(const char *s, uint8_t *name)
{
    memset(name, 0, 32U);
    return (unhex(s, name, 32U) < 0) ? -1 : 0;
}

static void cmd_window(char **a)
{
    unsigned long long v[3];
    wfas3_window_req r;
    int i;

    memset(&r, 0, sizeof r);
    for (i = 0; i < 3; i++) {
        if (get_u64(a[i], &v[i]) != 0) {
            (void)printf("err number\n");
            return;
        }
    }
    if (get_name(a[3], r.name) != 0) {
        (void)printf("err name\n");
        return;
    }
    r.kind = (uint32_t)v[0];
    r.ask = (uint32_t)v[1];
    r.perms = (uint32_t)v[2];
    memset(&g_out, 0, sizeof g_out);
    memset(g_code, 0, sizeof g_code);
    g_code_len = (r.kind == WFAS3_PAIR_PIN) ? WFAS3_PIN_LEN : WFAS3_CODE_LEN;
    print_out(wfas3_window_open(&g_dev, &r, g_code, &g_out));
}

static void cmd_pair(char **a)
{
    unsigned long long v[4];
    wfas3_pair_req r;
    long len;
    int i;

    memset(&r, 0, sizeof r);
    for (i = 0; i < 4; i++) {
        if (get_u64(a[(i == 0) ? 0 : (i + 1)], &v[i]) != 0) {
            (void)printf("err number\n");
            return;
        }
    }
    r.kind = (uint32_t)v[0];
    r.flags = (uint32_t)v[1];
    r.perms = (uint32_t)v[2];
    r.replace = (uint32_t)v[3];
    len = (r.kind == WFAS3_PAIR_PIN) ? unhex(a[1], r.pin, sizeof r.pin) : unhex(a[1], r.code, sizeof r.code);
    if ((len < 0) || (get_name(a[5], r.name) != 0) ||
        (get_addr(a[6], a[7], &r.beacon.addr) != 0) ||
        (unhex(a[8], r.beacon.nonce, sizeof r.beacon.nonce) < 0)) {
        (void)printf("err pair\n");
        return;
    }
    memset(&g_out, 0, sizeof g_out);
    print_out(wfas3_pair_start(&g_dev, &r, &g_out));
}

static void cmd_gopen(char **a)
{
    unsigned long long v[8];
    wfas3_config c;
    wfas3_addr g;
    int i;

    for (i = 0; i < 8; i++) {
        if (get_u64(a[i], &v[i]) != 0) {
            (void)printf("err number\n");
            return;
        }
    }
    if (get_addr(a[8], a[9], &g) != 0) {
        (void)printf("err address\n");
        return;
    }
    c.flags = (uint8_t)v[0];
    c.codec = (uint8_t)v[1];
    c.channels = (uint8_t)v[2];
    c.bits = (uint8_t)v[3];
    c.sample_rate = (uint32_t)v[4];
    c.frame_samples = (uint16_t)v[5];
    c.payload_bytes = (uint16_t)v[6];
    c.latency_ms = (uint16_t)v[7];
    (void)printf((wfas3_group_open(&g_dev, &c, &g) == WFAS3_OK) ? "ok\n" : "err gopen\n");
}

/* Pairing commands; 1 if a was one. */
static int dispatch_pair(char **a, int n)
{
    unsigned long long v[4];
    uint8_t label[WFAS3_LABEL_RECORD_LEN];
    char text[WFAS3_CODE_TEXT_LEN];
    int done = 1;
    int i;

    for (i = 0; i < 4; i++) {
        v[i] = 0U;
        if ((i + 1) < n) {
            (void)get_u64(a[i + 1], &v[i]);
        }
    }
    memset(&g_out, 0, sizeof g_out);
    if ((n >= 11) && (strcmp(a[0], "gopen") == 0)) {
        cmd_gopen(&a[1]);
    } else if (strcmp(a[0], "gclose") == 0) {
        (void)printf((wfas3_group_close(&g_dev) == WFAS3_OK) ? "ok\n" : "err gclose\n");
    } else if ((n >= 4) && (strcmp(a[0], "gsend") == 0)) {
        long len = unhex(a[3], g_buf, sizeof g_buf);

        if (len < 0) {
            (void)printf("err gsend\n");
        } else {
            print_out(wfas3_group_send(&g_dev, (uint64_t)v[0], (uint64_t)v[1], g_buf, (size_t)len, &g_out));
        }
    } else if (strcmp(a[0], "gmembers") == 0) {
        (void)printf("ok %u\n", (unsigned)wfas3_group_members(&g_dev));
    } else if ((n >= 3) && (strcmp(a[0], "want") == 0)) {
        (void)printf((wfas3_stream_want(&g_dev, (uint32_t)v[0], (uint32_t)v[1]) == WFAS3_OK) ? "ok\n" : "err want\n");
    } else if ((n >= 5) && (strcmp(a[0], "window") == 0)) {
        cmd_window(&a[1]);
    } else if (strcmp(a[0], "code") == 0) {
        ok_hex(g_code, g_code_len);
    } else if (strcmp(a[0], "wclose") == 0) {
        print_out(wfas3_window_close(&g_dev, &g_out));
    } else if (strcmp(a[0], "wfailed") == 0) {
        (void)printf("ok %u\n", (unsigned)wfas3_window_failed(&g_dev));
    } else if ((n >= 5) && (strcmp(a[0], "answer") == 0)) {
        print_out(wfas3_pair_answer(&g_dev, (uint32_t)v[0], (uint32_t)v[1], (uint32_t)v[2],
                                    (uint32_t)v[3], &g_out));
    } else if ((n >= 2) && (strcmp(a[0], "beacons") == 0)) {
        (void)printf((wfas3_beacons(&g_dev, (uint32_t)v[0]) == WFAS3_OK) ? "ok\n" : "err beacons\n");
    } else if ((n >= 10) && (strcmp(a[0], "pair") == 0)) {
        cmd_pair(&a[1]);
    } else if ((n >= 2) && (strcmp(a[0], "confirm") == 0)) {
        print_out(wfas3_pair_confirm(&g_dev, (uint32_t)v[0], &g_out));
    } else if ((n >= 3) && (strcmp(a[0], "close") == 0)) {
        print_out(wfas3_session_close(&g_dev, (uint32_t)v[0], (uint32_t)v[1], &g_out));
    } else if ((n >= 2) && (strcmp(a[0], "remove") == 0)) {
        print_out(wfas3_pair_remove(&g_dev, (uint32_t)v[0], &g_out));
    } else if (strcmp(a[0], "label") == 0) {
        if ((wfas3_label_create(&g_dev, label) == WFAS3_OK) && (wfas3_label_load(&g_dev, label) == WFAS3_OK)) {
            ok_hex(label, sizeof label);
        } else {
            (void)printf("err label\n");
        }
    } else if (strcmp(a[0], "labelcode") == 0) {
        if (wfas3_label_code(&g_dev, g_code) == WFAS3_OK) {
            ok_hex(g_code, WFAS3_CODE_LEN);
        } else {
            (void)printf("err label\n");
        }
    } else if ((n >= 3) && (strcmp(a[0], "codetext") == 0)) {
        if ((unhex(a[1], g_code, sizeof g_code) == (long)sizeof g_code) &&
            (wfas3_code_format(g_code, (uint32_t)v[1], text, sizeof text) == WFAS3_OK)) {
            (void)printf("ok %s\n", text);
        } else {
            (void)printf("err codetext\n");
        }
    } else if ((n >= 2) && (strcmp(a[0], "codeparse") == 0)) {
        if (wfas3_code_parse(a[1], strlen(a[1]), g_code) == WFAS3_OK) {
            ok_hex(g_code, WFAS3_CODE_LEN);
        } else {
            (void)printf("err codeparse\n");
        }
    } else {
        done = 0;
    }
    return done;
}

static void ok_hex(const uint8_t *p, size_t n)
{
    (void)printf("ok ");
    put_hex(p, n);
    (void)printf("\n");
}

static void cmd_connect(char **a)
{
    unsigned long long v[11];
    wfas3_addr to;
    wfas3_config c;
    int i;

    for (i = 0; i < 11; i++) {
        if ((i != 1) && (i != 2) && (get_u64(a[i], &v[i]) != 0)) {
            (void)printf("err number\n");
            return;
        }
    }
    if (get_addr(a[1], a[2], &to) != 0) {
        (void)printf("err address\n");
        return;
    }
    c.flags = (uint8_t)v[3];
    c.codec = (uint8_t)v[4];
    c.channels = (uint8_t)v[5];
    c.bits = (uint8_t)v[6];
    c.sample_rate = (uint32_t)v[7];
    c.frame_samples = (uint16_t)v[8];
    c.payload_bytes = (uint16_t)v[9];
    c.latency_ms = (uint16_t)v[10];
    memset(&g_out, 0, sizeof g_out);
    print_out(wfas3_connect(&g_dev, (uint32_t)v[0], &to, &c, &g_out));
}

static void dispatch(char **a, int n)
{
    unsigned long long v = 0U;
    unsigned long long w = 0U;
    unsigned long long x = 0U;
    wfas3_addr from;
    wfas3_policy pol;
    long len;

    if ((n >= 3) && (strcmp(a[0], "init") == 0)) {
        uint8_t seed[WFAS3_SEED_LEN];
        uint8_t rec[WFAS3_DEVICE_RECORD_LEN];
        long rl = unhex(a[2], rec, sizeof rec);

        if ((unhex(a[1], seed, sizeof seed) != (long)sizeof seed) ||
            ((rl != 0) && (rl != (long)sizeof rec))) {
            (void)printf("err init\n");
        } else if (wfas3_selftest() != WFAS3_OK) {
            (void)printf("err selftest\n");
        } else if (wfas3_device_init(&g_dev, seed, rec, (rl == 0) ? 0U : 1U, 1000000U) != WFAS3_OK) {
            (void)printf("err device\n");
        } else {
            ok_hex(rec, sizeof rec);
        }
    } else if ((n >= 2) && (strcmp(a[0], "entropy") == 0)) {
        uint8_t e[WFAS3_ENTROPY_LEN];

        if ((unhex(a[1], e, sizeof e) != (long)sizeof e) ||
            (wfas3_add_entropy(&g_dev, e) != WFAS3_OK)) {
            (void)printf("err entropy\n");
        } else {
            (void)printf("ok\n");
        }
    } else if ((n >= 2) && (strcmp(a[0], "time") == 0) && (get_u64(a[1], &v) == 0)) {
        (void)printf((wfas3_set_time(&g_dev, (uint64_t)v) == WFAS3_OK) ? "ok\n" : "err time\n");
    } else if ((n >= 3) && (strcmp(a[0], "import") == 0) && (get_u64(a[1], &v) == 0)) {
        if ((unhex(a[2], g_rec, sizeof g_rec) != (long)sizeof g_rec) ||
            (wfas3_pair_import(&g_dev, (uint32_t)v, g_rec) != WFAS3_OK)) {
            (void)printf("err import\n");
        } else {
            (void)printf("ok\n");
        }
    } else if ((n >= 2) && (strcmp(a[0], "export") == 0) && (get_u64(a[1], &v) == 0)) {
        if (wfas3_pair_export(&g_dev, (uint32_t)v, g_rec) != WFAS3_OK) {
            (void)printf("err export\n");
        } else {
            ok_hex(g_rec, sizeof g_rec);
        }
    } else if ((n >= 1) && (strcmp(a[0], "devrec") == 0)) {
        uint8_t rec[WFAS3_DEVICE_RECORD_LEN];

        if (wfas3_device_export(&g_dev, rec) != WFAS3_OK) {
            (void)printf("err devrec\n");
        } else {
            ok_hex(rec, sizeof rec);
        }
    } else if ((n >= 4) && (strcmp(a[0], "policy") == 0) && (get_u64(a[1], &v) == 0) &&
               (get_u64(a[2], &w) == 0) && (get_u64(a[3], &x) == 0)) {
        pol = g_dev.policy;
        pol.busy = (uint32_t)v;
        pol.codec_mask = (uint32_t)w;
        pol.max_channels = (uint32_t)x;
        (void)printf((wfas3_set_policy(&g_dev, &pol) == WFAS3_OK) ? "ok\n" : "err policy\n");
    } else if ((n >= 12) && (strcmp(a[0], "connect") == 0)) {
        cmd_connect(&a[1]);
    } else if ((n >= 4) && (strcmp(a[0], "recv") == 0)) {
        len = unhex(a[3], g_buf, sizeof g_buf);
        if ((get_addr(a[1], a[2], &from) != 0) || (len < 0)) {
            (void)printf("err recv\n");
        } else {
            memset(&g_out, 0, sizeof g_out);
            print_out(wfas3_receive(&g_dev, g_buf, (size_t)len, &from, &g_out));
        }
    } else if ((n >= 1) && (strcmp(a[0], "poll") == 0)) {
        memset(&g_out, 0, sizeof g_out);
        print_out(wfas3_poll(&g_dev, &g_out));
    } else if ((n >= 3) && (strcmp(a[0], "find") == 0) && (get_u64(a[1], &v) == 0) &&
               (get_u64(a[2], &w) == 0)) {
        memset(&g_out, 0, sizeof g_out);
        print_out(wfas3_find(&g_dev, (uint32_t)v, (uint32_t)w, &g_out));
    } else if (dispatch_pair(a, n) == 0) {
        (void)printf("err command\n");
    } else {
        /* a pairing command */
    }
}

int main(void)
{
    char *a[16];
    int n;

    while (fgets(g_line, (int)sizeof g_line, stdin) != NULL) {
        char *tok = strtok(g_line, " \r\n");

        n = 0;
        while ((tok != NULL) && (n < 16)) {
            a[n] = tok;
            n++;
            tok = strtok(NULL, " \r\n");
        }
        if ((n == 1) && (strcmp(a[0], "quit") == 0)) {
            break;
        }
        if (n > 0) {
            dispatch(a, n);
        }
        (void)fflush(stdout);
    }
    return 0;
}
