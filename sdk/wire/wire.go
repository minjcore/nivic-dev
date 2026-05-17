// Package wire is the official Go SDK for the Saving Wire binary protocol.
//
// Wire is a length-prefixed TCP protocol where every frame is HMAC-SHA256
// authenticated:
//
//	┌──────────┬────────┬──────────┬──────────────────┬─────────────┐
//	│ len  4 B │type  1B│ seq   4 B│ body  (len-41) B │  mac   32 B │
//	└──────────┴────────┴──────────┴──────────────────┴─────────────┘
//
// Quick start:
//
//	c, err := wire.Dial("localhost:7474", "your-secret")
//	if err != nil { ... }
//	defer c.Close()
//
//	tok, err := c.Login(uid, password)
//	bal, err := c.Balance(tok)
//	err       = c.Transfer(tok, toUID, amount)
package wire

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

// ─── Protocol constants ───────────────────────────────────────────────────────

// Frame type bytes (client → server).
const (
	TypePing           uint8 = 0x01
	TypeLogin          uint8 = 0x02
	TypeLogout         uint8 = 0x03
	TypeCreateAccount  uint8 = 0x10
	TypeTransfer       uint8 = 0x11
	TypeGetBalance     uint8 = 0x12
	TypeAddGuardian    uint8 = 0x13
	TypeRecoveryReq    uint8 = 0x14
	TypeRecoveryApprove uint8 = 0x15
	TypeGetHistory     uint8 = 0x16
)

// Frame type bytes (server → client responses).
const (
	TypePong     uint8 = 0x80
	TypeLoginAck uint8 = 0x81
	TypeAck      uint8 = 0x82
)

// Frame type bytes (server → client push events).
const (
	EvtTransferIn  uint8 = 0xC0
	EvtRecoveryReq uint8 = 0xC1
	EvtRecoveryOK  uint8 = 0xC2
	EvtGuardianAdd uint8 = 0xC3
)

// Status codes carried in ack bodies.
const (
	CodeOK             uint8 = 0x00
	CodeErrBadFrame    uint8 = 0x01
	CodeErrBadSig      uint8 = 0x02
	CodeErrIDTaken     uint8 = 0x03
	CodeErrIDReserved  uint8 = 0x04
	CodeErrNotFound    uint8 = 0x05
	CodeErrBadPassword uint8 = 0x06
	CodeErrBadToken    uint8 = 0x07
	CodeErrLowBalance  uint8 = 0x08
	CodeErrInternal    uint8 = 0xFF
)

const frameOverhead = 41 // 4(len) + 1(type) + 4(seq) + 32(mac)

// ─── Account ID constants ─────────────────────────────────────────────────────

const (
	VIPMax  uint32 = 16_777_215   // reserved for system accounts
	UserMin uint32 = 16_777_216   // regular user IDs start here
	UserMax uint32 = 4_294_967_295
)

// ─── Error type ───────────────────────────────────────────────────────────────

// WireError carries the status code returned by the server.
type WireError struct {
	Code    uint8
	Message string
}

func (e *WireError) Error() string { return fmt.Sprintf("wire: %s (0x%02x)", e.Message, e.Code) }

func codeErr(code uint8) error {
	msgs := map[uint8]string{
		CodeErrBadFrame:    "bad frame",
		CodeErrBadSig:      "bad signature",
		CodeErrIDTaken:     "ID already taken",
		CodeErrIDReserved:  "ID reserved",
		CodeErrNotFound:    "not found",
		CodeErrBadPassword: "bad password",
		CodeErrBadToken:    "bad session token",
		CodeErrLowBalance:  "insufficient balance",
		CodeErrInternal:    "internal server error",
	}
	msg, ok := msgs[code]
	if !ok {
		msg = "unknown error"
	}
	return &WireError{Code: code, Message: msg}
}

// ─── Frame ────────────────────────────────────────────────────────────────────

type frame struct {
	typ  uint8
	seq  uint32
	body []byte
}

// ─── Client ───────────────────────────────────────────────────────────────────

// Client is a Wire TCP connection. It is NOT safe for concurrent use —
// create one Client per goroutine, or protect with a mutex.
type Client struct {
	conn   net.Conn
	secret []byte
	seq    atomic.Uint32

	DialTimeout time.Duration // default 5s
	RPCTimeout  time.Duration // per-RPC deadline, default 10s
}

// Dial opens a TCP connection to addr and returns a ready Client.
// addr is "host:port" (e.g. "localhost:7474").
// secret must match the server's WIRE_SECRET env variable.
func Dial(addr, secret string) (*Client, error) {
	c := &Client{
		secret:     []byte(secret),
		DialTimeout: 5 * time.Second,
		RPCTimeout:  10 * time.Second,
	}
	var seed [4]byte
	rand.Read(seed[:])
	c.seq.Store(binary.BigEndian.Uint32(seed[:]))

	conn, err := net.DialTimeout("tcp", addr, c.DialTimeout)
	if err != nil {
		return nil, fmt.Errorf("wire: dial %s: %w", addr, err)
	}
	c.conn = conn
	return c, nil
}

