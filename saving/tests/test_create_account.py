#!/usr/bin/env python3
"""
Test CREATE_ACCOUNT (0x10):
  body: [mid 4B][pw_hash 32B]
  - Normal create + login works
  - Duplicate uid → ERR_ID_TAKEN    (0x03)
  - uid < 16,777,216 → ERR_ID_RESERVED (0x04)
  - uid > 4,294,967,295 → invalid (frame rejected)
  - uid = SAVING_ID_USER_MIN (16,777,216) → OK (boundary)
  - uid = SAVING_ID_MAX (4,294,967,295) → OK (boundary)
"""
import socket, struct, hmac, hashlib, random

import os
HOST   = os.getenv("WIRE_HOST", "127.0.0.1")
PORT   = int(os.getenv("WIRE_PORT", "7474"))
SECRET = b"saving_wire_secret_changeme"
RUN_ID = random.randint(100_000, 999_999)

SAVING_ID_USER_MIN = 16_777_216
SAVING_ID_MAX      = 4_294_967_295

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

def create_account_raw(s, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x10, seq, body)); _, r = recv_rpc(s)
    return r[0]

def login_ok(s, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x02, seq, body)); _, r = recv_rpc(s)
    return r[0] == 0

print(f"=== CREATE_ACCOUNT test (RUN_ID={RUN_ID}) ===")

conn = socket.create_connection((HOST, PORT)); conn.settimeout(5)

# ── Test 1: normal create ─────────────────────────────────────────────────────
UID = SAVING_ID_USER_MIN + RUN_ID
rc = create_account_raw(conn, UID, f"pw{RUN_ID}", seq=1)
print(f"\n[Test 1] Normal create uid={UID} → code=0x{rc:02X} (expect 0x00)")
assert rc == 0, f"FAIL: 0x{rc:02X}"
assert login_ok(conn, UID, f"pw{RUN_ID}", seq=2), "FAIL: login after create"
print(f"    ✓ created + login works")

# ── Test 2: duplicate uid → ERR_ID_TAKEN ─────────────────────────────────────
rc2 = create_account_raw(conn, UID, "different_pw", seq=3)
print(f"\n[Test 2] Duplicate uid → code=0x{rc2:02X} (expect 0x03 ERR_ID_TAKEN)")
assert rc2 == 0x03, f"FAIL: 0x{rc2:02X}"
print(f"    ✓ ERR_ID_TAKEN")

# ── Test 3: uid in VIP range → ERR_ID_RESERVED ───────────────────────────────
for vip_uid in [1, 999, SAVING_ID_USER_MIN - 1]:
    rc3 = create_account_raw(conn, vip_uid, "pw", seq=4)
    print(f"\n[Test 3] VIP uid={vip_uid} → code=0x{rc3:02X} (expect 0x04 ERR_ID_RESERVED)")
    assert rc3 == 0x04, f"FAIL uid={vip_uid}: 0x{rc3:02X}"
print(f"    ✓ ERR_ID_RESERVED for all VIP uids")

# ── Test 4: boundary uid = SAVING_ID_USER_MIN ────────────────────────────────
BOUNDARY_LOW = SAVING_ID_USER_MIN
rc4 = create_account_raw(conn, BOUNDARY_LOW, f"bl{RUN_ID}", seq=5)
# May already exist from prior runs — accept OK or ID_TAKEN
print(f"\n[Test 4] Boundary uid={BOUNDARY_LOW} → code=0x{rc4:02X} (expect 0x00 or 0x03)")
assert rc4 in (0x00, 0x03), f"FAIL: 0x{rc4:02X}"
print(f"    ✓ boundary low accepted")

# ── Test 5: boundary uid = SAVING_ID_MAX ─────────────────────────────────────
BOUNDARY_HIGH = SAVING_ID_MAX
rc5 = create_account_raw(conn, BOUNDARY_HIGH, f"bh{RUN_ID}", seq=6)
print(f"\n[Test 5] Boundary uid={BOUNDARY_HIGH} → code=0x{rc5:02X} (expect 0x00 or 0x03)")
assert rc5 in (0x00, 0x03), f"FAIL: 0x{rc5:02X}"
print(f"    ✓ boundary high accepted")

# ── Test 6: short body → ERR_BAD_FRAME ───────────────────────────────────────
short_body = struct.pack(">I", UID)   # only 4 bytes, missing pw_hash
conn.sendall(encode(0x10, seq=7, body=short_body))
_, r6 = recv_rpc(conn)
print(f"\n[Test 6] Short body → code=0x{r6[0]:02X} (expect 0x01 ERR_BAD_FRAME)")
assert r6[0] == 0x01, f"FAIL: 0x{r6[0]:02X}"
print(f"    ✓ ERR_BAD_FRAME")

conn.close()
print("\n=== ALL PASSED ===")
