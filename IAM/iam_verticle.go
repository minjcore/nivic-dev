package main

import (
	"database/sql"
	"fmt"
	"log/slog"
	"net/http"

	_ "github.com/lib/pq"

	"github.com/fluxorio/fluxor/pkg/core"
)

type IAMVerticle struct {
	*core.BaseVerticle
	db  *sql.DB
	srv *http.Server
	cfg Config
}

func NewIAMVerticle(cfg Config) *IAMVerticle {
	return &IAMVerticle{
		BaseVerticle: core.NewBaseVerticle("iam"),
		cfg:          cfg,
	}
}

func (v *IAMVerticle) Start(ctx core.FluxorContext) error {
	if err := v.BaseVerticle.Start(ctx); err != nil {
		return err
	}

	if v.cfg.DB == "" {
		return fmt.Errorf("IAM_DB not set")
	}
	if v.cfg.JWTSecret == "" {
		return fmt.Errorf("JWT_SECRET not set")
	}

	db, err := sql.Open("postgres", v.cfg.DB)
	if err != nil {
		return fmt.Errorf("open db: %w", err)
	}
	if err := db.Ping(); err != nil {
		return fmt.Errorf("ping db: %w", err)
	}
	if err := migrate(db); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	v.db = db

	h := &handler{db: v.db, cfg: v.cfg}
	v.srv = &http.Server{
		Addr:    v.cfg.Addr,
		Handler: h.routes(),
	}

	v.ExecuteOn(func() {
		slog.Info("iam listening", "addr", v.cfg.Addr)
		if err := v.srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("iam server error", "err", err)
		}
	})

	return nil
}

func (v *IAMVerticle) Stop(ctx core.FluxorContext) error {
	if v.srv != nil {
		v.srv.Close()
	}
	if v.db != nil {
		v.db.Close()
	}
	return v.BaseVerticle.Stop(ctx)
}
