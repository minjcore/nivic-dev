#!/usr/bin/env python3
"""Test PING (0x01) → PONG (0x80), no auth required, seq mirrored."""
import socket, struct, hmac, hashlib, random

HOST   = "127.0.0.1"
PORT   = 7474
SECRET = b"saving_wire_secret_changeme"

def sign(p):   return hmac.new(SECRET, p, hashlib.sha256).digest()
def encode(t, seq, body):
    total = 4+1+4+len(body)+32
    hdr   = struct.pack(">IBI", total, t, seq)
    return hdr + body + sign(hdr + body)

def recv_rpc(sock):
    hdr = b""
    while len(hdr) < 9: hdr += sock.recv(9-len(hdr))
    total, ftype, seq = struct.unpack(">IBI", hdr)
    rest = b""
    while len(rest) < total-9: rest += sock.recv(total-9-len(rest))
    return ftype, seq, rest[:-32]

print("=== PING test ===")
conn = socket.create_connection((HOST, PORT)); conn.settimeout(5)

# ── Test 1: PING → PONG, seq mirrored ────────────────────────────────────────
for seq in [1, 42, 65535]:
    conn.sendall(encode(0x01, seq, b""))
    ftype, resp_seq, body = recv_rpc(conn)
    print(f"\n[Test 1] PING seq={seq} → ftype=0x{ftype:02X}  resp_seq={resp_seq}  body_len={len(body)}")
    assert ftype == 0x80,    f"FAIL: expected PONG 0x80, got 0x{ftype:02X}"
    assert resp_seq == seq,  f"FAIL: seq not mirrored {resp_seq} != {seq}"
    assert len(body) == 0,   f"FAIL: PONG should have empty body"
    print(f"    ✓ PONG, seq mirrored, empty body")

conn.close()
print("\n=== ALL PASSED ===")
