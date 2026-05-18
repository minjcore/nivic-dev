package main

import (
	"encoding/binary"
	"fmt"
	"math/rand"
	"net"
	"strings"
	"time"
)

// ISOResult is returned after a successful authorization.
type ISOResult struct {
	AuthCode string // DE38
	RC       string // DE39  "00" = approved
}

// ISO8583Client sends a single 0200 financial transaction to the bank gateway.
// Uses the same ASCII-encoded ISO 8583 format as the C bank-gateway server:
//
//	[2B len][4B MTI][8B bitmap][fields...]
type ISO8583Client struct {
	Addr string // e.g. "127.0.0.1:8095"
}

// Purchase sends a 0200 debit against the given card and returns the result.
func (c *ISO8583Client) Purchase(pan string, expiryMM, expiryYY int, amount uint64, uid uint32) (*ISOResult, error) {
	conn, err := net.DialTimeout("tcp", c.Addr, 5*time.Second)
	if err != nil {
		return nil, fmt.Errorf("connect bank-gateway: %w", err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(10 * time.Second))

	msg := buildPurchase(pan, expiryMM, expiryYY, amount, uid)
	framed := frame(msg)
	if _, err := conn.Write(framed); err != nil {
		return nil, fmt.Errorf("send: %w", err)
	}

	resp, err := readFrame(conn)
	if err != nil {
		return nil, fmt.Errorf("recv: %w", err)
	}
	return parseResponse(resp)
}

// ── Build 0200 ───────────────────────────────────────────────────────────────

func buildPurchase(pan string, mm, yy int, amount uint64, uid uint32) []byte {
	now := time.Now().UTC()
	stan := fmt.Sprintf("%06d", rand.Intn(1000000))

	// DE values
	de := map[int]string{
		2:  pan,
		3:  "000000", // purchase
		4:  fmt.Sprintf("%012d", amount),
		7:  now.Format("0102150405"), // MMDDhhmmss
		11: stan,
		12: now.Format("150405"), // hhmmss
		13: now.Format("0102"),   // MMDD
		41: "SAVINGWL",           // terminal ID (8 chars)
		42: fmt.Sprintf("%-15d", uid), // merchant/card-owner ID (15 chars)
		49: "704",                // VND
	}
	_ = mm
	_ = yy

	var buf []byte

	// MTI
	buf = append(buf, []byte("0200")...)

	// Build bitmap and field bytes separately, then combine
	bm := make([]byte, 8)
	var fields []byte

	for _, deNum := range []int{2, 3, 4, 7, 11, 12, 13, 41, 42, 49} {
		val, ok := de[deNum]
		if !ok {
			continue
		}
		setBit(bm, deNum)

		switch deNum {
		case 2: // LLVAR
			fields = append(fields, []byte(fmt.Sprintf("%02d", len(val)))...)
			fields = append(fields, []byte(val)...)
		default: // fixed
			fields = append(fields, []byte(val)...)
		}
	}

	buf = append(buf, bm...)
	buf = append(buf, fields...)
	return buf
}

// ── Parse response ───────────────────────────────────────────────────────────

func parseResponse(data []byte) (*ISOResult, error) {
	if len(data) < 4+8 {
		return nil, fmt.Errorf("response too short (%d bytes)", len(data))
	}
	mti := string(data[:4])
	if !strings.HasPrefix(mti, "02") {
		return nil, fmt.Errorf("unexpected MTI %s", mti)
	}

	bm := data[4:12]
	pos := 12

	res := &ISOResult{}

	// DE field widths matching bank-gateway defs
	fixedLen := map[int]int{
		3: 6, 4: 12, 7: 10, 11: 6, 12: 6, 13: 4,
		37: 12, 38: 6, 39: 2, 41: 8, 42: 15, 49: 3, 54: 20,
	}

	for de := 2; de <= 64; de++ {
		if !testBit(bm, de) {
			continue
		}
		var flen int
		if de == 2 { // LLVAR
			if pos+2 > len(data) {
				break
			}
			flen = 0
			fmt.Sscanf(string(data[pos:pos+2]), "%d", &flen)
			pos += 2
		} else {
			flen = fixedLen[de]
			if flen == 0 {
				break // unknown — can't advance safely
			}
		}
		if pos+flen > len(data) {
			break
		}
		val := strings.TrimSpace(string(data[pos : pos+flen]))
		switch de {
		case 38:
			res.AuthCode = val
		case 39:
			res.RC = val
		}
		pos += flen
	}

	if res.RC == "" {
		return nil, fmt.Errorf("DE39 missing in response")
	}
	return res, nil
}

// ── Bitmap helpers ───────────────────────────────────────────────────────────

func setBit(bm []byte, de int) {
	bit := de - 1
	bm[bit/8] |= 1 << (7 - bit%8)
}

func testBit(bm []byte, de int) bool {
	bit := de - 1
	if bit/8 >= len(bm) {
		return false
	}
	return bm[bit/8]&(1<<(7-bit%8)) != 0
}

// ── TCP framing ──────────────────────────────────────────────────────────────

func frame(msg []byte) []byte {
	hdr := make([]byte, 2)
	binary.BigEndian.PutUint16(hdr, uint16(len(msg)))
	return append(hdr, msg...)
}

func readFrame(conn net.Conn) ([]byte, error) {
	hdr := make([]byte, 2)
	if _, err := readFull(conn, hdr); err != nil {
		return nil, err
	}
	mlen := int(binary.BigEndian.Uint16(hdr))
	if mlen <= 0 || mlen > 8192 {
		return nil, fmt.Errorf("invalid frame length %d", mlen)
	}
	buf := make([]byte, mlen)
	if _, err := readFull(conn, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

func readFull(conn net.Conn, buf []byte) (int, error) {
	total := 0
	for total < len(buf) {
		n, err := conn.Read(buf[total:])
		total += n
		if err != nil {
			return total, err
		}
	}
	return total, nil
}
