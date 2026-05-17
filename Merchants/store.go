package main

import (
	"database/sql"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

// ─── Domain types ────────────────────────────────────────────────────────────

type Merchant struct {
	MID        uint32 `json:"mid"`
	Name       string `json:"name"`
	PubkeyB64  string `json:"pubkey_b64"`  // base64(ed25519 pubkey, 32 bytes)
	PrivkeyB64 string `json:"-"`           // never exposed in API responses
	Token      string `json:"-"`           // merchant API token
	CreatedAt  int64  `json:"created_at"`  // unix seconds
}

// OrderStatus values
const (
	StatusPending = "pending"
	StatusPaid    = "paid"
	StatusExpired = "expired"
)

type Order struct {
	ID        string  `json:"id"`
	MID       uint32  `json:"mid"`
	Amount    uint64  `json:"amount"`
	Note      string  `json:"note,omitempty"`
	Status    string  `json:"status"`
	CreatedAt int64   `json:"created_at"`
	PaidAt    *int64  `json:"paid_at,omitempty"`
	PaidBy    *uint32 `json:"paid_by,omitempty"`
}

// ─── Store ────────────────────────────────────────────────────────────────────

type Store struct {
	db *sql.DB
}

func OpenStore(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("open db: %w", err)
	}
	if err := migrate(db); err != nil {
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return &Store{db: db}, nil
}

func migrate(db *sql.DB) error {
	_, err := db.Exec(`
		CREATE TABLE IF NOT EXISTS merchants (
			mid          INTEGER PRIMARY KEY,
			name         TEXT    NOT NULL,
			pubkey_b64   TEXT    NOT NULL,
			privkey_b64  TEXT    NOT NULL DEFAULT '',
			token        TEXT    NOT NULL DEFAULT '',
			created_at   INTEGER NOT NULL
		);
		CREATE TABLE IF NOT EXISTS orders (
			id         TEXT    PRIMARY KEY,
			mid        INTEGER NOT NULL REFERENCES merchants(mid),
			amount     INTEGER NOT NULL,
			note       TEXT    NOT NULL DEFAULT '',
			status     TEXT    NOT NULL DEFAULT 'pending',
			created_at INTEGER NOT NULL,
			paid_at    INTEGER,
			paid_by    INTEGER
		);
		CREATE INDEX IF NOT EXISTS idx_orders_mid ON orders(mid);
	`)
	// Add columns that may not exist in older DBs (SQLite ignores errors here via separate stmts)
	db.Exec(`ALTER TABLE merchants ADD COLUMN privkey_b64 TEXT NOT NULL DEFAULT ''`)
	db.Exec(`ALTER TABLE merchants ADD COLUMN token       TEXT NOT NULL DEFAULT ''`)
	return err
}

func (s *Store) Close() { s.db.Close() }

// ─── Merchants ────────────────────────────────────────────────────────────────

func (s *Store) Register(mid uint32, name, pubkeyB64, privkeyB64, token string) error {
	_, err := s.db.Exec(
		`INSERT OR REPLACE INTO merchants (mid, name, pubkey_b64, privkey_b64, token, created_at)
		 VALUES (?, ?, ?, ?, ?, ?)`,
		mid, name, pubkeyB64, privkeyB64, token, time.Now().Unix(),
	)
	return err
}

func (s *Store) Get(mid uint32) (*Merchant, error) {
	row := s.db.QueryRow(
		`SELECT mid, name, pubkey_b64, privkey_b64, token, created_at FROM merchants WHERE mid = ?`, mid)
	var m Merchant
	if err := row.Scan(&m.MID, &m.Name, &m.PubkeyB64, &m.PrivkeyB64, &m.Token, &m.CreatedAt); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return &m, nil
}

// ─── Orders ───────────────────────────────────────────────────────────────────

func (s *Store) CreateOrder(id string, mid uint32, amount uint64, note string) error {
	_, err := s.db.Exec(
		`INSERT INTO orders (id, mid, amount, note, status, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
		id, mid, amount, note, StatusPending, time.Now().UnixMilli(),
	)
	return err
}

func (s *Store) GetOrder(id string) (*Order, error) {
	row := s.db.QueryRow(
		`SELECT id, mid, amount, note, status, created_at, paid_at, paid_by FROM orders WHERE id = ?`, id)
	var o Order
	var paidAt sql.NullInt64
	var paidBy sql.NullInt32
	if err := row.Scan(&o.ID, &o.MID, &o.Amount, &o.Note, &o.Status,
		&o.CreatedAt, &paidAt, &paidBy); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	if paidAt.Valid {
		v := paidAt.Int64
		o.PaidAt = &v
	}
	if paidBy.Valid {
		v := uint32(paidBy.Int32)
		o.PaidBy = &v
	}
	return &o, nil
}

func (s *Store) ListOrders(mid uint32, limit int) ([]Order, error) {
	rows, err := s.db.Query(
		`SELECT id, mid, amount, note, status, created_at, paid_at, paid_by
		 FROM orders WHERE mid = ? ORDER BY created_at DESC LIMIT ?`, mid, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Order
	for rows.Next() {
		var o Order
		var paidAt sql.NullInt64
		var paidBy sql.NullInt32
		if err := rows.Scan(&o.ID, &o.MID, &o.Amount, &o.Note, &o.Status,
			&o.CreatedAt, &paidAt, &paidBy); err != nil {
			return nil, err
		}
		if paidAt.Valid {
			v := paidAt.Int64
			o.PaidAt = &v
		}
		if paidBy.Valid {
			v := uint32(paidBy.Int32)
			o.PaidBy = &v
		}
		out = append(out, o)
	}
	return out, nil
}

func (s *Store) Stats(mid uint32) (totalEarned uint64, orderCount int, err error) {
	row := s.db.QueryRow(
		`SELECT COALESCE(SUM(amount),0), COUNT(*) FROM orders WHERE mid=? AND status='paid'`, mid)
	err = row.Scan(&totalEarned, &orderCount)
	return
}

func (s *Store) MarkPaid(orderID string, paidBy uint32) error {
	res, err := s.db.Exec(
		`UPDATE orders SET status = ?, paid_at = ?, paid_by = ?
		 WHERE id = ? AND status = ?`,
		StatusPaid, time.Now().UnixMilli(), paidBy, orderID, StatusPending,
	)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return fmt.Errorf("order not found or already processed")
	}
	return nil
}
