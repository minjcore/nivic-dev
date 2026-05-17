// Package totp implements the Saving offline payment-token scheme.
//
// A payment token is a 32-character Base32 string derived from:
//
//	base32( HMAC-SHA256(secret, counter)[0:20] )
//
// where counter = floor(unix_seconds / Step).  The token rotates every [Step]
// seconds (default 30 s) and is verified with ±[Drift] windows of tolerance
// to absorb clock skew between the customer's device and the merchant's device.
//
// # Enrollment flow (one-time, per customer-merchant pair)
//
//  1. Customer calls [SecretFromUID] or generates 20 random bytes and stores
//     them as their permanent payment secret.
//  2. Customer shows an enrollment QR:
//     saving://totp-enroll?uid=<uid>&secret=<Base32(secret)>
//  3. Merchant scans it and persists (uid → secret) in their local store.
//
// # Payment flow (per transaction)
//
//  1. Customer calls [Generate] and displays the resulting 32-char token as a
//     QR:  saving://totp-pay?uid=<uid>&token=<token>
//  2. Merchant scans and calls [Verify] with the stored secret.
//  3. If valid, merchant creates the order and proceeds with settlement.
//
// # Server-side use
//
// A backend that wants to validate a token it received (e.g. via an API call
// from the merchant app) can call [Verify] directly — no database round-trip
// or nonce storage is needed because the token space is large enough (160-bit)
// that brute-force within a 30-second window is infeasible.
package totp

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Step is the TOTP time window in seconds.
const Step = 30

// Drift is the number of ±windows accepted during Verify to tolerate clock skew.
const Drift = 1

// ─── Base32 ───────────────────────────────────────────────────────────────────

const b32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

// EncodeBase32 encodes data using standard Base32 without padding.
func EncodeBase32(data []byte) string {
	var sb strings.Builder
	buf, bits := 0, 0
	for _, b := range data {
		buf = (buf << 8) | int(b)
		bits += 8
		for bits >= 5 {
			bits -= 5
			sb.WriteByte(b32Alphabet[(buf>>bits)&0x1f])
		}
	}
	if bits > 0 {
		sb.WriteByte(b32Alphabet[(buf<<(5-bits))&0x1f])
	}
	return sb.String()
}

// DecodeBase32 decodes a Base32 string (case-insensitive, no padding required).
// Returns an error if the input contains characters outside the Base32 alphabet.
func DecodeBase32(s string) ([]byte, error) {
	s = strings.ToUpper(s)
	var out []byte
	buf, bits := 0, 0
	for _, c := range s {
		idx := strings.IndexRune(b32Alphabet, c)
		if idx < 0 {
			return nil, fmt.Errorf("totp: invalid base32 character %q", c)
		}
		buf = (buf << 5) | idx
		bits += 5
		if bits >= 8 {
			bits -= 8
			out = append(out, byte((buf>>bits)&0xff))
		}
	}
	return out, nil
}

// ─── TOTP ─────────────────────────────────────────────────────────────────────

// Generate returns the 32-character payment token for secret at the given time.
// secret must be at least 1 byte; 20 bytes is recommended.
func Generate(secret []byte, at time.Time) (string, error) {
	if len(secret) == 0 {
		return "", errors.New("totp: secret must not be empty")
	}
	counter := uint64(at.Unix()) / Step
	var msg [8]byte
	binary.BigEndian.PutUint64(msg[:], counter)
	mac := hmac.New(sha256.New, secret)
	mac.Write(msg[:])
	digest := mac.Sum(nil) // 32 bytes
	return EncodeBase32(digest[:20]), nil
}

// GenerateNow returns the current payment token using time.Now().
func GenerateNow(secret []byte) (string, error) {
	return Generate(secret, time.Now())
}

// Verify checks whether token matches secret at time now, accepting ±Drift windows.
// token is case-insensitive.
func Verify(secret []byte, token string, now time.Time) (bool, error) {
	token = strings.ToUpper(strings.TrimSpace(token))
	if len(token) != 32 {
		return false, fmt.Errorf("totp: token must be 32 characters, got %d", len(token))
	}
	base := int64(now.Unix()) / Step
	for delta := int64(-Drift); delta <= Drift; delta++ {
		counter := uint64(base + delta)
		var msg [8]byte
		binary.BigEndian.PutUint64(msg[:], counter)
		mac := hmac.New(sha256.New, secret)
		mac.Write(msg[:])
		digest := mac.Sum(nil)
		if EncodeBase32(digest[:20]) == token {
			return true, nil
		}
	}
	return false, nil
}

// VerifyNow calls Verify with time.Now().
func VerifyNow(secret []byte, token string) (bool, error) {
	return Verify(secret, token, time.Now())
}

// SecondsRemaining returns how many seconds are left in the current window.
func SecondsRemaining(now time.Time) int {
	return Step - int(now.Unix()%Step)
}

// ─── Enrollment URL helpers ───────────────────────────────────────────────────

// EnrollmentURL returns the QR payload customers show to merchants once:
//
//	saving://totp-enroll?uid=<uid>&secret=<Base32(secret)>
func EnrollmentURL(uid uint32, secret []byte) string {
	return fmt.Sprintf("saving://totp-enroll?uid=%d&secret=%s", uid, EncodeBase32(secret))
}

// PaymentURL returns the per-transaction QR payload at the given time:
//
//	saving://totp-pay?uid=<uid>&token=<32-char-token>
func PaymentURL(uid uint32, secret []byte, at time.Time) (string, error) {
	tok, err := Generate(secret, at)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("saving://totp-pay?uid=%d&token=%s", uid, tok), nil
}

// ─── QR URL parsers ───────────────────────────────────────────────────────────

// EnrollPayload holds the fields from a saving://totp-enroll QR.
type EnrollPayload struct {
	UID      uint32
	Secret   []byte // decoded from Base32
	SecretB32 string
}

// PayPayload holds the fields from a saving://totp-pay QR.
type PayPayload struct {
	UID   uint32
	Token string // 32-char Base32 token
}

// ParseEnrollURL parses a saving://totp-enroll QR string.
func ParseEnrollURL(raw string) (*EnrollPayload, error) {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "saving" || u.Host != "totp-enroll" {
		return nil, errors.New("totp: not an enrollment URL")
	}
	uid, err := parseUID(u.Query().Get("uid"))
	if err != nil {
		return nil, err
	}
	b32 := u.Query().Get("secret")
	if b32 == "" {
		return nil, errors.New("totp: missing secret parameter")
	}
	secret, err := DecodeBase32(b32)
	if err != nil {
		return nil, err
	}
	return &EnrollPayload{UID: uid, Secret: secret, SecretB32: strings.ToUpper(b32)}, nil
}

// ParsePayURL parses a saving://totp-pay QR string.
func ParsePayURL(raw string) (*PayPayload, error) {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "saving" || u.Host != "totp-pay" {
		return nil, errors.New("totp: not a pay URL")
	}
	uid, err := parseUID(u.Query().Get("uid"))
	if err != nil {
		return nil, err
	}
	token := strings.ToUpper(strings.TrimSpace(u.Query().Get("token")))
	if len(token) != 32 {
		return nil, fmt.Errorf("totp: token must be 32 characters, got %d", len(token))
	}
	return &PayPayload{UID: uid, Token: token}, nil
}

func parseUID(s string) (uint32, error) {
	n, err := strconv.ParseUint(s, 10, 32)
	if err != nil {
		return 0, fmt.Errorf("totp: invalid uid %q", s)
	}
	return uint32(n), nil
}
