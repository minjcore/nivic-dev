package main

import (
	"crypto/sha256"
	"encoding/json"
	"log/slog"
	"net/http"
	"os"
)

func main() {
	addr     := envStr("AUTH_ADDR", ":8091")
	wireAddr := envStr("WIRE_ADDR", "127.0.0.1:7474")

	mux := http.NewServeMux()

	// POST /login — called only by the proxy, never exposed to the internet
	mux.HandleFunc("POST /login", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			ID       uint32 `json:"id"`
			Password string `json:"password"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == 0 || req.Password == "" {
			http.Error(w, "id and password required", http.StatusBadRequest)
			return
		}
		hash := sha256.Sum256([]byte(req.Password))
		wireToken, err := wireLoginOnce(wireAddr, req.ID, hash[:])
		if err != nil {
			if ae, ok := err.(*authErr); ok {
				switch ae.code {
				case 0x05, 0x06:
					http.Error(w, err.Error(), http.StatusUnauthorized)
					return
				}
			}
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		jwt, err := issueJWT(req.ID, wireToken)
		if err != nil {
			http.Error(w, "could not issue token", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"token": jwt,
			"uid":   req.ID,
		})
	})

	slog.Info("authservice starting", "addr", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		slog.Error("listen", "err", err)
		os.Exit(1)
	}
}

func envStr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
