// Package sevlet implements the Sevlet Wallet Wire binary codec.
//
// # Wire layout (big-endian)
//
//	┌──────────┬───────────┬───────────┬───────────┬───────────┬────────┬────────┬────────────┬────────┐
//	│ pad  3 B │command  8B│ mid     8B│requestId 8B│orderId  8B│debit 4B│credit 4B│ extraData │sig  32B│
//	└──────────┴───────────┴───────────┴───────────┴───────────┴────────┴────────┴────────────┴────────┘
//	           └─────────────────── HMAC-SHA256 input ──────────────────────────────────────────┘
//
// The 3-byte header padding is NOT included in the HMAC. Everything from command
// through the last byte of extraData is authenticated.
//
// Fixed head before extraData: 51 bytes (PREFIX_BEFORE_EXTRA).
// Minimum wire size (empty extraData): 51 + 32 = 83 bytes (MIN_WIRE).
//
// # Opcodes
//
//	OpTransfer       = 0  — debit → credit movement
//	OpConfirmPayment = 1  — second-phase settle for an order intent
//	OpRejectPayment  = 2  — cancel an intent
//	OpReversal       = 3  — audit-labelled reversal (same ledger shape as transfer)
//
// # Two-phase payment flow
//
//  1. Client sends TRANSFER with payment_check_order=true mid; server writes to
//     WAL only and returns a 32-byte confirm_challenge in the HTTP response body.
//  2. Client sends CONFIRM_PAYMENT with extraData = ConfirmExtra{originalRequestId, challenge}.
//     Server verifies challenge, settles to ledger.
//  3. Alternatively, client sends REJECT_PAYMENT with the same extraData to cancel.
//
// # Idempotency
//
// (mid, requestId) is the idempotency key. In order-payment mode a duplicate
// retry must carry the same orderId; a different orderId yields a 409.
package sevlet

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
)

// ─── Layout constants (mirrors SevletWalletCodec.java) ───────────────────────

const (
	headerPadLen       = 3
	sigLen             = 32
	prefixBeforeExtra  = headerPadLen + 8 + 8 + 8 + 8 + 8 + 4 + 4 // 51
	MinWire            = prefixBeforeExtra + sigLen                  // 83

	offsetCommand   = headerPadLen         // 3
	offsetMid       = offsetCommand + 8    // 11
	offsetRequestID = offsetMid + 8        // 19
	offsetOrderID   = offsetRequestID + 8  // 27
	offsetAmount    = offsetOrderID + 8    // 35
	offsetDebit     = offsetAmount + 8     // 43
	offsetCredit    = offsetDebit + 4      // 47
	offsetExtraData = prefixBeforeExtra    // 51

	// DefaultMaxExtraData mirrors ExtraDataPolicy.DEFAULT_MAX_EXTRA_DATA_BYTES.
	DefaultMaxExtraData = 262_144
)

// ─── Opcodes (mirrors WalletInputOp.java) ────────────────────────────────────

const (
	OpTransfer       uint64 = 0
	OpConfirmPayment uint64 = 1
	OpRejectPayment  uint64 = 2
	OpReversal       uint64 = 3
)

// OpName returns a human-readable label for a command opcode.
func OpName(op uint64) string {
	switch op {
	case OpTransfer:
		return "TRANSFER"
	case OpConfirmPayment:
		return "CONFIRM_PAYMENT"
	case OpRejectPayment:
		return "REJECT_PAYMENT"
	case OpReversal:
		return "REVERSAL"
	default:
		return fmt.Sprintf("UNKNOWN(%d)", op)
	}
}

// ─── Payload ──────────────────────────────────────────────────────────────────

// Payload is a decoded Sevlet wallet message. All numeric fields are unsigned
// 64-bit big-endian on the wire; Go uses uint64/uint32 throughout.
type Payload struct {
	Command   uint64
	Mid       uint64 // tenant HMAC key; for merchants equals merchant_id
	RequestID uint64 // idempotency with Mid
	OrderID   uint64 // order-payment duplicate guard
	Amount    uint64 // minor units
	Debit     uint32 // account index
	Credit    uint32 // account index
	ExtraData []byte // 0…N bytes; command-specific structure
	Sig       [sigLen]byte
}

// ─── Codec ────────────────────────────────────────────────────────────────────

