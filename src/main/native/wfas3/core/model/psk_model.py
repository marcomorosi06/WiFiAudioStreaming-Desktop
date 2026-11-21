#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Marco Morosi
#
# Exhaustive bounded model of the WFAS v3 attempt rules, pair psk and session
# reports (WFAS_V3_PROTOCOL.md, sections 9.1, 9.2 and 9.3).
#
# Two honest sides, 0 (low static key) and 1 (high static key). The network
# adversary holds every message ever sent and may deliver any of them, in any
# order, any number of times, or never. It has no keys.
#
# Also modelled: the stale notice of section 7.5, and the drops caused by the
# CookieRequest quota (7.4) and by the handshake rate limit (8.1 step 6).
#
# Checked on every reachable state:
#   NO-STALE    no stale notice ever reaches the attempt it would alarm
#   NO-REJECT   no Init that passes the cookie check, live or late, is ever
#               rejected for an unknown psk or a stale report
#   NO-FALSE    no honest report is ever classified as unknown
#   READY-PSK   the psk a side would use now is held by the peer
#   READY-REP   the report a side would send now is acceptable to the peer
#
# The bound is the total number of attempts started by the two sides.
#
# Usage: python3 -I psk_model.py [max_attempts]

import sys
from collections import deque, namedtuple

Side = namedtuple("Side", [
    "cur", "mine", "theirs",          # pair psk slots
    "att",                            # None | ("req",n) | ("ck",n) | ("out",hid,psk)
    "ectr", "seen", "used",           # entry counters: own, peer highest, peer consumed
    "base", "minec", "respp", "last", # report slots: (hid, seq)
    "seqhi", "ctx",                   # highest session seq, unconfirmed responder ctx
])

PAIR = ("pair", 0)


def initial():
    s = Side(cur="P0", mine=None, theirs=None, att=None,
             ectr=0, seen=0, used=0,
             base=PAIR, minec=None, respp=None, last=PAIR, seqhi=0, ctx=None)
    return (s, s, frozenset(), 0)


def psk_set(s):
    return {x for x in (s.cur, s.mine, s.theirs) if x is not None}


def choose(s):
    return s.mine if s.mine is not None else s.cur


def rep_class(s, rep):
    if rep[1] < s.base[1]:
        return "stale"
    if rep in (s.base, s.minec, s.respp):
        return "ok"
    return "unknown"


class Violation(Exception):
    pass


def alive(m, sides):
    # A message that no reachable state can ever accept again is dropped from
    # the network. This only removes no-op deliveries; it does not weaken the
    # adversary.
    kind, frm = m[0], m[1]
    st = sides[1 - frm]
    if kind == "creq":
        # Below or at the peer's counter it can only produce a stale notice,
        # which matters only while the sender still waits on that request.
        return m[2] > st.seen or sides[frm].att == ("req", m[2])
    if kind == "stale":
        return st.att == ("req", m[2])
    if kind == "crep":
        return st.att == ("req", m[2])
    if kind == "init":
        return m[3] >= st.seen and st.used < m[3]
    if kind in ("resp", "rej"):
        return st.att is not None and st.att[0] == "out" and st.att[1] == m[2]
    if kind == "conf":
        return st.ctx == m[2]
    return True


