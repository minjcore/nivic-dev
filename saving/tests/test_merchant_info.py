#!/usr/bin/env python3
"""
Test GET_MERCHANT_INFO (0x27):
  - Any authed user can query a merchant's public name
  - Unknown merchant → ERR_NOT_FOUND
"""
import socket, struct, hmac, hashlib, random

import os
HOST   = os.getenv("WIRE_HOST", "127.0.0.1")
PORT   = int(os.getenv("WIRE_PORT", "7474"))
SECRET = b"saving_wire_secret_changeme"
RUN_ID = random.randint(100_000, 999_999)

def sign(p):   return hmac.new(SECRET, p, hashlib.sha256).digest()
def encode(t, seq, body):
    total = 4+1+4+len(body)+32
    hdr   = struct.pack(">IBI", total, t, seq)
    return hdr + body + sign(hdr + body)

def recv_rpc(sock):
    while True:
        hdr = b""
        while len(hdr) < 9: hdr += sock.recv(9-len(hdr))
        total, ftype, _ = struct.unpack(">IBI", hdr)
        rest = b""
        while len(rest) < total-9: rest += sock.recv(total-9-len(rest))
        if ftype >= 0xC0: continue
        return ftype, rest[:-32]

def create_account(s, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x10, seq, body)); _, r = recv_rpc(s)
    assert r[0] in (0, 3)

def login(s, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x02, seq, body)); _, r = recv_rpc(s)
    assert r[0] == 0; return r[1:33]

def register_merchant(s, tok, seq, name):
    s.sendall(encode(0x23, seq, tok + name.encode()))
    _, r = recv_rpc(s); assert r[0] == 0

def get_merchant_info(s, tok, seq, merchant_id):
    body = tok + struct.pack(">I", merchant_id)
    s.sendall(encode(0x27, seq, body))
    _, r = recv_rpc(s)
    if r[0] != 0:
        return {"ok": False, "code": r[0]}
    return {"ok": True, "name": r[1:].decode()}

# ── Setup ─────────────────────────────────────────────────────────────────────
MERCH = 16_777_216 + RUN_ID
USER  = 16_777_216 + RUN_ID + 1
NAME  = f"Quan-Banh-Mi-{RUN_ID}"

print(f"=== GET_MERCHANT_INFO test (RUN_ID={RUN_ID}) ===")
print(f"    merchant={MERCH}  name='{NAME}'")

conn = socket.create_connection((HOST, PORT)); conn.settimeout(5)
create_account(conn, MERCH, f"m{RUN_ID}", seq=1)
create_account(conn, USER,  f"u{RUN_ID}", seq=2)
mt = login(conn, MERCH, f"m{RUN_ID}", seq=3)
ut = login(conn, USER,  f"u{RUN_ID}", seq=4)
register_merchant(conn, mt, seq=5, name=NAME)
print(f"    merchant registered ✓")

# ── Test 1: merchant queries own name ────────────────────────────────────────
r = get_merchant_info(conn, mt, seq=6, merchant_id=MERCH)
print(f"\n[Test 1] Merchant queries own info → name='{r.get('name')}'")
assert r["ok"] and r["name"] == NAME, f"FAIL: {r}"
print(f"    ✓ name matches")

# ── Test 2: regular user queries merchant ────────────────────────────────────
r2 = get_merchant_info(conn, ut, seq=7, merchant_id=MERCH)
print(f"\n[Test 2] User queries merchant → name='{r2.get('name')}'")
assert r2["ok"] and r2["name"] == NAME, f"FAIL: {r2}"
print(f"    ✓ any authed user can query")

# ── Test 3: unknown merchant → ERR_NOT_FOUND (0x05) ─────────────────────────
GHOST_ID = 9_999_999
r3 = get_merchant_info(conn, ut, seq=8, merchant_id=GHOST_ID)
print(f"\n[Test 3] Unknown merchant_id={GHOST_ID} → code=0x{r3.get('code', 0):02X} (expect 0x05)")
assert not r3["ok"] and r3["code"] == 0x05, f"FAIL: {r3}"
print(f"    ✓ ERR_NOT_FOUND returned")

# ── Test 4: bad token → ERR_BAD_TOKEN (0x07) ────────────────────────────────
bad_tok = bytes(32)
r4 = get_merchant_info(conn, bad_tok, seq=9, merchant_id=MERCH)
print(f"\n[Test 4] Bad token → code=0x{r4.get('code', 0):02X} (expect 0x07)")
assert not r4["ok"] and r4["code"] == 0x07, f"FAIL: {r4}"
print(f"    ✓ ERR_BAD_TOKEN returned")

conn.close()
print("\n=== ALL PASSED ===")
