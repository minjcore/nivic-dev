package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httputil"
	"net/url"
	"time"
)

func addWebRoutes(mux *http.ServeMux, authURL, wireAddr, staticDir, floatPwd string, store *Store, mailer *Mailer) {
	mux.Handle("GET /", http.FileServer(http.Dir(staticDir)))

	// ── Register ───────────────────────────────────────────────────────────────
	mux.HandleFunc("POST /api/register", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			ID       uint32 `json:"id"`
			Password string `json:"password"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == 0 || req.Password == "" {
			http.Error(w, "id and password required", http.StatusBadRequest)
			return
		}
		hash := sha256.Sum256([]byte(req.Password))
		if err := wireCreateAccount(wireAddr, req.ID, hash[:]); err != nil {
			writeWireErr(w, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})

	// ── Login: proxy → auth service → set JWT cookie ──────────────────────────
	mux.HandleFunc("POST /api/login", func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		resp, err := http.Post(authURL+"/login", "application/json", bytes.NewReader(body))
		if err != nil {
			http.Error(w, "auth service unavailable", http.StatusServiceUnavailable)
			return
		}
		defer resp.Body.Close()
		respBody, _ := io.ReadAll(resp.Body)
		if resp.StatusCode != http.StatusOK {
			http.Error(w, string(respBody), resp.StatusCode)
			return
		}
		var data struct {
			Token string `json:"token"`
			UID   uint32 `json:"uid"`
		}
		if err := json.Unmarshal(respBody, &data); err != nil || data.Token == "" {
			http.Error(w, "invalid auth response", http.StatusInternalServerError)
			return
		}
		if claims, err := verifyClaims(data.Token); err == nil {
			if wt, err := claims.wireToken(); err == nil {
				store.SaveWireToken(data.UID, wt)
			}
		}
		http.SetCookie(w, &http.Cookie{
			Name:     "token",
			Value:    data.Token,
			Path:     "/",
			HttpOnly: true,
			SameSite: http.SameSiteLaxMode,
		})
		writeJSON(w, map[string]any{"uid": data.UID})
	})

	// ── OTP rate limiter: 5 requests per minute per IP ────────────────────────
	otpRL := newRateLimiter(time.Minute, 5)
	otpHandle := func(pattern string, h http.HandlerFunc) {
		mux.Handle(pattern, otpRL.Middleware(h))
	}

	// ── Email bind: send OTP ──────────────────────────────────────────────────
	otpHandle("POST /api/email/bind", func(w http.ResponseWriter, r *http.Request) {
		c, ok := requireJWT(w, r)
		if !ok {
			return
		}
		var req struct {
			Email string `json:"email"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Email == "" {
			http.Error(w, "email required", http.StatusBadRequest)
			return
		}
		code, err := generateOTP()
		if err != nil {
			http.Error(w, "otp error", http.StatusInternalServerError)
			return
		}
		if err := store.SaveOTP(req.Email, code, &c.UID, 10*60*1e9); err != nil {
			http.Error(w, "store error", http.StatusInternalServerError)
			return
		}
		if mailer != nil {
			if err := mailer.SendOTP(req.Email, code); err != nil {
				http.Error(w, "email send failed: "+err.Error(), http.StatusInternalServerError)
				return
			}
		}
		w.WriteHeader(http.StatusNoContent)
	})

	// ── Email bind: verify OTP ────────────────────────────────────────────────
	otpHandle("POST /api/email/confirm", func(w http.ResponseWriter, r *http.Request) {
		c, ok := requireJWT(w, r)
		if !ok {
			return
		}
		var req struct {
			Email string `json:"email"`
			Code  string `json:"code"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Email == "" || req.Code == "" {
			http.Error(w, "email and code required", http.StatusBadRequest)
			return
		}
		uid, verified := store.VerifyOTP(req.Email, req.Code)
		if !verified || uid == nil || *uid != c.UID {
			http.Error(w, "mã OTP không hợp lệ hoặc đã hết hạn", http.StatusUnauthorized)
			return
		}
		if err := store.BindEmail(c.UID, req.Email); err != nil {
			http.Error(w, "bind error", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})

	// ── Email login: send OTP (no auth) ───────────────────────────────────────
	otpHandle("POST /api/email/otp", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Email string `json:"email"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Email == "" {
			http.Error(w, "email required", http.StatusBadRequest)
			return
		}
		uid, ok := store.LookupEmail(req.Email)
		if !ok {
			// Return 204 even if not found to avoid email enumeration
			w.WriteHeader(http.StatusNoContent)
			return
		}
		code, err := generateOTP()
		if err != nil {
			http.Error(w, "otp error", http.StatusInternalServerError)
			return
		}
		if err := store.SaveOTP(req.Email, code, &uid, 10*60*1e9); err != nil {
			http.Error(w, "store error", http.StatusInternalServerError)
			return
		}
		if mailer != nil {
			mailer.SendOTP(req.Email, code)
		}
		w.WriteHeader(http.StatusNoContent)
	})

	// ── Email login: verify OTP → issue JWT ───────────────────────────────────
	otpHandle("POST /api/email/login", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Email string `json:"email"`
			Code  string `json:"code"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Email == "" || req.Code == "" {
			http.Error(w, "email and code required", http.StatusBadRequest)
			return
		}
		uid, ok := store.VerifyOTP(req.Email, req.Code)
		if !ok || uid == nil {
			http.Error(w, "mã OTP không hợp lệ hoặc đã hết hạn", http.StatusUnauthorized)
			return
		}
		wt, ok := store.GetWireToken(*uid)
		if !ok {
			http.Error(w, "no session found, please login with password first", http.StatusUnauthorized)
			return
		}
		token, err := issueJWT(*uid, wt)
		if err != nil {
			http.Error(w, "jwt error", http.StatusInternalServerError)
			return
		}
		http.SetCookie(w, &http.Cookie{
			Name: "token", Value: token, Path: "/",
			HttpOnly: true, SameSite: http.SameSiteLaxMode,
		})
		writeJSON(w, map[string]any{"uid": *uid})
	})

	mux.HandleFunc("POST /api/logout", func(w http.ResponseWriter, r *http.Request) {
		http.SetCookie(w, &http.Cookie{Name: "token", Value: "", Path: "/", MaxAge: -1})
		w.WriteHeader(http.StatusNoContent)
	})

	// ── Authenticated endpoints: verify JWT locally, forward to Wire core ──────

	mux.HandleFunc("GET /api/me", func(w http.ResponseWriter, r *http.Request) {
		c, ok := requireJWT(w, r)
		if !ok {
			return
		}
		writeJSON(w, map[string]any{"uid": c.UID})
	})

	mux.HandleFunc("GET /api/balance", func(w http.ResponseWriter, r *http.Request) {
		c, ok := requireJWT(w, r)
		if !ok {
			return
		}
		wt, _ := c.wireToken()
		bal, err := wireBalance(wireAddr, wt)
		if err != nil {
			writeWireErr(w, err)
			return
		}
		writeJSON(w, bal)
	})

	mux.HandleFunc("GET /api/history", func(w http.ResponseWriter, r *http.Request) {
		c, ok := requireJWT(w, r)
		if !ok {
			return
		}
		wt, _ := c.wireToken()
		txs, err := wireHistory(wireAddr, wt)
		if err != nil {
			writeWireErr(w, err)
			return
		}
		writeJSON(w, txs)
	})

	// ── Topup (float account → user) ──────────────────────────────────────────
	mux.HandleFunc("POST /api/topup", func(w http.ResponseWriter, r *http.Request) {
		c, ok := requireJWT(w, r)
		if !ok {
			return
		}
		var req struct {
			Amount uint64 `json:"amount"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Amount == 0 {
			http.Error(w, "amount required", http.StatusBadRequest)
			return
		}
		floatHash := sha256.Sum256([]byte(floatPwd))
		floatToken, err := wireLogin(wireAddr, 1, floatHash[:])
		if err != nil {
			http.Error(w, "float login failed: "+err.Error(), http.StatusInternalServerError)
			return
		}
		if err := wireCashIn(wireAddr, floatToken, c.UID, req.Amount); err != nil {
			writeWireErr(w, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})

	mux.HandleFunc("POST /api/transfer", func(w http.ResponseWriter, r *http.Request) {
		c, ok := requireJWT(w, r)
		if !ok {
			return
		}
		wt, _ := c.wireToken()
		var req struct {
			To     uint32 `json:"to"`
			Amount uint64 `json:"amount"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.To == 0 || req.Amount == 0 {
			http.Error(w, "to and amount required", http.StatusBadRequest)
			return
		}
		if err := wireTransfer(wireAddr, wt, req.To, req.Amount); err != nil {
			writeWireErr(w, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
}

func addMerchantsProxy(mux *http.ServeMux, merchantsURL string) {
	target, err := url.Parse(merchantsURL)
	if err != nil {
		slog.Error("invalid MERCHANTS_URL", "err", err)
		return
	}
	proxy := httputil.NewSingleHostReverseProxy(target)
	for _, prefix := range []string{"/merchants/", "/orders/", "/loyalty/", "/payment_request/"} {
		mux.Handle("GET "+prefix, proxy)
		mux.Handle("POST "+prefix, proxy)
	}
}

func requireJWT(w http.ResponseWriter, r *http.Request) (*Claims, bool) {
	cookie, err := r.Cookie("token")
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return nil, false
	}
	claims, err := verifyClaims(cookie.Value)
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return nil, false
	}
	return claims, true
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func writeWireErr(w http.ResponseWriter, err error) {
	slog.Warn("wire error", "err", err)
	status := http.StatusInternalServerError
	if we, ok := err.(*WireError); ok {
		switch we.Code {
		case 0x03, 0x04:
			status = http.StatusConflict
		case 0x05:
			status = http.StatusNotFound
		case 0x06, 0x07:
			status = http.StatusUnauthorized
		case 0x08:
			status = http.StatusPaymentRequired
		}
	}
	http.Error(w, err.Error(), status)
}
