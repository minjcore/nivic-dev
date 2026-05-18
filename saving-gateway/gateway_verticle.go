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
//   GET  /health   — liveness
//   GET  /metrics  — request count, uptime
//   POST /events   — publish to EventBus  {"address":"saving.x","data":{...}}
//   GET  /events   — SSE stream from EventBus ?address=saving.x
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
