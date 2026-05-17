package sevlet_test

import (
	"bytes"
	"encoding/binary"
	"testing"

	"nivic.dev/saving/sdk/sevlet"
)

var (
	secret    = []byte("test-hmac-secret-32-bytes-padding")
	testMid   = uint64(1001)
	testReqID = uint64(42)
	testOrdID = uint64(7)
	testAmt   = uint64(100_000) // 100,000 minor units
	testDebit = uint32(1)
	testCredit = uint32(2)
)

// ─── Layout ───────────────────────────────────────────────────────────────────

func TestMinWireSize(t *testing.T) {
	// 3 pad + 8 cmd + 8 mid + 8 rid + 8 oid + 8 amt + 4 deb + 4 crd + 32 sig = 83
	if sevlet.MinWire != 83 {
		t.Fatalf("MinWire = %d, want 83", sevlet.MinWire)
	}
}

func TestEncodeLength(t *testing.T) {
	wire, err := sevlet.NewTransfer(testMid, testReqID, testOrdID, testAmt, testDebit, testCredit, nil, secret)
	if err != nil {
		t.Fatal(err)
	}
	if len(wire) != sevlet.MinWire {
		t.Fatalf("empty-extra wire len = %d, want %d", len(wire), sevlet.MinWire)
	}
}

func TestHeaderPadZero(t *testing.T) {
	wire, _ := sevlet.NewTransfer(testMid, testReqID, testOrdID, testAmt, testDebit, testCredit, nil, secret)
	if wire[0] != 0 || wire[1] != 0 || wire[2] != 0 {
		t.Fatal("first 3 bytes must be zero padding")
	}
}

// ─── Encode / Decode round-trip ───────────────────────────────────────────────

func TestRoundTrip(t *testing.T) {
	extra := []byte("hello-extra")
	wire, err := sevlet.Sign(sevlet.Payload{
		Command:   sevlet.OpTransfer,
		Mid:       testMid,
		RequestID: testReqID,
		OrderID:   testOrdID,
		Amount:    testAmt,
		Debit:     testDebit,
		Credit:    testCredit,
		ExtraData: extra,
	}, secret)
	if err != nil {
		t.Fatal(err)
	}
	p, err := sevlet.Decode(wire)
	if err != nil {
		t.Fatal(err)
	}
	if p.Command != sevlet.OpTransfer { t.Errorf("command mismatch") }
	if p.Mid != testMid               { t.Errorf("mid mismatch") }
	if p.RequestID != testReqID       { t.Errorf("requestID mismatch") }
	if p.OrderID != testOrdID         { t.Errorf("orderID mismatch") }
	if p.Amount != testAmt            { t.Errorf("amount mismatch") }
	if p.Debit != testDebit           { t.Errorf("debit mismatch") }
	if p.Credit != testCredit         { t.Errorf("credit mismatch") }
	if !bytes.Equal(p.ExtraData, extra) { t.Errorf("extraData mismatch") }
}

func TestRoundTripEmptyExtra(t *testing.T) {
	wire, _ := sevlet.NewTransfer(testMid, testReqID, testOrdID, testAmt, testDebit, testCredit, nil, secret)
	p, err := sevlet.Decode(wire)
	if err != nil {
		t.Fatal(err)
	}
	if len(p.ExtraData) != 0 {
		t.Fatalf("expected empty extraData, got %d bytes", len(p.ExtraData))
	}
}

// ─── HMAC verify ──────────────────────────────────────────────────────────────

func TestVerifyValid(t *testing.T) {
	wire, _ := sevlet.NewTransfer(testMid, testReqID, testOrdID, testAmt, testDebit, testCredit, nil, secret)
	ok, err := sevlet.Verify(wire, secret)
	if err != nil || !ok {
		t.Fatalf("expected valid HMAC, err=%v ok=%v", err, ok)
	}
}

func TestVerifyWrongSecret(t *testing.T) {
	wire, _ := sevlet.NewTransfer(testMid, testReqID, testOrdID, testAmt, testDebit, testCredit, nil, secret)
	ok, _ := sevlet.Verify(wire, []byte("wrong-secret"))
	if ok {
		t.Fatal("expected HMAC failure with wrong secret")
	}
}

