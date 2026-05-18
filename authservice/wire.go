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
	wireOverhead = 41
	wireMaxFrame = 4096
)

func wireLoginOnce(addr string, uid uint32, pwHash []byte) ([]byte, error) {
	c, err := net.Dial("tcp", addr)
	if err != nil {
		return nil, err
	}
	defer c.Close()

	body := make([]byte, 36)
	binary.BigEndian.PutUint32(body[0:4], uid)
	copy(body[4:], pwHash)

	total := uint32(wireOverhead + len(body))
	buf := make([]byte, total)
	binary.BigEndian.PutUint32(buf[0:4], total)
	buf[4] = 0x02 // LOGIN
	binary.BigEndian.PutUint32(buf[5:9], 1)
	copy(buf[9:], body)
	mac := hmac.New(sha256.New, []byte(wireSecret))
	mac.Write(buf[:9+len(body)])
	copy(buf[9+len(body):], mac.Sum(nil))

	if _, err := c.Write(buf); err != nil {
		return nil, err
	}

	var hdr [4]byte
	if _, err := io.ReadFull(c, hdr[:]); err != nil {
		return nil, err
	}
	tlen := binary.BigEndian.Uint32(hdr[:])
	if tlen < uint32(wireOverhead) || tlen > wireMaxFrame {
		return nil, fmt.Errorf("bad frame len")
	}
	rest := make([]byte, tlen-4)
	if _, err := io.ReadFull(c, rest); err != nil {
		return nil, err
	}
	bodyLen := int(tlen) - wireOverhead
	var resp []byte
	if bodyLen > 0 {
		resp = rest[5 : 5+bodyLen]
	}
	if len(resp) < 1 || resp[0] != 0x00 {
		code := byte(0xFF)
		if len(resp) > 0 {
			code = resp[0]
		}
		return nil, &authErr{code}
	}
	if len(resp) < 33 {
		return nil, fmt.Errorf("LOGIN_ACK too short")
	}
	token := make([]byte, 32)
	copy(token, resp[1:33])
	return token, nil
}

type authErr struct{ code byte }

func (e *authErr) Error() string {
	switch e.code {
	case 0x05:
		return "tài khoản không tồn tại"
	case 0x06:
		return "sai mật khẩu"
	}
	return fmt.Sprintf("lỗi 0x%02x", e.code)
}