// Close tears down the TCP connection.
func (c *Client) Close() { c.conn.Close() }

// ─── API ──────────────────────────────────────────────────────────────────────

// CreateAccount registers a new account with the given uid and password.
// uid must be in [UserMin, UserMax]. Returns ErrIDTaken if uid is already used.
func (c *Client) CreateAccount(uid uint32, password string) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	pwdHash := sha256.Sum256([]byte(password))
	body := make([]byte, 4+32)
	binary.BigEndian.PutUint32(body, uid)
	copy(body[4:], pwdHash[:])

	if err := c.send(TypeCreateAccount, body); err != nil {
		return fmt.Errorf("wire: createAccount send: %w", err)
	}
	f, err := c.recv()
	if err != nil {
		return fmt.Errorf("wire: createAccount recv: %w", err)
	}
	return checkAck(f)
}

// Login authenticates with uid + password.
// Returns a 32-byte session token valid until Logout or server restart.
func (c *Client) Login(uid uint32, password string) (token []byte, err error) {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	pwdHash := sha256.Sum256([]byte(password))
	body := make([]byte, 4+32)
	binary.BigEndian.PutUint32(body, uid)
	copy(body[4:], pwdHash[:])

	if err = c.send(TypeLogin, body); err != nil {
		return nil, fmt.Errorf("wire: login send: %w", err)
	}
	f, err := c.recv()
	if err != nil {
		return nil, fmt.Errorf("wire: login recv: %w", err)
	}
	if f.typ != TypeLoginAck || len(f.body) < 1 {
		return nil, fmt.Errorf("wire: unexpected login response 0x%02x", f.typ)
	}
	if f.body[0] != CodeOK {
		return nil, codeErr(f.body[0])
	}
	if len(f.body) < 33 {
		return nil, fmt.Errorf("wire: loginAck too short")
	}
	tok := make([]byte, 32)
	copy(tok, f.body[1:33])
	return tok, nil
}

// Logout invalidates the session token on the server.
func (c *Client) Logout(token []byte) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	if err := c.send(TypeLogout, token); err != nil {
		return fmt.Errorf("wire: logout send: %w", err)
	}
	f, err := c.recv()
	if err != nil {
		return err
	}
	return checkAck(f)
}

// Balance returns the current balance of the logged-in account in VND đồng.
func (c *Client) Balance(token []byte) (uint64, error) {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	if err := c.send(TypeGetBalance, token); err != nil {
		return 0, fmt.Errorf("wire: balance send: %w", err)
	}
	f, err := c.recv()
	if err != nil {
		return 0, err
	}
	if f.typ != TypeAck || len(f.body) < 1 {
		return 0, fmt.Errorf("wire: unexpected balance response")
	}
	if f.body[0] != CodeOK {
		return 0, codeErr(f.body[0])
	}
	if len(f.body) < 9 {
		return 0, fmt.Errorf("wire: balance body too short")
	}
	return binary.BigEndian.Uint64(f.body[1:9]), nil
}

// Transfer sends amount VND đồng to toUID from the logged-in account.
func (c *Client) Transfer(token []byte, toUID uint32, amount uint64) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	body := make([]byte, 32+4+8)
	copy(body, token)
	binary.BigEndian.PutUint32(body[32:], toUID)
	binary.BigEndian.PutUint64(body[36:], amount)

	if err := c.send(TypeTransfer, body); err != nil {
		return fmt.Errorf("wire: transfer send: %w", err)
	}
	f, err := c.recv()
	if err != nil {
		return err
	}
	return checkAck(f)
}

// Transaction is one history entry returned by History.
type Transaction struct {
	Seq       uint32
	Direction string // "sent" | "received"
	PeerUID   uint32
	Amount    uint64
	Balance   uint64
}

// History returns recent transactions for the logged-in account.
func (c *Client) History(token []byte) ([]Transaction, error) {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	if err := c.send(TypeGetHistory, token); err != nil {
		return nil, fmt.Errorf("wire: history send: %w", err)
	}
	f, err := c.recv()
	if err != nil {
		return nil, err
	}
	if f.typ != TypeAck || len(f.body) < 1 {
		return nil, fmt.Errorf("wire: unexpected history response")
	}
	if f.body[0] != CodeOK {
		return nil, codeErr(f.body[0])
	}
	// Body: code(1) | count(2) | [ dir(1)|peer(4)|amount(8)|balance(8) ] * count
	if len(f.body) < 3 {
		return nil, fmt.Errorf("wire: history body too short")
	}
	count := int(binary.BigEndian.Uint16(f.body[1:3]))
	txs := make([]Transaction, 0, count)
	off := 3
	for i := 0; i < count; i++ {
		if off+21 > len(f.body) {
			break
		}
		dir := "received"
		if f.body[off] == 0x01 {
			dir = "sent"
		}
		tx := Transaction{
			Direction: dir,
			PeerUID:   binary.BigEndian.Uint32(f.body[off+1:]),
			Amount:    binary.BigEndian.Uint64(f.body[off+5:]),
			Balance:   binary.BigEndian.Uint64(f.body[off+13:]),
		}
		txs = append(txs, tx)
		off += 21
	}
	return txs, nil
}

