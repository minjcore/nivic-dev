#!/usr/bin/env python3
"""
Test GET_MERCHANT_HISTORY (0x2A):
  - Non-merchant gets ERR_NOT_MERCHANT (0x0E)
  - Empty list when no payments yet
  - Returns C2M payments (via confirm_intent) newest-first
  - customer_id, amount, after_balance all correct
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

def conn():
    s = socket.socket(); s.connect((HOST, PORT)); s.settimeout(3); return s

def create_account(s, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x10, seq, body))
    _, r = recv_rpc(s); assert r[0] in (0, 3)

def login(s, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x02, seq, body))
    _, r = recv_rpc(s); assert r[0] == 0; return r[1:33]

def cash_in(s, bank_token, to_uid, amount, topup_id, seq):
    body = bank_token + struct.pack(">IQ", to_uid, amount) + topup_id.encode()
    s.sendall(encode(0x24, seq, body))
    _, r = recv_rpc(s); assert r[0] == 0

def register_merchant(s, token, name, seq):
    body = token + name.encode().ljust(64, b'\x00')
    s.sendall(encode(0x23, seq, body))
    _, r = recv_rpc(s); assert r[0] == 0

def create_intent(s, token, request_id, order_id, amount, gw_order_id, seq):
    body = (token + struct.pack(">QQQ", request_id, order_id, amount)
            + gw_order_id.encode().ljust(64, b'\x00'))
    s.sendall(encode(0x20, seq, body))
    _, r = recv_rpc(s); assert r[0] == 0

def confirm_intent(s, cust_token, merchant_id, request_id, seq):
    body = cust_token + struct.pack(">IQ", merchant_id, request_id)
    s.sendall(encode(0x29, seq, body))
    _, r = recv_rpc(s); return r[0]

def get_merchant_history(s, token, seq):
    s.sendall(encode(0x2A, seq, token))
    _, r = recv_rpc(s)
    return r

MERCH = 16_800_000 + RUN_ID
CUST1 = 50_000_000 + RUN_ID
CUST2 = 50_000_001 + RUN_ID

s_bank  = conn()
s_merch = conn()
s_cust1 = conn()
s_cust2 = conn()

tok_bank = login(s_bank, 1, "bank123", 1)
create_account(s_merch, MERCH, "pw_merch", 1)
create_account(s_cust1, CUST1, "pw_c1",    1)
create_account(s_cust2, CUST2, "pw_c2",    1)

tok_merch = login(s_merch, MERCH, "pw_merch", 2)
tok_cust1 = login(s_cust1, CUST1, "pw_c1",    2)
tok_cust2 = login(s_cust2, CUST2, "pw_c2",    2)

cash_in(s_bank, tok_bank, CUST1, 1_000_000, f"mh-c1-{RUN_ID}", 2)
cash_in(s_bank, tok_bank, CUST2, 1_000_000, f"mh-c2-{RUN_ID}", 3)
register_merchant(s_merch, tok_merch, f"TestMerch-{RUN_ID}", 3)

# Test 1: non-merchant → ERR_NOT_MERCHANT
r = get_merchant_history(s_cust1, tok_cust1, 3)
assert r[0] == 0x0E, f"Test 1 failed: expected 0x0E, got 0x{r[0]:02x}"
print("Test 1 pass: non-merchant → ERR_NOT_MERCHANT")

# Test 2: no payments yet → count=0
r = get_merchant_history(s_merch, tok_merch, 4)
assert r[0] == 0x00 and r[1] == 0, f"Test 2 failed: code=0x{r[0]:02x} count={r[1]}"
print("Test 2 pass: empty history → count=0")

# Test 3: two intent payments from different customers → newest-first
REQ1, ORD1 = RUN_ID * 10 + 1, RUN_ID * 100 + 1
REQ2, ORD2 = RUN_ID * 10 + 2, RUN_ID * 100 + 2

create_intent(s_merch, tok_merch, REQ1, ORD1, 200_000, f"gw-{RUN_ID}-1", 5)
rc = confirm_intent(s_cust1, tok_cust1, MERCH, REQ1, 4)
assert rc == 0, f"confirm1 failed: 0x{rc:02x}"
time.sleep(0.05)

create_intent(s_merch, tok_merch, REQ2, ORD2, 300_000, f"gw-{RUN_ID}-2", 6)
rc = confirm_intent(s_cust2, tok_cust2, MERCH, REQ2, 4)
assert rc == 0, f"confirm2 failed: 0x{rc:02x}"

r = get_merchant_history(s_merch, tok_merch, 7)
assert r[0] == 0x00, f"Test 3a failed: code=0x{r[0]:02x}"
count = r[1]
assert count == 2, f"Test 3b failed: expected 2 entries, got {count}"

e0_cid, e0_amt, e0_bal = struct.unpack(">IQq", r[2:22])
e1_cid, e1_amt, e1_bal = struct.unpack(">IQq", r[22:42])
assert e0_cid == CUST2, f"Test 3c: newest should be cust2 ({CUST2}), got {e0_cid}"
assert e0_amt == 300_000, f"Test 3d: wrong amount {e0_amt}"
assert e1_cid == CUST1, f"Test 3e: second should be cust1 ({CUST1}), got {e1_cid}"
assert e1_amt == 200_000, f"Test 3f: wrong amount {e1_amt}"
assert e0_bal > e1_bal, f"Test 3g: balance should grow, {e0_bal} vs {e1_bal}"
print(f"Test 3 pass: 2 entries newest-first, amounts correct (bal: {e1_bal} → {e0_bal})")

for s in (s_bank, s_merch, s_cust1, s_cust2):
    s.close()
print("All merchant_history tests passed.")
