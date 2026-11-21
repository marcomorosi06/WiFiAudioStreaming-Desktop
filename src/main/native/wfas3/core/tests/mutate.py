#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Marco Morosi
#
# Mutation test. Each entry changes one place, either in wfas.c (checked by
# tests/test_hs.c, tests/test_pair.c or tests/test_mcast.c) or in a second
# implementation (tests/peer.py, peer_pair.py or peer_mcast.py, checked by
# tests/interop.py, interop_pair.py or interop_mcast.py), to bring back a
# defect that was found and fixed, or to remove one rule of the
# specification. The test suites must fail for every entry; an entry that
# survives means that the suites no longer see that defect. A control entry
# with no change must pass.
#
# Usage: python3 -I tests/mutate.py <third-party object dir> <interop core object>
#   e.g. python3 -I tests/mutate.py build/tp build/wfas.o

import glob
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

CORE = [
    ("no change (control)", "/* rule C2 */", "/* rule C2 */"),
    ("rule C2 removed",
     "        p->ectr_used = p->ectr_seen;                     /* rule C2 */", ""),
    ("rule C3 removed: the low side answers too",
     "            answer = 0U;                                 /* rule C3: low side keeps its attempt */",
     "            wfas3_att_free(&dev->attempts[ai]);"),
    ("rule C5 removed",
     "        } else if ((p->ectr_used < p->ectr_seen) && (dev->now_ms < p->cookie_hold_ms)) {",
     "        } else if (0) {"),
    ("psk rule R2 removed",
     "    if (c->slot != 0U) {                                 /* rule R2 */", "    if (0) {"),
    ("psk rule I2 removed",
     "    if (a->psk_slot != 0U) {                             /* rule I2 */", "    if (0) {"),
    ("equal counter takes the stale path (draft 1 defect)",
     "        } else if (ectr < dev->pairs[pair].ectr_seen) {",
     "        } else if (ectr <= dev->pairs[pair].ectr_seen) {"),
    ("counter restarts from the reserve when a pair is reloaded",
     "p->ectr_send = dev->ectr_high;",
     "p->ectr_send = dev->ectr_limit - (uint64_t)WFAS3_ECTR_BLOCK;"),
    ("ML-KEM message bytes reused as the public Resp index",
     "        wfas3_wr_be32(&r[4], index);", "        memcpy(&r[4], &rnd[32], 4U); (void)index;"),
    ("colliding receive index accepted",
     "        } else if (wfas3_index_used(dev, wfas3_rd_be32(b)) == 0U) {",
     "        } else if (1U == 1U) {"),
    ("no alert on a stale report at the responder",
     "            st = wfas3_reject_build(dev, pair, d, from, 2U, out);\n            wfas3_suspect(dev, pair, out);",
     "            st = wfas3_reject_build(dev, pair, d, from, 2U, out);"),
    ("report check of the Resp skipped",
     "            if (wfas3_rep_class(p, payload, wfas3_rd_be64(&payload[16])) != WFAS3_CLS_OK) {",
     "            if (0) {"),
    ("mac2 not checked",
     "            (wfas3_init_mac2_ok(dev, p, d, from) == 1U)) {", "            (1U == 1U)) {"),
    ("cookie not consumed",
     "                p->ectr_used = p->ectr_seen;             /* step 5: cookie consumed */", ""),
    ("Resp pair_mac not checked",
     "        if ((wfas3_resp_mac_ok(p, a, d) == 1U) &&", "        if ((1U == 1U) &&"),
    ("replay window not checked",
     "            (wfas3_win_check(w, ctr) == 1U) &&", "            (1U == 1U) &&"),
    ("unconfirmed sessions never expire",
     "            wfas3_ses_free(s);                           /* never confirmed: no event */",
     "            (void)s;"),
    ("busy when no slot is free, even if one would be replaced",
     "(wfas3_ses_available(dev, p) == 0U)", "(wfas3_ses_alloc(dev) == WFAS3_NONE)"),
    ("the high side reports a failure instead of yielding",
     "out->events |= WFAS3_EV_ATTEMPT_YIELDED;", "out->events |= WFAS3_EV_ATTEMPT_FAILED;"),
]

