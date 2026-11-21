# WFAS v3 protocol specification

Draft 6, 17 November 2026. Status: EXPERIMENTAL.

This document fixes every wire format of WFAS v3. It is derived from "WFAS v3 -
progetto del protocollo" revision 4 and changes it where a review found a
weakness; every change is listed in Appendix A. WFAS v2 is a separate protocol
and is not modified by anything in this document.

No protocol is unbreakable. The goal of v3 is: no known attack on the
confidentiality and authenticity of audio and control data by anyone who does
not hold the keys of a valid pair. Availability against an attacker on the same
network cannot be guaranteed. Until the items of Appendix B are closed (formal
model, test vectors, external audit) v3 MUST be labelled experimental.

The key words MUST, MUST NOT, SHOULD, MAY are to be read as in RFC 2119.

## 1. Conventions

- All integers are unsigned, fixed width, big-endian. `u8`, `u16`, `u32`, `u64`.
- `a || b` is concatenation. `x[i..j]` is bytes i (inclusive) to j (exclusive).
- A device is one installation of the library. A pair is the association of two
  devices. Each pair has a low side and a high side: the low side is the one
  whose pair static public key is smaller when the two 32-byte keys are compared
  as big-endian numbers. Direction `LH` means low to high, `HL` high to low.
- Every datagram has an exact length for its type. A datagram of any other
  length is dropped before any computation.
- "Drop" means: no reply, no state change except rate counters.
- Time is a 64-bit monotonic clock in microseconds that keeps counting while
  the system is suspended. The core takes the highest value it has been
  given; a value that goes backwards is ignored. Durations in this document
  are measured on it; the capture times of audio come from the same clock.

## 2. Primitives

One suite, no negotiation. If a primitive falls, a new protocol version with a
new prologue is defined.

| Use | Primitive |
| --- | --- |
| DH | X25519 (RFC 7748). An all-zero output aborts the operation. |
| KEM | ML-KEM-768 (FIPS 203). Public key 1184, ciphertext 1088, secret 32 bytes. Encapsulation keys are checked as in FIPS 203 section 7.2 before use. |
| AEAD | ChaCha20-Poly1305 (RFC 8439). Nonce = 4 zero bytes followed by a u64; in a multicast packet the 4 bytes are the key epoch (14.1). |
| Hash | SHA-256. SHA-512 is used only inside CPace. |
| MAC | HMAC-SHA256. `MAC16(k, m)` and `MAC8(k, m)` are the first 16 and 8 bytes. |
| KDF | HKDF-SHA256 (RFC 5869). |
| PAKE | CPace, draft-irtf-cfrg-cpace-21, suite CPACE-X25519-SHA512. |
| RNG | HMAC-DRBG (SP 800-90A, SHA-256) inside the core, reseeded with system entropy at start and at every handshake, rekey and pairing. |

Notation:

- `KDF(k, label)` = HKDF-Expand(PRK = k, info = "WFAS3 " || label, L = 32), for a
  uniformly random 32-byte k, or the 16-byte secret or window nonce of
  section 13 (HMAC accepts both lengths; with L = 32 it is one HMAC of
  info || 0x01). `KDF16` keeps the first 16 bytes. Labels are ASCII.
- `Seal(k, n, ad, pt)` = ChaCha20-Poly1305 ciphertext followed by the 16-byte
  tag, with nonce `00 00 00 00 || u64(n)`. `Open` is the inverse and fails closed.
- All comparisons of MACs, tags and keys use one constant-time function.

### 2.1 Handshake state

The handshakes use the Noise symmetric state (h, ck, k, n), 32 bytes each
except n:

- `Init(name)`: h = SHA-256(name); ck = h; k = empty; then `MixHash(prologue)`
  with prologue = ASCII "WFAS3" || 0x01.
- `MixHash(d)`: h = SHA-256(h || d).
- `MixKey(ikm)`: (ck, k) = HKDF(salt = ck, ikm, info = empty, L = 64); n = 0.
- `MixKeyAndHash(ikm)`: (ck, t, k) = HKDF(salt = ck, ikm, info = empty, L = 96);
  MixHash(t); n = 0.
- `EncryptAndHash(pt)`: ct = Seal(k, n, h, pt); n = n + 1; MixHash(ct); output ct.
- `DecryptAndHash(ct)`: pt = Open(k, n, h, ct); n = n + 1; MixHash(ct).

Deviations from the Noise specification, all deliberate:

1. Nonces are big-endian (Noise uses little-endian for ChaChaPoly).
2. In the KK pattern the token `e1` comes before `es`, not after it as the hfs
   modifier prescribes. `e1` is still encrypted, because `psk0` and `e` have
   already produced a key. This lets a responder test a psk with one AEAD
   decryption and no public-key operation.
3. Output keys are derived with labels (section 6.3) instead of `Split()`.
4. Message headers are added to the transcript with `MixHash`.
5. `mac1`, `mac2`, pair tags and the `Resp` MAC are outside Noise.

## 3. Datagram header and types

UDP port 14114 (provisional; to be requested from IANA, see section 18).
`WFAS3_MAX_DATAGRAM` = 1400 bytes of UDP payload. A LAN with MTU 1500 is
required; a 1280-byte tunnel is not supported.

Every datagram starts with 4 bytes:

```
0  0xA3   v3 marker (no v2 packet starts with this byte)
1  type
2  0x00   any other value: drop
3  0x00   any other value: drop
```

| Type | Name | Direction | Length |
| --- | --- | --- | --- |
| 0x01 | Probe | either | 60 |
| 0x02 | ProbeReply | either | 60 |
| 0x03 | PairBeacon | pairing server, broadcast | 72 |
| 0x10 | Init | initiator -> responder | 1368 |
| 0x11 | Resp | responder -> initiator | 1228 |
| 0x12 | CookieReply | responder -> initiator | 64 |
| 0x13 | Pair | both, 9 steps | per step, section 13.4 |
| 0x14 | CookieRequest | initiator -> responder | 64 |
| 0x15 | Reject | responder -> initiator | 48 |
| 0x20 | Transport | both | 160, or the stream packet length of the session |
| 0x30 | Multicast | source -> group | stream payload + 187 |

Classification is by the exact type byte. Unknown types are dropped.

## 4. Roles

- Initiator and responder are handshake roles. Source and receiver are audio
  roles. They are independent; the handshake payloads say who is the source.
- Pairing client (C) and pairing server (S) are roles of section 13 only.

## 5. Stored state

Nothing is stored except keys, counters and settings: no logs, addresses,
dates or durations. All of it is encrypted at rest by the platform and MUST be
excluded from backups; a store restored from an older copy makes the affected
pairs unusable until they are paired again (sections 7.3 and 9).

Per device:

| Field | Size | Notes |
| --- | --- | --- |
| entry key | 32 | random; replaced at every revocation (section 9.4) |
| entry counter reserve | 8 | section 7.3 |
| pairing identity key | 32 | headless devices only: X25519 private key made at the factory, kept across resets |
| label secret | 16 | headless devices only, printed on the label; with the key above, the 48-byte label record |

Per pair, on both sides:

| Field | Size | Notes |
| --- | --- | --- |
| own static private key, peer static public key | 32 + 32 | X25519, made for this pair only |
| K_fix | 32 | fixed pair key, section 6.1 |
| psk cur, mine, theirs | 3 x 32 | section 9.2; mine and theirs may be absent |
| peer entry key | 32 | used for mac1 of what this side sends |
| own entry key held by peer | 32 | section 9.4; random per guest for a guest |
| ectr_seen, ectr_used | 8 + 8 | highest CookieRequest counter seen from the peer, and consumed |
| probe_seen | 8 | highest Probe counter seen from the peer |
| report slots base, mine_c, resp_p, last | 4 x 24 | section 9.3: (fingerprint 16, seq 8) |
| seq_hi | 8 | section 9.3 |
| name, permissions, flags | 32 + 1 + 1 | declared name; receive, microphone; guest, suspect |

A guest pair has the same fields but lives only in RAM: its record is never
written, exported or imported (section 13.6).
A device holds at most 64 pairs.

## 6. Key derivation

### 6.1 From K_fix

For each direction d in {"LH", "HL"}, the direction being that of the request
(CookieRequest, Init, Probe) the key protects or answers:

| Key | Derivation | Used for |
| --- | --- | --- |
| K_tag[d] | KDF(K_fix, "pair tag " d) | pair tag of CookieRequest and Init sent in direction d |
| K_ctr[d] | KDF(K_fix, "entry counter " d) | mask of the entry counter |
| K_probe[d] | KDF(K_fix, "probe " d) | tag of a Probe or ProbeReply sent in direction d |
| K_cookie[d] | KDF(K_fix, "cookie " d) | CookieReply answering a request of direction d |
| K_resp[d] | KDF(K_fix, "resp " d) | MAC of a Resp answering an Init of direction d |
| K_reject[d] | KDF(K_fix, "reject " d) | Reject answering an Init of direction d |
| K_replace | KDF(K_fix, "replace") | proof used when a pair is replaced (section 13.5) |

A packet reflected to its sender fails, because every key is directional.

### 6.2 From an entry key

`K_mac1 = KDF(entry_key, "mac1")`. `mac1` is always computed with the entry key
of the device the datagram is sent to, over all bytes that precede the field.

### 6.3 From a completed handshake

With ck the chaining key after the last token:

| Output | Derivation |
| --- | --- |
| K_i2r, K_r2i | KDF(ck, "transport i2r"), KDF(ck, "transport r2i") |
| fingerprint | KDF16(ck, "session fingerprint") |
| next pair psk | KDF(ck, "pair psk next") (session handshake only) |
| rekey psk | KDF(ck, "rekey psk") |

ck, h and all ephemeral keys are erased as soon as these are computed.

## 7. Entry

Before any public-key operation or per-pair state, an initiator must pass
three filters in this order: `mac1` (stops everyone who lacks an entry key
still in use), the pair tag (stops everyone who is not the other side of a
pair), and a fresh entry counter (stops replays). Then a cookie proves the
source address.

### 7.1 mac1 check

The receiver computes `mac1` with its current entry key. If it matches, every
pair is a candidate. Otherwise it computes it once for each distinct older
entry key that some pair still holds (section 9.4, including guest keys); if one
matches, the candidates are the pairs holding that key. No match: drop.

### 7.2 Pair tag check

For each candidate pair the receiver computes the tag with that pair's key for
the incoming direction and compares. The first match selects the pair. No
match: drop. The number of tag computations per second has one global cap
(section 15).

### 7.3 Entry counter

