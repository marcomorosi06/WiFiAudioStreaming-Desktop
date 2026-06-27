# WFAS — WiFi Audio Streaming Protocol

**Protocol version: 2**
Status: stable · Transport: UDP (IPv4) · Byte order: see each field

This document specifies the wire protocol shared by the WiFi Audio Streaming
desktop and Android applications. Both apps implement the same protocol and can
act as either server (audio source) or client (audio sink).

---

## 1. Overview

WFAS streams raw 16‑bit PCM audio over UDP on the local network. Three phases:

1. **Discovery** — the server periodically announces itself via UDP multicast.
2. **Connection** — in unicast mode the client performs a HELLO/ACK handshake;
   in multicast mode the client simply joins the group.
3. **Streaming** — the server sends a continuous flow of audio packets; control
   messages (PING/BYE) are interleaved on the same socket.

Version 2 adds an explicit, validated **protocol version** so that two peers
running incompatible builds detect the mismatch immediately and tell the user to
update, instead of producing silence, noise, or a generic timeout.

---

## 2. Audio packet format

Every audio packet begins with a fixed **10‑byte header** followed by the PCM
payload. The header size is unchanged from v1: versioning reuses a byte that was
already reserved, so the protocol stays lightweight (zero extra bytes on the wire).

```
 byte  0   1   2   3   4   5   6   7   8   9   10 ...
      +---+---+---+---+---+---+---+---+---+---+----------------+
      | W | F | V | F | seq   | sample position | PCM payload  |
      +---+---+---+---+---+---+---+---+---+---+----------------+
```

| Offset | Size | Field            | Notes                                                        |
|-------:|-----:|------------------|--------------------------------------------------------------|
| 0      | 1    | Magic 0          | `0x57` (`'W'`)                                               |
| 1      | 1    | Magic 1          | `0x46` (`'F'`)                                               |
| 2      | 1    | **Version**      | Protocol version. v2 = `0x02`.                              |
| 3      | 1    | Flags            | bit0 = silence frame (payload may be empty). Other bits = 0. |
| 4–5    | 2    | Sequence number  | Big‑endian uint16, wraps at 0xFFFF.                         |
| 6–9    | 4    | Sample position  | Big‑endian uint32, monotonic per‑channel sample index.       |
| 10…    | n    | PCM payload      | Signed 16‑bit **little‑endian**, interleaved by channel.     |

**Payload length** is chosen by the server, per packet. It is always a whole
number of frames (`channels × 2` bytes) and never exceeds `MTU − 10` bytes
(≈ 1390 on a standard 1500‑byte MTU). A receiver **must** accept any payload
length within these bounds — the length is not fixed and may be tuned smaller,
for example so a constrained or embedded receiver can request small packets.

**`seq` and `samplePosition` are mandatory.** Every server **must** stamp `seq`
(incrementing, wrapping at 0xFFFF) and `samplePosition` (the monotonic per‑channel
sample index) on **every** audio packet, including silence frames, so a receiver
can detect loss and reordering and conceal gaps by the exact missing duration.

Control messages (Section 4) are plain ASCII and never begin with the bytes
`0x57 0x46`, so a receiver distinguishes audio from control unambiguously by the
two magic bytes.

### 2.1 Version byte rules

* A server **must** stamp byte 2 of every audio packet with its own protocol
  version (`0x02` for v2).
* A client **must** read byte 2 of the **first** audio packet it receives and
  compare it with its own version (the *first‑packet sentinel*, Section 5.3).
  Subsequent packets are not re‑checked, so the steady‑state hot path is
  unaffected.

> Historical note: v1 builds wrote an inconsistent value here (Android wrote
> `0x00`, desktop wrote `0x01`) and never validated it. A v2 client therefore
> correctly flags any v1 peer as incompatible.

---

## 3. Discovery beacon

UDP multicast, group `239.255.0.1`, port `9091`. The server sends every ~3 s a
`;`‑separated ASCII string:

```
WIFI_AUDIO_STREAMER_DISCOVERY;<hostname>;<mode>;<port>[;protocols=...][;http_port=...][;sr=..;ch=..;bd=..]
```

* `<mode>` = `MULTICAST` or `UNICAST`
* `<port>` = streaming port
* A shutdown beacon uses `;BYE;` in place of mode and removes the entry.

Discovery is intentionally **not** used for version gating: it advertises
capabilities, while version compatibility is decided at connection time
(Section 5). This keeps discovery backward‑tolerant.

---

## 4. Control messages (ASCII, UDP)

| Message                        | Direction        | Meaning                                   |
|--------------------------------|------------------|-------------------------------------------|
| `MODE_PROBE`                   | client → server  | "Are you unicast?" probe.                 |
| `UNICAST`                      | server → client  | Reply to `MODE_PROBE`.                     |
| `HELLO_FROM_CLIENT;v=<n>`      | client → server  | Connect request carrying client version.  |
| `HELLO_ACK;v=<n>`              | server → client  | Accept, carrying server version.          |
| `WFAS_INCOMPATIBLE;v=<n>`      | server → client  | Reject: version mismatch, server is `<n>`.|
| `PING`                         | server → client  | Keep‑alive (every 1 s; 3 s timeout).      |
| `BYE`                          | server → client  | Server stopping / client gone.            |
| `CLIENT_BYE`                   | client → server  | Client disconnecting cleanly.             |