# Pairing (13), checked by tests/test_pair.c.
PAIR = [
    ("no change (control)", "/* a key for this guest only */", "/* a key for this guest only */"),
    ("a copy of step 5 after step 6 is not answered (deadlock under loss)",
     "(step == 5U) && (c->state == WFAS3_PS_S7) &&", "(step == 5U) && (c->state == 99U) &&"),
    ("a refused step 7 carries the decision yes and the entry key",
     "    memset(pt, 0, sizeof pt);\n    if (c->decision == 1U) {",
     "    memset(pt, 0, sizeof pt);\n    if (1U == 1U) {"),
    ("a guest gets the device entry key",
     "        if (c->guest == 1U) {\n            st = wfas3_rng_bytes(&dev->rng, c->entry, 32U);",
     "        if (0) {\n            st = wfas3_rng_bytes(&dev->rng, c->entry, 32U);"),
    ("a headless window grants the microphone unasked",
     "c->perms = w->perms & (((c->flags & WFAS3_PAIR_MIC) != 0U) ? 0xFFU : ~WFAS3_PERM_MICROPHONE);",
     "c->perms = w->perms;"),
    ("the fingerprint of W is not checked",
     "        if (wfas3_ct_eq(fp, c->fp_w, sizeof fp) == 0U) {", "        if (0) {"),
    ("a forged step 2 ends the run",
     "            c->bad2_ms = dev->now_ms;\n        }\n        st = WFAS3_E_DROP;",
     "            c->bad2_ms = dev->now_ms;\n        }\n        wfas3_pcli_fail(dev, WFAS3_PAIRF_WRONG, out);\n        st = WFAS3_OK;"),
    ("no total cap on step 1",
     "(wfas3_rate_take(&w->s1_win_ms, &w->s1_count, now, WFAS3_PAIR_S1_CAP) == 1U)",
     "(wfas3_rate_take(&w->s1_win_ms, &w->s1_count, now, 1000U) == 1U)"),
    ("every copy of step 1 is answered (amplification)",
     "        if ((c->state == WFAS3_PS_S2) && (wfas3_ct_eq(h, c->rx_hash, sizeof h) == 1U) &&\n            (dev->now_ms >= c->next_tx_ms)) {",
     "        if ((c->state == WFAS3_PS_S2) && (wfas3_ct_eq(h, c->rx_hash, sizeof h) == 1U)) {"),
    ("an unused pair is never erased",
     "                   (dev->now_ms >= p->unused_until_ms) && (wfas3_pair_has_session(dev, i) == 0U)) {",
     "                   (0U == 1U)) {"),
    ("the server keeps the pair a new pairing replaces",
     "                    dev->replace_pending = i;            /* removed at the next poll (13.5) */",
     "                    (void)i;"),
    ("the server's code comes from the wrong hash",
     "                c->code = wfas3_pair_sas(c->h, n_c, c->n_s);",
     "                c->code = wfas3_pair_sas(c->h3, n_c, c->n_s);"),
    ("a guest record is stored",
     "    if ((p->flags & WFAS3_PF_GUEST) == 0U) {       /* guest records are never stored (13.6) */",
     "    if (1U == 1U) {"),
]

# Multicast (14), checked by tests/test_mcast.c.
MCAST = [
    ("no change (control)", "/* nobody to wait for */", "/* nobody to wait for */"),
    ("the old key stays in use after a revocation",
     "            wfas3_grp_switch(g, dev->now_ms);            /* the old keys stop now */",
     "            g->pend = 1U;\n            g->pend_ms = dev->now_ms;"),
    ("a leave does not change the key",
     "    return wfas3_grp_rotate(dev, (revoke == 1U) ? WFAS3_GNEED_REVOKE : WFAS3_GNEED_LEAVE);",
     "    return (revoke == 1U) ? wfas3_grp_rotate(dev, WFAS3_GNEED_REVOKE) : WFAS3_OK;"),
    ("the tag of the receiver's slot is not checked",
     "            (wfas3_ct_eq(tag, &d[body + (8U * (size_t)x->slot)], sizeof tag) == 1U) &&",
     "            (1U == 1U) &&"),
    ("the multicast window is not checked",
     "        ok &= ((ctr >= x->floor) && (wfas3_win_check(&x->win, ctr) == 1U)) ? 1U : 0U;",
     "        ok &= 1U;"),
    ("NACK is ignored",
     "        dev->grp.m[k].resend = (wfas3_rd_be32(&c[8]) != 0U) ? 1U : 0U;",
     "        dev->grp.m[k].resend = 0U;"),
]

