/* -----------------------------------------------------------------------------
 * wfascli - command-line client of the WFAS v3 core (POSIX, Linux).
 *
 * WFAS v3 is EXPERIMENTAL and a separate protocol from WFAS v2. This program
 * is a thin shell around wfas.h: it owns the sockets, the clock, the entropy
 * and the stored records, and does exactly what the core asks. Audio is raw
 * PCM, signed 16-bit little-endian, 2 channels, 48 kHz (Opus is not built
 * in).
 *
 * Storage: one directory (default $HOME/.wfas3), mode 0700, owned by the
 * user; every file in it is mode 0600, written to a new temporary file and
 * renamed, never through a symbolic link. A file is erased by overwriting
 * it before unlinking it, and only if it is a regular file of the user with
 * the expected size.
 *
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Marco Morosi
 * ------------------------------------------------------------------------- */
#define _GNU_SOURCE
#include "wfas.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/random.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define CLI_PB          960U      /* 240 samples, 2 channels, 16 bits: 5 ms */
#define CLI_FRAME_US    5000U
#define CLI_DIR         1024      /* longest storage directory, with NUL      */
#define CLI_PATH        1100

/* What a command is waiting for, set by on_out(). */
typedef struct cli_state {
    uint32_t paired;
    uint32_t paired_pair;
    uint32_t failed;
    uint32_t fail_why;
    uint32_t race;
    uint32_t closed;
    uint32_t ask_pending;
    uint32_t ask_id;
    uint32_t ask_flags;
    uint32_t confirm_pending;
    uint32_t found;
    uint32_t not_found;
    wfas3_addr found_addr;
    uint32_t up;
    uint32_t up_session;
    uint32_t down;
    uint32_t started;
    uint32_t refused;
    uint32_t beacon_ok;
    wfas3_beacon beacon;
    uint64_t audio_packets;
} cli_state;

typedef struct cli {
    char        dir[CLI_DIR];
    char        pairs_dir[CLI_DIR + 16];
    uint16_t    port;
    uint32_t    bcast_set;
    struct sockaddr_in bcast;
    int         s4;
    int         s6;
    int         g4;               /* multicast group socket of a receiver      */
    wfas3_addr  gaddr;
    uint32_t    yes;              /* answer yes to every question              */
    uint32_t    pin_mode;         /* the pairing in progress uses a PIN        */
    uint8_t     name[WFAS3_NAME_LEN];
    FILE       *audio_out;
    cli_state   st;
} cli;

static wfas3_device g_dev;        /* holds every secret: locked, wiped at exit */
static wfas3_out g_out;
static cli g;
static volatile sig_atomic_t g_stop;

/* -- Small helpers ----------------------------------------------------------- */

static void wipe(void *p, size_t n)
{
    volatile uint8_t *v = (volatile uint8_t *)p;
    size_t i;

    for (i = 0U; i < n; i++) {
        v[i] = 0U;
    }
}

static void finish(int code)
{
    wipe(&g_dev, sizeof g_dev);
    wipe(&g_out, sizeof g_out);
    if (g.audio_out != NULL) {
        (void)fclose(g.audio_out);
    }
    exit(code);
}

static void die(const char *msg)
{
    (void)fprintf(stderr, "wfascli: %s\n", msg);
    finish(1);
}

static void on_signal(int sig)
{
    (void)sig;
    g_stop = 1;
}

static uint64_t now_us(void)
{
    struct timespec ts;

    if (clock_gettime(CLOCK_BOOTTIME, &ts) != 0) {
        die("no monotonic clock");
    }
    return ((uint64_t)ts.tv_sec * 1000000U) + ((uint64_t)ts.tv_nsec / 1000U);
}

static void rand_bytes(uint8_t *p, size_t n)
{
    size_t got = 0U;

    while (got < n) {
        ssize_t r = getrandom(&p[got], n - got, 0);

        if (r < 0) {
            if (errno != EINTR) {
                die("getrandom failed");
            }
        } else {
            got += (size_t)r;
        }
    }
}

static void add_entropy(void)
{
    uint8_t e[WFAS3_ENTROPY_LEN];

    rand_bytes(e, sizeof e);
    if (wfas3_add_entropy(&g_dev, e) != WFAS3_OK) {
        die("entropy refused");
    }
    wipe(e, sizeof e);
}

static void set_time(void)
{
    (void)wfas3_set_time(&g_dev, now_us());
}

static const char *addr_text(const wfas3_addr *a, char *buf, size_t cap)
{
    char ip[INET6_ADDRSTRLEN];
    static const uint8_t v4m[12] = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFFU, 0xFFU };

    if (memcmp(a->ip, v4m, 12U) == 0) {
        (void)inet_ntop(AF_INET, &a->ip[12], ip, sizeof ip);
        (void)snprintf(buf, cap, "%s:%u", ip, (unsigned)a->port);
    } else {
        (void)inet_ntop(AF_INET6, a->ip, ip, sizeof ip);
        (void)snprintf(buf, cap, "[%s]:%u", ip, (unsigned)a->port);
    }
    return buf;
}

/* "a.b.c.d:port" or "[v6]:port" into a. 0 on success. */
static int addr_parse(const char *s, wfas3_addr *a)
{
    char host[INET6_ADDRSTRLEN + 2];
    const char *colon;
    unsigned long port;
    char *end = NULL;
    size_t hl;

    memset(a, 0, sizeof *a);
    colon = strrchr(s, ':');
    if ((colon == NULL) || (colon == s)) {
        return -1;
    }
    hl = (size_t)(colon - s);
    if ((s[0] == '[') && (hl >= 2U) && (s[hl - 1U] == ']')) {
        s++;
        hl -= 2U;
    }
    if (hl >= sizeof host) {
        return -1;
    }
    memcpy(host, s, hl);
    host[hl] = '\0';
    port = strtoul(colon + 1, &end, 10);
    if ((end == colon + 1) || (*end != '\0') || (port == 0U) || (port > 65535U)) {
        return -1;
    }
    a->port = (uint16_t)port;
    if (inet_pton(AF_INET, host, &a->ip[12]) == 1) {
        a->ip[10] = 0xFFU;
        a->ip[11] = 0xFFU;
        return 0;
    }
    return (inet_pton(AF_INET6, host, a->ip) == 1) ? 0 : -1;
}

static void name_set(const char *s)
{
    size_t n = strlen(s);

    memset(g.name, 0, sizeof g.name);
    memcpy(g.name, s, (n > sizeof g.name) ? sizeof g.name : n);
}

