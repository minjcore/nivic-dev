"""
test_evt_intent_paid.py — verify merchant receives EVT_INTENT_PAID (0xC4)
when customer confirms an intent via CONFIRM_INTENT (0x29).

Push body: [request_id 8B][customer_id 4B][amount 8B]
"""
import socket, struct, hmac, hashlib, random, threading, time

HOST   = "127.0.0.1"
PORT   = 7474
SECRET = b"saving_wire_secret_changeme"
RUN_ID = random.randint(100_000, 999_999)

EVT_INTENT_PAID = 0xC4

def sign(p):   return hmac.new(SECRET, p, hashlib.sha256).digest()
def encode(t, seq, body=b""):
    total = 4+1+4+len(body)+32
    hdr   = struct.pack(">IBI", total, t, seq)
    return hdr + body + sign(hdr + body)

def recv_frame(sock):
    """Read one complete frame; returns (ftype, body_no_hmac)."""
    hdr = b""
    while len(hdr) < 9: hdr += sock.recv(9 - len(hdr))
    total, ftype, _ = struct.unpack(">IBI", hdr)
    rest = b""
    rest_len = total - 9
    while len(rest) < rest_len: rest += sock.recv(rest_len - len(rest))
    return ftype, rest[:-32]

def recv_rpc(sock):
    """Read frames, skip push events, return first RPC ACK."""
    while True:
        ftype, body = recv_frame(sock)
        if ftype < 0xC0:
            return ftype, body

def conn():
    s = socket.socket(); s.connect((HOST, PORT)); s.settimeout(4); return s

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
    s.sendall(encode(0x24, seq, body)); _, r = recv_rpc(s); assert r[0] == 0

def register_merchant(s, tok, seq, name):
    s.sendall(encode(0x23, seq, tok + name.encode()))
    _, r = recv_rpc(s); assert r[0] == 0

# ── UIDs ──────────────────────────────────────────────────────────────────────
BANK_UID  = 1
MID       = 16_777_216 + RUN_ID       # merchant (VIP block)
CUST_UID  = 20_000_000 + RUN_ID       # regular customer

AMOUNT    = 50_000
REQUEST_ID = RUN_ID + 1
ORDER_ID   = RUN_ID + 2

# ── Setup ─────────────────────────────────────────────────────────────────────
s_bank = conn()
tok_bank = login(s_bank, BANK_UID, "bank123", 1)  # UID=1 pre-exists

s_merch = conn()
create_account(s_merch, MID, "mpw", 1)
tok_merch = login(s_merch, MID, "mpw", 2)
register_merchant(s_merch, tok_merch, 3, f"Shop{RUN_ID}")

s_cust = conn()
create_account(s_cust, CUST_UID, "cpw", 1)
tok_cust = login(s_cust, CUST_UID, "cpw", 2)
cash_in(s_bank, tok_bank, 3, CUST_UID, 200_000, f"top-up-{RUN_ID}")

# ── Create intent ─────────────────────────────────────────────────────────────
body = tok_merch + struct.pack(">QQQ", REQUEST_ID, ORDER_ID, AMOUNT)
s_merch.sendall(encode(0x20, 4, body))
_, r = recv_rpc(s_merch); assert r[0] == 0, f"create_intent failed: 0x{r[0]:02X}"

# ── Collect merchant push events in background ────────────────────────────────
merchant_pushes = []
push_ready = threading.Event()

def collect_merchant_pushes():
    """Listen on s_merch for push frames for up to 2s."""
    s_merch.settimeout(2)
    try:
        while True:
            ftype, body = recv_frame(s_merch)
            if ftype >= 0xC0:
                merchant_pushes.append((ftype, body))
                if ftype == EVT_INTENT_PAID:
                    push_ready.set()
    except (socket.timeout, OSError):
        push_ready.set()  # unblock if no push arrives

t = threading.Thread(target=collect_merchant_pushes, daemon=True)
t.start()

# ── Customer confirms intent ──────────────────────────────────────────────────
body = tok_cust + struct.pack(">IQ", MID, REQUEST_ID)
s_cust.sendall(encode(0x29, 3, body))
_, r = recv_rpc(s_cust)
assert r[0] == 0, f"confirm_intent failed: 0x{r[0]:02X}"
after_cust = struct.unpack(">Q", r[1:9])[0]
assert after_cust == 150_000, f"customer balance wrong: {after_cust}"

# ── Verify EVT_INTENT_PAID was received ───────────────────────────────────────
push_ready.wait(timeout=2)

paid_events = [(ft, b) for ft, b in merchant_pushes if ft == EVT_INTENT_PAID]
assert len(paid_events) >= 1, \
    f"No EVT_INTENT_PAID received; got pushes: {[(hex(ft), b.hex()) for ft,b in merchant_pushes]}"

_, pb = paid_events[0]
assert len(pb) >= 20, f"EVT_INTENT_PAID body too short: {len(pb)}"
recv_req_id  = struct.unpack(">Q", pb[0:8])[0]
recv_cust_id = struct.unpack(">I", pb[8:12])[0]
recv_amount  = struct.unpack(">Q", pb[12:20])[0]

assert recv_req_id  == REQUEST_ID, f"request_id mismatch: {recv_req_id}"
assert recv_cust_id == CUST_UID,   f"customer_id mismatch: {recv_cust_id}"
assert recv_amount  == AMOUNT,     f"amount mismatch: {recv_amount}"

s_bank.close(); s_merch.close(); s_cust.close()
print("All EVT_INTENT_PAID tests passed.")
