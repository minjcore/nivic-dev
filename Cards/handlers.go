package main

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type handler struct {
	store     *Store
	wireToken string
	pub       *Publisher // nil when AMQP unavailable
}

func (h *handler) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health",                           h.handleHealth)
	mux.HandleFunc("GET /users/{uid}/cards",                h.handleList)
	mux.HandleFunc("POST /users/{uid}/cards",               h.handleAdd)
	mux.HandleFunc("DELETE /users/{uid}/cards/{cid}",       h.handleRemove)
	mux.HandleFunc("POST /users/{uid}/cards/{cid}/topup",   h.handleTopUp)
	mux.HandleFunc("POST /users/{uid}/device-token",        h.handleRegisterDeviceToken)
	mux.HandleFunc("POST /topups/{tid}/complete",           h.handleComplete) // called by Wire
	return mux
}

func (h *handler) handleHealth(w http.ResponseWriter, _ *http.Request) {
	jsonOK(w, map[string]string{"status": "ok", "service": "cards"})
}

// Auth: X-Wire-Token (user proves identity via shared wire secret)
// In production: verify user's session token directly with Wire server.

func (h *handler) authUID(r *http.Request) bool {
	return r.Header.Get("X-Wire-Token") == h.wireToken
}

// GET /users/{uid}/cards

func (h *handler) handleList(w http.ResponseWriter, r *http.Request) {
	if !h.authUID(r) {
		jsonErr(w, 401, "unauthorized")
		return
	}
	uid, err := parseUID(r.PathValue("uid"))
	if err != nil {
		jsonErr(w, 400, "invalid uid")
		return
	}
	cards, err := h.store.ListCards(uid)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if cards == nil {
		cards = []Card{}
	}
	jsonOK(w, cards)
}

// POST /users/{uid}/cards
// Body: { "last4":"1234", "bank":"VCB", "expiry":"12/27", "label":"Thẻ chính" }

func (h *handler) handleAdd(w http.ResponseWriter, r *http.Request) {
	if !h.authUID(r) {
		jsonErr(w, 401, "unauthorized")
		return
	}
	uid, err := parseUID(r.PathValue("uid"))
	if err != nil {
		jsonErr(w, 400, "invalid uid")
		return
	}
	var req struct {
		Last4  string `json:"last4"`
		Bank   string `json:"bank"`
		Expiry string `json:"expiry"`
		Label  string `json:"label"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, 400, "bad json")
		return
	}
	if len(req.Last4) != 4 || req.Bank == "" || req.Expiry == "" {
		jsonErr(w, 400, "last4 (4 digits), bank, expiry required")
		return
	}
	if !validExpiry(req.Expiry) {
		jsonErr(w, 400, "expiry must be MM/YY and not expired")
		return
	}

	id := fmt.Sprintf("%d-%d", uid, time.Now().UnixMilli())
	if err := h.store.AddCard(id, uid, req.Last4, req.Bank, req.Expiry, req.Label); err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	jsonOK(w, map[string]any{"card_id": id})
}

// DELETE /users/{uid}/cards/{cid}

func (h *handler) handleRemove(w http.ResponseWriter, r *http.Request) {
	if !h.authUID(r) {
		jsonErr(w, 401, "unauthorized")
		return
	}
	uid, err := parseUID(r.PathValue("uid"))
	if err != nil {
		jsonErr(w, 400, "invalid uid")
		return
	}
	if err := h.store.RemoveCard(r.PathValue("cid"), uid); err != nil {
		jsonErr(w, 404, err.Error())
		return
	}
	jsonOK(w, map[string]string{"status": "removed"})
}

// POST /users/{uid}/cards/{cid}/topup
// Body: { "amount": 100000 }
// Creates a pending top-up; Wire server calls /topups/{tid}/complete after crediting wallet.

func (h *handler) handleTopUp(w http.ResponseWriter, r *http.Request) {
	if !h.authUID(r) {
		jsonErr(w, 401, "unauthorized")
		return
	}
	uid, err := parseUID(r.PathValue("uid"))
	if err != nil {
		jsonErr(w, 400, "invalid uid")
		return
	}
	card, err := h.store.GetCard(r.PathValue("cid"), uid)
	if err != nil {
		jsonErr(w, 500, err.Error())
		return
	}
	if card == nil || card.Status != "active" {
		jsonErr(w, 404, "card not found")
		return
	}

	var req struct {
		Amount uint64 `json:"amount"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, 400, "bad json")
		return
	}
	if req.Amount < 10000 {
		jsonErr(w, 400, "minimum top-up 10,000 VND")
		return
	}

	tid := fmt.Sprintf("TU-%d-%d", uid, time.Now().UnixMilli())
	if err := h.store.CreateTopUp(tid, card.ID, uid, req.Amount); err != nil {
		jsonErr(w, 500, err.Error())
		return
	}

	// Publish async event → Topup Worker → Wire Server credit
	if h.pub != nil {
		evt := TopUpEvent{TopUpID: tid, UID: uid, CardID: card.ID, Amount: req.Amount}
		if err := h.pub.PublishTopUp(evt); err != nil {
			// Non-fatal: topup is persisted, can be retried
			slog.Warn("publish topup event failed", "topup_id", tid, "err", err)
		}
	}

	jsonOK(w, map[string]any{
		"topup_id": tid,
		"amount":   req.Amount,
		"status":   "pending",
	})
}

