package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	wireFrameOverhead    = 41
	wireMaxFrameSize     = 4096
	wireRPCTimeout       = 10 * time.Second
	wireDialTimeout      = 8 * time.Second
	wireKeepaliveTimeout = 90 * time.Second
)

// Wire message types (client → server)
const (
	wireCmdPing     uint8 = 0x01
	wireCmdLogin    uint8 = 0x02
	wireCmdLogout   uint8 = 0x03
	wireCmdTransfer uint8 = 0x11
	wireCmdBalance  uint8 = 0x12
	wireCmdHistory  uint8 = 0x16
)

// Wire message types (server → client)
const (
	wireAckPong    uint8 = 0x80
	wireAckLogin   uint8 = 0x81
	wireAckGeneric uint8 = 0x82
)

// Wire push event types
const (
	wireEvtTransferIn uint8 = 0xC0
	wireEvtIntentPaid uint8 = 0xC4
)

// Wire response codes
const (
	wireCodeOK uint8 = 0x00
)

// WireFrame is a decoded Wire protocol frame.
type WireFrame struct {
	Typ  uint8
	Seq  uint32
	Body []byte
}

// WireClient is a multiplexed Wire TCP connection.
// Multiple goroutines can call RPC concurrently; push events are delivered
// on the Events channel.
type WireClient struct {
	conn    net.Conn
	secret  []byte
	seq     atomic.Uint32
	writeMu sync.Mutex
	pending sync.Map       // seq(uint32) → chan *WireFrame
	Events  chan *WireFrame // push events (typ >= 0xC0), buffered 64
	done    chan struct{}
}

// DialWire connects to a Wire TCP server at addr with the given HMAC secret.
func DialWire(addr string, secret []byte) (*WireClient, error) {
	conn, err := net.DialTimeout("tcp", addr, wireDialTimeout)
	if err != nil {
		return nil, fmt.Errorf("wire dial %s: %w", addr, err)
	}
	var seed [4]byte
	rand.Read(seed[:])
	c := &WireClient{
		conn:   conn,
		secret: secret,
		Events: make(chan *WireFrame, 64),
		done:   make(chan struct{}),
	}
	c.seq.Store(binary.BigEndian.Uint32(seed[:]))
	go c.readLoop()
	return c, nil
}

// Login authenticates with uid + sha256(password) and returns the 32-byte session token.
func (c *WireClient) Login(uid uint32, password string) ([]byte, error) {
	pwHash := sha256.Sum256([]byte(password))
	body := make([]byte, 4+32)
	binary.BigEndian.PutUint32(body, uid)
	copy(body[4:], pwHash[:])

	resp, err := c.RPC(wireCmdLogin, body)
	if err != nil {
		return nil, fmt.Errorf("wire login: %w", err)
	}
	if resp.Typ != wireAckLogin || len(resp.Body) < 1 {
		return nil, fmt.Errorf("wire login: unexpected ack type 0x%02x", resp.Typ)
	}
	if resp.Body[0] != wireCodeOK {
		return nil, fmt.Errorf("wire login: server error 0x%02x", resp.Body[0])
	}
	if len(resp.Body) < 33 {
		return nil, fmt.Errorf("wire login: ack too short")
	}
	token := make([]byte, 32)
	copy(token, resp.Body[1:33])
	return token, nil
}

// Balance returns balance, pending, available, version for the given session token.
func (c *WireClient) Balance(token []byte) (balance, pending, available, version uint64, err error) {
	resp, err := c.RPC(wireCmdBalance, token)
	if err != nil {
		return 0, 0, 0, 0, fmt.Errorf("wire balance: %w", err)
	}
	if len(resp.Body) < 1 || resp.Body[0] != wireCodeOK {
		return 0, 0, 0, 0, fmt.Errorf("wire balance: code 0x%02x", safeFirstByte(resp.Body))
	}
	if len(resp.Body) < 33 {
		return 0, 0, 0, 0, fmt.Errorf("wire balance: short body")
	}
	balance = binary.BigEndian.Uint64(resp.Body[1:9])
	pending = binary.BigEndian.Uint64(resp.Body[9:17])
	available = binary.BigEndian.Uint64(resp.Body[17:25])
	version = binary.BigEndian.Uint64(resp.Body[25:33])
	return
}

