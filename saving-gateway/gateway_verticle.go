package main

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"sync/atomic"
	"time"

	"github.com/fluxorio/fluxor/pkg/core"
)

// GatewayVerticle is the BFF HTTP layer for saving.
// Runs alongside the C wire server (:7474) and exposes:
//   GET  /health          — liveness
//   GET  /metrics         — request count, uptime
//   POST /events          — publish to EventBus  {"address":"saving.x","data":{...}}
//   GET  /events          — SSE stream from EventBus ?address=saving.x
//   POST /wire/login      — Wire LOGIN  {"uid":123,"password":"..."}
//   GET  /wire/balance    — Wire GET_BALANCE  ?token=<hex>
//   POST /wire/transfer   — Wire TRANSFER {"token":"...","to":456,"amount":50000}
//   GET  /wire/ping       — Wire PING liveness check
type GatewayVerticle struct {
	*core.BaseVerticle
	server   *http.Server
	requests atomic.Int64
	start    time.Time
}

func NewGatewayVerticle() *GatewayVerticle {
	return &GatewayVerticle{
		BaseVerticle: core.NewBaseVerticle("saving-gateway"),
	}
}

func (v *GatewayVerticle) Start(ctx core.FluxorContext) error {
	if err := v.BaseVerticle.Start(ctx); err != nil {
		return err
	}
	v.start = time.Now()

	mux := http.NewServeMux()
	mux.HandleFunc("GET /health",         v.handleHealth(ctx))
	mux.HandleFunc("GET /metrics",        v.handleMetrics)
	mux.HandleFunc("POST /events",        v.handlePublish(ctx))
	mux.HandleFunc("GET /events",         v.handleSSE(ctx))
	mux.HandleFunc("POST /wire/login",    v.handleWireLogin(ctx))
	mux.HandleFunc("GET /wire/balance",   v.handleWireBalance(ctx))
	mux.HandleFunc("POST /wire/transfer", v.handleWireTransfer(ctx))
	mux.HandleFunc("GET /wire/ping",      v.handleWirePing(ctx))

	v.server = &http.Server{
		Addr:              ":8080",
		Handler:           v.countMiddleware(mux),
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		slog.Info("saving-gateway ready", "addr", ":8080")
		if err := v.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("gateway crashed", "err", err)
		}
	}()

	// Log all saving.* events from the EventBus
	v.Consumer("saving.*").Handler(func(_ core.FluxorContext, msg core.Message) error {
		slog.Info("event", "body", fmt.Sprintf("%s", msg.Body()))
		return nil
	})

	return nil
}

func (v *GatewayVerticle) Stop(ctx core.FluxorContext) error {
	if v.server != nil {
		_ = v.server.Close()
	}
	return v.BaseVerticle.Stop(ctx)
}

// ── handlers ──────────────────────────────────────────────────────────────────

func (v *GatewayVerticle) handleHealth(ctx core.FluxorContext) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		jsonResp(w, 200, map[string]any{
			"status":  "ok",
			"service": "saving-gateway",
			"uptime":  time.Since(v.start).Round(time.Second).String(),
		})
	}
}

func (v *GatewayVerticle) handleMetrics(w http.ResponseWriter, r *http.Request) {
	jsonResp(w, 200, map[string]any{
		"requests": v.requests.Load(),
		"uptime_s": int(time.Since(v.start).Seconds()),
	})
}

