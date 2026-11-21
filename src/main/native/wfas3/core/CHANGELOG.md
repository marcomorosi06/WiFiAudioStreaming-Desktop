# Changelog - WFAS v3

## 2026-11-21 - security fixes; specification draft 7

Changed (wire format: draft 7 does not interoperate with draft 6 for pairing)
- PairBeacon byte 6 is a flags byte, bit0 = one-use secret (code). A client
  ignores a state-1 beacon of a permanent secret (label) unless it carries
  the window_nonce of its run, so recorded label beacons can no longer end a
  pairing or raise a false race warning (13.2, G1).
- Pairing step 10, Retry (48 bytes), and the Retry token in bytes
  1264..1280 of step 1: only a step 1 with the token of its address, port
  and pair_sid takes part in admission; a run without step 3 gives way after
  3 s to an address whose turn it is; 16 addresses remembered
  (`WFAS3_PAIR_SRC`, now in `wfas.h`) (13.4, 13.7, G3).
- A step 2 whose pairing key does not match the code is ignored like a
  forgery; the run fails `WFAS3_PAIRF_KEY` with `WFAS3_EV_PAIR_RACE` only if
  the real step 2 does not come within 5 s (13.3, G2).
- Tag computations (7.2): first try for the pair last seen at the source
  address, 16 per second per pair outside the caps; the others count in a
  share of 512 per second for each of the last 16 source addresses and in
  the global cap (G4).
- Cookie secrets carry their creation time: accepted for mac2 only for 60 s,
  erased by `wfas3_poll` even without traffic; `wfas3_next_deadline` covers
  them (7.5, G5).
- `wfas3_name_clean` also removes every Default_Ignorable_Code_Point and
  U+2800 (13.2, G6).
- `WFAS3_EV_PAIR_ASK` carries the client's address in `out.addr` (13.4, G7);
  `wfascli` prints it.
- `WFAS_V3_PROTOCOL.md`: draft 7, sections 3, 7.2, 7.5, 9.4, 12, 13.2 to
  13.4, 13.7, 15, 16, 17, Appendix A (table G) and C.

Added
- `tests/test_pair.c`: replayed label beacons and beacon flags, Retry and
  tokens, step 1 floods from addresses that do not answer and from a few that
  do, step 2 with another pairing key followed by the real one, tag budget
  under floods from one and from many addresses, cookie age, question
  address, more name cleaning cases.
- `tests/peer_pair.py`, `tests/interop_pair.py`: beacon flags, Retry and
  tokens in both roles, a played label beacon, the delayed KEY failure, the
  address in the question.
- `tests/mutate.py`: 15 pairing and entry mutations of `wfas.c` and 2 of
  `tests/peer_pair.py` for the rules above.

No third-party file was added or changed; `NOTICE` and
`third_party/MANIFEST.sha256` are unchanged. WFAS v2 is not touched.

## 2026-11-17 - pairing, multicast and command-line stage; specification draft 6

Added
- `third_party/fiat/`: fiat-crypto `curve25519_32.c` (commit 3d2880ef2803),
  byte-identical, with its licence and author files; pinned in
  `third_party/MANIFEST.sha256`. `wfas_tp.h`, `wfas_tp.c`: field type and
  operations on it (`wfas_tp_fe_*`).
- `wfas.c`: Elligator 2 (RFC 9380 G.2.1, straight line, constant time) and
  CPace (draft-irtf-cfrg-cpace-21, CPACE-X25519-SHA512).
- `wfas.h`, `wfas.c`: pairing (13) in both roles: window and beacon, the
  nine steps with retransmission and copies, code, label and PIN methods,
  the 6-digit code with commitments, approval questions, guests, replacement
  of a pair, unused pairs erased after 10 minutes, step-1 admission, race
  warning. New functions `wfas3_code_format`, `wfas3_code_parse`,
  `wfas3_name_clean`, `wfas3_label_create`, `wfas3_label_load`,
  `wfas3_label_code`, `wfas3_window_open`, `wfas3_window_close`,
  `wfas3_pair_answer`, `wfas3_window_failed`, `wfas3_beacons`,
  `wfas3_pair_start`, `wfas3_pair_confirm`, `wfas3_pair_cancel`; events
  `WFAS3_EV_BEACON` to `WFAS3_EV_WINDOW_CLOSED`; `wfas3_out` gains `code`,
  `ask`, `pflags`, `name`, `beacon`.
