#!/usr/bin/env python3
# MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
# All rights reserved. See LICENSE at the repository root.
#
# Minimal Minecraft RCON client — auth + one command + read one response.
# Committed copy of the ad-hoc tool used to drive the M9 verification
# captures (docs/verification/m9/7.2-7.6); see multiforge-bench/README.md
# for why the bench harness itself uses a Java RconClient instead — this
# script exists so the m456 verification scripts in this directory don't
# depend on an operator's scratch directory to run.
#
# Usage: rcon.py <host> <port> <password> <command>
import socket
import struct
import sys

if len(sys.argv) != 5:
    sys.exit(f"usage: {sys.argv[0]} <host> <port> <password> <command>")

host, port, password, command = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]


def pkt(pid, ptype, body):
    payload = struct.pack("<ii", pid, ptype) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def readpkt(sock):
    ln = struct.unpack("<i", sock.recv(4))[0]
    data = b""
    while len(data) < ln:
        data += sock.recv(ln - len(data))
    pid, ptype = struct.unpack("<ii", data[:8])
    body = data[8:-2].decode("utf-8", errors="replace")
    return pid, ptype, body


with socket.create_connection((host, port), timeout=30) as s:
    s.sendall(pkt(1, 3, password))
    pid, _ptype, _body = readpkt(s)
    if pid == -1:
        sys.exit("rcon auth failed")
    s.sendall(pkt(2, 2, command))
    _pid, _ptype, body = readpkt(s)
    print(f"rcon reply: {body}")
