#!/usr/bin/env python3
"""
Test LOGIN (0x02) / LOGOUT (0x03):
  LOGIN  body: [mid 4B][pw_hash 32B]
  LOGIN_ACK:   [code 1B][token 32B]
  - Normal login → token works
  - Wrong password → ERR_BAD_PASSWORD (0x06)
  - Unknown account → ERR_NOT_FOUND   (0x05)
  - Double login → new token (old session replaced)
  - LOGOUT invalidates token → ERR_BAD_TOKEN on next call
"""
import socket, struct, hmac, hashlib, random

HOST   = "127.0.0.1"
PORT   = 7474
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

def raw_login(s, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x02, seq, body))
    ftype, r = recv_rpc(s)
    assert ftype == 0x81, f"expected LOGIN_ACK 0x81, got 0x{ftype:02X}"
    code  = r[0]
    token = r[1:33] if len(r) >= 33 else None
    return code, token

def get_balance(s, tok, seq):
    s.sendall(encode(0x12, seq, tok)); _, r = recv_rpc(s)
    return r[0]

def logout(s, tok, seq):
    s.sendall(encode(0x03, seq, tok)); _, r = recv_rpc(s)
    return r[0]

# ── Setup ─────────────────────────────────────────────────────────────────────
UID = 16_777_216 + RUN_ID
PW  = f"pass{RUN_ID}"
print(f"=== LOGIN test (RUN_ID={RUN_ID}) ===")
print(f"    uid={UID}")

conn = socket.create_connection((HOST, PORT)); conn.settimeout(5)
create_account(conn, UID, PW, seq=1)

# ── Test 1: normal login → token returned ────────────────────────────────────
code, token = raw_login(conn, UID, PW, seq=2)
print(f"\n[Test 1] Login → code=0x{code:02X}  token={'<32 bytes>' if token else 'None'}")
assert code == 0, f"FAIL: 0x{code:02X}"
assert token and len(token) == 32, f"FAIL: bad token {token}"
print(f"    ✓ login OK, token received")

# ── Test 2: token works for subsequent requests ───────────────────────────────
rc = get_balance(conn, token, seq=3)
print(f"\n[Test 2] Token → GET_BALANCE code=0x{rc:02X} (expect 0x00)")
assert rc == 0, f"FAIL: 0x{rc:02X}"
print(f"    ✓ token valid for subsequent calls")

# ── Test 3: wrong password → ERR_BAD_PASSWORD ────────────────────────────────
code3, _ = raw_login(conn, UID, "wrongpassword", seq=4)
print(f"\n[Test 3] Wrong password → code=0x{code3:02X} (expect 0x06)")
assert code3 == 0x06, f"FAIL: 0x{code3:02X}"
print(f"    ✓ ERR_BAD_PASSWORD")

# ── Test 4: unknown account → ERR_NOT_FOUND ──────────────────────────────────
code4, _ = raw_login(conn, 9_999_777, PW, seq=5)
print(f"\n[Test 4] Unknown uid → code=0x{code4:02X} (expect 0x05)")
assert code4 == 0x05, f"FAIL: 0x{code4:02X}"
print(f"    ✓ ERR_NOT_FOUND")

# ── Test 5: double login → new token ─────────────────────────────────────────
code5, token2 = raw_login(conn, UID, PW, seq=6)
print(f"\n[Test 5] Double login → code=0x{code5:02X}  same_token={token==token2}")
assert code5 == 0x00, f"FAIL: 0x{code5:02X}"
assert token2 != token, "FAIL: expected new token on re-login"
rc5 = get_balance(conn, token2, seq=7)
assert rc5 == 0, f"FAIL: new token rejected 0x{rc5:02X}"
print(f"    ✓ new token issued, works correctly")

# ── Test 6: LOGOUT → token invalidated ───────────────────────────────────────
rc_lo = logout(conn, token2, seq=8)
print(f"\n[Test 6] LOGOUT → code=0x{rc_lo:02X}")
assert rc_lo == 0, f"FAIL: 0x{rc_lo:02X}"
rc6 = get_balance(conn, token2, seq=9)
print(f"    GET_BALANCE after logout → code=0x{rc6:02X} (expect 0x07 ERR_BAD_TOKEN)")
assert rc6 == 0x07, f"FAIL: 0x{rc6:02X}"
print(f"    ✓ token invalidated after LOGOUT")

conn.close()
print("\n=== ALL PASSED ===")
