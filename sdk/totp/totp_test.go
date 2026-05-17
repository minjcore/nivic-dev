package totp_test

import (
	"strings"
	"testing"
	"time"

	"nivic.dev/saving/sdk/totp"
)

var testSecret = []byte("12345678901234567890") // 20 bytes, deterministic

func TestGenerateLen(t *testing.T) {
	tok, err := totp.GenerateNow(testSecret)
	if err != nil {
		t.Fatal(err)
	}
	if len(tok) != 32 {
		t.Fatalf("expected 32 chars, got %d: %q", len(tok), tok)
	}
	if strings.ToUpper(tok) != tok {
		t.Fatalf("token must be uppercase: %q", tok)
	}
}

func TestVerifyCurrentWindow(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	tok, _ := totp.Generate(testSecret, now)
	ok, err := totp.Verify(testSecret, tok, now)
	if err != nil || !ok {
		t.Fatalf("expected valid token, err=%v ok=%v", err, ok)
	}
}

func TestVerifyDriftMinus(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	prev := now.Add(-totp.Step * time.Second)
	tok, _ := totp.Generate(testSecret, prev)
	ok, err := totp.Verify(testSecret, tok, now)
	if err != nil || !ok {
		t.Fatal("token from previous window should be accepted within drift")
	}
}

func TestVerifyDriftPlus(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	next := now.Add(totp.Step * time.Second)
	tok, _ := totp.Generate(testSecret, next)
	ok, err := totp.Verify(testSecret, tok, now)
	if err != nil || !ok {
		t.Fatal("token from next window should be accepted within drift")
	}
}

func TestVerifyExpired(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	old := now.Add(-2 * totp.Step * time.Second)
	tok, _ := totp.Generate(testSecret, old)
	ok, err := totp.Verify(testSecret, tok, now)
	if err != nil || ok {
		t.Fatal("token from 2 windows ago should be rejected")
	}
}

func TestVerifyWrongSecret(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	tok, _ := totp.Generate(testSecret, now)
	ok, _ := totp.Verify([]byte("wrong-secret"), tok, now)
	if ok {
		t.Fatal("token with wrong secret should fail")
	}
}

func TestVerifyBadLength(t *testing.T) {
	_, err := totp.Verify(testSecret, "SHORT", time.Now())
	if err == nil {
		t.Fatal("expected error for short token")
	}
}

func TestBase32RoundTrip(t *testing.T) {
	data := []byte{0xDE, 0xAD, 0xBE, 0xEF, 0xCA, 0xFE, 0x00, 0xFF}
	enc := totp.EncodeBase32(data)
	dec, err := totp.DecodeBase32(enc)
	if err != nil {
		t.Fatal(err)
	}
	if string(dec) != string(data) {
		t.Fatalf("round-trip mismatch: %x != %x", dec, data)
	}
}

func TestBase32CaseInsensitive(t *testing.T) {
	enc := totp.EncodeBase32(testSecret)
	lower := strings.ToLower(enc)
	dec, err := totp.DecodeBase32(lower)
	if err != nil {
		t.Fatalf("lowercase decode failed: %v", err)
	}
	if string(dec) != string(testSecret) {
		t.Fatalf("case-insensitive round-trip failed")
	}
}

func TestEnrollURL(t *testing.T) {
	raw := totp.EnrollmentURL(16_777_216, testSecret)
	p, err := totp.ParseEnrollURL(raw)
	if err != nil {
		t.Fatal(err)
	}
	if p.UID != 16_777_216 {
		t.Fatalf("uid mismatch: %d", p.UID)
	}
	if string(p.Secret) != string(testSecret) {
		t.Fatal("secret mismatch after parse")
	}
}

func TestPayURL(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	raw, err := totp.PaymentURL(16_777_216, testSecret, now)
	if err != nil {
		t.Fatal(err)
	}
	p, err := totp.ParsePayURL(raw)
	if err != nil {
		t.Fatal(err)
	}
	if p.UID != 16_777_216 {
		t.Fatalf("uid mismatch: %d", p.UID)
	}
	ok, _ := totp.Verify(testSecret, p.Token, now)
	if !ok {
		t.Fatal("token from PaymentURL should verify")
	}
}

func TestSecondsRemaining(t *testing.T) {
	// window starts at 1_699_999_980, so +15 s → 15 s elapsed → 15 s remaining
	now := time.Unix(1_699_999_995, 0)
	rem := totp.SecondsRemaining(now)
	if rem != 15 {
		t.Fatalf("expected 15, got %d", rem)
	}
}

func TestEmptySecret(t *testing.T) {
	_, err := totp.GenerateNow(nil)
	if err == nil {
		t.Fatal("expected error for nil secret")
	}
}
