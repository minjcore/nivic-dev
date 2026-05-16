package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

const banner = `
╔╦╗┌─┐┌─┐┬ ┬┌─┐  ╦ ╦┌─┐┬─┐┬┌─┌─┐┬─┐
 ║ │ │├─┘│ │├─┘  ║║║│ │├┬┘├┴┐├┤ ├┬┘
 ╩ └─┘┴  └─┘┴    ╚╩╝└─┘┴└─┴ ┴└─┘┴└─
`

func main() {
	fmt.Print(banner)
	fmt.Println("Top-up Worker  RabbitMQ → Wire")
	fmt.Println("──────────────────────────────────────────")

	amqpURL    := envOr("AMQP_URL",       "amqp://guest:guest@localhost:5672/")
	wireHost   := envOr("WIRE_HOST",      "127.0.0.1")
	wirePort   := envInt("WIRE_PORT",     7474)
	wireSecret := envOr("WIRE_SECRET",    "saving_wire_secret_changeme")
	floatUID   := uint32(envInt("FLOAT_UID", 1))   // VIP uid=1 = topup float account
	floatPwd   := envOr("FLOAT_PWD",     "saving_float_changeme")

	conn, err := dialWithRetry(amqpURL, 10, 2*time.Second)
	if err != nil {
		slog.Error("amqp connect failed", "err", err)
		os.Exit(1)
	}
	defer conn.Close()

	ch, err := conn.Channel()
	if err != nil {
		slog.Error("amqp channel failed", "err", err)
		os.Exit(1)
	}
	defer ch.Close()

	if err := setupTopology(ch); err != nil {
		slog.Error("topology setup failed", "err", err)
		os.Exit(1)
	}

	w := &Worker{
		ch:         ch,
		wireHost:   wireHost,
		wirePort:   wirePort,
		wireSecret: wireSecret,
		floatUID:   floatUID,
		floatPwd:   floatPwd,
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	slog.Info("topup worker ready",
		"amqp", amqpURL, "wire", fmt.Sprintf("%s:%d", wireHost, wirePort),
		"float_uid", floatUID)

	if err := w.Run(ctx); err != nil && err != context.Canceled {
		slog.Error("worker error", "err", err)
		os.Exit(1)
	}
}

func dialWithRetry(url string, attempts int, delay time.Duration) (*amqp.Connection, error) {
	var err error
	for i := range attempts {
		var conn *amqp.Connection
		conn, err = amqp.Dial(url)
		if err == nil {
			return conn, nil
		}
		slog.Warn("amqp not ready, retrying...", "attempt", i+1, "err", err)
		time.Sleep(delay)
	}
	return nil, err
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func envInt(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return fallback
}