// Encode serialises p to wire bytes with the pre-computed Sig field written
// as the trailing 32 bytes. Call Sign instead to compute Sig automatically.
func Encode(p Payload) []byte {
	extra := p.ExtraData
	wire := make([]byte, prefixBeforeExtra+len(extra)+sigLen)
	// 3-byte zero pad already zero from make
	binary.BigEndian.PutUint64(wire[offsetCommand:], p.Command)
	binary.BigEndian.PutUint64(wire[offsetMid:], p.Mid)
	binary.BigEndian.PutUint64(wire[offsetRequestID:], p.RequestID)
	binary.BigEndian.PutUint64(wire[offsetOrderID:], p.OrderID)
	binary.BigEndian.PutUint64(wire[offsetAmount:], p.Amount)
	binary.BigEndian.PutUint32(wire[offsetDebit:], p.Debit)
	binary.BigEndian.PutUint32(wire[offsetCredit:], p.Credit)
	copy(wire[offsetExtraData:], extra)
	copy(wire[len(wire)-sigLen:], p.Sig[:])
	return wire
}

// Decode parses a full wire buffer. The returned Payload's Sig field is set
// but NOT verified — call Verify after looking up the HMAC secret by Mid.
func Decode(wire []byte) (Payload, error) {
	if len(wire) < MinWire {
		return Payload{}, fmt.Errorf("sevlet: wire too short: need %d, got %d", MinWire, len(wire))
	}
	if wire[0] != 0 || wire[1] != 0 || wire[2] != 0 {
		return Payload{}, errors.New("sevlet: header padding must be three zero bytes")
	}
	extraEnd := len(wire) - sigLen
	var p Payload
	p.Command   = binary.BigEndian.Uint64(wire[offsetCommand:])
	p.Mid       = binary.BigEndian.Uint64(wire[offsetMid:])
	p.RequestID = binary.BigEndian.Uint64(wire[offsetRequestID:])
	p.OrderID   = binary.BigEndian.Uint64(wire[offsetOrderID:])
	p.Amount    = binary.BigEndian.Uint64(wire[offsetAmount:])
	p.Debit     = binary.BigEndian.Uint32(wire[offsetDebit:])
	p.Credit    = binary.BigEndian.Uint32(wire[offsetCredit:])
	p.ExtraData = make([]byte, extraEnd-offsetExtraData)
	copy(p.ExtraData, wire[offsetExtraData:extraEnd])
	copy(p.Sig[:], wire[extraEnd:])
	return p, nil
}

// PeekMid reads the Mid field without decoding the rest of the frame.
// Use this to look up the HMAC secret in the DB before calling Verify.
func PeekMid(wire []byte) (uint64, error) {
	if len(wire) < offsetMid+8 {
		return 0, fmt.Errorf("sevlet: wire too short to read mid (need %d, got %d)", offsetMid+8, len(wire))
	}
	return binary.BigEndian.Uint64(wire[offsetMid:]), nil
}

// signedRegion returns the slice of wire that is authenticated by HMAC:
// from command (offset 3) through the byte before the trailing sig.
func signedRegion(wire []byte) []byte {
	return wire[offsetCommand : len(wire)-sigLen]
}

// ─── Sign and Verify ──────────────────────────────────────────────────────────

// Sign encodes p (ignoring p.Sig), computes HMAC-SHA256(secret, signedRegion),
// writes the tag into the trailing 32 bytes, and returns the complete wire frame.
func Sign(p Payload, secret []byte) ([]byte, error) {
	if len(secret) == 0 {
		return nil, errors.New("sevlet: secret must not be empty")
	}
	if len(p.ExtraData) > DefaultMaxExtraData {
		return nil, fmt.Errorf("sevlet: extraData length %d exceeds max %d", len(p.ExtraData), DefaultMaxExtraData)
	}
	p.Sig = [sigLen]byte{} // clear; will be overwritten
	wire := Encode(p)
	tag := computeHMAC(secret, signedRegion(wire))
	copy(wire[len(wire)-sigLen:], tag)
	return wire, nil
}

// Verify returns true if the trailing HMAC-SHA256 tag authenticates the frame.
// Decode the wire first (to get Mid for secret lookup), then call Verify.
func Verify(wire []byte, secret []byte) (bool, error) {
	if len(wire) < MinWire {
		return false, fmt.Errorf("sevlet: wire too short: need %d, got %d", MinWire, len(wire))
	}
	if len(secret) == 0 {
		return false, errors.New("sevlet: secret must not be empty")
	}
	tag := computeHMAC(secret, signedRegion(wire))
	return hmac.Equal(tag, wire[len(wire)-sigLen:]), nil
}

func computeHMAC(key, data []byte) []byte {
	mac := hmac.New(sha256.New, key)
	mac.Write(data)
	return mac.Sum(nil)
}

