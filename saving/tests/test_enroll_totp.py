#!/usr/bin/env python3
"""
Test ENROLL_TOTP (0x22):
  body: [merchant_token 32B][customer_id 4B][secret 20B]
  - Normal enroll + TOTP verify works
  - Re-enroll (upsert) with new secret → new secret takes effect
  - Non-merchant caller → ERR_NOT_MERCHANT (0x0E)
  - Bad token → ERR_BAD_TOKEN (0x07)
  - Short body → ERR_BAD_FRAME (0x01)
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

def enroll_totp(s, tok, seq, cid, secret):
    s.sendall(encode(0x22, seq, tok + struct.pack(">I", cid) + secret))
    _, r = recv_rpc(s); return r[0]

def cash_in(s, tok, seq, to_uid, amount, tid):
    body = tok + struct.pack(">IQ", to_uid, amount) + tid.encode()
    s.sendall(encode(0x24, seq, body)); _, r = recv_rpc(s); assert r[0] == 0

def totp_now(secret, delta=0):
    T = int(time.time()) // 30 + delta
    h = hmac.new(secret, struct.pack(">Q", T), hashlib.sha256).digest()
    off = h[-1] & 0x0f
    return (struct.unpack(">I", h[off:off+4])[0] & 0x7FFFFFFF) % 1_000_000

def pay_intent(s, tok, seq, mid, req_id, totp_code):
    body = tok + struct.pack(">IQI", mid, req_id, totp_code)
    s.sendall(encode(0x21, seq, body)); _, r = recv_rpc(s); return r[0]

def create_intent(s, tok, seq, req_id, ord_id, amount):
    body = tok + struct.pack(">QQQ", req_id, ord_id, amount)
    s.sendall(encode(0x20, seq, body)); _, r = recv_rpc(s); assert r[0] == 0

# ── Setup ─────────────────────────────────────────────────────────────────────
MERCH = 16_777_216 + RUN_ID
CUST  = 16_777_216 + RUN_ID + 1
SEC_A = hashlib.sha256(f"secA{RUN_ID}".encode()).digest()[:20]
SEC_B = hashlib.sha256(f"secB{RUN_ID}".encode()).digest()[:20]

print(f"=== ENROLL_TOTP test (RUN_ID={RUN_ID}) ===")

mc = socket.create_connection((HOST, PORT)); mc.settimeout(5)
cc = socket.create_connection((HOST, PORT)); cc.settimeout(5)
create_account(mc, MERCH, f"m{RUN_ID}", seq=1)
create_account(cc, CUST,  f"c{RUN_ID}", seq=1)
mt = login(mc, MERCH, f"m{RUN_ID}", seq=2)
ct = login(cc, CUST,  f"c{RUN_ID}", seq=2)
register_merchant(mc, mt, seq=3, name=f"Shop{RUN_ID}")

bc = socket.create_connection((HOST, PORT)); bc.settimeout(5)
bt = login(bc, 1, "bank123", seq=1)
cash_in(bc, bt, seq=2, to_uid=CUST, amount=500_000, tid=f"ET-FUND-{RUN_ID}")
bc.close()

# ── Test 1: normal enroll ─────────────────────────────────────────────────────
rc = enroll_totp(mc, mt, seq=4, cid=CUST, secret=SEC_A)
print(f"\n[Test 1] Enroll TOTP → code=0x{rc:02X} (expect 0x00)")
assert rc == 0, f"FAIL: 0x{rc:02X}"
print(f"    ✓ enrolled")

# Verify: pay an intent using enrolled secret
create_intent(mc, mt, seq=5, req_id=RUN_ID*100+1, ord_id=RUN_ID*10+1, amount=10_000)
totp = totp_now(SEC_A)
rc2 = pay_intent(cc, ct, seq=3, mid=MERCH, req_id=RUN_ID*100+1, totp_code=totp)
print(f"    PAY_INTENT with correct TOTP={totp:06d} → code=0x{rc2:02X}")
assert rc2 == 0, f"FAIL: 0x{rc2:02X}"
print(f"    ✓ TOTP verified correctly after enroll")

# ── Test 2: re-enroll with new secret → old secret invalid ───────────────────
rc3 = enroll_totp(mc, mt, seq=6, cid=CUST, secret=SEC_B)
print(f"\n[Test 2] Re-enroll with new secret → code=0x{rc3:02X}")
assert rc3 == 0, f"FAIL: 0x{rc3:02X}"

create_intent(mc, mt, seq=7, req_id=RUN_ID*100+2, ord_id=RUN_ID*10+2, amount=10_000)
# Old secret should fail
old_totp = totp_now(SEC_A)
rc4 = pay_intent(cc, ct, seq=4, mid=MERCH, req_id=RUN_ID*100+2, totp_code=old_totp)
print(f"    Old secret TOTP → code=0x{rc4:02X} (expect 0x0C ERR_TOTP_INVALID)")
assert rc4 == 0x0C, f"FAIL: 0x{rc4:02X}"

# New secret should work
new_totp = totp_now(SEC_B)
rc5 = pay_intent(cc, ct, seq=5, mid=MERCH, req_id=RUN_ID*100+2, totp_code=new_totp)
print(f"    New secret TOTP → code=0x{rc5:02X} (expect 0x00)")
assert rc5 == 0, f"FAIL: 0x{rc5:02X}"
print(f"    ✓ re-enroll upserts secret correctly")

# ── Test 3: non-merchant caller → ERR_NOT_MERCHANT ───────────────────────────
rc6 = enroll_totp(cc, ct, seq=6, cid=CUST, secret=SEC_A)
print(f"\n[Test 3] Non-merchant caller → code=0x{rc6:02X} (expect 0x0E)")
assert rc6 == 0x0E, f"FAIL: 0x{rc6:02X}"
print(f"    ✓ ERR_NOT_MERCHANT")

# ── Test 4: bad token → ERR_BAD_TOKEN ────────────────────────────────────────
rc7 = enroll_totp(mc, bytes(32), seq=8, cid=CUST, secret=SEC_A)
print(f"\n[Test 4] Bad token → code=0x{rc7:02X} (expect 0x07)")
assert rc7 == 0x07, f"FAIL: 0x{rc7:02X}"
print(f"    ✓ ERR_BAD_TOKEN")

# ── Test 5: short body → ERR_BAD_FRAME ───────────────────────────────────────
mc.sendall(encode(0x22, seq=9, body=bytes(10)))
_, r8 = recv_rpc(mc)
print(f"\n[Test 5] Short body → code=0x{r8[0]:02X} (expect 0x01)")
assert r8[0] == 0x01, f"FAIL: 0x{r8[0]:02X}"
print(f"    ✓ ERR_BAD_FRAME")

mc.close(); cc.close()
print("\n=== ALL PASSED ===")
