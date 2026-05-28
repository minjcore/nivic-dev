package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"
)

func sha256Hex(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

// ─── Payment request ────────────────────────────────────────────────────────
//
//	QR payload (base64url of JSON):
//	  { "mid":12345, "order_id":"ORD-001", "amount":50000, "ts":1715000000000, "sig":"BASE64" }
//
//	Signed message = mid(4 BE) || amount(8 BE) || ts(8 BE) || order_id(utf-8)

type PaymentRequest struct {
	MID     uint32 `json:"mid"`
	OrderID string `json:"order_id"`
	Amount  uint64 `json:"amount"`
	TS      int64  `json:"ts"`  // unix milliseconds
	Sig     string `json:"sig"` // base64(ed25519 signature, 64 bytes)
}

const paymentRequestTTL = 10 * time.Minute

func signedMsg(mid uint32, amount uint64, ts int64, orderID string) []byte {
	msg := make([]byte, 4+8+8+len(orderID))
	binary.BigEndian.PutUint32(msg[0:], mid)
	binary.BigEndian.PutUint64(msg[4:], amount)
	binary.BigEndian.PutUint64(msg[12:], uint64(ts))
	copy(msg[20:], orderID)
	return msg
}

func buildPaymentRequest(m *Merchant, orderID string, amount uint64) (*PaymentRequest, error) {
	privRaw, err := base64.StdEncoding.DecodeString(m.PrivkeyB64)
	if err != nil || len(privRaw) != ed25519.PrivateKeySize {
		return nil, fmt.Errorf("stored privkey corrupt")
	}
	ts := time.Now().UnixMilli()
	msg := signedMsg(m.MID, amount, ts, orderID)
	sig := ed25519.Sign(ed25519.PrivateKey(privRaw), msg)
	return &PaymentRequest{
		MID:     m.MID,
		OrderID: orderID,
		Amount:  amount,
		TS:      ts,
		Sig:     base64.StdEncoding.EncodeToString(sig),
	}, nil
}

// base64url-encodes a PaymentRequest as JSON for embedding in QR
func prToBase64URL(pr *PaymentRequest) (string, error) {
	b, err := json.Marshal(pr)
	if err != nil {
		return "", err
	}
	s := base64.URLEncoding.EncodeToString(b)
	// strip padding — receivers must re-pad
	for len(s) > 0 && s[len(s)-1] == '=' {
		s = s[:len(s)-1]
	}
	return s, nil
}

// ─── Handler context ────────────────────────────────────────────────────────

type handler struct {
	store        *Store
	adminToken   string
	wireAdminURL string
	wireM2MToken string
	wireAddr     string
	mailer       *mailer
	jwtSecret    string
}

func (h *handler) routes() http.Handler {
	mux := http.NewServeMux()
	// ── Customer auth (BFF JWT) ────────────────────────────────────────────
	mux.HandleFunc("POST /auth/login",        h.handleCustomerLogin)
	mux.HandleFunc("GET /auth/me",            h.withCustomerAuth(h.handleCustomerMe))
	mux.HandleFunc("GET /loyalty/user/{uid}", h.withCustomerAuth(h.handleUserLoyalty))
	mux.HandleFunc("POST /pay",               h.withCustomerAuth(h.handleCustomerPay))
	mux.HandleFunc("POST /pay/confirm",       h.withCustomerAuth(h.handleCustomerPayConfirm))

	mux.HandleFunc("GET /health",                                  h.handleHealth)
	mux.HandleFunc("GET /merchants",                               h.handleSearch) // ?q=name OR ?slug=bmap
	mux.HandleFunc("GET /merchants/{mid}",                         h.handleGet)
	mux.HandleFunc("POST /merchants",                              h.handleRegister)
	mux.HandleFunc("POST /merchants/onboard",                      h.handleOnboard)
	mux.HandleFunc("POST /merchants/login",                        h.handleMerchantLogin)
	mux.HandleFunc("POST /merchants/{mid}/orders",                 h.handleCreateOrder)
	mux.HandleFunc("GET /merchants/{mid}/orders",                  h.handleListOrders)
	mux.HandleFunc("GET /merchants/{mid}/orders/{oid}",            h.handleGetOrder)
	mux.HandleFunc("GET /merchants/{mid}/stats",                   h.handleStats)
	mux.HandleFunc("GET /merchants/{mid}/crm",                    h.handleCRM)
	mux.HandleFunc("GET /merchants/{mid}/loyalty",                 h.handleLoyaltyMembers)
	mux.HandleFunc("GET /merchants/{mid}/loyalty/{uid}",           h.handleLoyaltyGet)
	mux.HandleFunc("POST /merchants/{mid}/loyalty/{uid}/award",    h.handleLoyaltyAward)

	mux.HandleFunc("POST /orders/{oid}/confirm",                   h.handleConfirmOrder)
	mux.HandleFunc("POST /merchants/{mid}/orders/{oid}/confirm",   h.handleMerchantConfirmOrder)
	mux.HandleFunc("POST /payment_request/verify",                 h.handleVerify)
	mux.HandleFunc("POST /chat/{mid}",                             h.handleChatSend)
	mux.HandleFunc("POST /chat/{mid}/reply",                       h.handleChatReply)
	mux.HandleFunc("GET /chat/{mid}/inbox",                        h.handleChatInbox)
	mux.HandleFunc("GET /chat/{mid}/{uid}",                        h.handleChatThread)
	// Menu items
	mux.HandleFunc("GET /merchants/{mid}/menu",                    h.handleListMenu)
	mux.HandleFunc("POST /merchants/{mid}/menu",                   h.handleAddMenuItem)
	mux.HandleFunc("DELETE /merchants/{mid}/menu/{id}",            h.handleDeleteMenuItem)
	// Profile update
	mux.HandleFunc("PATCH /merchants/{mid}",                       h.handleUpdateProfile)
	// Slug management
	mux.HandleFunc("PATCH /merchants/{mid}/slug",                  h.handleUpdateSlug)
	// Custom domain management
	mux.HandleFunc("PATCH /merchants/{mid}/domain",                h.handleSetDomain)
	mux.HandleFunc("DELETE /merchants/{mid}/domain",               h.handleRemoveDomain)
	// Caddy on_demand TLS ask endpoint — returns 200 if domain is registered, 404 otherwise
	mux.HandleFunc("GET /caddy/check-domain",                      h.handleCaddyCheckDomain)
	// Admin: backfill slugs for merchants that don't have one
	mux.HandleFunc("POST /admin/migrate-slugs",                    h.handleMigrateSlugs)
	// Public pay endpoint (no merchant token needed — QR is Ed25519-signed)
	mux.HandleFunc("POST /public/{mid}/order",                     h.handlePublicCreateOrder)
	// App relay: Wire app POSTs txn_id after CONFIRM_INTENT ACK; Merchants pull-verifies with Wire admin.
	mux.HandleFunc("POST /orders/{oid}/wire_confirm",              h.handleWireConfirm)
	// Universal pay page — Shopify model: one URL, all platforms
	mux.HandleFunc("GET /pay/{order_id}",                          h.handlePayPage)
	mux.HandleFunc("GET /pay/{order_id}/wire",                   h.handlePayOrderWire)
	mux.HandleFunc("GET /pay/{order_id}/status",                   h.handlePayStatus)

	// ACS flow — offline signed QR payments
	mux.HandleFunc("POST /merchants/{mid}/qr",   h.handleGenerateQR)   // generate QR token
	mux.HandleFunc("GET /qr/{token}",            h.handleQRPage)        // resolve QR → HTML with saving-pay meta
	mux.HandleFunc("POST /acs/{ref}",            h.handleACSCallback)   // Wire server callback

	// Middleware: merchant public page for *.nivic.dev subdomains and custom domains
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host := r.Host
		if i := strings.LastIndex(host, ":"); i != -1 {
			host = host[:i]
		}

		if r.URL.Path == "/" || r.URL.Path == "" {
			var m *Merchant

			const suffix = ".nivic.dev"
			if strings.HasSuffix(host, suffix) {
				slug := strings.TrimSuffix(host, suffix)
				if slug != "saving" && slug != "www" && slug != "api" && slug != "" {
					m, _ = h.store.GetBySlug(slug)
				}
			} else if !strings.HasSuffix(host, ".nivic.dev") {
				// Custom domain (e.g. bmapworkshop.com)
				m, _ = h.store.GetByDomain(host)
			}

			if m != nil {
				h.handleMerchantPage(w, r, m)
				return
			}
		}

		mux.ServeHTTP(w, r)
	})
}