// ─── ConfirmExtra — CONFIRM / REJECT extraData v0 prefix ─────────────────────

// ConfirmExtraMinLen is the minimum extraData size for CONFIRM / REJECT:
// originalRequestId (8) + challenge (32).
const ConfirmExtraMinLen = 8 + sigLen // 40

// ConfirmExtra is the v0 fixed prefix of extraData for OpConfirmPayment /
// OpRejectPayment. Bytes after the 40-byte prefix are opaque TLV extensions.
type ConfirmExtra struct {
	OriginalRequestID uint64
	Challenge         [sigLen]byte // 32-byte value must match stored intent
	Tail              []byte       // optional application TLV; may be nil
}

// EncodeConfirmExtra serialises a ConfirmExtra to bytes for use as ExtraData.
func EncodeConfirmExtra(ce ConfirmExtra) []byte {
	out := make([]byte, ConfirmExtraMinLen+len(ce.Tail))
	binary.BigEndian.PutUint64(out[0:], ce.OriginalRequestID)
	copy(out[8:], ce.Challenge[:])
	copy(out[ConfirmExtraMinLen:], ce.Tail)
	return out
}

// DecodeConfirmExtra parses the fixed v0 prefix from extraData.
func DecodeConfirmExtra(extra []byte) (ConfirmExtra, error) {
	if len(extra) < ConfirmExtraMinLen {
		return ConfirmExtra{}, fmt.Errorf("sevlet: confirm extraData needs %d bytes, got %d",
			ConfirmExtraMinLen, len(extra))
	}
	var ce ConfirmExtra
	ce.OriginalRequestID = binary.BigEndian.Uint64(extra[0:8])
	copy(ce.Challenge[:], extra[8:40])
	if len(extra) > ConfirmExtraMinLen {
		ce.Tail = make([]byte, len(extra)-ConfirmExtraMinLen)
		copy(ce.Tail, extra[ConfirmExtraMinLen:])
	}
	return ce, nil
}

// ─── Client helpers ───────────────────────────────────────────────────────────

// NewTransfer builds and signs a TRANSFER frame.
func NewTransfer(mid, requestID, orderID, amount uint64, debit, credit uint32, extra []byte, secret []byte) ([]byte, error) {
	return Sign(Payload{
		Command:   OpTransfer,
		Mid:       mid,
		RequestID: requestID,
		OrderID:   orderID,
		Amount:    amount,
		Debit:     debit,
		Credit:    credit,
		ExtraData: extra,
	}, secret)
}

// NewReversal builds and signs a REVERSAL frame (same ledger shape as TRANSFER,
// distinct opcode for audit/reconciliation).
func NewReversal(mid, requestID, orderID, amount uint64, debit, credit uint32, extra []byte, secret []byte) ([]byte, error) {
	return Sign(Payload{
		Command:   OpReversal,
		Mid:       mid,
		RequestID: requestID,
		OrderID:   orderID,
		Amount:    amount,
		Debit:     debit,
		Credit:    credit,
		ExtraData: extra,
	}, secret)
}

// NewConfirm builds and signs a CONFIRM_PAYMENT frame.
// amount/debit/credit carry the settlement values for the ledger (typically
// echoed from the original intent, but may differ for partial fulfilment).
func NewConfirm(mid, requestID, orderID, amount uint64, debit, credit uint32, ce ConfirmExtra, secret []byte) ([]byte, error) {
	return Sign(Payload{
		Command:   OpConfirmPayment,
		Mid:       mid,
		RequestID: requestID,
		OrderID:   orderID,
		Amount:    amount,
		Debit:     debit,
		Credit:    credit,
		ExtraData: EncodeConfirmExtra(ce),
	}, secret)
}

// NewReject builds and signs a REJECT_PAYMENT frame.
func NewReject(mid, requestID, orderID, amount uint64, debit, credit uint32, ce ConfirmExtra, secret []byte) ([]byte, error) {
	return Sign(Payload{
		Command:   OpRejectPayment,
		Mid:       mid,
		RequestID: requestID,
		OrderID:   orderID,
		Amount:    amount,
		Debit:     debit,
		Credit:    credit,
		ExtraData: EncodeConfirmExtra(ce),
	}, secret)
}

// ExtraDataLength returns the number of extraData bytes given a total wire size.
func ExtraDataLength(totalWire int) (int, error) {
	if totalWire < MinWire {
		return 0, fmt.Errorf("sevlet: wire %d < MinWire %d", totalWire, MinWire)
	}
	return totalWire - prefixBeforeExtra - sigLen, nil
}