Each device keeps in RAM, per pair, a 64-bit send counter `ectr`, incremented
before every CookieRequest and Probe it sends to that pair. It MUST never
repeat or decrease, including across restarts: the device stores one reserve
R; at start every pair counter is set to R and R + 65536 is stored; when any
counter reaches the stored value, 65536 is added to it and stored before the
counter is used. A pair loaded while the device runs starts from the highest
counter value the device has used since it started, not from R: starting
from R could repeat a value already sent, which the peer would take as stale
and report as a theft.

On the wire the counter is masked so that it is not a linkable identifier:

```
ctr_masked = ectr XOR MAC8(K_ctr[d], type || nonce16)
```

where type is the datagram type byte and nonce16 the random 16-byte field of
the same datagram (request_id or nonce).

The receiver unmasks after the pair tag has selected the pair, and compares
with `ectr_seen` for a CookieRequest, with `probe_seen` for a Probe:

- greater: fresh. Store the new value in `ectr_seen` or `probe_seen` at once,
  whatever happens to the datagram afterwards, and continue.
- equal: a duplicate of the datagram already accepted. Drop, with no answer of
  any kind.
- lower: stale. Take the stale path of the datagram type (7.5, 12).

The equal case MUST be silent. A network or an attacker can deliver a second
copy of the request of a live attempt; answering it with a stale notice would
let anyone without keys raise a false alert.

A Probe never touches `ectr_seen` or `ectr_used`. The three values are written
to storage whenever the pair record is written. When one of them changes, the
record is written again one minute after its previous write, or at once if
that minute has passed: at most once per minute for these values alone.

### 7.4 CookieRequest (64 bytes)

```
0   A3 14 00 00
4   sender_index   u32   random, chosen by the initiator for this attempt and
                         distinct from every receive index it has in use;
                         becomes its receive index if the session is created
8   request_id     16    random
24  ctr_masked     8
32  pair_tag       16    MAC16(K_tag[d], bytes 0..32)
48  mac1           16    MAC16(K_mac1, bytes 0..48)
```

Responder, in order: length and header; mac1 (7.1); pair tag (7.2); counter
(7.3); attempt rule C3 (9.1); per-pair quota of one fresh CookieRequest every
2 s; then send a CookieReply. A fresh request dropped by C3 or by the quota
also sets `ectr_used = ectr_seen`, so that no cookie is considered issued. The
responder keeps no other state.

### 7.5 CookieReply (64 bytes)

```
0   A3 12 00 00
4   receiver_index u32   sender_index of the request
8   salt           24    random
32  sealed         32    Seal(key, 0, ad, plaintext16)
```

`ad` = bytes 0..32 of this datagram || pair_tag of the request.

Normal reply: key = HKDF-Expand(K_cookie[d], "WFAS3 cookie" || salt, 32) and

```
plaintext16 = cookie = MAC16(S, src_addr16 || src_port || request_id || u64(ectr))
```

where S is the device cookie secret (32 random bytes, replaced every 30 s, the
previous one stays valid; never stored), src_addr16 is the source address of
the request as an IPv6 address (IPv4-mapped for IPv4) and src_port its port.

Stale notice: sent instead when the request took the stale path, at most once
every 30 s per pair and twice per second in total; beyond that the request is
dropped. key = HKDF-Expand(K_cookie[d], "WFAS3 stale" || salt, 32), plaintext16
= 16 zero bytes. It issues no cookie and changes no state.

Initiator: a CookieReply is considered only if receiver_index is that of its
outstanding attempt and the attempt is still waiting for its CookieReply. Once
a cookie has been accepted, every later CookieReply for that attempt is
ignored. It tries the cookie key, then the stale key. A reply that opens with
neither is ignored and the attempt continues. A stale notice for an attempt
that is still waiting means that the peer has accepted, with this pair's keys,
a counter strictly higher than any this device ever sent, or that this
device's store was rolled back: the pair is marked suspect and the application
MUST tell the user in plain words and offer to pair again. It is never
reported as a network failure.

## 8. Handshake

Pattern: Noise KK with psk0 and hfs, one datagram per message. The static keys
are the pair static keys, known from pairing; they never travel.

```
-> s
<- s
...
-> psk, e, e1, es, ss
<- e, ee, ekem1, se
```

Name: "WFAS3_KKpsk0hfs_25519+MLKEM768_ChaChaPoly_SHA256".
After `Init(name)`: MixHash(initiator static public key), MixHash(responder
static public key).

### 8.1 Init (1368 bytes)

```
0     A3 10 00 00
4     sender_index  u32    same as in the CookieRequest
8     request_id    16     same as in the CookieRequest
24    pair_tag      16     MAC16(K_tag[d], bytes 0..24)
40    e             32     initiator ephemeral X25519 public key
72    e1            1200   sealed ML-KEM-768 encapsulation key
1272  payload       64     sealed InitPayload (48 bytes)
1336  mac1          16     MAC16(K_mac1, bytes 0..1336)
1352  mac2          16     MAC16(cookie, bytes 0..1352)
```

Initiator: MixHash(bytes 0..40); MixKeyAndHash(psk) with the psk chosen by rule
I1 (9.2); MixHash(e); MixKey(e); e1 = EncryptAndHash(kem_pk); MixKey(DH(e, s_r));
MixKey(DH(s_i, s_r)); payload = EncryptAndHash(InitPayload).

Responder, in order, dropping at the first failure unless stated:

1. length and header;
2. mac1 (7.1), pair tag (7.2);
3. the pair has an issued, unconsumed cookie: `ectr_used < ectr_seen`, and
   this side has no attempt of its own for the pair (rules 9.1 make this
   impossible; it is checked anyway);
4. mac2, with the cookie recomputed from the current and the previous cookie
   secret, the source address and port of this datagram, request_id and
   `ectr_seen`. An Init that belongs to any request other than the latest one
   fails here;
5. set `ectr_used = ectr_seen`: the cookie is consumed whatever follows;
6. at most one handshake per pair every 2 s, and the global handshake cap;
7. psk trial: for cur, mine, theirs in this order, run the transcript up to
   `e1` and try to open it. None opens: send Reject reason 1, mark the pair
   suspect, alert the user, stop;
8. check the encapsulation key, compute the two DH values, open the payload.
   Failure: drop (the peer holds the psk but not the static key);
9. report check (9.3). Stale: Reject reason 2. Unknown: Reject reason 3. In
   both cases mark the pair suspect, alert the user and stop. A stale report
   comes from an older copy of the peer's record: a thief's copy or a store
   that was rolled back;
10. apply rules R2, R3 (9.2) and the report rules (9.3), write the pair record,
    then send Resp.

### 8.2 Resp (1228 bytes)

```
0     A3 11 00 00
4     sender_index   u32    random, distinct from every receive index the
                            responder has in use; its receive index
8     receiver_index u32    sender_index of the Init
12    e              32     responder ephemeral X25519 public key
44    ekem1          1104   sealed ML-KEM-768 ciphertext
1148  payload        64     sealed RespPayload (48 bytes)
1212  pair_mac       16     MAC16(K_resp[d], request_id || bytes 0..1212)
```

Responder: MixHash(bytes 0..12); MixHash(e); MixKey(e); MixKey(DH(e_r, e_i));
(ct, ss) = Encaps(kem_pk); ekem1 = EncryptAndHash(ct); MixKey(ss);
MixKey(DH(e_r, s_i)); payload = EncryptAndHash(RespPayload). The ephemeral
private key and the ML-KEM randomness are secret and drawn from the generator
separately from sender_index (section 17).

Initiator, in order: length and header; receiver_index is that of the
outstanding attempt; pair_mac; DH(e_i, e_r); open ekem1 and only then
decapsulate; DH(s_i, e_r); open the payload; report check (9.3: any result
other than ok marks the pair suspect, alerts the user and aborts); a
session_seq not greater than its own seq_hi aborts the attempt without an
alert, since an honest responder never sends one; rule I2
(9.2) and report rules; write the pair record; derive the keys of 6.3. A Resp
that fails any check is ignored and the attempt continues until its timeout.

A forged Resp costs one HMAC. A replayed Resp fails pair_mac, which covers the
request_id of the current attempt.

### 8.3 Payloads

InitPayload (48 bytes):

```
0   report_fp   16   fingerprint of the sender's last concluded session
16  report_seq  u64  its sequence number
24  seq_hi      u64  highest session sequence number the sender knows
32  config      16   StreamConfig proposed by the initiator
```

RespPayload (48 bytes): the same, except that bytes 24..32 are `session_seq`,
the sequence number of this session, and config is the responder's decision.

StreamConfig (16 bytes):

```
0   flags          u8   bit0: the sender of this payload is the audio source
                        bit1: synchronised playback (section 10.6)
                        bit2: multicast delivery (section 14)
                        bit3: microphone stream allowed in this session
                        bits 4-7: zero
1   codec          u8   1 = PCM, 2 = Opus
2   channels       u8   1..8
3   bits           u8   PCM: 16 or 24 (little-endian, interleaved). Opus: 16
4   sample_rate    u32  PCM: 44100, 48000, 88200, 96000, 176400, 192000
                        Opus: 48000
8   frame_samples  u16  samples per channel in one packet
10  payload_bytes  u16  exact audio bytes in one packet, 1..1344
12  latency_ms     u16  target latency
14  status         u8   Init: 0. Resp: 0 accepted, 1 busy, 2 not permitted,
                        3 configuration not supported
15  reserved       u8   zero
```

Rules: PCM: payload_bytes = frame_samples * channels * bits / 8, and
frame_samples = min(floor(sample_rate / 200), floor(1344 / (channels * bits / 8))),
that is 5 ms of samples rounded down, or fewer if they do not fit (44100 Hz
gives 220). Opus: frame_samples is 120, 240, 480 or 960; hard CBR,
application RESTRICTED_LOWDELAY, no DTX, no in-band FEC; every packet is
exactly payload_bytes long. With status 0 the responder's config MUST equal
the proposal except for bit0, which is inverted. With status 3 it carries a
configuration the responder would accept; the initiator may open again with
it. With status other than 0 no session is created, but the psk and report
rules still apply. Any reserved bit set, or any inconsistent value, is treated
as status 3 by a responder and aborts the attempt on an initiator.

bit2 is answered with status 3 by a device that does not implement section
14; with bit2, payload_bytes is at most 1213. A receiver that implements it
accepts bit2 from a source. A source accepts a proposal with bit2 only if it
has a group open whose configuration equals the proposal except for bit0,
and fewer than 16 sessions of that group; otherwise it answers status 3 with
the group's configuration (seen from the initiator) if a group is open, and
status 1 if the group is full. A source opens sessions with bit2 only for
its open group. bit3 needs the microphone permission in the pair records of both devices:
the initiator proposes it only if its record has it, the responder answers
status 2 if its record does not.

The Init payload has no forward secrecy against the pair keys; it carries only
capabilities and a fingerprint.

### 8.4 Reject (48 bytes)