The `;v=<n>` suffix is the only addition in v2. Parsers extract the version with
a tolerant token scan (`v=` followed by an integer); a missing suffix is treated
as version `0` (i.e. a pre‑v2 / legacy peer).

---

## 5. Version negotiation

Goal (from issue #4): when the two ends run different protocol versions, **close
the stream and show an "update required" dialog with the download link**, on
whichever device is running the newer build — regardless of which side is the
server (sender) and which is the client (receiver).

### 5.1 Unicast handshake

```
client                              server
  | --- HELLO_FROM_CLIENT;v=2 ---->  |
  |                                  |  parse client version
  |                                  |   ├─ matches  → start audio engine
  |  <------- HELLO_ACK;v=2 -------- |   │             reply HELLO_ACK;v=2
  |                                  |   └─ differs/absent
  |  <--- WFAS_INCOMPATIBLE;v=2 ---- |                 reply, keep serving others
```

* **Server** matches the request with `startsWith("HELLO_FROM_CLIENT")`, then
  reads `v=`. If the client version is absent or `!=` the server version, the
  server replies `WFAS_INCOMPATIBLE;v=<server>`, raises the mismatch signal
  locally (so the *server's* user sees the update dialog), and continues waiting
  for other clients. It does **not** start streaming to that client.
* **Client** inspects the reply:
  * `HELLO_ACK;v=<n>` with `n == own` → proceed to streaming.
  * `HELLO_ACK;v=<n>` with `n != own`, or `WFAS_INCOMPATIBLE;v=<n>` → raise the
    mismatch signal (the *client's* user sees the dialog) and abort.
  * anything else → existing generic "handshake failed".

### 5.2 Multicast

Multicast has no handshake. Compatibility is enforced solely by the
**first‑packet sentinel**: the client checks byte 2 of the first received audio
packet (Section 5.3).

### 5.3 First‑packet sentinel

On the first WFAS audio packet (magic matches) the client compares header byte 2
with its own version. On mismatch it raises the mismatch signal and stops. This
single check covers:

* multicast (no handshake exists), and
* any unicast server whose ACK negotiation was bypassed (defense in depth).

### 5.4 Behaviour on mismatch — both directions

| Older app role | Newer app role | How the newer app detects it                              | Dialog shown on |
|----------------|----------------|-----------------------------------------------------------|-----------------|
| Server         | Client         | `HELLO_ACK` version (unicast) or sentinel (multicast)     | Client          |
| Client         | Server         | missing/old `v=` in `HELLO_FROM_CLIENT` → reject          | Server          |

In both cases the device running the **newer** build stops the stream and shows:

> **Protocol version incompatible.** This device speaks WFAS v*X*, the other end
> speaks v*Y*. Please update so both run the latest version.
> [ Open downloads ]

Download links:
* Desktop: <https://github.com/marcomorosi06/WiFiAudioStreaming-Desktop/releases>
* Android: <https://github.com/marcomorosi06/WiFiAudioStreaming-Android/releases>

Each app's dialog links to **its own** releases page (the app the user is
currently looking at), and the text reminds them to update both ends.

### 5.5 Known legacy edge

A **v2 client → v1 server in unicast** cannot be reported as a clean version
mismatch: the v1 server requires the HELLO text to equal `HELLO_FROM_CLIENT`
exactly and silently ignores the `;v=2` suffix, so it never replies and the
client falls back to its existing 15 s "server did not respond" timeout. This
combination is only possible when one side was not updated; the multicast path
for the same pairing is fully covered by the sentinel, and once both ends are on
v2+ every path is clean. This is an inherent limitation of the unversioned v1
handshake and cannot be fixed without changing already‑released v1 builds.

---

## 6. Versioning policy

* `WFAS_PROTOCOL_VERSION` is a single integer constant defined once per app.
* Bump it by **+1** whenever a change makes the wire format incompatible with the
  previous version (header layout, payload encoding, or handshake semantics).
* Purely additive, backward‑compatible changes (e.g. a new optional discovery
  token) do **not** require a bump.
* The two apps must always ship the same `WFAS_PROTOCOL_VERSION` value for a
  given release wave.

---

## 7. Security (connection authorization)

An **optional** server-side toggle gates who may connect. It has three modes:

* **Off** — current behaviour: any client that completes the handshake streams.
* **Ask** — the server asks its user to approve each incoming client.
* **Key** — the client must prove it knows a pre-shared key.

Security applies to **unicast only**: multicast has no per-client handshake or
back-channel, so a multicast server cannot authorize individual receivers.

The audio packet format (Section 2) is **unchanged**. Security lives entirely in
the handshake (Section 5), as additional ASCII control messages. The extension is
additive: an old server ignores the extra HELLO tokens; an old client that
receives one of the new replies treats it as an unexpected response and falls back
to its generic "handshake failed" path.

### 7.1 New control messages

| Message                                   | Direction        | Meaning                                          |
|-------------------------------------------|------------------|--------------------------------------------------|
| `HELLO_FROM_CLIENT;v=<n>;cnonce=<hex>`    | client → server  | HELLO carrying a fresh client nonce.             |
| `HELLO_FROM_CLIENT;v=<n>;cnonce=<hex>;cproof=<hex>` | client → server | HELLO answering a key challenge.       |
| `WFAS_PENDING`                            | server → client  | Awaiting the server user's approval. Resent ~2 s.|
| `WFAS_AUTH_REQUIRED;snonce=<hex>;sproof=<hex>` | server → client | A key is required; server proves it too.    |
| `WFAS_UNAUTHORIZED`                       | server → client  | Denied (wrong key, user denied, or timed out).   |

`cnonce` / `snonce` are fresh random 16-byte values, lowercase hex (32 chars).
An optional `;name=<value>` token in HELLO carries a human-friendly device name
for the approval dialog.

### 7.2 Ask mode (interactive approval)

```
client                                   server
  | --- HELLO_FROM_CLIENT;v=2;name=… --->  |
  |  <------- WFAS_PENDING --------------- |  (UI dialog opens; resent every ~2 s)
  |               …user decides…           |
  |  <------- HELLO_ACK;v=2 -------------- |  (Allow)   → stream
  |    or  <-- WFAS_UNAUTHORIZED --------- |  (Deny / server-side timeout)
```

* The server **re-sends `WFAS_PENDING` every ~2 s** while the dialog is open
  (keep-alive). The client resets a watchdog on each one and **aborts if it sees
  neither `WFAS_PENDING` nor a final reply within ~6 s** — so a server that dies
  mid-prompt never leaves the client hanging.
* The client also enforces an **absolute cap** (e.g. 120 s) and offers the user a
  cancel, which sends `CLIENT_BYE` so the server can dismiss the dialog.
* If the server user ignores the dialog past the server's own timeout (e.g. 60 s),
  the server sends `WFAS_UNAUTHORIZED` so the client gets a clean answer.

### 7.3 Key mode (mutual challenge-response)

Both ends share a secret key `K`. The exchange authenticates **both** sides and
the key never travels on the wire:

```
client                                                server
  | --- HELLO_FROM_CLIENT;v=2;cnonce=C --------------->  |
  |  <-- WFAS_AUTH_REQUIRED;snonce=S;sproof=HMAC(K,…S) - |
  |     verify sproof  (authenticates the server)        |
  | --- HELLO_FROM_CLIENT;v=2;cnonce=C;cproof=HMAC(K,…C)>|
  |                              verify cproof            |
  |  <-- HELLO_ACK;v=2  or  WFAS_UNAUTHORIZED ---------- |
```

Proofs are HMAC-SHA256 over domain-separated, nonce-bound ASCII inputs:

```
sproof = hex( HMAC-SHA256(K, "WFAS-S:" + cnonce_hex + ":" + snonce_hex) )
cproof = hex( HMAC-SHA256(K, "WFAS-C:" + cnonce_hex + ":" + snonce_hex) )
```

* The `"WFAS-S:"` / `"WFAS-C:"` prefixes are **domain separation**: a server proof
  can never be replayed as a client proof.
* Both nonces appear in both proofs, binding the exchange and preventing replay
  and reflection.
* Verifying `sproof` lets the **client authenticate the server** — a rogue server
  that does not hold `K` cannot produce it, so it cannot be impersonated.
* This is authentication only: it decides **who** connects. It does **not** encrypt
  the audio (see Section 7.5).

### 7.4 The discovery beacon is not trusted

Discovery (Section 3) is unauthenticated multicast that anyone can spoof, so **no
security decision may depend on it**. The beacon carries at most a generic
`secure=1` hint (so the client can show a lock icon); it never advertises which
mode is in use.

Protection against a **downgrade attack** (a spoofed beacon or rogue server
claiming "no security") is enforced **client-side**: if the user configured the
client to require a key for a server, the client **must abort** unless the
handshake actually performed the key exchange — even if the server replied
`HELLO_ACK` directly. Pin the expectation per server (trust-on-first-use): once a
server is known to require a key, a later claim of "none" is ignored.

### 7.5 Encryption (reserved — optional future layer)

> **Not yet implemented — reserved.** Authentication (7.2–7.3) controls who
> connects but leaves the PCM payload in clear, so on a LAN the audio is still
> sniffable. A future **optional** layer may encrypt the payload: a session key
> derived from a shared password via a KDF (e.g. PBKDF2/Argon2) and a symmetric
> AEAD cipher (e.g. ChaCha20-Poly1305) — no asymmetric crypto required. It will be
> negotiated in the handshake and flagged per packet; **minimal/embedded peers may
> skip it** and remain conformant for the unencrypted profile. The wire details
> are intentionally left unspecified here until the layer is designed.
