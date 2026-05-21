package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"
)

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
	store      *Store
	adminToken string
}

func (h *handler) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health",                              h.handleHealth)
	mux.HandleFunc("GET /merchants",                           h.handleSearch)
	mux.HandleFunc("GET /merchants/{mid}",                     h.handleGet)
	mux.HandleFunc("POST /merchants",                          h.handleRegister)
	mux.HandleFunc("POST /merchants/onboard",                  h.handleOnboard)
	mux.HandleFunc("POST /merchants/{mid}/orders",             h.handleCreateOrder)
	mux.HandleFunc("GET /merchants/{mid}/orders",              h.handleListOrders)
	mux.HandleFunc("GET /merchants/{mid}/orders/{oid}",        h.handleGetOrder)
	mux.HandleFunc("GET /merchants/{mid}/stats",               h.handleStats)
	mux.HandleFunc("GET /merchants/{mid}/loyalty",             h.handleLoyaltyMembers)
	mux.HandleFunc("GET /merchants/{mid}/loyalty/{uid}",       h.handleLoyaltyGet)
	mux.HandleFunc("POST /merchants/{mid}/loyalty/{uid}/award",h.handleLoyaltyAward)
	mux.HandleFunc("GET /loyalty/user/{uid}",                  h.handleUserLoyalty)
	mux.HandleFunc("POST /orders/{oid}/confirm",               h.handleConfirmOrder)
	mux.HandleFunc("POST /merchants/{mid}/orders/{oid}/confirm", h.handleMerchantConfirmOrder)
	mux.HandleFunc("POST /payment_request/verify",             h.handleVerify)
	mux.HandleFunc("POST /chat/{mid}",                         h.handleChatSend)
	mux.HandleFunc("POST /chat/{mid}/reply",                   h.handleChatReply)
	mux.HandleFunc("GET /chat/{mid}/inbox",                    h.handleChatInbox)
	mux.HandleFunc("GET /chat/{mid}/{uid}",                    h.handleChatThread)
	return mux
}

// GET /health

func (h *handler) handleHealth(w http.ResponseWriter, _ *http.Request) {
	jsonOK(w, map[string]string{"status": "ok", "service": "merchants-host"})
}

// GET /merchants?q={query}
// Public — search merchants by name.

func (h *handler) handleSearch(w http.ResponseWriter, r *http.Request) {
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
		MID  uint32 `json:"mid"`
		Name string `json:"name"`
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

	if err := h.store.Register(req.MID, req.Name, pubB64, privB64, token); err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	// Return privkey and token once — caller must store securely
	jsonOK(w, map[string]any{
		"registered":  req.MID,
		"pubkey_b64":  pubB64,
		"privkey_b64": privB64,
		"token":       token,
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

	jsonOK(w, map[string]any{
		"order_id": orderID,
		"pr":       prB64,
		"qr_url":   "saving://pay?pr=" + prB64,
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
		UID  uint32 `json:"uid"`
		Name string `json:"name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, 400, "bad json")
		return
	}
	if req.UID == 0 || req.Name == "" {
		jsonErr(w, 400, "uid and name required")
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
	token   := base64.URLEncoding.EncodeToString(tokenBytes)
	pubB64  := base64.StdEncoding.EncodeToString(pub)
	privB64 := base64.StdEncoding.EncodeToString(priv)

	if err := h.store.Register(req.UID, req.Name, pubB64, privB64, token); err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	jsonOK(w, map[string]any{
		"mid":   req.UID,
		"name":  req.Name,
		"token": token,
	})
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
