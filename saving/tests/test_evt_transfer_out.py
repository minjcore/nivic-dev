"""
test_evt_transfer_out.py — verify sender receives EVT_TRANSFER_OUT (0xC8)
after TRANSFER and CONFIRM_INTENT.

Push body: [to_id 4B][amount 8B][balance 8B]

Uses two connections for sender: one for RPC (send + receive ACK),
one dedicated to collecting push events (registry_push goes to all sessions).
"""
import socket, struct, hmac, hashlib, random, threading

import os
HOST   = os.getenv("WIRE_HOST", "127.0.0.1")
PORT   = int(os.getenv("WIRE_PORT", "7474"))
SECRET = b"saving_wire_secret_changeme"
RUN_ID = random.randint(100_000, 999_999)

EVT_TRANSFER_OUT = 0xC8

def sign(p):   return hmac.new(SECRET, p, hashlib.sha256).digest()
def encode(t, seq, body=b""):
    total = 4+1+4+len(body)+32
    hdr   = struct.pack(">IBI", total, t, seq)
    return hdr + body + sign(hdr + body)

def recv_frame(sock):
    hdr = b""
    while len(hdr) < 9: hdr += sock.recv(9 - len(hdr))
    total, ftype, _ = struct.unpack(">IBI", hdr)
    rest = b""
    rest_len = total - 9
    while len(rest) < rest_len: rest += sock.recv(rest_len - len(rest))
    return ftype, rest[:-32]

def recv_rpc(sock):
    while True:
        ftype, body = recv_frame(sock)
        if ftype < 0xC0:
            return ftype, body

def new_conn():
    s = socket.socket(); s.connect((HOST, PORT)); s.settimeout(4); return s

def do_login(uid, pw, seq=1):
    """Open a new socket, login, return (socket, token)."""
    s = new_conn()
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x02, seq, body)); _, r = recv_rpc(s)
    assert r[0] == 0, f"login failed: 0x{r[0]:02X}"
    return s, r[1:33]

def create_account(uid, pw):
    s = new_conn()
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x10, 1, body)); recv_rpc(s); s.close()

def collect_pushes(sock, target_type, result_list, ready_event, timeout=3):
    """Drain push events from sock until target arrives or timeout."""
    sock.settimeout(timeout)
    try:
        while True:
            ftype, body = recv_frame(sock)
            if ftype >= 0xC0:
                result_list.append((ftype, body))
                if ftype == target_type:
                    ready_event.set()
    except (socket.timeout, OSError):
        ready_event.set()

def assert_evt_transfer_out(evts, label, expected_to, expected_amount, expected_balance):
    hits = [(ft, b) for ft, b in evts if ft == EVT_TRANSFER_OUT]
    assert hits, f"{label}: no EVT_TRANSFER_OUT; all pushes: {[(hex(ft),b.hex()) for ft,b in evts]}"
    _, pb = hits[0]
    assert len(pb) >= 20, f"{label}: body too short: {len(pb)}"
    to_id  = struct.unpack(">I", pb[0:4])[0]
    amount = struct.unpack(">Q", pb[4:12])[0]
    bal    = struct.unpack(">Q", pb[12:20])[0]
    assert to_id  == expected_to,       f"{label}: to_id {to_id} != {expected_to}"
    assert amount == expected_amount,   f"{label}: amount {amount} != {expected_amount}"
    assert bal    == expected_balance,  f"{label}: balance {bal} != {expected_balance}"
    print(f"    ✓ to={to_id}  amount={amount}  balance={bal}")

# ── UIDs ───────────────────────────────────────────────────────────────────────
BANK_UID = 1
SENDER   = 16_777_216 + RUN_ID
RECEIVER = 17_000_000 + RUN_ID
MERCH    = 18_000_000 + RUN_ID
AMOUNT   = 30_000
PW_S     = f"spw{RUN_ID}"
PW_R     = f"rpw{RUN_ID}"
PW_M     = f"mpw{RUN_ID}"

