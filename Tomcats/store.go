package main

import (
	"database/sql"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

type Store struct{ db *sql.DB }

func OpenStore(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("open: %w", err)
	}
	_, err = db.Exec(`
		CREATE TABLE IF NOT EXISTS device_tokens (
			uid        INTEGER NOT NULL,
			platform   TEXT    NOT NULL CHECK(platform IN ('ios','android')),
			token      TEXT    NOT NULL,
			updated_at INTEGER NOT NULL,
			PRIMARY KEY (uid, platform)
		);
		CREATE TABLE IF NOT EXISTS email_bindings (
			uid        INTEGER NOT NULL PRIMARY KEY,
			email      TEXT    NOT NULL UNIQUE,
			created_at INTEGER NOT NULL
		);
		CREATE TABLE IF NOT EXISTS otp_codes (
			email      TEXT    NOT NULL,
			code       TEXT    NOT NULL,
			uid        INTEGER,
			expires_at INTEGER NOT NULL,
			used       INTEGER NOT NULL DEFAULT 0,
			PRIMARY KEY (email, code)
		);
		CREATE TABLE IF NOT EXISTS wire_sessions (
			uid        INTEGER NOT NULL PRIMARY KEY,
			wire_token TEXT    NOT NULL,
			updated_at INTEGER NOT NULL
		);
	`)
	if err != nil {
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() { s.db.Close() }

// ── Email binding ─────────────────────────────────────────────────────────────

func (s *Store) SaveOTP(email, code string, uid *uint32, ttl time.Duration) error {
	exp := time.Now().Add(ttl).Unix()
	var uidVal any
	if uid != nil {
		uidVal = *uid
	}
	_, err := s.db.Exec(
		`INSERT OR REPLACE INTO otp_codes (email, code, uid, expires_at, used) VALUES (?, ?, ?, ?, 0)`,
		email, code, uidVal, exp)
	return err
}

func (s *Store) VerifyOTP(email, code string) (uid *uint32, ok bool) {
	var uidVal sql.NullInt64
	var exp int64
	var used int
	err := s.db.QueryRow(
		`SELECT uid, expires_at, used FROM otp_codes WHERE email = ? AND code = ?`,
		email, code).Scan(&uidVal, &exp, &used)
	if err != nil || used == 1 || time.Now().Unix() > exp {
		return nil, false
	}
	s.db.Exec(`UPDATE otp_codes SET used = 1 WHERE email = ? AND code = ?`, email, code)
	if uidVal.Valid {
		v := uint32(uidVal.Int64)
		return &v, true
	}
	return nil, true
}

func (s *Store) BindEmail(uid uint32, email string) error {
	_, err := s.db.Exec(
		`INSERT OR REPLACE INTO email_bindings (uid, email, created_at) VALUES (?, ?, ?)`,
		uid, email, time.Now().Unix())
	return err
}

func (s *Store) LookupEmail(email string) (uid uint32, ok bool) {
	err := s.db.QueryRow(`SELECT uid FROM email_bindings WHERE email = ?`, email).Scan(&uid)
	return uid, err == nil
}

func (s *Store) GetEmail(uid uint32) (email string, ok bool) {
	err := s.db.QueryRow(`SELECT email FROM email_bindings WHERE uid = ?`, uid).Scan(&email)
	return email, err == nil
}

func (s *Store) SaveWireToken(uid uint32, token []byte) error {
	_, err := s.db.Exec(
		`INSERT OR REPLACE INTO wire_sessions (uid, wire_token, updated_at) VALUES (?, ?, ?)`,
		uid, fmt.Sprintf("%x", token), time.Now().Unix())
	return err
}

func (s *Store) GetWireToken(uid uint32) ([]byte, bool) {
	var hex string
	err := s.db.QueryRow(`SELECT wire_token FROM wire_sessions WHERE uid = ?`, uid).Scan(&hex)
	if err != nil {
		return nil, false
	}
	b := make([]byte, len(hex)/2)
	for i := range b {
		fmt.Sscanf(hex[2*i:2*i+2], "%02x", &b[i])
	}
	return b, true
}

func (s *Store) Register(uid uint32, platform, token string) error {
	_, err := s.db.Exec(`
		INSERT INTO device_tokens (uid, platform, token, updated_at)
		VALUES (?, ?, ?, ?)
		ON CONFLICT(uid, platform) DO UPDATE SET
			token      = excluded.token,
			updated_at = excluded.updated_at`,
		uid, platform, token, time.Now().Unix())
	return err
}

// GetTokens returns the APNs and FCM tokens for a user (empty string if absent).
func (s *Store) GetTokens(uid uint32) (apns, fcm string, err error) {
	rows, err := s.db.Query(
		`SELECT platform, token FROM device_tokens WHERE uid = ?`, uid)
	if err != nil {
		return
	}
	defer rows.Close()
	for rows.Next() {
		var platform, tok string
		if e := rows.Scan(&platform, &tok); e != nil {
			err = e
			return
		}
		switch platform {
		case "ios":
			apns = tok
		case "android":
			fcm = tok
		}
	}
	return
}