// GET /health

func (h *handler) handleHealth(w http.ResponseWriter, _ *http.Request) {
	jsonOK(w, map[string]string{"status": "ok", "service": "merchants-host"})
}

// GET /merchants?q={query}
// Public — search merchants by name.

func (h *handler) handleSearch(w http.ResponseWriter, r *http.Request) {
	// ?slug=bmap → single merchant lookup by slug
	if slug := r.URL.Query().Get("slug"); slug != "" {
		m, err := h.store.GetBySlug(slug)
		if err != nil {
			jsonErr(w, 500, err.Error()); return
		}
		if m == nil {
			jsonErr(w, 404, "merchant not found"); return
		}
		jsonOK(w, m); return
	}
	// ?q=name → full-text search
	q := r.URL.Query().Get("q")
	results, err := h.store.SearchMerchants(q)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if results == nil {
		results = []MerchantSearchResult{}
	}
	jsonOK(w, results)
}

// GET /merchants/{mid}

func (h *handler) handleGet(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid")
		return
	}
	m, err := h.store.Get(mid)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if m == nil {
		jsonErr(w, 404, "merchant not found")
		return
	}
	jsonOK(w, m) // PrivkeyB64 / Token are tagged json:"-" so never exposed
}

// GET /merchants/by-slug/{slug} — public lookup for Wire app (slug from *.nivic.dev).

func (h *handler) handleGetBySlug(w http.ResponseWriter, r *http.Request) {
	slug := r.PathValue("slug")
	if slug == "" {
		jsonErr(w, 400, "slug required")
		return
	}
	m, err := h.store.GetBySlug(slug)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if m == nil {
		jsonErr(w, 404, "merchant not found")
		return
	}
	jsonOK(w, map[string]any{
		"mid":    m.MID,
		"name":   m.Name,
		"slug":   m.Slug,
		"address": m.Address,
	})
}

// POST /merchants
// Header: X-Admin-Token: <token>
// Body:   { "mid":12345, "name":"Shop A" }
//         pubkey/privkey are generated server-side; returned once in response.

func (h *handler) handleRegister(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-Admin-Token") != h.adminToken {
		jsonErr(w, 401, "unauthorized")
		return
	}
	var req struct {
		MID     uint32 `json:"mid"`
		Name    string `json:"name"`
		Email   string `json:"email"`
		Address string `json:"address"`
		Website string `json:"website"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, 400, "bad json")
		return
	}
	if req.MID == 0 || req.Name == "" {
		jsonErr(w, 400, "mid and name required")
		return
	}

	// Generate Ed25519 keypair for this merchant
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		jsonErr(w, 500, "keygen failed")
		return
	}

	// Generate a random merchant API token
	tokenBytes := make([]byte, 24)
	if _, err := rand.Read(tokenBytes); err != nil {
		jsonErr(w, 500, "token gen failed")
		return
	}
	token := base64.URLEncoding.EncodeToString(tokenBytes)

	pubB64  := base64.StdEncoding.EncodeToString(pub)
	privB64 := base64.StdEncoding.EncodeToString(priv)

	if err := h.store.Register(req.MID, req.Name, req.Email, req.Address, req.Website, pubB64, privB64, token, ""); err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	slug, _ := h.store.GenerateSlug(req.MID, req.Name)
	_ = h.store.SetSlug(req.MID, slug)
	// Return privkey and token once — caller must store securely
	jsonOK(w, map[string]any{
		"registered":  req.MID,
		"pubkey_b64":  pubB64,
		"privkey_b64": privB64,
		"token":       token,
		"slug":        slug,
	})
}

// POST /merchants/{mid}/orders
// Header: X-Merchant-Token: <token>
// Body:   { "amount":50000, "note":"Cà phê x2", "order_id":"POS-001" }
// Returns: { "order_id":"...", "pr":"BASE64URL", "qr_url":"saving://pay?pr=..." }

func (h *handler) handleCreateOrder(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid")
		return
	}
	m, err := h.store.Get(mid)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if m == nil {
		jsonErr(w, 404, "merchant not found")
		return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized")
		return
	}

	var req struct {
		Amount         uint64 `json:"amount"`
		Note           string `json:"note"`
		OrderID        string `json:"order_id"`       // optional; POS-supplied reference
		DiscountPoints int64  `json:"discount_points"` // points to deduct at payment
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, 400, "bad json")
		return
	}
	if req.Amount == 0 {
		jsonErr(w, 400, "amount required")
		return
	}

	// Apply point discount: 1 point = 100 VND
	finalAmount := req.Amount
	if req.DiscountPoints > 0 {
		discount := uint64(req.DiscountPoints) * 100
		if discount >= finalAmount {
			discount = finalAmount - 1 // minimum 1 VND
		}
		finalAmount -= discount
	}

	orderID := req.OrderID
	if orderID == "" {
		orderID = fmt.Sprintf("%d-%d", mid, time.Now().UnixMilli())
	}

	if err := h.store.CreateOrder(orderID, mid, finalAmount, req.Note, req.DiscountPoints); err != nil {
		jsonErr(w, 500, err.Error())
		return
	}

	pr, err := buildPaymentRequest(m, orderID, req.Amount)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}

	prB64, err := prToBase64URL(pr)
	if err != nil {
		jsonErr(w, 500, "encode failed")
		return
	}

	rid := h.wireCreateIntent(mid, m.PasswordHash, finalAmount, orderID)
	jsonOK(w, map[string]any{
		"order_id":   orderID,
		"pr":         prB64,
		"qr_url":     "saving://pay?pr=" + prB64,
		"intent_url": wireIntentURL(mid, rid, finalAmount, orderID),
		"store_url":  wireStoreURL(mid),
		"request_id": rid,
	})
}

// GET /merchants/{mid}/orders/{oid}
// Header: X-Merchant-Token: <token>

func (h *handler) handleGetOrder(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid")
		return
	}
	m, err := h.store.Get(mid)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if m == nil {
		jsonErr(w, 404, "merchant not found")
		return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized")
		return
	}

	o, err := h.store.GetOrder(r.PathValue("oid"))
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if o == nil || o.MID != mid {
		jsonErr(w, 404, "order not found")
		return
	}
	jsonOK(w, o)
}

// POST /orders/{oid}/confirm
// Called by Wire after a successful payment.
// Body: { "paid_by": 16777216 }  (UID of payer)
// Auth: X-Wire-Token header (shared secret, set via WIRE_TOKEN env)

func (h *handler) handleConfirmOrder(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-Wire-Token") != h.adminToken {
		// Re-use adminToken for Wire↔MerchantsHost auth for simplicity;
		// in production this would be a separate rotating secret.
		jsonErr(w, 401, "unauthorized")
		return
	}
	var body struct {
		PaidBy uint32 `json:"paid_by"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		jsonErr(w, 400, "bad json")
		return
	}
	pts, err := h.store.MarkPaid(r.PathValue("oid"), body.PaidBy)
	if err != nil {
		jsonErr(w, 400, err.Error())
		return
	}
	jsonOK(w, map[string]any{"status": "paid", "points_awarded": pts})
}

