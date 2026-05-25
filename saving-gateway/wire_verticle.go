package main

import (
	"encoding/binary"
	"encoding/hex"
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
	addr        string
	secret      []byte
	svcUID      uint32 // service account UID — 0 = no auto-login
	svcPassword string // service account password
	client      *WireClient
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
	var svcUID uint32
	if v := os.Getenv("WIRE_SERVICE_UID"); v != "" {
		var n uint64
		fmt.Sscanf(v, "%d", &n)
		svcUID = uint32(n)
	}
	return &WireVerticle{
		BaseVerticle: core.NewBaseVerticle("wire"),
		addr:         addr,
		secret:       []byte(secret),
		svcUID:       svcUID,
		svcPassword:  os.Getenv("WIRE_SERVICE_PASSWORD"),
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
	v.Consumer("saving.wire.register_merchant").Handler(v.handleRegisterMerchant)
	v.Consumer("saving.wire.enroll_totp").Handler(v.handleEnrollTOTP)
	v.Consumer("saving.wire.create_intent").Handler(v.handleCreateIntent)
	v.Consumer("saving.wire.pay_intent").Handler(v.handlePayIntent)
	v.Consumer("saving.wire.cash_in").Handler(v.handleCashIn)
	v.Consumer("saving.wire.cash_out").Handler(v.handleCashOut)
	v.Consumer("saving.wire.totp_charge").Handler(v.handleTotpCharge)
	v.Consumer("saving.wire.get_merchant_info").Handler(v.handleGetMerchantInfo)
	v.Consumer("saving.wire.list_intents").Handler(v.handleListIntents)

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
	// Auto-login as service account so the gateway receives push events.
	if v.svcUID != 0 && v.svcPassword != "" {
		token, err := c.Login(v.svcUID, v.svcPassword)
		if err != nil {
			c.Close()
			return fmt.Errorf("service account login uid=%d: %w", v.svcUID, err)
		}
		slog.Info("wire: service account logged in", "uid", v.svcUID)
		_ = token // held by the TCP connection; push events now flow
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
	slog.Info("wire: push listener started")
	for f := range c.Events {
		slog.Info("wire: push event received", "typ", fmt.Sprintf("0x%02x", f.Typ), "body_len", len(f.Body))
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
		case wireEvtCashOut:
			if len(f.Body) < 20 {
				continue
			}
			bankMID := binary.BigEndian.Uint32(f.Body[0:4])
			amt := binary.BigEndian.Uint64(f.Body[4:12])
			bal := binary.BigEndian.Uint64(f.Body[12:20])
			_ = v.Publish("saving.cash_out", map[string]any{
				"bank_mid": bankMID, "amount": amt, "balance": bal,
			})
		}
	}
}

// ── EventBus RPC handlers ─────────────────────────────────────────────────────

func (v *WireVerticle) handleLogin(_ core.FluxorContext, msg core.Message) error {
	const addr = "saving.wire.login"
	var req map[string]any
	if err := msg.DecodeBody(&req); err != nil {
		return v.reply(addr, false, "invalid body", nil)
	}
	uid, _ := req["uid"].(float64)
	pw, _ := req["password"].(string)
	if uid == 0 || pw == "" {
		return v.reply(addr, false, "uid and password required", nil)
	}
	c, err := v.withClient(addr)
	if err != nil {
		return err
	}
	token, err := c.Login(uint32(uid), pw)
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	return v.reply(addr, true, "", map[string]any{"token": hex.EncodeToString(token)})
}

func (v *WireVerticle) handleBalance(_ core.FluxorContext, msg core.Message) error {
	const addr = "saving.wire.balance"
	var req map[string]any
	if err := msg.DecodeBody(&req); err != nil {
		return v.reply(addr, false, "invalid body", nil)
	}
	token, err := hexToken(req, "token")
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	c, err := v.withClient(addr)
	if err != nil {
		return err
	}
	bal, pend, avail, ver, err := c.Balance(token)
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	return v.reply(addr, true, "", map[string]any{
		"balance": bal, "pending": pend, "available": avail, "version": ver,
	})
}

func (v *WireVerticle) handleTransfer(_ core.FluxorContext, msg core.Message) error {
	const addr = "saving.wire.transfer"
	var req map[string]any
	if err := msg.DecodeBody(&req); err != nil {
		return v.reply(addr, false, "invalid body", nil)
	}
	token, err := hexToken(req, "token")
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	to, _ := req["to"].(float64)
	amount, _ := req["amount"].(float64)
	if to == 0 || amount <= 0 {
		return v.reply(addr, false, "to and amount required", nil)
	}
	c, err := v.withClient(addr)
	if err != nil {
		return err
	}
	after, err := c.Transfer(token, uint32(to), uint64(amount))
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	return v.reply(addr, true, "", map[string]any{"after_balance": after})
}

func (v *WireVerticle) handlePing(_ core.FluxorContext, msg core.Message) error {
	const addr = "saving.wire.ping"
	c, err := v.withClient(addr)
	if err != nil {
		return err
	}
	if err := c.Ping(); err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	return v.reply(addr, true, "", map[string]any{"pong": true})
}

func (v *WireVerticle) handleRegisterMerchant(_ core.FluxorContext, msg core.Message) error {
	const addr = "saving.wire.register_merchant"
	var req map[string]any
	if err := msg.DecodeBody(&req); err != nil {
		return v.reply(addr, false, "invalid body", nil)
	}
	token, err := hexToken(req, "token")
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	name, _ := req["name"].(string)
	c, err := v.withClient(addr)
	if err != nil {
		return err
	}
	if err := c.RegisterMerchant(token, name); err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	return v.reply(addr, true, "", nil)
}

func (v *WireVerticle) handleEnrollTOTP(_ core.FluxorContext, msg core.Message) error {
	const addr = "saving.wire.enroll_totp"
	var req map[string]any
	if err := msg.DecodeBody(&req); err != nil {
		return v.reply(addr, false, "invalid body", nil)
	}
	token, err := hexToken(req, "token")
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	customerID, _ := req["customer_id"].(float64)
	secret, err := decodeSecret(req["secret"])
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	c, err := v.withClient(addr)
	if err != nil {
		return err
	}
	if err := c.EnrollTOTP(token, uint32(customerID), secret); err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	return v.reply(addr, true, "", nil)
}

func (v *WireVerticle) handleCreateIntent(_ core.FluxorContext, msg core.Message) error {
	const addr = "saving.wire.create_intent"
	var req map[string]any
	if err := msg.DecodeBody(&req); err != nil {
		return v.reply(addr, false, "invalid body", nil)
	}
	token, err := hexToken(req, "token")
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	requestID, _ := req["request_id"].(float64)
	orderID, _ := req["order_id"].(float64)
	amount, _ := req["amount"].(float64)
	gwOrderID, _ := req["gateway_order_id"].(string)
	if requestID == 0 || amount <= 0 {
		return v.reply(addr, false, "request_id and amount required", nil)
	}
	c, err := v.withClient(addr)
	if err != nil {
		return err
	}
	if err := c.CreateIntent(token, uint64(requestID), uint64(orderID), uint64(amount), gwOrderID); err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	return v.reply(addr, true, "", map[string]any{
		"request_id": uint64(requestID), "amount": uint64(amount),
	})
}

func (v *WireVerticle) handlePayIntent(_ core.FluxorContext, msg core.Message) error {
	const addr = "saving.wire.pay_intent"
	var req map[string]any
	if err := msg.DecodeBody(&req); err != nil {
		return v.reply(addr, false, "invalid body", nil)
	}
	token, err := hexToken(req, "token")
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	merchantID, _ := req["merchant_id"].(float64)
	requestID, _ := req["request_id"].(float64)
	secret, err := decodeSecret(req["secret"])
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	if merchantID == 0 || requestID == 0 {
		return v.reply(addr, false, "merchant_id and request_id required", nil)
	}
	totpCode := TOTPCode(secret)
	c, err := v.withClient(addr)
	if err != nil {
		return err
	}
	if err := c.PayIntent(token, uint32(merchantID), uint64(requestID), totpCode); err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	return v.reply(addr, true, "", map[string]any{"totp_code": totpCode})
}

func (v *WireVerticle) handleCashIn(_ core.FluxorContext, msg core.Message) error {
	const addr = "saving.wire.cash_in"
	var req map[string]any
	if err := msg.DecodeBody(&req); err != nil {
		return v.reply(addr, false, "invalid body", nil)
	}
	token, err := hexToken(req, "token")
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	toUID, _ := req["to_uid"].(float64)
	amount, _ := req["amount"].(float64)
	topupID, _ := req["topup_id"].(string)
	if toUID == 0 || amount <= 0 || topupID == "" {
		return v.reply(addr, false, "to_uid, amount and topup_id required", nil)
	}
	c, err := v.withClient(addr)
	if err != nil {
		return err
	}
	if err := c.CashIn(token, uint32(toUID), uint64(amount), topupID); err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	return v.reply(addr, true, "", nil)
}

func (v *WireVerticle) handleCashOut(_ core.FluxorContext, msg core.Message) error {
	const addr = "saving.wire.cash_out"
	var req map[string]any
	if err := msg.DecodeBody(&req); err != nil {
		return v.reply(addr, false, "invalid body", nil)
	}
	token, err := hexToken(req, "token")
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	fromUID, _ := req["from_uid"].(float64)
	amount, _ := req["amount"].(float64)
	cashoutID, _ := req["cashout_id"].(string)
	if fromUID == 0 || amount <= 0 || cashoutID == "" {
		return v.reply(addr, false, "from_uid, amount and cashout_id required", nil)
	}
	c, err := v.withClient(addr)
	if err != nil {
		return err
	}
	if err := c.CashOut(token, uint32(fromUID), uint64(amount), cashoutID); err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	return v.reply(addr, true, "", nil)
}

func (v *WireVerticle) handleTotpCharge(_ core.FluxorContext, msg core.Message) error {
	const addr = "saving.wire.totp_charge"
	var req map[string]any
	if err := msg.DecodeBody(&req); err != nil {
		return v.reply(addr, false, "invalid body", nil)
	}
	token, err := hexToken(req, "token")
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	customerUID, _ := req["customer_uid"].(float64)
	amount, _ := req["amount"].(float64)
	secret, err := decodeSecret(req["secret"])
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	if customerUID == 0 || amount <= 0 {
		return v.reply(addr, false, "customer_uid and amount required", nil)
	}
	totpCode := TOTPCode(secret)
	c, err := v.withClient(addr)
	if err != nil {
		return err
	}
	if err := c.TotpCharge(token, uint32(customerUID), totpCode, uint64(amount)); err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	return v.reply(addr, true, "", map[string]any{"totp_code": totpCode})
}

func (v *WireVerticle) handleGetMerchantInfo(_ core.FluxorContext, msg core.Message) error {
	const addr = "saving.wire.get_merchant_info"
	var req map[string]any
	if err := msg.DecodeBody(&req); err != nil {
		return v.reply(addr, false, "invalid body", nil)
	}
	token, err := hexToken(req, "token")
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	merchantID, _ := req["merchant_id"].(float64)
	if merchantID == 0 {
		return v.reply(addr, false, "merchant_id required", nil)
	}
	c, err := v.withClient(addr)
	if err != nil {
		return err
	}
	name, err := c.GetMerchantInfo(token, uint32(merchantID))
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	return v.reply(addr, true, "", map[string]any{"name": name})
}

func (v *WireVerticle) handleListIntents(_ core.FluxorContext, msg core.Message) error {
	const addr = "saving.wire.list_intents"
	var req map[string]any
	if err := msg.DecodeBody(&req); err != nil {
		return v.reply(addr, false, "invalid body", nil)
	}
	token, err := hexToken(req, "token")
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	c, err := v.withClient(addr)
	if err != nil {
		return err
	}
	intents, err := c.ListIntents(token)
	if err != nil {
		return v.reply(addr, false, err.Error(), nil)
	}
	items := make([]map[string]any, len(intents))
	for i, it := range intents {
		items[i] = map[string]any{"request_id": it.RequestID, "amount": it.Amount}
	}
	return v.reply(addr, true, "", map[string]any{"intents": items})
}

// ── helpers ───────────────────────────────────────────────────────────────────

// withClient returns the active WireClient or publishes a "not connected" reply.
func (v *WireVerticle) withClient(addr string) (*WireClient, error) {
	if v.client == nil {
		return nil, v.reply(addr, false, "wire not connected", nil)
	}
	return v.client, nil
}

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

// hexToken decodes a 32-byte session token from the request map.
func hexToken(req map[string]any, key string) ([]byte, error) {
	s, _ := req[key].(string)
	b, err := hex.DecodeString(s)
	if err != nil || len(b) != 32 {
		return nil, fmt.Errorf("token must be 64 hex chars")
	}
	return b, nil
}

// decodeSecret decodes a 20-byte TOTP secret from a hex string value.
func decodeSecret(v any) ([]byte, error) {
	s, _ := v.(string)
	b, err := hex.DecodeString(s)
	if err != nil || len(b) != 20 {
		return nil, fmt.Errorf("secret must be 40 hex chars (20 bytes)")
	}
	return b, nil
}
