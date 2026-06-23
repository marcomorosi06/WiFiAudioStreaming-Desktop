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
