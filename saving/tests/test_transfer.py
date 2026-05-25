#!/usr/bin/env python3
"""
Test TRANSFER (0x11):
  - Normal transfer between two users
  - EVT_TRANSFER_IN push to recipient
  - Idempotency: same seq → no double debit
  - Low balance → ERR_LOW_BALANCE
  - Unknown recipient → ERR_NOT_FOUND
  - Bad token → ERR_BAD_TOKEN
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

def recv_rpc(sock, collect_push=False):
    pushes = []
    while True:
        hdr = b""
        while len(hdr) < 9: hdr += sock.recv(9-len(hdr))
        total, ftype, _ = struct.unpack(">IBI", hdr)
        rest = b""
        while len(rest) < total-9: rest += sock.recv(total-9-len(rest))
        if ftype >= 0xC0:
            pushes.append((ftype, rest[:-32])); continue
        return ftype, rest[:-32], pushes

def create_account(s, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x10, seq, body)); _, r, _ = recv_rpc(s)
    assert r[0] in (0, 3)

def login(s, uid, pw, seq):
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x02, seq, body)); _, r, _ = recv_rpc(s)
    assert r[0] == 0; return r[1:33]

def get_balance(s, tok, seq):
    s.sendall(encode(0x12, seq, tok)); _, r, _ = recv_rpc(s)
    assert r[0] == 0
    return struct.unpack(">Q", r[1:9])[0]

def cash_in(s, tok, seq, to_uid, amount, tid):
    body = tok + struct.pack(">IQ", to_uid, amount) + tid.encode()
    s.sendall(encode(0x24, seq, body)); _, r, _ = recv_rpc(s)
    assert r[0] == 0

def transfer(s, tok, seq, to_uid, amount):
    body = tok + struct.pack(">IQ", to_uid, amount)
    s.sendall(encode(0x11, seq, body))
    _, r, pushes = recv_rpc(s)
    after_bal = struct.unpack(">Q", r[1:9])[0] if r[0] == 0 and len(r) >= 9 else None
    return r[0], after_bal, pushes

def drain_push(s, timeout=0.3):
    """Collect push frames buffered on socket."""
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

# ── Setup ─────────────────────────────────────────────────────────────────────
UID_A = 16_777_216 + RUN_ID
UID_B = 16_777_216 + RUN_ID + 1
print(f"=== TRANSFER test (RUN_ID={RUN_ID}) ===")
print(f"    sender={UID_A}  recipient={UID_B}")

# Both users go online first (so push can be delivered)
ca = socket.create_connection((HOST, PORT)); ca.settimeout(5)
cb = socket.create_connection((HOST, PORT)); cb.settimeout(5)
create_account(ca, UID_A, f"a{RUN_ID}", seq=1)
create_account(cb, UID_B, f"b{RUN_ID}", seq=1)
ta = login(ca, UID_A, f"a{RUN_ID}", seq=2)
tb = login(cb, UID_B, f"b{RUN_ID}", seq=2)

# Fund sender via bank
bc = socket.create_connection((HOST, PORT)); bc.settimeout(5)
bt = login(bc, 1, "bank123", seq=1)
cash_in(bc, bt, seq=2, to_uid=UID_A, amount=500_000, tid=f"TF-FUND-{RUN_ID}")
bc.close()

bal_a = get_balance(ca, ta, seq=3)
bal_b = get_balance(cb, tb, seq=3)
print(f"    A balance: {bal_a:,}   B balance: {bal_b:,}")

# ── Test 1: normal transfer A → B ────────────────────────────────────────────
AMOUNT = 100_000
rc, after_a, _ = transfer(ca, ta, seq=4, to_uid=UID_B, amount=AMOUNT)
print(f"\n[Test 1] Transfer {AMOUNT:,} A→B → code=0x{rc:02X}  after_bal_A={after_a:,}")
assert rc == 0, f"FAIL: 0x{rc:02X}"
assert after_a == bal_a - AMOUNT, f"FAIL: expected {bal_a-AMOUNT}, got {after_a}"

# Drain push BEFORE get_balance — push may arrive before balance response
time.sleep(0.2)
pushes_b = drain_push(cb)

bal_b2 = get_balance(cb, tb, seq=4)
assert bal_b2 == bal_b + AMOUNT, f"FAIL: B balance wrong {bal_b2}"
print(f"    ✓ A debited  B credited  B_balance={bal_b2:,}")

# ── Test 2: EVT_TRANSFER_IN received by B ────────────────────────────────────
evt = [p for p in pushes_b if p[0] == 0xC0]
print(f"\n[Test 2] EVT_TRANSFER_IN at B → found={len(evt)}")
assert len(evt) >= 1, f"FAIL: no push received"
body = evt[0][1]
from_id    = struct.unpack(">I", body[0:4])[0]
pushed_amt = struct.unpack(">Q", body[4:12])[0]
new_bal_b  = struct.unpack(">Q", body[12:20])[0]
print(f"    from={from_id}  amount={pushed_amt:,}  new_balance={new_bal_b:,}")
assert from_id == UID_A and pushed_amt == AMOUNT
print(f"    ✓ push correct")

# ── Test 3: idempotency — replay same seq ────────────────────────────────────
rc2, _, _ = transfer(ca, ta, seq=4, to_uid=UID_B, amount=AMOUNT)  # same seq
bal_a2 = get_balance(ca, ta, seq=5)
print(f"\n[Test 3] Replay seq=4 → code=0x{rc2:02X}  A_balance={bal_a2:,} (unchanged)")
assert rc2 == 0, f"FAIL: 0x{rc2:02X}"
assert bal_a2 == after_a, f"FAIL: balance changed on replay"
print(f"    ✓ idempotency: no double debit")

# ── Test 4: low balance ───────────────────────────────────────────────────────
rc3, _, _ = transfer(ca, ta, seq=6, to_uid=UID_B, amount=999_999_999)
print(f"\n[Test 4] Transfer > balance → code=0x{rc3:02X} (expect 0x08 ERR_LOW_BALANCE)")
assert rc3 == 0x08, f"FAIL: 0x{rc3:02X}"
print(f"    ✓ ERR_LOW_BALANCE")

# ── Test 5: unknown recipient ────────────────────────────────────────────────
rc4, _, _ = transfer(ca, ta, seq=7, to_uid=9_999_888, amount=1000)
print(f"\n[Test 5] Unknown recipient → code=0x{rc4:02X} (expect 0x05 ERR_NOT_FOUND)")
assert rc4 == 0x05, f"FAIL: 0x{rc4:02X}"
print(f"    ✓ ERR_NOT_FOUND")

# ── Test 6: bad token ────────────────────────────────────────────────────────
rc5, _, _ = transfer(ca, bytes(32), seq=8, to_uid=UID_B, amount=1000)
print(f"\n[Test 6] Bad token → code=0x{rc5:02X} (expect 0x07 ERR_BAD_TOKEN)")
assert rc5 == 0x07, f"FAIL: 0x{rc5:02X}"
print(f"    ✓ ERR_BAD_TOKEN")

ca.close(); cb.close()
print("\n=== ALL PASSED ===")
