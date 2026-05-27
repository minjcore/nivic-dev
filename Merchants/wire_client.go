package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"math/rand/v2"
	"net"
	"time"
)

const (
	wireSecret           = "saving_wire_secret_changeme"
	wireTypeLogin        = 0x02
	wireTypeCreateIntent = 0x20
	wireTypeTOTPCharge   = 0x25
	wireTypeLoginACK     = 0x81
	wireTypeACK          = 0x82
	wireFrameOverhead    = 41
	wireMaxFrame         = 4096
	wireCodeOK           = 0x00
)

func wireSig(data []byte) [32]byte {
	mac := hmac.New(sha256.New, []byte(wireSecret))
	mac.Write(data)
	var out [32]byte
	mac.Sum(out[:0])
	return out
}

func wireEncode(typ uint8, seq uint32, body []byte) []byte {
	total := wireFrameOverhead + len(body)
	buf := make([]byte, total)
	binary.BigEndian.PutUint32(buf[0:], uint32(total))
	buf[4] = typ
	binary.BigEndian.PutUint32(buf[5:], seq)
	copy(buf[9:], body)
	sig := wireSig(buf[:9+len(body)])
	copy(buf[9+len(body):], sig[:])
	return buf
}

func wireRecv(conn net.Conn) (typ uint8, body []byte, err error) {
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
	want := wireSig(buf[:sigOff])
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

// WireLogin authenticates any Wire account (user or merchant) and returns the 32-byte session token.
// pwHashHex is the SHA-256 hex of the account password.
func WireLogin(addr string, uid uint32, pwHashHex string) ([32]byte, error) {
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
	if _, err = conn.Write(wireEncode(wireTypeLogin, 1, body)); err != nil {
		return empty, fmt.Errorf("send LOGIN: %w", err)
	}
	typ, resp, err := wireRecv(conn)
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

// WireTOTPCharge logs in as the merchant and submits a TOTP charge against a customer.
// totpCode is the raw 6-digit RFC 6238 integer. Wire debits customerUID and credits mid.
// Returns the error codes from Wire: TOTP_INVALID, LOW_BALANCE, NOT_FOUND, etc.
func WireTOTPCharge(addr string, mid uint32, pwHashHex string, customerUID uint32, totpCode uint32, amount uint64) error {
	pwRaw, err := hex.DecodeString(pwHashHex)
	if err != nil || len(pwRaw) != 32 {
		return fmt.Errorf("invalid pw_hash")
	}
	conn, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		return fmt.Errorf("dial wire: %w", err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(10 * time.Second))

	// LOGIN
	loginBody := make([]byte, 36)
	binary.BigEndian.PutUint32(loginBody, mid)
	copy(loginBody[4:], pwRaw)
	if _, err = conn.Write(wireEncode(wireTypeLogin, 1, loginBody)); err != nil {
		return fmt.Errorf("send LOGIN: %w", err)
	}
	typ, body, err := wireRecv(conn)
	if err != nil {
		return fmt.Errorf("recv LOGIN_ACK: %w", err)
	}
	if typ != wireTypeLoginACK || len(body) < 33 || body[0] != wireCodeOK {
		code := byte(0xFF)
		if len(body) > 0 {
			code = body[0]
		}
		return fmt.Errorf("login rejected: 0x%02X", code)
	}
	token := body[1:33]

	// TOTP_CHARGE: [merchant_token 32B][customer_uid 4B][totp_code 4B][amount 8B]
	chargeBody := make([]byte, 48)
	copy(chargeBody[0:], token)
	binary.BigEndian.PutUint32(chargeBody[32:], customerUID)
	binary.BigEndian.PutUint32(chargeBody[36:], totpCode)
	binary.BigEndian.PutUint64(chargeBody[40:], amount)
	if _, err = conn.Write(wireEncode(wireTypeTOTPCharge, 2, chargeBody)); err != nil {
		return fmt.Errorf("send TOTP_CHARGE: %w", err)
	}
	typ, body, err = wireRecv(conn)
	if err != nil {
		return fmt.Errorf("recv ACK: %w", err)
	}
	if typ != wireTypeACK || len(body) < 1 || body[0] != wireCodeOK {
		code := byte(0xFF)
		if len(body) > 0 {
			code = body[0]
		}
		switch code {
		case 0x0C:
			return fmt.Errorf("invalid TOTP code")
		case 0x08:
			return fmt.Errorf("insufficient balance")
		case 0x05:
			return fmt.Errorf("customer not found or TOTP not enrolled")
		default:
			return fmt.Errorf("charge rejected: 0x%02X", code)
		}
	}
	return nil
}

// WireCreateIntent dials Wire TCP, logs in as the merchant, and registers a payment intent.
// pwHashHex is the merchant's SHA-256 password hex (same as password_hash in Merchants DB).
// Returns the request_id confirmed by Wire, which is used to build the FrontStore deeplink.
func WireCreateIntent(addr string, mid uint32, pwHashHex string, amount uint64, gatewayOrderID string) (uint64, error) {
	pwRaw, err := hex.DecodeString(pwHashHex)
	if err != nil || len(pwRaw) != 32 {
		return 0, fmt.Errorf("invalid pw_hash")
	}

	conn, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		return 0, fmt.Errorf("dial wire: %w", err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(10 * time.Second))

	// LOGIN: [mid 4B][pw_hash 32B]
	loginBody := make([]byte, 36)
	binary.BigEndian.PutUint32(loginBody, mid)
	copy(loginBody[4:], pwRaw)
	if _, err = conn.Write(wireEncode(wireTypeLogin, 1, loginBody)); err != nil {
		return 0, fmt.Errorf("send LOGIN: %w", err)
	}

	typ, body, err := wireRecv(conn)
	if err != nil {
		return 0, fmt.Errorf("recv LOGIN_ACK: %w", err)
	}
	if typ != wireTypeLoginACK || len(body) < 33 || body[0] != wireCodeOK {
		code := byte(0xFF)
		if len(body) > 0 {
			code = body[0]
		}
		return 0, fmt.Errorf("LOGIN rejected: 0x%02X", code)
	}
	token := body[1:33]

	// CREATE_INTENT: [token 32B][request_id 8B][order_id 8B][amount 8B][gateway_order_id N]
	requestID := rand.Uint64()
	orderIDNum := fnv64a(gatewayOrderID)

	ciBody := make([]byte, 56+len(gatewayOrderID))
	copy(ciBody[0:], token)
	binary.BigEndian.PutUint64(ciBody[32:], requestID)
	binary.BigEndian.PutUint64(ciBody[40:], orderIDNum)
	binary.BigEndian.PutUint64(ciBody[48:], amount)
	copy(ciBody[56:], gatewayOrderID)

	if _, err = conn.Write(wireEncode(wireTypeCreateIntent, 2, ciBody)); err != nil {
		return 0, fmt.Errorf("send CREATE_INTENT: %w", err)
	}

	typ, body, err = wireRecv(conn)
	if err != nil {
		return 0, fmt.Errorf("recv CREATE_INTENT ACK: %w", err)
	}
	if typ != wireTypeACK || len(body) < 1 || body[0] != wireCodeOK {
		code := byte(0xFF)
		if len(body) > 0 {
			code = body[0]
		}
		return 0, fmt.Errorf("CREATE_INTENT rejected: 0x%02X", code)
	}

	// ACK body: [code 1B][status 1B][mid 4B][request_id 8B][amount 8B]
	if len(body) >= 14 {
		return binary.BigEndian.Uint64(body[6:14]), nil
	}
	return requestID, nil
}

// fnv64a maps an arbitrary string to uint64 for Wire's numeric order_id field.
func fnv64a(s string) uint64 {
	h := uint64(14695981039346656037)
	for i := 0; i < len(s); i++ {
		h ^= uint64(s[i])
		h *= 1099511628211
	}
	return h
}
