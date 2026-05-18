package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"io"
	"net"
)

const (
	wireSecret   = "saving_wire_secret_changeme"
	wireOverhead = 41 // 4+1+4+32
	wireMaxFrame = 4096

	msgLogin      byte = 0x02
	msgGetBalance byte = 0x12
	msgGetHistory byte = 0x16
	msgTransfer   byte = 0x11

	codeOK byte = 0x00
)

type wireConn struct {
	c   net.Conn
	seq uint32
}

func dialWire(addr string) (*wireConn, error) {
	c, err := net.Dial("tcp", addr)
	if err != nil {
		return nil, err
	}
	return &wireConn{c: c}, nil
}

func (w *wireConn) close() { w.c.Close() }

func (w *wireConn) nextSeq() uint32 {
	w.seq++
	return w.seq
}

func wireFrame(msgType byte, seq uint32, body []byte) []byte {
	total := uint32(wireOverhead + len(body))
	buf := make([]byte, total)
	binary.BigEndian.PutUint32(buf[0:4], total)
	buf[4] = msgType
	binary.BigEndian.PutUint32(buf[5:9], seq)
	copy(buf[9:], body)
	mac := hmac.New(sha256.New, []byte(wireSecret))
	mac.Write(buf[:9+len(body)])
	copy(buf[9+len(body):], mac.Sum(nil))
	return buf
}

func (w *wireConn) recv() (body []byte, err error) {
	var hdr [4]byte
	if _, err = io.ReadFull(w.c, hdr[:]); err != nil {
		return
	}
	total := binary.BigEndian.Uint32(hdr[:])
	if total < uint32(wireOverhead) || total > wireMaxFrame {
		err = fmt.Errorf("bad frame len %d", total)
		return
	}
	rest := make([]byte, total-4)
	if _, err = io.ReadFull(w.c, rest); err != nil {
		return
	}
	bodyLen := int(total) - wireOverhead
	if bodyLen > 0 {
		body = rest[5 : 5+bodyLen]
	}
	return
}

func (w *wireConn) rpc(msgType byte, body []byte) ([]byte, error) {
	if _, err := w.c.Write(wireFrame(msgType, w.nextSeq(), body)); err != nil {
		return nil, err
	}
	return w.recv()
}

// ─── High-level ops ───────────────────────────────────────────────────────────

func wireLogin(addr string, uid uint32, pwHash []byte) ([]byte, error) {
	w, err := dialWire(addr)
	if err != nil {
		return nil, err
	}
	defer w.close()

	body := make([]byte, 36)
	binary.BigEndian.PutUint32(body[0:4], uid)
	copy(body[4:], pwHash)

	resp, err := w.rpc(msgLogin, body)
	if err != nil {
		return nil, err
	}
	if len(resp) < 1 || resp[0] != codeOK {
		return nil, wireCodeErr(resp)
	}
	if len(resp) < 33 {
		return nil, fmt.Errorf("LOGIN_ACK too short")
	}
	token := make([]byte, 32)
	copy(token, resp[1:33])
	return token, nil
}

type BalanceResp struct {
	Balance   uint64 `json:"balance"`
	Pending   uint64 `json:"pending"`
	Available uint64 `json:"available_balance"`
	Version   uint64 `json:"version"`
}

func wireBalance(addr string, token []byte) (BalanceResp, error) {
	w, err := dialWire(addr)
	if err != nil {
		return BalanceResp{}, err
	}
	defer w.close()

	resp, err := w.rpc(msgGetBalance, token)
	if err != nil {
		return BalanceResp{}, err
	}
	if len(resp) < 1 || resp[0] != codeOK {
		return BalanceResp{}, wireCodeErr(resp)
	}
	var b BalanceResp
	if len(resp) >= 33 {
		b.Balance   = binary.BigEndian.Uint64(resp[1:9])
		b.Pending   = binary.BigEndian.Uint64(resp[9:17])
		b.Available = binary.BigEndian.Uint64(resp[17:25])
		b.Version   = binary.BigEndian.Uint64(resp[25:33])
	}
	return b, nil
}

type TxEntry struct {
	Direction    int    `json:"direction"`
	Counterpart  uint32 `json:"counterpart"`
	Amount       uint64 `json:"amount"`
	AfterBalance uint64 `json:"after_balance"`
}

func wireHistory(addr string, token []byte) ([]TxEntry, error) {
	w, err := dialWire(addr)
	if err != nil {
		return nil, err
	}
	defer w.close()

	resp, err := w.rpc(msgGetHistory, token)
	if err != nil {
		return nil, err
	}
	if len(resp) < 1 || resp[0] != codeOK {
		return nil, wireCodeErr(resp)
	}
	if len(resp) < 2 {
		return []TxEntry{}, nil
	}
	count := int(resp[1])
	txs := make([]TxEntry, 0, count)
	for i := range count {
		base := 2 + i*21
		if base+21 > len(resp) {
			break
		}
		txs = append(txs, TxEntry{
			Direction:    int(resp[base]),
			Counterpart:  binary.BigEndian.Uint32(resp[base+1 : base+5]),
			Amount:       binary.BigEndian.Uint64(resp[base+5 : base+13]),
			AfterBalance: binary.BigEndian.Uint64(resp[base+13 : base+21]),
		})
	}
	return txs, nil
}

func wireTransfer(addr string, token []byte, toID uint32, amount uint64) error {
	w, err := dialWire(addr)
	if err != nil {
		return err
	}
	defer w.close()

	body := make([]byte, 44)
	copy(body[0:32], token)
	binary.BigEndian.PutUint32(body[32:36], toID)
	binary.BigEndian.PutUint64(body[36:44], amount)

	resp, err := w.rpc(msgTransfer, body)
	if err != nil {
		return err
	}
	if len(resp) < 1 || resp[0] != codeOK {
		return wireCodeErr(resp)
	}
	return nil
}

// ─── Error ────────────────────────────────────────────────────────────────────

type WireError struct{ Code byte }

func (e *WireError) Error() string {
	m := map[byte]string{
		0x05: "tài khoản không tồn tại",
		0x06: "sai mật khẩu",
		0x07: "phiên hết hạn, đăng nhập lại",
		0x08: "số dư không đủ",
		0x03: "ID đã được đăng ký",
		0xFF: "lỗi server",
	}
	if s, ok := m[e.Code]; ok {
		return s
	}
	return fmt.Sprintf("lỗi 0x%02x", e.Code)
}

func wireCodeErr(resp []byte) error {
	code := byte(0xFF)
	if len(resp) > 0 {
		code = resp[0]
	}
	return &WireError{Code: code}
}
