#!/usr/bin/env python3
"""
Test CREATE_INTENT 2-step flow:
  Step 1 — idempotency gate   : (mid, request_id) → replay
  Step 2 — order dedup        : (mid, order_id)   → return existing intent
"""
import socket, struct, hmac, hashlib, time, random, os

import os
HOST   = os.getenv("WIRE_HOST", "127.0.0.1")
PORT   = int(os.getenv("WIRE_PORT", "7474"))
SECRET = b"saving_wire_secret_changeme"
RUN_ID = random.randint(100_000, 999_999)

# ── Wire helpers ──────────────────────────────────────────────────────────────

def sign(payload: bytes) -> bytes:
    return hmac.new(SECRET, payload, hashlib.sha256).digest()

def encode(ftype: int, seq: int, body: bytes) -> bytes:
    total = 4 + 1 + 4 + len(body) + 32
    hdr   = struct.pack(">IBI", total, ftype, seq)
    sig   = sign(hdr + body)
    return hdr + body + sig

def recv_response(sock) -> tuple:
    """Read frames, skip push events (type >= 0xC0), return first RPC response."""
    while True:
        hdr = b""
        while len(hdr) < 9:
            hdr += sock.recv(9 - len(hdr))
        total, ftype, seq = struct.unpack(">IBI", hdr)
        rest = b""
        while len(rest) < total - 9:
            rest += sock.recv(total - 9 - len(rest))
        if ftype >= 0xC0:
            print(f"    [push 0x{ftype:02X} skipped]")
            continue
        return ftype, seq, rest[:-32]   # strip sig

def login(sock, uid: int, pw: str, seq: int) -> bytes:
    pw_hash = hashlib.sha256(pw.encode()).digest()
    body    = struct.pack(">I", uid) + pw_hash
    sock.sendall(encode(0x02, seq, body))
    ftype, _, resp = recv_response(sock)
    assert ftype == 0x81 and resp[0] == 0, f"login failed code=0x{resp[0]:02X}"
    return resp[1:33]   # session token

def create_account(sock, uid: int, pw: str, seq: int):
    pw_hash = hashlib.sha256(pw.encode()).digest()
    body    = struct.pack(">I", uid) + pw_hash
    sock.sendall(encode(0x10, seq, body))
    ftype, _, resp = recv_response(sock)
    ok = resp[0] in (0x00, 0x03)   # OK or already exists
    assert ok, f"create_account failed 0x{resp[0]:02X}"

def register_merchant(sock, token: bytes, seq: int, name: str):
    body = token + name.encode()
    sock.sendall(encode(0x23, seq, body))
    _, _, resp = recv_response(sock)
    assert resp[0] == 0, f"register_merchant failed 0x{resp[0]:02X}"

def create_intent(sock, token: bytes, seq: int,
                  request_id: int, order_id: int, amount: int,
                  gateway_order_id: str = "") -> dict:
    body = token + struct.pack(">QQQ", request_id, order_id, amount)
    if gateway_order_id:
        body += gateway_order_id.encode()
    sock.sendall(encode(0x20, seq, body))
    _, _, resp = recv_response(sock)
    code = resp[0]
    if code != 0x00:
        return {"ok": False, "code": code}
    if len(resp) < 22:
        # idempotency replay — step 1 fired, no extra body
        return {"ok": True, "status": "replay_step1"}
    # extra: [status 1B][mid 4B][request_id 8B][amount 8B]
    status     = resp[1]
    mid_r      = struct.unpack(">I", resp[2:6])[0]
    req_id_r   = struct.unpack(">Q", resp[6:14])[0]
    amount_r   = struct.unpack(">Q", resp[14:22])[0]
    return {"ok": True, "status": status, "mid": mid_r,
            "request_id": req_id_r, "amount": amount_r}

# ── Test setup ────────────────────────────────────────────────────────────────

MERCH_UID = 16_777_216 + random.randint(1, 99_999)   # user range, register as merchant
MERCH_PW  = f"merch{RUN_ID}"

print(f"=== CREATE_INTENT 2-step flow (RUN_ID={RUN_ID}) ===")
print(f"    merchant uid={MERCH_UID}")

conn = socket.create_connection((HOST, PORT))
conn.settimeout(5)

# Create & login merchant
create_account(conn, MERCH_UID, MERCH_PW, seq=1)
token = login(conn, MERCH_UID, MERCH_PW, seq=2)
register_merchant(conn, token, seq=3, name=f"Shop-{RUN_ID}")
print(f"    merchant registered ✓")

# ── Test 1: brand new intent ──────────────────────────────────────────────────
REQ_ID_A  = RUN_ID * 100 + 1
ORDER_ID_A = RUN_ID * 10  + 1
AMOUNT_A   = 150_000

r1 = create_intent(conn, token, seq=4, request_id=REQ_ID_A,
                   order_id=ORDER_ID_A, amount=AMOUNT_A,
                   gateway_order_id=f"GW-{RUN_ID}-A")
print(f"\n[Test 1] New intent → status={r1['status']} (expect 1=created)")
assert r1["ok"] and r1["status"] == 1, f"FAIL: {r1}"
assert r1["amount"] == AMOUNT_A
print(f"    ✓ amount={r1['amount']:,}  mid={r1['mid']}")

# ── Test 2: exact replay — same (mid, request_id) → step 1 hits ──────────────
r2 = create_intent(conn, token, seq=5, request_id=REQ_ID_A,
                   order_id=ORDER_ID_A, amount=AMOUNT_A)
print(f"\n[Test 2] Same request_id replay → status={r2.get('status')} (expect replay_step1)")
assert r2["ok"] and r2["status"] == "replay_step1", f"FAIL: {r2}"
print(f"    ✓ idempotency gate fired (step 1)")

# ── Test 3: same order_id, different request_id → step 2 fires ───────────────
REQ_ID_B = RUN_ID * 100 + 2   # different requestId
r3 = create_intent(conn, token, seq=6, request_id=REQ_ID_B,
                   order_id=ORDER_ID_A, amount=999_999)   # different amount too
print(f"\n[Test 3] Same order_id, new request_id → status={r3.get('status')} (expect 0=existing)")
assert r3["ok"] and r3["status"] == 0, f"FAIL: {r3}"
assert r3["amount"] == AMOUNT_A, f"FAIL: expected original amount {AMOUNT_A}, got {r3['amount']}"
print(f"    ✓ order dedup fired (step 2) — returned original amount={r3['amount']:,}")

# ── Test 4: completely new order ──────────────────────────────────────────────
REQ_ID_C  = RUN_ID * 100 + 3
ORDER_ID_B = RUN_ID * 10 + 2
AMOUNT_B   = 75_000

r4 = create_intent(conn, token, seq=7, request_id=REQ_ID_C,
                   order_id=ORDER_ID_B, amount=AMOUNT_B)
print(f"\n[Test 4] New order → status={r4.get('status')} (expect 1=created)")
assert r4["ok"] and r4["status"] == 1, f"FAIL: {r4}"
assert r4["amount"] == AMOUNT_B
print(f"    ✓ new intent created — amount={r4['amount']:,}")

conn.close()
print("\n=== ALL TESTS PASSED ===")
