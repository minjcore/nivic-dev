package main

import (
	"encoding/binary"
	"fmt"
	"log/slog"
	"os"
	"time"

	"github.com/fluxorio/fluxor/pkg/core"
)

// WireVerticle maintains a persistent Wire TCP connection to the C server (:7474).
// It runs on the EventLoop like any other Verticle.
//
// Push events from the server are forwarded to the EventBus:
//   saving.transfer_in  — EVT_TRANSFER_IN  body: map[from,amount,balance uint64]
//   saving.intent_paid  — EVT_INTENT_PAID  body: map[request_id,customer_id,amount uint64]
//
// Other Verticles (e.g. GatewayVerticle) call WireVerticle via EventBus:
//   saving.wire.login    — {uid, password}  → {token, error}
//   saving.wire.balance  — {token}          → {balance, pending, available}
//   saving.wire.transfer — {token,to,amount}→ {after_balance, error}
type WireVerticle struct {
	*core.BaseVerticle
	addr   string
	secret []byte
	client *WireClient
}

func NewWireVerticle() *WireVerticle {
	addr := os.Getenv("WIRE_HOST")
	if addr == "" {
		addr = "127.0.0.1:7474"
	}
	secret := os.Getenv("WIRE_SECRET")
	if secret == "" {
		secret = "saving_wire_secret_changeme"
	}
	return &WireVerticle{
		BaseVerticle: core.NewBaseVerticle("wire"),
		addr:         addr,
		secret:       []byte(secret),
	}
}

func (v *WireVerticle) Start(ctx core.FluxorContext) error {
	if err := v.BaseVerticle.Start(ctx); err != nil {
		return err
	}

	if err := v.connect(); err != nil {
		// Non-fatal: gateway starts without Wire; reconnect loop will retry.
		slog.Warn("wire: initial connect failed, will retry", "err", err)
	}

	// Reconnect loop: re-establishes connection when lost.
	v.ExecuteOn(func() {
		v.reconnectLoop(ctx)
	})

	// Register EventBus handlers for RPC-over-bus.
	v.Consumer("saving.wire.login").Handler(v.handleLogin)
	v.Consumer("saving.wire.balance").Handler(v.handleBalance)
	v.Consumer("saving.wire.transfer").Handler(v.handleTransfer)
	v.Consumer("saving.wire.ping").Handler(v.handlePing)

	slog.Info("wire verticle started", "addr", v.addr)
	return nil
}

func (v *WireVerticle) Stop(ctx core.FluxorContext) error {
	if v.client != nil {
		v.client.Close()
	}
	return v.BaseVerticle.Stop(ctx)
}

// ── connection management ─────────────────────────────────────────────────────

func (v *WireVerticle) connect() error {
	c, err := DialWire(v.addr, v.secret)
	if err != nil {
		return err
	}
	v.client = c
	go v.pushListener(c)
	slog.Info("wire: connected", "addr", v.addr)
	return nil
}

func (v *WireVerticle) reconnectLoop(ctx core.FluxorContext) {
	for {
		if v.client == nil {
			if err := v.connect(); err != nil {
				slog.Warn("wire: reconnect failed, retry in 5s", "err", err)
				select {
				case <-time.After(5 * time.Second):
				case <-ctx.GoCMD().Context().Done():
					return
				}
				continue
			}
		}
		// Wait until this connection drops or context cancels.
		select {
		case <-v.client.Done():
			slog.Warn("wire: connection lost, reconnecting...")
			v.client = nil
		case <-ctx.GoCMD().Context().Done():
			return
		}
	}
}

// ── push event listener ───────────────────────────────────────────────────────

func (v *WireVerticle) pushListener(c *WireClient) {
	for f := range c.Events {
		switch f.Typ {
		case wireEvtTransferIn:
			if len(f.Body) < 20 {
				continue
			}
			from := binary.BigEndian.Uint32(f.Body[0:4])
			amt := binary.BigEndian.Uint64(f.Body[4:12])
			bal := binary.BigEndian.Uint64(f.Body[12:20])
			_ = v.Publish("saving.transfer_in", map[string]any{
				"from": from, "amount": amt, "balance": bal,
			})
		case wireEvtIntentPaid:
			if len(f.Body) < 20 {
				continue
			}
			reqID := binary.BigEndian.Uint64(f.Body[0:8])
			cust := binary.BigEndian.Uint32(f.Body[8:12])
			amt := binary.BigEndian.Uint64(f.Body[12:20])
			_ = v.Publish("saving.intent_paid", map[string]any{
				"request_id": reqID, "customer_id": cust, "amount": amt,
			})
		}
	}
}