// POST /orders/{oid}/wire_confirm
// Called by the Wire Android app after it receives CONFIRM_INTENT ACK from Wire TCP.
// Body: { "txn_id": 12345, "paid_by": 16777216 }
// No auth — legitimacy is proven by pull-verifying txn_id against Wire admin API.

func (h *handler) handleWireConfirm(w http.ResponseWriter, r *http.Request) {
	var body struct {
		TxnID  int64  `json:"txn_id"`
		PaidBy uint32 `json:"paid_by"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		jsonErr(w, 400, "bad json"); return
	}
	if body.TxnID <= 0 {
		jsonErr(w, 400, "txn_id required"); return
	}

	oid := r.PathValue("oid")
	o, err := h.store.GetOrder(oid)
	if err != nil || o == nil {
		jsonErr(w, 404, "order not found"); return
	}
	if o.Status != StatusPending {
		jsonErr(w, 409, "order already settled"); return
	}

	// Pull-verify: ask Wire admin whether this txn actually happened
	txn, err := h.verifyWireTxn(body.TxnID)
	if err != nil {
		jsonErr(w, 502, "wire verify failed: "+err.Error()); return
	}
	if uint32(txn.ToID) != o.MID {
		jsonErr(w, 400, "txn recipient mismatch"); return
	}
	if txn.Amount != o.Amount {
		jsonErr(w, 400, "txn amount mismatch"); return
	}

	paidBy := body.PaidBy
	if paidBy == 0 {
		paidBy = uint32(txn.FromID)
	}

	pts, err := h.store.MarkPaid(oid, paidBy)
	if err != nil {
		jsonErr(w, 400, err.Error()); return
	}

	// Notify merchant via email (non-blocking)
	go func() {
		m, err := h.store.Get(o.MID)
		if err == nil && m != nil && m.Email != "" {
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			_ = h.mailer.send(ctx, m.Email,
				fmt.Sprintf("✅ Đơn hàng %s đã được thanh toán", oid[max(0, len(oid)-8):]),
				emailOrderPaid(m.Name, oid, o.Amount, pts),
			)
		}
	}()

	if paidBy != 0 {
		go func() {
			msg := fmt.Sprintf("✅ Đơn hàng %s đã được thanh toán!\nSố tiền: %s₫", oid[max(0, len(oid)-8):], formatVND(int64(o.Amount)))
			if pts > 0 {
				msg += fmt.Sprintf("\n🌟 Bạn được cộng %d điểm tích luỹ.", pts)
			}
			_, _ = h.store.SendChatMessage(o.MID, paidBy, true, msg)
		}()
	}

	jsonOK(w, map[string]any{"status": "paid", "points_awarded": pts})
}

type wireTxnInfo struct {
	TxnID  int64  `json:"txn_id"`
	FromID int64  `json:"from_id"`
	ToID   int64  `json:"to_id"`
	Amount uint64 `json:"amount"`
	Type   int    `json:"type"`
}

func (h *handler) verifyWireTxn(txnID int64) (*wireTxnInfo, error) {
	if h.wireAdminURL == "" {
		return nil, fmt.Errorf("WIRE_ADMIN_URL not configured")
	}
	url := fmt.Sprintf("%s/api/txn?id=%d", h.wireAdminURL, txnID)
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, err
	}
	if h.wireM2MToken != "" {
		req.Header.Set("X-M2M-Token", h.wireM2MToken)
	}
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("wire returned %d", resp.StatusCode)
	}
	var info wireTxnInfo
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		return nil, err
	}
	return &info, nil
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

// POST /merchants/{mid}/orders/{oid}/confirm
// Merchant confirms receipt of a bank transfer (VietQR).
// Body: { "paid_by": 16777219 }  (UID of payer, optional — 0 if unknown)
// Auth: X-Merchant-Token

func (h *handler) handleMerchantConfirmOrder(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "bad mid"); return
	}
	m, err := h.store.Get(mid)
	if err != nil {
		jsonErr(w, 404, "merchant not found"); return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized"); return
	}
	var body struct {
		PaidBy uint32 `json:"paid_by"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		jsonErr(w, 400, "bad json"); return
	}
	oid := r.PathValue("oid")
	pts, err := h.store.MarkPaid(oid, body.PaidBy)
	if err != nil {
		jsonErr(w, 400, err.Error()); return
	}

	// Auto-message the customer when uid is known
	if body.PaidBy != 0 {
		go func() {
			o, err := h.store.GetOrder(oid)
			if err != nil || o == nil {
				return
			}
			msg := fmt.Sprintf("✅ Đơn hàng %s đã được xác nhận!\nSố tiền: %s₫", o.ID[len(o.ID)-8:], formatVND(int64(o.Amount)))
			if pts > 0 {
				msg += fmt.Sprintf("\n🌟 Bạn được cộng %d điểm tích luỹ.", pts)
			}
			_, _ = h.store.SendChatMessage(mid, body.PaidBy, true, msg)
		}()
	}

	jsonOK(w, map[string]any{"status": "paid", "points_awarded": pts})
}

// POST /payment_request/verify
// Body: { "mid":12345, "order_id":"...", "amount":50000, "ts":..., "sig":"BASE64" }
// Returns: { "valid":true, "merchant":{...}, "order":{...} }

func (h *handler) handleVerify(w http.ResponseWriter, r *http.Request) {
	var pr PaymentRequest
	if err := json.NewDecoder(r.Body).Decode(&pr); err != nil {
		jsonErr(w, 400, "bad json")
		return
	}

	age := time.Since(time.UnixMilli(pr.TS))
	if age > paymentRequestTTL || age < -time.Minute {
		jsonErr(w, 400, fmt.Sprintf("payment_request expired or future-dated (age=%s)", age.Round(time.Second)))
		return
	}

	m, err := h.store.Get(pr.MID)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if m == nil {
		jsonErr(w, 404, "merchant not found")
		return
	}

	pubkeyRaw, err := base64.StdEncoding.DecodeString(m.PubkeyB64)
	if err != nil || len(pubkeyRaw) != ed25519.PublicKeySize {
		jsonErr(w, 500, "stored pubkey corrupt")
		return
	}

	sigRaw, err := base64.StdEncoding.DecodeString(pr.Sig)
	if err != nil || len(sigRaw) != ed25519.SignatureSize {
		jsonErr(w, 400, "invalid sig encoding")
		return
	}

	msg := signedMsg(pr.MID, pr.Amount, pr.TS, pr.OrderID)
	if !ed25519.Verify(ed25519.PublicKey(pubkeyRaw), msg, sigRaw) {
		jsonErr(w, 400, "signature invalid")
		return
	}

	// Also return order info if it exists
	order, _ := h.store.GetOrder(pr.OrderID)

	resp := map[string]any{
		"valid":    true,
		"merchant": m,
	}
	if order != nil {
		resp["order"] = order
	}
	jsonOK(w, resp)
}

// POST /merchants/onboard
// Self-service: any user can become a merchant. MID = their UID.
// Body: { "uid": 16777216, "name": "Quán Cà Phê ABC" }
// Returns: { "mid", "name", "token" }  — token shown once, store securely.

