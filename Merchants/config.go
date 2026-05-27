package main

import (
	"log/slog"
	"os"
	"strconv"

	"nivic.dev/merchants/internal/kson"
)

// Config holds all runtime configuration for the Merchants service.
type Config struct {
	Addr         string // HTTP listen address, e.g. ":8090"
	AdminToken   string // Bearer token for /admin/* routes
	DB           string // PostgreSQL DSN
	WireAdminURL string // Wire admin HTTP base URL
	WireM2MToken string // M2M bearer token for Wire admin calls
	OpsToken     string // Bearer token for /ops/* control-plane routes
	WireAddr     string // Wire TCP address for CREATE_INTENT, e.g. "localhost:7474"
	SMTP         SMTPConfig
}

// SMTPConfig holds outbound email settings.
type SMTPConfig struct {
	Host     string
	Port     int
	User     string
	Pass     string
	From     string
	FromName string
}

// loadConfig reads merchants.kson (path from MERCHANTS_CONFIG, default ./merchants.kson)
// then overlays environment variables. Env vars always take precedence.
//
// If the kson file is not found, falls back to env vars only (backward compatible
// with the old systemd service that sets MERCHANTS_DB, SMTP_HOST, etc.).
func loadConfig() Config {
	cfg := defaultConfig()

	// Locate config file
	path := os.Getenv("MERCHANTS_CONFIG")
	if path == "" {
		path = "merchants.kson"
	}

	m, err := kson.ParseFile(path)
	if err != nil {
		if !os.IsNotExist(err) {
			slog.Warn("merchants: could not parse config file, using env vars only",
				"path", path, "err", err)
		}
		// No file — env-only mode
		applyEnv(&cfg)
		return cfg
	}

	slog.Info("merchants: loaded config", "path", path)

	// Apply kson values
	cfg.Addr         = kson.GetString(m, "addr", cfg.Addr)
	cfg.AdminToken   = kson.GetString(m, "token", cfg.AdminToken)
	cfg.DB           = kson.GetString(m, "db.dsn", cfg.DB)
	cfg.OpsToken     = kson.GetString(m, "ops.token", cfg.OpsToken)
	cfg.WireAdminURL = kson.GetString(m, "wire.admin-url", cfg.WireAdminURL)
	cfg.WireM2MToken = kson.GetString(m, "wire.m2m-token", cfg.WireM2MToken)
	cfg.WireAddr     = kson.GetString(m, "wire.addr", cfg.WireAddr)

	cfg.SMTP.Host     = kson.GetString(m, "smtp.host", cfg.SMTP.Host)
	cfg.SMTP.Port     = int(kson.GetInt(m, "smtp.port", int64(cfg.SMTP.Port)))
	cfg.SMTP.User     = kson.GetString(m, "smtp.user", cfg.SMTP.User)
	cfg.SMTP.Pass     = kson.GetString(m, "smtp.pass", cfg.SMTP.Pass)
	cfg.SMTP.From     = kson.GetString(m, "smtp.from", cfg.SMTP.From)
	cfg.SMTP.FromName = kson.GetString(m, "smtp.from-name", cfg.SMTP.FromName)

	// Env vars always win (allows Docker/systemd overrides without editing the file)
	applyEnv(&cfg)
	return cfg
}

// applyEnv overlays environment variables onto cfg.
func applyEnv(cfg *Config) {
	if v := os.Getenv("MERCHANTS_DB"); v != "" {
		cfg.DB = v
	}
	if v := os.Getenv("MERCHANTS_ADDR"); v != "" {
		cfg.Addr = v
	}
	if v := os.Getenv("MERCHANTS_TOKEN"); v != "" {
		cfg.AdminToken = v
	}
	if v := os.Getenv("OPS_TOKEN"); v != "" {
		cfg.OpsToken = v
	}
	if v := os.Getenv("WIRE_ADMIN_URL"); v != "" {
		cfg.WireAdminURL = v
	}
	if v := os.Getenv("WIRE_M2M_TOKEN"); v != "" {
		cfg.WireM2MToken = v
	}
	if v := os.Getenv("WIRE_ADDR"); v != "" {
		cfg.WireAddr = v
	}
	if v := os.Getenv("SMTP_HOST"); v != "" {
		cfg.SMTP.Host = v
	}
	if v := os.Getenv("SMTP_PORT"); v != "" {
		if p, err := strconv.Atoi(v); err == nil {
			cfg.SMTP.Port = p
		}
	}
	if v := os.Getenv("SMTP_USER"); v != "" {
		cfg.SMTP.User = v
	}
	if v := v2(os.Getenv("SMTP_PASS"), os.Getenv("SMTP_PASSWORD")); v != "" {
		cfg.SMTP.Pass = v
	}
	if v := os.Getenv("SMTP_FROM"); v != "" {
		cfg.SMTP.From = v
	}
	if v := os.Getenv("SMTP_FROM_NAME"); v != "" {
		cfg.SMTP.FromName = v
	}
}

func defaultConfig() Config {
	return Config{
		Addr:         ":8090",
		AdminToken:   "change-me-in-production",
		OpsToken:     "change-me-in-production",
		DB:           "postgres://postgres:postgres@localhost/merchants?sslmode=disable",
		WireAdminURL: "http://localhost:7475",
		WireAddr:     "localhost:7474",
		SMTP:         SMTPConfig{Port: 465, FromName: "Nivic Pay"},
	}
}

// v2 returns the first non-empty of a, b.
func v2(a, b string) string {
	if a != "" {
		return a
	}
	return b
}
