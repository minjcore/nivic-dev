#!/usr/bin/env python3
"""
Test ERR_SYSTEM_OFFLINE (0x0F) — mid=1 online guard.

Flow:
  1. Ensure mid=1 is offline (login → logout to get a valid token, then logout)
  2. Money-movement frames → expect 0x0F on all of them
  3. Non-money frames (PING, GET_BALANCE, CREATE_ACCOUNT) → expect NOT 0x0F
  4. Login mid=1
  5. Same money-movement frames → expect not 0x0F (normal error or 0x00)
  6. Logout mid=1
  7. Money-movement frame → expect 0x0F again
"""
import socket, struct, hmac, hashlib, random, time

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

def login(s, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x02, seq, body))
    _, r = recv_rpc(s)
    assert r[0] == 0, f"login failed uid={uid}: 0x{r[0]:02X}"
    return r[1:33]

def logout(s, token, seq):
    s.sendall(encode(0x03, seq, token))
    _, r = recv_rpc(s)
    return r[0]

def create_account(s, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x10, seq, body))
    _, r = recv_rpc(s)
    assert r[0] in (0, 3), f"create_account failed uid={uid}: 0x{r[0]:02X}"

ERR_SYSTEM_OFFLINE = 0x0F

print(f"=== ERR_SYSTEM_OFFLINE guard test (RUN_ID={RUN_ID}) ===")

# ── Setup: register test accounts ─────────────────────────────────────────────
MERCH_UID = 16_777_216 + RUN_ID
CUST_UID  = 16_777_216 + RUN_ID + 1

setup = socket.create_connection((HOST, PORT)); setup.settimeout(5)
create_account(setup, MERCH_UID, f"m{RUN_ID}", seq=1)
create_account(setup, CUST_UID,  f"c{RUN_ID}", seq=2)
setup.close()

# ── Step 1: force mid=1 offline ────────────────────────────────────────────────
# Login mid=1, grab its token, then immediately logout so it's offline.
s1 = socket.create_connection((HOST, PORT)); s1.settimeout(5)
bank_tok = login(s1, 1, "bank123", seq=1)
rc_lo = logout(s1, bank_tok, seq=2)
s1.close()
print(f"\n[Setup] Logged mid=1 in then out → logout code=0x{rc_lo:02X}")
# Give the session table time to clear (logout is synchronous, should be instant)

# ── Test 2: money-movement frames while offline ────────────────────────────────
mc = socket.create_connection((HOST, PORT)); mc.settimeout(5)
cc = socket.create_connection((HOST, PORT)); cc.settimeout(5)
mt = login(mc, MERCH_UID, f"m{RUN_ID}", seq=1)
ct = login(cc, CUST_UID,  f"c{RUN_ID}", seq=1)

print(f"\n--- Money-movement frames while mid=1 OFFLINE ---")

# TRANSFER (0x11): [token 32B][to 4B][amount 8B][ref 8B]
mc.sendall(encode(0x11, seq=2, body=mt + struct.pack(">IQQ", CUST_UID, 1000, RUN_ID*100+1)))
_, r = recv_rpc(mc)
print(f"[Test A] TRANSFER → 0x{r[0]:02X} (expect 0x{ERR_SYSTEM_OFFLINE:02X})")
assert r[0] == ERR_SYSTEM_OFFLINE, f"FAIL: 0x{r[0]:02X}"
print(f"    ✓ TRANSFER blocked")

# CREATE_INTENT (0x20): [token 32B][req_id 8B][order_id 8B][amount 8B]
mc.sendall(encode(0x20, seq=3, body=mt + struct.pack(">QQQ", RUN_ID*100+1, RUN_ID*10+1, 5000)))
_, r = recv_rpc(mc)
print(f"[Test B] CREATE_INTENT → 0x{r[0]:02X} (expect 0x{ERR_SYSTEM_OFFLINE:02X})")
assert r[0] == ERR_SYSTEM_OFFLINE, f"FAIL: 0x{r[0]:02X}"
print(f"    ✓ CREATE_INTENT blocked")

# PAY_INTENT (0x21): [token 32B][mid 4B][req_id 8B][totp_code 4B]
cc.sendall(encode(0x21, seq=2, body=ct + struct.pack(">IQI", MERCH_UID, RUN_ID*100+1, 123456)))
_, r = recv_rpc(cc)
print(f"[Test C] PAY_INTENT → 0x{r[0]:02X} (expect 0x{ERR_SYSTEM_OFFLINE:02X})")
assert r[0] == ERR_SYSTEM_OFFLINE, f"FAIL: 0x{r[0]:02X}"
print(f"    ✓ PAY_INTENT blocked")

