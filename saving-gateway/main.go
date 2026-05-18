package main

import (
	"fmt"
	"log"

	"github.com/fluxorio/fluxor/pkg/entrypoint"
)

const banner = `
╔═╗┌─┐┬  ┬┬┌┐┌┌─┐  ╔═╗┌─┐┌┬┐┌─┐┬ ┬┌─┐┬ ┬
╚═╗├─┤└┐┌┘│││││ ┬  ║ ╦├─┤ │ ├┤ │││├─┤└┬┘
╚═╝┴ ┴ └┘ ┴┘└┘└─┘  ╚═╝┴ ┴ ┴ └─┘└┴┘┴ ┴ ┴
`

func main() {
	fmt.Print(banner)
	fmt.Println("BFF layer for saving wire server • :8080")
	fmt.Println("──────────────────────────────────────────")

	app, err := entrypoint.NewMainVerticle("")
	if err != nil {
		log.Fatalf("init: %v", err)
	}

	if _, err = app.DeployVerticle(NewGatewayVerticle()); err != nil {
		log.Fatalf("deploy: %v", err)
	}

	if err := app.Start(); err != nil {
		log.Fatalf("start: %v", err)
	}

	if err := app.Stop(); err != nil {
		log.Printf("stop: %v", err)
	}
}
