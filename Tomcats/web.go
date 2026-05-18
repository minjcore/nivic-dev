package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
)

func addWebRoutes(mux *http.ServeMux, authURL, wireAddr, staticDir string) {
	mux.Handle("GET /", http.FileServer(http.Dir(staticDir)))

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
		http.SetCookie(w, &http.Cookie{
			Name:     "token",
			Value:    data.Token,
			Path:     "/",
			HttpOnly: true,
			SameSite: http.SameSiteLaxMode,
		})
		writeJSON(w, map[string]any{"uid": data.UID})
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
		case 0x06, 0x07:
			status = http.StatusUnauthorized
		case 0x08:
			status = http.StatusPaymentRequired
		case 0x05:
			status = http.StatusNotFound
		}
	}
	http.Error(w, err.Error(), status)
}
