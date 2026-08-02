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
WIFI_AUDIO_STREAMER_DISCOVERY;<hostname>;<mode>;<port>[;protocols=...][;http_port=...][;sr=..;ch=..;bd=..][;auth=...][;enc=...][;mic=...]
```

* `<mode>` = `MULTICAST` or `UNICAST`
* `<port>` = streaming port
* `auth=` = security-mode hint: `OFF`, `ASK`, or `KEY` (optional)
* `enc=` = `1` if the server encrypts traffic, else `0` (optional; `enc=1` implies `auth=KEY`)
* `mic=` = microphone hint, any of `tx` (server includes its mic in the stream) and `rx` (server accepts the client's mic / talk-back), e.g. `mic=tx`, `mic=rx`, `mic=txrx` (optional; omitted when neither applies)
* A shutdown beacon uses `;BYE;` in place of mode and removes the entry.

All tokens are `key=value` and order-independent; unknown tokens are ignored, so
`auth=`/`enc=` are backward-compatible additions. `auth=`/`enc=` are **display
hints only** (used to show a lock/key badge in the device list) and carry no
security weight — see Section 7.4.

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
| `WFAS_BUSY`                    | server → client  | Reject: the unicast session is already taken (Section 5.6). |
| `PING`                         | server → client  | Keep‑alive (every 1 s; 3 s timeout).      |
| `BYE`                          | server → client  | Server stopping / client gone.            |
| `CLIENT_BYE`                   | client → server  | Client disconnecting cleanly.             |

`WFAS_BUSY` is an optional, additive rejection (added in the v2 line, no version
bump — see Section 6): a server that does not implement it simply stays silent
and the client times out as before, so it is safe to omit in a minimal
implementation. A client that does not know it treats it as an unexpected reply
and falls back to its generic "handshake failed" path.

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

### 5.6 Session exclusivity (unicast)

A unicast server serves **exactly one** client at a time. From the moment the
handshake completes until the session ends, the server is bound to that client's
IP address, and the binding is enforced on **every** datagram received on the
streaming socket:

* `HELLO_FROM_CLIENT` or `MODE_PROBE` from **any other** address → the server
  replies `WFAS_BUSY` to that address and keeps the current session untouched.
* `CLIENT_BYE` (and any other control message) from **any other** address is
  **discarded**. A server that acts on `CLIENT_BYE` without checking the sender
  lets any host on the LAN tear down someone else's session.
* Datagrams from the connected client are processed normally.

The client, on receiving `WFAS_BUSY`, **must** abort the connection attempt
immediately — it must not keep retrying until timeout — and report to the user
that the server is streaming to another device.

While a unicast session is active the server also stops advertising itself in
the discovery beacon (Section 3), so it disappears from other devices' lists.
That is a courtesy, not a defence: a client can still reach the server by manual
IP or from a stale list entry, which is exactly why `WFAS_BUSY` exists.

Multicast has no session state and therefore no exclusivity: any number of
receivers may join the group.

### 5.7 Multicast teardown

A multicast server that stops sends `BYE` to the group three times, from a
cancellation-safe path so that it is emitted however the send loop ends — clean
break, exception, or coroutine cancellation. Receivers stop on the first one.

`BYE` is best effort and MUST NOT be relied on. There is no handshake, so there
is nothing to acknowledge it, and multicast on Wi-Fi is transmitted unreliably at
the lowest basic rate: losing all three copies is realistic, and the sender may
also be killed without running any teardown at all.

The only thing a receiver can rely on is the **encryption beacon**, which an
encrypted multicast server already emits every 400 ms (Section 8.4). Where that
heartbeat exists, a receiver **MUST** stop after **6 seconds** without any
datagram and report the server as gone.

The heartbeat only holds if the server drives it from a timer **independent of
the audio source**. A server that sends the beacon from inside its capture loop
will stop beaconing whenever the loop stalls — and it will: a loopback capture on
a machine that is playing nothing delivers no buffer at all, so the read blocks
and neither audio nor beacon leaves the machine. The session looks alive on the
sender and is dead on the wire. A conforming multicast server therefore **MUST**
send the beacon from its own timer.

There is deliberately **no keep-alive for the unencrypted case**: adding one would
mean putting a packet shape on the wire that no existing v2 peer emits, which is
not worth it for a liveness hint. An unencrypted multicast receiver therefore has
no heartbeat, and must not apply the timeout: if it misses `BYE` it waits, exactly
as before. Encryption is what buys reliable teardown detection.

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
(Multicast *encryption* is still possible via a shared key — see Section 8.)

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
* This is authentication only: it decides **who** connects. To also encrypt the
  audio, see Section 8.

### 7.4 The discovery beacon is not trusted

Discovery (Section 3) is unauthenticated multicast that anyone can spoof, so **no
security decision may depend on it**. The beacon may carry advisory `auth=`
(`OFF`/`ASK`/`KEY`) and `enc=` (`0`/`1`) hints so the client can show a lock/key
badge in the device list. These are **display-only**: the client MUST NOT use
them to decide whether to require a key or to expect encryption — that is always
settled by the handshake.

Protection against a **downgrade attack** (a spoofed beacon or rogue server
claiming "no security") is enforced **client-side**: if the user configured the
client to require a key for a server, the client **must abort** unless the
handshake actually performed the key exchange — even if the server replied
`HELLO_ACK` directly. Pin the expectation per server (trust-on-first-use): once a
server is known to require a key, a later claim of "none" is ignored.

## 8. Encryption

Authentication (Section 7) controls *who* connects; encryption protects *what* is
sent. It is an **optional** layer, negotiated in the handshake and flagged per
packet, so unencrypted peers remain conformant. Encryption requires a pre-shared
key (Key mode): it is the shared secret all key material is derived from — there
is no asymmetric/PKI exchange.

### 8.1 Cipher and key schedule

The payload is sealed with **ChaCha20-Poly1305** (RFC 8439), chosen for constant-
time software performance on devices without AES acceleration (e.g. Raspberry Pi).
Session keys come from **HKDF-SHA256** (RFC 5869):

- **Unicast.** After the Section 7 handshake both ends share the key `K` and the
  two nonces `cnonce`,`snonce`. They derive, with `salt = cnonce‖snonce` (ASCII)
  and `ikm = K`:
  - `key_c2s = HKDF(salt, K, "WFAS c2s key", 32)`, `prefix_c2s = HKDF(..., "WFAS c2s iv", 4)`
  - `key_s2c = HKDF(salt, K, "WFAS s2c key", 32)`, `prefix_s2c = HKDF(..., "WFAS s2c iv", 4)`

  Separate keys per direction; a fresh per-session key because the nonces are fresh.
- **Multicast.** No handshake exists, so the key derives from `K` and a random
  per-session `salt` announced in the beacon (8.4): `key = HKDF(salt, K, "WFAS mcast key", 32)`,
  `prefix = HKDF(salt, K, "WFAS mcast iv", 4)`.

### 8.2 Encrypted packet format

```
[ header 10B ]   magic, version, flags|ENCRYPTED(0x02), seq(BE16), samplePos(BE32)
[ counter 8B ]   monotonic per-packet counter, big-endian (the nonce low bytes)
[ ciphertext ]   ChaCha20-Poly1305(payload)
[ tag 16B ]      Poly1305 authentication tag
```

- The 10-byte header travels in clear and is bound as **associated data**, so
  `seq`/`samplePos`/flags are authenticated though readable.
- `nonce(12B) = prefix(4B) ‖ counter(8B big-endian)`. The counter is transmitted
  so the receiver can reconstruct the nonce despite loss/reorder. An 8-byte
  counter never overflows in practice, so no volume-based re-keying is needed.
- A silence frame is sent with an empty payload (counter + tag only), keeping it
  authenticated.

### 8.3 Overhead, MTU and anti-replay

- Overhead is **24 bytes** per packet (8 counter + 16 tag). When encryption is on,
  the sender lowers its payload cap by 24 so the encrypted datagram still fits the
  MTU and avoids IP fragmentation (which badly degrades mobile networks).
- AEAD authenticates each packet but not its **freshness**, so a receiver MUST run
  an **anti-replay sliding window** (a bitmask sized at or above the jitter-buffer
  depth in packets; the reference uses 1024). Order of operations: cheap pre-check
  (drop if the counter is too old or already seen) → verify the tag → update the
  window **only after** authentication succeeds. The window resets on a new session
  (new handshake, or new multicast salt).

### 8.4 Multicast beacon

Because multicast has no back-channel, the server announces the session key
material in a periodic clear beacon (≈1/s, more frequent at start to shorten join):

```
WFAS_MCAST_ENC;epoch=<n>;time=<unix>;salt=<hex>;mac=<hex>
mac = HMAC-SHA256(K, "WFAS-MCAST:epoch=<n>;time=<unix>;salt=<hex>")
```

The salt is public (the key needs `K` too); the **MAC binds epoch/time/salt** so a
party without `K` cannot inject a fake salt. A joining receiver waits for one valid
beacon, derives the key and starts decrypting. `epoch` is a server-persisted
**monotonic counter** (survives reboot, needs no clock): clients reject any beacon
with `epoch ≤` the highest they have accepted — this defeats replay of a whole
recorded session even on devices without NTP. `time` is a best-effort Unix
timestamp clients may additionally reject when stale.

### 8.5 Negotiation and anti-downgrade

Encryption is negotiated inside the authenticated handshake and the negotiated
flag is bound into the proof transcript, so a man-in-the-middle cannot strip it. A
client that requires encryption **aborts** if the server does not confirm it.

### 8.6 Threat model (multicast, symmetric-only)

With a shared group key and no signatures, any group member can both decrypt and
encrypt. Consequences, accepted by design:

- **No source non-repudiation.** A malicious group member can inject audio that
  appears to come from the server. The pre-shared key is the trust boundary:
  multicast encryption assumes mutual trust among all participants. The only true
  fix is per-server asymmetric signatures (e.g. Ed25519), deliberately out of
  scope here.
- **Ghost replay** of an entire past session is mitigated by the monotonic `epoch`
  (8.4); a brand-new client with no history and the real server offline may accept
  a replayed session once — an inherent limit of multicast without a back-channel.

---

## 9. QR pairing (application-level bootstrap)

**This section is not part of the WFAS v2 wire protocol.** Nothing here changes the packet
format (Section 2), the handshake (Sections 5 and 7) or the key schedule (Section 8). QR
pairing is a *bootstrap and UX layer sitting on top of Key mode*: it decides how the
pre-shared key `K` reaches the second device, not how `K` is used once both ends have it.

An implementation that does not support QR pairing remains fully conformant.

### 9.1 QR pairing is Key mode

`SecurityMode` is **unchanged**: it remains `OFF` / `ASK` / `KEY`. QR pairing is not a
fourth mode. A device doing QR pairing *is* in Key mode, and everything it puts on the
wire is byte-identical to Key mode:

* same challenge-response (`WFAS_AUTH_REQUIRED` / `cproof` / `sproof`, Section 7.3),
* same HKDF-SHA256 derivation and ChaCha20-Poly1305 sealing (Section 8),
* same advisory beacon hint, `auth=KEY` (Section 7.4).

The only difference is the **provenance of `K`**, which never appears on the wire:

| | `K` comes from |
|---|---|
| Key mode, manual | a passphrase typed by the user on both devices |
| Key mode, QR pairing | 256 bits from a CSPRNG, encoded Base64 URL-safe without padding, transferred out-of-band via QR |

Because a QR-paired key is a full-entropy 256-bit secret rather than a human-chosen
passphrase, it removes the weakest link of Key mode without adding a single cryptographic
primitive.

**This is deliberate, and it is a compatibility requirement, not an implementation
detail.** Announcing a new `auth=` value would have three costs, all avoidable:

1. peers built before this section would classify the server as "no security" in their
   device list and CLI output, because they match `auth=` against a closed set;
2. a user who downgrades to an earlier build would find the persisted mode unrecognised,
   silently falling back to `OFF` — a security regression triggered by an app rollback;
3. it would put a value on the wire that Section 7 never defined.

Implementations MUST therefore persist and announce `KEY`, and track "this key came from a
QR invite" as **local UI state only**.

### 9.2 Pairing URI

Unicast:

```
wifiaudio://pair?ip=<ip>&port=<port>&mode=unicast&key=<base64url>&exp=<unix_ts>&v=2
```

Multicast:

```
wifiaudio://pair?ip=<group_ip>&port=<port>&mode=multicast&key=<base64url>&epoch=<n>&exp=<unix_ts>&v=2
```

| Field | Presence | Meaning |
|---|---|---|
| `ip` | always | Peer address (unicast) or multicast group (multicast). |
| `port` | always | WFAS streaming port, 1–65535. |
| `mode` | always | `unicast` or `multicast`. |
| `key` | always | `K`, 256 bits, Base64 URL-safe, unpadded (43 chars). |
| `exp` | always | Unix seconds. Expiry of **the invite**, not of the key. |
| `epoch` | multicast only, optional | Current value of the server-persisted multicast `epoch` (Section 8.4). |
| `v` | always | URI schema version. `2`, aligned with the WFAS protocol version. |

`epoch` is what makes a late join work. A receiver that joins after one or more server
restarts must know which epoch to expect, otherwise the anti-replay rule of Section 8.4
(`reject any beacon with epoch ≤ the highest accepted`) discards the session silently and
the user sees nothing but silence. It is present only when the multicast beacon is
actually running, i.e. when encryption is enabled.

An equivalent HTTPS App Link form carries the same fields **in the fragment**:

```
https://www.marcomorosi.eu/wifi-audio-streaming/pair#<same fields>
https://www.marcomorosi.eu/it/wifi-audio-streaming/pair#<same fields>
```

This is the form the QR encodes, so that a scanner without the app lands on a page that
offers the download instead of a dead custom scheme. The fragment is not a cosmetic
choice: per RFC 3986 it is never transmitted to the origin server, so `key` cannot reach
an access log, a `Referer` header, or a reverse proxy. The path alone is requested.

A parser MUST read the fields from the fragment when present and fall back to the query
string otherwise, so that both forms — and links produced by earlier builds — resolve
identically. The custom-scheme form keeps the query string: it never reaches a server.

### 9.3 Parsing rules

A conforming parser MUST reject the URI, in this order, before using any field:

1. scheme is not `wifiaudio` with host `pair` (or the HTTPS App Link form above);
2. `v` is not a supported version;
3. a field required for the given `mode` is missing or out of range;
4. `exp` is in the past, allowing a clock-skew tolerance (30 s in the reference).

Rejection is reported as a single "invalid" outcome; the caller decides what to show. The
distinction between *expired* and *malformed* is made by re-parsing with the expiry check
disabled.

The URI MUST be parsed with a real URI parser, not by string splitting: `ip` can be an
IPv6 literal in brackets and is therefore percent-encoded inside the query.

### 9.4 Invite lifetime

The reference TTL is **120 seconds**. Expiry bounds the window in which a QR photographed
by a bystander, or left on screen, is usable. It does not bound the life of `K`: once the
handshake has completed, the session is governed by Sections 7 and 8 as usual.

* **Unicast** — every invite carries a **freshly generated** `K`. A unicast session serves
  one client, so an invite and a new key are the same act.
* **Multicast** — an invite **reuses the current group key** and only carries a new `exp`.
  Generating a new key would evict every already-connected receiver, so it is a separate,
  explicitly confirmed action.

### 9.5 Multicast re-key

Replacing the group key is an operation the user performs deliberately, for example after
a device is believed to be compromised. It:

1. generates a fresh `K'`;
2. draws a fresh salt and increments the persisted `epoch` (Section 8.4);
3. rebuilds the beacon and the multicast direction from `K'`.

Receivers holding the old key can no longer authenticate the beacon MAC, so they stop
deriving new material and fall silent — the intended outcome. Receivers that pair again
from a new QR pick up `K'` and the new epoch. The audio stream itself is not interrupted
for the sender; no re-handshake exists in multicast to interrupt.

### 9.6 What QR pairing does not do

* It does not authenticate the **channel** the QR travels over. Anyone who can photograph
  the screen within the TTL obtains `K`. This is the same trust model as reading a
  passphrase aloud, with a shorter window and a stronger secret.
* It does not change the multicast threat model of Section 8.6: the group key remains
  symmetric, so any member can both decrypt and inject.
* It does not remove the anti-downgrade obligation of Section 7.4. A client paired in `QR`
  mode MUST still abort if the handshake does not actually perform the key exchange.

### 9.7 Implementation note

`WfasPairingUri` (`build` / `parse`) is a pure, dependency-free function pair and is
**duplicated verbatim** in the Android and Desktop code bases. The two copies must stay
byte-identical; a change to the URI schema is a change to this section and to both files.
