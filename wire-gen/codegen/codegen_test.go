package codegen

import (
	"strings"
	"testing"

	"github.com/nivic/wire-gen/schema"
)

// minimal schema: fixed fields only, no var/trailing
const simpleSchema = `
protocol Payment {
    pad    bytes(3)   [skip]
    mid    u64
    amount u64
    debit  u32
    credit u16
    flags  u8
}
`

// full schema: skip + var + trailing (mirrors sevlet_wallet)
const fullSchema = `
protocol SevletWallet {
    pad        bytes(3)  [skip]
    command    u64
    mid        u64
    request_id u64
    order_id   u64
    amount     u64
    debit      u32
    credit     u32
    extra_data bytes(*)  [var]
    sig        bytes(32) [trailing]
}
`

// multi-trailing schema: two trailing fields
const multiTrailingSchema = `
protocol Frame {
    hdr  u32
    body bytes(*) [var]
    tag  u8
    mac  bytes(16)
}
`

func mustParse(t *testing.T, src string) *schema.Protocol {
	t.Helper()
	p, err := schema.Parse(strings.NewReader(src))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	return p
}

func gen(t *testing.T, fn func(*strings.Builder)) string {
	t.Helper()
	var b strings.Builder
	fn(&b)
	return b.String()
}

func assertContains(t *testing.T, out, want string) {
	t.Helper()
	if !strings.Contains(out, want) {
		t.Errorf("output missing %q\n\nfull output:\n%s", want, out)
	}
}

func assertNotContains(t *testing.T, out, want string) {
	t.Helper()
	if strings.Contains(out, want) {
		t.Errorf("output should not contain %q", want)
	}
}

// ── C ────────────────────────────────────────────────────────────────────────

func TestC_Simple(t *testing.T) {
	p := mustParse(t, simpleSchema)
	out := gen(t, func(b *strings.Builder) {
		if err := GenerateC(p, b); err != nil {
			t.Fatal(err)
		}
	})

	assertContains(t, out, "#pragma once")
	assertContains(t, out, "#include <stdint.h>")
	assertContains(t, out, "PAYMENT_OFFSET_MID")
	assertContains(t, out, "PAYMENT_OFFSET_AMOUNT")
	assertContains(t, out, "PAYMENT_MIN_WIRE_LEN")
	assertContains(t, out, "uint64_t     mid")
	assertContains(t, out, "uint64_t     amount")
	assertContains(t, out, "uint32_t     debit")
	assertContains(t, out, "uint16_t     credit")
	assertContains(t, out, "uint8_t      flags")
	assertContains(t, out, "static inline int payment_decode(")
	assertContains(t, out, "return 0;")
	// skip field must not appear in struct
	assertNotContains(t, out, "pad;")
	// no var constants for fixed-only schema
	assertNotContains(t, out, "PREFIX_LEN")
}

func TestC_Full(t *testing.T) {
	p := mustParse(t, fullSchema)
	out := gen(t, func(b *strings.Builder) {
		if err := GenerateC(p, b); err != nil {
			t.Fatal(err)
		}
	})

	assertContains(t, out, "SEVLETWALLET_PREFIX_LEN")
	assertContains(t, out, "SEVLETWALLET_TRAILING_LEN")
	assertContains(t, out, "SEVLETWALLET_MIN_WIRE_LEN        83")
	assertContains(t, out, "const uint8_t *extra_data")
	assertContains(t, out, "extra_data_len")
	assertContains(t, out, "const uint8_t * sig")
	// trailing offset: relative 0 → no hardcoded +51
	assertContains(t, out, "out->sig = buf + (len - SEVLETWALLET_TRAILING_LEN);")
	assertNotContains(t, out, "TRAILING_LEN + 51")
}

func TestC_MultiTrailing(t *testing.T) {
	p := mustParse(t, multiTrailingSchema)
	out := gen(t, func(b *strings.Builder) {
		if err := GenerateC(p, b); err != nil {
			t.Fatal(err)
		}
	})

	// tag: relative 0, mac: relative 1
	assertContains(t, out, "out->tag = buf + (len - FRAME_TRAILING_LEN);")
	assertContains(t, out, "out->mac = buf + (len - FRAME_TRAILING_LEN + 1);")
}

// ── Java ─────────────────────────────────────────────────────────────────────

