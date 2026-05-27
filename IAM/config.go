package main

import (
	"os"
	"strconv"
	"time"
)

type Config struct {
	Addr           string
	DB             string
	JWTSecret      string
	AccessTTL      time.Duration
	RefreshTTL     time.Duration
	WireAddr       string
	WireSecret     string
}

func loadConfig() Config {
	accessHours := 24
	if v := os.Getenv("IAM_ACCESS_TTL_HOURS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			accessHours = n
		}
	}
	refreshDays := 7
	if v := os.Getenv("IAM_REFRESH_TTL_DAYS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			refreshDays = n
		}
	}
	return Config{
		Addr:       envOr("IAM_ADDR", "127.0.0.1:8085"),
		DB:         envOr("IAM_DB", ""),
		JWTSecret:  envOr("JWT_SECRET", ""),
		AccessTTL:  time.Duration(accessHours) * time.Hour,
		RefreshTTL: time.Duration(refreshDays) * 24 * time.Hour,
		WireAddr:   envOr("WIRE_ADDR", "127.0.0.1:7474"),
		WireSecret: envOr("WIRE_SECRET", "saving_wire_secret_changeme"),
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
