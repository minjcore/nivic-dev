package main

import (
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"github.com/google/uuid"
)

type handler struct {
	db  *sql.DB
	cfg Config
}

func (h *handler) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health",           h.handleHealth)
	mux.HandleFunc("POST /auth/login",      h.handleLogin)
	mux.HandleFunc("POST /auth/refresh",    h.handleRefresh)
	mux.HandleFunc("POST /auth/logout",     h.handleLogout)
	mux.HandleFunc("GET /auth/me",          h.handleMe)
	mux.HandleFunc("POST /auth/introspect", h.handleIntrospect)
	return mux
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

func clientIP(r *http.Request) string {
	if v := r.Header.Get("X-Real-IP"); v != "" {
		return v
	}
	if v := r.Header.Get("X-Forwarded-For"); v != "" {
		return strings.Split(v, ",")[0]
	}
	return r.RemoteAddr
}

func (h *handler) handleHealth(w http.ResponseWriter, _ *http.Request) {
	jsonOK(w, map[string]string{"status": "ok", "service": "iam"})
}

func (h *handler) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		UID      uint32 `json:"uid"`
		PWHash   string `json:"pw_hash"`   // pre-hashed SHA-256 hex (Merchants flow)
		Password string `json:"password"`  // raw password (Tomcats / authservice flow)
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UID == 0 {
		jsonErr(w, 400, "uid required")
		return
	}
	// Accept raw password — hash it like authservice did
	if req.PWHash == "" && req.Password != "" {
		h := sha256.Sum256([]byte(req.Password))
		req.PWHash = fmt.Sprintf("%x", h)
	}
	if req.PWHash == "" {
		jsonErr(w, 400, "password or pw_hash required")
		return
	}

	wireToken, err := wireLogin(h.cfg.WireAddr, req.UID, req.PWHash, []byte(h.cfg.WireSecret))
	if err != nil {
		auditLog(h.db, "auth.login", req.UID, clientIP(r), false)
		jsonErr(w, 401, "invalid credentials")
		return
	}

	accessToken, exp, err := issueJWT(req.UID, wireToken[:], h.cfg.JWTSecret, h.cfg.AccessTTL)
	if err != nil {
		jsonErr(w, 500, "token error")
		return
	}

	refreshToken := uuid.New().String() + "." + uuid.New().String()
	if err := storeRefreshToken(h.db, req.UID, refreshToken, h.cfg.RefreshTTL); err != nil {
		jsonErr(w, 500, "db error")
		return
	}

	auditLog(h.db, "auth.login", req.UID, clientIP(r), true)
	jsonOK(w, map[string]any{
		"access_token":  accessToken,
		"refresh_token": refreshToken,
		"expires_at":    exp,
		"token_type":    "Bearer",
	})
}

func (h *handler) handleRefresh(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.RefreshToken == "" {
		jsonErr(w, 400, "refresh_token required")
		return
	}

	uid, ok := validateRefreshToken(h.db, req.RefreshToken)
	if !ok {
		jsonErr(w, 401, "invalid or expired refresh token")
		return
	}

	if err := revokeRefreshToken(h.db, req.RefreshToken); err != nil {
		jsonErr(w, 500, "db error")
		return
	}

	accessToken, exp, err := issueJWT(uid, nil, h.cfg.JWTSecret, h.cfg.AccessTTL)
	if err != nil {
		jsonErr(w, 500, "token error")
		return
	}

	newRefresh := uuid.New().String() + "." + uuid.New().String()
	if err := storeRefreshToken(h.db, uid, newRefresh, h.cfg.RefreshTTL); err != nil {
		jsonErr(w, 500, "db error")
		return
	}

	auditLog(h.db, "auth.refresh", uid, clientIP(r), true)
	jsonOK(w, map[string]any{
		"access_token":  accessToken,
		"refresh_token": newRefresh,
		"expires_at":    exp,
		"token_type":    "Bearer",
	})
}

func (h *handler) handleLogout(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RefreshToken string `json:"refresh_token"`
	}
	json.NewDecoder(r.Body).Decode(&req)
	if req.RefreshToken != "" {
		revokeRefreshToken(h.db, req.RefreshToken)
	}
	jsonOK(w, map[string]bool{"ok": true})
}

func (h *handler) handleMe(w http.ResponseWriter, r *http.Request) {
	uid, err := h.requireToken(r)
	if err != nil {
		jsonErr(w, 401, err.Error())
		return
	}
	jsonOK(w, map[string]any{"uid": uid})
}

func (h *handler) handleIntrospect(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Token == "" {
		jsonErr(w, 400, "token required")
		return
	}
	uid, err := verifyJWT(req.Token, h.cfg.JWTSecret)
	if err != nil {
		jsonOK(w, map[string]any{"active": false})
		return
	}
	jsonOK(w, map[string]any{"active": true, "uid": uid})
}

func (h *handler) requireToken(r *http.Request) (uint32, error) {
	v := r.Header.Get("Authorization")
	token := strings.TrimPrefix(v, "Bearer ")
	if token == "" {
		return 0, http.ErrNoCookie
	}
	return verifyJWT(token, h.cfg.JWTSecret)
}