func (h *handler) handleOnboard(w http.ResponseWriter, r *http.Request) {
	var req struct {
		UID      uint32 `json:"uid"`
		Name     string `json:"name"`
		Email    string `json:"email"`
		Address  string `json:"address"`
		Website  string `json:"website"`
		Password string `json:"password"` // plain-text; stored as SHA-256 hex
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, 400, "bad json")
		return
	}
	if req.UID == 0 || req.Name == "" || req.Password == "" {
		jsonErr(w, 400, "uid, name and password required")
		return
	}
	if len(req.Name) > 64 {
		jsonErr(w, 400, "name too long (max 64)")
		return
	}

	// Idempotent: if already registered, return existing public info
	existing, err := h.store.Get(req.UID)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if existing != nil {
		jsonErr(w, 409, "already a merchant")
		return
	}

	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		jsonErr(w, 500, "keygen failed")
		return
	}
	tokenBytes := make([]byte, 24)
	if _, err := rand.Read(tokenBytes); err != nil {
		jsonErr(w, 500, "token gen failed")
		return
	}
	token    := base64.URLEncoding.EncodeToString(tokenBytes)
	pubB64   := base64.StdEncoding.EncodeToString(pub)
	privB64  := base64.StdEncoding.EncodeToString(priv)
	pwHash   := sha256Hex(req.Password)

	if err := h.store.Register(req.UID, req.Name, req.Email, req.Address, req.Website, pubB64, privB64, token, pwHash); err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	slug, _ := h.store.GenerateSlug(req.UID, req.Name)
	_ = h.store.SetSlug(req.UID, slug)

	// Register Ed25519 pubkey with Wire admin so signed QR payments work.
	// Non-fatal: merchant is created; Wire registration is best-effort.
	if h.wireAdminURL != "" {
		go h.registerPubkeyWithWire(req.UID, []byte(pub))
	}

	if req.Email != "" {
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			_ = h.mailer.send(ctx, req.Email, "Chào mừng bạn đến với Nivic Pay!", emailWelcome(req.Name))
		}()
	}

	jsonOK(w, map[string]any{
		"mid":   req.UID,
		"name":  req.Name,
		"token": token,
		"slug":  slug,
		"url":   "https://" + slug + ".nivic.dev",
	})
}

// POST /merchants/login
// Body: { "uid": 16777216, "password": "plaintext" }
// Returns: { "mid", "name", "token", "slug" }

func (h *handler) handleMerchantLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		UID      uint32 `json:"uid"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, 400, "bad json"); return
	}
	if req.UID == 0 || req.Password == "" {
		jsonErr(w, 400, "uid and password required"); return
	}
	m, err := h.store.Login(req.UID, sha256Hex(req.Password))
	if err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	if m == nil {
		jsonErr(w, 401, "invalid credentials"); return
	}
	jsonOK(w, map[string]any{
		"mid":   m.MID,
		"name":  m.Name,
		"token": m.Token,
		"slug":  m.Slug,
	})
}

// wireCreateIntent calls Wire TCP CREATE_INTENT and stores the returned request_id on the order.
// Falls back to a timestamp-based rid if Wire is unreachable or the merchant has no password.
func (h *handler) wireCreateIntent(mid uint32, pwHashHex string, amount uint64, orderID string) uint64 {
	if h.wireAddr == "" || pwHashHex == "" {
		return uint64(time.Now().UnixMilli())
	}
	rid, err := WireCreateIntent(h.wireAddr, mid, pwHashHex, amount, orderID)
	if err != nil {
		slog.Warn("Wire CREATE_INTENT failed, using timestamp rid", "mid", mid, "err", err)
		return uint64(time.Now().UnixMilli())
	}
	_ = h.store.SetOrderWireRequestID(orderID, rid)
	return rid
}

// registerPubkeyWithWire posts [mid 4B][pubkey 32B] to Wire admin /api/merchant_pubkey.
func (h *handler) registerPubkeyWithWire(mid uint32, pubkey []byte) {
	body := make([]byte, 36)
	binary.BigEndian.PutUint32(body[:4], mid)
	copy(body[4:], pubkey)
	req, err := http.NewRequest("POST", h.wireAdminURL+"/api/merchant_pubkey", strings.NewReader(string(body)))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	if h.wireM2MToken != "" {
		req.Header.Set("X-M2M-Token", h.wireM2MToken)
	}
	resp, err := http.DefaultClient.Do(req)
	if err == nil {
		resp.Body.Close()
	}
}

// GET /merchants/{mid}/orders
// Header: X-Merchant-Token

func (h *handler) handleListOrders(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid")
		return
	}
	m, err := h.store.Get(mid)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if m == nil {
		jsonErr(w, 404, "merchant not found")
		return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized")
		return
	}
	orders, err := h.store.ListOrders(mid, 50)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if orders == nil {
		orders = []Order{}
	}
	jsonOK(w, orders)
}

// GET /merchants/{mid}/stats
// Header: X-Merchant-Token

func (h *handler) handleStats(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid")
		return
	}
	m, err := h.store.Get(mid)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if m == nil {
		jsonErr(w, 404, "merchant not found")
		return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized")
		return
	}
	earned, count, err := h.store.Stats(mid)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	jsonOK(w, map[string]any{
		"total_earned": earned,
		"order_count":  count,
	})
}

// GET /merchants/{mid}/crm
// Header: X-Merchant-Token
// Returns customer segments, spend, visit frequency, churn risk.

func (h *handler) handleCRM(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid"); return
	}
	m, err := h.store.Get(mid)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found"); return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized"); return
	}
	crm, err := h.store.CRMInsights(mid)
	if err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	jsonOK(w, crm)
}

// GET /merchants/{mid}/loyalty
// Header: X-Merchant-Token — returns all loyalty members sorted by points desc.

func (h *handler) handleLoyaltyMembers(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid"); return
	}
	m, err := h.store.Get(mid)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found"); return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized"); return
	}
	members, err := h.store.ListLoyaltyMembers(mid)
	if err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	if members == nil {
		members = []LoyaltyEntry{}
	}
	jsonOK(w, members)
}

// GET /merchants/{mid}/loyalty/{uid}
// Public — returns point balance for uid at this merchant.

func (h *handler) handleLoyaltyGet(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid"); return
	}
	uid, err := parseMID(r.PathValue("uid"))
	if err != nil {
		jsonErr(w, 400, "invalid uid"); return
	}
	pts, err := h.store.GetPoints(mid, uid)
	if err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	jsonOK(w, map[string]any{
		"uid":         uid,
		"mid":         mid,
		"points":      pts,
		"value_vnd":   pts * 100,
		"rate":        "1 point = 100 ₫ / 1,000 ₫ spent = 1 point",
	})
}

// POST /merchants/{mid}/loyalty/{uid}/award
// Header: X-Merchant-Token — manually award points (bonus, correction, etc.)
// Body: { "points": 50 }

func (h *handler) handleLoyaltyAward(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid"); return
	}
	uid, err := parseMID(r.PathValue("uid"))
	if err != nil {
		jsonErr(w, 400, "invalid uid"); return
	}
	m, err := h.store.Get(mid)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found"); return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized"); return
	}
	var req struct {
		Points int64 `json:"points"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Points == 0 {
		jsonErr(w, 400, "points required"); return
	}
	if err := h.store.AwardPoints(mid, uid, req.Points); err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	pts, _ := h.store.GetPoints(mid, uid)
	jsonOK(w, map[string]any{"uid": uid, "mid": mid, "points": pts})
}

// GET /loyalty/user/{uid}
// Public — returns all merchants where uid has loyalty points.