// POST /events  body: {"address":"saving.transfer","data":{...}}
func (v *GatewayVerticle) handlePublish(ctx core.FluxorContext) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Address string `json:"address"`
			Data    any    `json:"data"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Address == "" {
			jsonResp(w, 400, map[string]any{"error": "need address + data"})
			return
		}
		payload, _ := json.Marshal(req.Data)
		ctx.EventBus().Publish(req.Address, payload)
		jsonResp(w, 200, map[string]any{"published": req.Address})
	}
}

// GET /events?address=saving.transfer  — SSE stream
func (v *GatewayVerticle) handleSSE(ctx core.FluxorContext) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		addr := r.URL.Query().Get("address")
		if addr == "" {
			jsonResp(w, 400, map[string]any{"error": "need ?address="})
			return
		}
		flusher, ok := w.(http.Flusher)
		if !ok {
			http.Error(w, "streaming unsupported", 500)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Connection", "keep-alive")

		ch := make(chan []byte, 32)
		consumer := ctx.EventBus().Consumer(addr)
		consumer.Handler(func(_ core.FluxorContext, msg core.Message) error {
			if b, ok := msg.Body().([]byte); ok {
				select {
				case ch <- b:
				default:
				}
			}
			return nil
		})
		defer consumer.Unregister()

		for {
			select {
			case <-r.Context().Done():
				return
			case data := <-ch:
				fmt.Fprintf(w, "data: %s\n\n", data)
				flusher.Flush()
			}
		}
	}
}

// ── Wire HTTP handlers ────────────────────────────────────────────────────────
// All Wire ops go through WireVerticle via EventBus to keep TCP ownership on
// one goroutine. Gateway publishes a request, subscribes to the _reply topic,
// and waits up to 12 s.

// POST /wire/login  {"uid":123,"password":"abc"}
func (v *GatewayVerticle) handleWireLogin(ctx core.FluxorContext) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req map[string]any
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			jsonResp(w, 400, map[string]any{"error": "invalid json"})
			return
		}
		result, err := wireBusCall(ctx, "saving.wire.login", req)
		if err != nil {
			jsonResp(w, 502, map[string]any{"error": err.Error()})
			return
		}
		jsonResp(w, 200, result)
	}
}

// GET /wire/balance?token=<hex64>
func (v *GatewayVerticle) handleWireBalance(ctx core.FluxorContext) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token := r.URL.Query().Get("token")
		if token == "" {
			jsonResp(w, 400, map[string]any{"error": "need ?token="})
			return
		}
		result, err := wireBusCall(ctx, "saving.wire.balance", map[string]any{"token": token})
		if err != nil {
			jsonResp(w, 502, map[string]any{"error": err.Error()})
			return
		}
		jsonResp(w, 200, result)
	}
}

// POST /wire/transfer  {"token":"...","to":456,"amount":50000}
func (v *GatewayVerticle) handleWireTransfer(ctx core.FluxorContext) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req map[string]any
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			jsonResp(w, 400, map[string]any{"error": "invalid json"})
			return
		}
		result, err := wireBusCall(ctx, "saving.wire.transfer", req)
		if err != nil {
			jsonResp(w, 502, map[string]any{"error": err.Error()})
			return
		}
		jsonResp(w, 200, result)
	}
}

// GET /wire/ping
func (v *GatewayVerticle) handleWirePing(ctx core.FluxorContext) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		result, err := wireBusCall(ctx, "saving.wire.ping", map[string]any{})
		if err != nil {
			jsonResp(w, 502, map[string]any{"error": err.Error()})
			return
		}
		jsonResp(w, 200, result)
	}
}

// wireBusCall publishes to addr, waits for the _reply on a one-shot consumer.
func wireBusCall(ctx core.FluxorContext, addr string, body map[string]any) (map[string]any, error) {
	ch := make(chan map[string]any, 1)
	replyAddr := addr + "._reply"
	consumer := ctx.EventBus().Consumer(replyAddr)
	consumer.Handler(func(_ core.FluxorContext, msg core.Message) error {
		var m map[string]any
		if err := msg.DecodeBody(&m); err == nil {
			select {
			case ch <- m:
			default:
			}
		}
		return nil
	})
	defer consumer.Unregister()

	if err := ctx.EventBus().Publish(addr, body); err != nil {
		return nil, fmt.Errorf("publish %s: %w", addr, err)
	}

	select {
	case result := <-ch:
		return result, nil
	case <-time.After(12 * time.Second):
		return nil, fmt.Errorf("wire timeout")
	}
}

// ── helpers ───────────────────────────────────────────────────────────────────

func (v *GatewayVerticle) countMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		v.requests.Add(1)
		next.ServeHTTP(w, r)
	})
}

func jsonResp(w http.ResponseWriter, code int, val any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(val)
}
