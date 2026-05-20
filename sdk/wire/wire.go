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
	TypePing            uint8 = 0x01
	TypeLogin           uint8 = 0x02
	TypeLogout          uint8 = 0x03
	TypeCreateAccount   uint8 = 0x10
	TypeTransfer        uint8 = 0x11
	TypeGetBalance      uint8 = 0x12
	TypeAddGuardian     uint8 = 0x13
	TypeRecoveryReq     uint8 = 0x14
	TypeRecoveryApprove uint8 = 0x15
	TypeGetHistory      uint8 = 0x16
	TypeCreateIntent    uint8 = 0x20
	TypePayIntent       uint8 = 0x21
	TypeEnrollTOTP      uint8 = 0x22
	TypeRegisterMerchant uint8 = 0x23
	TypeCashIn          uint8 = 0x24
	TypeTOTPCharge      uint8 = 0x25
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
	EvtIntentPaid  uint8 = 0xC4
)

// Status codes carried in ack bodies.
const (
	CodeOK               uint8 = 0x00
	CodeErrBadFrame      uint8 = 0x01
	CodeErrBadSig        uint8 = 0x02
	CodeErrIDTaken       uint8 = 0x03
	CodeErrIDReserved    uint8 = 0x04
	CodeErrNotFound      uint8 = 0x05
	CodeErrBadPassword   uint8 = 0x06
	CodeErrBadToken      uint8 = 0x07
	CodeErrLowBalance    uint8 = 0x08
	CodeErrGuardianFull  uint8 = 0x09
	CodeErrNotGuardian   uint8 = 0x0A
	CodeErrNeedGuardians uint8 = 0x0B
	CodeErrTOTPInvalid   uint8 = 0x0C
	CodeErrIntentSettled uint8 = 0x0D
	CodeErrNotMerchant   uint8 = 0x0E
	CodeErrInternal      uint8 = 0xFF
)

const frameOverhead = 41 // 4(len) + 1(type) + 4(seq) + 32(mac)

// ─── Account ID constants ─────────────────────────────────────────────────────

const (
	VIPMax  uint32 = 16_777_215
	UserMin uint32 = 16_777_216
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
		CodeErrBadFrame:      "bad frame",
		CodeErrBadSig:        "bad signature",
		CodeErrIDTaken:       "ID already taken",
		CodeErrIDReserved:    "ID reserved",
		CodeErrNotFound:      "not found",
		CodeErrBadPassword:   "bad password",
		CodeErrBadToken:      "bad session token",
		CodeErrLowBalance:    "insufficient balance",
		CodeErrGuardianFull:  "guardian list full",
		CodeErrNotGuardian:   "not a guardian",
		CodeErrNeedGuardians: "need at least 2 guardians",
		CodeErrTOTPInvalid:   "invalid TOTP code",
		CodeErrIntentSettled: "intent already settled",
		CodeErrNotMerchant:   "not a merchant account",
		CodeErrInternal:      "internal server error",
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

	DialTimeout time.Duration
	RPCTimeout  time.Duration
}

