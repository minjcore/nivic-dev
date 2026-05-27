package main

import (
	"fmt"
	"log"

	"github.com/fluxorio/fluxor/pkg/entrypoint"
)

func main() {
	fmt.Println("IAM • Saving platform identity service • internal")
	fmt.Println("──────────────────────────────────────────────────")

	cfg := loadConfig()

	app, err := entrypoint.NewMainVerticle("")
	if err != nil {
		log.Fatalf("init: %v", err)
	}

	if _, err = app.DeployVerticle(NewIAMVerticle(cfg)); err != nil {
		log.Fatalf("deploy iam: %v", err)
	}

	if err := app.Start(); err != nil {
		log.Fatalf("start: %v", err)
	}
	app.Stop()
}