func TestJava_Simple(t *testing.T) {
	p := mustParse(t, simpleSchema)
	out := gen(t, func(b *strings.Builder) {
		if err := GenerateJava(p, "app.saving.wire", b); err != nil {
			t.Fatal(err)
		}
	})

	assertContains(t, out, "package app.saving.wire;")
	assertContains(t, out, "public final class PaymentCodec")
	assertContains(t, out, "public static final int OFFSET_MID")
	assertContains(t, out, "public static final int MIN_WIRE_LEN")
	assertContains(t, out, "public record Decoded(")
	assertContains(t, out, "long mid")
	assertContains(t, out, "long amount")
	assertContains(t, out, "int debit")
	assertContains(t, out, "int credit")
	assertContains(t, out, "int flags")
	assertContains(t, out, "public static Decoded decode(byte[] raw)")
	assertContains(t, out, "readLong(raw, OFFSET_MID)")
	assertContains(t, out, "readInt(raw, OFFSET_DEBIT)")
	assertContains(t, out, "readShort(raw, OFFSET_CREDIT)")
	assertContains(t, out, "raw[OFFSET_FLAGS] & 0xFF")
	// peekMid generated for u64 field containing "mid"
	assertContains(t, out, "public static long peekMid(")
}

func TestJava_Full(t *testing.T) {
	p := mustParse(t, fullSchema)
	out := gen(t, func(b *strings.Builder) {
		if err := GenerateJava(p, "", b); err != nil {
			t.Fatal(err)
		}
	})

	assertContains(t, out, "PREFIX_LEN")
	assertContains(t, out, "TRAILING_LEN")
	// record: extraData before sig
	extraIdx := strings.Index(out, "extraData")
	sigIdx := strings.Index(out, "byte[] sig")
	if extraIdx < 0 || sigIdx < 0 || extraIdx > sigIdx {
		t.Error("record: extraData must appear before sig")
	}
	// trailing read: no hardcoded offset
	assertContains(t, out, "raw.length - TRAILING_LEN, raw.length - TRAILING_LEN + 32")
	assertNotContains(t, out, "TRAILING_LEN + 51")
	// var read
	assertContains(t, out, "Arrays.copyOfRange(raw, PREFIX_LEN, raw.length - TRAILING_LEN)")
}

func TestJava_NoPackage(t *testing.T) {
	p := mustParse(t, simpleSchema)
	out := gen(t, func(b *strings.Builder) {
		if err := GenerateJava(p, "", b); err != nil {
			t.Fatal(err)
		}
	})
	assertNotContains(t, out, "package ;")
}

// ── Kotlin ───────────────────────────────────────────────────────────────────

func TestKotlin_Simple(t *testing.T) {
	p := mustParse(t, simpleSchema)
	out := gen(t, func(b *strings.Builder) {
		if err := GenerateKotlin(p, "app.saving.wire", b); err != nil {
			t.Fatal(err)
		}
	})

	assertContains(t, out, "package app.saving.wire")
	assertContains(t, out, "object PaymentProto")
	assertContains(t, out, "const val OFFSET_MID")
	assertContains(t, out, "const val MIN_WIRE_LEN")
	assertContains(t, out, "fun peekMid(")
	assertContains(t, out, "fun decode(raw: ByteArray): Payment")
	assertContains(t, out, "val mid = readLong(raw, OFFSET_MID)")
	assertContains(t, out, "val debit = readInt(raw, OFFSET_DEBIT)")
	assertContains(t, out, "val credit = readShort(raw, OFFSET_CREDIT)")
	assertContains(t, out, "val flags = raw[OFFSET_FLAGS].toInt() and 0xFF")
	assertContains(t, out, "data class Payment(")
	assertContains(t, out, "val mid: Long")
	assertContains(t, out, "val debit: Int")
}

func TestKotlin_Full(t *testing.T) {
	p := mustParse(t, fullSchema)
	out := gen(t, func(b *strings.Builder) {
		if err := GenerateKotlin(p, "", b); err != nil {
			t.Fatal(err)
		}
	})

	// data class: extraData before sig
	extraIdx := strings.Index(out, "val extraData")
	sigIdx := strings.Index(out, "val sig")
	if extraIdx < 0 || sigIdx < 0 || extraIdx > sigIdx {
		t.Error("data class: extraData must appear before sig")
	}
	// trailing read: relative offset 0
	assertContains(t, out, "raw.size - TRAILING_LEN, raw.size - TRAILING_LEN + 32")
	assertNotContains(t, out, "TRAILING_LEN + 51")
	// var read
	assertContains(t, out, "raw.copyOfRange(PREFIX_LEN, raw.size - TRAILING_LEN)")
}

// ── Swift ────────────────────────────────────────────────────────────────────