```
0   A3 15 00 00
4   receiver_index u32   sender_index of the Init
8   request_id     16
24  sealed         24    Seal(key, 0, bytes 0..24, reason u8 || 7 zero bytes)
```

key = HKDF-Expand(K_reject[d], "WFAS3 reject" || request_id, 32).
Reasons: 1 unknown psk, 2 stale report, 3 unknown report.

A Reject is sent only in answer to an Init that passed step 5. The initiator
considers it only for its outstanding attempt; any reason marks the pair
suspect and alerts the user. Between two honest devices no Reject is ever
produced (section 9.5), so a Reject always means that a third party holds or
has used the pair keys, or that one store was rolled back.

### 8.5 Completion

- The initiator has concluded when the Resp is accepted. Its next transport
  packet, whatever it carries, confirms the key.
- The responder has concluded when it accepts the first transport packet on
  the new index. Until then it sends nothing on the new session and keeps any
  previous session of the pair alive, so a repeated Init obtains nothing.
- A side holds at most one handshake or unconfirmed session context per pair.
  Starting an attempt or accepting an Init destroys the previous one.
- An unconfirmed session is discarded, without any event, 15 s after the Resp
  was sent. The psk and report rules already applied stay as they are.
- Audio starts only after the receiver sends START (section 10.4).

## 9. Pair state machines

### 9.1 Attempts

An attempt is the sequence CookieRequest, CookieReply, Init, Resp on one side.

- C1. Per pair a side has at most one attempt, and has issued at most one
  usable cookie (usable while `ectr_used < ectr_seen`).
- C2. Sending a CookieRequest sets `ectr_used = ectr_seen`: the cookie this
  side had issued to the peer is void.
- C3. A fresh CookieRequest that arrives while this side has an attempt for
  the same pair: the low side drops it; the high side abandons its own
  attempt and answers.
- C4. Abandoning an attempt (timeout, C3, session created by the peer)
  discards its cookie and its Init state; late replies to it are ignored.
- C5. A side SHOULD NOT start an attempt within 5 s of issuing a cookie.

C1 to C4 guarantee that two crossing Inits are never both accepted. A side
that receives a valid Init therefore never has an attempt of its own.

Retry every 5 s; give up after 60 s and tell the user.

### 9.2 Pair psk

Slots: `cur`, and optionally `mine` (derived from a handshake this side
initiated; the peer certainly holds it) and `theirs` (derived from a handshake
this side answered; the peer may hold it). After pairing: cur = psk0.

- I1. The initiator uses `mine` if present, otherwise `cur`.
- R1. The responder tries `cur`, `mine`, `theirs` (8.1 step 7).
- R2. If the slot that opened is not `cur`: cur = that psk; mine and theirs
  are erased.
- R3. theirs = next pair psk of this handshake (6.3). The record is written
  before the Resp is sent.
- I2. On an accepted Resp: if the psk used was not `cur`, then cur = that psk
  and theirs is erased. mine = next pair psk of this handshake. The record is
  written before the first transport packet is sent.

Erased psks are overwritten. The next psk depends on the ephemeral X25519 and
ML-KEM secrets of the handshake, so a stolen psk becomes useless once the two
real devices have promoted a newer one: at the latest at their third handshake
after the theft, at the second if the same side opens both.

### 9.3 Session reports

Each session has a fingerprint (6.3) and a sequence number. Each side keeps
`seq_hi` and four slots (fingerprint, seq):

- `last`: the last session this side concluded; this is what it reports.
- `base`: the last report received from the peer.
- `mine_c`: the latest session this side concluded as initiator.
- `resp_p`: the latest handshake this side answered.

After pairing: base = last = (pair fingerprint, 0), seq_hi = 0, others empty.

Classification of a received report r:

- stale: r.seq < base.seq;
- ok: r equals base, mine_c or resp_p;
- unknown: anything else.

Responder, on an Init that passed the psk trial: classify the report (8.1 step
9). If ok: base = r; erase mine_c if mine_c.seq <= r.seq; session_seq =
max(seq_hi of the Init, own seq_hi) + 1; seq_hi = session_seq; resp_p =
(fingerprint, session_seq). When the session is later confirmed (8.5): last =
resp_p.

Initiator, on a Resp: classify. If ok: base = r; mine_c = last = (fingerprint,
session_seq); erase resp_p; seq_hi = max(seq_hi, session_seq).

An unknown report means the peer concluded a session this device never took
part in: someone else has used this pair's keys.

### 9.4 Entry key rotation

Removing a pair erases its record and generates a new device entry key. The
sessions of the removed pair are closed with BYE reason 2. A guest pair
never held the entry key: its end closes its session with BYE reason 4 and
changes no key (13.6). Every other pair
receives the new key in an ENTRY_KEY frame as soon as it has a session, and
"own entry key held by peer" is updated when ENTRY_KEY_ACK arrives. The
receiving side replaces its "peer entry key" when it gets the frame. Old entry
keys are kept only inside the records of the pairs that still hold them.
A mac1 made with the current key makes every pair a candidate, so a lost ACK
does no harm. A removed device still holds the entry key that was current
when it was removed. Once every other pair has received the new key it stops
at mac1 like a stranger. Until then its mac1 still verifies, and each of its
datagrams costs one tag computation for every pair that has not yet been
updated (at most 63), under the global cap of section 15; it never passes the
pair tag.

ENTRY_KEY carries the current entry key and is repeated in every control
packet until ENTRY_KEY_ACK with the first 8 bytes of its SHA-256 arrives. The
receiver of ENTRY_KEY stores its pair record with the new "peer entry key"
before it sends ENTRY_KEY_ACK: a lost store would otherwise lock it out.
FORGET asks the peer to erase the pair; its application erases it, as a
removal on its own side, unless its user decides to keep it.

### 9.5 Model checking

The rules of 9.1 to 9.3 were explored exhaustively with a bounded model
(`model/psk_model.py`): two honest devices and a network that can drop, delay,
reorder and replay every message, including the stale notice of 7.5 and the
drops caused by the quota of 7.4 and the rate limit of 8.1 step 6, up to 6
attempts (911,840 states). In every reachable state: no stale notice reaches
an attempt that would act on it; no Init that passes the cookie check, live
or arriving late, is ever rejected for an unknown psk or a stale report; no
honest report is classified unknown; the psk a side would use is held by its
peer; the report a side would send is acceptable to its peer. Removing rule
C2, or treating an equal counter as stale, breaks these properties. Because
no Reject is ever produced between honest devices, every reason of a Reject,
and a stale report in particular, can raise the alert (8.1 step 9).

The model covers only these state machines between two honest devices, with
atomic steps. It does not cover cryptography, pairing, rekey, probes,
restarts, storage roll-back or an adversary that holds keys. It is evidence,
not a proof: Appendix B requires an unbounded model.

## 10. Transport

### 10.1 Packet

```
0   A3 20 00 00
4   receiver_index u32   chosen by the receiver of this packet
8   counter        u64   starts at 0 for each key; never repeats
16  sealed frames        Seal(K, counter, bytes 0..16, frames)
```

Receiver, in order: the length is 160 or the audio packet length of the
stream it receives (10.3); header; receiver_index is known (during a rekey a
session has two, section 11); the counter is not below the highest accepted
minus 4095 and not already accepted; tag; only then it marks the counter and
reads the frames (10.2). There is no limit by source address. A packet whose
counter is higher than every one accepted before "advances the counter";
only such packets renew the presence and consent timers and start a path
check.

After authentication the first frame decides the kind of packet: AUDIO or
MIC_AUDIO make it an audio packet, any other frame a control packet. An
audio packet MUST have the exact audio packet length and hold exactly one
frame; a control packet MUST be 160 bytes. Anything else is a protocol error.

The counter and the window belong to the core. A session closes when its
send counter would reach 2^60. The core is called from one thread or under
one lock.

### 10.2 Frames

The sealed part is a sequence of frames `type u8 || length u16 || content`,
read from the start until the end of the plaintext or a PADDING byte.
PADDING (0x00) has no length field and every byte after it MUST be zero; when
the frames fill the plaintext exactly there is no PADDING.

A frame is valid when its type is known, its length is the one in the table,
the kind of packet and the role of its sender allow it, the session agreed
the feature it belongs to, its content is well formed, and no frame of the
same type came before it in the packet. If any frame of a packet is not
valid, none of the frames is applied and the session closes with BYE reason
3 (10.4).

Streams. The main stream goes from the source to the receiver. With bit3
agreed, the microphone stream goes the other way, from the receiver of the
main stream to its source. So each device sends at most one stream and
receives at most one. In the table, "receiver" means the receiver of a
stream: its frames refer to the stream it receives; "source" means the
source of a stream: its frames refer to the stream it sends.

| Type | Name | Content (bytes) | Sent by | Needs |
| --- | --- | --- | --- | --- |
| 0x00 | PADDING | none | both | |
| 0x01 | AUDIO | epoch u32, sample_pos u64, capture_us u64, data (20 + payload_bytes) | source of the main stream | audio packet |
| 0x02 | MIC_AUDIO | as AUDIO | source of the microphone stream | audio packet, bit3 |
| 0x03 | START | epoch u32 (4) | receiver | |
| 0x04 | STOP | none (0) | receiver | |
| 0x05 | REPORT | last_capture_us u64, received u32, lost u32, jitter_us u32, buffer_ms u8 (21) | receiver | |
| 0x06 | TIME_REQ | t1 u64 (8) | receiver | bit1 |
| 0x07 | TIME_RESP | t1 u64, t2 u64, t3 u64 (24) | source | bit1 |
| 0x08 | PARAMS | epoch u32, StreamConfig (20) | source of the main stream | not bit2 |
| 0x09 | PARAMS_ACK | epoch u32 (4) | receiver | |
| 0x0A | REKEY_INIT | rekey_id u8, chunk u8, data 64 (66) | session initiator | |
| 0x0B | REKEY_RESP | rekey_id u8, chunk u8, data 64 (66) | session responder | |
| 0x0C | REKEY_ACK | rekey_id u8, bitmap u32 (5) | both | |
| 0x0D | PATH_CHALLENGE | data 8 | both | |
| 0x0E | PATH_RESPONSE | data 8 | both | |
| 0x0F | BYE | reason u8 (1) | both | |
| 0x10 | ENTRY_KEY | key 32 | both | |
| 0x11 | ENTRY_KEY_ACK | SHA-256(key)[0..8] (8) | both | |
| 0x12 | GROUP_JOIN | section 14.2 (104) | source | bit2 |
| 0x13 | GROUP_ACK | group u32, epoch u32 (8) | receiver | bit2 |
| 0x14 | GROUP_LEAVE | group u32 (4) | both | bit2 |
| 0x15 | NACK | first_counter u64, bitmap u32 (12) | receiver | bit2 |
| 0x16 | MIC_STATE | state u8, epoch u32, StreamConfig (21) | source of the microphone stream | bit3 |
| 0x17 | FORGET | none (0) | both | |

