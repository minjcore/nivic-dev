package main

import (
	"fmt"
	"log"

	"github.com/fluxorio/fluxor/pkg/entrypoint"
)

const banner = `

`

func main() {
	fmt.Print(banner)
	fmt.Println("BFF layer for saving wire server • :8080")
	fmt.Println("──────────────────────────────────────────")

	app, err := entrypoint.NewMainVerticleWithOptions("", entrypoint.WithOptions(entrypoint.MainVerticleOptions{
		AdminSocketPath: "/tmp/saving-gateway.sock",
	}))
	if err != nil {
		log.Fatalf("init: %v", err)
	}

	// WireVerticle first — owns the TCP connection and registers EventBus handlers
	// before GatewayVerticle registers HTTP routes that call them.
	if _, err = app.DeployVerticle(NewWireVerticle()); err != nil {
		log.Fatalf("deploy wire: %v", err)
	}

	if _, err = app.DeployVerticle(NewGatewayVerticle()); err != nil {
		log.Fatalf("deploy gateway: %v", err)
	}

	if err := app.Start(); err != nil {
		log.Fatalf("start: %v", err)
	}

	if err := app.Stop(); err != nil {
		log.Printf("stop: %v", err)
	}
}