// Transfer sends amount to toUID. Returns after-balance on success.
func (c *WireClient) Transfer(token []byte, toUID uint32, amount uint64) (uint64, error) {
	body := make([]byte, 32+4+8)
	copy(body, token)
	binary.BigEndian.PutUint32(body[32:], toUID)
	binary.BigEndian.PutUint64(body[36:], amount)

	resp, err := c.RPC(wireCmdTransfer, body)
	if err != nil {
		return 0, fmt.Errorf("wire transfer: %w", err)
	}
	if len(resp.Body) < 1 || resp.Body[0] != wireCodeOK {
		return 0, fmt.Errorf("wire transfer: code 0x%02x — %s",
			safeFirstByte(resp.Body), wireErrMsg(safeFirstByte(resp.Body)))
	}
	var after uint64
	if len(resp.Body) >= 9 {
		after = binary.BigEndian.Uint64(resp.Body[1:9])
	}
	return after, nil
}

// Ping sends a PING and waits for PONG.
func (c *WireClient) Ping() error {
	resp, err := c.RPC(wireCmdPing, nil)
	if err != nil {
		return err
	}
	if resp.Typ != wireAckPong {
		return fmt.Errorf("expected PONG, got 0x%02x", resp.Typ)
	}
	return nil
}

// RPC sends a request and waits for the matching response by seq.
func (c *WireClient) RPC(typ uint8, body []byte) (*WireFrame, error) {
	seq := c.seq.Add(1)
	ch := make(chan *WireFrame, 1)
	c.pending.Store(seq, ch)
	defer c.pending.Delete(seq)

	if err := c.sendRaw(typ, seq, body); err != nil {
		return nil, err
	}
	select {
	case f, ok := <-ch:
		if !ok {
			return nil, fmt.Errorf("connection closed")
		}
		return f, nil
	case <-c.done:
		return nil, fmt.Errorf("connection closed")
	case <-time.After(wireRPCTimeout):
		return nil, fmt.Errorf("RPC timeout (type=0x%02x)", typ)
	}
}

// Close shuts down the connection.
func (c *WireClient) Close() {
	c.conn.Close()
}

// Done returns a channel closed when the connection is lost.
func (c *WireClient) Done() <-chan struct{} {
	return c.done
}

func (c *WireClient) readLoop() {
	defer func() {
		close(c.done)
		close(c.Events)
		c.pending.Range(func(k, v any) bool {
			close(v.(chan *WireFrame))
			c.pending.Delete(k)
			return true
		})
	}()
	for {
		f, err := c.recvRaw()
		if err != nil {
			return
		}
		if f.Typ >= 0xC0 {
			select {
			case c.Events <- f:
			default:
			}
			continue
		}
		if ch, ok := c.pending.Load(f.Seq); ok {
			ch.(chan *WireFrame) <- f
		}
	}
}

func (c *WireClient) sendRaw(typ uint8, seq uint32, body []byte) error {
	total := uint32(wireFrameOverhead + len(body))
	raw := make([]byte, 0, total)
	raw = binary.BigEndian.AppendUint32(raw, total)
	raw = append(raw, typ)
	raw = binary.BigEndian.AppendUint32(raw, seq)
	raw = append(raw, body...)
	mac := hmac.New(sha256.New, c.secret)
	mac.Write(raw)
	raw = append(raw, mac.Sum(nil)...)

	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	c.conn.SetDeadline(time.Now().Add(wireRPCTimeout))
	_, err := c.conn.Write(raw)
	return err
}

func (c *WireClient) recvRaw() (*WireFrame, error) {
	var lenBuf [4]byte
	c.conn.SetDeadline(time.Now().Add(wireKeepaliveTimeout))
	if _, err := io.ReadFull(c.conn, lenBuf[:]); err != nil {
		return nil, err
	}
	total := binary.BigEndian.Uint32(lenBuf[:])
	if total < uint32(wireFrameOverhead) || total > wireMaxFrameSize {
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
	body := make([]byte, len(rest)-5-32)
	copy(body, rest[5:len(rest)-32])
	return &WireFrame{
		Typ:  rest[0],
		Seq:  binary.BigEndian.Uint32(rest[1:5]),
		Body: body,
	}, nil
}

func safeFirstByte(b []byte) uint8 {
	if len(b) > 0 {
		return b[0]
	}
	return 0xFF
}

func wireErrMsg(code uint8) string {
	msgs := map[uint8]string{
		0x05: "not found",
		0x06: "bad password",
		0x07: "session expired",
		0x08: "low balance",
		0xFF: "internal error",
	}
	if s, ok := msgs[code]; ok {
		return s
	}
	return fmt.Sprintf("code 0x%02x", code)
}