Frames other than AUDIO and MIC_AUDIO are allowed only in control packets.
A frame whose sender has no stream of the kind it refers to (for instance
START from a device that receives no stream) is not valid.

Content rules: BYE reasons are 0 normal, 1 pre-empted, 2 revoked, 3
protocol error, 4 guest ended, 5 rekey failed; any other value is not valid.
The StreamConfig in PARAMS and in MIC_STATE with state 1 MUST be valid
(8.3) with status 0, and its flags byte MUST be the flags agreed for the
session with bit0 set. MIC_STATE state is 0 (off) or 1 (on); with state 0 the
StreamConfig is 16 zero bytes. The rules for rekey frames are in section 11.

All times in frames are microseconds on the clock of their sender, except
t1 in TIME_RESP and last_capture_us in REPORT, which echo a time of the
device that reads them.

### 10.3 Packet kinds and cadence

- Control packet: exactly 160 bytes (128 bytes of frames). Each side sends
  one every 100 ms for the whole life of the session, from the moment the
  session is established on that side (the initiator when it accepts the
  Resp, the responder when the key is confirmed), with PADDING when it has
  nothing to say.
- Audio packet: exactly payload_bytes + 55 bytes, one AUDIO frame, for the
  main stream; MIC_AUDIO for the microphone stream. Sent at the cadence of
  capture, also during silence.
- Path challenge packet: a control packet holding only PATH_CHALLENGE, sent
  to a candidate address (10.5) in addition to the regular control packets.

A control packet carries every frame that is due, in this order; a frame is
added only if it fits in what is left of the 128 bytes, otherwise it waits
for the next control packet:

1. FORGET, then BYE, while the session is closing (10.4);
2. PATH_RESPONSE for the latest PATH_CHALLENGE received, once;
3. START or STOP for the stream received (10.4);
4. PARAMS or MIC_STATE for the stream sent, until PARAMS_ACK or START for
   its epoch arrives;
5. PARAMS_ACK for the latest PARAMS or MIC_STATE received, once;
   then, with bit2, GROUP_JOIN from the source (14.2);
6. REPORT for the stream received (10.4); then, with bit2, GROUP_ACK once,
   GROUP_LEAVE, NACK once (14.2, 14.3);
7. REKEY_ACK while receiving a rekey message (11);
8. one chunk of the rekey message being sent (11);
9. TIME_RESP for the latest TIME_REQ received, once, t3 being the time the
   packet is built;
10. TIME_REQ when due (10.6);
11. ENTRY_KEY until ENTRY_KEY_ACK for the current entry key arrives (9.4);
12. ENTRY_KEY_ACK for the latest ENTRY_KEY received, once.

The largest set that a device sends while it receives a stream and takes part
in a rekey, REPORT + REKEY_ACK + one chunk + TIME_RESP, is exactly 128 bytes.
The frames of section 14 come before the rekey frames: one that does not fit
waits, and GROUP_JOIN, 107 bytes with its header, leaves room for the others
at least two control packets in three.

Size and cadence are constant per stream. An observer learns that a session
exists, its stream classes and whether the microphone stream is on; a path
check also shows that an address changed. Nothing else.

### 10.4 Consent, presence and closing

Receiver of a stream. Its application decides whether it wants the stream;
the core never wants the microphone stream unless the application asks.

- START(epoch of the stream) is in every control packet while the receiver
  wants the stream, the stream is on (the microphone stream: switched on by
  MIC_STATE), and no audio of the current epoch has arrived in the last 2 s.
  With bit1, START waits for the first valid time sample (10.6).
- STOP is in every control packet while the receiver does not want the
  stream and audio of it has arrived in the last 2 s.
- REPORT is in every control packet while the receiver wants the stream and
  it is on. last_capture_us is the capture_us of the latest audio packet of
  the current epoch delivered to the application, 0 if none; received counts
  the audio packets delivered since the session was established; lost counts
  the packets of the sample_pos sequence of each epoch that were not
  delivered (missing or late); jitter_us is the interarrival jitter of RFC
  3550, from capture_us and arrival times; buffer_ms is D (10.6) with bit1,
  otherwise a value given by the application.
- Audio is discarded when the receiver does not want the stream, when its
  epoch is not the current one, or when it is late (10.6).
- When the receiver accepts PARAMS or MIC_STATE with a new epoch, the
  previous epoch ends: its decoder restarts clean.

Source of a stream.

- It sends no audio before START for the current epoch has arrived in a
  packet that advances the counter.
- Consent: each REPORT that arrives in a packet advancing the counter, with
  last_capture_us not older than 2 s and not later than the source's clock
  (0 is accepted during the first 2 s after START), extends consent to 2 s
  after its arrival. When consent lapses or STOP arrives, the source stops;
  a new START is needed to resume.
- New parameters of the main stream: PARAMS with a new epoch; from then on
  no audio until START for that epoch. The flags of a stream cannot change.
- Microphone: switched on only by a local command on the device that has it,
  with bit3 agreed and the microphone permission in its own pair record:
  MIC_STATE state 1 with a new epoch and the configuration. Switched off:
  MIC_STATE state 0 with a new epoch. Both are repeated until PARAMS_ACK or
  START for that epoch. No microphone audio is sent before START from the
  peer, whose own application must want it.

Presence: no authentic packet that advances the counter for 1 s: the
application is warned; for 15 s: the session closes without BYE.

Closing. A device that closes a session (its application, a protocol
error, a failed rekey, the removal of the pair) sends BYE with the reason
at once and in its next two control packets, 100 ms apart, then erases the
session; in the meantime it accepts nothing on it. On the removal of the
pair FORGET comes before BYE (reason 2) in the same packets. A device that
receives BYE closes the session at once, without answering.

### 10.5 Address change

A packet that passes all checks and advances the counter, from an address
other than the session address, starts a path check unless one is already
running for the session: the core sends a path challenge packet with 8 random
bytes to that address, up to 3 times, 1 s apart. Audio and control packets
keep going to the session address. The receiver of a PATH_CHALLENGE answers
with PATH_RESPONSE in its next control packet. When a PATH_RESPONSE with the
same 8 bytes arrives from the candidate address, the candidate becomes the
session address; one that arrives from another address is ignored. 1 s after
the third try without it, the candidate is discarded.

### 10.6 Synchronised playback (bit1)

The receiver of a stream sends TIME_REQ(t1, its clock) every 250 ms during
the first 10 s after the stream was switched on (the main stream: when the
session was established), then every second. The source answers in its next
control packet with TIME_RESP(t1, t2, t3), t2 being the arrival of the
TIME_REQ and t3 the building of the TIME_RESP, both on its clock. With t4 the
arrival of the TIME_RESP:

```
round trip = (t4 - t1) - (t3 - t2)
offset     = ((t2 - t1) + (t3 - t4)) / 2      source clock minus receiver clock
```

A sample is valid when t1 is one of the last four TIME_REQ sent, t3 >= t2 and
0 <= round trip < 15 ms. During the fast phase (the first 10 s) the estimate
is the offset of the valid sample with the smallest round trip so far. After
it, each valid sample moves the estimate towards its offset by at most
0.5 ms for each second since the previous update (500 ppm); the first valid
sample, if none came in the fast phase, sets it directly.

The playback instant of an audio packet on the receiver's clock is
capture_us - estimate + D. D starts at 60 ms. A packet that arrives after
its playback instant, or that the application reports late, is discarded,
never played out of time, and D becomes min(200 ms, D + lateness + 5 ms).
After 30 s without late packets D falls by 5 ms, and again every 30 s, never
below 30 ms. The application follows the playback instants and corrects the
drift by resampling, within 500 ppm.

## 11. Rekey

Every 120 s, plus a random phase of 0 to 30 s drawn once per session, the
session initiator starts a rekey inside the control channel: the first 120 s
plus the phase after the session was established, each next one 120 s after
the previous one began. rekey_id is 1 for the first rekey of the session and
grows by one, modulo 256, at every rekey.

The rekey is the handshake of section 8 with these differences: name
"WFAS3_KKpsk0hfs_rekey_25519+MLKEM768_ChaChaPoly_SHA256"; after the static
keys, MixHash(rekey_id || current receive index of the initiator || current
receive index of the responder); each message starts with new_index, the
receive index its sender will use after the rekey, distinct from every
receive index in use, added with MixHash before any other token; psk = the
rekey psk of the keys in use; payloads are 48 zero bytes; no cookie, tags or
mac fields. Outputs: K_i2r, K_r2i and the next rekey psk (6.3). The pair psk
and the session reports are not touched.

```
REKEY_INIT message, 1300 bytes: new_index u32 || e 32 || e1 1200 || payload 64
REKEY_RESP message, 1204 bytes: new_index u32 || e 32 || ekem1 1104 || payload 64
```

Each message is cut into 64-byte chunks, 21 and 19, the last one padded with
zeros that MUST be zero. The sender puts one chunk in each control packet,
cycling through the chunks not yet acknowledged. The receiver puts
REKEY_ACK(rekey_id, bitmap of the chunks it holds, bit i = chunk i) in every
control packet until it starts sending its own message or has switched; bits
beyond the number of chunks MUST be zero. A REKEY_RESP chunk also
acknowledges the whole REKEY_INIT. chunk MUST be below the number of chunks.

The responder accepts REKEY_INIT chunks whose rekey_id is one more than the
last completed rekey (the first such chunk begins the rekey on its side) or
equal to the rekey in progress; chunks of the last completed rekey are
ignored; any other rekey_id is not valid. The initiator ignores REKEY_RESP
chunks and REKEY_ACK of any rekey other than the one in progress; the
responder ignores REKEY_ACK of any rekey other than the one in progress.

The responder computes its answer when it holds the whole REKEY_INIT. A
REKEY_INIT that does not open, carries an invalid encapsulation key or a
non-zero padding or payload closes the session with BYE reason 5. From then
on the responder has its new keys ready but keeps sending and receiving with
the old ones.

Switch. The initiator, when the whole REKEY_RESP verifies, derives the new
keys and from then on sends with them, to the responder's new index, with
counters from 0; a REKEY_RESP that does not verify closes the session with BYE
reason 5. The responder switches when it accepts the first packet on its new
index: from then on it sends with the new keys to the initiator's new index.
The initiator finishes its switch when it accepts a packet on its own new
index. At the end of its switch each side keeps the old receive key and its
window for 1 s more, never beyond 60 s after the rekey began, so that packets
the network delivers late are not lost, then erases it; such a packet never
advances the counter. Old send keys are erased at once. The rekey psk changes
at the switch on each side. A side that has not finished its switch 60 s after
the rekey began on its side closes the session with BYE reason 5; the
application reopens it through the entry.