- `wfas.h`, `wfas.c`: multicast (14): one group per source, members by
  START, GROUP_JOIN, GROUP_ACK, GROUP_LEAVE, key epochs given out before use
  and changed at once on revocation, NACK with resends in the session,
  de-duplication. New functions `wfas3_group_open`, `wfas3_group_close`,
  `wfas3_group_send`, `wfas3_group_members`; events `WFAS3_EV_GROUP_JOIN`,
  `WFAS3_EV_GROUP_LEAVE`; `WFAS3_GROUP_SLOTS`, `WFAS3_MCAST_HISTORY` (64,
  can be lowered), `WFAS3_MCAST_DEDUP`.
- `wfascli.c`: command-line client for Linux: init, label-create, pairs,
  remove, pair-server, pair-client, find, send, multicast, listen; records in
  a 0700 directory, 0600 files written by rename, no symbolic links, erased
  by overwriting; getrandom, CLOCK_BOOTTIME, locked memory, no core dumps.
- Tests: `tests/test_pair.c` (pairing suite), `tests/test_mcast.c`
  (multicast suite), `tests/peer_pair.py` and `tests/peer_mcast.py` (second
  implementations of 13 and 14, written from the specification) with
  `tests/interop_pair.py` and `tests/interop_mcast.py` (both roles each),
  `tests/cli_test.py` (two `wfascli` devices on one machine). Elligator 2
  and CPace known answers in `tests/test_base.c`, from RFC 9380 J.4,
  draft-irtf-cfrg-cpace-21 B.1 and the Python reference. `tests/interop.c`:
  pairing and multicast commands, longer output line.
- `tests/mutate.py`: 12 pairing and 5 multicast mutations of `wfas.c`, 8
  mutations of the new second implementations.
- `Makefile`: the new suites with GCC, Clang, sanitizers and Valgrind; the
  new interoperability tests with and without sanitizers; `wfascli` built by
  `all` with GCC and Clang and analysed with cppcheck and the Clang
  analyser; target `cli-test`.
- `NOTICE`: fiat-crypto (used under MIT) with the MIT text; the IETF and IRTF
  test data in `tests/`, with the Revised BSD licence text.

Changed
- `wfas3_connect` and the responder accept bit2 (8.3); `wfas3_send_audio`
  and `wfas3_stream_params` refuse the main stream of a group session.
- `wfas3_pair_import` refuses a record with the guest flag; a guest record is
  never exported; STORE_PAIR is never raised for a guest.
- `wfas3_pair_remove` of a guest pair closes with BYE 4 and keeps the entry
  key; BYE 4 received in a guest session ends the guest pair;
  `wfas3_session_close` accepts reason 4 only for a guest.
- No ENTRY_KEY is sent to a guest; ENTRY_KEY and ENTRY_KEY_ACK of a guest
  are ignored.
- Seal and Open take the 4 high bytes of the nonce (zero except in a
  multicast packet).

Fixed during this stage (found by the new tests)
- Under loss, a server waiting for step 8 did not answer copies of step 5,
  because its own retransmissions of step 7 kept postponing the answer: the
  client never saw step 6 and both sides timed out. Every authentic copy of
  step 5 is now answered with step 6.
- A source sent group packets before any member held the key, and a
  receiver did not ask again for the packets between its join and the first
  one it received.

Specification draft 6 (Appendix A, F1 to F16)
- Pairing tokens, KDF with 16-byte keys, retransmission and waits of the
  pairing steps, refused step 7, SAS byte order, race warning, approval,
  guests, imported unused pairs and replacement on both sides, step-1
  admission; multicast state machines, bit2 negotiation, order of the group
  frames, multicast nonce; fiat-crypto pinned; client storage rules.

WFAS v2 is not touched by any of this.

## 2026-11-17 - discovery stage; specification draft 5

Added
- `wfas.h`, `wfas.c`: discovery (12). `wfas3_find` starts or stops a search
  for the peer of a pair: a Probe at once and every 2 s, at most 15, to the
  last seen address and to the broadcast address in turn; ends with the new
  events `WFAS3_EV_FOUND` (with `out.addr`) or `WFAS3_EV_NOT_FOUND`. The
  receiver answers fresh Probes once per 2 s per pair and 10 per second in
  total, stale Probes once per 30 s per pair and 2 per second in total from
  a budget of their own; copies get no answer. `wfas3_out` gains
  `broadcast` and `addr`; `WFAS3_MAX_FINDS`.
