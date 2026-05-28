// Gateway verticle v2 — hot-deployable plugin.
// Build: go build -buildmode=plugin -o /tmp/gateway_v2.so ./cmd/gateway-plugin/
package main

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"sync/atomic"
	"time"

	"github.com/fluxorio/fluxor/pkg/core"
)

const version = "v2"

type GatewayV2 struct {
	*core.BaseVerticle
	server   *http.Server
	requests atomic.Int64
	start    time.Time
}

func (v *GatewayV2) Start(ctx core.FluxorContext) error {
	if err := v.BaseVerticle.Start(ctx); err != nil {
		return err
	}
	v.start = time.Now()

	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", v.handleHealth)
	mux.HandleFunc("GET /metrics", v.handleMetrics)
	mux.HandleFunc("POST /events", v.handlePublish(ctx))
	mux.HandleFunc("GET /events", v.handleSSE(ctx))

	mux.HandleFunc("POST /wire/login", wirePost(ctx, "saving.wire.login"))
	mux.HandleFunc("POST /wire/transfer", wirePost(ctx, "saving.wire.transfer"))
	mux.HandleFunc("POST /wire/merchant/register", wirePost(ctx, "saving.wire.register_merchant"))
	mux.HandleFunc("POST /wire/merchant/enroll_totp", wirePost(ctx, "saving.wire.enroll_totp"))
	mux.HandleFunc("POST /wire/intent/create", wirePost(ctx, "saving.wire.create_intent"))
	mux.HandleFunc("POST /wire/intent/pay", wirePost(ctx, "saving.wire.pay_intent"))
	mux.HandleFunc("GET /wire/balance", wireGet(ctx, "saving.wire.balance", "token"))
	mux.HandleFunc("GET /wire/ping", wireGet(ctx, "saving.wire.ping"))

	// v2: new endpoint
	mux.HandleFunc("GET /version", func(w http.ResponseWriter, r *http.Request) {
		jsonResp(w, 200, map[string]any{"version": version, "deployed": v.start.Format(time.RFC3339)})
	})

	port := os.Getenv("GATEWAY_PORT")
	if port == "" {
		port = "8080"
	}
	v.server = &http.Server{
		Addr:              ":" + port,
		Handler:           v.countMW(mux),
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		slog.Info("saving-gateway v2 ready", "addr", ":"+port)
		if err := v.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("gateway v2 crashed", "err", err)
		}
	}()

	ctx.EventBus().Consumer("saving.*").Handler(func(_ core.FluxorContext, msg core.Message) error {
		slog.Info("event", "body", fmt.Sprintf("%s", msg.Body()))
		return nil
	})

	return nil
}

func (v *GatewayV2) Stop(ctx core.FluxorContext) error {
	if v.server != nil {
		_ = v.server.Close()
	}
	return v.BaseVerticle.Stop(ctx)
}

func (v *GatewayV2) handleHealth(w http.ResponseWriter, r *http.Request) {
	jsonResp(w, 200, map[string]any{
		"status":  "ok",
		"service": "saving-gateway",
		"version": version,
		"uptime":  time.Since(v.start).Round(time.Second).String(),
	})
}

func (v *GatewayV2) handleMetrics(w http.ResponseWriter, r *http.Request) {
	jsonResp(w, 200, map[string]any{
		"version":  version,
		"requests": v.requests.Load(),
		"uptime_s": int(time.Since(v.start).Seconds()),
	})
}

func (v *GatewayV2) handlePublish(ctx core.FluxorContext) http.HandlerFunc {
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

func (v *GatewayV2) handleSSE(ctx core.FluxorContext) http.HandlerFunc {
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

func (v *GatewayV2) countMW(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		v.requests.Add(1)
		next.ServeHTTP(w, r)
	})
}

// ── wire bus helpers ─────────────────────────────────────────────────────────

func wirePost(ctx core.FluxorContext, addr string) http.HandlerFunc {
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

func wireGet(ctx core.FluxorContext, addr string, params ...string) http.HandlerFunc {
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

func jsonResp(w http.ResponseWriter, code int, val any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(val)
}

// NewVerticle is the plugin entrypoint.
func NewVerticle() core.Verticle {
	return &GatewayV2{BaseVerticle: core.NewBaseVerticle("saving-gateway-v2")}
}

func main() {} // required for go build ./...; actual entrypoint is NewVerticle