func TestVerifyTamperedAmount(t *testing.T) {
	wire, _ := sevlet.NewTransfer(testMid, testReqID, testOrdID, testAmt, testDebit, testCredit, nil, secret)
	// flip a bit in the amount field (offset 35)
	wire[35] ^= 0xff
	ok, _ := sevlet.Verify(wire, secret)
	if ok {
		t.Fatal("tampered wire should fail HMAC")
	}
}

func TestVerifyTamperedPadDoesNotAffectHMAC(t *testing.T) {
	wire, _ := sevlet.NewTransfer(testMid, testReqID, testOrdID, testAmt, testDebit, testCredit, nil, secret)
	// The 3-byte pad is NOT in HMAC input; modifying it must not invalidate sig.
	wire[0] = 0xff
	ok, err := sevlet.Verify(wire, secret)
	if err != nil || !ok {
		t.Fatal("header pad is unauthenticated; tampering it should not break HMAC")
	}
}

// ─── PeekMid ──────────────────────────────────────────────────────────────────

func TestPeekMid(t *testing.T) {
	wire, _ := sevlet.NewTransfer(testMid, testReqID, testOrdID, testAmt, testDebit, testCredit, nil, secret)
	mid, err := sevlet.PeekMid(wire)
	if err != nil || mid != testMid {
		t.Fatalf("PeekMid = %d err=%v, want %d", mid, err, testMid)
	}
}

// ─── Opcodes ──────────────────────────────────────────────────────────────────

func TestOpcodeConstants(t *testing.T) {
	if sevlet.OpTransfer != 0       { t.Error("OpTransfer must be 0") }
	if sevlet.OpConfirmPayment != 1 { t.Error("OpConfirmPayment must be 1") }
	if sevlet.OpRejectPayment != 2  { t.Error("OpRejectPayment must be 2") }
	if sevlet.OpReversal != 3       { t.Error("OpReversal must be 3") }
}

func TestOpNameKnown(t *testing.T) {
	cases := map[uint64]string{
		sevlet.OpTransfer:       "TRANSFER",
		sevlet.OpConfirmPayment: "CONFIRM_PAYMENT",
		sevlet.OpRejectPayment:  "REJECT_PAYMENT",
		sevlet.OpReversal:       "REVERSAL",
	}
	for op, want := range cases {
		if got := sevlet.OpName(op); got != want {
			t.Errorf("OpName(%d) = %q, want %q", op, got, want)
		}
	}
}

func TestOpNameUnknown(t *testing.T) {
	if sevlet.OpName(99) == "" {
		t.Error("OpName for unknown op should return non-empty string")
	}
}

// ─── ConfirmExtra ─────────────────────────────────────────────────────────────

func TestConfirmExtraRoundTrip(t *testing.T) {
	var challenge [32]byte
	for i := range challenge { challenge[i] = byte(i) }
	ce := sevlet.ConfirmExtra{
		OriginalRequestID: 999,
		Challenge:         challenge,
		Tail:              []byte{0xDE, 0xAD},
	}
	encoded := sevlet.EncodeConfirmExtra(ce)
	if len(encoded) != sevlet.ConfirmExtraMinLen+2 {
		t.Fatalf("encoded len = %d, want %d", len(encoded), sevlet.ConfirmExtraMinLen+2)
	}
	got, err := sevlet.DecodeConfirmExtra(encoded)
	if err != nil {
		t.Fatal(err)
	}
	if got.OriginalRequestID != ce.OriginalRequestID {
		t.Error("OriginalRequestID mismatch")
	}
	if got.Challenge != ce.Challenge {
		t.Error("Challenge mismatch")
	}
	if !bytes.Equal(got.Tail, ce.Tail) {
		t.Error("Tail mismatch")
	}
}

func TestConfirmExtraTooShort(t *testing.T) {
	_, err := sevlet.DecodeConfirmExtra([]byte{1, 2, 3})
	if err == nil {
		t.Fatal("expected error for too-short extraData")
	}
}