func (h *handler) handleUserLoyalty(w http.ResponseWriter, r *http.Request) {
	uid, err := parseMID(r.PathValue("uid"))
	if err != nil {
		jsonErr(w, 400, "invalid uid"); return
	}
	// Enforce: customer may only read their own loyalty (jwt sub == path uid).
	if jwtUID, ok := customerUIDFromCtx(r); ok && jwtUID != uid {
		jsonErr(w, 403, "forbidden"); return
	}
	entries, err := h.store.UserLoyalty(uid)
	if err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	if entries == nil {
		entries = []UserLoyaltyEntry{}
	}
	jsonOK(w, entries)
}

// GET /chat/{mid}/inbox
// Merchant reads all customer threads, newest first.
// Header: X-Merchant-Token

func (h *handler) handleChatInbox(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid")
		return
	}
	m, err := h.store.Get(mid)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found")
		return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized")
		return
	}
	items, err := h.store.GetChatInbox(mid)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if items == nil {
		items = []ChatInboxItem{}
	}
	jsonOK(w, items)
}

// POST /chat/{mid}
// Customer sends a message to a merchant.
// Body: { "uid": 16777216, "text": "xe xong chưa?" }

func (h *handler) handleChatSend(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid")
		return
	}
	if m, _ := h.store.Get(mid); m == nil {
		jsonErr(w, 404, "merchant not found")
		return
	}
	var req struct {
		UID  uint32 `json:"uid"`
		Text string `json:"text"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UID == 0 || req.Text == "" {
		jsonErr(w, 400, "uid and text required")
		return
	}
	if len(req.Text) > 500 {
		jsonErr(w, 400, "message too long")
		return
	}
	id, err := h.store.SendChatMessage(mid, req.UID, false, req.Text)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	jsonOK(w, map[string]any{"id": id})
}

// POST /chat/{mid}/reply
// Merchant replies to a customer thread.
// Header: X-Merchant-Token
// Body: { "uid": 16777216, "text": "xong rồi anh ơi" }

func (h *handler) handleChatReply(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid")
		return
	}
	m, err := h.store.Get(mid)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found")
		return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized")
		return
	}
	var req struct {
		UID  uint32 `json:"uid"`
		Text string `json:"text"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UID == 0 || req.Text == "" {
		jsonErr(w, 400, "uid and text required")
		return
	}
	if len(req.Text) > 500 {
		jsonErr(w, 400, "message too long")
		return
	}
	id, err := h.store.SendChatMessage(mid, req.UID, true, req.Text)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	jsonOK(w, map[string]any{"id": id})
}

// GET /chat/{mid}/{uid}?since={unix_millis}
// Returns messages in thread between merchant and customer after the given timestamp.
// Both parties can poll freely (no auth — support chat is not sensitive).

func (h *handler) handleChatThread(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid")
		return
	}
	uid, err := parseMID(r.PathValue("uid"))
	if err != nil {
		jsonErr(w, 400, "invalid uid")
		return
	}
	since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	msgs, err := h.store.GetChatThread(mid, uid, since)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if msgs == nil {
		msgs = []ChatMessage{}
	}
	jsonOK(w, msgs)
}

// GET /merchants/{mid}/menu — public, lists menu items.

func (h *handler) handleListMenu(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid"); return
	}
	items, err := h.store.ListMenuItems(mid)
	if err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	if items == nil {
		items = []MenuItem{}
	}
	jsonOK(w, items)
}

// POST /merchants/{mid}/menu
// Header: X-Merchant-Token
// Body: { "name":"Cà phê sữa", "price":25000, "description":"...", "sort_order":0 }

func (h *handler) handleAddMenuItem(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid"); return
	}
	m, err := h.store.Get(mid)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found"); return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized"); return
	}
	var req struct {
		Name        string `json:"name"`
		Price       uint64 `json:"price"`
		Description string `json:"description"`
		SortOrder   int    `json:"sort_order"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Name == "" {
		jsonErr(w, 400, "name required"); return
	}
	id, err := h.store.AddMenuItem(mid, req.Name, req.Price, req.Description, req.SortOrder)
	if err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	jsonOK(w, map[string]any{"id": id})
}

// DELETE /merchants/{mid}/menu/{id}
// Header: X-Merchant-Token

func (h *handler) handleDeleteMenuItem(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid"); return
	}
	itemID, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		jsonErr(w, 400, "invalid id"); return
	}
	m, err := h.store.Get(mid)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found"); return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized"); return
	}
	if err := h.store.DeleteMenuItem(itemID, mid); err != nil {
		jsonErr(w, 404, err.Error()); return
	}
	jsonOK(w, map[string]string{"status": "deleted"})
}

// PATCH /merchants/{mid}
// Header: X-Merchant-Token
// Body: { "name":"...", "address":"...", "website":"..." } — all fields optional.

func (h *handler) handleUpdateProfile(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid"); return
	}
	m, err := h.store.Get(mid)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found"); return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized"); return
	}
	var req struct {
		Name    *string `json:"name"`
		Address *string `json:"address"`
		Website *string `json:"website"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, 400, "bad json"); return
	}
	if req.Name != nil {
		if len(*req.Name) == 0 || len(*req.Name) > 64 {
			jsonErr(w, 400, "name must be 1–64 chars"); return
		}
		m.Name = *req.Name
	}
	if req.Address != nil { m.Address = *req.Address }
	if req.Website != nil { m.Website = *req.Website }

	if err := h.store.UpdateProfile(mid, m.Name, m.Address, m.Website); err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	jsonOK(w, m)
}

// PATCH /merchants/{mid}/slug
// Header: X-Merchant-Token
// Body: { "slug": "my-shop" }

func (h *handler) handleUpdateSlug(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid"); return
	}
	m, err := h.store.Get(mid)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found"); return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized"); return
	}
	var req struct {
		Slug string `json:"slug"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Slug == "" {
		jsonErr(w, 400, "slug required"); return
	}
	req.Slug = slugify(req.Slug)
	if len(req.Slug) < 2 || len(req.Slug) > 40 {
		jsonErr(w, 400, "slug must be 2–40 chars"); return
	}
	exists, _ := h.store.SlugExists(req.Slug)
	if exists && req.Slug != m.Slug {
		jsonErr(w, 409, "slug already taken"); return
	}
	if err := h.store.SetSlug(mid, req.Slug); err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	jsonOK(w, map[string]string{"slug": req.Slug, "url": "https://" + req.Slug + ".nivic.dev"})
}

// POST /public/{mid}/order — no auth needed; signed by merchant's Ed25519 key.
// Body: { "amount": 50000, "note": "cà phê x2" }

func (h *handler) handlePublicCreateOrder(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid"); return
	}
	m, err := h.store.Get(mid)
	if err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	if m == nil {
		jsonErr(w, 404, "merchant not found"); return
	}
	var req struct {
		Amount uint64 `json:"amount"`
		Note   string `json:"note"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, 400, "bad json"); return
	}
	if req.Amount == 0 {
		jsonErr(w, 400, "amount required"); return
	}
	orderID := fmt.Sprintf("pub-%d-%d", mid, time.Now().UnixMilli())
	if err := h.store.CreateOrder(orderID, mid, req.Amount, req.Note, 0); err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	pr, err := buildPaymentRequest(m, orderID, req.Amount)
	if err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	prB64, err := prToBase64URL(pr)
	if err != nil {
		jsonErr(w, 500, "encode failed"); return
	}
	scheme := "https"
	if r.TLS == nil && r.Header.Get("X-Forwarded-Proto") != "https" {
		scheme = "http"
	}
	payURL := scheme + "://" + r.Host + "/pay/" + orderID
	rid := h.wireCreateIntent(mid, m.PasswordHash, req.Amount, orderID)
	jsonOK(w, map[string]any{
		"order_id":   orderID,
		"pr":         prB64,
		"qr_url":     "saving://pay?pr=" + prB64,
		"pay_url":    payURL,
		"wire_url":   wireStoreURL(mid),
		"intent_url": wireIntentURL(mid, rid, req.Amount, orderID),
		"request_id": rid,
	})
}