// Dial opens a TCP connection to addr and returns a ready Client.
// addr is "host:port" (e.g. "localhost:7474").
// secret must match the server's WIRE_SECRET env variable.
func Dial(addr, secret string) (*Client, error) {
	c := &Client{
		secret:      []byte(secret),
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

// ─── Account ──────────────────────────────────────────────────────────────────

// CreateAccount registers a new account with the given uid and password.
func (c *Client) CreateAccount(uid uint32, password string) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	pwdHash := sha256.Sum256([]byte(password))
	body := make([]byte, 4+32)
	binary.BigEndian.PutUint32(body, uid)
	copy(body[4:], pwdHash[:])
	f, err := c.rpc(TypeCreateAccount, body)
	if err != nil {
		return err
	}
	return checkAck(f)
}

// Login authenticates with uid + password.
// Returns a 32-byte session token.
func (c *Client) Login(uid uint32, password string) (token []byte, err error) {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	pwdHash := sha256.Sum256([]byte(password))
	body := make([]byte, 4+32)
	binary.BigEndian.PutUint32(body, uid)
	copy(body[4:], pwdHash[:])

	f, err := c.rpc(TypeLogin, body)
	if err != nil {
		return nil, err
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
	f, err := c.rpc(TypeLogout, token)
	if err != nil {
		return err
	}
	return checkAck(f)
}

// ─── Balance & History ────────────────────────────────────────────────────────

// Balance returns the current balance in VND đồng.
func (c *Client) Balance(token []byte) (uint64, error) {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	f, err := c.rpc(TypeGetBalance, token)
	if err != nil {
		return 0, err
	}
	if err := checkAck(f); err != nil {
		return 0, err
	}
	if len(f.body) < 9 {
		return 0, fmt.Errorf("wire: balance body too short")
	}
	return binary.BigEndian.Uint64(f.body[1:9]), nil
}

// Transaction is one entry returned by History.
type Transaction struct {
	Direction string // "sent" | "received" | "payment_sent" | "payment_received" | "cash_in" | "cash_out"
	PeerUID   uint32
	Amount    uint64
	Balance   uint64
}

// History returns the last ≤20 transactions for the logged-in account.
func (c *Client) History(token []byte) ([]Transaction, error) {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	f, err := c.rpc(TypeGetHistory, token)
	if err != nil {
		return nil, err
	}
	if err := checkAck(f); err != nil {
		return nil, err
	}
	// body: [code 1B][count 1B][dir 1B | peer 4B | amount 8B | balance 8B] × count
	if len(f.body) < 2 {
		return []Transaction{}, nil
	}
	count := int(f.body[1])
	txs := make([]Transaction, 0, count)
	off := 2
	dirs := map[uint8]string{
		0: "sent", 1: "received",
		2: "payment_sent", 3: "payment_received",
		4: "cash_in", 5: "cash_out",
	}
	for i := 0; i < count && off+21 <= len(f.body); i++ {
		dir := dirs[f.body[off]]
		if dir == "" {
			dir = fmt.Sprintf("0x%02x", f.body[off])
		}
		txs = append(txs, Transaction{
			Direction: dir,
			PeerUID:   binary.BigEndian.Uint32(f.body[off+1:]),
			Amount:    binary.BigEndian.Uint64(f.body[off+5:]),
			Balance:   binary.BigEndian.Uint64(f.body[off+13:]),
		})
		off += 21
	}
	return txs, nil
}

// ─── Transfer ─────────────────────────────────────────────────────────────────

// Transfer sends amount VND to toUID.
func (c *Client) Transfer(token []byte, toUID uint32, amount uint64) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	body := make([]byte, 32+4+8)
	copy(body, token)
	binary.BigEndian.PutUint32(body[32:], toUID)
	binary.BigEndian.PutUint64(body[36:], amount)
	f, err := c.rpc(TypeTransfer, body)
	if err != nil {
		return err
	}
	return checkAck(f)
}

// CashIn credits toUID's account with amount VND (bank float account only).
// topupID is an arbitrary reference string for idempotency.
func (c *Client) CashIn(token []byte, toUID uint32, amount uint64, topupID string) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	tid := []byte(topupID)
	body := make([]byte, 32+4+8+len(tid))
	copy(body, token)
	binary.BigEndian.PutUint32(body[32:], toUID)
	binary.BigEndian.PutUint64(body[36:], amount)
	copy(body[44:], tid)
	f, err := c.rpc(TypeCashIn, body)
	if err != nil {
		return err
	}
	return checkAck(f)
}

// ─── Social recovery ─────────────────────────────────────────────────────────

// AddGuardian registers guardianUID as a recovery guardian.
func (c *Client) AddGuardian(token []byte, guardianUID uint32) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	body := make([]byte, 32+4)
	copy(body, token)
	binary.BigEndian.PutUint32(body[32:], guardianUID)
	f, err := c.rpc(TypeAddGuardian, body)
	if err != nil {
		return err
	}
	return checkAck(f)
}

// RequestRecovery broadcasts a recovery request for uid (no token — new device).
func (c *Client) RequestRecovery(uid uint32) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	body := make([]byte, 4)
	binary.BigEndian.PutUint32(body, uid)
	f, err := c.rpc(TypeRecoveryReq, body)
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
	f, err := c.rpc(TypeRecoveryApprove, body)
	if err != nil {
		return err
	}
	return checkAck(f)
}

// ─── Merchant & Payment Intent ────────────────────────────────────────────────

// RegisterMerchant registers the logged-in VIP account as a named merchant.
func (c *Client) RegisterMerchant(token []byte, name string) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	body := append(token[:32:32], []byte(name)...)
	f, err := c.rpc(TypeRegisterMerchant, body)
	if err != nil {
		return err
	}
	return checkAck(f)
}

// EnrollTOTP enrolls a customer's TOTP secret for a merchant.
// secret must be the raw 20-byte HMAC-SHA256 TOTP key.
func (c *Client) EnrollTOTP(token []byte, customerUID uint32, secret []byte) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	body := make([]byte, 32+4+len(secret))
	copy(body, token)
	binary.BigEndian.PutUint32(body[32:], customerUID)
	copy(body[36:], secret)
	f, err := c.rpc(TypeEnrollTOTP, body)
	if err != nil {
		return err
	}
	return checkAck(f)
}

// IntentResult is returned by CreateIntent.
type IntentResult struct {
	MerchantUID uint32
	RequestID   uint64
	Amount      uint64
}

