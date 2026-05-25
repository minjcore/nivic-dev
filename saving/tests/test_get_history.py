#!/usr/bin/env python3
"""
Test GET_HISTORY (0x16):
  ACK extra: [count 1B][dir 1B | counterpart 4B | amount 8B | after_balance 8B] x N
  direction: 0=transfer_sent, 1=transfer_recv, 2=payment_sent,
             3=payment_recv,  4=cash_in,       5=cash_out
  Newest first, max 20.
"""
import socket, struct, hmac, hashlib, random, time

HOST   = "127.0.0.1"
PORT   = 7474
SECRET = b"saving_wire_secret_changeme"
RUN_ID = random.randint(100_000, 999_999)

DIR = {0:"transfer_sent", 1:"transfer_recv", 2:"payment_sent",
       3:"payment_recv",  4:"cash_in",       5:"cash_out"}

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
    s.sendall(encode(0x24, seq, body)); _, r = recv_rpc(s)
    assert r[0] == 0

def transfer(s, tok, seq, to_uid, amount):
    body = tok + struct.pack(">IQ", to_uid, amount)
    s.sendall(encode(0x11, seq, body)); _, r = recv_rpc(s)
    assert r[0] == 0

def get_history(s, tok, seq):
    s.sendall(encode(0x16, seq, tok)); _, r = recv_rpc(s)
    assert r[0] == 0, f"GET_HISTORY err 0x{r[0]:02X}"
    count = r[1]
    entries = []
    for i in range(count):
        off = 2 + i * 21
        direction    = r[off]
        counterpart  = struct.unpack(">I", r[off+1:off+5])[0]
        amount       = struct.unpack(">Q", r[off+5:off+13])[0]
        after_bal    = struct.unpack(">Q", r[off+13:off+21])[0]
        entries.append({"dir": direction, "dir_name": DIR.get(direction,"?"),
                        "counterpart": counterpart, "amount": amount,
                        "after_balance": after_bal})
    return entries

# ── Setup ─────────────────────────────────────────────────────────────────────
UID_A = 16_777_216 + RUN_ID
UID_B = 16_777_216 + RUN_ID + 1
BANK  = 1

print(f"=== GET_HISTORY test (RUN_ID={RUN_ID}) ===")
print(f"    A={UID_A}  B={UID_B}")

ca = socket.create_connection((HOST, PORT)); ca.settimeout(5)
cb = socket.create_connection((HOST, PORT)); cb.settimeout(5)
create_account(ca, UID_A, f"a{RUN_ID}", seq=1)
create_account(cb, UID_B, f"b{RUN_ID}", seq=1)
ta = login(ca, UID_A, f"a{RUN_ID}", seq=2)
tb = login(cb, UID_B, f"b{RUN_ID}", seq=2)

bc = socket.create_connection((HOST, PORT)); bc.settimeout(5)
bt = login(bc, BANK, "bank123", seq=1)

# Fund A: cash_in 500,000
cash_in(bc, bt, seq=2, to_uid=UID_A, amount=500_000, tid=f"GH-FUND-A-{RUN_ID}")
# Fund B: cash_in 100,000
cash_in(bc, bt, seq=3, to_uid=UID_B, amount=100_000, tid=f"GH-FUND-B-{RUN_ID}")
bc.close()
print("    funded: A=500,000  B=100,000")

# A transfers 150,000 to B
transfer(ca, ta, seq=3, to_uid=UID_B, amount=150_000)
print("    A→B transfer 150,000 done")

# A transfers 50,000 to B (second transfer)
transfer(ca, ta, seq=4, to_uid=UID_B, amount=50_000)
print("    A→B transfer 50,000 done")

# ── Test 1: A's history — newest first ───────────────────────────────────────
hist_a = get_history(ca, ta, seq=5)
print(f"\n[Test 1] A history count={len(hist_a)} (expect 3: 2 sent + 1 cash_in)")
for e in hist_a:
    print(f"    dir={e['dir']} ({e['dir_name']})  counterpart={e['counterpart']}"
          f"  amount={e['amount']:,}  after_bal={e['after_balance']:,}")

assert len(hist_a) == 3, f"FAIL count={len(hist_a)}"
# Newest first: transfer_sent(50k), transfer_sent(150k), cash_in(500k)
assert hist_a[0]["dir"] == 0 and hist_a[0]["amount"] == 50_000,  f"FAIL[0] {hist_a[0]}"
assert hist_a[1]["dir"] == 0 and hist_a[1]["amount"] == 150_000, f"FAIL[1] {hist_a[1]}"
assert hist_a[2]["dir"] == 4 and hist_a[2]["amount"] == 500_000, f"FAIL[2] {hist_a[2]}"
print(f"    ✓ newest first  ✓ directions correct")

# ── Test 2: running after_balance correct for A ───────────────────────────────
# Oldest→newest: cash_in(+500k)=500k, transfer(-150k)=350k, transfer(-50k)=300k
# history is newest-first so after_balance values should be 300k, 350k, 500k
assert hist_a[0]["after_balance"] == 300_000, f"FAIL after_bal[0] {hist_a[0]['after_balance']}"
assert hist_a[1]["after_balance"] == 350_000, f"FAIL after_bal[1] {hist_a[1]['after_balance']}"
assert hist_a[2]["after_balance"] == 500_000, f"FAIL after_bal[2] {hist_a[2]['after_balance']}"
print(f"\n[Test 2] A after_balance: 500k→350k→300k ✓")

# ── Test 3: B's history ───────────────────────────────────────────────────────
hist_b = get_history(cb, tb, seq=5)
print(f"\n[Test 3] B history count={len(hist_b)} (expect 3: 2 recv + 1 cash_in)")
for e in hist_b:
    print(f"    dir={e['dir']} ({e['dir_name']})  counterpart={e['counterpart']}"
          f"  amount={e['amount']:,}  after_bal={e['after_balance']:,}")

assert len(hist_b) == 3, f"FAIL count={len(hist_b)}"
assert hist_b[0]["dir"] == 1 and hist_b[0]["amount"] == 50_000,  f"FAIL[0] {hist_b[0]}"
assert hist_b[1]["dir"] == 1 and hist_b[1]["amount"] == 150_000, f"FAIL[1] {hist_b[1]}"
assert hist_b[2]["dir"] == 4 and hist_b[2]["amount"] == 100_000, f"FAIL[2] {hist_b[2]}"
# B final balance: 100k + 150k + 50k = 300k
assert hist_b[0]["after_balance"] == 300_000, f"FAIL after_bal {hist_b[0]['after_balance']}"
print(f"    ✓ directions correct  ✓ after_balance correct")

# ── Test 4: counterpart correctly set ────────────────────────────────────────
print(f"\n[Test 4] Counterpart IDs correct")
assert hist_a[0]["counterpart"] == UID_B, f"FAIL counterpart {hist_a[0]['counterpart']}"
assert hist_b[0]["counterpart"] == UID_A, f"FAIL counterpart {hist_b[0]['counterpart']}"
print(f"    ✓ A sees counterpart=B  B sees counterpart=A")

# ── Test 5: bad token → ERR_BAD_TOKEN ────────────────────────────────────────
ca.sendall(encode(0x16, seq=6, body=bytes(32)))
_, r = recv_rpc(ca)
print(f"\n[Test 5] Bad token → code=0x{r[0]:02X} (expect 0x07)")
assert r[0] == 0x07, f"FAIL 0x{r[0]:02X}"
print(f"    ✓ ERR_BAD_TOKEN")

ca.close(); cb.close()
print("\n=== ALL PASSED ===")