- The last seen address of each pair, in RAM only: the address of its
  latest session, or of the latest accepted ProbeReply.
- `tests/peer.py`: Probe and ProbeReply, written from section 12.
  `tests/interop.c`: command `find`, output with the broadcast flag and the
  found address. `tests/interop.py`: discovery in both directions, counters,
  copies, stale Probes, every changed byte of a ProbeReply, cadence,
  alternation, the end of a search (806 checks).

Changed
- The pair record is now written when `ectr_seen`, `ectr_used` or
  `probe_seen` change, at most once a minute for these values alone (7.3).
  Before, they were written only together with other changes of the record.
- A record that fails to import is erased whole, derived keys included.

Specification draft 5 (Appendix A, E1 to E7)
- Search, broadcast address, alternation, last seen address, matching of a
  ProbeReply, separate budget of stale answers to Probes, the write rule of
  7.3.

Not covered by a dedicated C test suite: discovery (tested by the
interoperability test only), and transport and rekey (see the previous
entry). Not yet implemented: pairing, multicast, `wfascli.c`.

## 2026-11-17 - transport stage; specification draft 4

Added
- `wfas.h`, `wfas.c`: transport (10): frames, control packets every 100 ms
  built by `wfas3_poll()`, audio packets, consent with START, STOP and
  REPORT, presence warning after 1 s and close after 15 s, closing with
  three BYE packets, address change with a path check, synchronised
  playback (bit1), microphone stream (bit3); rekey (11) in both roles;
  entry key rotation with ENTRY_KEY and ENTRY_KEY_ACK and pair removal with
  FORGET (9.4). Time is now in microseconds.
- New functions: `wfas3_next_deadline`, `wfas3_pair_remove`,
  `wfas3_send_audio`, `wfas3_stream_want`, `wfas3_stream_params`,
  `wfas3_mic_off`, `wfas3_audio_late`, `wfas3_set_buffer_ms`,
  `wfas3_session_close`, `wfas3_session_info`. New status `WFAS3_E_PERM`;
  events `WFAS3_EV_AUDIO` to `WFAS3_EV_PATH_CHANGED`; BYE reasons and
  close codes; `wfas3_out` carries audio.

Removed
- `wfas3_session_control`: control packets now come only from
  `wfas3_poll()`, at the cadence of 10.3.

Changed
- `tests/test_hs.c`, `tests/interop.c`, `tests/interop.py`: time in
  microseconds, control packets taken from `wfas3_poll()`. The handshake
  suite checks its invariants on every delivered packet, now including the
  control packets of every 100 ms (372,453 checks).
- `tests/mutate.py`: two targets follow the code they mutate.

Fixed during this stage (found by review)
- `wfas3_send_audio` returned an error while leaving a BYE packet and a
  session-down event in its output when consent had lapsed or the counter
  was spent. It now leaves nothing; `wfas3_poll()` reports both.
- A lack of entropy while a rekey message was computed closed the session.
- The deadline of a rekey start ignored the 100 ms gap between rekey
  computations, so an application sleeping until the deadline woke up in a
  loop.
- `wfas3_session_close` accepted an unconfirmed session, which the
  application never saw come up.
- Receive keys are erased as soon as a session starts closing.
- Two frame buffers were passed, with length zero, before being set; GCC
  flagged it in the sanitizer build.

Specification draft 4 (Appendix A, D1 to D15)
- Time in microseconds; frame rules, packet kinds and the order of frames
  in control packets; streams and roles, the microphone stream; consent,
  presence and closing; path check; formulas of synchronised playback;
  rekey transcript, ids, chunks and acknowledgements; entry key
  acknowledgement and FORGET; the old receive key kept 1 s after a rekey,
  without advancing the counter; START also ends the announcement of new
  parameters.

Not yet covered by a dedicated test suite: the transport and rekey code
beyond what the handshake suite and the interoperability test exercise. Not
yet implemented: discovery, pairing, multicast, `wfascli.c`.

## 2026-11-17 - implementation, entry and handshake stage; specification draft 3

