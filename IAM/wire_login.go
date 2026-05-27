package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"time"
)

const (
	wireTypeLogin    = 0x02
	wireTypeLoginACK = 0x81
	wireFrameOverhead = 41
	wireMaxFrame     = 4096
	wireCodeOK       = 0x00
)

func wireSig(data []byte, secret []byte) [32]byte {
	mac := hmac.New(sha256.New, secret)
	mac.Write(data)
	var out [32]byte
	mac.Sum(out[:0])
	return out
}

func wireEncode(typ uint8, seq uint32, body []byte, secret []byte) []byte {
	total := wireFrameOverhead + len(body)
	buf := make([]byte, total)
	binary.BigEndian.PutUint32(buf[0:], uint32(total))
	buf[4] = typ
	binary.BigEndian.PutUint32(buf[5:], seq)
	copy(buf[9:], body)
	sig := wireSig(buf[:9+len(body)], secret)
	copy(buf[9+len(body):], sig[:])
	return buf
}

func wireRecv(conn net.Conn, secret []byte) (typ uint8, body []byte, err error) {
	var hdr [4]byte
	if _, err = io.ReadFull(conn, hdr[:]); err != nil {
		return
	}
	total := binary.BigEndian.Uint32(hdr[:])
	if total < wireFrameOverhead || total > wireMaxFrame {
		err = fmt.Errorf("bad frame size %d", total)
		return
	}
	buf := make([]byte, total)
	copy(buf, hdr[:])
	if _, err = io.ReadFull(conn, buf[4:]); err != nil {
		return
	}
	sigOff := int(total) - 32
	want := wireSig(buf[:sigOff], secret)
	for i := range want {
		if buf[sigOff+i] != want[i] {
			err = fmt.Errorf("HMAC mismatch")
			return
		}
	}
	typ = buf[4]
	body = buf[9:sigOff]
	return
}

// wireLogin authenticates a Wire account and returns the 32-byte session token.
// pwHashHex is the SHA-256 hex of the account password.
func wireLogin(addr string, uid uint32, pwHashHex string, secret []byte) ([32]byte, error) {
	var empty [32]byte
	pwRaw, err := hex.DecodeString(pwHashHex)
	if err != nil || len(pwRaw) != 32 {
		return empty, fmt.Errorf("invalid pw_hash")
	}
	conn, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		return empty, fmt.Errorf("dial wire: %w", err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(10 * time.Second))

	body := make([]byte, 36)
	binary.BigEndian.PutUint32(body, uid)
	copy(body[4:], pwRaw)
	if _, err = conn.Write(wireEncode(wireTypeLogin, 1, body, secret)); err != nil {
		return empty, fmt.Errorf("send LOGIN: %w", err)
	}
	typ, resp, err := wireRecv(conn, secret)
	if err != nil {
		return empty, fmt.Errorf("recv LOGIN_ACK: %w", err)
	}
	if typ != wireTypeLoginACK || len(resp) < 33 || resp[0] != wireCodeOK {
		code := byte(0xFF)
		if len(resp) > 0 {
			code = resp[0]
		}
		return empty, fmt.Errorf("login rejected: 0x%02X", code)
	}
	var token [32]byte
	copy(token[:], resp[1:33])
	return token, nil
}
