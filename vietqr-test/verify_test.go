package main

import (
	"fmt"
	"testing"
)

// Known-good VietQR payload from NAPAS developer docs — CRC must be 1D97
// Source: https://vietqr.io spec test vector
func TestCRC(t *testing.T) {
	// Test vector from EMV QRCPS spec: "123456789" → CRC16/CCITT-FALSE = 0x29B1
	data := []byte("123456789")
	got := crc16(data)
	want := uint16(0x29B1)
	if got != want {
		t.Errorf("CRC16 of '123456789' = %04X, want %04X", got, want)
	} else {
		fmt.Printf("CRC16 test vector OK: %04X\n", got)
	}
}

// Verify the payload we generated is self-consistent: strip last 4 chars (CRC value),
// recompute, check they match
func TestSelfConsistency(t *testing.T) {
	full := "00020101021238490010A0000007270108QRIBFTTA02069704410309938314061520459995303704540410005802VN6008Viet Nam62130809test gara63049B8B"
	
	// everything except last 4 hex chars is the CRC input
	payload := full[:len(full)-4]
	crcValue := full[len(full)-4:]
	
	computed := crc16([]byte(payload))
	expected := fmt.Sprintf("%04X", computed)
	
	fmt.Printf("Payload:          %s\n", payload)
	fmt.Printf("CRC in QR:        %s\n", crcValue)
	fmt.Printf("CRC recomputed:   %s\n", expected)
	
	if expected != crcValue {
		t.Errorf("CRC mismatch: got %s, in payload %s", expected, crcValue)
	} else {
		fmt.Println("CRC self-consistent ✓")
	}
}