// PATCH /merchants/{mid}/domain
// Header: X-Merchant-Token
// Body: { "domain": "bmapworkshop.com" }
// Merchant must first add a CNAME/A record pointing to this server.

func (h *handler) handleSetDomain(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid"); return
	}
	m, err := h.store.Get(mid)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found"); return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized"); return
	}
	var req struct {
		Domain string `json:"domain"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Domain == "" {
		jsonErr(w, 400, "domain required"); return
	}
	// Strip scheme if accidentally included
	req.Domain = strings.TrimPrefix(strings.TrimPrefix(req.Domain, "https://"), "http://")
	req.Domain = strings.TrimRight(req.Domain, "/")
	if len(req.Domain) < 4 || len(req.Domain) > 253 {
		jsonErr(w, 400, "invalid domain"); return
	}
	exists, _ := h.store.DomainExists(req.Domain)
	if exists && req.Domain != m.CustomDomain {
		jsonErr(w, 409, "domain already registered to another merchant"); return
	}
	if err := h.store.SetCustomDomain(mid, req.Domain); err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	jsonOK(w, map[string]string{
		"domain": req.Domain,
		"status": "registered",
		"note":   "Add a CNAME record: " + req.Domain + " → " + m.Slug + ".nivic.dev",
	})
}

// DELETE /merchants/{mid}/domain
// Header: X-Merchant-Token

func (h *handler) handleRemoveDomain(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid"); return
	}
	m, err := h.store.Get(mid)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found"); return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized"); return
	}
	_ = h.store.SetCustomDomain(mid, "")
	jsonOK(w, map[string]string{"status": "removed"})
}

// POST /admin/migrate-slugs — one-time backfill for merchants without slugs.
// Header: X-Admin-Token

func (h *handler) handleMigrateSlugs(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-Admin-Token") != h.adminToken {
		jsonErr(w, 401, "unauthorized"); return
	}
	updated, err := h.store.BackfillSlugs()
	if err != nil {
		jsonErr(w, 500, err.Error()); return
	}
	jsonOK(w, map[string]any{"updated": updated})
}

// GET /caddy/check-domain?domain=bmapworkshop.com
// Called by Caddy on_demand TLS before issuing a certificate.
// Returns 200 if domain is a known merchant subdomain or custom domain, 404 otherwise.

func (h *handler) handleCaddyCheckDomain(w http.ResponseWriter, r *http.Request) {
	domain := r.URL.Query().Get("domain")
	if domain == "" {
		w.WriteHeader(400); return
	}
	// Approve any *.nivic.dev subdomain that maps to a merchant slug
	const suffix = ".nivic.dev"
	if strings.HasSuffix(domain, suffix) {
		slug := strings.TrimSuffix(domain, suffix)
		if slug != "" && slug != "saving" && slug != "www" && slug != "api" {
			m, _ := h.store.GetBySlug(slug)
			if m != nil {
				w.WriteHeader(200); return
			}
		}
		w.WriteHeader(404); return
	}
	// Approve registered custom domains
	exists, err := h.store.DomainExists(domain)
	if err != nil || !exists {
		w.WriteHeader(404); return
	}
	w.WriteHeader(200)
}

// ─── Merchant public page ─────────────────────────────────────────────────────

func (h *handler) handleMerchantPage(w http.ResponseWriter, r *http.Request, m *Merchant) {
	items, _ := h.store.ListMenuItems(m.MID)
	if items == nil {
		items = []MenuItem{}
	}
	renderMerchantPage(w, m, items)
}

// ─── Helpers ────────────────────────────────────────────────────────────────

func parseMID(s string) (uint32, error) {
	v, err := strconv.ParseUint(s, 10, 32)
	return uint32(v), err
}

func jsonOK(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func jsonErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

// GET /pay/{order_id} — universal pay page (Shopify model)
func (h *handler) handlePayPage(w http.ResponseWriter, r *http.Request) {
	orderID := r.PathValue("order_id")
	o, err := h.store.GetOrder(orderID)
	if err != nil || o == nil {
		http.Error(w, "Không tìm thấy đơn hàng", 404)
		return
	}
	m, err := h.store.Get(o.MID)
	if err != nil || m == nil {
		http.Error(w, "Merchant not found", 404)
		return
	}
	pr, err := buildPaymentRequest(m, orderID, o.Amount)
	if err != nil {
		http.Error(w, "error", 500)
		return
	}
	prB64, _ := prToBase64URL(pr)
	rid := o.WireRequestID
	if rid == 0 {
		rid = uint64(o.CreatedAt)
	}
	renderPayPage(w, payPageData{
		OrderID:      orderID,
		MerchantName: m.Name,
		Amount:       o.Amount,
		Note:         o.Note,
		DeepLink:     template.URL(wireIntentURL(o.MID, rid, o.Amount, orderID)),
		QrLink:       template.URL("saving://pay?pr=" + prB64),
		Status:       o.Status,
	})
}

// GET /pay/{order_id}/wire — JSON deeplinks for Wire app (https pay page / app links).

func (h *handler) handlePayOrderWire(w http.ResponseWriter, r *http.Request) {
	orderID := r.PathValue("order_id")
	o, err := h.store.GetOrder(orderID)
	if err != nil || o == nil {
		jsonErr(w, 404, "not found")
		return
	}
	rid := o.WireRequestID
	if rid == 0 {
		rid = uint64(o.CreatedAt)
	}
	jsonOK(w, map[string]any{
		"order_id":   orderID,
		"mid":        o.MID,
		"amount":     o.Amount,
		"status":     o.Status,
		"request_id": rid,
		"intent_url": wireIntentURL(o.MID, rid, o.Amount, orderID),
		"store_url":  wireStoreURL(o.MID),
	})
}

// GET /pay/{order_id}/status — poll for payment completion
func (h *handler) handlePayStatus(w http.ResponseWriter, r *http.Request) {
	orderID := r.PathValue("order_id")
	o, err := h.store.GetOrder(orderID)
	if err != nil || o == nil {
		jsonErr(w, 404, "not found")
		return
	}
	jsonOK(w, map[string]any{"status": o.Status})
}

func formatVND(amount int64) string {
	s := strconv.FormatInt(amount, 10)
	out := []byte{}
	for i, c := range s {
		if i > 0 && (len(s)-i)%3 == 0 {
			out = append(out, '.')
		}
		out = append(out, byte(c))
	}
	return string(out)
}

// ─── Customer Auth (BFF JWT) ─────────────────────────────────────────────────

// slugFromHost extracts the merchant slug from the Host header (e.g. "bmap.nivic.dev" → "bmap").
func slugFromHost(host string) string {
	if i := strings.LastIndex(host, ":"); i != -1 {
		host = host[:i]
	}
	const suffix = ".nivic.dev"
	if strings.HasSuffix(host, suffix) {
		slug := strings.TrimSuffix(host, suffix)
		if slug != "" && slug != "saving" && slug != "www" && slug != "api" && slug != "ops" && slug != "go" {
			return slug
		}
	}
	return ""
}

// ctxKey is a private context key type for customer auth values.
type ctxKey int

const ctxCustomerUID ctxKey = 1

// customerUIDFromCtx returns the customer uid injected by withCustomerAuth.
func customerUIDFromCtx(r *http.Request) (uint32, bool) {
	uid, ok := r.Context().Value(ctxCustomerUID).(uint32)
	return uid, ok
}

// requireCustomerAuth extracts and verifies the BFF JWT.
// Enforces aud == merchant slug from Host header (when on a merchant subdomain).
// Returns (uid, slug) on success.
func (h *handler) requireCustomerAuth(r *http.Request) (uint32, string, error) {
	if h.jwtSecret == "" {
		return 0, "", errors.New("auth not configured")
	}
	auth := r.Header.Get("Authorization")
	token := strings.TrimPrefix(auth, "Bearer ")
	if token == "" || token == auth {
		return 0, "", errors.New("missing token")
	}
	uid, aud, err := jwtVerify(token, h.jwtSecret)
	if err != nil {
		return 0, "", err
	}
	// Enforce aud == merchant slug so a token from shop-a cannot be used on shop-b.
	if slug := slugFromHost(r.Host); slug != "" && aud != slug {
		return 0, "", errors.New("token not valid for this merchant")
	}
	return uid, aud, nil
}

// withCustomerAuth is middleware: verifies JWT + aud==slug, injects uid into context.
func (h *handler) withCustomerAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		uid, _, err := h.requireCustomerAuth(r)
		if err != nil {
			jsonErr(w, 401, err.Error())
			return
		}
		r = r.WithContext(context.WithValue(r.Context(), ctxCustomerUID, uid))
		next(w, r)
	}
}

// POST /auth/login
// Body: {"uid": 16777216, "pw_hash": "<sha256hex>"}
// Verifies against Wire core, issues BFF JWT scoped to this merchant subdomain.
func (h *handler) handleCustomerLogin(w http.ResponseWriter, r *http.Request) {
	if h.jwtSecret == "" {
		jsonErr(w, 503, "auth not configured")
		return
	}
	slug := slugFromHost(r.Host)
	if slug == "" {
		jsonErr(w, 400, "no merchant context — call from <slug>.nivic.dev")
		return
	}

	var req struct {
		UID    uint32 `json:"uid"`
		PWHash string `json:"pw_hash"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UID == 0 || req.PWHash == "" {
		jsonErr(w, 400, "uid and pw_hash required")
		return
	}

	if _, err := WireLogin(h.wireAddr, req.UID, req.PWHash); err != nil {
		slog.Warn("customer login failed", "uid", req.UID, "slug", slug, "err", err)
		jsonErr(w, 401, "invalid credentials")
		return
	}

	token, err := jwtIssue(req.UID, slug, h.jwtSecret)
	if err != nil {
		jsonErr(w, 500, "token error")
		return
	}
	jsonOK(w, map[string]string{"token": token})
}