static void name_print(const char *what, const uint8_t *raw)
{
    char text[WFAS3_NAME_TEXT_LEN];

    wfas3_name_clean(raw, text);                         /* no control or direction characters */
    (void)printf("%s \"%s\"\n", what, text);
}

/* -- Storage ----------------------------------------------------------------- */

/* A directory of this user that nobody else can enter; created if missing. */
static void dir_secure(const char *path)
{
    struct stat sb;

    if ((mkdir(path, 0700) != 0) && (errno != EEXIST)) {
        die("cannot create the storage directory");
    }
    if (lstat(path, &sb) != 0) {
        die("cannot inspect the storage directory");
    }
    if (!S_ISDIR(sb.st_mode) || (sb.st_uid != geteuid()) || ((sb.st_mode & 077) != 0)) {
        (void)fprintf(stderr, "wfascli: %s must be a directory of this user with mode 0700\n", path);
        finish(1);
    }
}

/* 1 read, 0 missing, -1 unsafe or damaged. */
static int file_read(const char *path, uint8_t *buf, size_t len)
{
    struct stat sb;
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    int r = -1;
    size_t got = 0U;

    if (fd < 0) {
        return (errno == ENOENT) ? 0 : -1;
    }
    if ((fstat(fd, &sb) == 0) && S_ISREG(sb.st_mode) && (sb.st_uid == geteuid()) &&
        ((sb.st_mode & 077) == 0) && ((size_t)sb.st_size == len)) {
        while (got < len) {
            ssize_t n = read(fd, &buf[got], len - got);

            if (n <= 0) {
                if ((n < 0) && (errno == EINTR)) {
                    continue;
                }
                break;
            }
            got += (size_t)n;
        }
        r = (got == len) ? 1 : -1;
    }
    (void)close(fd);
    if (r != 1) {
        wipe(buf, len);
    }
    return r;
}

static void fsync_dir(const char *dir)
{
    int fd = open(dir, O_RDONLY | O_DIRECTORY | O_CLOEXEC);

    if (fd >= 0) {
        (void)fsync(fd);
        (void)close(fd);
    }
}

/* Overwrite and unlink path, if it is ours and len bytes long. 0 if gone. */
static int file_erase(const char *path, size_t len)
{
    struct stat sb;
    uint8_t zero[512];
    int fd = open(path, O_WRONLY | O_NOFOLLOW | O_CLOEXEC);
    size_t done = 0U;
    int ok = -1;

    if (fd < 0) {
        return (errno == ENOENT) ? 0 : -1;
    }
    memset(zero, 0, sizeof zero);
    if ((fstat(fd, &sb) == 0) && S_ISREG(sb.st_mode) && (sb.st_uid == geteuid()) &&
        ((size_t)sb.st_size == len)) {
        while (done < len) {
            size_t n = ((len - done) > sizeof zero) ? sizeof zero : (len - done);

            if (write(fd, zero, n) != (ssize_t)n) {
                break;
            }
            done += n;
        }
        ok = ((done == len) && (fsync(fd) == 0)) ? 0 : -1;
    }
    (void)close(fd);
    if (ok == 0) {
        ok = (unlink(path) == 0) ? 0 : -1;
    }
    return ok;
}

/* Write a new file name in dir: a temporary file, then rename. A leftover
 * temporary file is ours to erase only if it passes file_erase()'s checks. */
