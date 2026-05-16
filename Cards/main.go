package main

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"
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

	dbPath    := envOr("CARDS_DB",    "cards.db")
	addr      := envOr("CARDS_ADDR",  ":8091")
	wireToken := envOr("WIRE_TOKEN",  "change-me-in-production")
	amqpURL   := envOr("AMQP_URL",   "amqp://guest:guest@localhost:5672/")

	store, err := OpenStore(dbPath)
	if err != nil {
		slog.Error("store init failed", "err", err)
		os.Exit(1)
	}
	defer store.Close()

	// AMQP connection (optional — service works without it, topup falls back to sync)
	var pub *Publisher
	if conn, err := dialAMQP(amqpURL, 5, time.Second); err != nil {
		slog.Warn("amqp unavailable, topup will be synchronous", "err", err)
	} else {
		pub, err = NewPublisher(conn)
		if err != nil {
			slog.Warn("amqp publisher init failed", "err", err)
		} else {
			go ConsumeResults(conn, store)
			slog.Info("amqp connected", "url", amqpURL)
		}
	}

	h := &handler{store: store, wireToken: wireToken, pub: pub}

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
