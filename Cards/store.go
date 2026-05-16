package main

import (
	"database/sql"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

type Card struct {
	ID        string `json:"id"`
	UID       uint32 `json:"uid"`
	Last4     string `json:"last4"`
	Bank      string `json:"bank"`
	Expiry    string `json:"expiry"`  // MM/YY
	Label     string `json:"label,omitempty"`
	Status    string `json:"status"`  // active | removed
	CreatedAt int64  `json:"created_at"`
}

type TopUp struct {
	ID        string `json:"id"`
	UID       uint32 `json:"uid"`
	CardID    string `json:"card_id"`
	Amount    uint64 `json:"amount"`
	Status    string `json:"status"`  // pending | done | failed
	CreatedAt int64  `json:"created_at"`
}

type Store struct{ db *sql.DB }

func OpenStore(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("open: %w", err)
	}
	_, err = db.Exec(`
		CREATE TABLE IF NOT EXISTS cards (
			id         TEXT    PRIMARY KEY,
			uid        INTEGER NOT NULL,
			last4      TEXT    NOT NULL,
			bank       TEXT    NOT NULL,
			expiry     TEXT    NOT NULL,
			label      TEXT    NOT NULL DEFAULT '',
			status     TEXT    NOT NULL DEFAULT 'active',
			created_at INTEGER NOT NULL
		);
		CREATE INDEX IF NOT EXISTS idx_cards_uid ON cards(uid);

		CREATE TABLE IF NOT EXISTS topups (
			id         TEXT    PRIMARY KEY,
			uid        INTEGER NOT NULL,
			card_id    TEXT    NOT NULL REFERENCES cards(id),
			amount     INTEGER NOT NULL,
			status     TEXT    NOT NULL DEFAULT 'pending',
			created_at INTEGER NOT NULL
		);

		CREATE TABLE IF NOT EXISTS device_tokens (
			uid        INTEGER PRIMARY KEY,
			token      TEXT    NOT NULL,
			updated_at INTEGER NOT NULL
		);
	`)
	if err != nil {
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() { s.db.Close() }

// ─── Cards ────────────────────────────────────────────────────────────────────

func (s *Store) AddCard(id string, uid uint32, last4, bank, expiry, label string) error {
	_, err := s.db.Exec(
		`INSERT INTO cards (id, uid, last4, bank, expiry, label, status, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, 'active', ?)`,
		id, uid, last4, bank, expiry, label, time.Now().Unix(),
	)
	return err
}

func (s *Store) ListCards(uid uint32) ([]Card, error) {
	rows, err := s.db.Query(
		`SELECT id, uid, last4, bank, expiry, label, status, created_at
		 FROM cards WHERE uid = ? AND status = 'active' ORDER BY created_at DESC`, uid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var cards []Card
	for rows.Next() {
		var c Card
		if err := rows.Scan(&c.ID, &c.UID, &c.Last4, &c.Bank,
			&c.Expiry, &c.Label, &c.Status, &c.CreatedAt); err != nil {
			return nil, err
		}
		cards = append(cards, c)
	}
	return cards, nil
}

func (s *Store) GetCard(id string, uid uint32) (*Card, error) {
	row := s.db.QueryRow(
		`SELECT id, uid, last4, bank, expiry, label, status, created_at
		 FROM cards WHERE id = ? AND uid = ?`, id, uid)
	var c Card
	if err := row.Scan(&c.ID, &c.UID, &c.Last4, &c.Bank,
		&c.Expiry, &c.Label, &c.Status, &c.CreatedAt); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return &c, nil
}

func (s *Store) RemoveCard(id string, uid uint32) error {
	res, err := s.db.Exec(
		`UPDATE cards SET status = 'removed' WHERE id = ? AND uid = ? AND status = 'active'`,
		id, uid)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return fmt.Errorf("card not found")
	}
	return nil
}

// ─── Top-ups ──────────────────────────────────────────────────────────────────

func (s *Store) CreateTopUp(id, cardID string, uid uint32, amount uint64) error {
	_, err := s.db.Exec(
		`INSERT INTO topups (id, uid, card_id, amount, status, created_at)
		 VALUES (?, ?, ?, ?, 'pending', ?)`,
		id, uid, cardID, amount, time.Now().Unix(),
	)
	return err
}

func (s *Store) CompleteTopUp(id, status string) error {
	res, err := s.db.Exec(
		`UPDATE topups SET status = ? WHERE id = ? AND status = 'pending'`, status, id)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return fmt.Errorf("topup not found or already processed")
	}
	return nil
}

// ─── Device tokens ────────────────────────────────────────────────────────────

func (s *Store) RegisterDeviceToken(uid uint32, token string) error {
	_, err := s.db.Exec(
		`INSERT INTO device_tokens (uid, token, updated_at) VALUES (?, ?, ?)
		 ON CONFLICT(uid) DO UPDATE SET token = excluded.token, updated_at = excluded.updated_at`,
		uid, token, time.Now().Unix())
	return err
}

func (s *Store) GetDeviceToken(uid uint32) (string, error) {
	var token string
	err := s.db.QueryRow(`SELECT token FROM device_tokens WHERE uid = ?`, uid).Scan(&token)
	if err == sql.ErrNoRows {
		return "", nil
	}
	return token, err
}

// ─── Top-ups (continued) ──────────────────────────────────────────────────────

func (s *Store) GetTopUp(id string) (*TopUp, error) {
	row := s.db.QueryRow(
		`SELECT id, uid, card_id, amount, status, created_at FROM topups WHERE id = ?`, id)
	var t TopUp
	if err := row.Scan(&t.ID, &t.UID, &t.CardID, &t.Amount, &t.Status, &t.CreatedAt); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return &t, nil
}