func TestNewConfirmSetsOpcode(t *testing.T) {
	var challenge [32]byte
	ce := sevlet.ConfirmExtra{OriginalRequestID: testReqID, Challenge: challenge}
	wire, err := sevlet.NewConfirm(testMid, testReqID+1, testOrdID, testAmt, testDebit, testCredit, ce, secret)
	if err != nil {
		t.Fatal(err)
	}
	p, _ := sevlet.Decode(wire)
	if p.Command != sevlet.OpConfirmPayment {
		t.Errorf("command = %d, want OpConfirmPayment", p.Command)
	}
	decoded, _ := sevlet.DecodeConfirmExtra(p.ExtraData)
	if decoded.OriginalRequestID != testReqID {
		t.Error("OriginalRequestID not preserved in CONFIRM extraData")
	}
}

func TestNewRejectSetsOpcode(t *testing.T) {
	var challenge [32]byte
	ce := sevlet.ConfirmExtra{OriginalRequestID: testReqID, Challenge: challenge}
	wire, err := sevlet.NewReject(testMid, testReqID+1, testOrdID, testAmt, testDebit, testCredit, ce, secret)
	if err != nil {
		t.Fatal(err)
	}
	p, _ := sevlet.Decode(wire)
	if p.Command != sevlet.OpRejectPayment {
		t.Errorf("command = %d, want OpRejectPayment", p.Command)
	}
}

// ─── Error paths ──────────────────────────────────────────────────────────────

func TestDecodeTooShort(t *testing.T) {
	_, err := sevlet.Decode(make([]byte, sevlet.MinWire-1))
	if err == nil {
		t.Fatal("expected error for too-short wire")
	}
}

func TestDecodeNonZeroPad(t *testing.T) {
	wire, _ := sevlet.NewTransfer(testMid, testReqID, testOrdID, testAmt, testDebit, testCredit, nil, secret)
	wire[0] = 1 // break pad check
	// Decode checks padding; Verify does not re-check pad
	_, err := sevlet.Decode(wire)
	if err == nil {
		t.Fatal("expected error for non-zero pad byte")
	}
}

func TestSignEmptySecret(t *testing.T) {
	_, err := sevlet.Sign(sevlet.Payload{}, nil)
	if err == nil {
		t.Fatal("expected error for nil secret")
	}
}

func TestExtraDataTooLarge(t *testing.T) {
	_, err := sevlet.Sign(sevlet.Payload{
		ExtraData: make([]byte, sevlet.DefaultMaxExtraData+1),
	}, secret)
	if err == nil {
		t.Fatal("expected error when extraData exceeds max")
	}
}

func TestPeekMidTooShort(t *testing.T) {
	_, err := sevlet.PeekMid(make([]byte, 10))
	if err == nil {
		t.Fatal("expected error for short wire in PeekMid")
	}
}

func TestExtraDataLength(t *testing.T) {
	n, err := sevlet.ExtraDataLength(sevlet.MinWire + 10)
	if err != nil || n != 10 {
		t.Fatalf("ExtraDataLength = %d err=%v, want 10", n, err)
	}
}

// ─── Cross-check with reference values ───────────────────────────────────────
// Verify field offsets match the Java SevletWalletCodec constants.

func TestFieldOffsets(t *testing.T) {
	wire, _ := sevlet.NewTransfer(testMid, testReqID, testOrdID, testAmt, testDebit, testCredit, nil, secret)
	if binary.BigEndian.Uint64(wire[3:11]) != sevlet.OpTransfer {
		t.Error("command not at offset 3")
	}
	if binary.BigEndian.Uint64(wire[11:19]) != testMid {
		t.Error("mid not at offset 11")
	}
	if binary.BigEndian.Uint64(wire[19:27]) != testReqID {
		t.Error("requestId not at offset 19")
	}
	if binary.BigEndian.Uint64(wire[27:35]) != testOrdID {
		t.Error("orderId not at offset 27")
	}
	if binary.BigEndian.Uint64(wire[35:43]) != testAmt {
		t.Error("amount not at offset 35")
	}
	if binary.BigEndian.Uint32(wire[43:47]) != testDebit {
		t.Error("debit not at offset 43")
	}
	if binary.BigEndian.Uint32(wire[47:51]) != testCredit {
		t.Error("credit not at offset 47")
	}
}
