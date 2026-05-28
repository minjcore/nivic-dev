package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
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
	mux.HandleFunc("GET /health",  v.handleHealth(ctx))
	mux.HandleFunc("GET /metrics", v.handleMetrics)
	mux.HandleFunc("POST /events", v.handlePublish(ctx))
	mux.HandleFunc("GET /events",  v.handleSSE(ctx))

	// Wire — POST endpoints share a single factory; each differs only by bus address.
	mux.HandleFunc("POST /wire/login",                  v.wirePostHandler(ctx, "saving.wire.login"))
	mux.HandleFunc("POST /wire/transfer",               v.wirePostHandler(ctx, "saving.wire.transfer"))
	mux.HandleFunc("POST /wire/merchant/register",      v.wirePostHandler(ctx, "saving.wire.register_merchant"))
	mux.HandleFunc("POST /wire/merchant/enroll_totp",   v.wirePostHandler(ctx, "saving.wire.enroll_totp"))
	mux.HandleFunc("POST /wire/intent/create",          v.wirePostHandler(ctx, "saving.wire.create_intent"))
	mux.HandleFunc("POST /wire/intent/pay",             v.wirePostHandler(ctx, "saving.wire.pay_intent"))

	// Wire — GET endpoints carry query params into the bus request.
	mux.HandleFunc("GET /wire/balance", v.wireGetHandler(ctx, "saving.wire.balance", "token"))
	mux.HandleFunc("GET /wire/ping",    v.wireGetHandler(ctx, "saving.wire.ping"))

	port := os.Getenv("GATEWAY_PORT")
	if port == "" {
		port = "8080"
	}
	addr := ":" + port
	v.server = &http.Server{
		Addr:              addr,
		Handler:           v.countMiddleware(mux),
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		slog.Info("saving-gateway ready", "addr", addr)
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
		shutCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		_ = v.server.Shutdown(shutCtx)
	}
	return v.BaseVerticle.Stop(ctx)
}

// ── handler factories ─────────────────────────────────────────────────────────

// wirePostHandler decodes a JSON body and forwards it to the given bus address.
func (v *GatewayVerticle) wirePostHandler(ctx core.FluxorContext, addr string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req map[string]any
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			jsonResp(w, 400, map[string]any{"error": "invalid json"})
			return
		}
		result, err := wireBusCall(ctx, addr, req)
		if err != nil {
			jsonResp(w, 502, map[string]any{"error": err.Error()})
			return
		}
		jsonResp(w, 200, result)
	}
}

// wireGetHandler copies named query params into the bus request map.
func (v *GatewayVerticle) wireGetHandler(ctx core.FluxorContext, addr string, params ...string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		req := make(map[string]any, len(params))
		for _, p := range params {
			if val := r.URL.Query().Get(p); val != "" {
				req[p] = val
			}
		}
		result, err := wireBusCall(ctx, addr, req)
		if err != nil {
			jsonResp(w, 502, map[string]any{"error": err.Error()})
			return
		}
		jsonResp(w, 200, result)
	}
}

// ── non-wire handlers ─────────────────────────────────────────────────────────

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
