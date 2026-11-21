#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Marco Morosi
#
# Test of wfascli on one machine: two devices with their own directories and
# UDP ports on 127.0.0.1, broadcasts redirected to each other with -B. Pairing
# with a code, a label and a PIN, unicast audio compared byte for byte,
# multicast (skipped when the host does not loop multicast back), removal,
# and the storage rules (mode 0700 directory, no symbolic links).
#
# Usage: python3 -I tests/cli_test.py build/wfascli

import os
import random
import re
import shutil
import subprocess
import sys
import tempfile
import time

RUN = 0
FAILED = 0


def expect(cond, what):
    global RUN, FAILED
    RUN += 1
    if not cond:
        FAILED += 1
        print("FAIL:", what)
    return cond


class Dev:
    def __init__(self, cli, root, name, port, peer_port):
        self.cli, self.dir, self.name = cli, os.path.join(root, name), name
        self.port, self.peer_port = port, peer_port

    def args(self, *a):
        return [self.cli, "-d", self.dir, "-p", str(self.port), "-n", self.name, "-y",
                "-B", "127.0.0.1:%d" % self.peer_port] + list(a)

    def run(self, *a, timeout=60):
        r = subprocess.run(self.args(*a), capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout + r.stderr

    def start(self, *a):
        return subprocess.Popen(self.args(*a), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)


def wait_line(p, pattern, timeout=10):
    """Read p's output until a line matches; returns the match and the text."""
    end, text = time.time() + timeout, ""
    while time.time() < end:
        line = p.stdout.readline()
        if not line:
            break
        text += line
        m = re.search(pattern, line)
        if m:
            return m, text
    return None, text


def finish(p, timeout=30):
    try:
        out, _ = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        p.kill()
        out, _ = p.communicate()
    return p.returncode, out


def pair(a, b, kind, label):
    """a opens a window of kind, b pairs with it."""
    srv = a.start("pair-server", kind) if kind != "label" else a.start("pair-server", "label", "headless")
    if kind == "pin":
        m, text = wait_line(srv, r"^PIN: (\d{8})$")
        ok = expect(m is not None, label + ": the server shows a PIN")
        secret = m.group(1) if m else "00000000"
        rc, out = b.run("pair-client", "pin", secret, a.name, timeout=60) if ok else (1, "")
    else:
        m, text = wait_line(srv, r"^code: ([0-9A-Z-]{64})$")
        ok = expect(m is not None, label + ": the server shows a code")
        rc, out = b.run("pair-client", kind, m.group(1).lower(), "mic", timeout=60) if ok else (1, "")
    src, sout = finish(srv)
    sout = text + sout
    expect(rc == 0 and "paired in slot" in out, label + ": the client pairs")
    expect(src == 0 and "paired in slot" in sout, label + ": the server pairs")
    if kind == "pin":
        codes_c = re.findall(r"^code (\d{6})$", out, re.M)
        codes_s = re.findall(r"code (\d{6}): compare", sout)
        expect(codes_c and codes_c == codes_s, label + ": both sides show the same 6-digit code")
    return out, sout


def main():
    cli = os.path.abspath(sys.argv[1])
    root = tempfile.mkdtemp(prefix="wfascli.")
    os.chmod(root, 0o700)
    base = random.randint(20000, 40000)
    a = Dev(cli, root, "Speaker", base, base + 1)
    b = Dev(cli, root, "Phone", base + 1, base)
    try:
        expect(a.run("init")[0] == 0 and b.run("init")[0] == 0, "init")
        expect(oct(os.stat(a.dir).st_mode & 0o777) == "0o700", "the directory is mode 0700")
        expect(oct(os.stat(os.path.join(a.dir, "device.rec")).st_mode & 0o777) == "0o600",
               "the device record is mode 0600")
        rec0 = open(os.path.join(a.dir, "device.rec"), "rb").read()

        pair(a, b, "code", "code")
        rc, out = a.run("pairs")
        expect(rc == 0 and re.search(r'^00 "Phone" receive microphone$', out, re.M) is not None,
               "the server lists the new pair")
        rc, out = b.run("pairs")
        expect(re.search(r'^00 "Speaker"', out, re.M) is not None, "the client lists the new pair")
        expect(os.path.getsize(os.path.join(a.dir, "pairs", "00.rec")) == 420, "a 420-byte pair record")

        # Unicast audio, byte for byte.
        data = os.urandom(960 * 400)
        src = os.path.join(root, "in.raw")
        dst = os.path.join(root, "out.raw")
        open(src, "wb").write(data)
        lis = b.start("listen", dst, "6")
        wait_line(lis, r"^listening")
        rc, out = a.run("send", "0", src, "2")
        lrc, lout = finish(lis)
        got = open(dst, "rb").read() if os.path.exists(dst) else b""
        expect(rc == 0 and "sent 400 packets" in out, "send: the whole file is sent")
        expect(len(got) == len(data) and got == data, "send: the listener writes exactly what was sent")

        # Multicast to the paired listener.
        dstm = os.path.join(root, "outm.raw")
        lis = b.start("listen", dstm, "6")
        wait_line(lis, r"^listening")
        rc, out = a.run("multicast", "239.77.%d.%d:%d" % (base % 250, base % 7, base + 5), "2", "0")
        lrc, lout = finish(lis)
        if "joining group" in lout and "received 0 packets" not in lout:
            ms = re.search(r"sent (\d+) packets", out)
            mr = re.search(r"received (\d+) packets", lout)
            expect(ms is not None and mr is not None and ms.group(1) == mr.group(1) and int(ms.group(1)) > 300,
                   "multicast: every packet sent arrives")
        else:
            print("multicast: the host does not loop multicast back, skipped")

        # A label on a headless device.
        rc, out = a.run("label-create")
        expect(rc == 0 and re.search(r"^code: ", out, re.M) is not None, "label-create prints the code")
        rc, out = a.run("label-create")
        expect(rc != 0, "a label is made once")
        pair(a, b, "label", "label")

        # PIN.
        pair(a, b, "pin", "pin")

        # Removal: the record is erased and the entry key changes.
        rc, out = b.run("pairs")
        slots = re.findall(r"^(\d\d) ", out, re.M)
        expect(len(slots) == 3, "three pairs on the client")
        rc, out = a.run("remove", "00")
        expect(rc == 0 and "pair 00 erased" in out, "remove")
        expect(not os.path.exists(os.path.join(a.dir, "pairs", "00.rec")), "remove: the record is gone")
        expect(open(os.path.join(a.dir, "device.rec"), "rb").read()[:32] != rec0[:32],
               "remove: a new entry key is stored")
        rc, out = b.run("find", "00", timeout=60)
        expect("found at 127.0.0.1:%d" % a.port in out or "not found" in out, "find runs")

        # Storage rules.
        os.chmod(a.dir, 0o755)
        rc, out = a.run("pairs")
        expect(rc != 0 and "mode 0700" in out, "a directory others can enter is refused")
        os.chmod(a.dir, 0o700)
        p = os.path.join(a.dir, "device.rec")
        os.rename(p, p + ".real")
        os.symlink(p + ".real", p)
        rc, out = a.run("pairs")
        expect(rc != 0 and "unsafe" in out, "a symbolic link as record is refused")
        os.remove(p)
        os.rename(p + ".real", p)
        os.chmod(p, 0o644)
        rc, out = a.run("pairs")
        expect(rc != 0, "a record others can read is refused")
        os.chmod(p, 0o600)
        expect(a.run("pairs")[0] == 0, "the repaired directory works again")
    finally:
        shutil.rmtree(root, ignore_errors=True)
    print("cli: %d checks, %d failed" % (RUN, FAILED))
    return 0 if FAILED == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