// CreateIntent creates a payment intent (QR order) for the logged-in merchant.
// requestID and orderID should be unique per request; use time.Now().UnixMilli() if unsure.
func (c *Client) CreateIntent(token []byte, requestID, orderID, amount uint64) (IntentResult, error) {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	body := make([]byte, 32+8+8+8)
	copy(body, token)
	binary.BigEndian.PutUint64(body[32:], requestID)
	binary.BigEndian.PutUint64(body[40:], orderID)
	binary.BigEndian.PutUint64(body[48:], amount)

	f, err := c.rpc(TypeCreateIntent, body)
	if err != nil {
		return IntentResult{}, err
	}
	if err := checkAck(f); err != nil {
		return IntentResult{}, err
	}
	// extra: [status 1B][mid 4B][request_id 8B][amount 8B]
	if len(f.body) < 22 {
		return IntentResult{}, fmt.Errorf("wire: createIntent ack too short")
	}
	return IntentResult{
		MerchantUID: binary.BigEndian.Uint32(f.body[2:6]),
		RequestID:   binary.BigEndian.Uint64(f.body[6:14]),
		Amount:      binary.BigEndian.Uint64(f.body[14:22]),
	}, nil
}

// PayIntent pays a merchant intent using the customer's TOTP code.
// totpCode is the current 6-digit code shown to the customer.
func (c *Client) PayIntent(token []byte, merchantUID uint32, requestID uint64, totpCode uint32) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	body := make([]byte, 32+4+8+4)
	copy(body, token)
	binary.BigEndian.PutUint32(body[32:], merchantUID)
	binary.BigEndian.PutUint64(body[36:], requestID)
	binary.BigEndian.PutUint32(body[44:], totpCode)
	f, err := c.rpc(TypePayIntent, body)
	if err != nil {
		return err
	}
	return checkAck(f)
}

// TOTPCharge initiates a merchant-initiated charge using the customer's TOTP code.
// Only VIP merchant accounts (uid < 16_777_216) may call this.
func (c *Client) TOTPCharge(token []byte, customerUID uint32, totpCode uint32, amount uint64) error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	body := make([]byte, 32+4+4+8)
	copy(body, token)
	binary.BigEndian.PutUint32(body[32:], customerUID)
	binary.BigEndian.PutUint32(body[36:], totpCode)
	binary.BigEndian.PutUint64(body[40:], amount)
	f, err := c.rpc(TypeTOTPCharge, body)
	if err != nil {
		return err
	}
	return checkAck(f)
}

// ─── Ping ─────────────────────────────────────────────────────────────────────

// Ping sends a keepalive and waits for pong.
func (c *Client) Ping() error {
	c.conn.SetDeadline(time.Now().Add(c.RPCTimeout))
	f, err := c.rpc(TypePing, nil)
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

// IntentPaidEvent is the decoded payload for EvtIntentPaid.
type IntentPaidEvent struct {
	RequestID  uint64
	CustomerUID uint32
	Amount     uint64
}

// DecodeTransferIn decodes an EvtTransferIn event payload.
func DecodeTransferIn(e Event) (TransferInEvent, error) {
	if e.Type != EvtTransferIn || len(e.Payload) < 20 {
		return TransferInEvent{}, fmt.Errorf("wire: not a transferIn event")
	}
	return TransferInEvent{
		FromUID: binary.BigEndian.Uint32(e.Payload[0:4]),
		Amount:  binary.BigEndian.Uint64(e.Payload[4:12]),
		Balance: binary.BigEndian.Uint64(e.Payload[12:20]),
	}, nil
}

// DecodeIntentPaid decodes an EvtIntentPaid event payload.
func DecodeIntentPaid(e Event) (IntentPaidEvent, error) {
	if e.Type != EvtIntentPaid || len(e.Payload) < 20 {
		return IntentPaidEvent{}, fmt.Errorf("wire: not an intentPaid event")
	}
	return IntentPaidEvent{
		RequestID:   binary.BigEndian.Uint64(e.Payload[0:8]),
		CustomerUID: binary.BigEndian.Uint32(e.Payload[8:12]),
		Amount:      binary.BigEndian.Uint64(e.Payload[12:20]),
	}, nil
}

// Listen blocks and delivers server-pushed events to ch.
// Returns when the connection closes or an error occurs.
// Call in a separate goroutine after Login.
func (c *Client) Listen(ch chan<- Event) {
	for {
		f, err := c.recv()
		if err != nil {
			close(ch)
			return
		}
		if f.typ >= 0xC0 {
			ch <- Event{Type: f.typ, Payload: f.body}
		}
	}
}

// ─── Frame I/O ────────────────────────────────────────────────────────────────

func (c *Client) rpc(typ uint8, body []byte) (*frame, error) {
	if err := c.send(typ, body); err != nil {
		return nil, fmt.Errorf("wire: send 0x%02x: %w", typ, err)
	}
	return c.recv()
}

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
