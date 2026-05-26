package main

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"
)

const banner = `
╔╦╗┌─┐┬─┐┌─┐┬ ┬┌─┐┌┐┌┌┬┐┌─┐  ╦ ╦┌─┐┌─┐┌┬┐
║║║├┤ ├┬┘│  ├─┤├─┤│││ │ └─┐  ╠═╣│ │└─┐ │
╩ ╩└─┘┴└─└─┘┴ ┴┴ ┴┘└┘ ┴ └─┘  ╩ ╩└─┘└─┘ ┴
`

func main() {
	fmt.Print(banner)
	fmt.Println("Merchant public-key registry • :8090")
	fmt.Println("──────────────────────────────────────────")

	dbPath       := envOr("MERCHANTS_DB",      "merchants.db")
	addr         := envOr("MERCHANTS_ADDR",    ":8090")
	adminToken   := envOr("MERCHANTS_TOKEN",   "change-me-in-production")
	wireAdminURL := envOr("WIRE_ADMIN_URL",    "http://localhost:7475")
	wireM2MToken := envOr("WIRE_M2M_TOKEN",   "")

	store, err := OpenStore(dbPath)
	if err != nil {
		slog.Error("store init failed", "err", err)
		os.Exit(1)
	}
	defer store.Close()

	h := &handler{
		store:        store,
		adminToken:   adminToken,
		wireAdminURL: wireAdminURL,
		wireM2MToken: wireM2MToken,
	}

	slog.Info("merchants-host ready", "addr", addr, "db", dbPath)
	if err := http.ListenAndServe(addr, h.routes()); err != nil {
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
