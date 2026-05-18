#!/usr/bin/env python3
"""Send money from 16777217 → 16777216 to trigger push notification."""
import socket, struct, hashlib, hmac

SECRET  = b"saving_wire_secret_changeme"
HOST    = "127.0.0.1"
PORT    = 7474

WIRE_LOGIN    = 0x02
WIRE_TRANSFER = 0x11
WIRE_LOGIN_ACK = 0x81
WIRE_ACK       = 0x82

def sign(data: bytes) -> bytes:
    return hmac.new(SECRET, data, hashlib.sha256).digest()

def frame(ftype: int, seq: int, body: bytes) -> bytes:
    total = 4 + 1 + 4 + len(body) + 32
    hdr   = struct.pack(">IBI", total, ftype, seq) + body
    return hdr + sign(hdr)

def recv_frame(s: socket.socket) -> tuple[int, int, bytes]:
    hdr = s.recv(4, socket.MSG_WAITALL)
    total = struct.unpack(">I", hdr)[0]
    rest  = s.recv(total - 4, socket.MSG_WAITALL)
    raw   = hdr + rest
    ftype = raw[4]
    seq   = struct.unpack(">I", raw[5:9])[0]
    body  = raw[9:total-32]
    return ftype, seq, body

s = socket.create_connection((HOST, PORT))

# LOGIN as 16777217, password "123"
pw_hash = hashlib.sha256(b"123").digest()
login_body = struct.pack(">I", 16777217) + pw_hash
s.sendall(frame(WIRE_LOGIN, 1, login_body))

ftype, seq, body = recv_frame(s)
assert ftype == WIRE_LOGIN_ACK and body[0] == 0, f"Login failed: {body[0]:#x}"
token = body[1:33]
print(f"Logged in as 16777217, token={token.hex()[:16]}...")

# TRANSFER 99,999 VND → 16777216
transfer_body = token + struct.pack(">IQ", 16777216, 99999)
s.sendall(frame(WIRE_TRANSFER, 2, transfer_body))

ftype, seq, body = recv_frame(s)
assert ftype == WIRE_ACK and body[0] == 0, f"Transfer failed: {body[0]:#x}"
print("Transfer OK — check simulator for notification!")

s.close()
