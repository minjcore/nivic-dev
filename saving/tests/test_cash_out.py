#!/usr/bin/env python3
"""
Test CASH_OUT (0x26):
  body: [bank_token 32B][from_uid 4B][amount 8B][cashout_id N bytes]
  Bank (uid 1-999) debits user → credits bank.
  - Normal cash out + EVT_CASH_OUT push to user
  - Idempotency: same cashout_id → no double debit
  - Low balance    → ERR_LOW_BALANCE  (0x08)
  - Non-bank caller → ERR_NOT_MERCHANT (0x0E)
  - Unknown user   → ERR_NOT_FOUND    (0x05)
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

def get_balance(s, tok, seq):
    s.sendall(encode(0x12, seq, tok)); _, r = recv_rpc(s)
    assert r[0] == 0; return struct.unpack(">Q", r[1:9])[0]

def cash_in(s, tok, seq, to_uid, amount, tid):
    body = tok + struct.pack(">IQ", to_uid, amount) + tid.encode()
    s.sendall(encode(0x24, seq, body)); _, r = recv_rpc(s); assert r[0] == 0

def cash_out(s, tok, seq, from_uid, amount, cashout_id):
    body = tok + struct.pack(">IQ", from_uid, amount) + cashout_id.encode()
    s.sendall(encode(0x26, seq, body)); _, r = recv_rpc(s)
    return r[0]

# ── Setup ─────────────────────────────────────────────────────────────────────
USER = 16_777_216 + RUN_ID
AMT  = 120_000

print(f"=== CASH_OUT test (RUN_ID={RUN_ID}) ===")
print(f"    user={USER}  bank=1")

# User goes online first (to receive push)
uc = socket.create_connection((HOST, PORT)); uc.settimeout(5)
create_account(uc, USER, f"u{RUN_ID}", seq=1)
ut = login(uc, USER, f"u{RUN_ID}", seq=2)

# Bank
bc = socket.create_connection((HOST, PORT)); bc.settimeout(5)
bt = login(bc, 1, "bank123", seq=1)

# Fund user
cash_in(bc, bt, seq=2, to_uid=USER, amount=500_000, tid=f"CO-FUND-{RUN_ID}")
print(f"    funded user 500,000 ✓")

bal_u_before = get_balance(uc, ut, seq=3)
bal_b_before = get_balance(bc, bt, seq=3)
print(f"    balances: user={bal_u_before:,}  bank={bal_b_before:,}")

# ── Test 1: normal cash_out ───────────────────────────────────────────────────
CASHOUT_ID = f"CO-{RUN_ID}-001"
rc = cash_out(bc, bt, seq=4, from_uid=USER, amount=AMT, cashout_id=CASHOUT_ID)
print(f"\n[Test 1] CASH_OUT {AMT:,} → code=0x{rc:02X} (expect 0x00)")
assert rc == 0, f"FAIL: 0x{rc:02X}"

time.sleep(0.2)
pushes_u = drain_push(uc)

bal_u = get_balance(uc, ut, seq=4)
bal_b = get_balance(bc, bt, seq=5)
print(f"    user: {bal_u_before:,} → {bal_u:,}  (diff={bal_u-bal_u_before:,})")
print(f"    bank: {bal_b_before:,} → {bal_b:,}  (diff={bal_b-bal_b_before:,})")
assert bal_u == bal_u_before - AMT, f"FAIL user balance"
assert bal_b == bal_b_before + AMT, f"FAIL bank balance"
print(f"    ✓ user debited  bank credited")

# ── Test 2: EVT_CASH_OUT push to user ────────────────────────────────────────
evt = [p for p in pushes_u if p[0] == 0xC5]
print(f"\n[Test 2] EVT_CASH_OUT push → found={len(evt)}")
assert len(evt) >= 1, f"FAIL: no EVT_CASH_OUT push"
body = evt[0][1]
bank_r  = struct.unpack(">I", body[0:4])[0]
amt_r   = struct.unpack(">Q", body[4:12])[0]
bal_r   = struct.unpack(">Q", body[12:20])[0]
print(f"    bank_mid={bank_r}  amount={amt_r:,}  new_balance={bal_r:,}")
assert bank_r == 1 and amt_r == AMT and bal_r == bal_u
print(f"    ✓ EVT_CASH_OUT correct")

# ── Test 3: idempotency — same cashout_id → no double debit ──────────────────
rc2 = cash_out(bc, bt, seq=6, from_uid=USER, amount=AMT, cashout_id=CASHOUT_ID)
bal_u2 = get_balance(uc, ut, seq=5)
print(f"\n[Test 3] Replay same cashout_id → code=0x{rc2:02X}  user_balance={bal_u2:,} (unchanged)")
assert rc2 == 0, f"FAIL: 0x{rc2:02X}"
assert bal_u2 == bal_u, f"FAIL: balance changed on replay"
print(f"    ✓ idempotency: no double debit")

# ── Test 4: low balance ───────────────────────────────────────────────────────
rc3 = cash_out(bc, bt, seq=7, from_uid=USER, amount=999_999_999,
               cashout_id=f"CO-{RUN_ID}-002")
print(f"\n[Test 4] Cash out > balance → code=0x{rc3:02X} (expect 0x08)")
assert rc3 == 0x08, f"FAIL: 0x{rc3:02X}"
print(f"    ✓ ERR_LOW_BALANCE")

# ── Test 5: non-bank caller → ERR_NOT_MERCHANT ───────────────────────────────
rc4 = cash_out(uc, ut, seq=6, from_uid=USER, amount=1000,
               cashout_id=f"CO-{RUN_ID}-003")
print(f"\n[Test 5] Non-bank caller → code=0x{rc4:02X} (expect 0x0E)")
assert rc4 == 0x0E, f"FAIL: 0x{rc4:02X}"
print(f"    ✓ ERR_NOT_MERCHANT")

# ── Test 6: unknown user → ERR_NOT_FOUND ─────────────────────────────────────
rc5 = cash_out(bc, bt, seq=8, from_uid=9_999_888, amount=1000,
               cashout_id=f"CO-{RUN_ID}-004")
print(f"\n[Test 6] Unknown user → code=0x{rc5:02X} (expect 0x05)")
assert rc5 == 0x05, f"FAIL: 0x{rc5:02X}"
print(f"    ✓ ERR_NOT_FOUND")

uc.close(); bc.close()
print("\n=== ALL PASSED ===")
