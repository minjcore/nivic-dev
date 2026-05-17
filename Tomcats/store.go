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
	`)
	if err != nil {
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() { s.db.Close() }

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