static void file_write(const char *dir, const char *name, const uint8_t *buf, size_t len)
{
    char tmp[CLI_PATH];
    char dst[CLI_PATH];
    int fd;
    size_t done = 0U;

    (void)snprintf(tmp, sizeof tmp, "%s/.%s.tmp", dir, name);
    (void)snprintf(dst, sizeof dst, "%s/%s", dir, name);
    fd = open(tmp, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if ((fd < 0) && (errno == EEXIST) && (file_erase(tmp, len) == 0)) {
        fd = open(tmp, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    }
    if (fd < 0) {
        die("cannot create a record file");
    }
    while (done < len) {
        ssize_t n = write(fd, &buf[done], len - done);

        if (n <= 0) {
            if ((n < 0) && (errno == EINTR)) {
                continue;
            }
            die("cannot write a record file");
        }
        done += (size_t)n;
    }
    if ((fsync(fd) != 0) || (close(fd) != 0) || (rename(tmp, dst) != 0)) {
        die("cannot store a record file");
    }
    fsync_dir(dir);
}

static void pair_path(uint32_t slot, char *buf, size_t cap)
{
    (void)snprintf(buf, cap, "%s/%02u.rec", g.pairs_dir, (unsigned)slot);
}

static void store_device(void)
{
    uint8_t rec[WFAS3_DEVICE_RECORD_LEN];

    if (wfas3_device_export(&g_dev, rec) != WFAS3_OK) {
        die("device record");
    }
    file_write(g.dir, "device.rec", rec, sizeof rec);
    wipe(rec, sizeof rec);
}

static void store_pair(uint32_t slot)
{
    uint8_t rec[WFAS3_PAIR_RECORD_LEN];
    char name[16];

    if (wfas3_pair_export(&g_dev, slot, rec) == WFAS3_OK) {
        (void)snprintf(name, sizeof name, "%02u.rec", (unsigned)slot);
        file_write(g.pairs_dir, name, rec, sizeof rec);
    }
    wipe(rec, sizeof rec);
}

static void erase_pair(uint32_t slot)
{
    char path[CLI_PATH];

    pair_path(slot, path, sizeof path);
    if (file_erase(path, WFAS3_PAIR_RECORD_LEN) != 0) {
        (void)fprintf(stderr, "wfascli: could not erase %s: remove it by hand\n", path);
    }
}

/* The device record (created on first use) and every stored pair. */
static void load_all(void)
{
    uint8_t seed[WFAS3_SEED_LEN];
    uint8_t rec[WFAS3_DEVICE_RECORD_LEN];
    uint8_t prec[WFAS3_PAIR_RECORD_LEN];
    uint8_t lrec[WFAS3_LABEL_RECORD_LEN];
    char path[CLI_PATH];
    uint32_t slot;
    int r;

    if (wfas3_selftest() != WFAS3_OK) {
        die("self-test failed: this device cannot run WFAS v3");
    }
    (void)snprintf(path, sizeof path, "%s/device.rec", g.dir);
    r = file_read(path, rec, sizeof rec);
    if (r < 0) {
        die("device.rec is unsafe or damaged");
    }
    rand_bytes(seed, sizeof seed);
    if (wfas3_device_init(&g_dev, seed, rec, (r == 1) ? 1U : 0U, now_us()) != WFAS3_OK) {
        die("device init");
    }
    file_write(g.dir, "device.rec", rec, sizeof rec);  /* before any other call */
    wipe(seed, sizeof seed);
    wipe(rec, sizeof rec);
    for (slot = 0U; slot < WFAS3_MAX_PAIRS; slot++) {
        pair_path(slot, path, sizeof path);
        r = file_read(path, prec, sizeof prec);
        if ((r == 1) && (wfas3_pair_import(&g_dev, slot, prec) != WFAS3_OK)) {
            (void)fprintf(stderr, "wfascli: pair record %02u refused\n", (unsigned)slot);
        } else if (r < 0) {
            (void)fprintf(stderr, "wfascli: pair record %02u is unsafe or damaged\n", (unsigned)slot);
        } else {
            /* loaded or absent */
        }
    }
    wipe(prec, sizeof prec);
    (void)snprintf(path, sizeof path, "%s/label.rec", g.dir);
    if ((file_read(path, lrec, sizeof lrec) == 1) && (wfas3_label_load(&g_dev, lrec) != WFAS3_OK)) {
        die("label record refused");
    }
    wipe(lrec, sizeof lrec);
}

/* -- Network ----------------------------------------------------------------- */

static void net_open(void)
{
    struct sockaddr_in a4;
    struct sockaddr_in6 a6;
    int on = 1;

    memset(&a4, 0, sizeof a4);
    a4.sin_family = AF_INET;
    a4.sin_port = htons(g.port);
    a4.sin_addr.s_addr = htonl(INADDR_ANY);
    g.s4 = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if ((g.s4 < 0) || (setsockopt(g.s4, SOL_SOCKET, SO_BROADCAST, &on, sizeof on) != 0) ||
        (bind(g.s4, (struct sockaddr *)&a4, sizeof a4) != 0)) {
        die("cannot open the UDP port");
    }
    (void)setsockopt(g.s4, IPPROTO_IP, IP_MULTICAST_LOOP, &on, sizeof on);
    memset(&a6, 0, sizeof a6);
    a6.sin6_family = AF_INET6;
    a6.sin6_port = htons(g.port);
    a6.sin6_addr = in6addr_any;
    g.s6 = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if ((g.s6 >= 0) && ((setsockopt(g.s6, IPPROTO_IPV6, IPV6_V6ONLY, &on, sizeof on) != 0) ||
                        (bind(g.s6, (struct sockaddr *)&a6, sizeof a6) != 0))) {
        (void)close(g.s6);
        g.s6 = -1;                                       /* IPv4 only */
    }
    g.g4 = -1;
}

static void send_to(const wfas3_addr *to, const uint8_t *d, size_t len)
{
    static const uint8_t v4m[12] = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFFU, 0xFFU };

    if (memcmp(to->ip, v4m, 12U) == 0) {
        struct sockaddr_in a;

        memset(&a, 0, sizeof a);
        a.sin_family = AF_INET;
        a.sin_port = htons(to->port);
        memcpy(&a.sin_addr, &to->ip[12], 4U);
        (void)sendto(g.s4, d, len, 0, (struct sockaddr *)&a, sizeof a);
    } else if (g.s6 >= 0) {
        struct sockaddr_in6 a;

        memset(&a, 0, sizeof a);
        a.sin6_family = AF_INET6;
        a.sin6_port = htons(to->port);
        memcpy(&a.sin6_addr, to->ip, 16U);
        (void)sendto(g.s6, d, len, 0, (struct sockaddr *)&a, sizeof a);
    } else {
        /* no IPv6 socket */
    }
}

/* Receiver: listen to the group address of a session (IPv4 only here). */
static void group_join(const wfas3_addr *ga)
{
    struct sockaddr_in a;
    struct ip_mreq mr;
    int on = 1;

    if ((g.g4 >= 0) || (ga->ip[10] != 0xFFU) || (ga->ip[11] != 0xFFU)) {
        return;
    }
    memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_port = htons(ga->port);
    memcpy(&a.sin_addr, &ga->ip[12], 4U);
    memset(&mr, 0, sizeof mr);
    memcpy(&mr.imr_multiaddr, &ga->ip[12], 4U);
    mr.imr_interface.s_addr = htonl(INADDR_ANY);
    g.g4 = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if ((g.g4 < 0) || (setsockopt(g.g4, SOL_SOCKET, SO_REUSEADDR, &on, sizeof on) != 0) ||
        (bind(g.g4, (struct sockaddr *)&a, sizeof a) != 0) ||
        (setsockopt(g.g4, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mr, sizeof mr) != 0)) {
        (void)fprintf(stderr, "wfascli: cannot join the multicast group\n");
        if (g.g4 >= 0) {
            (void)close(g.g4);
        }
        g.g4 = -1;
        return;
    }
    g.gaddr = *ga;
}

static void group_leave(void)
{
    if (g.g4 >= 0) {
        (void)close(g.g4);
        g.g4 = -1;
    }
}

/* -- Outputs of the core ----------------------------------------------------- */

static const char *fail_text(uint32_t why)
{
    switch (why) {
    case WFAS3_PAIRF_TIMEOUT: return "no answer in time";
    case WFAS3_PAIRF_WRONG: return "the code or PIN does not match";
    case WFAS3_PAIRF_REFUSED: return "the other side said no";
    case WFAS3_PAIRF_KEY: return "the server does not hold the key of the code: someone is in the middle";
    case WFAS3_PAIRF_USED: return "the window was used by another device";
    case WFAS3_PAIRF_FULL: return "no free pair slot";
    case WFAS3_PAIRF_PROTOCOL: return "protocol error";
    case WFAS3_PAIRF_CANCELLED: return "cancelled";
    case WFAS3_PAIRF_CODE: return "the codes differ";
    default: return "unknown";
    }
}

/* Every record is stored before the datagram of the same output leaves. */
static void on_out(void)
{
    cli_state *s = &g.st;
    uint32_t ev = g_out.events;
    char at[64];

    if ((ev & WFAS3_EV_STORE_DEVICE) != 0U) {
        store_device();
    }
    if (((ev & WFAS3_EV_STORE_PAIR) != 0U) && (g_out.pair < WFAS3_MAX_PAIRS)) {
        store_pair(g_out.pair);
    }
    if (((ev & WFAS3_EV_PAIR_ERASED) != 0U) && (g_out.pair < WFAS3_MAX_PAIRS)) {
        erase_pair(g_out.pair);
        (void)printf("pair %02u erased\n", (unsigned)g_out.pair);
    }
    if ((ev & WFAS3_EV_SUSPECT) != 0U) {
        (void)printf("WARNING: the keys of pair %02u were used by another device, or a store was "
                     "rolled back; pair again\n", (unsigned)g_out.pair);
    }
    if ((ev & WFAS3_EV_FORGET_ASKED) != 0U) {
        (void)printf("pair %02u: the peer removed this pair\n", (unsigned)g_out.pair);
    }
    if ((ev & WFAS3_EV_PAIRED) != 0U) {
        s->paired = 1U;
        s->paired_pair = g_out.pair;
        (void)printf("paired in slot %02u%s from %s\n", (unsigned)g_out.pair,
                     ((g_out.pflags & WFAS3_PAIR_GUEST) != 0U) ? " (guest, not stored)" : "",
                     addr_text(&g_out.addr, at, sizeof at));
        name_print("peer name:", g_out.name);
        (void)printf("SIGNAL: a new pair was made\n");   /* a headless device beeps here (13.2) */
    }
    if ((ev & WFAS3_EV_PAIR_FAILED) != 0U) {
        s->failed = 1U;
        s->fail_why = g_out.status;
        (void)printf("pairing failed: %s\n", fail_text(g_out.status));
    }
    if ((ev & WFAS3_EV_PAIR_RACE) != 0U) {
        s->race = 1U;
        (void)printf("WARNING: another device may have been paired in place of this one.\n"
                     "On the other device remove any unknown entry from its paired devices; on a\n"
                     "headless device erase all pairs (hold its button for 10 s) and pair again.\n");
    }
    if ((ev & WFAS3_EV_PAIR_ASK) != 0U) {
        s->ask_pending = 1U;
        s->ask_id = g_out.ask;
        s->ask_flags = g_out.pflags;
        name_print("pair with", g_out.name);
        (void)printf("  asking from %s\n", addr_text(&g_out.addr, at, sizeof at));
        if ((g_out.pflags & WFAS3_PAIR_GUEST) != 0U) {
            (void)printf("  as a guest\n");
        }
        if ((g_out.pflags & WFAS3_PAIR_MIC) != 0U) {
            (void)printf("  it asks for the microphone\n");
        }
        if (g.pin_mode != 0U) {
            (void)printf("  code %06u: compare it with the other screen\n", (unsigned)g_out.code);
        }
    }
    if ((ev & WFAS3_EV_PAIR_CODE) != 0U) {
        (void)printf("code %06u\n", (unsigned)g_out.code);
        s->confirm_pending = g.pin_mode;
    }
    if ((ev & WFAS3_EV_BEACON) != 0U) {
        s->beacon_ok = 1U;
        s->beacon = g_out.beacon;
    }
    if ((ev & WFAS3_EV_WINDOW_CLOSED) != 0U) {
        s->closed = 1U;
        (void)printf("pairing window closed\n");
    }
    if ((ev & WFAS3_EV_FOUND) != 0U) {
        s->found = 1U;
        s->found_addr = g_out.addr;
    }
    if ((ev & WFAS3_EV_NOT_FOUND) != 0U) {
        s->not_found = 1U;
    }
    if ((ev & WFAS3_EV_SESSION_UP) != 0U) {
        s->up += 1U;
        s->up_session = g_out.session;
        (void)printf("session %u up with pair %02u\n", (unsigned)g_out.session, (unsigned)g_out.pair);
        if ((g_out.config.flags & WFAS3_CF_SOURCE) == 0U) {
            (void)wfas3_stream_want(&g_dev, g_out.session, 1U);
        }
    }
    if ((ev & WFAS3_EV_SESSION_DOWN) != 0U) {
        s->down += 1U;
        (void)printf("session %u down (%u)\n", (unsigned)g_out.session, (unsigned)g_out.status);
    }
    if ((ev & WFAS3_EV_REFUSED) != 0U) {
        s->refused = 1U;
        (void)printf("refused by the peer (%u)\n", (unsigned)g_out.status);
    }
    if ((ev & WFAS3_EV_STREAM_START) != 0U) {
        s->started = 1U;
    }
    if ((ev & WFAS3_EV_STREAM_STOP) != 0U) {
        s->started = 0U;
    }
    if ((ev & WFAS3_EV_GROUP_JOIN) != 0U) {
        (void)printf("joining group %s\n", addr_text(&g_out.addr, at, sizeof at));
        group_join(&g_out.addr);
    }
    if ((ev & WFAS3_EV_GROUP_LEAVE) != 0U) {
        group_leave();
    }
    if ((ev & WFAS3_EV_AUDIO) != 0U) {
        s->audio_packets += 1U;
        if (g.audio_out != NULL) {
            (void)fwrite(g_out.audio, 1U, g_out.audio_len, g.audio_out);
        }
    }
    if (g_out.len > 0U) {
        if ((g_out.broadcast == 1U) && (g.bcast_set == 1U)) {
            (void)sendto(g.s4, g_out.dgram, g_out.len, 0, (struct sockaddr *)&g.bcast, sizeof g.bcast);
        } else {
            send_to(&g_out.to, g_out.dgram, g_out.len);
        }
    }
    (void)fflush(stdout);
}

/* Run the timers of the core until it has nothing to do. */
static void core_poll(void)
{
    uint32_t k;
    wfas3_status st;

    for (k = 0U; k < 256U; k++) {
        set_time();
        st = wfas3_poll(&g_dev, &g_out);
        if (st == WFAS3_E_ENTROPY) {
            add_entropy();
        } else if ((st != WFAS3_OK) || ((g_out.len == 0U) && (g_out.events == 0U))) {
            break;
        } else {
            on_out();
        }
    }
}

static void core_recv(int fd)
{
    uint8_t buf[2048];
    struct sockaddr_storage ss;
    wfas3_addr from;
    uint32_t k;
    wfas3_status st;

    for (k = 0U; k < 64U; k++) {
        socklen_t sl = sizeof ss;
        ssize_t n;

        memset(&ss, 0, sizeof ss);
        n = recvfrom(fd, buf, sizeof buf, 0, (struct sockaddr *)&ss, &sl);

        if (n <= 0) {
            break;
        }
        memset(&from, 0, sizeof from);
        if (ss.ss_family == AF_INET) {
            const struct sockaddr_in *a = (const struct sockaddr_in *)&ss;

            from.ip[10] = 0xFFU;
            from.ip[11] = 0xFFU;
            memcpy(&from.ip[12], &a->sin_addr, 4U);
            from.port = ntohs(a->sin_port);
        } else if (ss.ss_family == AF_INET6) {
            const struct sockaddr_in6 *a = (const struct sockaddr_in6 *)&ss;

            memcpy(from.ip, &a->sin6_addr, 16U);
            from.port = ntohs(a->sin6_port);
        } else {
            continue;
        }
        set_time();
        st = wfas3_receive(&g_dev, buf, (size_t)n, &from, &g_out);
        if (st == WFAS3_E_ENTROPY) {
            add_entropy();
            st = wfas3_receive(&g_dev, buf, (size_t)n, &from, &g_out);
        }
        if (st == WFAS3_OK) {
            on_out();
        }
    }
}

/* One round: wait for a datagram, the next deadline of the core, stdin when
 * asked, or at most wait_us; then handle it. Returns 1 if stdin is ready. */
static int pump(uint64_t wait_us, int want_stdin)
{
    struct pollfd fds[4];
    uint64_t dl;
    uint64_t now;
    int nfds = 0;
    int timeout;
    int stdin_ready = 0;
    int i;

    set_time();
    now = now_us();
    dl = wfas3_next_deadline(&g_dev);
    if (dl < (now + wait_us)) {
        wait_us = (dl > now) ? (dl - now) : 0U;
    }
    timeout = (int)((wait_us + 999U) / 1000U);
    fds[nfds].fd = g.s4;
    fds[nfds].events = POLLIN;
    nfds++;
    if (g.s6 >= 0) {
        fds[nfds].fd = g.s6;
        fds[nfds].events = POLLIN;
        nfds++;
    }
    if (g.g4 >= 0) {
        fds[nfds].fd = g.g4;
        fds[nfds].events = POLLIN;
        nfds++;
    }
    if (want_stdin != 0) {
        fds[nfds].fd = STDIN_FILENO;
        fds[nfds].events = POLLIN;
        nfds++;
    }
    if (poll(fds, (nfds_t)nfds, timeout) > 0) {
        for (i = 0; i < nfds; i++) {
            if ((fds[i].revents & POLLIN) != 0) {
                if (fds[i].fd == STDIN_FILENO) {
                    stdin_ready = 1;
                } else {
                    core_recv(fds[i].fd);
                }
            }
        }
    }
    core_poll();
    return stdin_ready;
}

/* A yes or no from the user (or --yes). Blocks only when stdin is ready. */
static int read_yes(void)
{
    char line[64];

    if (fgets(line, sizeof line, stdin) == NULL) {
        return 0;
    }
    return ((line[0] == 'y') || (line[0] == 'Y')) ? 1 : 0;
}

static wfas3_config cfg_pcm(uint32_t source, uint32_t group)
{
    wfas3_config c;

    memset(&c, 0, sizeof c);
    c.flags = (uint8_t)(((source != 0U) ? WFAS3_CF_SOURCE : 0U) | ((group != 0U) ? WFAS3_CF_MULTICAST : 0U));
    c.codec = WFAS3_CODEC_PCM;
    c.channels = 2U;
    c.bits = 16U;
    c.sample_rate = 48000U;
    c.frame_samples = 240U;
    c.payload_bytes = CLI_PB;
    c.latency_ms = (group != 0U) ? 200U : 40U;
    return c;
}

/* -- Commands ---------------------------------------------------------------- */

static int cmd_init(void)
{
    (void)printf("device ready in %s\n", g.dir);
    return 0;
}

static void print_code(const uint8_t *code)
{
    char text[WFAS3_CODE_TEXT_LEN];
    char qr[WFAS3_CODE_QR_LEN];

    if ((wfas3_code_format(code, 0U, text, sizeof text) == WFAS3_OK) &&
        (wfas3_code_format(code, 1U, qr, sizeof qr) == WFAS3_OK)) {
        (void)printf("code: %s\nqr: %s\n", text, qr);
    }
}

static int cmd_label_create(void)
{
    uint8_t lrec[WFAS3_LABEL_RECORD_LEN];
    uint8_t code[WFAS3_CODE_LEN];
    char path[CLI_PATH];

    (void)snprintf(path, sizeof path, "%s/label.rec", g.dir);
    if (file_read(path, lrec, sizeof lrec) != 0) {
        die("a label exists already: it is permanent (erase all pairs instead)");
    }
    if ((wfas3_label_create(&g_dev, lrec) != WFAS3_OK) || (wfas3_label_load(&g_dev, lrec) != WFAS3_OK) ||
        (wfas3_label_code(&g_dev, code) != WFAS3_OK)) {
        die("label");
    }
    file_write(g.dir, "label.rec", lrec, sizeof lrec);
    print_code(code);
    wipe(lrec, sizeof lrec);
    wipe(code, sizeof code);
    return 0;
}

static int cmd_pairs(void)
{
    uint32_t i;
    uint32_t n = 0U;

    for (i = 0U; i < WFAS3_MAX_PAIRS; i++) {
        const wfas3_pair *p = &g_dev.pairs[i];

        if (p->used == 1U) {
            char text[WFAS3_NAME_TEXT_LEN];

            wfas3_name_clean(p->name, text);
            (void)printf("%02u \"%s\"%s%s%s\n", (unsigned)i, text,
                         ((p->perms & WFAS3_PERM_RECEIVE) != 0U) ? " receive" : "",
                         ((p->perms & WFAS3_PERM_MICROPHONE) != 0U) ? " microphone" : "",
                         ((p->flags & WFAS3_PF_SUSPECT) != 0U) ? " SUSPECT" : "");
            n++;
        }
    }
    if (n == 0U) {
        (void)printf("no pairs\n");
    }
    return 0;
}

static int cmd_remove(uint32_t slot)
{
    if ((slot >= WFAS3_MAX_PAIRS) || (wfas3_pair_remove(&g_dev, slot, &g_out) != WFAS3_OK)) {
        die("no such pair");
    }
    on_out();
    return 0;
}

static int cmd_pair_server(uint32_t kind, uint32_t ask)
{
    wfas3_window_req r;
    uint8_t code[WFAS3_CODE_LEN];
    uint64_t done_at = 0U;
    wfas3_status st;

    memset(&r, 0, sizeof r);
    r.kind = kind;
    r.ask = ask;
    r.perms = WFAS3_PERM_RECEIVE | WFAS3_PERM_MICROPHONE;
    memcpy(r.name, g.name, sizeof r.name);
    g.pin_mode = (kind == WFAS3_PAIR_PIN) ? 1U : 0U;
    set_time();
    st = wfas3_window_open(&g_dev, &r, code, &g_out);
    if (st == WFAS3_E_ENTROPY) {
        add_entropy();
        st = wfas3_window_open(&g_dev, &r, code, &g_out);
    }
    if (st != WFAS3_OK) {
        die((st == WFAS3_E_STATE) ? "no label loaded (label-create first)" : "cannot open a window");
    }
    if (kind == WFAS3_PAIR_PIN) {
        (void)printf("PIN: %.8s\n", (const char *)code);
    } else {
        print_code(code);
    }
    wipe(code, sizeof code);
    on_out();
    while ((g_stop == 0) && (g.st.closed == 0U) && ((done_at == 0U) || (now_us() < done_at))) {
        int ready = pump(100000U, (g.st.ask_pending != 0U) && (g.yes == 0U));

        if ((g.st.ask_pending != 0U) && ((g.yes != 0U) || (ready != 0))) {
            uint32_t yes = (g.yes != 0U) ? 1U : (uint32_t)read_yes();

            g.st.ask_pending = 0U;
            set_time();
            if (wfas3_pair_answer(&g_dev, g.st.ask_id, yes, WFAS3_PERM_RECEIVE | WFAS3_PERM_MICROPHONE,
                                  0U, &g_out) == WFAS3_OK) {
                on_out();
            }
        }
        if ((g.st.paired != 0U) && (done_at == 0U)) {
            done_at = now_us() + 3000000U;               /* answer copies of step 8 a while */
        }
        if ((g.st.ask_pending != 0U) && (g.yes == 0U)) {
            (void)printf("pair? [y/N] ");
            (void)fflush(stdout);
        }
    }
    (void)printf("failed attempts in this window: %u\n",
                 (unsigned)((wfas3_window_failed(&g_dev) == WFAS3_NONE) ? 0U : wfas3_window_failed(&g_dev)));
    return (g.st.paired != 0U) ? 0 : 2;
}

static int cmd_pair_client(uint32_t kind, const char *secret, const char *server, uint32_t flags,
                           uint32_t replace)
{
    wfas3_pair_req r;
    uint64_t until;
    wfas3_status st;
    char text[WFAS3_NAME_TEXT_LEN];

    memset(&r, 0, sizeof r);
    r.kind = kind;
    r.flags = flags;
    r.perms = WFAS3_PERM_RECEIVE | WFAS3_PERM_MICROPHONE;
    r.replace = replace;
    memcpy(r.name, g.name, sizeof r.name);
    g.pin_mode = (kind == WFAS3_PAIR_PIN) ? 1U : 0U;
    if (kind == WFAS3_PAIR_PIN) {
        if (strlen(secret) != WFAS3_PIN_LEN) {
            die("the PIN has 8 digits");
        }
        memcpy(r.pin, secret, WFAS3_PIN_LEN);
        (void)wfas3_beacons(&g_dev, 1U);
        until = now_us() + 10000000U;
        while ((g_stop == 0) && (now_us() < until)) {
            (void)pump(100000U, 0);
            if (g.st.beacon_ok != 0U) {
                wfas3_name_clean(g.st.beacon.name, text);
                if ((server == NULL) || (strcmp(server, text) == 0)) {
                    break;
                }
                g.st.beacon_ok = 0U;
            }
        }
        if (g.st.beacon_ok == 0U) {
            die("no pairing window in PIN mode found");
        }
        (void)printf("server \"%s\"\n", text);
        r.beacon = g.st.beacon;
        (void)wfas3_beacons(&g_dev, 0U);
    } else if (wfas3_code_parse(secret, strlen(secret), r.code) != WFAS3_OK) {
        die("this code is not valid: check it");
    }
    set_time();
    st = wfas3_pair_start(&g_dev, &r, &g_out);
    if (st == WFAS3_E_ENTROPY) {
        add_entropy();
        st = wfas3_pair_start(&g_dev, &r, &g_out);
    }
    wipe(&r, sizeof r);
    if (st != WFAS3_OK) {
        die("cannot start pairing");
    }
    on_out();
    while ((g_stop == 0) && (g.st.paired == 0U) && (g.st.failed == 0U)) {
        int ready = pump(100000U, (g.st.confirm_pending != 0U) && (g.yes == 0U));

        if ((g.st.confirm_pending != 0U) && ((g.yes != 0U) || (ready != 0))) {
            uint32_t yes = (g.yes != 0U) ? 1U : (uint32_t)read_yes();

            g.st.confirm_pending = 0U;
            set_time();
            if (wfas3_pair_confirm(&g_dev, yes, &g_out) == WFAS3_OK) {
                on_out();
            }
        } else if (g.st.confirm_pending != 0U) {
            (void)printf("same code on the server? [y/N] ");
            (void)fflush(stdout);
        } else {
            /* waiting */
        }
    }
    if (g_stop != 0) {
        (void)wfas3_pair_cancel(&g_dev);
    }
    return (g.st.paired != 0U) ? 0 : 2;
}

/* Look for the peer of slot; 0 and its address when found. */
static int find_peer(uint32_t slot, wfas3_addr *at)
{
    g.st.found = 0U;
    g.st.not_found = 0U;
    set_time();
    if (wfas3_find(&g_dev, slot, 1U, &g_out) != WFAS3_OK) {
        return -1;
    }
    on_out();
    while ((g_stop == 0) && (g.st.found == 0U) && (g.st.not_found == 0U)) {
        (void)pump(100000U, 0);
    }
    if (g.st.found != 0U) {
        (void)wfas3_find(&g_dev, slot, 0U, &g_out);
        *at = g.st.found_addr;
        return 0;
    }
    return -1;
}

static int cmd_find(uint32_t slot)
{
    wfas3_addr at;
    char text[64];

    if ((slot >= WFAS3_MAX_PAIRS) || (g_dev.pairs[slot].used == 0U)) {
        die("no such pair");
    }
    if (find_peer(slot, &at) != 0) {
        (void)printf("not found\n");
        return 2;
    }
    (void)printf("found at %s\n", addr_text(&at, text, sizeof text));
    return 0;
}

/* Fill one packet from the file (or a test pattern) at sample position pos. */
static int next_audio(FILE *in, uint8_t *buf, uint64_t pos)
{
    size_t i;

    if (in != NULL) {
        return (fread(buf, 1U, CLI_PB, in) == CLI_PB) ? 0 : -1;
    }
    for (i = 0U; i < CLI_PB; i++) {
        buf[i] = (uint8_t)((pos / 240U) + i);
    }
    return 0;
}

static int connect_slot(uint32_t slot, uint32_t group)
{
    wfas3_addr at;
    wfas3_config c = cfg_pcm(1U, group);
    char text[64];
    uint64_t until;
    uint32_t up0 = g.st.up;
    wfas3_status st;

    if ((slot >= WFAS3_MAX_PAIRS) || (g_dev.pairs[slot].used == 0U)) {
        die("no such pair");
    }
    if (find_peer(slot, &at) != 0) {
        (void)fprintf(stderr, "wfascli: pair %02u not found\n", (unsigned)slot);
        return -1;
    }
    (void)printf("pair %02u found at %s\n", (unsigned)slot, addr_text(&at, text, sizeof text));
    set_time();
    st = wfas3_connect(&g_dev, slot, &at, &c, &g_out);
    if (st == WFAS3_E_ENTROPY) {
        add_entropy();
        st = wfas3_connect(&g_dev, slot, &at, &c, &g_out);
    }
    if (st != WFAS3_OK) {
        return -1;
    }
    on_out();
    until = now_us() + 10000000U;
    while ((g_stop == 0) && (g.st.up == up0) && (g.st.refused == 0U) && (now_us() < until)) {
        (void)pump(100000U, 0);
    }
    return (g.st.up > up0) ? 0 : -1;
}

/* Send audio to one pair (group = NULL) or to a group of pairs. */
static int cmd_send(const uint32_t *slots, uint32_t n, const char *file, uint32_t seconds,
                    const wfas3_addr *group)
{
    FILE *in = NULL;
    uint8_t buf[CLI_PB];
    uint64_t next;
    uint64_t end;
    uint64_t pos = 0U;
    uint64_t sent = 0U;
    uint32_t i;
    uint32_t session = WFAS3_NONE;
    uint32_t loaded = 0U;
    wfas3_status st;

    if (file != NULL) {
        in = fopen(file, "rb");
        if (in == NULL) {
            die("cannot open the audio file");
        }
    }
    if (group != NULL) {
        wfas3_config c = cfg_pcm(1U, 1U);

        if (wfas3_group_open(&g_dev, &c, group) != WFAS3_OK) {
            die("cannot open the group");
        }
    }
    for (i = 0U; i < n; i++) {
        if (connect_slot(slots[i], (group != NULL) ? 1U : 0U) == 0) {
            session = g.st.up_session;
        }
    }
    if (session == WFAS3_NONE) {
        die("no session");
    }
    end = now_us() + 5000000U;
    while ((g_stop == 0) && (g.st.started == 0U) && (now_us() < end)) {
        (void)pump(20000U, 0);                           /* no audio before START (10.4) */
    }
    end = now_us() + ((uint64_t)seconds * 1000000U);
    next = now_us();
    loaded = 0U;
    while ((g_stop == 0) && (now_us() < end)) {
        uint64_t now = now_us();

        if (now >= next) {
            if ((loaded == 0U) && (next_audio(in, buf, pos) != 0)) {
                break;
            }
            loaded = 1U;
            set_time();
            st = (group != NULL) ? wfas3_group_send(&g_dev, pos, now, buf, CLI_PB, &g_out)
                                 : wfas3_send_audio(&g_dev, session, pos, now, buf, CLI_PB, &g_out);
            if (st == WFAS3_OK) {
                on_out();
                sent += 1U;
                loaded = 0U;                             /* the next packet of the file */
                pos += 240U;
            }
            next += CLI_FRAME_US;
        }
        (void)pump((next > now_us()) ? (next - now_us()) : 0U, 0);
    }
    (void)printf("sent %llu packets\n", (unsigned long long)sent);
    for (i = 0U; i < WFAS3_MAX_SESSIONS; i++) {
        wfas3_info info;

        if ((wfas3_session_info(&g_dev, i, &info) == WFAS3_OK) && (info.state == 2U) &&
            (wfas3_session_close(&g_dev, i, WFAS3_BYE_NORMAL, &g_out) == WFAS3_OK)) {
            on_out();
        }
    }
    end = now_us() + 300000U;
    while (now_us() < end) {
        (void)pump(50000U, 0);                           /* the two more BYE packets */
    }
    if (in != NULL) {
        (void)fclose(in);
    }
    return (sent > 0U) ? 0 : 2;
}

static int cmd_listen(const char *file, uint32_t seconds)
{
    uint64_t end = (seconds == 0U) ? UINT64_MAX : (now_us() + ((uint64_t)seconds * 1000000U));

    if (file != NULL) {
        int fd = open(file, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);

        if ((fd < 0) || ((g.audio_out = fdopen(fd, "wb")) == NULL)) {
            die("cannot create the audio file");
        }
    }
    (void)printf("listening on port %u\n", (unsigned)g.port);
    (void)fflush(stdout);
    while ((g_stop == 0) && (now_us() < end)) {
        (void)pump(200000U, 0);
    }
    (void)printf("received %llu packets\n", (unsigned long long)g.st.audio_packets);
    return 0;
}

/* -- Main -------------------------------------------------------------------- */

static void usage(void)
{
    (void)fprintf(stderr,
        "usage: wfascli [options] command [arguments]\n"
        "options:\n"
        "  -d DIR        storage directory (default $HOME/.wfas3)\n"
        "  -p PORT       local UDP port (default 14114)\n"
        "  -n NAME       declared name of this device\n"
        "  -y            answer yes to every question (tests, headless use)\n"
        "  -B ADDR:PORT  send broadcasts there instead (tests on one machine)\n"
        "commands:\n"
        "  init                          create the device record\n"
        "  label-create                  make the permanent label of a headless device\n"
        "  pairs                         list the pairs\n"
        "  remove SLOT                   remove a pair (the entry key changes)\n"
        "  pair-server [code|label|pin] [headless]   open a pairing window\n"
        "  pair-client code|label TEXT [guest] [mic] [replace SLOT]\n"
        "  pair-client pin DIGITS [SERVER-NAME] [guest] [mic]\n"
        "  find SLOT                     look for the peer of a pair\n"
        "  send SLOT [FILE] [SECONDS]    send raw PCM (s16le, 2 ch, 48 kHz) or a test pattern\n"
        "  multicast ADDR:PORT SECONDS SLOT...   send a test pattern to a group\n"
        "  listen [FILE] [SECONDS]       accept sessions, write received audio\n");
    finish(1);
}

static uint32_t num(const char *s, uint32_t max)
{
    char *end = NULL;
    unsigned long v = strtoul(s, &end, 10);

    if ((end == s) || (*end != '\0') || (v > max)) {
        usage();
    }
    return (uint32_t)v;
}

int main(int argc, char **argv)
{
    const char *home = getenv("HOME");
    const char *cmd;
    struct sigaction sa;
    wfas3_addr ba;
    int opt;
    int rc = 1;

    (void)prctl(PR_SET_DUMPABLE, 0, 0, 0, 0);           /* no core dump of the keys */
    (void)mlockall(MCL_CURRENT | MCL_FUTURE);            /* best effort: not swapped out */
    memset(&g, 0, sizeof g);
    g.port = (uint16_t)WFAS3_UDP_PORT;
    g.s6 = -1;
    g.g4 = -1;
    name_set("wfascli");
    if ((home == NULL) || (strlen(home) >= (sizeof g.dir - 8U))) {
        home = ".";
    }
    (void)snprintf(g.dir, sizeof g.dir, "%s/.wfas3", home);
    while ((opt = getopt(argc, argv, "d:p:n:yB:")) != -1) {
        switch (opt) {
        case 'd':
            if (strlen(optarg) >= (sizeof g.dir - 1U)) {
                usage();
            }
            (void)snprintf(g.dir, sizeof g.dir, "%s", optarg);
            break;
        case 'p':
            g.port = (uint16_t)num(optarg, 65535U);
            break;
        case 'n':
            name_set(optarg);
            break;
        case 'y':
            g.yes = 1U;
            break;
        case 'B':
            if ((addr_parse(optarg, &ba) != 0) || (ba.ip[10] != 0xFFU)) {
                usage();
            }
            memset(&g.bcast, 0, sizeof g.bcast);
            g.bcast.sin_family = AF_INET;
            g.bcast.sin_port = htons(ba.port);
            memcpy(&g.bcast.sin_addr, &ba.ip[12], 4U);
            g.bcast_set = 1U;
            break;
        default:
            usage();
        }
    }
    if (optind >= argc) {
        usage();
    }
    cmd = argv[optind];
    argc -= optind;
    argv += optind;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_signal;
    (void)sigaction(SIGINT, &sa, NULL);
    (void)sigaction(SIGTERM, &sa, NULL);
    umask(077);
    dir_secure(g.dir);
    (void)snprintf(g.pairs_dir, sizeof g.pairs_dir, "%s/pairs", g.dir);
    dir_secure(g.pairs_dir);
    load_all();
    net_open();
    if (strcmp(cmd, "init") == 0) {
        rc = cmd_init();
    } else if (strcmp(cmd, "label-create") == 0) {
        rc = cmd_label_create();
    } else if (strcmp(cmd, "pairs") == 0) {
        rc = cmd_pairs();
    } else if ((strcmp(cmd, "remove") == 0) && (argc == 2)) {
        rc = cmd_remove(num(argv[1], WFAS3_MAX_PAIRS - 1U));
    } else if (strcmp(cmd, "pair-server") == 0) {
        uint32_t kind = WFAS3_PAIR_CODE;
        uint32_t ask = 1U;
        int i;

        for (i = 1; i < argc; i++) {
            if (strcmp(argv[i], "label") == 0) {
                kind = WFAS3_PAIR_LABEL;
            } else if (strcmp(argv[i], "pin") == 0) {
                kind = WFAS3_PAIR_PIN;
            } else if (strcmp(argv[i], "headless") == 0) {
                ask = 0U;
            } else if (strcmp(argv[i], "code") != 0) {
                usage();
            }
        }
        rc = cmd_pair_server(kind, ask);
    } else if ((strcmp(cmd, "pair-client") == 0) && (argc >= 3)) {
        uint32_t kind = (strcmp(argv[1], "pin") == 0) ? WFAS3_PAIR_PIN
                      : (strcmp(argv[1], "label") == 0) ? WFAS3_PAIR_LABEL : WFAS3_PAIR_CODE;
        const char *server = NULL;
        uint32_t flags = 0U;
        uint32_t replace = WFAS3_NONE;
        int i;

        if ((strcmp(argv[1], "code") != 0) && (kind == WFAS3_PAIR_CODE)) {
            usage();
        }
        for (i = 3; i < argc; i++) {
            if (strcmp(argv[i], "guest") == 0) {
                flags |= WFAS3_PAIR_GUEST;
            } else if (strcmp(argv[i], "mic") == 0) {
                flags |= WFAS3_PAIR_MIC;
            } else if ((strcmp(argv[i], "replace") == 0) && ((i + 1) < argc)) {
                replace = num(argv[++i], WFAS3_MAX_PAIRS - 1U);
            } else if ((kind == WFAS3_PAIR_PIN) && (server == NULL)) {
                server = argv[i];
            } else {
                usage();
            }
        }
        rc = cmd_pair_client(kind, argv[2], server, flags, replace);
    } else if ((strcmp(cmd, "find") == 0) && (argc == 2)) {
        rc = cmd_find(num(argv[1], WFAS3_MAX_PAIRS - 1U));
    } else if ((strcmp(cmd, "send") == 0) && (argc >= 2) && (argc <= 4)) {
        uint32_t slot = num(argv[1], WFAS3_MAX_PAIRS - 1U);

        rc = cmd_send(&slot, 1U, (argc >= 3) ? argv[2] : NULL, (argc >= 4) ? num(argv[3], 86400U) : 10U, NULL);
    } else if ((strcmp(cmd, "multicast") == 0) && (argc >= 4)) {
        uint32_t slots[WFAS3_GROUP_SLOTS];
        uint32_t n = 0U;
        int i;

        if (addr_parse(argv[1], &ba) != 0) {
            usage();
        }
        for (i = 3; (i < argc) && (n < WFAS3_GROUP_SLOTS); i++) {
            slots[n++] = num(argv[i], WFAS3_MAX_PAIRS - 1U);
        }
        rc = cmd_send(slots, n, NULL, num(argv[2], 86400U), &ba);
    } else if ((strcmp(cmd, "listen") == 0) && (argc <= 3)) {
        rc = cmd_listen((argc >= 2) ? argv[1] : NULL, (argc >= 3) ? num(argv[2], 86400U) : 0U);
    } else {
        usage();
    }
    finish(rc);
    return rc;
}
