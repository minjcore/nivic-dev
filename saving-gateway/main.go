package main

import (
	"context"
	"fmt"
	"log"
	"os"

	"github.com/fluxorio/fluxor/pkg/core"
	"github.com/fluxorio/fluxor/pkg/core/cluster/eventbus"
	"github.com/fluxorio/fluxor/pkg/entrypoint"
)

const banner = `

`

func main() {
	fmt.Print(banner)
	fmt.Println("BFF layer for saving wire server • :8081")
	fmt.Println("──────────────────────────────────────────")

	app, err := entrypoint.NewMainVerticleWithOptions("", entrypoint.WithOptions(entrypoint.MainVerticleOptions{
		// Embedded NATS — required for subprocess verticles to share EventBus.
		BootstrapHook: entrypoint.StartEmbeddedNATS,

		// Switch to NATS EventBus so both in-process and subprocess verticles
		// see each other's messages.
		EventBusFactory: func(ctx context.Context, gocmd core.GoCMD, cfg map[string]any) (core.EventBus, error) {
			natsURL, _ := cfg["nats_url"].(string)
			return eventbus.NewClusterEventBusNATS(ctx, gocmd, eventbus.ClusterNATSConfig{
				URL: natsURL,
			})
		},

		EnableSubprocessManager: true,
		AdminSocketPath:         "/tmp/saving-gateway.sock",
	}))
	if err != nil {
		log.Fatalf("init: %v", err)
	}

	// WireVerticle runs in-process — owns the TCP connection to Wire C server
	// and publishes/consumes saving.wire.* events on the shared NATS EventBus.
	if _, err = app.DeployVerticle(NewWireVerticle()); err != nil {
		log.Fatalf("deploy wire: %v", err)
	}

	// GatewayVerticle runs as a subprocess — connects to the same NATS EventBus,
	// so it can call saving.wire.* handlers owned by WireVerticle above.
	// Binary path: GATEWAY_SUBPROCESS_BIN env var or default.
	gatewayBin := os.Getenv("GATEWAY_SUBPROCESS_BIN")
	if gatewayBin == "" {
		gatewayBin = "/root/app/saving-gateway/gateway-subprocess"
	}
	if id, err := app.SpawnSubprocess(gatewayBin); err != nil {
		log.Printf("warn: failed to spawn gateway subprocess: %v (continuing without HTTP)", err)
	} else {
		log.Printf("gateway subprocess spawned: %s", id)
	}

	if err := app.Start(); err != nil {
		log.Fatalf("start: %v", err)
	}

	if err := app.Stop(); err != nil {
		log.Printf("stop: %v", err)
	}
}