// AddGuardian adds guardianUID as a social-recovery guardian for the logged-in account.
func (c *Client) AddGuardian(token []byte, guardianUID uint32) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	body := make([]byte, 32+4)
	copy(body, token)
	binary.BigEndian.PutUint32(body[32:], guardianUID)
	if err := c.send(TypeAddGuardian, body); err != nil {
		return err
	}
	f, err := c.recv()
	if err != nil {
		return err
	}
	return checkAck(f)
}

// RequestRecovery requests guardianUID to approve account recovery.
func (c *Client) RequestRecovery(uid uint32, password string, guardianUID uint32) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	pwdHash := sha256.Sum256([]byte(password))
	body := make([]byte, 4+32+4)
	binary.BigEndian.PutUint32(body, uid)
	copy(body[4:], pwdHash[:])
	binary.BigEndian.PutUint32(body[36:], guardianUID)
	if err := c.send(TypeRecoveryReq, body); err != nil {
		return err
	}
	f, err := c.recv()
	if err != nil {
		return err
	}
	return checkAck(f)
}

// ApproveRecovery approves a pending recovery request for targetUID.
func (c *Client) ApproveRecovery(token []byte, targetUID uint32) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	body := make([]byte, 32+4)
	copy(body, token)
	binary.BigEndian.PutUint32(body[32:], targetUID)
	if err := c.send(TypeRecoveryApprove, body); err != nil {
		return err
	}
	f, err := c.recv()
	if err != nil {
		return err
	}
	return checkAck(f)
}

// Ping sends a keepalive ping and waits for pong.
func (c *Client) Ping() error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	if err := c.send(TypePing, nil); err != nil {
		return err
	}
	f, err := c.recv()
	if err != nil {
		return err
	}
	if f.typ != TypePong {
		return fmt.Errorf("wire: expected pong, got 0x%02x", f.typ)
	}
	return nil
}

// ─── Push-event listener ──────────────────────────────────────────────────────

// Event is delivered by Listen when the server pushes an unsolicited frame.
type Event struct {
	Type    uint8
	Payload []byte
}

// TransferInEvent is the decoded payload for EvtTransferIn.
type TransferInEvent struct {
	FromUID uint32
	Amount  uint64
	Balance uint64
}

// DecodeTransferIn decodes an EvtTransferIn event payload.
func DecodeTransferIn(e Event) (TransferInEvent, error) {
	if e.Type != EvtTransferIn || len(e.Payload) < 20 {
		return TransferInEvent{}, fmt.Errorf("wire: not a transferIn event")
	}
	return TransferInEvent{
		FromUID: binary.BigEndian.Uint32(e.Payload[0:]),
		Amount:  binary.BigEndian.Uint64(e.Payload[4:]),
		Balance: binary.BigEndian.Uint64(e.Payload[12:]),
	}, nil
}

// Listen blocks and delivers server-pushed events to ch.
// Returns when the connection closes.
// Call in a separate goroutine after Login.
func (c *Client) Listen(ch chan<- Event) {
	for {
		f, err := c.recv()
		if err != nil {
			close(ch)
			return
		}
		if f.typ >= 0xC0 { // push events
			ch <- Event{Type: f.typ, Payload: f.body}
		}
	}
}

// ─── Frame I/O ────────────────────────────────────────────────────────────────

func (c *Client) send(typ uint8, body []byte) error {
	seq := c.seq.Add(1)
	total := uint32(frameOverhead + len(body))

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

func (c *Client) recv() (*frame, error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(c.conn, lenBuf[:]); err != nil {
		return nil, fmt.Errorf("wire: recv len: %w", err)
	}
	total := binary.BigEndian.Uint32(lenBuf[:])
	if total < uint32(frameOverhead) || total > 65535 {
		return nil, fmt.Errorf("wire: bad frame length %d", total)
	}
	rest := make([]byte, total-4)
	if _, err := io.ReadFull(c.conn, rest); err != nil {
		return nil, fmt.Errorf("wire: recv body: %w", err)
	}

	// Verify HMAC
	covered := append(lenBuf[:], rest[:len(rest)-32]...)
	gotMAC := rest[len(rest)-32:]
	mac := hmac.New(sha256.New, c.secret)
	mac.Write(covered)
	if !hmac.Equal(mac.Sum(nil), gotMAC) {
		return nil, fmt.Errorf("wire: HMAC mismatch")
	}

	typ := rest[0]
	seq := binary.BigEndian.Uint32(rest[1:5])
	body := make([]byte, len(rest)-5-32)
	copy(body, rest[5:len(rest)-32])
	return &frame{typ: typ, seq: seq, body: body}, nil
}

func checkAck(f *frame) error {
	if f.typ != TypeAck || len(f.body) < 1 {
		return fmt.Errorf("wire: unexpected frame 0x%02x", f.typ)
	}
	if f.body[0] != CodeOK {
		return codeErr(f.body[0])
	}
	return nil
}
