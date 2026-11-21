/* -----------------------------------------------------------------------------
 * WFAS v3 - core library, public interface.
 *
 * Implements WFAS_V3_PROTOCOL.md. WFAS v3 is EXPERIMENTAL and is a separate
 * protocol from WFAS v2: nothing here reads or writes a v2 packet.
 *
 * The core opens no socket, reads no clock, allocates no memory and keeps no
 * global state. It is called from one thread, or under one lock. Its inputs
 * are received datagrams with their source address, the monotonic time in
 * microseconds, entropy and stored records; its outputs are datagrams to
 * send, records to store, audio to play and events.
 *
 * Build stage: pairing and multicast. Implemented: datagram classifier,
 * cryptographic layer, stored records (specification 5), entry (7),
 * handshake (8), pair state machines (9.1 to 9.3), entry key rotation and
 * pair removal (9.4), transport with frames, audio, consent, presence,
 * closing, address change and synchronised playback (10), rekey (11),
 * discovery (12), pairing (13), multicast (14).
 *
 * Rules for the caller:
 *  - Call wfas3_selftest() once at start; do not use the library if it fails.
 *  - Give the time with wfas3_set_time() before every call; it must come from
 *    one monotonic clock in microseconds that keeps running in suspend, the
 *    same clock as the capture times of audio.
 *  - Handle every output before the next call into the core. Events that ask
 *    to store a record must be completed before that next call, and before
 *    the datagram of the same output is sent.
 *  - Call wfas3_poll() until it returns WFAS3_OK with nothing to do, at the
 *    latest at wfas3_next_deadline().
 *  - When a call returns WFAS3_E_ENTROPY, give fresh entropy with
 *    wfas3_add_entropy() and repeat the same call: nothing has changed.
 *
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Marco Morosi
 * ------------------------------------------------------------------------- */
#ifndef WFAS3_H
#define WFAS3_H

#include <stdint.h>
#include <stddef.h>

#include "wfas_tp.h"