Building REKEY_INIT and REKEY_RESP needs fresh entropy, like a handshake. A
device computes at most one rekey message every 100 ms.

## 12. Discovery

There are no periodic beacons. A device looks for the other side of a pair
with a probe that only that pair can build, and only when its application
asks.

Probe and ProbeReply (60 bytes):

```
0   A3 01 00 00  (ProbeReply: A3 02 00 00)
4   nonce        16   Probe: random. ProbeReply: echoed
20  ctr_masked   8    Probe: the entry counter of the pair (7.3), masked
                      with the type byte 0x01. ProbeReply: echoed
28  tag          16   MAC16(K_probe[d], bytes 0..28), d = direction of travel
44  mac1         16   MAC16(K_mac1, bytes 0..44), entry key of the receiver
```

Search. The application starts a search for one pair; a device runs at most
four at a time. The first Probe leaves at once and the next ones every 2 s;
a pair never gets more than one Probe every 2 s, also across searches, so a
search started sooner sends its first Probe when the 2 s have passed. If the
device has a last seen address for the pair, Probes 1, 3, 5... go to it and
Probes 2, 4, 6... to the local broadcast address; otherwise all go to the
broadcast address. The broadcast address is 255.255.255.255, port 14114; on a
link without IPv4 it is ff02::1, port 14114. After 15 Probes without an
accepted ProbeReply the search ends 2 s after the last one and the
application is told that the peer was not found. Of a running search the
device keeps the nonce and ctr_masked of each Probe sent in the last 4 s,
and nothing else.

Last seen address: per pair, in RAM only, never stored. It is the address of
the latest session of the pair, as changed by 10.5, or the source address of
the latest accepted ProbeReply, whichever came last.

Receiver of a Probe, in order: length and header; mac1 (7.1); tag with
K_probe for the incoming direction, counted in the cap of 7.2; counter (7.3)
against `probe_seen`:

- greater: fresh. `probe_seen` takes the value at once. The Probe is
  answered if the pair had no fresh answer in the last 2 s and fewer than 10
  fresh answers were sent in the last second in total.
- equal: drop, with no answer of any kind.
- lower: stale. The Probe is answered if the pair had no stale answer to a
  Probe in the last 30 s and fewer than 2 stale answers to Probes were sent
  in the last second in total. This budget is apart from that of fresh
  answers and from that of the stale notices of 7.5, so that replayed Probes
  can use up neither.

The answer is a ProbeReply that echoes nonce and ctr_masked, sent to the
source address of the Probe. The receiver keeps no other state, raises no
alert and never answers a ProbeReply.

Receiver of a ProbeReply, in order: length and header; nonce and ctr_masked
both equal to those of a Probe of a running search sent in the last 4 s;
mac1 (7.1) with a key that the pair of that search holds; tag with K_probe
for the incoming direction, counted in the cap of 7.2. Then the search ends
and its nonces are forgotten, the pair's last seen address becomes the
source address of the ProbeReply, and the application is told that the peer
was found there. That address is a candidate for the handshake, which
authenticates everything; it is trusted for nothing else.

## 13. Pairing

Pairing creates, on both devices, a fresh X25519 static key pair for this pair
only, K_fix, psk0, and exchanges the entry keys. It is a hybrid handshake
(X25519 and ML-KEM-768 ephemerals) keyed by CPace and, when a code is used, by
a pairing key whose fingerprint is in the code.

### 13.1 Methods

| Method | CPace secret (PRS) | Pairing key W | Protection against a man in the middle |
| --- | --- | --- | --- |
| Code on screen (QR, or typed) | 16 random bytes, one use, 120 s | fresh X25519 key for this window | secret, plus fingerprint of W in the code |
| Label on a headless device | 16 random bytes, permanent | the device's pairing identity key | secret, plus fingerprint of W on the label |
| PIN | 8 decimal digits (ASCII), one use, 120 s | fresh, not authenticated | 6-digit code compared by the user on both screens |

Pairing code: 32 bytes = secret (16) || fp (16), with
fp = SHA-256("WFAS3 pairing key" || W_public)[0..16]. It is written as 52
characters of Crockford base32 (260 bits: the 256 bits of the code followed by
the first 4 bits of SHA-256("WFAS3 code check" || code)), shown in 13 groups of
4. A QR code contains "WFAS3:" followed by the same 52 characters.

A photograph of a code or label gives the secret and a fingerprint, but not
the private key of W: its holder cannot impersonate the server to the client,
so it cannot sit in the middle. It can still try to pair as a client while a
window is open; that consumes the window and the owner's own pairing then
fails visibly.

### 13.2 Window and beacon

A window is opened by hand (on a headless device, by a short press of the
button), lasts at most 120 s and admits one pairing. Holding the button for
10 s erases all pairs and opens no window. A device is never pairing server
and pairing client at the same time.

PairBeacon (72 bytes), broadcast when the window opens and then once per
second while it is open:

```
0   A3 03 00 00
4   mode          u8   1 = code or label, 2 = PIN
5   state         u8   0 = open, 1 = used: a pairing was completed in this window
6   reserved      2    zero
8   window_nonce  16   random per window
24  name          32   mode 2: declared name, UTF-8, zero padded. mode 1: zero
56  tag           16   mode 1: MAC16(K_b, bytes 0..56), K_b = KDF(secret, "pair beacon")
                       mode 2: zero
```

After a pairing completes, the server keeps sending the beacon with state 1
until 120 s have passed since the window was opened, and accepts no further
step 1. In mode 1 the state is covered by the tag, so a client that holds the
code learns with certainty that someone else used the window.

A mode-1 client waits for a beacon whose tag verifies with its code; the
first one with state 0 starts its run (its step 1 goes to the source address
of the beacon, and window_nonce comes from it). A beacon of its code with
state 1 seen before its own step 8 ends the run. A mode-2 client starts from
a beacon chosen by its user and the PIN typed by its user.