def successors(state, max_hs):
    sides = [state[0], state[1]]
    net, nhs = state[2], state[3]

    def pack(i, si, j=None, sj=None, add=None, hs=nhs):
        new = list(sides)
        new[i] = si
        if j is not None:
            new[j] = sj
        n2 = net if add is None else net | {add}
        n2 = frozenset(m for m in n2 if alive(m, new))
        return (new[0], new[1], n2, hs)

    for x in (0, 1):
        sx = sides[x]

        # C2: starting an attempt invalidates the cookie this side issued.
        if sx.att is None and sides[0].ectr + sides[1].ectr < max_hs:
            n = sx.ectr + 1
            yield "start%d" % x, pack(x, sx._replace(ectr=n, att=("req", n), used=sx.seen),
                                      add=("creq", x, n))

        # Create and send Init. Destroys any unconfirmed responder context.
        if sx.att is not None and sx.att[0] == "ck":
            hid = nhs + 1
            psk = choose(sx)
            msg = ("init", x, hid, sx.att[1], psk, sx.last, sx.seqhi)
            yield "init%d" % x, pack(x, sx._replace(att=("out", hid, psk), ctx=None),
                                     add=msg, hs=hid)

        # Give up on the current attempt (timeout).
        if sx.att is not None:
            yield "abandon%d" % x, pack(x, sx._replace(att=None))

    for m in net:
        kind, frm = m[0], m[1]
        to = 1 - frm
        st, sf = sides[to], sides[frm]

        if kind == "creq":
            n = m[2]
            if n == st.seen:
                continue                       # duplicate: dropped in silence
            if n < st.seen:
                yield "creq-stale", pack(to, st, add=("stale", to, n))
                continue
            # Quota: the counter is recorded and consumed, no cookie is issued.
            yield "creq-quota", pack(to, st._replace(seen=n, used=n))
            if st.att is not None:
                if to == 0:
                    # C3: low side keeps its own attempt. The counter is
                    # recorded and consumed, no cookie is issued.
                    yield "creq-drop", pack(to, st._replace(seen=n, used=n))
                    continue
                st = st._replace(att=None)     # C3: high side yields
            yield "creq-accept", pack(to, st._replace(seen=n), add=("crep", to, n))

        elif kind == "stale":
            # Considered only while waiting for the CookieReply of that request.
            if st.att == ("req", m[2]):
                raise Violation("NO-STALE")

        elif kind == "crep":
            n = m[2]
            if st.att == ("req", n):
                yield "crep-accept", pack(to, st._replace(att=("ck", n)))

        elif kind == "init":
            _, _, hid, ectr, psk, rep, seqhi = m
            if not (ectr == st.seen and st.used < ectr):
                continue                       # cookie/mac2 fails: no state change
            assert st.att is None, "valid cookie while attempting"
            st = st._replace(used=ectr)
            yield "init-ratelimit", pack(to, st)   # 8.1 step 6: cookie consumed, dropped
            live = sf.att is not None and sf.att[0] == "out" and sf.att[1] == hid
            if psk not in psk_set(st):
                raise Violation("NO-REJECT (unknown psk%s)" % (", live" if live else ""))
            cls = rep_class(st, rep)
            if cls == "stale":
                raise Violation("NO-REJECT (stale report%s)" % (", live" if live else ""))
            if cls == "unknown":
                raise Violation("NO-FALSE (responder)")
            if psk != st.cur:
                st = st._replace(cur=psk, mine=None, theirs=None)
            minec = st.minec if (st.minec and st.minec[1] > rep[1]) else None
            s = max(seqhi, st.seqhi) + 1
            nxt = "N%d" % hid
            st = st._replace(theirs=nxt, base=rep, minec=minec, respp=(hid, s),
                             seqhi=s, ctx=hid)
            yield "init-accept", pack(to, st, add=("resp", to, hid, nxt, st.last, s))

        elif kind == "resp":
            _, _, hid, nxt, rep, s = m
            if st.att is None or st.att[0] != "out" or st.att[1] != hid:
                continue
            cls = rep_class(st, rep)
            if cls != "ok":
                raise Violation("NO-FALSE (initiator, %s)" % cls)
            used = st.att[2]
            if used != st.cur:
                st = st._replace(cur=used, theirs=None)
            st = st._replace(mine=nxt, base=rep, minec=(hid, s), respp=None,
                             last=(hid, s), seqhi=max(st.seqhi, s), att=None)
            yield "resp-accept", pack(to, st, add=("conf", to, hid))

        elif kind == "conf":
            _, _, hid = m
            if st.ctx != hid:
                continue
            assert st.respp is not None and st.respp[0] == hid
            yield "conf-accept", pack(to, st._replace(last=st.respp, ctx=None))

        elif kind == "rej":
            _, _, hid = m
            if st.att is not None and st.att[0] == "out" and st.att[1] == hid:
                raise Violation("alarm on live Init")


def check_static(state):
    for x in (0, 1):
        sx, sy = state[x], state[1 - x]
        if choose(sx) not in psk_set(sy):
            raise Violation("READY-PSK side %d" % x)
        if rep_class(sy, sx.last) != "ok":
            raise Violation("READY-REP side %d" % x)


def main():
    max_hs = int(sys.argv[1]) if len(sys.argv) > 1 else 4
    start = initial()
    seen = {start: (None, None)}
    queue = deque([start])
    while queue:
        st = queue.popleft()
        try:
            check_static(st)
            for name, nxt in successors(st, max_hs):
                if nxt not in seen:
                    seen[nxt] = (st, name)
                    queue.append(nxt)
        except Violation as v:
            trace, cur = [], st
            while cur is not None:
                prev, name = seen[cur]
                trace.append((name, cur))
                cur = prev
            print("VIOLATION:", v)
            for name, s in reversed(trace):
                print(" ", name)
                print("     side0", s[0])
                print("     side1", s[1])
            return 1
    print("max_attempts=%d states=%d: all invariants hold" % (max_hs, len(seen)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
