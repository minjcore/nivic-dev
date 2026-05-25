#!/usr/bin/env python3
"""
Test CONFIRM_INTENT (0x29):
  body: [customer_token 32B][merchant_id 4B][request_id 8B]
  Customer-initiated: scan merchant QR → confirm → pay. No TOTP required.
  ACK extra: [after_balance 8B]
  - Normal confirm
  - Already settled   → ERR_INTENT_SETTLED (0x0D)
  - Low balance       → ERR_LOW_BALANCE    (0x08)
  - Intent not found  → ERR_NOT_FOUND      (0x05)
  - EVT_INTENT_PAID push to merchant
"""
import socket, struct, hmac, hashlib, random, time

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

def login(s, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x02, seq, body)); _, r = recv_rpc(s)
    assert r[0] == 0; return r[1:33]

def cash_in(s, tok, seq, to_uid, amount, tid):
    body = tok + struct.pack(">IQ", to_uid, amount) + tid.encode()
    s.sendall(encode(0x24, seq, body)); _, r = recv_rpc(s); assert r[0] == 0

def get_balance(s, tok, seq):
    s.sendall(encode(0x12, seq, tok)); _, r = recv_rpc(s)
    assert r[0] == 0; return struct.unpack(">Q", r[1:9])[0]

def register_merchant(s, tok, seq, name):
    s.sendall(encode(0x23, seq, tok + name.encode()))
    _, r = recv_rpc(s); assert r[0] == 0

def create_intent(s, tok, seq, request_id, order_id, amount):
    body = tok + struct.pack(">QQQ", request_id, order_id, amount)
    s.sendall(encode(0x20, seq, body)); _, r = recv_rpc(s)
    assert r[0] == 0

def confirm_intent(s, tok, seq, merchant_id, request_id):
    body = tok + struct.pack(">IQ", merchant_id, request_id)
    s.sendall(encode(0x29, seq, body)); _, r = recv_rpc(s)
    return r[0], r[1:]

MERCH = 16_777_216 + RUN_ID
CUST  = 16_777_216 + RUN_ID + 1
AMT   = 75_000
REQ   = RUN_ID * 100
ORD   = RUN_ID * 200

print(f"=== CONFIRM_INTENT test (RUN_ID={RUN_ID}) ===")
print(f"    merchant={MERCH}  customer={CUST}")

# Bank
bc = socket.create_connection((HOST, PORT)); bc.settimeout(5)
bt = login(bc, 1, "bank123", seq=1)

# Merchant
mc = socket.create_connection((HOST, PORT)); mc.settimeout(5)
create_account(mc, MERCH, f"m{RUN_ID}", seq=1)
mt = login(mc, MERCH, f"m{RUN_ID}", seq=2)
register_merchant(mc, mt, seq=3, name=f"Shop-{RUN_ID}")

# Customer
cc = socket.create_connection((HOST, PORT)); cc.settimeout(5)
create_account(cc, CUST, f"c{RUN_ID}", seq=1)
ct = login(cc, CUST, f"c{RUN_ID}", seq=2)
cash_in(bc, bt, seq=2, to_uid=CUST, amount=500_000, tid=f"CI-FUND-{RUN_ID}")

# Merchant creates intent
create_intent(mc, mt, seq=4, request_id=REQ, order_id=ORD, amount=AMT)
print(f"    intent created: request_id={REQ} amount={AMT:,} ✓")

bal_c_before = get_balance(cc, ct, seq=3)
bal_m_before = get_balance(mc, mt, seq=5)
print(f"    balances: customer={bal_c_before:,}  merchant={bal_m_before:,}")

# ── Test 1: normal confirm ────────────────────────────────────────────────────
rc, extra = confirm_intent(cc, ct, seq=4, merchant_id=MERCH, request_id=REQ)
print(f"\n[Test 1] CONFIRM_INTENT {AMT:,} → code=0x{rc:02X} (expect 0x00)")
assert rc == 0, f"FAIL: 0x{rc:02X}"
after_bal = struct.unpack(">Q", extra[:8])[0] if len(extra) >= 8 else -1
bal_c = get_balance(cc, ct, seq=5)
bal_m = get_balance(mc, mt, seq=6)
print(f"    customer: {bal_c_before:,} → {bal_c:,}  (ACK after_balance={after_bal:,})")
print(f"    merchant: {bal_m_before:,} → {bal_m:,}")
assert bal_c == bal_c_before - AMT, "FAIL customer balance"
assert bal_m == bal_m_before + AMT, "FAIL merchant balance"
assert after_bal == bal_c,          "FAIL ACK after_balance mismatch"
print(f"    ✓ debit customer  credit merchant  ACK balance correct")

# ── Test 2: already settled ───────────────────────────────────────────────────
rc2, _ = confirm_intent(cc, ct, seq=6, merchant_id=MERCH, request_id=REQ)
print(f"\n[Test 2] Already settled → code=0x{rc2:02X} (expect 0x0D)")
assert rc2 == 0x0D, f"FAIL: 0x{rc2:02X}"
print(f"    ✓ ERR_INTENT_SETTLED")

# ── Test 3: low balance ───────────────────────────────────────────────────────
REQ2 = REQ + 1; ORD2 = ORD + 1
create_intent(mc, mt, seq=7, request_id=REQ2, order_id=ORD2, amount=999_999_999)
rc3, _ = confirm_intent(cc, ct, seq=7, merchant_id=MERCH, request_id=REQ2)
print(f"\n[Test 3] Low balance → code=0x{rc3:02X} (expect 0x08)")
assert rc3 == 0x08, f"FAIL: 0x{rc3:02X}"
print(f"    ✓ ERR_LOW_BALANCE")

# ── Test 4: intent not found ──────────────────────────────────────────────────
rc4, _ = confirm_intent(cc, ct, seq=8, merchant_id=MERCH, request_id=REQ + 9999)
print(f"\n[Test 4] Intent not found → code=0x{rc4:02X} (expect 0x05)")
assert rc4 == 0x05, f"FAIL: 0x{rc4:02X}"
print(f"    ✓ ERR_NOT_FOUND")

bc.close(); mc.close(); cc.close()
print("\n=== ALL PASSED ===")