Added
- `wfas.h`, `wfas.c`: stored records (5), entry with mac1, pair tag, masked
  counter and cookies (7), handshake Init, Resp and Reject (8), attempts,
  pair psk and session reports (9.1 to 9.3), padding-only control packet
  (10.1). New functions: `wfas3_device_init`, `wfas3_device_export`,
  `wfas3_add_entropy`, `wfas3_set_time`, `wfas3_set_policy`,
  `wfas3_pair_import`, `wfas3_pair_export`, `wfas3_connect`, `wfas3_poll`,
  `wfas3_receive`, `wfas3_session_control`. New events
  `WFAS3_EV_ATTEMPT_YIELDED`; `wfas3_out.config`.
- `wfas_tp_drbg_left()` in the glue.
- `tests/test_hs.c`: handshake suite (8390 checks).
- `tests/peer.py`, `tests/interop.c`, `tests/interop.py`: second
  implementation of sections 6 to 9 and interoperability test (739 checks).
- `tests/mutate.py`: 18 changes to `wfas.c` (defects found so far, rules
  C2, C3, C5, R2, I2 removed) and 11 to `tests/peer.py`, one at a time; the
  suites must fail for each.
- `Makefile`: targets `interop`, `model`, `mutate`, `analyze`; `check` now
  also runs the new suites with GCC, Clang, sanitizers and Valgrind, the
  model, cppcheck and the Clang analyser, and checks every project file for
  non-ASCII bytes.

Fixed during this stage (found by review, tests or the second implementation)
- Resp: the public sender_index was taken from the bytes of the ML-KEM
  message, exposing 32 of its 256 bits. Now drawn separately.
- A pair reloaded while the device runs restarted its entry counter from the
  reserve and could repeat a value, causing a false theft alert.
- Receive indices could collide between attempts and sessions.
- A responder with all session slots taken answered busy even when the new
  session would replace an unconfirmed one of the same pair.
- An unconfirmed session was never discarded.
- The confirmation packet changed the session address without a path check.
- The high side reported its yielded attempt as a failure.

Specification draft 3 (Appendix A, C1 to C8)
- PCM frame_samples defined with floor; stale report alerts the responder
  too; counter of a reloaded pair; distinct receive indices; separate
  randomness for public fields; abort on a non-increasing session number;
  15 s expiry of unconfirmed sessions; responder attempt check in 8.1.
- Model: "no Reject between honest devices" is now a checked invariant.

Not yet implemented: frames and audio, control cadence and presence timers,
path change, rekey, discovery, pairing, multicast, entry key rotation,
`wfascli.c`.

## 2026-11-16 - implementation, foundation stage

- Added `third_party/`: HACL* (hacl-packages 05c3d8fb) and the libcrux
  ML-KEM-768 header of OpenSSH V_10_2_P1, byte-identical to upstream, with
  `third_party/MANIFEST.sha256` checked before every build.
- Added `NOTICE` with the full licence texts (MIT, Apache-2.0) and `LICENSE`.
- Added `wfas_tp.h`, `wfas_tp.c`, `wfas_tp_mlkem.c`: glue to the primitives.
- Added `wfas.h`, `wfas.c`: datagram classifier, KDF, MAC, AEAD, handshake
  symmetric state, random generator wrapper, power-on self-test.
- Added `tests/ref.py`, `tests/gen_vectors.py`, `tests/vectors.h`,
  `tests/test_base.c` and `Makefile` (`make check`).
- Not yet implemented: entry, handshake, pair state machines, transport,
  rekey, discovery, pairing, multicast, `wfascli.c`.

## 2026-11-16 - specification draft 2 (experimental)

- Fixed: a replayed CookieRequest with a counter equal to the highest seen
  obtained a stale notice and let anyone without keys mark a pair suspect.
  Equal counters are now dropped in silence (7.3, 7.5, 12).
- PairBeacon: byte 5 is now `state` (0 open, 1 used). Mandatory warning after
  a lost pairing race (13.2).
- Text corrected: key-compromise impersonation (16.5), entry key rotation
  cost (9.4), limits of theft detection (16.3), limits of the model (9.5).
- Model: stale notice, quota drop and rate-limit drop added; 911,840 states at
  6 attempts.

## 2026-11-16 - specification draft 1 (experimental)

- Added `WFAS_V3_PROTOCOL.md`: byte-level specification of WFAS v3, derived
  from the design document revision 4. Appendix A lists every change made to
  the design and why.
- Added `model/psk_model.py`: bounded exhaustive model of the attempt rules,
  the pair psk and the session reports (specification sections 9.1 to 9.3).
- No code and no third-party file yet. `NOTICE` and
  `third_party/MANIFEST.sha256` are added together with the first vendored
  file. WFAS v2 is untouched.