// GET /auth/me — uid + merchant injected by withCustomerAuth middleware.
func (h *handler) handleCustomerMe(w http.ResponseWriter, r *http.Request) {
	uid, _ := customerUIDFromCtx(r)
	slug := slugFromHost(r.Host)
	jsonOK(w, map[string]any{"uid": uid, "merchant": slug})
}

// POST /pay (customer-protected)
// Body: {"amount": 50000, "note": "Coffee", "use_points": true}
// Merchant resolved from JWT aud (subdomain slug). Customer uid from JWT sub.
// Applies loyalty discount when use_points=true, creates Wire payment intent.
func (h *handler) handleCustomerPay(w http.ResponseWriter, r *http.Request) {
	uid, _ := customerUIDFromCtx(r)

	slug := slugFromHost(r.Host)
	if slug == "" {
		jsonErr(w, 400, "no merchant context")
		return
	}
	m, err := h.store.GetBySlug(slug)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found")
		return
	}

	var req struct {
		Amount    uint64 `json:"amount"`
		Note      string `json:"note"`
		UsePoints bool   `json:"use_points"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Amount == 0 {
		jsonErr(w, 400, "amount required")
		return
	}

	// Apply loyalty discount if requested.
	var pointsUsed int64
	finalAmount := req.Amount
	if req.UsePoints {
		pts, _ := h.store.GetPoints(m.MID, uid)
		if pts > 0 {
			// 1 point = 1 VND discount, capped at the order amount.
			discount := uint64(pts)
			if discount > req.Amount {
				discount = req.Amount
			}
			finalAmount = req.Amount - discount
			pointsUsed = int64(discount)
		}
	}

	orderID := fmt.Sprintf("pay-%d-%d-%d", m.MID, uid, time.Now().UnixMilli())
	if err := h.store.CreateOrder(orderID, m.MID, req.Amount, req.Note, pointsUsed); err != nil {
		jsonErr(w, 500, "create order failed")
		return
	}

	rid := h.wireCreateIntent(m.MID, m.PasswordHash, finalAmount, orderID)

	scheme := "https"
	if r.TLS == nil && r.Header.Get("X-Forwarded-Proto") != "https" {
		scheme = "http"
	}
	jsonOK(w, map[string]any{
		"order_id":     orderID,
		"uid":          uid,
		"amount":       req.Amount,
		"discount":     pointsUsed,
		"final_amount": finalAmount,
		"pay_url":      scheme + "://" + r.Host + "/pay/" + orderID,
		"intent_url":   wireIntentURL(m.MID, rid, finalAmount, orderID),
		"qr_url":       "saving://pay?pr=" + func() string { p, _ := buildPaymentRequest(m, orderID, finalAmount); b, _ := prToBase64URL(p); return b }(),
		"request_id":   rid,
	})
}

// POST /pay/confirm (customer-protected)
// Body: {"order_id": "pay-...", "totp_code": 123456}
// Submits TOTP_CHARGE on Wire (merchant debits customer), then marks order paid.
func (h *handler) handleCustomerPayConfirm(w http.ResponseWriter, r *http.Request) {
	uid, _ := customerUIDFromCtx(r)

	slug := slugFromHost(r.Host)
	if slug == "" {
		jsonErr(w, 400, "no merchant context")
		return
	}
	m, err := h.store.GetBySlug(slug)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found")
		return
	}

	var req struct {
		OrderID  string `json:"order_id"`
		TOTPCode uint32 `json:"totp_code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.OrderID == "" || req.TOTPCode == 0 {
		jsonErr(w, 400, "order_id and totp_code required")
		return
	}

	o, err := h.store.GetOrder(req.OrderID)
	if err != nil || o == nil {
		jsonErr(w, 404, "order not found")
		return
	}
	if o.MID != m.MID {
		jsonErr(w, 403, "order does not belong to this merchant")
		return
	}
	if o.Status != StatusPending {
		jsonErr(w, 409, "order already settled")
		return
	}

	// Charge via Wire: merchant session debits customer, credits merchant.
	chargeAmount := o.Amount
	if o.DiscountPoints > 0 && uint64(o.DiscountPoints) <= o.Amount {
		chargeAmount = o.Amount - uint64(o.DiscountPoints)
	}
	if err := WireTOTPCharge(h.wireAddr, m.MID, m.PasswordHash, uid, req.TOTPCode, chargeAmount); err != nil {
		slog.Warn("TOTP_CHARGE failed", "uid", uid, "mid", m.MID, "order", req.OrderID, "err", err)
		jsonErr(w, 402, err.Error())
		return
	}

	pts, err := h.store.MarkPaid(req.OrderID, uid)
	if err != nil {
		jsonErr(w, 500, "mark paid failed")
		return
	}

	// Notify merchant (non-blocking)
	go func() {
		if m.Email != "" {
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			_ = h.mailer.send(ctx, m.Email,
				fmt.Sprintf("✅ Đơn hàng %s đã được thanh toán", req.OrderID[max(0, len(req.OrderID)-8):]),
				emailOrderPaid(m.Name, req.OrderID, o.Amount, pts),
			)
		}
		msg := fmt.Sprintf("✅ Đơn hàng %s đã được thanh toán!\nSố tiền: %s₫", req.OrderID[max(0, len(req.OrderID)-8):], formatVND(int64(o.Amount)))
		if pts > 0 {
			msg += fmt.Sprintf("\n🌟 Bạn được cộng %d điểm tích luỹ.", pts)
		}
		_, _ = h.store.SendChatMessage(m.MID, uid, true, msg)
	}()

	jsonOK(w, map[string]any{
		"status":         "paid",
		"order_id":       req.OrderID,
		"amount_charged": chargeAmount,
		"points_awarded": pts,
	})
}