PEER = [
    ("no change (control)", 'HS_NAME = b"', 'HS_NAME = b"'),
    ("protocol name off by one letter",
     'b"WFAS3_KKpsk0hfs_25519+MLKEM768_ChaChaPoly_SHA256"',
     'b"WFAS3_KKpsk0hfs_25519+MLKEM768_ChaChaPoly_SHA255"'),
    ("transport key label", 'b"transport i2r"', 'b"transport i2x"'),
    ("direction of the pair tag inverted",
     'tag = mac16(self.key(b"pair tag ", True), d)', 'tag = mac16(self.key(b"pair tag ", False), d)'),
    ("cookie AD without the pair tag",
     "return head + ref.seal(key, 0, head + req[32:48], pt)", "return head + ref.seal(key, 0, head, pt)"),
    ("counter mask without the type byte",
     'mask = mac8(self.key(b"entry counter ", True), bytes([T_CREQ]) + rid)',
     'mask = mac8(self.key(b"entry counter ", True), rid)'),
    ("es computed with the static key",
     "        s.mix_key(dh(e_priv, self.peer_pub))\n        s.mix_key(dh(self.own_priv, self.peer_pub))",
     "        s.mix_key(dh(self.own_priv, self.peer_pub))\n        s.mix_key(dh(self.own_priv, self.peer_pub))"),
    ("Resp header not in the transcript",
     "        r = header(T_RESP) + index + d[4:8]\n        s.mix_hash(r)",
     "        r = header(T_RESP) + index + d[4:8]"),
    ("Resp MAC without the request id",
     'r += mac16(self.key(b"resp ", False), d[8:24] + r)', 'r += mac16(self.key(b"resp ", False), r)'),
    ("session number not incremented",
     "s_seq = max(u64(payload[24:32]), self.seq_hi) + 1 if",
     "s_seq = max(u64(payload[24:32]), self.seq_hi) + 0 if"),
    ("mac2 over the wrong range", "d[:1352]),\n                        d[1352:1368])",
     "d[:1351]),\n                        d[1352:1368])"),
    ("transport associated data empty",
     "d += ref.seal(sess.k_send, sess.send_ctr, d, bytes(128))",
     "d += ref.seal(sess.k_send, sess.send_ctr, b\"\", bytes(128))"),
]

# Second implementations of 13 and 14: (description, file, old, new).
PEER2 = [
    ("no change (control)", "peer_pair.py", 'T_PAIR = 0x13', 'T_PAIR = 0x13'),
    ("CPace CI without the mode byte", "peer_pair.py",
     'b"WFAS3-PAIR" + bytes([mode])', 'b"WFAS3-PAIR"'),
    ("commit_s over the wrong hash", "peer_pair.py",
     'commit_s = sha256(b"WFAS3 sas commit s" + c["h"] + c["n_s"])',
     'commit_s = sha256(b"WFAS3 sas commit s" + c["h3"] + c["n_s"])'),
    ("fingerprint label", "peer_pair.py", 'b"WFAS3 pairing key"', 'b"WFAS3 pairing kex"'),
    ("PIN filter label", "peer_pair.py",
     'self.k_f = kdf(self.nonce, b"pin filter")', 'self.k_f = kdf(self.nonce, b"pin filtr")'),
    ("step 4 sealed with the nonce of step 5", "peer_pair.py",
     'seal(c["keys"]["s2c"], 4, hd, commit_s)', 'seal(c["keys"]["s2c"], 5, hd, commit_s)'),
    ("multicast nonce without the epoch", "peer_mcast.py",
     'return epoch.to_bytes(4, "big") + p64(counter)', 'return bytes(4) + p64(counter)'),
    ("multicast tags over the wrong bytes", "peer_mcast.py",
     'mac8(tag_keys[i], d) if i in tag_keys', 'mac8(tag_keys[i], d[20:]) if i in tag_keys'),
    ("GROUP_JOIN keys swapped", "peer_mcast.py", '+ key + tag_key)', '+ tag_key + key)'),
]
SCRIPT = {"peer_pair.py": "interop_pair.py", "peer_mcast.py": "interop_mcast.py"}


