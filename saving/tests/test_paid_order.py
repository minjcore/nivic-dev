#!/usr/bin/env python3
"""Test: CREATE_INTENT on an already-paid orderId → ERR_INTENT_SETTLED (0x0D)"""
import socket, struct, hmac, hashlib, random

import os
HOST   = os.getenv("WIRE_HOST", "127.0.0.1")
PORT   = int(os.getenv("WIRE_PORT", "7474"))
SECRET = b"saving_wire_secret_changeme"
RUN_ID = random.randint(100_000, 999_999)

def sign(payload):
    return hmac.new(SECRET, payload, hashlib.sha256).digest()

def encode(ftype, seq, body):
    total = 4 + 1 + 4 + len(body) + 32
    hdr   = struct.pack(">IBI", total, ftype, seq)
    return hdr + body + sign(hdr + body)

def recv_response(sock):
    while True:
        hdr = b""
        while len(hdr) < 9:
            hdr += sock.recv(9 - len(hdr))
        total, ftype, seq = struct.unpack(">IBI", hdr)
        rest = b""
        while len(rest) < total - 9:
            rest += sock.recv(total - 9 - len(rest))
        if ftype >= 0xC0:
            continue
        return ftype, seq, rest[:-32]

def login(sock, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    sock.sendall(encode(0x02, seq, body))
    _, _, resp = recv_response(sock)
    assert resp[0] == 0, f"login failed 0x{resp[0]:02X}"
    return resp[1:33]

def create_account(sock, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    sock.sendall(encode(0x10, seq, body))
    _, _, resp = recv_response(sock)
    assert resp[0] in (0x00, 0x03)

def register_merchant(sock, token, seq, name):
    sock.sendall(encode(0x23, seq, token + name.encode()))
    _, _, resp = recv_response(sock)
    assert resp[0] == 0

def create_intent(sock, token, seq, request_id, order_id, amount):
    body = token + struct.pack(">QQQ", request_id, order_id, amount)
    sock.sendall(encode(0x20, seq, body))
    _, _, resp = recv_response(sock)
    if len(resp) >= 22:
        return {"code": resp[0], "status": resp[1], "amount": struct.unpack(">Q", resp[14:22])[0]}
    return {"code": resp[0], "status": None}

def pay_intent(sock, cust_token, seq, merchant_id, request_id, totp_code):
    body = cust_token + struct.pack(">IQI", merchant_id, request_id, totp_code)
    sock.sendall(encode(0x21, seq, body))
    _, _, resp = recv_response(sock)
    return resp[0]

def cash_in(sock, bank_token, seq, to_uid, amount, topup_id):
    body = bank_token + struct.pack(">IQ", to_uid, amount) + topup_id.encode()
    sock.sendall(encode(0x24, seq, body))
    _, _, resp = recv_response(sock)
    return resp[0]

def enroll_totp(sock, merch_token, seq, customer_id, secret_20b):
    body = merch_token + struct.pack(">I", customer_id) + secret_20b
    sock.sendall(encode(0x22, seq, body))
    _, _, resp = recv_response(sock)
    return resp[0]

import time
def totp_now(secret_20b):
    T = int(time.time()) // 30
    msg = struct.pack(">Q", T)
    h   = hmac.new(secret_20b, msg, hashlib.sha256).digest()
    off = h[-1] & 0x0f
    code = struct.unpack(">I", h[off:off+4])[0] & 0x7FFFFFFF
    return code % 1_000_000

# ── Setup ─────────────────────────────────────────────────────────────────────

MERCH_UID  = 16_777_216 + RUN_ID
CUST_UID   = 16_777_216 + RUN_ID + 50_000
BANK_UID   = 1

MERCH_PW   = f"m{RUN_ID}"
CUST_PW    = f"c{RUN_ID}"
BANK_PW    = "bank123"
TOTP_SEC   = hashlib.sha256(f"totp{RUN_ID}".encode()).digest()[:20]

ORDER_ID   = RUN_ID * 10 + 1
AMOUNT     = 50_000

print(f"=== PAID ORDER test (RUN_ID={RUN_ID}) ===")
print(f"    merchant={MERCH_UID}  customer={CUST_UID}")

conn = socket.create_connection((HOST, PORT))
conn.settimeout(5)

# Create accounts
create_account(conn, MERCH_UID, MERCH_PW, seq=1)
create_account(conn, CUST_UID,  CUST_PW,  seq=2)

# Login all
merch_token = login(conn, MERCH_UID, MERCH_PW, seq=3)
cust_token  = login(conn, CUST_UID,  CUST_PW,  seq=4)

# Open separate connection for bank (keeps sessions separate)
bconn = socket.create_connection((HOST, PORT))
bconn.settimeout(5)
bank_token = login(bconn, BANK_UID, BANK_PW, seq=1)

# Register merchant + enroll TOTP
register_merchant(conn, merch_token, seq=5, name=f"Shop{RUN_ID}")
rc = enroll_totp(conn, merch_token, seq=6, customer_id=CUST_UID, secret_20b=TOTP_SEC)
assert rc == 0, f"enroll_totp failed 0x{rc:02X}"

# Fund customer
rc = cash_in(bconn, bank_token, seq=2, to_uid=CUST_UID,
             amount=500_000, topup_id=f"TOPUP-PAID-{RUN_ID}")
assert rc == 0, f"cash_in failed 0x{rc:02X}"
print(f"    customer funded 500,000 ✓")
bconn.close()

# ── Step A: create intent ─────────────────────────────────────────────────────
REQ_ID_A = RUN_ID * 100 + 1
r = create_intent(conn, merch_token, seq=7,
                  request_id=REQ_ID_A, order_id=ORDER_ID, amount=AMOUNT)
assert r["code"] == 0 and r["status"] == 1, f"FAIL create: {r}"
print(f"\n[A] Intent created — order_id={ORDER_ID}  amount={AMOUNT:,} ✓")

# ── Step B: customer pays the intent ─────────────────────────────────────────
totp = totp_now(TOTP_SEC)
rc = pay_intent(conn, cust_token, seq=8,
                merchant_id=MERCH_UID, request_id=REQ_ID_A, totp_code=totp)
assert rc == 0, f"FAIL pay_intent 0x{rc:02X}"
print(f"[B] Intent paid (TOTP={totp:06d}) ✓  → status now settled")

# ── Step C: merchant tries to create new intent for same orderId → ERR_INTENT_SETTLED
REQ_ID_B = RUN_ID * 100 + 2   # fresh requestId, same orderId
r2 = create_intent(conn, merch_token, seq=9,
                   request_id=REQ_ID_B, order_id=ORDER_ID, amount=AMOUNT)
print(f"\n[C] CREATE_INTENT on paid orderId → code=0x{r2['code']:02X}  (expect 0x0D ERR_INTENT_SETTLED)")
assert r2["code"] == 0x0D, f"FAIL: expected 0x0D, got 0x{r2['code']:02X}"
print(f"    ✓ ERR_INTENT_SETTLED returned correctly")

conn.close()
print("\n=== PASSED ===")