func TestSwift_Simple(t *testing.T) {
	p := mustParse(t, simpleSchema)
	out := gen(t, func(b *strings.Builder) {
		if err := GenerateSwift(p, b); err != nil {
			t.Fatal(err)
		}
	})

	assertContains(t, out, "import Foundation")
	assertContains(t, out, "enum PaymentOffset")
	assertContains(t, out, "static let mid")
	assertContains(t, out, "enum PaymentLen")
	assertContains(t, out, "static let minWireLen")
	assertContains(t, out, "struct Payment {")
	assertContains(t, out, "let mid: UInt64")
	assertContains(t, out, "let amount: UInt64")
	assertContains(t, out, "let debit: UInt32")
	assertContains(t, out, "let credit: UInt16")
	assertContains(t, out, "let flags: UInt8")
	assertContains(t, out, "static func decode(_ raw: Data) throws -> Payment")
	assertContains(t, out, "throw WireError.frameTooShort")
	assertContains(t, out, "static func peekMid(")
	assertContains(t, out, "enum WireError: Error")
}

func TestSwift_Full(t *testing.T) {
	p := mustParse(t, fullSchema)
	out := gen(t, func(b *strings.Builder) {
		if err := GenerateSwift(p, b); err != nil {
			t.Fatal(err)
		}
	})

	// struct field order: "    let extraData" before "    let sig: Data"
	// use indented form to avoid matching "static let sigLen" in constants
	extraIdx := strings.Index(out, "    let extraData")
	sigIdx := strings.Index(out, "    let sig: Data")
	if extraIdx < 0 || sigIdx < 0 || extraIdx > sigIdx {
		t.Errorf("struct: extraData (idx %d) must appear before sig (idx %d)", extraIdx, sigIdx)
	}
	// trailing read: relative offset 0
	assertContains(t, out, "b.count - SevletWalletLen.trailingLen) ..< (b.count - SevletWalletLen.trailingLen) + 32")
	assertNotContains(t, out, "trailingLen + 51")
	// var read
	assertContains(t, out, "SevletWalletLen.prefixLen ..< b.count - SevletWalletLen.trailingLen")
}

// ── Go ───────────────────────────────────────────────────────────────────────

func TestGo_Simple(t *testing.T) {
	p := mustParse(t, simpleSchema)
	out := gen(t, func(b *strings.Builder) {
		if err := GenerateGo(p, "wire", b); err != nil {
			t.Fatal(err)
		}
	})

	assertContains(t, out, "package wire")
	assertContains(t, out, "import")
	assertContains(t, out, "encoding/binary")
	assertContains(t, out, "OffsetMid")
	assertContains(t, out, "MinWireLen")
	assertContains(t, out, "type Payment struct {")
	assertContains(t, out, "Mid                  uint64")
	assertContains(t, out, "Amount               uint64")
	assertContains(t, out, "Debit                uint32")
	assertContains(t, out, "Credit               uint16")
	assertContains(t, out, "Flags                byte")
	assertContains(t, out, "func Decode(raw []byte) (Payment, error)")
	assertContains(t, out, "binary.BigEndian.Uint64(raw[OffsetMid:])")
	assertContains(t, out, "func PeekMid(raw []byte) uint64")
	assertContains(t, out, "func (f Payment) Encode() []byte")
}

func TestGo_Full(t *testing.T) {
	p := mustParse(t, fullSchema)
	out := gen(t, func(b *strings.Builder) {
		if err := GenerateGo(p, "app.saving.wire", b); err != nil {
			t.Fatal(err)
		}
	})

	// package name: last dot-segment
	assertContains(t, out, "package wire")
	assertNotContains(t, out, "package app.saving.wire")

	// struct field order: "\tExtraData" before "\tSig"
	// use tab-indented form to avoid matching "SigLen" in constants
	extraIdx := strings.Index(out, "\tExtraData")
	sigIdx := strings.Index(out, "\tSig ")
	if extraIdx < 0 || sigIdx < 0 || extraIdx > sigIdx {
		t.Errorf("struct: ExtraData (idx %d) must appear before Sig (idx %d)", extraIdx, sigIdx)
	}
	// trailing read: relative offset 0 → no +N
	assertContains(t, out, "raw[len(raw)-TrailingLen:]")
	assertNotContains(t, out, "TrailingLen+51")
	assertNotContains(t, out, "TrailingLen + 51")
	// var read
	assertContains(t, out, "raw[PrefixLen:len(raw)-TrailingLen]")
}

func TestGo_PackageName(t *testing.T) {
	cases := []struct {
		pkg  string
		want string
	}{
		{"", "sevletwallet"},
		{"wire", "wire"},
		{"app.saving.wire", "wire"},
		{"com.example.proto", "proto"},
	}
	p := mustParse(t, fullSchema)
	for _, c := range cases {
		got := goPackageName(c.pkg, p.Name)
		if got != c.want {
			t.Errorf("goPackageName(%q, %q) = %q, want %q", c.pkg, p.Name, got, c.want)
		}
	}
}
