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

	cfg := loadConfig()

	store, err := OpenStore(cfg.DB)
	if err != nil {
		slog.Error("store init failed", "err", err)
		os.Exit(1)
	}
	defer store.Close()

	h := &handler{
		store:        store,
		adminToken:   cfg.AdminToken,
		wireAdminURL: cfg.WireAdminURL,
		wireM2MToken: cfg.WireM2MToken,
		wireAddr:     cfg.WireAddr,
		mailer:       mailerFromConfig(cfg.SMTP),
	}

	slog.Info("merchants-host ready", "addr", cfg.Addr, "db", cfg.DB)
	if err := http.ListenAndServe(cfg.Addr, h.routes()); err != nil {
		slog.Error("server error", "err", err)
		os.Exit(1)
	}
}
