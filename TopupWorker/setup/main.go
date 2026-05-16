// setup: tạo float account uid=1 (bank) trong Wire server
package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"sync/atomic"
)

const (
	wireHost   = "127.0.0.1"
	wirePort   = 7474
	wireSecret = "saving_wire_secret_changeme"
	floatUID   = uint32(1)
	floatPwd   = "saving_float_changeme"

	typeCreateAccount = 0x10
	typeLoginAck      = 0x81 // reused for createAccount ack
	typeAck           = 0x82
	codeOK            = 0x00
	codeIDTaken       = 0x03
	frameOverhead     = 41
)

var seq atomic.Uint32

func main() {
	conn, err := net.Dial("tcp", fmt.Sprintf("%s:%d", wireHost, wirePort))
	if err != nil {
		log.Fatalf("connect: %v", err)
	}
	defer conn.Close()

	pwdHash := sha256.Sum256([]byte(floatPwd))
	body := make([]byte, 4+32)
	binary.BigEndian.PutUint32(body, floatUID)
	copy(body[4:], pwdHash[:])

	if err := sendFrame(conn, typeCreateAccount, body); err != nil {
		log.Fatalf("send createAccount: %v", err)
	}

	frame, err := recvFrame(conn)
	if err != nil {
		log.Fatalf("recv: %v", err)
	}
	if len(frame.body) < 1 {
		log.Fatal("empty response")
	}

	switch frame.body[0] {
	case codeOK:
		fmt.Printf("✓ Float account uid=%d created\n", floatUID)
	case codeIDTaken:
		fmt.Printf("✓ Float account uid=%d already exists\n", floatUID)
	default:
		log.Fatalf("✗ error code 0x%x", frame.body[0])
	}
}

type rawFrame struct {
	typ  uint8
	body []byte
}

func sendFrame(conn net.Conn, typ uint8, body []byte) error {
	s := seq.Add(1)
	totalLen := uint32(frameOverhead + len(body))
	raw := make([]byte, 0, totalLen)
	raw = binary.BigEndian.AppendUint32(raw, totalLen)
	raw = append(raw, typ)
	raw = binary.BigEndian.AppendUint32(raw, s)
	raw = append(raw, body...)
	mac := hmac.New(sha256.New, []byte(wireSecret))
	mac.Write(raw)
	raw = append(raw, mac.Sum(nil)...)
	_, err := conn.Write(raw)
	return err
}

func recvFrame(conn net.Conn) (*rawFrame, error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(conn, lenBuf[:]); err != nil {
		return nil, err
	}
	totalLen := binary.BigEndian.Uint32(lenBuf[:])
	rest := make([]byte, totalLen-4)
	if _, err := io.ReadFull(conn, rest); err != nil {
		return nil, err
	}
	return &rawFrame{typ: rest[0], body: rest[5 : len(rest)-32]}, nil
}
