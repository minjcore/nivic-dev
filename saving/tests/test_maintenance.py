"""
test_maintenance.py — maintenance flag blocks Wire commands while ON,
and resumes normal operation after turning OFF.
"""
import struct, hmac, hashlib, socket, time, requests, random

HOST, PORT = "127.0.0.1", 7474
ADMIN_URL  = "http://127.0.0.1:7475"
ADMIN_PASS = "saving_admin_dev"
SECRET     = b"saving_wire_secret_changeme"
RUN_ID     = random.randint(100_000, 999_999)

def sign(p):
    return hmac.new(SECRET, p, hashlib.sha256).digest()

def encode(typ, seq, body=b""):
    total = 9 + len(body) + 32
    hdr   = struct.pack(">IBI", total, typ, seq)
    return hdr + body + sign(hdr + body)

def recv_rpc(s):
    while True:
        hdr = b""
        while len(hdr) < 9: hdr += s.recv(9 - len(hdr))
        total, ftype, _ = struct.unpack(">IBI", hdr)
        rest = b""
        rest_len = total - 9
        while len(rest) < rest_len: rest += s.recv(rest_len - len(rest))
        if ftype >= 0xC0: continue   # skip push events
        return ftype, rest[:-32]     # strip HMAC

def conn():
    s = socket.socket(); s.connect((HOST, PORT)); s.settimeout(3); return s

def admin_token():
    r = requests.post(f"{ADMIN_URL}/api/login",
                      json={"username": "admin", "password": ADMIN_PASS})
    return r.json()["token"]

def set_maintenance(token, on):
    requests.post(f"{ADMIN_URL}/api/maintenance",
                  json={"enabled": on},
                  headers={"Authorization": f"Bearer {token}"})

def get_maintenance(token):
    r = requests.get(f"{ADMIN_URL}/api/maintenance",
                     headers={"Authorization": f"Bearer {token}"})
    return r.json()["maintenance"]

def create_and_login(uid, pw, base_seq=1):
    s = conn()
    body = struct.pack(">I", uid) + hashlib.sha256(pw.encode()).digest()
    s.sendall(encode(0x10, base_seq, body))
    recv_rpc(s)
    s.sendall(encode(0x02, base_seq+1, body))
    _, r = recv_rpc(s)
    assert r[0] == 0, f"login failed: 0x{r[0]:02X}"
    return s, r[1:33]  # socket, token


def test_maintenance_blocks_transfer():
    """With maintenance ON, GET_BALANCE returns ERR_MAINTENANCE (0x10)."""
    tok = admin_token()
    set_maintenance(tok, False)   # reset any stale state
    # Create account while server is in normal mode
    uid = 16_777_216 + RUN_ID + 4000
    s_pre = conn()
    body  = struct.pack(">I", uid) + hashlib.sha256(b"pw").digest()
    s_pre.sendall(encode(0x10, 1, body)); recv_rpc(s_pre); s_pre.close()

    set_maintenance(tok, True)
    assert get_maintenance(tok) is True

    # LOGIN is allowed during maintenance
    s = conn()
    s.sendall(encode(0x02, 1, body))
    _, r = recv_rpc(s)
    assert r[0] == 0x00, f"LOGIN should work during maintenance, got 0x{r[0]:02X}"
    token32 = r[1:33]
    # GET_BALANCE (0x12) should be blocked
    s.sendall(encode(0x12, 2, token32))
    _, r2 = recv_rpc(s)
    s.close()
    set_maintenance(tok, False)
    assert r2[0] == 0x10, f"expected ERR_MAINTENANCE (0x10), got 0x{r2[0]:02X}"

def test_maintenance_ping_allowed():
    """PING works even when maintenance is ON."""
    tok = admin_token()
    set_maintenance(tok, False)   # reset any stale state
    set_maintenance(tok, True)
    s = conn()
    s.sendall(encode(0x01, 1))
    ftype, _ = recv_rpc(s)
    s.close()
    set_maintenance(tok, False)
    assert ftype == 0x80, f"PING should return PONG (0x80), got 0x{ftype:02X}"

def test_maintenance_off_resumes():
    """Normal operation resumes after turning maintenance OFF."""
    tok = admin_token()
    set_maintenance(tok, False)   # reset any stale state
    uid = 16_777_216 + RUN_ID + 5000
    s, token32 = create_and_login(uid, "pw2")
    s.close()

    set_maintenance(tok, True)
    set_maintenance(tok, False)
    assert get_maintenance(tok) is False

    s2, token32 = create_and_login(uid, "pw2", base_seq=3)
    s2.sendall(encode(0x12, 5, token32))
    _, r = recv_rpc(s2)
    s2.close()
    assert r[0] == 0x00, f"GET_BALANCE should return OK after maintenance OFF, got 0x{r[0]:02X}"


test_maintenance_blocks_transfer()
test_maintenance_ping_allowed()
test_maintenance_off_resumes()
print("All maintenance tests passed.")