def apply(src, old, new):
    text = open(src).read()
    if text.count(old) != 1:
        raise SystemExit("mutation target not found exactly once: %r" % old[:60])
    return text.replace(old, new)


def run_core(tmp, tp_objs, entries=None, test="test_hs.c"):
    results = []
    for desc, old, new in (CORE if entries is None else entries):
        work = os.path.join(tmp, "core")
        shutil.rmtree(work, ignore_errors=True)
        os.makedirs(work)
        open(os.path.join(work, "wfas.c"), "w").write(apply(os.path.join(ROOT, "wfas.c"), old, new))
        for h in ("wfas.h", "wfas_tp.h"):
            shutil.copy(os.path.join(ROOT, h), work)
        code = open(os.path.join(HERE, test)).read().replace('"../wfas.c"', '"wfas.c"')
        open(os.path.join(work, "t.c"), "w").write(code)
        exe = os.path.join(work, "t")
        cc = subprocess.run([os.environ.get("CC", "cc"), "-std=c99", "-O1", "-I" + work,
                             os.path.join(work, "t.c")] + tp_objs + ["-o", exe],
                            capture_output=True, text=True)
        if cc.returncode != 0:
            results.append((desc, None, "does not compile"))
            continue
        run = subprocess.run([exe], capture_output=True, text=True, timeout=300)
        results.append((desc, run.returncode, "%d failing checks" % run.stdout.count("FAIL:")))
    return results


def run_peer(tmp, driver):
    results = []
    for desc, old, new in PEER:
        work = os.path.join(tmp, "peer")
        shutil.rmtree(work, ignore_errors=True)
        os.makedirs(work)
        open(os.path.join(work, "peer.py"), "w").write(apply(os.path.join(HERE, "peer.py"), old, new))
        for f in ("ref.py", "interop.py"):
            shutil.copy(os.path.join(HERE, f), work)
        run = subprocess.run([sys.executable, "-I", os.path.join(work, "interop.py"), driver],
                             capture_output=True, text=True, timeout=600)
        results.append((desc, run.returncode, "%d failing checks" % run.stdout.count("FAIL:")))
    return results


def run_peer2(tmp, driver):
    results = []
    for desc, name, old, new in PEER2:
        work = os.path.join(tmp, "peer2")
        shutil.rmtree(work, ignore_errors=True)
        os.makedirs(work)
        for f in ("ref.py", "peer.py", "interop.py", "peer_pair.py", "peer_mcast.py",
                  "interop_pair.py", "interop_mcast.py"):
            shutil.copy(os.path.join(HERE, f), work)
        open(os.path.join(work, name), "w").write(apply(os.path.join(HERE, name), old, new))
        run = subprocess.run([sys.executable, "-I", os.path.join(work, SCRIPT[name]), driver],
                             capture_output=True, text=True, timeout=900)
        results.append((desc + " (" + name + ")", run.returncode,
                        "%d failing checks" % run.stdout.count("FAIL:")))
    return results


def report(title, results):
    bad = 0
    print(title)
    for i, (desc, code, note) in enumerate(results):
        control = i == 0
        good = (code == 0) if control else (code not in (0, None))
        bad += 0 if good else 1
        verdict = "ok  " if good else "BAD "
        print("  %s%-58s %s" % (verdict, desc, note if code is not None else "does not compile"))
    return bad


def main():
    tp_objs = sorted(glob.glob(os.path.join(sys.argv[1], "*.o")))
    driver = sys.argv[2]
    tmp = tempfile.mkdtemp(prefix="wfas3-mutate-")
    try:
        bad = report("wfas.c mutations, checked by tests/test_hs.c:", run_core(tmp, tp_objs))
        bad += report("wfas.c pairing mutations, checked by tests/test_pair.c:",
                      run_core(tmp, tp_objs, PAIR, "test_pair.c"))
        bad += report("wfas.c multicast mutations, checked by tests/test_mcast.c:",
                      run_core(tmp, tp_objs, MCAST, "test_mcast.c"))
        bad += report("tests/peer.py mutations, checked by tests/interop.py:", run_peer(tmp, driver))
        bad += report("tests/peer_pair.py and peer_mcast.py mutations, checked by their interop tests:",
                      run_peer2(tmp, driver))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("mutate: %s" % ("every defect is caught" if bad == 0 else "%d entries not caught" % bad))
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