print(f"=== EVT_TRANSFER_OUT test (RUN_ID={RUN_ID}) ===")

# ── Setup ──────────────────────────────────────────────────────────────────────
create_account(SENDER,   PW_S)
create_account(RECEIVER, PW_R)
create_account(MERCH,    PW_M)

# Register MERCH as merchant
s_merch, tok_merch = do_login(MERCH, PW_M)
s_merch.sendall(encode(0x23, 2, tok_merch + f"Shop{RUN_ID}".encode()))
_, r = recv_rpc(s_merch); assert r[0] == 0; s_merch.close()

# Fund sender (300_000)
s_bank, tok_bank = do_login(BANK_UID, "bank123")
body = tok_bank + struct.pack(">IQ", SENDER, 300_000) + f"fund-{RUN_ID}".encode()
s_bank.sendall(encode(0x24, 2, body)); _, r = recv_rpc(s_bank); assert r[0] == 0
s_bank.close()

# ── Test 1: TRANSFER → EVT_TRANSFER_OUT ───────────────────────────────────────
print(f"\n[Test 1] TRANSFER → EVT_TRANSFER_OUT")

# s_rpc: sends the TRANSFER, reads the ACK
# s_push: second session for same account — only collects push events
s_rpc,  tok_s  = do_login(SENDER, PW_S, seq=1)
s_push, _      = do_login(SENDER, PW_S, seq=1)

pushes1 = []; ready1 = threading.Event()
t1 = threading.Thread(target=collect_pushes,
                      args=(s_push, EVT_TRANSFER_OUT, pushes1, ready1), daemon=True)
t1.start()

body = tok_s + struct.pack(">IQQ", RECEIVER, AMOUNT, RUN_ID + 100)
s_rpc.sendall(encode(0x11, 2, body))
_, r = recv_rpc(s_rpc); assert r[0] == 0, f"TRANSFER failed: 0x{r[0]:02X}"
ack_after = struct.unpack(">Q", r[9:17])[0]   # extra: [txn_id 8B][after_balance 8B]

ready1.wait(timeout=4); t1.join(timeout=1)
s_rpc.close(); s_push.close()
assert_evt_transfer_out(pushes1, "TRANSFER", RECEIVER, AMOUNT, ack_after)

# ── Test 2: CONFIRM_INTENT → EVT_TRANSFER_OUT ─────────────────────────────────
print(f"\n[Test 2] CONFIRM_INTENT → EVT_TRANSFER_OUT")

# Create intent as merchant
s_merch2, tok_merch2 = do_login(MERCH, PW_M)
REQ_ID = RUN_ID + 999; ORD_ID = RUN_ID + 998
body = tok_merch2 + struct.pack(">QQQ", REQ_ID, ORD_ID, AMOUNT)
s_merch2.sendall(encode(0x20, 2, body))
_, r = recv_rpc(s_merch2); assert r[0] == 0, f"create_intent failed: 0x{r[0]:02X}"
s_merch2.close()

# Two sender connections again
s_rpc2,  tok_s2  = do_login(SENDER, PW_S, seq=1)
s_push2, _       = do_login(SENDER, PW_S, seq=1)

pushes2 = []; ready2 = threading.Event()
t2 = threading.Thread(target=collect_pushes,
                      args=(s_push2, EVT_TRANSFER_OUT, pushes2, ready2), daemon=True)
t2.start()

body = tok_s2 + struct.pack(">IQ", MERCH, REQ_ID)
s_rpc2.sendall(encode(0x29, 2, body))
_, r = recv_rpc(s_rpc2); assert r[0] == 0, f"confirm_intent failed: 0x{r[0]:02X}"
ci_after = struct.unpack(">Q", r[1:9])[0]   # extra: [after_balance 8B]

ready2.wait(timeout=4); t2.join(timeout=1)
s_rpc2.close(); s_push2.close()
assert_evt_transfer_out(pushes2, "CONFIRM_INTENT", MERCH, AMOUNT, ci_after)

print("\nAll EVT_TRANSFER_OUT tests passed.")
