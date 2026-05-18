package main

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

const banner = `
╔═╗┌─┐┬─┐┌┬┐┌─┐  ╔═╗┌─┐┬─┐┬  ┬┬┌─┐┌─┐
║  ├─┤├┬┘ ││└─┐  ╚═╗├┤ ├┬┘└┐┌┘││  ├┤
╚═╝┴ ┴┴└──┴┘└─┘  ╚═╝└─┘┴└─ └┘ ┴└─┘└─┘
`

func main() {
	fmt.Print(banner)
	fmt.Println("Card-linking service • :8091")
	fmt.Println("──────────────────────────────────────────")

	dbPath       := envOr("CARDS_DB",        "cards.db")
	addr         := envOr("CARDS_ADDR",     ":8091")
	wireToken    := envOr("WIRE_TOKEN",     "change-me-in-production")
	amqpURL      := envOr("AMQP_URL",       "amqp://guest:guest@localhost:5672/")
	bankGWAddr   := envOr("BANK_GW_ADDR",  "127.0.0.1:8095")
	wireAddr     := envOr("WIRE_ADDR",      "127.0.0.1:7474")
	wireSecret   := envOr("WIRE_SECRET",    "supersecret")
	wireFloatUID := envOr("WIRE_FLOAT_UID", "1")
	wireFloatPwd := envOr("WIRE_FLOAT_PWD", "bankpassword")

	store, err := OpenStore(dbPath)
	if err != nil {
		slog.Error("store init failed", "err", err)
		os.Exit(1)
	}
	defer store.Close()

	// APNs client (optional — push notifications disabled if env vars absent)
	var apns *APNsClient
	if c, err := NewAPNsClientFromEnv(); err != nil {
		slog.Warn("apns disabled", "reason", err)
	} else {
		apns = c
		slog.Info("apns configured", "bundle", os.Getenv("APNS_BUNDLE_ID"),
			"env", os.Getenv("APNS_ENV"))
	}

	// AMQP connection (optional — service works without it, topup falls back to sync)
	var pub *Publisher
	if conn, err := dialAMQP(amqpURL, 5, time.Second); err != nil {
		slog.Warn("amqp unavailable, topup will be synchronous", "err", err)
	} else {
		pub, err = NewPublisher(conn)
		if err != nil {
			slog.Warn("amqp publisher init failed", "err", err)
		} else {
			go ConsumeResults(conn, store, apns)
			slog.Info("amqp connected", "url", amqpURL)
		}
	}

	iso := &ISO8583Client{Addr: bankGWAddr}
	slog.Info("bank-gateway configured", "addr", bankGWAddr)

	floatUID64, _ := strconv.ParseUint(wireFloatUID, 10, 32)
	wireCfg := &WireConfig{
		Addr:     wireAddr,
		Secret:   wireSecret,
		FloatUID: uint32(floatUID64),
		FloatPwd: wireFloatPwd,
	}
	slog.Info("wire configured", "addr", wireAddr, "float_uid", wireFloatUID)

	h := &handler{store: store, wireToken: wireToken, pub: pub, iso: iso, wire: wireCfg}

	slog.Info("cards-service ready", "addr", addr, "db", dbPath)
	if err := http.ListenAndServe(addr, h.routes()); err != nil {
		slog.Error("server error", "err", err)
		os.Exit(1)
	}
}

func dialAMQP(url string, attempts int, delay time.Duration) (*amqp.Connection, error) {
	var lastErr error
	for range attempts {
		conn, err := amqp.Dial(url)
		if err == nil {
			return conn, nil
		}
		lastErr = err
		time.Sleep(delay)
	}
	return nil, lastErr
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