// POST /users/{uid}/device-token
// Body: { "token": "<hex apns token>" }

func (h *handler) handleRegisterDeviceToken(w http.ResponseWriter, r *http.Request) {
	if !h.authUID(r) {
		jsonErr(w, 401, "unauthorized")
		return
	}
	uid, err := parseUID(r.PathValue("uid"))
	if err != nil {
		jsonErr(w, 400, "bad uid")
		return
	}
	var body struct {
		Token string `json:"token"`
	}
	if json.NewDecoder(r.Body).Decode(&body) != nil || body.Token == "" {
		jsonErr(w, 400, "token required")
		return
	}
	if err := h.store.RegisterDeviceToken(uid, body.Token); err != nil {
		slog.Error("register device token", "uid", uid, "err", err)
		jsonErr(w, 500, "store error")
		return
	}
	slog.Info("device token registered", "uid", uid)
	w.WriteHeader(http.StatusNoContent)
}

// POST /topups/{tid}/complete
// Body: { "status":"done" }   or   { "status":"failed" }
// Called by Wire server after attempting to credit the user wallet.

func (h *handler) handleComplete(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-Wire-Token") != h.wireToken {
		jsonErr(w, 401, "unauthorized")
		return
	}
	var req struct {
		Status string `json:"status"` // "done" | "failed"
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, 400, "bad json")
		return
	}
	if req.Status != "done" && req.Status != "failed" {
		jsonErr(w, 400, `status must be "done" or "failed"`)
		return
	}
	if err := h.store.CompleteTopUp(r.PathValue("tid"), req.Status); err != nil {
		jsonErr(w, 400, err.Error())
		return
	}
	jsonOK(w, map[string]string{"status": req.Status})
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

func parseUID(s string) (uint32, error) {
	v, err := strconv.ParseUint(s, 10, 32)
	return uint32(v), err
}

// validExpiry checks MM/YY format and that the card hasn't expired.
func validExpiry(s string) bool {
	parts := strings.Split(s, "/")
	if len(parts) != 2 || len(parts[0]) != 2 || len(parts[1]) != 2 {
		return false
	}
	month, err1 := strconv.Atoi(parts[0])
	year, err2 := strconv.Atoi(parts[1])
	if err1 != nil || err2 != nil || month < 1 || month > 12 {
		return false
	}
	now := time.Now()
	fullYear := 2000 + year
	exp := time.Date(fullYear, time.Month(month)+1, 1, 0, 0, 0, 0, time.UTC)
	return exp.After(now)
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
