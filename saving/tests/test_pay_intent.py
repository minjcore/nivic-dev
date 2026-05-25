#!/usr/bin/env python3
"""
Test PAY_INTENT (0x21):
  body: [customer_token 32B][merchant_id 4B][request_id 8B][totp_code 4B]
  - Normal pay: debit customer, credit merchant, push EVT_INTENT_PAID
  - Wrong TOTP     → ERR_TOTP_INVALID  (0x0C)
  - Already paid   → ERR_INTENT_SETTLED (0x0D)
  - Intent missing → ERR_NOT_FOUND     (0x05)
  - Low balance    → ERR_LOW_BALANCE   (0x08)
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

def drain_push(s, timeout=0.3):
    pushes = []
    s.settimeout(timeout)
    try:
        while True:
            hdr = b""
            while len(hdr) < 9: hdr += s.recv(9-len(hdr))
            total, ftype, _ = struct.unpack(">IBI", hdr)
            rest = b""
            while len(rest) < total-9: rest += s.recv(total-9-len(rest))
            if ftype >= 0xC0: pushes.append((ftype, rest[:-32]))
    except: pass
    s.settimeout(5)
    return pushes

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
    _, r = recv_rpc(s); assert r[0] == 0

def cash_in(s, tok, seq, to_uid, amount, tid):
    body = tok + struct.pack(">IQ", to_uid, amount) + tid.encode()
    s.sendall(encode(0x24, seq, body)); _, r = recv_rpc(s); assert r[0] == 0

def create_intent(s, tok, seq, req_id, ord_id, amount):
    body = tok + struct.pack(">QQQ", req_id, ord_id, amount)
    s.sendall(encode(0x20, seq, body)); _, r = recv_rpc(s); assert r[0] == 0

def get_balance(s, tok, seq):
    s.sendall(encode(0x12, seq, tok)); _, r = recv_rpc(s)
    assert r[0] == 0; return struct.unpack(">Q", r[1:9])[0]

def totp_now(secret, delta=0):
    T = int(time.time()) // 30 + delta
    h = hmac.new(secret, struct.pack(">Q", T), hashlib.sha256).digest()
    off = h[-1] & 0x0f
    return (struct.unpack(">I", h[off:off+4])[0] & 0x7FFFFFFF) % 1_000_000

def pay_intent(s, tok, seq, merchant_id, req_id, totp_code):
    body = tok + struct.pack(">IQI", merchant_id, req_id, totp_code)
    s.sendall(encode(0x21, seq, body)); _, r = recv_rpc(s)
    return r[0]

# ── Setup ─────────────────────────────────────────────────────────────────────
MERCH = 16_777_216 + RUN_ID
CUST  = 16_777_216 + RUN_ID + 1
TSEC  = hashlib.sha256(f"totp{RUN_ID}".encode()).digest()[:20]
AMT   = 80_000

print(f"=== PAY_INTENT test (RUN_ID={RUN_ID}) ===")
print(f"    merchant={MERCH}  customer={CUST}")

mc = socket.create_connection((HOST, PORT)); mc.settimeout(5)
cc = socket.create_connection((HOST, PORT)); cc.settimeout(5)
create_account(mc, MERCH, f"m{RUN_ID}", seq=1)
create_account(cc, CUST,  f"c{RUN_ID}", seq=1)
mt = login(mc, MERCH, f"m{RUN_ID}", seq=2)
ct = login(cc, CUST,  f"c{RUN_ID}", seq=2)
register_merchant(mc, mt, seq=3, name=f"Shop{RUN_ID}")
enroll_totp(mc, mt, seq=4, cid=CUST, secret=TSEC)

bc = socket.create_connection((HOST, PORT)); bc.settimeout(5)
bt = login(bc, 1, "bank123", seq=1)
cash_in(bc, bt, seq=2, to_uid=CUST,  amount=500_000, tid=f"PI-C-{RUN_ID}")
cash_in(bc, bt, seq=3, to_uid=MERCH, amount=100_000, tid=f"PI-M-{RUN_ID}")
bc.close()
print(f"    funded: customer=500,000  merchant=100,000")

REQ_ID = RUN_ID * 100 + 1
ORD_ID = RUN_ID * 10  + 1
create_intent(mc, mt, seq=5, req_id=REQ_ID, ord_id=ORD_ID, amount=AMT)
print(f"    intent created: req_id={REQ_ID}  amount={AMT:,}")

bal_c_before = get_balance(cc, ct, seq=3)
bal_m_before = get_balance(mc, mt, seq=6)
print(f"    balances before: customer={bal_c_before:,}  merchant={bal_m_before:,}")

# ── Test 1: normal pay ────────────────────────────────────────────────────────
totp = totp_now(TSEC)
rc = pay_intent(cc, ct, seq=4, merchant_id=MERCH, req_id=REQ_ID, totp_code=totp)
print(f"\n[Test 1] PAY_INTENT TOTP={totp:06d} → code=0x{rc:02X} (expect 0x00)")
assert rc == 0, f"FAIL: 0x{rc:02X}"

time.sleep(0.2)
pushes_m = drain_push(mc)
bal_c = get_balance(cc, ct, seq=5)
bal_m = get_balance(mc, mt, seq=7)
print(f"    customer: {bal_c_before:,} → {bal_c:,}  (diff={bal_c-bal_c_before:,})")
print(f"    merchant: {bal_m_before:,} → {bal_m:,}  (diff={bal_m-bal_m_before:,})")
assert bal_c == bal_c_before - AMT, f"FAIL customer balance"
assert bal_m == bal_m_before + AMT, f"FAIL merchant balance"
print(f"    ✓ debit customer  credit merchant")

# ── Test 2: EVT_INTENT_PAID push to merchant ─────────────────────────────────
evt_paid = [p for p in pushes_m if p[0] == 0xC4]
print(f"\n[Test 2] EVT_INTENT_PAID push → found={len(evt_paid)}")
assert len(evt_paid) >= 1, f"FAIL: no EVT_INTENT_PAID"
body = evt_paid[0][1]
req_r  = struct.unpack(">Q", body[0:8])[0]
cust_r = struct.unpack(">I", body[8:12])[0]
amt_r  = struct.unpack(">Q", body[12:20])[0]
print(f"    request_id={req_r}  customer={cust_r}  amount={amt_r:,}")
assert req_r == REQ_ID and cust_r == CUST and amt_r == AMT
print(f"    ✓ EVT_INTENT_PAID correct")

# ── Test 3: already settled → ERR_INTENT_SETTLED ────────────────────────────
totp2 = totp_now(TSEC)
rc2 = pay_intent(cc, ct, seq=6, merchant_id=MERCH, req_id=REQ_ID, totp_code=totp2)
print(f"\n[Test 3] Pay already settled intent → code=0x{rc2:02X} (expect 0x0D)")
assert rc2 == 0x0D, f"FAIL: 0x{rc2:02X}"
print(f"    ✓ ERR_INTENT_SETTLED")

# ── Test 4: wrong TOTP ────────────────────────────────────────────────────────
REQ_ID2 = RUN_ID * 100 + 2
create_intent(mc, mt, seq=8, req_id=REQ_ID2, ord_id=RUN_ID*10+2, amount=AMT)
rc3 = pay_intent(cc, ct, seq=7, merchant_id=MERCH, req_id=REQ_ID2, totp_code=123456)
print(f"\n[Test 4] Wrong TOTP → code=0x{rc3:02X} (expect 0x0C)")
assert rc3 == 0x0C, f"FAIL: 0x{rc3:02X}"
print(f"    ✓ ERR_TOTP_INVALID")

# ── Test 5: intent not found ──────────────────────────────────────────────────
totp3 = totp_now(TSEC)
rc4 = pay_intent(cc, ct, seq=8, merchant_id=MERCH, req_id=999_999_999, totp_code=totp3)
print(f"\n[Test 5] Intent not found → code=0x{rc4:02X} (expect 0x05)")
assert rc4 == 0x05, f"FAIL: 0x{rc4:02X}"
print(f"    ✓ ERR_NOT_FOUND")

# ── Test 6: low balance ───────────────────────────────────────────────────────
REQ_ID3 = RUN_ID * 100 + 3
create_intent(mc, mt, seq=9, req_id=REQ_ID3, ord_id=RUN_ID*10+3, amount=999_999_999)
totp4 = totp_now(TSEC)
rc5 = pay_intent(cc, ct, seq=9, merchant_id=MERCH, req_id=REQ_ID3, totp_code=totp4)
print(f"\n[Test 6] Low balance → code=0x{rc5:02X} (expect 0x08)")
assert rc5 == 0x08, f"FAIL: 0x{rc5:02X}"
print(f"    ✓ ERR_LOW_BALANCE")

mc.close(); cc.close()
print("\n=== ALL PASSED ===")
