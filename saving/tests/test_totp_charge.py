#!/usr/bin/env python3
"""
Test TOTP_CHARGE (0x25):
  body: [merchant_token 32B][customer_uid 4B][totp_code 4B][amount 8B]
  Merchant-initiated: verify customer TOTP then debit customer → credit merchant.
  Only VIP (uid < 16,777,216) may call this.
  - Normal charge
  - Wrong TOTP     → ERR_TOTP_INVALID (0x0C)
  - Low balance    → ERR_LOW_BALANCE  (0x08)
  - Non-VIP caller → ERR_NOT_MERCHANT (0x0E)
  - No TOTP enrolled → ERR_NOT_FOUND (0x05)
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

def enroll_totp(s, tok, seq, cid, secret):
    s.sendall(encode(0x22, seq, tok + struct.pack(">I", cid) + secret))
    _, r = recv_rpc(s); assert r[0] == 0

def cash_in(s, tok, seq, to_uid, amount, tid):
    body = tok + struct.pack(">IQ", to_uid, amount) + tid.encode()
    s.sendall(encode(0x24, seq, body)); _, r = recv_rpc(s); assert r[0] == 0

def get_balance(s, tok, seq):
    s.sendall(encode(0x12, seq, tok)); _, r = recv_rpc(s)
    assert r[0] == 0; return struct.unpack(">Q", r[1:9])[0]

def totp_now(secret, delta=0):
    T = int(time.time()) // 30 + delta
    h = hmac.new(secret, struct.pack(">Q", T), hashlib.sha256).digest()
    off = h[-1] & 0x0f
    return (struct.unpack(">I", h[off:off+4])[0] & 0x7FFFFFFF) % 1_000_000

def totp_charge(s, tok, seq, cust_uid, totp_code, amount):
    body = tok + struct.pack(">IIQ", cust_uid, totp_code, amount)
    s.sendall(encode(0x25, seq, body)); _, r = recv_rpc(s)
    return r[0]

def register_merchant(s, tok, seq, name):
    s.sendall(encode(0x23, seq, tok + name.encode()))
    _, r = recv_rpc(s); assert r[0] == 0

# ── Setup: bank (uid=1, VIP) is the merchant for TOTP_CHARGE ─────────────────
# TOTP_CHARGE requires caller uid < 16,777,216 (VIP)
# We use bank uid=1 as the VIP merchant
CUST  = 16_777_216 + RUN_ID
TSEC  = hashlib.sha256(f"tc{RUN_ID}".encode()).digest()[:20]
AMT   = 60_000

print(f"=== TOTP_CHARGE test (RUN_ID={RUN_ID}) ===")
print(f"    vip_merchant=1 (bank)  customer={CUST}")

# Customer connection
cc = socket.create_connection((HOST, PORT)); cc.settimeout(5)
create_account(cc, CUST, f"c{RUN_ID}", seq=1)
ct = login(cc, CUST, f"c{RUN_ID}", seq=2)

# Bank/VIP merchant connection
bc = socket.create_connection((HOST, PORT)); bc.settimeout(5)
bt = login(bc, 1, "bank123", seq=1)

# Register bank as merchant (needed for enroll_totp check)
register_merchant(bc, bt, seq=2, name=f"Bank-VIP-{RUN_ID}")

# Fund customer
cash_in(bc, bt, seq=3, to_uid=CUST, amount=500_000, tid=f"TC-FUND-{RUN_ID}")

# VIP merchant enrolls TOTP for customer
enroll_totp(bc, bt, seq=4, cid=CUST, secret=TSEC)
print(f"    TOTP enrolled ✓  customer funded 500,000 ✓")

bal_c_before = get_balance(cc, ct, seq=3)
bal_m_before = get_balance(bc, bt, seq=5)
print(f"    balances: customer={bal_c_before:,}  merchant(bank)={bal_m_before:,}")

# ── Test 1: normal charge ─────────────────────────────────────────────────────
totp = totp_now(TSEC)
rc = totp_charge(bc, bt, seq=6, cust_uid=CUST, totp_code=totp, amount=AMT)
print(f"\n[Test 1] TOTP_CHARGE {AMT:,} TOTP={totp:06d} → code=0x{rc:02X} (expect 0x00)")
assert rc == 0, f"FAIL: 0x{rc:02X}"

bal_c = get_balance(cc, ct, seq=4)
bal_m = get_balance(bc, bt, seq=7)
print(f"    customer: {bal_c_before:,} → {bal_c:,}  (diff={bal_c-bal_c_before:,})")
print(f"    merchant: {bal_m_before:,} → {bal_m:,}  (diff={bal_m-bal_m_before:,})")
assert bal_c == bal_c_before - AMT, f"FAIL customer balance"
assert bal_m == bal_m_before + AMT, f"FAIL merchant balance"
print(f"    ✓ debit customer  credit merchant")

# ── Test 2: wrong TOTP ────────────────────────────────────────────────────────
rc2 = totp_charge(bc, bt, seq=8, cust_uid=CUST, totp_code=123456, amount=AMT)
print(f"\n[Test 2] Wrong TOTP → code=0x{rc2:02X} (expect 0x0C)")
assert rc2 == 0x0C, f"FAIL: 0x{rc2:02X}"
print(f"    ✓ ERR_TOTP_INVALID")

# ── Test 3: low balance ───────────────────────────────────────────────────────
totp3 = totp_now(TSEC)
rc3 = totp_charge(bc, bt, seq=9, cust_uid=CUST, totp_code=totp3, amount=999_999_999)
print(f"\n[Test 3] Charge > balance → code=0x{rc3:02X} (expect 0x08)")
assert rc3 == 0x08, f"FAIL: 0x{rc3:02X}"
print(f"    ✓ ERR_LOW_BALANCE")

# ── Test 4: non-VIP caller → ERR_NOT_MERCHANT ────────────────────────────────
# Use a regular user (uid >= 16,777,216) as caller
uc = socket.create_connection((HOST, PORT)); uc.settimeout(5)
CUST2 = 16_777_216 + RUN_ID + 2
create_account(uc, CUST2, f"u{RUN_ID}", seq=1)
ut = login(uc, CUST2, f"u{RUN_ID}", seq=2)
totp4 = totp_now(TSEC)
rc4 = totp_charge(uc, ut, seq=3, cust_uid=CUST, totp_code=totp4, amount=AMT)
print(f"\n[Test 4] Non-VIP caller → code=0x{rc4:02X} (expect 0x0E)")
assert rc4 == 0x0E, f"FAIL: 0x{rc4:02X}"
print(f"    ✓ ERR_NOT_MERCHANT")
uc.close()

# ── Test 5: no TOTP enrolled for this pair ────────────────────────────────────
CUST3 = 16_777_216 + RUN_ID + 3
nc = socket.create_connection((HOST, PORT)); nc.settimeout(5)
create_account(nc, CUST3, f"n{RUN_ID}", seq=1)
totp5 = totp_now(TSEC)
rc5 = totp_charge(bc, bt, seq=10, cust_uid=CUST3, totp_code=totp5, amount=AMT)
print(f"\n[Test 5] No TOTP enrolled → code=0x{rc5:02X} (expect 0x05)")
assert rc5 == 0x05, f"FAIL: 0x{rc5:02X}"
print(f"    ✓ ERR_NOT_FOUND")
nc.close()

bc.close(); cc.close()
print("\n=== ALL PASSED ===")