# CASH_IN (0x24): uses bank_token (but mid=1 is offline so guard fires first)
# We use a zeroed bank token — system offline check fires before token check
bc2 = socket.create_connection((HOST, PORT)); bc2.settimeout(5)
bc2.sendall(encode(0x24, seq=1, body=bytes(32) + struct.pack(">IQ", CUST_UID, 10000) + b"tid1"))
_, r = recv_rpc(bc2)
print(f"[Test D] CASH_IN → 0x{r[0]:02X} (expect 0x{ERR_SYSTEM_OFFLINE:02X})")
assert r[0] == ERR_SYSTEM_OFFLINE, f"FAIL: 0x{r[0]:02X}"
bc2.close()
print(f"    ✓ CASH_IN blocked")

# CASH_OUT (0x26)
bc3 = socket.create_connection((HOST, PORT)); bc3.settimeout(5)
bc3.sendall(encode(0x26, seq=1, body=bytes(32) + struct.pack(">IQ", CUST_UID, 1000) + b"co1"))
_, r = recv_rpc(bc3)
print(f"[Test E] CASH_OUT → 0x{r[0]:02X} (expect 0x{ERR_SYSTEM_OFFLINE:02X})")
assert r[0] == ERR_SYSTEM_OFFLINE, f"FAIL: 0x{r[0]:02X}"
bc3.close()
print(f"    ✓ CASH_OUT blocked")

# TOTP_CHARGE (0x25)
mc.sendall(encode(0x25, seq=4, body=mt + struct.pack(">IIQ", CUST_UID, 123456, 500)))
_, r = recv_rpc(mc)
print(f"[Test F] TOTP_CHARGE → 0x{r[0]:02X} (expect 0x{ERR_SYSTEM_OFFLINE:02X})")
assert r[0] == ERR_SYSTEM_OFFLINE, f"FAIL: 0x{r[0]:02X}"
print(f"    ✓ TOTP_CHARGE blocked")

# ── Test 3: non-money frames NOT blocked ──────────────────────────────────────
print(f"\n--- Non-money frames while mid=1 OFFLINE ---")

# PING (0x01)
mc.sendall(encode(0x01, seq=5, body=b""))
ftype, _ = recv_rpc(mc)
print(f"[Test G] PING → ftype=0x{ftype:02X} (expect 0x80 PONG)")
assert ftype == 0x80, f"FAIL: 0x{ftype:02X}"
print(f"    ✓ PING works offline")

# GET_BALANCE (0x12)
mc.sendall(encode(0x12, seq=6, body=mt))
_, r = recv_rpc(mc)
print(f"[Test H] GET_BALANCE → code=0x{r[0]:02X} (expect NOT 0x{ERR_SYSTEM_OFFLINE:02X})")
assert r[0] != ERR_SYSTEM_OFFLINE, f"FAIL: blocked by offline guard"
print(f"    ✓ GET_BALANCE not blocked")

# ── Step 4: bring mid=1 online ────────────────────────────────────────────────
print(f"\n--- Bringing mid=1 ONLINE ---")
bc_on = socket.create_connection((HOST, PORT)); bc_on.settimeout(5)
bank_tok2 = login(bc_on, 1, "bank123", seq=1)
print(f"    mid=1 logged in OK")

# ── Test 5: money-movement now allowed ────────────────────────────────────────
print(f"\n--- Money-movement frames while mid=1 ONLINE ---")

# TRANSFER — expect ERR_LOW_BALANCE (0x08) or OK, NOT 0x0F
mc.sendall(encode(0x11, seq=7, body=mt + struct.pack(">IQQ", CUST_UID, 1000, RUN_ID*100+2)))
_, r = recv_rpc(mc)
print(f"[Test I] TRANSFER → 0x{r[0]:02X} (expect NOT 0x{ERR_SYSTEM_OFFLINE:02X})")
assert r[0] != ERR_SYSTEM_OFFLINE, f"FAIL: still blocked"
print(f"    ✓ TRANSFER not blocked (got 0x{r[0]:02X})")

# CASH_IN with real bank token → should succeed
bc_on.sendall(encode(0x24, seq=2, body=bank_tok2 + struct.pack(">IQ", CUST_UID, 50_000) + f"CI-{RUN_ID}".encode()))
_, r = recv_rpc(bc_on)
print(f"[Test J] CASH_IN → 0x{r[0]:02X} (expect 0x00)")
assert r[0] == 0, f"FAIL: 0x{r[0]:02X}"
print(f"    ✓ CASH_IN succeeded")

# ── Step 6: logout mid=1 again ────────────────────────────────────────────────
logout(bc_on, bank_tok2, seq=3)
bc_on.close()
print(f"\n[Step 6] Logged mid=1 out again")

# ── Test 7: offline guard back ────────────────────────────────────────────────
mc.sendall(encode(0x11, seq=8, body=mt + struct.pack(">IQQ", CUST_UID, 1000, RUN_ID*100+3)))
_, r = recv_rpc(mc)
print(f"\n[Test K] TRANSFER after logout → 0x{r[0]:02X} (expect 0x{ERR_SYSTEM_OFFLINE:02X})")
assert r[0] == ERR_SYSTEM_OFFLINE, f"FAIL: 0x{r[0]:02X}"
print(f"    ✓ TRANSFER blocked again after logout")

mc.close(); cc.close()
print("\n=== ALL PASSED ===")