// ── EventBus RPC handlers ─────────────────────────────────────────────────────

func (v *WireVerticle) handleLogin(_ core.FluxorContext, msg core.Message) error {
	req, ok := msg.Body().(map[string]any)
	if !ok {
		return v.reply("saving.wire.login", false, "invalid body", nil)
	}
	uid, _ := req["uid"].(float64)
	pw, _ := req["password"].(string)
	if uid == 0 || pw == "" {
		return v.reply("saving.wire.login", false, "uid and password required", nil)
	}
	if v.client == nil {
		return v.reply("saving.wire.login", false, "wire not connected", nil)
	}
	token, err := v.client.Login(uint32(uid), pw)
	if err != nil {
		return v.reply("saving.wire.login", false, err.Error(), nil)
	}
	return v.reply("saving.wire.login", true, "", map[string]any{"token": fmt.Sprintf("%x", token)})
}

func (v *WireVerticle) handleBalance(_ core.FluxorContext, msg core.Message) error {
	req, ok := msg.Body().(map[string]any)
	if !ok {
		return v.reply("saving.wire.balance", false, "invalid body", nil)
	}
	token, err := hexToken(req, "token")
	if err != nil {
		return v.reply("saving.wire.balance", false, err.Error(), nil)
	}
	if v.client == nil {
		return v.reply("saving.wire.balance", false, "wire not connected", nil)
	}
	bal, pend, avail, ver, err := v.client.Balance(token)
	if err != nil {
		return v.reply("saving.wire.balance", false, err.Error(), nil)
	}
	return v.reply("saving.wire.balance", true, "", map[string]any{
		"balance": bal, "pending": pend, "available": avail, "version": ver,
	})
}

func (v *WireVerticle) handleTransfer(_ core.FluxorContext, msg core.Message) error {
	req, ok := msg.Body().(map[string]any)
	if !ok {
		return v.reply("saving.wire.transfer", false, "invalid body", nil)
	}
	token, err := hexToken(req, "token")
	if err != nil {
		return v.reply("saving.wire.transfer", false, err.Error(), nil)
	}
	to, _ := req["to"].(float64)
	amount, _ := req["amount"].(float64)
	if to == 0 || amount <= 0 {
		return v.reply("saving.wire.transfer", false, "to and amount required", nil)
	}
	if v.client == nil {
		return v.reply("saving.wire.transfer", false, "wire not connected", nil)
	}
	after, err := v.client.Transfer(token, uint32(to), uint64(amount))
	if err != nil {
		return v.reply("saving.wire.transfer", false, err.Error(), nil)
	}
	return v.reply("saving.wire.transfer", true, "", map[string]any{"after_balance": after})
}

func (v *WireVerticle) handlePing(_ core.FluxorContext, msg core.Message) error {
	if v.client == nil {
		return v.reply("saving.wire.ping", false, "wire not connected", nil)
	}
	if err := v.client.Ping(); err != nil {
		return v.reply("saving.wire.ping", false, err.Error(), nil)
	}
	return v.reply("saving.wire.ping", true, "", map[string]any{"pong": true})
}

// ── reply helpers ─────────────────────────────────────────────────────────────

func (v *WireVerticle) reply(addr string, ok bool, errMsg string, data map[string]any) error {
	payload := map[string]any{"ok": ok}
	if errMsg != "" {
		payload["error"] = errMsg
	}
	for k, val := range data {
		payload[k] = val
	}
	return v.Publish(addr+"._reply", payload)
}

// hexToken decodes a 32-byte session token from a hex string in the request map.
func hexToken(req map[string]any, key string) ([]byte, error) {
	s, _ := req[key].(string)
	if len(s) != 64 {
		return nil, fmt.Errorf("token must be 64 hex chars")
	}
	token := make([]byte, 32)
	for i := 0; i < 32; i++ {
		var b byte
		_, err := fmt.Sscanf(s[i*2:i*2+2], "%02x", &b)
		if err != nil {
			return nil, fmt.Errorf("invalid token hex")
		}
		token[i] = b
	}
	return token, nil
}
