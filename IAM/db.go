package main

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"log/slog"
	"time"
)

func migrate(db *sql.DB) error {
	_, err := db.Exec(`
		CREATE TABLE IF NOT EXISTS iam_refresh_tokens (
			id         BIGSERIAL PRIMARY KEY,
			token_hash TEXT        NOT NULL UNIQUE,
			uid        BIGINT      NOT NULL,
			expires_at TIMESTAMPTZ NOT NULL,
			revoked_at TIMESTAMPTZ,
			created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);
		CREATE INDEX IF NOT EXISTS idx_iam_rt_hash ON iam_refresh_tokens(token_hash);

		CREATE TABLE IF NOT EXISTS iam_audit_logs (
			id         BIGSERIAL PRIMARY KEY,
			event      TEXT        NOT NULL,
			uid        BIGINT,
			ip         TEXT,
			success    BOOLEAN     NOT NULL DEFAULT TRUE,
			created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
		);
	`)
	return err
}

func rtHash(plain string) string {
	h := sha256.Sum256([]byte(plain))
	return hex.EncodeToString(h[:])
}

func storeRefreshToken(db *sql.DB, uid uint32, plain string, ttl time.Duration) error {
	_, err := db.Exec(
		`INSERT INTO iam_refresh_tokens (token_hash, uid, expires_at) VALUES ($1, $2, $3)`,
		rtHash(plain), uid, time.Now().Add(ttl),
	)
	return err
}

func validateRefreshToken(db *sql.DB, plain string) (uid uint32, ok bool) {
	var u uint64
	var revokedAt sql.NullTime
	err := db.QueryRow(
		`SELECT uid, revoked_at FROM iam_refresh_tokens
		 WHERE token_hash=$1 AND expires_at > NOW()`,
		rtHash(plain),
	).Scan(&u, &revokedAt)
	if err != nil || revokedAt.Valid {
		return 0, false
	}
	return uint32(u), true
}

func revokeRefreshToken(db *sql.DB, plain string) error {
	_, err := db.Exec(
		`UPDATE iam_refresh_tokens SET revoked_at=NOW() WHERE token_hash=$1`,
		rtHash(plain),
	)
	return err
}

func auditLog(db *sql.DB, event string, uid uint32, ip string, success bool) {
	_, err := db.Exec(
		`INSERT INTO iam_audit_logs (event, uid, ip, success) VALUES ($1, $2, $3, $4)`,
		event, uid, ip, success,
	)
	if err != nil {
		slog.Warn("audit log failed", "err", err)
	}
}