A client whose own pairing did not complete and that sees state 1 for its
code, or whose pairing fails after a step 2 of its run has verified (for any
reason except its own user's cancellation), MUST tell the user that another
device may have been paired in its place, and MUST propose the remedy: on a
headless device, erase all pairs (hold the button for 10 s) and pair again;
on a device with a screen, remove the unknown entry from the list of paired
devices. A headless device MUST signal every completed pairing with its
indicator or a sound, and cannot remove a single pair by itself.

In mode 1 nothing readable is announced: only a device that holds the code
recognises the beacon. In mode 2 the name is public and unauthenticated; an
application shows at most 8 such names, cleaned of control and direction
characters, and decides nothing from them.

Filter key: mode 1: K_f = KDF(secret, "pair filter"). Mode 2:
K_f = KDF(window_nonce, "pin filter"). It can never come from the PIN. Here
and for K_b the key of KDF is 16 bytes (section 2).

### 13.3 Handshake

Name: "WFAS3_PAIR_25519+MLKEM768+CPace_ChaChaPoly_SHA256".
After `Init(name)`: MixHash(mode || pair_sid || window_nonce), mode being one
byte.

```
-> e, e1, ya
<- e, ee, yb, cpace, ekem1, w, ew, s, es
-> s, se
```

- `e`: the sender's X25519 ephemeral public key, in clear, MixHash only.
- `e1`: ML-KEM-768 encapsulation key, in clear, MixHash.
- `ya`, `yb`: CPace public values, in clear, MixHash.
- `ee`: MixKey(DH(e_c, e_s)).
- `cpace`: MixKey(ISK), 64 bytes.
- `ekem1`: (ct, ss) = Encaps(e1); EncryptAndHash(ct); MixKey(ss). The client
  decapsulates only after the AEAD tag of `ekem1` has verified.
- `w`: EncryptAndHash(W_public). `ew`: MixKey(DH(e_c, W)).
- `s` (server): EncryptAndHash(new pair static public key of the server);
  `es`: MixKey(DH(e_c, s_s)).
- `s` (client): EncryptAndHash(new pair static public key of the client);
  `se`: MixKey(DH(s_c, e_s)).

CPace: PRS as in 13.1; CI = ASCII "WFAS3-PAIR" || mode; sid = pair_sid ||
window_nonce; ADa = client ephemeral public key; ADb = server ephemeral public
key; generator, scalar multiplication and
ISK = SHA-512(lv_cat("CPace255_ISK", sid, K) || lv_cat(Ya, ADa) || lv_cat(Yb, ADb))
exactly as in draft-irtf-cfrg-cpace-21, suite CPACE-X25519-SHA512, with the
Elligator 2 map of RFC 9380. K all zero aborts, as does an all-zero Ya or Yb.

The server static key, the pairing key, the client static key and everything
after them are encrypted with keys that depend on ISK: a party without the
secret obtains nothing, and gets exactly one guess per run. A client
processes a step 2 on a copy of its state: a step 2 that does not verify
changes nothing, so a forgery cannot stop the real one.

In mode 1 the client MUST check fp against W_public as soon as `w` is opened
and abort on mismatch. In mode 2 there is no such check.

After the third message, with ck its chaining key: K_c2s = KDF(ck, "pair
c2s"), K_s2c = KDF(ck, "pair s2c"), K_fix = KDF(ck, "pair fixed"),
psk0 = KDF(ck, "pair psk"), pair fingerprint = KDF16(ck, "pair fingerprint").
The new record of each side holds its own static key, the peer's, K_fix,
psk0 as psk cur, the two entry keys of steps 7 and 8, the declared name of
the peer, report slots base and last set to (pair fingerprint, 0) and
seq_hi 0.

### 13.4 Messages

Every Pair datagram:

```
0   A3 13 00 00
4   step      u8   1..9
5   mode      u8
6   reserved  2    zero
8   pair_sid  8    random, chosen by the client
16  body
..  filter    16   MAC16(K_f, all preceding bytes)
```

| Step | From | Length | Body |
| --- | --- | --- | --- |
| 1 | C | 1368 | e 32, e1 1184, ya 32, 88 zero bytes |
| 2 | S | 1296 | e 32, yb 32, ekem1 1104, w 48, s 48 |
| 3 | C | 176 | s 48, EncryptAndHash(name 32, flags u8, 15 zero, commit_c 32) 96 |
| 4 | S | 80 | Seal(K_s2c, 4, bytes 0..16, commit_s 32) |
| 5 | C | 80 | Seal(K_c2s, 5, bytes 0..16, n_c 32) |
| 6 | S | 80 | Seal(K_s2c, 6, bytes 0..16, n_s 32) |
| 7 | S | 120 | Seal(K_s2c, 7, bytes 0..16, decision u8, permissions u8, guest u8, 1 zero, name 32, entry key 32, 4 zero) |
| 8 | C | 100 | Seal(K_c2s, 8, bytes 0..16, accept u8, 3 zero, entry key 32, replaces 16) |
| 9 | S | 52 | Seal(K_s2c, 9, bytes 0..16, done u8, 3 zero) |

flags in step 3: bit0 guest requested, bit1 microphone requested; the other
bits zero. Every value outside its range, and every non-zero reserved byte,
is a protocol error. A server serves a datagram only from the address and
pair_sid of its run; a client only from the server's address and its own
pair_sid.

Retransmission. Each step is built once and sent unchanged; a step is
processed once. Until step 3 has verified, which proves the client's address,
a reply is never larger than the datagram that causes it, and the server
sends nothing on its own: it answers each copy of step 1 with its stored
step 2, at most once per second. After that the side that waits for the
other one's next step sends its last step again every second: the client
steps 1, 3, 5 and 8, the server steps 4, 6 (while its user is being asked)
and 7. A copy of step 5 that verifies, received after step 6 was replaced by
step 7, is answered with step 6 again. A copy of step 8 received in the 10 s
after step 9 is answered with step 9 again. Waits: 10 s for a step that the
peer sends without waiting for anyone, 70 s for one that may wait for a
user, 30 s for step 2 from a server that is busy; a client that received a
step 2 that does not verify waits 5 s more for the real one; then the run
fails.

Code comparison: n_c, n_s are 32 random bytes;
commit_c = SHA-256("WFAS3 sas commit c" || h3 || n_c), with h3 the transcript
hash after `se`; commit_s = SHA-256("WFAS3 sas commit s" || h || n_s) with h
the final transcript hash, after the payload of step 3. Each side checks the
other's commitment when the nonce is revealed; a mismatch ends the run. The
code is the u64 (big-endian) in the first 8 bytes of
SHA-256("WFAS3 sas" || h || n_c || n_s), modulo 1,000,000, shown as 6
digits. In PIN mode both users MUST confirm that the codes are equal: the
server's user before step 7 carries a yes, the client's user before step 8
carries accept 1. In mode 1 the exchange still runs and the code MAY be
shown.

Approval: after step 5 the server asks its user (declared name, cleaned; the
flags; in PIN mode the code), "No" first, 60 s, no answer means no; one
question is open at a time, the others wait their turn. On a headless device
the open window is the approval, with the permissions set when it was
opened. A refusal (decision 0) carries a zero name, permissions and entry
key, and the client answers it with step 8 with accept 0 and nothing else;
so does a client whose user says the codes differ. The microphone permission
is granted only if the client asked for it; the client's record has it only
if it asked and step 7 grants it.

Nothing is stored before step 9 (server: before receiving step 8, and before
step 9 leaves). A pair that completes no session handshake within 10 minutes
of its creation, or of its import while it has never had one (seq_hi 0), is
erased: removed as in 9.4.

### 13.5 Replacing a pair

`replaces` in step 8 is MAC16(K_replace of the old pair, "replace" || h), or
zero. If it matches one of the server's pairs that is not a guest, that pair
is removed (section 9.4) once the new one is stored; the client removes the
old pair the same way once it has stored the new one. The client names the
old pair when its user starts the pairing. An application offers this when a
pair is marked suspect. Without it, a suspect pair on a headless device would
survive a new pairing.

### 13.6 Guests

A guest pair follows the same steps with guest = 1 in step 7. A client asks
for it with bit0 of flags; the server MUST then make it a guest pair, and may
make any pair a guest pair. A client that asked and gets guest 0 aborts. Its
record exists only in RAM on both sides: it is never written, exported or
imported. Its "entry key" in steps 7 and 8 is a random key made by each side
for this guest only, so that the device entry key is never given to a guest;
no ENTRY_KEY frame is sent to a guest and ENTRY_KEY from a guest is
ignored. The pair ends when either side sends BYE reason 4 (sent only in a
guest session; for another pair it only closes the session), at restart, on a local command, or when it has had no
session for 120 s; its end changes no entry key. Until then it may reopen
its session through the entry like any pair.

### 13.7 Limits

A Pair datagram with a wrong filter is dropped after one HMAC. In both
modes the server admits step 1 at most once per second per source address
and 4 times per second in total, with at most 2 runs in computation at a
time; addresses take turns: an address served before waits while another
one, seen in the last 2 s, has not been served or was served less recently.
The server remembers the last 8 addresses for this. An attempt in progress
is never interrupted and there is no lock-out. The application shows the
count of attempts of the window that ended without a pair, except those its
own user refused, and proposes the code.

## 14. Multicast

Optional. One source, at most 16 receivers, each of which has a unicast
session with the source and bit2 set. A source has one group at a time,
with one configuration and one multicast address (IPv6 ff00::/8 or IPv4
224.0.0.0/4) and port, chosen by its application.

### 14.1 Packet

```
0   A3 30 00 00
4   group    u32
8   epoch    u32
12  counter  u64
20  sealed   Seal(group_key, nonce, bytes 0..20, one AUDIO frame)
..  tags     16 x 8   tag[i] = MAC8(tag_key of slot i, all preceding bytes)
```

The AEAD nonce is epoch (4 bytes) || counter (8 bytes). The counter belongs
to the group: it starts at 0, grows by one with every packet, across epochs,
and the group closes before it reaches 2^60. The AUDIO frame is that of 10.2,
with stream epoch 0. Length is exactly payload_bytes + 187, so payload_bytes
is at most 1213. Every slot has a tag key in every epoch, so the tags of free
slots cannot be told from those of members.

Receiver, in order: length, header; group and epoch known (the current
epoch, or the previous one during 2 s); counter not below the counter of its
first GROUP_JOIN, inside its own 4096 window and not seen; its own tag; the
AEAD; one AUDIO frame of the exact length; then mark and play as audio of
its session. A group member can listen but cannot forge for another member.
A multicast packet never advances the counter of a session.

### 14.2 Membership and keys

GROUP_JOIN (104 bytes of content), sent in the unicast session:

```
group u32 | epoch u32 | counter u64 | slot u8 | 3 zero |
address 16 | port u16 | 2 zero | group_key 32 | tag_key 32
```

counter is the next counter of the group when the frame is built; slot is
the receiver's slot, 0..15; address and port are the group's (an IPv4
address is IPv4-mapped); tag_key is the key of that slot in that epoch. A
GROUP_JOIN with any other value is a protocol error.

A receiver becomes a member when the source accepts its START in the
session; the source then sends GROUP_JOIN, at most every 300 ms, until
GROUP_ACK(group, epoch) for that epoch arrives. Group packets are sent only
while at least one member holds the key and has consent (10.4); a member
whose consent lapses, or whose session ends, loses its slot. A receiver that
no longer wants the stream sends GROUP_LEAVE; a source that drops a member,
or closes its group, sends GROUP_LEAVE to it. GROUP_LEAVE goes in three
control packets and is not answered.

The receiver answers every GROUP_JOIN that is not older than its current
epoch with GROUP_ACK, once. A GROUP_JOIN for
a new group or address is a join: the application listens to the address. A
GROUP_JOIN for the same group with a higher epoch is a new epoch: the
previous key stays valid for 2 s. A lower epoch changes nothing. On
GROUP_LEAVE from the source, or when its own application stops wanting the
stream, the receiver erases the keys and its application leaves the
address.

Epochs. A new epoch, with a new group key and new tag keys for every slot,
is made every 10 minutes, at every leave and at every revocation. After a
leave or every 10 minutes it is given out first: each member receives the
current epoch, if it does not hold it yet, then the new one, and the source
keeps sending under the current key until every member holds the new one,
for 2 s at most. One new epoch is given out at a time: a second leave puts
the one being given out in use. After a revocation the new epoch is in use
at once. A revocation is the removal, on the source, of the pair of a
member.

### 14.3 Loss

NACK(first_counter, bitmap) asks for the group packets first_counter + i for
every bit i (bit 0 is the least significant) that is set. A receiver sends
one, once, when an accepted packet shows a gap after the highest counter it
accepted, or after the counter of its join; a NACK covers at most the 32
counters from first_counter on. The source keeps at least the packets of the
last 200 ms (64 by default) and resends each one asked for, while its
capture_us plus latency_ms is still in the future, as an audio packet of
that member's session with the same AUDIO frame. A receiver plays a
(stream epoch, sample_pos) once, whichever path it came by, and remembers
the last 64.

### 14.4 Sessions of a group

A session with bit2 agreed has the configuration of the group for its whole
life: PARAMS is not valid in it, and its main stream carries no audio of its
own, only the resends of 14.3. The microphone stream (bit3) is unchanged.
Its other frames are those of section 10.

## 15. Limits and timers

| Item | Limit |
| --- | --- |
| Datagram without valid mac1 | dropped after one HMAC per distinct entry key still held by some pair |
| Tag computations | one global cap per second, set from measurement on the slowest supported device |
| Fresh CookieRequest | one per 2 s per pair |
| Stale CookieRequest, stale Probe | one answer per 30 s per pair, 2 per second in total; separate budgets for the two |
| Cookie | bound to address, port, request and counter; secret replaced every 30 s, so valid at most 60 s |
| Init | latest request only, once; one handshake per pair every 2 s |
| Handshakes in total | cap set from measurement: at most 10 percent of the CPU, audio always first |
| Sessions | 8 by default |
| Attempt | retry every 5 s, give up after 60 s |
| Control packets | one every 100 ms per session and side |
| Rekey | every 120 s plus 0..30 s; complete within 60 s or close; one rekey message computed per 100 ms per device |
| Consent | audio stops after 2 s without a fresh REPORT |
| Peer lost | warn after 1 s, close after 15 s |
| Closing | BYE at once and in the next two control packets |
| Path check | 3 tries, 1 s apart; one per session at a time |
| TIME_REQ | every 250 ms for 10 s, then every second |
| Playback delay D | starts at 60 ms, between 30 and 200 ms |
| Probe | one per 2 s per pair; 15 per search, 4 searches at a time; fresh answers one per 2 s per pair, 10 per second in total |
| ProbeReply | accepted for a Probe of the last 4 s, once |
| Seen counters alone | written at most once per minute per pair |
| Pairing window, code, PIN | 120 s, one pairing; beacon every second |
| Pairing steps | sent again every second; 10 s for a step the peer sends without waiting, 70 s for one that may wait for a user, 30 s for step 2, 5 s after a step 2 that does not verify; copies of step 8 answered for 10 s |
| Step 1 | one per second per address, 4 per second in total, 2 runs at a time, 8 addresses remembered |
| Approval question | 60 s, then no; one at a time |
| Unconfirmed session (responder) | discarded after 15 s |
| Unused new pair | erased after 10 minutes |
| Guest without session | erased after 120 s |
| Group key | new every 10 minutes, at every leave and revocation; the previous one 2 s at most after a leave, none after a revocation |
| GROUP_JOIN | at most every 300 ms until GROUP_ACK |
| GROUP_LEAVE | in three control packets |
| Group history for NACK | at least 200 ms of packets (64 by default) |
| Pairs per device | 64 |

Every wait has a ceiling. Waits that are renewed are renewed only by authentic
packets that advance the counter.

## 16. Security considerations

What is guaranteed against anyone without the keys of a valid pair, under the
assumption that X25519 and ML-KEM-768 are not both broken and that
ChaCha20-Poly1305 and SHA-256 hold: confidentiality and authenticity of every
byte after the handshake; no injection and no forged control message; no
useful replay; forward secrecy through ephemeral keys in every handshake and
rekey; confidentiality and authentication against a quantum adversary through
ML-KEM and the symmetric pair psk; no offline dictionary attack; no downgrade;
no amplification.

What remains, by design:

1. Availability. Whoever controls the radio or the path can stop the audio.
   A valid paired device can consume shared entry capacity.
2. Existence of the session, its stream class and the microphone stream being
   on are visible.
3. A compromised paired device receives audio as intended. A thief holding a
   pair's keys can impersonate that device until the pair is replaced or the
   real devices have promoted a newer psk (9.2). Because K_fix does not
   change, the thief keeps the ability to pass the entry for that pair, to
   answer its probes and to raise alerts, until the pair is replaced. The
   real devices are told by a Reject, an unknown report or a stale notice,
   but only when they next run a handshake with each other: a thief who also
   blocks the path between them stays undetected for as long as it does.
4. Stale path. A CookieRequest or Probe captured on the network can be
   replayed. It consumes no fresh quota and creates no state, but it obtains
   an answer at most once every 30 s per pair. Whoever captured it can
   therefore learn that the addressed device is reachable and still holds
   that pair, until that device's entry key is replaced. This is the price of
   telling a device loudly that its keys were used by someone else, instead
   of failing in silence.
5. Key-compromise impersonation. Whoever steals the keys of device A can,
   without a quantum computer, build an Init in the name of its peer B that A
   accepts: `es` and `ss` can be computed with A's private key. A then updates
   its psk and report slots and answers. The forger cannot process the Resp,
   which needs B's private key, so no session exists and no audio or control
   data is exposed; the next handshake between the real devices raises the
   alert. With a quantum computer that breaks X25519 the forger can also
   complete the session, because the pair psk is symmetric.
6. Label and quantum computer. With a photograph of a label, the ability to
   break X25519, and presence on the network during two pairing windows (one
   to obtain the pairing identity public key by pairing as a client, which
   the owner sees as a failed pairing; one to use it), a man in the middle of
   a later pairing is possible. Without a quantum computer it is not.
7. Pairing race. Whoever holds a code or a photograph of a label and is on
   the network while a window is open can pair first. That pair stays until
   it is removed. The owner is warned (13.2) but, on a headless device, the
   only removal is erasing all pairs, and the label secret is permanent.
8. Asymmetric delay of time packets: up to 7.5 ms per room.
9. User errors, implementation bugs, side channels and flaws in the state
   machines are reduced by section 17 and Appendix B, not removed by this
   document.

## 17. Implementation requirements

The reference implementation is `wfas.h`, `wfas.c` (the core) and `wfascli.c`,
plus unmodified third-party files in `third_party/`. Two small glue files,
`wfas_tp.c` and `wfas_tp_mlkem.c` behind `wfas_tp.h`, are the only ones that
include third-party headers: they adapt argument types and add no
cryptography. They are two because the HACL* and libcrux headers define the
same macros and cannot share a translation unit.

Core contract:

- No sockets, clock, allocation, threads, globals, function pointers or
  system calls. Inputs: received datagrams with their source address, the
  monotonic time, entropy, the stored records. Outputs: datagrams to send,
  records to store, events (including "pair suspect").
- The caller never supplies a nonce, counter or ephemeral key.
- Without entropy at start the core refuses to run.
- A rejected datagram changes no session or key state.
- Every buffer holding a secret is erased with volatile writes on every path.
- The caller completes every store the core asks for before its next call
  into the core: a later call may release a datagram that relies on it.
- A record that the rules say is written "before" a message MUST be handed to
  storage and confirmed before that message is released.
- Secret random values (ephemeral keys, ML-KEM randomness, cookie secrets)
  never share generator output with a value sent in clear (indices, request
  ids, salts, nonces).
- Receive indices of live attempts and sessions are distinct on a device.

The rules of the design document for the C language (C99 subset, compiler
flags, static analysis, CBMC on every core function, sanitizers, full
coverage, fuzzing, constant-time checks on the final binary, computed maximum
stack) are normative for a release. Comments are English and ASCII only.

Third-party primitives, never modified, each pinned by version and SHA-256 in
`third_party/MANIFEST.sha256`, which the build checks before compiling:

| Component | Pinned at | Provides |
| --- | --- | --- |
| HACL* (portable C) | hacl-packages commit 05c3d8fb321e (HACL* ae5d839c2e1f) | X25519, ChaCha20-Poly1305, SHA-256, SHA-512, HMAC, HKDF, HMAC-DRBG |
| libcrux, as the single header generated by OpenSSH `mlkem768.sh` | OpenSSH V_10_2_P1, libcrux 84c5d87b3092 | ML-KEM-768 |
| fiat-crypto field arithmetic for Curve25519, 32-bit (`curve25519_32.c`) | fiat-crypto commit 3d2880ef2803 | GF(2^255 - 19), the base of the Elligator 2 map of CPace (RFC 9380 G.2.1, straight-line version); included by `wfas_tp.c` |
| libopus (outside the core) | not yet vendored | Opus |

OpenSSH V_10_2_P1 is the last release that ships the single ML-KEM header;
later releases ship a larger file that also contains ML-DSA. Moving to it is
a block update of this table, the manifest and all checks.

The only cryptographic code written for this project is the Elligator 2 map
and the CPace composition around it; both reproduce the test vectors of RFC
9380 appendix J.4 and of draft-irtf-cfrg-cpace-21 appendix B.1. The licence
text of every component is shipped in `NOTICE`.

`wfascli.c` is a POSIX command-line client for Linux: it keeps its records in
a directory of mode 0700, writes each file with mode 0600 through a new
temporary file and a rename, never through a symbolic link, overwrites a
file before unlinking it, takes entropy from getrandom, time from
CLOCK_BOOTTIME, locks its memory and disables core dumps.

Tests (`make check`): known answers for every primitive, from an independent
Python reference; the handshake suite, with two and three devices over a
network that drops, duplicates, delays and reorders, forgeries of every byte,
replays, crossing handshakes, a thief with a copy of a record, an old copy, a
store rolled back, restarts; the interoperability test against
`tests/peer.py`, a second implementation of sections 6 to 9 and 12 written
from this document, which compares the stored state of both sides after every
handshake and exchanges Probes in both directions; the bounded model; cppcheck
and the Clang analyser; GCC and Clang, sanitizers and Valgrind. The pairing
suite (all three methods over a hostile network, wrong codes, PINs and
keys, refusals, guests, unused and replaced pairs, races and admission) and
the multicast suite (members, loss and resends, leave, revocation, consent,
epochs, forgeries) run on the C core; `tests/peer_pair.py` and
`tests/peer_mcast.py`, second implementations of sections 13 and 14 written
from this document, interoperate with it in both roles; `tests/cli_test.py`
runs two `wfascli` devices on one machine. `make mutate`
puts back, one at a time, every defect found so far in the core, removes rules
C2, C3, C5, R2 and I2 and 17 rules of sections 13 and 14, and alters the
second implementations in 19 ways; the suites must fail every time.

## 18. Coexistence with v2

v2 is unchanged. v3 uses its own port and the marker 0xA3; v2 packets start
with ASCII text or "WF". There is no negotiation and no automatic fallback: a
device paired with v3 is never contacted with v2. Port 14114 is used for
testing only until IANA assigns a port (RFC 6335, RFC 7605).

## Appendix A. Changes from design revision 4

| # | Change | Reason |
| --- | --- | --- |
| A1 | Pairing codes and labels carry the fingerprint of a pairing key; the server proves it holds that key (13.1, 13.3). The typed code grows from 26 to 52 characters. | A photographed label allowed a silent man in the middle of every pairing of that device. |
| A2 | Entry counter in CookieRequest and Probe (7.3). Probe and ProbeReply grow from 52 to 60 bytes. The 8 zero bytes of CookieRequest become the masked counter. The 30-entry request cache is replaced by two counters per pair. | CookieRequest and Probe could be replayed to starve a pair's quota. |
| A3 | Stale path and stale notice (7.5, 12). | Without it, a thief could lock the real device out in silence by sending a higher counter. |
| A4 | Pair psk with three slots and explicit rules; attempt rules C1 to C5 with the tie-break at cookie time (9.1, 9.2). | The two-slot description allowed a false alarm after lost packets, and crossing handshakes could split the two sides. |
| A5 | Session reports moved into the handshake payloads, with sequence numbers (9.3). | Bounded storage with no false alarm; checked before the session exists. |
| A6 | New datagram Reject (8.4), bound to the request. | The encrypted refusal was undefined and replayable. |
| A7 | Pairing token order fixed: CPace is mixed before any static key is sent (13.3). | With the XXpsk3 order the client static key was readable by a man in the middle. |
| A8 | Pair beacon in code mode carries no name (13.2). | Nothing readable is announced when a code is used. |
| A9 | Rekey chunks of 64 bytes with acknowledgement bitmap (11). | Without retransmission a lossy network failed every rekey. |
| A10 | Guests get a per-guest entry key and may reopen until the guest pair ends (13.6). | A guest could not compute mac1 and was expelled by 60 s of loss. |
| A11 | `replaces` in pairing (13.5); pair marked suspect. | A stolen pair survived a new pairing on a headless device. |
| A12 | Maximum datagram is 1400 bytes, not 1368; multicast audio is at most 1213 bytes, not 1216. | The audio packet, not Init, is the largest datagram. |
| A13 | `e1` before `es` documented as a deviation from the hfs rule (2.1). | The design used this order without saying it differs from the cited specification. |
| A14 | Frames added: MIC_AUDIO, ENTRY_KEY, ENTRY_KEY_ACK, GROUP_JOIN, GROUP_ACK, GROUP_LEAVE, NACK, MIC_STATE, FORGET, REKEY_ACK. | They were needed by the design but had no format. |
| A15 | Per-pair storage is about 420 bytes, not 350. | Three psk slots, counters and report slots. |

Changes in draft 2, after the review of draft 1:

| # | Change | Reason |
| --- | --- | --- |
| B1 | A counter equal to the highest seen is dropped in silence; the stale path is taken only by a strictly lower counter (7.3, 12). | A second copy of the request of a live attempt obtained a stale notice, so anyone without keys could mark a pair suspect. |
| B2 | A stale notice is considered only while the attempt waits for its CookieReply (7.5). | Defence in depth for B1. |
| B3 | PairBeacon carries a state byte; mandatory warning and remedy after a lost pairing race; headless devices signal every pairing (13.2, 16.7). | The owner had no certain sign that someone else had used the window. |
| B4 | Key-compromise impersonation of the first message stated without the quantum condition (16.5). | The text said a quantum computer was needed. |
| B5 | Cost of a removed device during entry key rotation stated exactly (9.4, 15). | It does not always stop at the first HMAC. |
| B6 | Theft detection needs a handshake between the real devices (16.3). | Not stated. |
| B7 | Model extended with the stale notice and the quota and rate-limit drops; its limits listed (9.5). | The model did not cover the path of B1. |

Changes in draft 3, from the implementation of sections 5 to 9:

| # | Change | Reason |
| --- | --- | --- |
| C1 | PCM frame_samples defined with floor (8.3). | "5 ms of samples" is 220.5 at 44100 Hz; two implementations could disagree. |
| C2 | A stale report raises the alert on the responder too (8.1 step 9, 9.5). | The model shows that no Reject is ever produced between honest devices, so a stale report always means an old copy or a rolled-back store; before, only the holder of that copy was told. |
| C3 | A pair loaded while the device runs starts from the highest counter used (7.3). | Starting from the reserve could repeat a counter and cause a false theft alert. |
| C4 | Receive indices distinct on a device (7.4, 8.2, 17). | A collision would route a datagram to the wrong attempt or session. |
| C5 | Secret randomness never shares generator output with a public field (8.2, 17). | The first implementation took the Resp sender_index from the bytes of the ML-KEM message, exposing 32 of its 256 bits. |
| C6 | The initiator aborts on a session_seq not above its seq_hi (8.2). | Not specified; an honest responder never sends it. |
| C7 | Unconfirmed sessions expire after 15 s (8.5, 15). | A lost key confirmation kept a session slot forever. |
| C8 | 8.1 step 3 also checks that the responder has no attempt of its own. | Defence in depth; rules 9.1 already make it impossible. |

Changes in draft 4, from the implementation of sections 10 and 11:

| # | Change | Reason |
| --- | --- | --- |
| D1 | Time in microseconds (1). | Synchronisation needs sub-millisecond resolution. |
| D2 | PADDING is optional when the frames fill the plaintext; one frame per type and packet; a packet with an invalid frame is not applied at all (10.2). | The largest control packet of 10.3 is exactly 128 bytes and has no room for PADDING; a half-applied packet is hard to reason about. |
| D3 | The first frame decides the kind of packet, after authentication (10.1). | An audio packet with 105 bytes of payload is 160 bytes long, like a control packet. |
| D4 | Streams defined; the microphone stream goes from the receiver of the main stream to its source; "Sent by" refers to the role in a stream (10.2). | REPORT and TIME_RESP were assigned to opposite roles but listed together, and it was not said which device has the microphone. |
| D5 | MIC_STATE carries an epoch and is acknowledged with PARAMS_ACK (10.2, 10.4). | Without an epoch an acknowledgement could not tell which state it confirms. |
| D6 | When each frame is sent, and in which order (10.3, 10.4). | Lost control packets would otherwise lose START, STOP or acknowledgements. |
| D7 | bit2 is refused without section 14; bit3 needs the permission on both sides (8.3). | Features that a device does not implement must not be agreed. |
| D8 | Round trip and offset formulas; fast and slow phase; the size of the increase of D (10.6). | The 15 ms filter cannot work if the source's hold time is not subtracted; "rises at once" gave no amount. |
| D9 | new_index is added to the rekey transcript; rekey_id starts at 1; rules for unexpected rekey_id, chunk and ACK values; computations need entropy (11). | Not specified. |
| D10 | Closing sends BYE three times; a protocol error closes with BYE 3; the 15 s timeout closes without BYE (10.4). | Not specified. |
| D11 | Only packets that advance the counter start a path check, one at a time (10.5). | Late packets from an old address would start pointless checks. |
| D12 | ENTRY_KEY repeated until acknowledged; the receiver stores before it acknowledges; FORGET is a request to the application (9.4). | A lost store could lock a device out; a remote erase without the user is too strong. |
| D13 | The old receive key is kept 1 s after the switch of a rekey (11). | Erasing it at the first packet under the new key would drop every packet that the network reorders across the switch, at every rekey. |
| D14 | PARAMS and MIC_STATE are also no longer repeated once START for their epoch arrives (10.3, 10.4). | START for an epoch shows that the receiver holds its parameters; repeating them only takes room in the control packet. |
| D15 | A packet accepted under the old key after the switch never advances the counter (11). | It is older than every packet under the new key; a late START or REPORT must not restart a stream or extend consent. |

Changes in draft 5, from the implementation of section 12:

| # | Change | Reason |
| --- | --- | --- |
| E1 | A search is defined: started by the application, a Probe at once and every 2 s, at most 15, four searches at a time, ending with found or not found (12). | How and how long a device looks for a peer was not specified. |
| E2 | The broadcast address is 255.255.255.255, port 14114, or ff02::1 on a link without IPv4 (12). | "The local broadcast address" was not a value an implementation could send to. |
| E3 | Probes alternate between the last seen address and the broadcast address (12). | "Then to the broadcast address" did not say when; alternating retries both after a loss. |
| E4 | The last seen address is defined: latest session of the pair or latest accepted ProbeReply, RAM only (12). | Not specified; section 5 forbids storing addresses. |
| E5 | A ProbeReply must match both nonce and ctr_masked of a Probe of a running search; then the search ends and its nonces are forgotten (12). | A copy of the reply would otherwise be accepted again within 4 s. |
| E6 | Stale answers to Probes have their own per-pair 30 s timer and their own total of 2 per second, apart from the stale notices; tag checks of a ProbeReply count in the cap of 7.2 (12, 15). | Replayed Probes must not use up the stale notice that tells a device its keys were used elsewhere. |
| E7 | When ectr_seen, ectr_used or probe_seen change, the record is written one minute after its previous write, or at once (7.3). | "At most once per minute otherwise" did not say that they are written at all: a value could stay unwritten until the next handshake. |

Changes in draft 6, from the implementation of sections 13 and 14:

| # | Change | Reason |
| --- | --- | --- |
| F1 | Token semantics of the pairing handshake written out: `e` is MixHash only; `ee`, `ew`, `es`, `se` and `ekem1` defined; an all-zero CPace value aborts (13.3). | They were implicit; two implementations could differ. |
| F2 | KDF also accepts the 16-byte secret and window nonce (2, 13.2). | K_f and K_b are derived from 16-byte values, but KDF was defined for 32-byte keys. |
| F3 | Retransmission of the pairing steps: who sends again, when, and how copies are answered; the server sends nothing on its own before step 3 (13.4). | "Retransmitted until the next step arrives" deadlocks where the next step is the other side's, and the amplification rule needed a precise form. |
| F4 | Waits of every pairing step; a step 2 that does not verify is ignored, the run fails 5 s later (13.3, 13.4, 15). | No wait was given, and a forged step 2 must not stop the real one. |
| F5 | A refused step 7 carries no name, permissions or entry key; the client answers it with accept 0; PIN confirmations are placed before step 7 and step 8 (13.4). | The device entry key would have been given to a refused client. |
| F6 | The SAS u64 is big-endian; h is the hash after the payload of step 3 (13.4). | Not specified. |
| F7 | The race warning is due after any failure that follows a verified step 2, except a cancellation, and on state 1 before step 8 (13.2). | "Whose step 1 was answered" did not say what counts as an answer. |
| F8 | Approval: one question at a time; headless windows carry their permissions; the microphone only if asked (13.4). | Not specified. |
| F9 | Guests: a requested guest must be granted; both sides make a per-guest key; no ENTRY_KEY to or from a guest; BYE 4 only for guests; never exported or imported (5, 9.4, 13.6). | The guest rules did not say who decides, and ENTRY_KEY would have given the device key to a guest. |
| F10 | An imported pair that never had a session also gets the 10 minutes of 13.4; the client also removes a replaced pair (13.4, 13.5). | A restored unused record would stay forever; only the server side of a replacement was defined. |
| F11 | Step-1 admission in both modes, with the turn rule and its table (13.7). | "Served in turn" was not an algorithm; mode 1 had no limit against a holder of the code. |
| F12 | Multicast completed: group counter, stream epoch 0, membership by START, GROUP_JOIN semantics and cadence, GROUP_ACK, GROUP_LEAVE, epochs given out before use, revocation, NACK bitmap, history, resends, de-duplication, fixed configuration (14). | Section 14 gave formats but not the state machines. |
| F13 | bit2 negotiation and its status answers; PARAMS not valid with bit2 (8.3, 10.2). | A source must refuse sessions that are not of its group. |
| F14 | Order of the group frames in a control packet (10.3). | GROUP_JOIN (107 bytes) does not fit together with every other frame. |
| F15 | AEAD nonce in a multicast packet starts with the key epoch, as 14.1 already said (2). | Section 2 said 4 zero bytes for every nonce. |
| F16 | fiat-crypto pinned; `wfascli.c` requirements (17, Appendix B). | The component is now vendored; the storage rules of the client were not written down. |

## Appendix B. Open before "stable"

1. Symbolic model (Tamarin or ProVerif) of sections 7, 8, 11, 13, 14, and a
   computational argument for the hybrid composition.
2. Unbounded model (TLA+ or equivalent) of section 9, including restarts and
   storage failures, and of rekey, path change, pairing windows and quotas.
3. Versions and SHA-256 of the third-party files still missing (libopus) and
   of the compilers. HACL*, libcrux and fiat-crypto are pinned (section 17).
4. Test vectors for every message. Second implementations of sections 6 to
   9, 12, 13 and 14 exist (`tests/peer.py`, `tests/peer_pair.py`,
   `tests/peer_mcast.py`) and interoperate with the C core, but they have the
   same author as the core: an implementation by someone else, from this
   document alone, is still needed.
5. Measured caps of section 15 on the slowest supported device.
6. Proof of constant packet size for every admitted Opus configuration.
7. External audit of this document and of the code.
8. IANA port assignment.

## Appendix C. Size summary

| Datagram | Bytes | Largest answer it can cause |
| --- | --- | --- |
| Probe | 60 | ProbeReply 60 |
| CookieRequest | 64 | CookieReply 64 |
| Init | 1368 | Resp 1228 or Reject 48 |
| Pair step 1 | 1368 | step 2, 1296 |
| Pair step 3 | 176 | step 4, 80 |
| Control packet | 160 | - |
| Audio packet, PCM 48 kHz stereo 16 bit, 5 ms | 1015 | - |
| Audio packet, largest (7.1, 16 bit, 84 samples) | 1399 | - |
| Multicast packet, largest | 1400 | - |