// POST /merchants/{mid}/qr
// Header: X-Merchant-Token
// Body: { "amount": 50000, "note": "Coffee", "acs_url": "https://..." }
// Returns: { "qr_url": "saving://acs?..." }
func (h *handler) handleGenerateQR(w http.ResponseWriter, r *http.Request) {
	mid, err := parseMID(r.PathValue("mid"))
	if err != nil {
		jsonErr(w, 400, "invalid mid"); return
	}
	m, err := h.store.Get(mid)
	if err != nil || m == nil {
		jsonErr(w, 404, "merchant not found"); return
	}
	if r.Header.Get("X-Merchant-Token") != m.Token {
		jsonErr(w, 401, "unauthorized"); return
	}

	var req struct {
		Amount int64  `json:"amount"`
		Note   string `json:"note"`
		AcsURL string `json:"acs_url"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, 400, "invalid body"); return
	}
	if req.Amount <= 0 {
		jsonErr(w, 400, "amount required"); return
	}

	// Verify merchant has a signing key
	if _, err := base64.StdEncoding.DecodeString(m.PrivkeyB64); err != nil {
		jsonErr(w, 500, "merchant key not configured"); return
	}

	acsURL := req.AcsURL
	if acsURL == "" {
		slug := m.Slug
		if slug == "" {
			slug = fmt.Sprintf("%d", mid)
		}
		acsURL = fmt.Sprintf("https://%s.nivic.dev/acs/default", slug)
	}

	// Generate short random token (8 bytes → 11 base64url chars)
	tokenBytes := make([]byte, 8)
	if _, err := rand.Read(tokenBytes); err != nil {
		jsonErr(w, 500, "token generation failed"); return
	}
	token := base64.URLEncoding.WithPadding(base64.NoPadding).EncodeToString(tokenBytes)

	if err := h.store.CreateQRToken(token, mid, req.Amount, req.Note, acsURL); err != nil {
		jsonErr(w, 500, "store failed"); return
	}

	slug := m.Slug
	if slug == "" {
		slug = fmt.Sprintf("%d", mid)
	}
	qrURL := fmt.Sprintf("https://%s.nivic.dev/qr/%s", slug, token)

	jsonOK(w, map[string]any{
		"qr_url": qrURL,
		"token":  token,
		"mid":    mid,
		"amount": req.Amount,
	})
}

// GET /qr/{token} — returns HTML with saving-pay meta tag; app fetches this to resolve payment params.
func (h *handler) handleQRPage(w http.ResponseWriter, r *http.Request) {
	token := r.PathValue("token")
	qt, err := h.store.GetQRToken(token)
	if err != nil || qt == nil {
		http.NotFound(w, r); return
	}

	m, err := h.store.Get(qt.MID)
	if err != nil || m == nil {
		http.NotFound(w, r); return
	}

	privKeyBytes, err := base64.StdEncoding.DecodeString(m.PrivkeyB64)
	if err != nil || len(privKeyBytes) != ed25519.PrivateKeySize {
		http.Error(w, "key error", 500); return
	}

	ts := time.Now().Unix()
	ref := token // QR token IS the idempotency ref
	// Signed msg: mid(4BE) || amount(8BE) || ts(8BE) || ref
	msg := make([]byte, 20+len(ref))
	binary.BigEndian.PutUint32(msg[0:4], qt.MID)
	binary.BigEndian.PutUint64(msg[4:12], uint64(qt.Amount))
	binary.BigEndian.PutUint64(msg[12:20], uint64(ts))
	copy(msg[20:], ref)
	sig    := ed25519.Sign(ed25519.PrivateKey(privKeyBytes), msg)
	sigB64 := base64.URLEncoding.WithPadding(base64.NoPadding).EncodeToString(sig)

	// meta content is query-string encoded so app can reuse AcsPayload.parse("saving://acs?"+content)
	metaContent := fmt.Sprintf("mid=%d&amount=%d&ts=%d&sig=%s&ref=%s&acs=%s&note=%s",
		qt.MID, qt.Amount, ts, sigB64, ref,
		strings.ReplaceAll(qt.AcsURL, "&", "%26"),
		strings.ReplaceAll(qt.Note, "&", "%26"),
	)

	amountFmt := fmt.Sprintf("%s ₫", formatVND(qt.Amount))
	_ = ref // used in metaContent above
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="saving-pay" content="%s">
<title>Thanh toán %s - %s</title>
<style>
body{background:#000;color:#fff;font-family:-apple-system,sans-serif;display:flex;align-items:center;
justify-content:center;min-height:100vh;margin:0;text-align:center;padding:24px;box-sizing:border-box}
.card{max-width:360px;width:100%%}
.amt{font-size:2.5rem;font-weight:900;margin:16px 0}
.note{color:#888;font-size:.9rem;margin-bottom:24px}
.btn{display:inline-block;background:#fff;color:#000;padding:14px 32px;border-radius:14px;
text-decoration:none;font-weight:700;font-size:1rem}
.sub{color:#555;font-size:.75rem;margin-top:16px}
</style>
</head>
<body>
<div class="card">
<div style="font-size:3rem">🏪</div>
<h2 style="margin:8px 0">%s</h2>
<div class="amt">%s</div>
%s
<a href="saving://acs?%s" class="btn">Mở Wire để thanh toán</a>
<div class="sub">Hoặc quét QR này bằng Wire app</div>
</div>
</body>
</html>`,
		metaContent,
		amountFmt, m.Name,
		m.Name,
		amountFmt,
		func() string {
			if qt.Note != "" { return `<div class="note">` + qt.Note + `</div>` }
			return ""
		}(),
		metaContent,
	)
}

// POST /acs/{ref}
// Wire server POSTs here after QR_PAY completes.
// Body: { "mid": N, "customer_id": N, "amount": N, "txn_id": N, "status": "paid" }
func (h *handler) handleACSCallback(w http.ResponseWriter, r *http.Request) {
	ref := r.PathValue("ref")

	var cb struct {
		MID        uint32 `json:"mid"`
		CustomerID uint32 `json:"customer_id"`
		Amount     int64  `json:"amount"`
		TxnID      int64  `json:"txn_id"`
		Status     string `json:"status"`
	}
	if err := json.NewDecoder(r.Body).Decode(&cb); err != nil {
		w.WriteHeader(400); return
	}

	slog.Info("acs callback", "ref", ref, "mid", cb.MID,
		"customer", cb.CustomerID, "amount", cb.Amount,
		"txn_id", cb.TxnID, "status", cb.Status)

	if ref != "default" && cb.Status == "paid" {
		_ = h.store.MarkOrderPaidByRef(ref, cb.CustomerID, cb.TxnID)
	}

	w.WriteHeader(200)
}
