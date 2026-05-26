#!/usr/bin/env python3
"""
Test LIST_INTENTS:
  - Returns only pending (status=0) intents, newest first
  - Settled intents are excluded
  - Max 10 per call
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
    s.sendall(encode(0x10, seq, body))
    _, r = recv_rpc(s); assert r[0] in (0,3)

def login(s, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x02, seq, body))
    _, r = recv_rpc(s); assert r[0] == 0; return r[1:33]

def register_merchant(s, tok, seq, name):
    s.sendall(encode(0x23, seq, tok + name.encode()))
    _, r = recv_rpc(s); assert r[0] == 0

def enroll_totp(s, tok, seq, cid, secret):
    s.sendall(encode(0x22, seq, tok + struct.pack(">I", cid) + secret))
    _, r = recv_rpc(s); assert r[0] == 0

def cash_in(s, tok, seq, to_uid, amount, tid):
    body = tok + struct.pack(">IQ", to_uid, amount) + tid.encode()
    s.sendall(encode(0x24, seq, body))
    _, r = recv_rpc(s); assert r[0] == 0

def create_intent(s, tok, seq, req_id, ord_id, amount):
    body = tok + struct.pack(">QQQ", req_id, ord_id, amount)
    s.sendall(encode(0x20, seq, body))
    _, r = recv_rpc(s); assert r[0] == 0

def pay_intent(s, tok, seq, mid, req_id, code):
    body = tok + struct.pack(">IQI", mid, req_id, code)
    s.sendall(encode(0x21, seq, body))
    _, r = recv_rpc(s); return r[0]

def totp_now(secret):
    T   = int(time.time()) // 30
    h   = hmac.new(secret, struct.pack(">Q", T), hashlib.sha256).digest()
    off = h[-1] & 0x0f
    return (struct.unpack(">I", h[off:off+4])[0] & 0x7FFFFFFF) % 1_000_000

def list_intents(s, tok, seq):
    s.sendall(encode(0x28, seq, tok))
    _, r = recv_rpc(s)
    assert r[0] == 0, f"list_intents err 0x{r[0]:02X}"
    count = r[1]
    items = []
    for i in range(count):
        off = 2 + i*16
        req_id = struct.unpack(">Q", r[off:off+8])[0]
        amount = struct.unpack(">Q", r[off+8:off+16])[0]
        items.append({"request_id": req_id, "amount": amount})
    return items

# ── Setup ─────────────────────────────────────────────────────────────────────
MERCH = 16_777_216 + RUN_ID
CUST  = 16_777_216 + RUN_ID + 50_000
TSEC  = hashlib.sha256(f"t{RUN_ID}".encode()).digest()[:20]

print(f"=== LIST_INTENTS test (RUN_ID={RUN_ID}) ===")

conn = socket.create_connection((HOST, PORT)); conn.settimeout(5)
create_account(conn, MERCH, f"m{RUN_ID}", seq=1)
create_account(conn, CUST,  f"c{RUN_ID}", seq=2)
mt = login(conn, MERCH, f"m{RUN_ID}", seq=3)
ct = login(conn, CUST,  f"c{RUN_ID}", seq=4)
register_merchant(conn, mt, seq=5, name=f"Shop{RUN_ID}")
enroll_totp(conn, mt, seq=6, cid=CUST, secret=TSEC)

# Fund customer
bc = socket.create_connection((HOST, PORT)); bc.settimeout(5)
bt = login(bc, 1, "bank123", seq=1)
cash_in(bc, bt, seq=2, to_uid=CUST, amount=1_000_000, tid=f"LI-{RUN_ID}")
bc.close()
print("    customer funded ✓")

# ── Create 3 pending intents ──────────────────────────────────────────────────
seq = 7
INTENTS = [(RUN_ID*100+i, RUN_ID*10+i, (i+1)*10_000) for i in range(1,4)]
for req_id, ord_id, amt in INTENTS:
    create_intent(conn, mt, seq, req_id, ord_id, amt); seq += 1
print(f"    created 3 intents: amounts {[x[2] for x in INTENTS]}")

# Pay the first intent (status → settled)
totp = totp_now(TSEC)
rc = pay_intent(conn, ct, seq, MERCH, INTENTS[0][0], totp); seq += 1
assert rc == 0, f"pay failed 0x{rc:02X}"
print(f"    paid intent req_id={INTENTS[0][0]} (amount={INTENTS[0][2]:,}) → settled")

# ── Test 1: list returns only pending ─────────────────────────────────────────
items = list_intents(conn, mt, seq); seq += 1
print(f"\n[Test 1] LIST_INTENTS → count={len(items)} (expect 2 pending)")
assert len(items) == 2, f"FAIL: expected 2, got {len(items)}: {items}"
returned_reqs = {x["request_id"] for x in items}
assert INTENTS[0][0] not in returned_reqs, "FAIL: settled intent should not appear"
assert INTENTS[1][0] in returned_reqs and INTENTS[2][0] in returned_reqs
print(f"    ✓ settled intent excluded")
summary = [f"req={x['request_id']} amt={x['amount']:,}" for x in items]
print(f"    ✓ pending: {summary}")

# ── Test 2: newest first ──────────────────────────────────────────────────────
print(f"\n[Test 2] Order newest-first (expect req {INTENTS[2][0]} before {INTENTS[1][0]})")
assert items[0]["request_id"] == INTENTS[2][0], \
    f"FAIL: expected newest first {INTENTS[2][0]}, got {items[0]['request_id']}"
print(f"    ✓ newest first confirmed")

# ── Test 3: create 9 more → total 11 pending → list returns max 10 ────────────
for i in range(4, 13):
    create_intent(conn, mt, seq, RUN_ID*100+i, RUN_ID*10+i, i*1000); seq += 1
items2 = list_intents(conn, mt, seq); seq += 1
print(f"\n[Test 3] 11 pending intents → LIST returns {len(items2)} (expect max 10)")
assert len(items2) == 10, f"FAIL: expected 10, got {len(items2)}"
print(f"    ✓ capped at 10")

conn.close()
print("\n=== ALL PASSED ===")
