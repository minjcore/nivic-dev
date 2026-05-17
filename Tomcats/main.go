package main

import (
	"log/slog"
	"net/http"
	"os"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

func main() {
	slog.Info("tomcats starting")

	amqpURL := env("AMQP_URL", "amqp://guest:guest@localhost:5672/")
	addr := env("TOMCATS_ADDR", ":8093")
	dbPath := env("TOMCATS_DB", "tomcats.db")

	store, err := OpenStore(dbPath)
	if err != nil {
		slog.Error("open store", "err", err)
		os.Exit(1)
	}
	defer store.Close()

	var apns *APNsClient
	if a, err := NewAPNsClientFromEnv(); err != nil {
		slog.Warn("apns disabled", "reason", err)
	} else {
		apns = a
		slog.Info("apns ready")
	}

	var fcm *FCMClient
	if f, err := NewFCMClientFromEnv(); err != nil {
		slog.Warn("fcm disabled", "reason", err)
	} else {
		fcm = f
		slog.Info("fcm ready")
	}

	conn := mustAMQP(amqpURL)
	defer conn.Close()

	go ConsumeEvents(conn, store, apns, fcm)

	slog.Info("tomcats http", "addr", addr)
	if err := http.ListenAndServe(addr, routes(store)); err != nil {
		slog.Error("http", "err", err)
	}
}

func mustAMQP(url string) *amqp.Connection {
	for range 10 {
		conn, err := amqp.Dial(url)
		if err == nil {
			slog.Info("amqp connected")
			return conn
		}
		slog.Warn("amqp dial", "err", err)
		time.Sleep(3 * time.Second)
	}
	slog.Error("amqp unavailable")
	os.Exit(1)
	return nil
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
