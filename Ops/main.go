package main

import (
	"log/slog"
	"net/http"
	"os"
)

func main() {
	addr    := envOr("OPS_ADDR",       ":9000")
	token   := envOr("OPS_TOKEN",      "change-me-in-production")
	dsn     := envOr("MERCHANTS_DB",   "postgres://postgres:postgres@localhost/merchants?sslmode=disable")
	wireURL := envOr("WIRE_ADMIN_URL", "http://localhost:7475")
	wireM2M := envOr("WIRE_M2M_TOKEN", "")

	store, err := OpenStore(dsn)
	if err != nil {
		slog.Error("store failed", "err", err)
		os.Exit(1)
	}
	defer store.Close()

	h := &opsHandler{store: store, token: token, wireURL: wireURL, wireM2M: wireM2M}
	mux := http.NewServeMux()
	h.register(mux)

	slog.Info("ops control plane ready", "addr", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		slog.Error("server error", "err", err)
		os.Exit(1)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
