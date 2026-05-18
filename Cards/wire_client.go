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

const (
	wireTypeLogin    = 0x02
	wireTypeCashIn   = 0x24
	wireTypeLoginAck = 0x81
	wireTypeAck      = 0x82
	wireCodeOK       = 0x00
	wireFrameOH      = 41 // 4(len)+1(type)+4(seq)+32(hmac)
	wireDialTimeout  = 5 * time.Second
	wireRPCTimeout   = 10 * time.Second
)

type WireClient struct {
	conn   net.Conn
	secret []byte
	seq    atomic.Uint32
}

func DialWire(addr, secret string) (*WireClient, error) {
	conn, err := net.DialTimeout("tcp", addr, wireDialTimeout)
	if err != nil {
		return nil, err
	}
	conn.SetDeadline(time.Now().Add(wireRPCTimeout))
	c := &WireClient{conn: conn, secret: []byte(secret)}
	var seed [4]byte
	rand.Read(seed[:])
	c.seq.Store(binary.BigEndian.Uint32(seed[:]))
	return c, nil
}

func (c *WireClient) Close() { c.conn.Close() }

func (c *WireClient) Login(uid uint32, password string) ([]byte, error) {
	pwdHash := sha256.Sum256([]byte(password))
	body := make([]byte, 4+32)
	binary.BigEndian.PutUint32(body, uid)
	copy(body[4:], pwdHash[:])
	if err := c.sendFrame(wireTypeLogin, body); err != nil {
		return nil, fmt.Errorf("login send: %w", err)
	}
	f, err := c.recvFrame()
	if err != nil {
		return nil, fmt.Errorf("login recv: %w", err)
	}
	if f.typ != wireTypeLoginAck || len(f.body) < 33 || f.body[0] != wireCodeOK {
		return nil, fmt.Errorf("login rejected: type=0x%x code=0x%x", f.typ, f.body[0])
	}
	token := make([]byte, 32)
	copy(token, f.body[1:33])
	return token, nil
}

func (c *WireClient) CashIn(token []byte, toUID uint32, amount uint64, topupID string) error {
	tid := []byte(topupID)
	body := make([]byte, 32+4+8+len(tid))
	copy(body, token)
	binary.BigEndian.PutUint32(body[32:], toUID)
	binary.BigEndian.PutUint64(body[36:], amount)
	copy(body[44:], tid)
	if err := c.sendFrame(wireTypeCashIn, body); err != nil {
		return fmt.Errorf("cash_in send: %w", err)
	}
	f, err := c.recvFrame()
	if err != nil {
		return fmt.Errorf("cash_in recv: %w", err)
	}
	if f.typ != wireTypeAck || len(f.body) < 1 || f.body[0] != wireCodeOK {
		return fmt.Errorf("cash_in failed: type=0x%x code=0x%x", f.typ, f.body[0])
	}
	return nil
}

type wireFrame struct {
	typ  uint8
	body []byte
}

func (c *WireClient) sendFrame(typ uint8, body []byte) error {
	seq := c.seq.Add(1)
	total := uint32(wireFrameOH + len(body))
	raw := make([]byte, 0, total)
	raw = binary.BigEndian.AppendUint32(raw, total)
	raw = append(raw, typ)
	raw = binary.BigEndian.AppendUint32(raw, seq)
	raw = append(raw, body...)
	mac := hmac.New(sha256.New, c.secret)
	mac.Write(raw)
	raw = append(raw, mac.Sum(nil)...)
	_, err := c.conn.Write(raw)
	return err
}

func (c *WireClient) recvFrame() (*wireFrame, error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(c.conn, lenBuf[:]); err != nil {
		return nil, err
	}
	total := binary.BigEndian.Uint32(lenBuf[:])
	if total < uint32(wireFrameOH) || total > 4096 {
		return nil, fmt.Errorf("bad frame len %d", total)
	}
	rest := make([]byte, total-4)
	if _, err := io.ReadFull(c.conn, rest); err != nil {
		return nil, err
	}
	covered := append(lenBuf[:], rest[:len(rest)-32]...)
	mac := hmac.New(sha256.New, c.secret)
	mac.Write(covered)
	if !hmac.Equal(mac.Sum(nil), rest[len(rest)-32:]) {
		return nil, fmt.Errorf("HMAC mismatch")
	}
	return &wireFrame{typ: rest[0], body: rest[5 : len(rest)-32]}, nil
}
