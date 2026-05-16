package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync/atomic"
	"time"
)

// ─── Wire protocol types ──────────────────────────────────────────────────────

const (
	typeLogin    = 0x02
	typeTransfer = 0x11
	typeLoginAck = 0x81
	typeAck      = 0x82

	codeOK           = 0x00
	codeLowBalance   = 0x08

	frameOverhead = 41 // 4(len) + 1(type) + 4(seq) + 32(hmac)
)

// ─── WireClient ───────────────────────────────────────────────────────────────

type WireClient struct {
	conn   net.Conn
	secret []byte
	seq    atomic.Uint32
}

const (
	dialTimeout = 5 * time.Second
	rpcTimeout  = 8 * time.Second // covers login + transfer round-trips
)

func Dial(host string, port int, secret string) (*WireClient, error) {
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", host, port), dialTimeout)
	if err != nil {
		return nil, err
	}
	conn.SetDeadline(time.Now().Add(rpcTimeout))
	c := &WireClient{conn: conn, secret: []byte(secret)}
	// Start seq from a random base so (mid, seq) idempotency keys never collide
	// across separate connections for the same account.
	var seed [4]byte
	rand.Read(seed[:])
	c.seq.Store(binary.BigEndian.Uint32(seed[:]))
	return c, nil
}

func (c *WireClient) Close() { c.conn.Close() }

// Login authenticates with uid + sha256(password). Returns 32-byte session token.
func (c *WireClient) Login(uid uint32, password string) ([]byte, error) {
	pwdHash := sha256.Sum256([]byte(password))
	body := make([]byte, 4+32)
	binary.BigEndian.PutUint32(body, uid)
	copy(body[4:], pwdHash[:])

	if err := c.sendFrame(typeLogin, body); err != nil {
		return nil, fmt.Errorf("login send: %w", err)
	}

	frame, err := c.recvFrame()
	if err != nil {
		return nil, fmt.Errorf("login recv: %w", err)
	}
	if frame.typ != typeLoginAck || len(frame.body) < 1 {
		return nil, fmt.Errorf("unexpected response type 0x%x", frame.typ)
	}
	if frame.body[0] != codeOK {
		return nil, fmt.Errorf("login rejected: code 0x%x", frame.body[0])
	}
	if len(frame.body) < 33 {
		return nil, fmt.Errorf("loginAck body too short: %d", len(frame.body))
	}
	token := make([]byte, 32)
	copy(token, frame.body[1:33])
	return token, nil
}

// Transfer sends amount from the logged-in account to toUID.
func (c *WireClient) Transfer(token []byte, toUID uint32, amount uint64) error {
	body := make([]byte, 32+4+8)
	copy(body, token)
	binary.BigEndian.PutUint32(body[32:], toUID)
	binary.BigEndian.PutUint64(body[36:], amount)

	if err := c.sendFrame(typeTransfer, body); err != nil {
		return fmt.Errorf("transfer send: %w", err)
	}

	frame, err := c.recvFrame()
	if err != nil {
		return fmt.Errorf("transfer recv: %w", err)
	}
	if frame.typ != typeAck || len(frame.body) < 1 {
		return fmt.Errorf("unexpected response 0x%x", frame.typ)
	}
	if frame.body[0] != codeOK {
		return fmt.Errorf("transfer failed: code 0x%x", frame.body[0])
	}
	return nil
}

// ─── Frame I/O ────────────────────────────────────────────────────────────────

type rawFrame struct {
	typ  uint8
	seq  uint32
	body []byte
}

func (c *WireClient) sendFrame(typ uint8, body []byte) error {
	seq := c.seq.Add(1)
	totalLen := uint32(frameOverhead + len(body))

	raw := make([]byte, 0, totalLen)
	raw = binary.BigEndian.AppendUint32(raw, totalLen)
	raw = append(raw, typ)
	raw = binary.BigEndian.AppendUint32(raw, seq)
	raw = append(raw, body...)

	mac := hmac.New(sha256.New, c.secret)
	mac.Write(raw)
	raw = append(raw, mac.Sum(nil)...)

	_, err := c.conn.Write(raw)
	return err
}

func (c *WireClient) recvFrame() (*rawFrame, error) {
	// Read 4-byte length prefix
	var lenBuf [4]byte
	if _, err := io.ReadFull(c.conn, lenBuf[:]); err != nil {
		return nil, err
	}
	totalLen := binary.BigEndian.Uint32(lenBuf[:])
	if totalLen < uint32(frameOverhead) || totalLen > 4096 {
		return nil, fmt.Errorf("bad frame length: %d", totalLen)
	}

	rest := make([]byte, totalLen-4)
	if _, err := io.ReadFull(c.conn, rest); err != nil {
		return nil, err
	}

	// Verify HMAC
	covered := append(lenBuf[:], rest[:len(rest)-32]...)
	receivedMAC := rest[len(rest)-32:]
	mac := hmac.New(sha256.New, c.secret)
	mac.Write(covered)
	if !hmac.Equal(mac.Sum(nil), receivedMAC) {
		return nil, fmt.Errorf("HMAC mismatch")
	}

	typ := rest[0]
	seq := binary.BigEndian.Uint32(rest[1:5])
	body := rest[5 : len(rest)-32]
	return &rawFrame{typ: typ, seq: seq, body: body}, nil
}
