#!/usr/bin/env python3
"""Create Bank account (uid=1, pw=bank123) needed by all tests."""
import socket, struct, hmac, hashlib, argparse, os, sys

HOST   = os.getenv("WIRE_HOST", "127.0.0.1")

parser = argparse.ArgumentParser()
parser.add_argument("--port", type=int, default=int(os.getenv("WIRE_PORT", "7474")))
args = parser.parse_args()

SECRET = b"saving_wire_secret_changeme"

def sign(p): return hmac.new(SECRET, p, hashlib.sha256).digest()
def encode(t, seq, body):
    hdr = struct.pack(">IBI", 4+1+4+len(body)+32, t, seq)
    return hdr + body + sign(hdr + body)

s = socket.create_connection((HOST, args.port), timeout=5)
uid_be  = struct.pack(">I", 1)
pw_hash = hashlib.sha256(b"bank123").digest()
s.sendall(encode(0x10, 1, uid_be + pw_hash))

hdr = b""
while len(hdr) < 9: hdr += s.recv(9 - len(hdr))
total, _, _ = struct.unpack(">IBI", hdr)
rest = b""
while len(rest) < total - 9: rest += s.recv(total - 9 - len(rest))
code = rest[0]

if code == 0:
    print("seed_bank: Bank account created (uid=1)")
elif code == 3:
    print("seed_bank: Bank account already exists (uid=1)")
else:
    print(f"seed_bank: unexpected code 0x{code:02X}", file=sys.stderr)
    sys.exit(1)

s.close()