#ifdef __cplusplus
extern "C" {
#endif

#define WFAS3_PROTOCOL_VERSION   3
#define WFAS3_UDP_PORT           14114U   /* provisional, see specification 18 */
#define WFAS3_MAX_DATAGRAM       1400U
#define WFAS3_MARKER             0xA3U
#define WFAS3_HEADER_LEN         4U

/* Datagram types (specification section 3). */
#define WFAS3_T_PROBE            0x01U
#define WFAS3_T_PROBE_REPLY      0x02U
#define WFAS3_T_PAIR_BEACON      0x03U
#define WFAS3_T_INIT             0x10U
#define WFAS3_T_RESP             0x11U
#define WFAS3_T_COOKIE_REPLY     0x12U
#define WFAS3_T_PAIR             0x13U
#define WFAS3_T_COOKIE_REQUEST   0x14U
#define WFAS3_T_REJECT           0x15U
#define WFAS3_T_TRANSPORT        0x20U
#define WFAS3_T_MULTICAST        0x30U

/* Exact datagram lengths. */
#define WFAS3_LEN_PROBE          60U
#define WFAS3_LEN_PAIR_BEACON    72U
#define WFAS3_LEN_INIT           1368U
#define WFAS3_LEN_RESP           1228U
#define WFAS3_LEN_COOKIE         64U
#define WFAS3_LEN_REJECT         48U
#define WFAS3_LEN_CONTROL        160U
#define WFAS3_AUDIO_OVERHEAD     55U      /* audio packet = payload + 55      */
#define WFAS3_MCAST_OVERHEAD     187U     /* multicast packet = payload + 187 */
#define WFAS3_MAX_AUDIO_PAYLOAD  1344U
#define WFAS3_MAX_MCAST_PAYLOAD  1213U

/* Capacity. Lower them on small devices. Pairs: at most 64. */
#ifndef WFAS3_MAX_PAIRS
#define WFAS3_MAX_PAIRS          64U
#endif
#ifndef WFAS3_MAX_SESSIONS
#define WFAS3_MAX_SESSIONS       8U
#endif
#define WFAS3_MAX_ATTEMPTS       4U
#define WFAS3_MAX_FINDS          4U       /* searches running at a time (12)  */
#define WFAS3_PAIR_CTX           2U       /* pairing clients served at a time (13.7) */
#define WFAS3_GROUP_SLOTS        16U      /* receivers of a multicast group (14) */
#ifndef WFAS3_MCAST_HISTORY
#define WFAS3_MCAST_HISTORY      64U      /* packets a source keeps for NACK (14.3):
                                             at least the packets of 200 ms   */
#endif
#define WFAS3_MCAST_DEDUP        64U      /* sample positions remembered (14.3) */
#define WFAS3_NONE               0xFFFFFFFFU

/* Sizes of what the caller supplies or stores. */
#define WFAS3_SEED_LEN           48U      /* entropy at start                 */
#define WFAS3_ENTROPY_LEN        32U      /* entropy when the core asks       */
#define WFAS3_DEVICE_RECORD_LEN  40U
#define WFAS3_PAIR_RECORD_LEN    420U
#define WFAS3_LABEL_RECORD_LEN   48U      /* headless: pairing identity key 32,
                                             label secret 16 (specification 5) */
#define WFAS3_CODE_LEN           32U      /* pairing code: secret 16, fp 16   */
#define WFAS3_CODE_TEXT_LEN      65U      /* 13 groups of 4 and '-', with NUL */
#define WFAS3_CODE_QR_LEN        59U      /* "WFAS3:" and 52, with NUL        */
#define WFAS3_PIN_LEN            8U       /* ASCII digits                     */
#define WFAS3_NAME_LEN           32U      /* declared name: UTF-8, zero padded */
#define WFAS3_NAME_TEXT_LEN      33U      /* cleaned name and NUL             */

/* Result of every public function. The caller must read it. */
typedef enum wfas3_status {
    WFAS3_OK = 0,
    WFAS3_E_ARG = 1,       /* invalid argument                                */
    WFAS3_E_DROP = 2,      /* datagram dropped: no reply, no state change     */
    WFAS3_E_AUTH = 3,      /* authentication failed                           */
    WFAS3_E_ENTROPY = 4,   /* fresh entropy needed: call wfas3_add_entropy()
                              and repeat the same call; nothing has changed   */
    WFAS3_E_SELFTEST = 5,  /* a primitive gives a wrong answer on this device */
    WFAS3_E_STATE = 6,     /* not allowed in the current state                */
    WFAS3_E_FULL = 7,      /* no free slot                                    */
    WFAS3_E_PERM = 8       /* not allowed by the permissions of the pair      */
} wfas3_status;

/* Events reported in wfas3_out.events. Several can come in one output. */
#define WFAS3_EV_STORE_PAIR      0x0001U  /* store the record of out.pair    */
#define WFAS3_EV_STORE_DEVICE    0x0002U  /* store the device record         */
#define WFAS3_EV_SESSION_UP      0x0004U  /* out.session is established;
                                             out.config is its configuration.
                                             It replaces any previous session
                                             of out.pair                     */
#define WFAS3_EV_SESSION_DOWN    0x0008U  /* out.session was closed;
                                             out.status is a WFAS3_DOWN_*    */
#define WFAS3_EV_SUSPECT         0x0010U  /* the keys of out.pair were used by
                                             someone else, or a store was
                                             rolled back: tell the user, offer
                                             to pair again; never show it as a
                                             network failure                 */
#define WFAS3_EV_ATTEMPT_FAILED  0x0020U  /* the attempt for out.pair ended  */
#define WFAS3_EV_REFUSED         0x0040U  /* the peer answered with out.status;
                                             with WFAS3_ST_UNSUPPORTED,
                                             out.config is what it accepts   */
#define WFAS3_EV_ATTEMPT_YIELDED 0x0080U  /* the attempt for out.pair was
                                             dropped because the peer opens a
                                             session at the same time (rule
                                             C3); a SESSION_UP normally follows */
#define WFAS3_EV_AUDIO           0x0100U  /* out.audio holds audio to play    */
#define WFAS3_EV_STREAM_START    0x0200U  /* the peer accepts the stream this
                                             device sends: audio may flow     */
#define WFAS3_EV_STREAM_STOP     0x0400U  /* stop sending: STOP, or consent
                                             lapsed                          */
#define WFAS3_EV_STREAM_PARAMS   0x0800U  /* the stream this device receives
                                             changed: out.config, out.stream_on */
#define WFAS3_EV_PEER_SILENT     0x1000U  /* nothing from the peer for 1 s    */
#define WFAS3_EV_PEER_BACK       0x2000U  /* the peer is heard again          */
#define WFAS3_EV_PAIR_ERASED     0x4000U  /* erase the stored record of
                                             out.pair                        */
#define WFAS3_EV_FORGET_ASKED    0x8000U  /* the peer removed this pair and
                                             asks to be forgotten: call
                                             wfas3_pair_remove() unless the
                                             user keeps it                   */
#define WFAS3_EV_REKEYED         0x10000U /* the session has new keys         */
#define WFAS3_EV_PATH_CHANGED    0x20000U /* the session address changed      */
#define WFAS3_EV_FOUND           0x40000U /* the peer of out.pair answered a
                                             Probe from out.addr: connect there */
#define WFAS3_EV_NOT_FOUND       0x80000U /* the search for out.pair ended
                                             without an answer                 */
#define WFAS3_EV_BEACON          0x100000U  /* a PIN-mode PairBeacon, out.beacon,
                                               reported while wfas3_beacons()
                                               is on; nothing in it is verified */
#define WFAS3_EV_PAIR_ASK        0x200000U  /* server: ask the user whether to
                                               pair with out.name (clean it with
                                               wfas3_name_clean), out.pflags; in
                                               PIN mode show out.code too. "No"
                                               first; answer with
                                               wfas3_pair_answer(out.ask)     */
#define WFAS3_EV_PAIR_CODE       0x400000U  /* client: show the 6-digit out.code;
                                               in PIN mode the user compares it
                                               with the server's and answers
                                               with wfas3_pair_confirm()      */
#define WFAS3_EV_PAIRED          0x800000U  /* a new pair, out.pair, with the
                                               peer's out.name and out.addr;
                                               WFAS3_PAIR_GUEST in out.pflags
                                               for a guest pair, which is never
                                               stored. A headless device MUST
                                               signal it (13.2)               */
#define WFAS3_EV_PAIR_FAILED     0x1000000U /* the pairing ended without a pair:
                                               out.status is a WFAS3_PAIRF_*  */
#define WFAS3_EV_PAIR_RACE       0x2000000U /* with PAIR_FAILED on a client: tell
                                               the user that another device may
                                               have been paired in its place and
                                               propose the remedy (13.2)      */
#define WFAS3_EV_WINDOW_CLOSED   0x4000000U /* server: the pairing window closed */
#define WFAS3_EV_GROUP_JOIN      0x8000000U /* receiver: join the multicast group
                                               out.addr (address and port) for
                                               out.session                    */
#define WFAS3_EV_GROUP_LEAVE     0x10000000U/* receiver: leave the multicast group
                                               out.addr of out.session        */

/* Why a session closed (out.status with WFAS3_EV_SESSION_DOWN). Values 0 to
 * 5 are the BYE reasons of specification 10.2, sent by the peer. */
#define WFAS3_BYE_NORMAL         0U
#define WFAS3_BYE_PREEMPTED      1U
#define WFAS3_BYE_REVOKED        2U
#define WFAS3_BYE_PROTOCOL       3U
#define WFAS3_BYE_GUEST_ENDED    4U
#define WFAS3_BYE_REKEY_FAILED   5U
#define WFAS3_DOWN_LOCAL         0x10U    /* closed by this application      */
#define WFAS3_DOWN_TIMEOUT       0x11U    /* nothing from the peer for 15 s  */
#define WFAS3_DOWN_PROTOCOL      0x12U    /* the peer broke the protocol     */
#define WFAS3_DOWN_REKEY         0x13U    /* the rekey did not complete      */
#define WFAS3_DOWN_REMOVED       0x14U    /* the pair was removed            */
#define WFAS3_DOWN_LIMIT         0x15U    /* the counter limit was reached   */

/* Pair permissions and flags (stored). */
#define WFAS3_PERM_RECEIVE       0x01U  /* the peer may receive our audio     */
#define WFAS3_PERM_MICROPHONE    0x02U  /* microphone audio may flow between
                                           this device and the peer; both
                                           records must allow it            */
#define WFAS3_PF_GUEST           0x01U
#define WFAS3_PF_SUSPECT         0x02U

/* Pairing (specification 13). Methods: */
#define WFAS3_PAIR_CODE          1U     /* a fresh code, shown as text or QR   */
#define WFAS3_PAIR_LABEL         2U     /* the label of a headless device      */
#define WFAS3_PAIR_PIN           3U     /* a fresh PIN; both users compare the
                                           6-digit code                        */
/* What a client asks for (out.pflags with PAIR_ASK), and what a pair is. */
#define WFAS3_PAIR_GUEST         0x01U  /* a guest pair: RAM only (13.6)        */
#define WFAS3_PAIR_MIC           0x02U  /* the microphone permission            */
/* Why a pairing failed (out.status with WFAS3_EV_PAIR_FAILED). */
#define WFAS3_PAIRF_TIMEOUT      1U     /* no answer in time                    */
#define WFAS3_PAIRF_WRONG        2U     /* the code or PIN does not match, or
                                           someone interfered                  */
#define WFAS3_PAIRF_REFUSED      3U     /* the other side said no              */
#define WFAS3_PAIRF_KEY          4U     /* the server does not hold the pairing
                                           key of the code: someone is in the
                                           middle                              */
#define WFAS3_PAIRF_USED         5U     /* the window was used by another device */
#define WFAS3_PAIRF_FULL         6U     /* no free pair slot                    */
#define WFAS3_PAIRF_PROTOCOL     7U     /* a commitment or a field is wrong     */
#define WFAS3_PAIRF_CANCELLED    8U     /* cancelled on this device             */
#define WFAS3_PAIRF_CODE         9U     /* PIN mode: the user said the codes
                                           differ                              */

/* Stream configuration flags, codecs and answers (specification 8.3). */
#define WFAS3_CF_SOURCE          0x01U
#define WFAS3_CF_SYNC            0x02U
#define WFAS3_CF_MULTICAST       0x04U
#define WFAS3_CF_MICROPHONE      0x08U
#define WFAS3_CODEC_PCM          1U
#define WFAS3_CODEC_OPUS         2U
#define WFAS3_ST_ACCEPTED        0U
#define WFAS3_ST_BUSY            1U
#define WFAS3_ST_NOT_PERMITTED   2U
#define WFAS3_ST_UNSUPPORTED     3U

/* Which stream an audio output belongs to. */
#define WFAS3_STREAM_MAIN        0U
#define WFAS3_STREAM_MIC         1U

typedef struct wfas3_addr {
    uint8_t  ip[16];       /* IPv6, or IPv4-mapped (::ffff:a.b.c.d)           */
    uint16_t port;
} wfas3_addr;

/* Seen from this device: in a session configuration WFAS3_CF_SOURCE set
 * means this device is the source of the main stream. In the configuration
 * of a single stream (wfas3_stream_params, WFAS3_EV_STREAM_PARAMS) the flags
 * are set by the core. */
typedef struct wfas3_config {
    uint8_t  flags;        /* WFAS3_CF_*                                      */
    uint8_t  codec;        /* WFAS3_CODEC_*                                   */
    uint8_t  channels;     /* 1..8                                            */
    uint8_t  bits;         /* PCM: 16 or 24. Opus: 16                         */
    uint32_t sample_rate;
    uint16_t frame_samples;
    uint16_t payload_bytes;
    uint16_t latency_ms;
} wfas3_config;

/* What this device accepts when it answers a handshake. */
typedef struct wfas3_policy {
    uint32_t     busy;          /* 1: answer "busy" to every request          */
    uint32_t     codec_mask;    /* bit n set: codec n accepted                */
    uint32_t     max_channels;
    wfas3_config suggest;       /* offered with "configuration not supported" */
} wfas3_policy;

/* A PIN-mode PairBeacon (13.2). Public and unauthenticated. */
typedef struct wfas3_beacon {
    wfas3_addr addr;           /* where it came from: the server              */
    uint32_t   used;           /* 1: a pairing was completed in that window   */
    uint8_t    nonce[16];      /* window_nonce                                */
    uint8_t    name[32];       /* declared name, raw: wfas3_name_clean()      */
} wfas3_beacon;

/* A pairing window opened by a server (wfas3_window_open). */
typedef struct wfas3_window_req {
    uint32_t kind;             /* WFAS3_PAIR_CODE, _LABEL or _PIN             */
    uint8_t  name[32];         /* this device's declared name (step 7; the
                                  beacon in PIN mode)                         */
    uint32_t ask;              /* 1: ask the user about each client
                                  (WFAS3_EV_PAIR_ASK). 0: the open window is
                                  the approval (headless device)              */
    uint32_t perms;            /* with ask = 0: WFAS3_PERM_* of the new pair;
                                  the microphone only if the client asks      */
} wfas3_window_req;

/* A pairing started by a client (wfas3_pair_start). */
typedef struct wfas3_pair_req {
    uint32_t     kind;         /* WFAS3_PAIR_CODE, _LABEL or _PIN             */
    uint8_t      code[32];     /* CODE, LABEL: from wfas3_code_parse()        */
    uint8_t      pin[8];       /* PIN: the 8 digits typed by the user (ASCII) */
    wfas3_beacon beacon;       /* PIN: the beacon the user chose              */
    uint8_t      name[32];     /* this device's declared name                 */
    uint32_t     flags;        /* WFAS3_PAIR_GUEST, WFAS3_PAIR_MIC asked for  */
    uint32_t     perms;        /* WFAS3_PERM_* of the new pair on this side;
                                  the microphone only if the server grants it */
    uint32_t     replace;      /* the pair this one replaces (13.5), usually a
                                  suspect one, or WFAS3_NONE                  */
} wfas3_pair_req;

/* Output of a call. len == 0 means there is no datagram to send. With
 * broadcast == 1 the datagram goes to the local broadcast address: out.to is
 * then ::ffff:255.255.255.255 port 14114; on a link without IPv4 the
 * application sends it to ff02::1, port 14114, instead (specification 12). */
typedef struct wfas3_out {
    uint8_t      dgram[WFAS3_MAX_DATAGRAM];
    size_t       len;
    wfas3_addr   to;
    uint32_t     broadcast;
    uint32_t     events;     /* WFAS3_EV_*                                    */
    uint32_t     pair;       /* pair the events refer to, or WFAS3_NONE       */
    uint32_t     session;    /* session the events refer to, or WFAS3_NONE    */
    uint32_t     status;     /* WFAS3_ST_* with REFUSED, WFAS3_DOWN_* or a BYE
                                reason with SESSION_DOWN                       */
    wfas3_config config;     /* see SESSION_UP, REFUSED, STREAM_PARAMS        */
    uint32_t     stream_on;  /* with STREAM_PARAMS: the stream is on          */
    wfas3_addr   addr;       /* with FOUND: the address the peer answered from;
                                with PAIRED: the peer's address; with
                                GROUP_JOIN, GROUP_LEAVE: the group address  */
    /* with WFAS3_EV_AUDIO */
    uint32_t     stream;     /* WFAS3_STREAM_*                                */
    uint32_t     epoch;
    uint64_t     sample_pos;
    uint64_t     capture_us; /* on the source's clock                         */
    uint64_t     playout_us; /* on this device's clock; 0 without bit1        */
    size_t       audio_len;
    uint8_t      audio[WFAS3_MAX_AUDIO_PAYLOAD];
    /* pairing (13) */
    uint32_t     code;       /* with PAIR_ASK (PIN mode) and PAIR_CODE: 0..999999 */
    uint32_t     ask;        /* with PAIR_ASK: the question to answer         */
    uint32_t     pflags;     /* with PAIR_ASK, PAIRED: WFAS3_PAIR_*           */
    uint8_t      name[32];   /* with PAIR_ASK, PAIRED: the peer's declared name,
                                raw: clean it before showing it              */
    wfas3_beacon beacon;     /* with BEACON                                   */
} wfas3_out;

/* State of a session, for display and diagnostics. */
typedef struct wfas3_info {
    uint32_t     state;          /* 0 free, 1 unconfirmed, 2 up, 3 closing     */
    uint32_t     pair;
    uint32_t     initiator;      /* this device opened the session            */
    uint32_t     source;         /* this device is the source of the main stream */
    uint32_t     microphone;     /* bit3 agreed                               */
    uint32_t     synchronised;   /* bit1 agreed                               */
    wfas3_addr   addr;
    /* the stream this device sends */
    uint32_t     tx_exists;
    uint32_t     tx_on;
    uint32_t     tx_started;     /* the peer accepts it: audio may flow       */
    uint32_t     tx_epoch;
    wfas3_config tx_config;
    uint32_t     peer_received;  /* from the peer's latest REPORT             */
    uint32_t     peer_lost;
    uint32_t     peer_jitter_us;
    uint32_t     peer_buffer_ms;
    /* the stream this device receives */
    uint32_t     rx_exists;
    uint32_t     rx_on;
    uint32_t     rx_wanted;
    uint32_t     rx_epoch;
    wfas3_config rx_config;
    uint32_t     rx_received;
    uint32_t     rx_lost;
    uint32_t     rx_jitter_us;
    uint32_t     time_valid;     /* with bit1                                 */
    int64_t      offset_us;      /* source clock minus this clock             */
    uint32_t     delay_ms;       /* playback delay D                          */
    /* channel */
    uint32_t     rekeys;         /* completed rekeys                          */
    uint32_t     rekeying;       /* a rekey is in progress                    */
    uint32_t     path_check;     /* a path check is running                   */
    uint32_t     silent;         /* nothing heard for 1 s                     */
    uint64_t     silence_ms;     /* time since the peer was last heard        */
} wfas3_info;

/* ---------------------------------------------------------------------------
 * The structures below are private to the core. They are defined here only so
 * that the caller can reserve memory for a wfas3_device. Do not read or write
 * their fields.
 * ------------------------------------------------------------------------- */

typedef struct wfas3_sym {
    uint8_t  h[32];
    uint8_t  ck[32];
    uint8_t  k[32];
    uint64_t n;
    uint32_t has_key;
} wfas3_sym;

typedef struct wfas3_rng {
    wfas_tp_drbg drbg;
    uint32_t     ready;
} wfas3_rng;

typedef struct wfas3_pair {
    uint32_t used;
    uint32_t flags;
    uint32_t perms;
    uint32_t is_low;
    uint32_t has_mine;
    uint32_t has_theirs;
    uint32_t held_current;
    uint32_t held_group;
    uint8_t  s_priv[32];
    uint8_t  s_pub[32];
    uint8_t  peer_pub[32];
    uint8_t  k_fix[32];
    uint8_t  psk[3][32];        /* cur, mine, theirs                          */
    uint8_t  peer_entry[32];
    uint8_t  own_entry_held[32];
    uint8_t  k_tag_in[32];
    uint8_t  k_mac1_held[32];
    uint64_t ectr_seen;
    uint64_t ectr_used;
    uint64_t probe_seen;
    uint8_t  rep_fp[4][16];     /* base, last, mine_c, resp_p                 */
    uint64_t rep_seq[4];
    uint32_t rep_has[4];
    uint64_t seq_hi;
    uint8_t  name[32];
    uint64_t ectr_send;         /* RAM only from here                         */
    uint64_t next_cookie_ms;
    uint64_t next_stale_ms;
    uint64_t next_hs_ms;
    uint64_t next_store_ms;     /* earliest write of seen values alone (7.3)  */
    uint64_t cookie_hold_ms;
    uint64_t next_probe_ms;     /* earliest next Probe to this pair           */
    uint64_t next_preply_ms;    /* earliest next answer to a fresh Probe      */
    uint64_t next_pstale_ms;    /* earliest next answer to a stale Probe      */
    uint32_t seen_dirty;        /* ectr_seen, ectr_used or probe_seen changed
                                   since the record was last written          */
    uint32_t seen_valid;
    wfas3_addr seen_addr;       /* last seen address of the peer (12)         */
    uint8_t  k_probe_in[32];
    uint64_t unused_until_ms;   /* erased then without a session handshake (13.4) */
    uint64_t guest_seen_ms;     /* guest: the last time it had a session (13.6) */
} wfas3_pair;

typedef struct wfas3_search {   /* a search for the peer of a pair (12)       */
    uint32_t state;             /* 0 free, 1 searching                        */
    uint32_t pair;
    uint32_t tries;             /* Probes sent                                */
    uint32_t next_slot;
    uint64_t next_ms;
    uint32_t valid[3];          /* Probes of the last 4 s                     */
    uint64_t sent_ms[3];
    uint8_t  nonce[3][16];
    uint8_t  ctr[3][8];
} wfas3_search;

typedef struct wfas3_attempt {
    uint32_t   state;
    uint32_t   pair;
    uint32_t   index;
    uint32_t   psk_slot;
    uint64_t   ectr;
    uint64_t   started_ms;
    uint64_t   last_tx_ms;
    wfas3_addr addr;
    uint8_t    request_id[16];
    uint8_t    pair_tag[16];
    uint8_t    config[16];
    uint8_t    e_priv[32];
    uint8_t    kem_sk[WFAS_TP_MLKEM_SK];
    wfas3_sym  sym;
} wfas3_attempt;

typedef struct wfas3_window {
    uint32_t any;
    uint64_t max;
    uint64_t bits[64];          /* 4096 counters                              */
} wfas3_window;

typedef struct wfas3_tx {       /* the stream this device sends               */
    uint32_t exists;
    uint32_t on;
    uint32_t epoch;
    uint8_t  config[16];
    uint32_t announce;          /* PARAMS or MIC_STATE until PARAMS_ACK       */
    uint32_t started;
    uint64_t start_ms;
    uint64_t consent_ms;        /* consent valid until                        */
    uint32_t tresp;             /* a TIME_RESP is due                         */
    uint64_t tresp_t1;
    uint64_t tresp_t2;
    uint32_t rep_received;      /* the peer's latest REPORT                   */
    uint32_t rep_lost;
    uint32_t rep_jitter_us;
    uint32_t rep_buffer_ms;
} wfas3_tx;

typedef struct wfas3_rx {       /* the stream this device receives            */
    uint32_t exists;
    uint32_t on;
    uint32_t epoch;
    uint8_t  config[16];
    uint32_t want;
    uint32_t ack;               /* a PARAMS_ACK is due                        */
    uint32_t ack_epoch;
    uint32_t got_audio;         /* audio of the current epoch delivered       */
    uint64_t ep_audio_ms;       /* arrival of the latest audio delivered      */
    uint32_t heard;             /* some audio of this stream has arrived      */
    uint64_t audio_ms;          /* arrival of the latest audio of any epoch   */
    uint64_t last_capture_us;
    uint32_t received;
    uint32_t lost_done;         /* lost in epochs that ended                  */
    uint32_t ep_delivered;
    uint64_t first_pos;
    uint64_t high_pos;
    uint32_t have_prev;
    uint64_t prev_arr_us;
    uint64_t prev_cap_us;
    uint32_t jitter_us;
    uint32_t buffer_ms;
    uint32_t time_valid;
    int64_t  offset_us;
    int64_t  best_rtt_us;
    uint64_t sync_start_ms;
    uint64_t last_update_us;
    uint64_t next_treq_ms;
    uint64_t treq_t1[4];
    uint32_t treq_valid;
    uint32_t treq_next;
    uint32_t d_ms;
    uint64_t last_late_ms;
    uint64_t last_dec_ms;
} wfas3_rx;

typedef struct wfas3_rekey {
    uint32_t     state;
    uint32_t     id;
    uint32_t     last_id;
    uint32_t     count;           /* completed rekeys                         */
    uint64_t     started_ms;
    uint32_t     bits_rx;         /* chunks held of the message received      */
    uint32_t     bits_ack;        /* chunks of the message sent acknowledged  */
    uint32_t     next_chunk;
    uint32_t     new_index;       /* this device's receive index after it     */
    uint32_t     peer_new_index;
    uint8_t      buf[1344];       /* message sent or received                 */
    uint8_t      e_priv[32];
    uint8_t      kem_seed[64];    /* the ML-KEM key is derived again from it  */
    wfas3_sym    sym;
    uint8_t      k_send_new[32];
    uint8_t      k_recv_new[32];
    uint8_t      psk_new[32];
    wfas3_window win_new;
} wfas3_rekey;

typedef struct wfas3_mrx {      /* this device as a receiver of a group (14)  */
    uint32_t     joined;
    uint32_t     group;
    uint32_t     slot;
    uint32_t     epoch;
    uint8_t      key[32];
    uint8_t      tag_key[32];
    uint32_t     old_valid;       /* the previous epoch, kept 2 s             */
    uint32_t     old_epoch;
    uint64_t     old_until_ms;
    uint8_t      old_key[32];
    uint8_t      old_tag_key[32];
    uint64_t     floor;           /* counters below it are older than the join */
    wfas3_addr   addr;
    wfas3_window win;
    uint32_t     ack_due;         /* a GROUP_ACK is due                       */
    uint32_t     ack_epoch;
    uint32_t     leave_left;      /* GROUP_LEAVE in this many control packets */
    uint32_t     leave_group;
    uint32_t     nack_due;
    uint64_t     nack_first;
    uint32_t     nack_bits;
    uint32_t     dedup_n;
    uint32_t     dedup_next;
    uint32_t     dedup_epoch[WFAS3_MCAST_DEDUP];
    uint64_t     dedup_pos[WFAS3_MCAST_DEDUP];
} wfas3_mrx;

typedef struct wfas3_session {
    uint32_t     state;
    uint32_t     pair;
    uint32_t     initiator;
    uint32_t     source;
    uint8_t      flags;           /* agreed flags with bit0 set               */
    uint32_t     recv_index;
    uint32_t     send_index;
    uint64_t     seq;
    uint64_t     send_ctr;
    uint64_t     created_ms;
    uint64_t     next_ctl_ms;
    uint64_t     last_rx_ms;
    uint32_t     silent;
    wfas3_window win;
    uint8_t      k_send[32];
    uint8_t      k_recv[32];
    uint8_t      rekey_psk[32];
    uint8_t      fp[16];
    uint8_t      config[16];      /* agreed, from this device's point of view */
    uint32_t     old_valid;       /* receive key before the last rekey, kept a
                                     short while for late packets           */
    uint32_t     recv_index_old;
    uint64_t     old_until_ms;
    uint8_t      k_recv_old[32];
    wfas3_window win_old;
    wfas3_addr   addr;
    uint32_t     path_active;
    uint32_t     path_tries;
    uint64_t     path_next_ms;
    uint8_t      path_data[8];
    wfas3_addr   path_addr;
    uint32_t     path_resp;
    uint8_t      path_resp_data[8];
    uint32_t     bye_left;
    uint32_t     bye_reason;
    uint32_t     forget;
    uint32_t     ekack;
    uint8_t      ekack_hash[8];
    uint64_t     next_rekey_ms;
    wfas3_tx     tx;
    wfas3_rx     rx;
    wfas3_rekey  rk;
    wfas3_mrx    mrx;
} wfas3_session;

typedef struct wfas3_member {   /* a receiver of this device's group (14)      */
    uint32_t     used;
    uint32_t     session;
    uint64_t     seq;             /* of that session: the same session         */
    uint32_t     pair;
    uint32_t     acked;           /* acked_epoch is valid                      */
    uint32_t     acked_epoch;
    uint64_t     join_next_ms;
    uint32_t     resend;          /* resend_first and resend_bits are valid    */
    uint64_t     resend_first;
    uint32_t     resend_bits;
} wfas3_member;

typedef struct wfas3_hist {     /* a packet sent to the group, for NACK         */
    uint32_t     valid;
    uint64_t     counter;
    uint64_t     sample_pos;
    uint64_t     capture_us;
    uint8_t      data[WFAS3_MAX_MCAST_PAYLOAD];
} wfas3_hist;

typedef struct wfas3_group {    /* the group this device sends (14)            */
    uint32_t     open;
    uint32_t     id;
    uint8_t      config[16];      /* with bit0 and bit2                        */
    wfas3_addr   addr;
    uint64_t     counter;         /* the next one                              */
    uint32_t     epoch;           /* the key epoch in use                      */
    uint8_t      key[32];
    uint8_t      tag_keys[WFAS3_GROUP_SLOTS][32];
    uint64_t     epoch_ms;
    uint32_t     pend;            /* a new epoch is being given to the members */
    uint32_t     pend_epoch;
    uint8_t      pend_key[32];
    uint8_t      pend_tag_keys[WFAS3_GROUP_SLOTS][32];
    uint64_t     pend_ms;
    wfas3_member m[WFAS3_GROUP_SLOTS];
    wfas3_hist   h[WFAS3_MCAST_HISTORY];
    uint32_t     h_next;
} wfas3_group;

typedef struct wfas3_pwin {     /* the pairing window of a server (13.2)      */
    uint32_t   open;
    uint32_t   kind;
    uint32_t   mode;              /* on the wire: 1 code or label, 2 PIN        */
    uint32_t   used;              /* a pairing completed: beacon state 1        */
    uint32_t   ask;
    uint32_t   perms;
    uint32_t   failed;            /* attempts that ended without a pair (13.7)  */
    uint32_t   ask_next;          /* id of the next question                    */
    uint64_t   opened_ms;
    uint64_t   next_beacon_ms;
    uint32_t   prs_len;
    uint8_t    prs[16];           /* secret, or the PIN digits                  */
    uint8_t    w_priv[32];
    uint8_t    w_pub[32];
    uint8_t    nonce[16];
    uint8_t    k_f[32];           /* filter key                                 */
    uint8_t    k_b[32];           /* beacon tag key, mode 1                     */
    uint8_t    name[32];
    uint64_t   s1_win_ms;         /* step-1 admission (13.7)                    */
    uint32_t   s1_count;
    wfas3_addr src[8];
    uint64_t   src_served_ms[8];
    uint64_t   src_seen_ms[8];
    uint32_t   src_waiting[8];
    uint32_t   src_used[8];
} wfas3_pwin;

typedef struct wfas3_pctx {     /* a client served by this server (13.4)      */
    uint32_t   state;
    uint32_t   asked;             /* the question was put to the user           */
    uint32_t   ask_id;
    uint32_t   decided;           /* the answer is known: step 7 is due         */
    uint32_t   decision;
    uint32_t   flags;             /* asked for in step 3                        */
    uint32_t   perms;
    uint32_t   guest;
    uint32_t   code;
    uint64_t   wait_ms;           /* start of the current wait                  */
    uint64_t   next_tx_ms;
    wfas3_addr addr;
    uint8_t    sid[8];
    uint8_t    rx_hash[32];       /* the last step received, to know its copies */
    wfas3_sym  sym;
    uint8_t    h3[32];
    uint8_t    h[32];
    uint8_t    e_priv[32];
    uint8_t    s_priv[32];        /* new pair static key                        */
    uint8_t    peer_s[32];
    uint8_t    k_c2s[32];
    uint8_t    k_s2c[32];
    uint8_t    k_fix[32];
    uint8_t    psk0[32];
    uint8_t    fp[16];
    uint8_t    n_s[32];
    uint8_t    commit_c[32];
    uint8_t    name[32];          /* the client's declared name                 */
    uint8_t    entry[32];         /* entry key sent in step 7                   */
    size_t     tx_len;
    uint8_t    tx[1296];          /* the step sent, retransmitted unchanged     */
    uint8_t    tx6[80];           /* step 6, for copies of step 5 after it      */
} wfas3_pctx;

typedef struct wfas3_pcli {     /* this device pairing as a client (13.4)     */
    uint32_t   state;
    uint32_t   kind;
    uint32_t   mode;
    uint32_t   flags;
    uint32_t   perms;
    uint32_t   replace;
    uint32_t   confirmed;         /* PIN mode: the user said the codes match    */
    uint32_t   answered;          /* a valid step 2 arrived (13.2 warning)      */
    uint32_t   bad2;              /* a step 2 did not open                      */
    uint32_t   guest;
    uint32_t   peer_perms;
    uint32_t   code;
    uint64_t   started_ms;
    uint64_t   wait_ms;
    uint64_t   bad2_ms;
    uint64_t   next_tx_ms;
    wfas3_addr addr;
    uint32_t   prs_len;
    uint8_t    prs[16];
    uint8_t    fp_w[16];          /* mode 1: fingerprint of W from the code     */
    uint8_t    k_f[32];
    uint8_t    k_b[32];
    uint8_t    nonce[16];
    uint8_t    sid[8];
    uint8_t    ya[32];            /* CPace scalar                               */
    uint8_t    ya_pub[32];
    uint8_t    e_priv[32];
    uint8_t    e_pub[32];
    uint8_t    kem_sk[WFAS_TP_MLKEM_SK];
    wfas3_sym  sym;
    uint8_t    h3[32];
    uint8_t    h[32];
    uint8_t    s_priv[32];
    uint8_t    peer_s[32];
    uint8_t    k_c2s[32];
    uint8_t    k_s2c[32];
    uint8_t    k_fix[32];
    uint8_t    psk0[32];
    uint8_t    fp[16];
    uint8_t    n_c[32];
    uint8_t    commit_s[32];
    uint8_t    name[32];
    uint8_t    peer_name[32];
    uint8_t    peer_entry[32];
    uint8_t    own_entry[32];
    size_t     tx_len;
    uint8_t    tx[1368];
} wfas3_pcli;

typedef struct wfas3_device {
    wfas3_rng     rng;
    uint32_t      entropy_fresh;
    uint32_t      started;
    uint64_t      now_us;
    uint64_t      now_ms;
    uint64_t      ectr_limit;
    uint64_t      ectr_high;
    uint64_t      cookie_next_ms;
    uint64_t      tag_win_ms;
    uint64_t      stale_win_ms;
    uint64_t      hs_win_ms;
    uint64_t      rekey_gap_ms;
    uint64_t      probe_win_ms;
    uint64_t      pstale_win_ms;
    uint32_t      tag_count;
    uint32_t      stale_count;
    uint32_t      hs_count;
    uint32_t      probe_count;
    uint32_t      pstale_count;
    uint32_t      cookie_ready;
    uint32_t      poll_next;
    uint8_t       entry_key[32];
    uint8_t       entry_hash[8];
    uint8_t       k_mac1[32];
    uint8_t       cookie_secret[2][32];
    uint8_t       suggest[16];
    wfas3_policy  policy;
    wfas3_pair    pairs[WFAS3_MAX_PAIRS];
    wfas3_attempt attempts[WFAS3_MAX_ATTEMPTS];
    wfas3_search  finds[WFAS3_MAX_FINDS];
    wfas3_session sessions[WFAS3_MAX_SESSIONS];
    uint32_t      label_ok;       /* headless: label loaded                   */
    uint8_t       label_priv[32]; /* pairing identity key                     */
    uint8_t       label_secret[16];
    uint32_t      beacons_on;     /* report PIN-mode beacons                  */
    uint32_t      replace_pending;/* pair removed at the next poll (13.5)     */
    wfas3_pwin    win;
    wfas3_pctx    pctx[WFAS3_PAIR_CTX];
    wfas3_pcli    pcli;
    wfas3_group   grp;
} wfas3_device;

/* ---------------------------------------------------------------------------
 * Functions
 * ------------------------------------------------------------------------- */

/* Classify a received datagram by its 4 fixed bytes and its exact length.
 * On WFAS3_OK, *type holds one of WFAS3_T_*. Anything else is WFAS3_E_DROP. */
wfas3_status wfas3_classify(const uint8_t *dgram, size_t len, uint8_t *type);

/* Power-on self-test of every primitive. A device MUST call it once at start
 * and MUST NOT use the library if it does not return WFAS3_OK. */
wfas3_status wfas3_selftest(void);

/* Start the core. seed is WFAS3_SEED_LEN bytes from the system generator.
 * record is the stored device record (record_valid = 1) or an empty buffer
 * (record_valid = 0: a new entry key is created). On return record holds the
 * record to store; it MUST be stored before any other call. */
wfas3_status wfas3_device_init(wfas3_device *dev, const uint8_t *seed,
                               uint8_t *record, uint32_t record_valid, uint64_t now_us);

/* Current device record (WFAS3_DEVICE_RECORD_LEN bytes), to store whenever an
 * output carries WFAS3_EV_STORE_DEVICE. */
wfas3_status wfas3_device_export(const wfas3_device *dev, uint8_t *record);

/* Give the core WFAS3_ENTROPY_LEN bytes from the system generator. */
wfas3_status wfas3_add_entropy(wfas3_device *dev, const uint8_t *entropy);

/* Give the core the monotonic time in microseconds. A value lower than one
 * already given is ignored. */
wfas3_status wfas3_set_time(wfas3_device *dev, uint64_t now_us);

/* The latest time, in microseconds, at which wfas3_poll() must be called. */
uint64_t wfas3_next_deadline(const wfas3_device *dev);

wfas3_status wfas3_set_policy(wfas3_device *dev, const wfas3_policy *policy);

/* Load or save the record of one pair (WFAS3_PAIR_RECORD_LEN bytes). */
wfas3_status wfas3_pair_import(wfas3_device *dev, uint32_t pair, const uint8_t *record);
wfas3_status wfas3_pair_export(const wfas3_device *dev, uint32_t pair, uint8_t *record);

/* Remove a pair (specification 9.4): its sessions close with FORGET and BYE,
 * the device gets a new entry key (WFAS3_EV_STORE_DEVICE) and the record must
 * be erased (WFAS3_EV_PAIR_ERASED). out may carry the first FORGET packet. */
wfas3_status wfas3_pair_remove(wfas3_device *dev, uint32_t pair, wfas3_out *out);

/* Look for the peer of a pair (specification 12). on = 1 starts a search:
 * a Probe now, then one every 2 s, at most 15. When an address of the peer
 * was seen in this run, Probes 1, 3, 5... go there and the others to the
 * local broadcast address; otherwise all go to the broadcast address. A
 * pair never gets more than one Probe every 2 s, so the first one may come
 * from wfas3_poll() instead of out. The search ends with WFAS3_EV_FOUND
 * (out.addr: the candidate address for wfas3_connect(), authenticated by
 * nothing but the handshake) or WFAS3_EV_NOT_FOUND. on = 0 ends it without
 * an event. WFAS3_E_STATE if a search for the pair is already running,
 * WFAS3_E_FULL if WFAS3_MAX_FINDS are. */
wfas3_status wfas3_find(wfas3_device *dev, uint32_t pair, uint32_t on, wfas3_out *out);

/* Open a session with a pair: produces the CookieRequest. */
wfas3_status wfas3_connect(wfas3_device *dev, uint32_t pair, const wfas3_addr *to,
                           const wfas3_config *config, wfas3_out *out);

/* Timers and control packets. Call until it returns WFAS3_OK with
 * out->len == 0 and no event. */
wfas3_status wfas3_poll(wfas3_device *dev, wfas3_out *out);

/* Process one received datagram. */
wfas3_status wfas3_receive(wfas3_device *dev, const uint8_t *dgram, size_t len,
                           const wfas3_addr *from, wfas3_out *out);

/* Send one packet of the stream this device sends. len must be exactly the
 * payload_bytes of its configuration; capture_us is on the clock given to
 * wfas3_set_time(). Allowed only between WFAS3_EV_STREAM_START and
 * WFAS3_EV_STREAM_STOP; WFAS3_E_STATE otherwise, and also when consent has
 * lapsed or the counter is spent: wfas3_poll() then reports it. out carries
 * nothing but the audio packet. */
wfas3_status wfas3_send_audio(wfas3_device *dev, uint32_t session, uint64_t sample_pos,
                              uint64_t capture_us, const uint8_t *data, size_t len,
                              wfas3_out *out);

/* Whether this device wants the stream it receives (consent, 10.4). */
wfas3_status wfas3_stream_want(wfas3_device *dev, uint32_t session, uint32_t want);

/* New configuration of the stream this device sends. The source of the main
 * stream announces new parameters; the receiver of the main stream switches
 * its microphone on (bit3 agreed and WFAS3_PERM_MICROPHONE in its record).
 * config->flags is ignored: the core sets it. The stream stops at once,
 * without an event: audio waits for a new WFAS3_EV_STREAM_START. */
wfas3_status wfas3_stream_params(wfas3_device *dev, uint32_t session, const wfas3_config *config);

/* Switch the microphone off. */
wfas3_status wfas3_mic_off(wfas3_device *dev, uint32_t session);

/* The application could not play a packet in time (bit1): late by late_us. */
wfas3_status wfas3_audio_late(wfas3_device *dev, uint32_t session, uint64_t late_us);

/* The playback buffer of the application, reported to the peer without bit1. */
wfas3_status wfas3_set_buffer_ms(wfas3_device *dev, uint32_t session, uint32_t ms);

/* Close a session that is up (after WFAS3_EV_SESSION_UP) with a BYE reason:
 * WFAS3_BYE_NORMAL, WFAS3_BYE_PREEMPTED or WFAS3_BYE_GUEST_ENDED. out carries
 * WFAS3_EV_SESSION_DOWN and the first BYE packet; two more follow from
 * wfas3_poll(). */
wfas3_status wfas3_session_close(wfas3_device *dev, uint32_t session, uint32_t reason,
                                 wfas3_out *out);

wfas3_status wfas3_session_info(const wfas3_device *dev, uint32_t session, wfas3_info *info);

/* -- Pairing (specification 13) ---------------------------------------------
 * A server opens a window; a client pairs with it. A device is not server
 * and client at the same time (WFAS3_E_STATE). */

/* Pairing code as text: 13 groups of 4 Crockford base32 characters joined by
 * '-' (qr = 0, cap >= WFAS3_CODE_TEXT_LEN), or "WFAS3:" and the 52
 * characters for a QR code (qr = 1, cap >= WFAS3_CODE_QR_LEN). */
wfas3_status wfas3_code_format(const uint8_t *code, uint32_t qr, char *text, size_t cap);

/* Read a typed or scanned code: an optional "WFAS3:", case ignored, '-' and
 * spaces ignored, O read as 0, I and L as 1. WFAS3_E_ARG unless 52 characters
 * with the right check bits. */
wfas3_status wfas3_code_parse(const char *text, size_t len, uint8_t *code);

/* A declared name for display: invalid UTF-8 becomes '?', control, format
 * and direction characters are removed. Always NUL-terminated. */
void wfas3_name_clean(const uint8_t *name, char *text);

/* Headless devices. wfas3_label_create makes the pairing identity key and
 * the label secret at the factory (WFAS3_LABEL_RECORD_LEN bytes, kept across
 * resets); wfas3_label_load gives them to the core at every start;
 * wfas3_label_code is the code printed on the label. */
wfas3_status wfas3_label_create(wfas3_device *dev, uint8_t *record);
wfas3_status wfas3_label_load(wfas3_device *dev, const uint8_t *record);
wfas3_status wfas3_label_code(const wfas3_device *dev, uint8_t *code);

/* Server: open a window (120 s, one pairing). code receives the code to show
 * (WFAS3_CODE_LEN bytes) or, in PIN mode, the PIN (WFAS3_PIN_LEN digits).
 * out carries the first beacon; the next ones come from wfas3_poll(). The
 * window ends with WFAS3_EV_WINDOW_CLOSED. */
wfas3_status wfas3_window_open(wfas3_device *dev, const wfas3_window_req *req, uint8_t *code,
                               wfas3_out *out);
wfas3_status wfas3_window_close(wfas3_device *dev, wfas3_out *out);

/* Server: the user's answer to the question out.ask of WFAS3_EV_PAIR_ASK.
 * yes = 1 pairs with perms (WFAS3_PERM_*; the microphone only if the client
 * asked); guest = 1 makes it a guest pair even if the client did not ask.
 * out may carry step 7. WFAS3_E_STATE if the question is no longer open. */
wfas3_status wfas3_pair_answer(wfas3_device *dev, uint32_t ask, uint32_t yes, uint32_t perms,
                               uint32_t guest, wfas3_out *out);

/* Number of attempts of the open window that ended without a pair (13.7),
 * or WFAS3_NONE if no window is open. */
uint32_t wfas3_window_failed(const wfas3_device *dev);

/* Client: report PIN-mode beacons as WFAS3_EV_BEACON while on = 1. */
wfas3_status wfas3_beacons(wfas3_device *dev, uint32_t on);

/* Client: pair. With a code or label the core waits for the beacon of that
 * code and starts by itself; with a PIN it starts at once with the chosen
 * beacon, so out may carry step 1. The pairing ends with WFAS3_EV_PAIRED or
 * WFAS3_EV_PAIR_FAILED. */
wfas3_status wfas3_pair_start(wfas3_device *dev, const wfas3_pair_req *req, wfas3_out *out);

/* Client, PIN mode: yes = 1 when the user says that the code shown here is
 * the code shown on the server. Until then step 8 is not sent. */
wfas3_status wfas3_pair_confirm(wfas3_device *dev, uint32_t yes, wfas3_out *out);

/* Client: stop pairing, without an event. */
wfas3_status wfas3_pair_cancel(wfas3_device *dev);

/* -- Multicast (specification 14) -------------------------------------------
 * A source opens one group with a fixed configuration (bit2 set, payload at
 * most WFAS3_MAX_MCAST_PAYLOAD) and a multicast address. A receiver joins by
 * a session with bit2 (wfas3_connect from either side): when it wants the
 * stream, the source gives it the group keys and it gets
 * WFAS3_EV_GROUP_JOIN; it leaves with wfas3_stream_want(0), and the source
 * sends it away with WFAS3_EV_GROUP_LEAVE. Group audio arrives as
 * WFAS3_EV_AUDIO of that session; a packet lost on the group may arrive in
 * the session instead, never twice. A receiver also leaves the group when
 * its session ends. */

/* Source: open the group. config is the configuration of every session of
 * the group, seen from the source (WFAS3_CF_SOURCE and WFAS3_CF_MULTICAST
 * set); group_addr is an IPv6 or IPv4 multicast address and port. */
wfas3_status wfas3_group_open(wfas3_device *dev, const wfas3_config *config,
                              const wfas3_addr *group_addr);

/* Source: close the group; every receiver is told to leave. */
wfas3_status wfas3_group_close(wfas3_device *dev);

/* Source: one audio packet to the group, exactly payload_bytes long. Sessions
 * of the group carry no audio of their own: wfas3_send_audio refuses them.
 * WFAS3_E_STATE while no receiver has consent. */
wfas3_status wfas3_group_send(wfas3_device *dev, uint64_t sample_pos, uint64_t capture_us,
                              const uint8_t *data, size_t len, wfas3_out *out);

/* Source: receivers that hold the group key now. */
uint32_t wfas3_group_members(const wfas3_device *dev);

#ifdef __cplusplus
}
#endif
#endif
