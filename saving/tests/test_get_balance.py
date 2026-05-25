#!/usr/bin/env python3
"""
Test GET_BALANCE (0x12):
  ACK extra: [balance 8B][pending 8B][available_balance 8B][version 8B]
  - balance        = running SUM of transfers
  - pending        = sum of open (status=0) payment intents
  - available      = balance - pending
  - version        = number of transfer rows (lamport clock)
  - Bad token      → ERR_BAD_TOKEN
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

def get_balance_detail(s, tok, seq):
    s.sendall(encode(0x12, seq, tok)); _, r = recv_rpc(s)
    assert r[0] == 0, f"GET_BALANCE err 0x{r[0]:02X}"
    balance   = struct.unpack(">Q", r[1:9])[0]
    pending   = struct.unpack(">Q", r[9:17])[0]
    available = struct.unpack(">Q", r[17:25])[0]
    version   = struct.unpack(">Q", r[25:33])[0]
    return {"balance": balance, "pending": pending,
            "available": available, "version": version}

def cash_in(s, tok, seq, to_uid, amount, tid):
    body = tok + struct.pack(">IQ", to_uid, amount) + tid.encode()
    s.sendall(encode(0x24, seq, body)); _, r = recv_rpc(s)
    assert r[0] == 0

def register_merchant(s, tok, seq, name):
    s.sendall(encode(0x23, seq, tok + name.encode()))
    _, r = recv_rpc(s); assert r[0] == 0

def create_intent(s, tok, seq, req_id, ord_id, amount):
    body = tok + struct.pack(">QQQ", req_id, ord_id, amount)
    s.sendall(encode(0x20, seq, body)); _, r = recv_rpc(s)
    assert r[0] == 0

# ── Setup ─────────────────────────────────────────────────────────────────────
MERCH = 16_777_216 + RUN_ID
print(f"=== GET_BALANCE test (RUN_ID={RUN_ID}) ===")

conn = socket.create_connection((HOST, PORT)); conn.settimeout(5)
create_account(conn, MERCH, f"m{RUN_ID}", seq=1)
mt = login(conn, MERCH, f"m{RUN_ID}", seq=2)
register_merchant(conn, mt, seq=3, name=f"Shop{RUN_ID}")

# Bank funds merchant
bc = socket.create_connection((HOST, PORT)); bc.settimeout(5)
bt = login(bc, 1, "bank123", seq=1)
cash_in(bc, bt, seq=2, to_uid=MERCH, amount=1_000_000, tid=f"GB-FUND-{RUN_ID}")
bc.close()

# ── Test 1: fresh balance after cash_in ──────────────────────────────────────
d = get_balance_detail(conn, mt, seq=4)
print(f"\n[Test 1] After cash_in 1,000,000")
print(f"    balance={d['balance']:,}  pending={d['pending']:,}  "
      f"available={d['available']:,}  version={d['version']}")
assert d["balance"]   == 1_000_000, f"FAIL balance {d['balance']}"
assert d["pending"]   == 0,         f"FAIL pending {d['pending']}"
assert d["available"] == 1_000_000, f"FAIL available {d['available']}"
assert d["version"]   >= 1,         f"FAIL version {d['version']}"
print(f"    ✓ balance=1,000,000  pending=0  available=1,000,000")

# ── Test 2: create intent → pending increases, available decreases ────────────
INTENT_AMT = 300_000
create_intent(conn, mt, seq=5,
              req_id=RUN_ID*100+1, ord_id=RUN_ID*10+1, amount=INTENT_AMT)

d2 = get_balance_detail(conn, mt, seq=6)
print(f"\n[Test 2] After create_intent {INTENT_AMT:,}")
print(f"    balance={d2['balance']:,}  pending={d2['pending']:,}  "
      f"available={d2['available']:,}  version={d2['version']}")
assert d2["balance"]   == 1_000_000,              f"FAIL balance {d2['balance']}"
assert d2["pending"]   == INTENT_AMT,             f"FAIL pending {d2['pending']}"
assert d2["available"] == 1_000_000 - INTENT_AMT, f"FAIL available {d2['available']}"
print(f"    ✓ pending={INTENT_AMT:,}  available={1_000_000-INTENT_AMT:,}")

# ── Test 3: second intent → pending accumulates ───────────────────────────────
INTENT_AMT2 = 200_000
create_intent(conn, mt, seq=7,
              req_id=RUN_ID*100+2, ord_id=RUN_ID*10+2, amount=INTENT_AMT2)

d3 = get_balance_detail(conn, mt, seq=8)
total_pending = INTENT_AMT + INTENT_AMT2
print(f"\n[Test 3] After second intent {INTENT_AMT2:,}")
print(f"    balance={d3['balance']:,}  pending={d3['pending']:,}  "
      f"available={d3['available']:,}")
assert d3["pending"]   == total_pending,             f"FAIL pending {d3['pending']}"
assert d3["available"] == 1_000_000 - total_pending, f"FAIL available {d3['available']}"
print(f"    ✓ pending={total_pending:,}  available={1_000_000-total_pending:,}")

# ── Test 4: version increments with each transfer ────────────────────────────
v_before = d["version"]
v_after  = d3["version"]
print(f"\n[Test 4] Version: before={v_before}  after={v_after} (cash_in adds 1 tx)")
assert v_after >= v_before, f"FAIL version did not increase"
print(f"    ✓ version monotonically increasing")

# ── Test 5: bad token → ERR_BAD_TOKEN ────────────────────────────────────────
conn.sendall(encode(0x12, seq=9, body=bytes(32)))
_, r = recv_rpc(conn)
print(f"\n[Test 5] Bad token → code=0x{r[0]:02X} (expect 0x07)")
assert r[0] == 0x07, f"FAIL: 0x{r[0]:02X}"
print(f"    ✓ ERR_BAD_TOKEN")

conn.close()
print("\n=== ALL PASSED ===")
