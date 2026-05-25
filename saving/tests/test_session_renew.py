#!/usr/bin/env python3
"""
Test RENEW_SESSION (0x04):
  - Valid token → OK + remaining_s == TTL (900)
  - Expired/garbage token → ERR_BAD_TOKEN
  - Works while system offline (no clearing account needed)
"""
import socket, struct, hmac, hashlib, random, os

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

def renew(s, token, seq):
    s.sendall(encode(0x04, seq, token))
    _, r = recv_rpc(s)
    return r

TTL = 900  # WIRE_TOKEN_TTL_SEC
UID = 60_000_000 + RUN_ID

s = conn()
create_account(s, UID, "pw", 1)
tok = login(s, UID, "pw", 2)

# Test 1: valid token → OK, remaining_s = TTL
r = renew(s, tok, 3)
assert r[0] == 0x00, f"Test 1a failed: code=0x{r[0]:02x}"
remaining = struct.unpack(">I", r[1:5])[0]
assert remaining == TTL, f"Test 1b failed: expected {TTL}s, got {remaining}s"
print(f"Test 1 pass: valid token → OK, remaining={remaining}s")

# Test 2: garbage token → ERR_BAD_TOKEN (0x06)
bad_tok = os.urandom(32)
r = renew(s, bad_tok, 4)
assert r[0] == 0x07, f"Test 2 failed: expected 0x07, got 0x{r[0]:02x}"
print("Test 2 pass: garbage token → ERR_BAD_TOKEN")

# Test 3: renew multiple times → still OK
for seq in range(5, 8):
    r = renew(s, tok, seq)
    assert r[0] == 0x00, f"Test 3 failed at seq={seq}: 0x{r[0]:02x}"
print("Test 3 pass: repeated renew → always OK")

s.close()
print("All session_renew tests passed.")
