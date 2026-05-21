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
	ID             string  `json:"id"`
	MID            uint32  `json:"mid"`
	Amount         uint64  `json:"amount"`
	Note           string  `json:"note,omitempty"`
	Status         string  `json:"status"`
	CreatedAt      int64   `json:"created_at"`
	PaidAt         *int64  `json:"paid_at,omitempty"`
	PaidBy         *uint32 `json:"paid_by,omitempty"`
	DiscountPoints int64   `json:"discount_points,omitempty"`
	PointsAwarded  int64   `json:"points_awarded,omitempty"`
}

// LoyaltyEntry is one row in the merchant's loyalty member list.
type LoyaltyEntry struct {
	UID    uint32 `json:"uid"`
	Points int64  `json:"points"`
}

// UserLoyaltyEntry is one merchant's points from a user's perspective.
type UserLoyaltyEntry struct {
	MID        uint32 `json:"mid"`
	MerchantName string `json:"merchant_name"`
	Points     int64  `json:"points"`
}

// PointsPerVND: 1 point per 1,000 VND spent.
const PointsPerVND = 1_000

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
			id              TEXT    PRIMARY KEY,
			mid             INTEGER NOT NULL REFERENCES merchants(mid),
			amount          INTEGER NOT NULL,
			note            TEXT    NOT NULL DEFAULT '',
			status          TEXT    NOT NULL DEFAULT 'pending',
			created_at      INTEGER NOT NULL,
			paid_at         INTEGER,
			paid_by         INTEGER,
			discount_points INTEGER NOT NULL DEFAULT 0,
			points_awarded  INTEGER NOT NULL DEFAULT 0
		);
		CREATE INDEX IF NOT EXISTS idx_orders_mid ON orders(mid);
		CREATE TABLE IF NOT EXISTS loyalty_points (
			uid    INTEGER NOT NULL,
			mid    INTEGER NOT NULL REFERENCES merchants(mid),
			points INTEGER NOT NULL DEFAULT 0,
			PRIMARY KEY (uid, mid)
		);
		CREATE INDEX IF NOT EXISTS idx_loyalty_uid ON loyalty_points(uid);
		CREATE TABLE IF NOT EXISTS chat_messages (
			id            INTEGER PRIMARY KEY AUTOINCREMENT,
			mid           INTEGER NOT NULL,
			uid           INTEGER NOT NULL,
			from_merchant INTEGER NOT NULL DEFAULT 0,
			body          TEXT    NOT NULL,
			created_at    INTEGER NOT NULL
		);
		CREATE INDEX IF NOT EXISTS idx_chat_mid_uid ON chat_messages(mid, uid, created_at);
	`)
	db.Exec(`ALTER TABLE merchants ADD COLUMN privkey_b64 TEXT NOT NULL DEFAULT ''`)
	db.Exec(`ALTER TABLE merchants ADD COLUMN token       TEXT NOT NULL DEFAULT ''`)
	db.Exec(`ALTER TABLE orders ADD COLUMN discount_points INTEGER NOT NULL DEFAULT 0`)
	db.Exec(`ALTER TABLE orders ADD COLUMN points_awarded  INTEGER NOT NULL DEFAULT 0`)

	// FTS5 full-text index on merchant names
	db.Exec(`CREATE VIRTUAL TABLE IF NOT EXISTS merchants_fts USING fts5(
		name, content='merchants', content_rowid='mid', tokenize='unicode61'
	)`)
	db.Exec(`CREATE TRIGGER IF NOT EXISTS merchants_ai AFTER INSERT ON merchants BEGIN
		INSERT INTO merchants_fts(rowid, name) VALUES (new.mid, new.name);
	END`)
	db.Exec(`CREATE TRIGGER IF NOT EXISTS merchants_ad AFTER DELETE ON merchants BEGIN
		INSERT INTO merchants_fts(merchants_fts, rowid, name) VALUES ('delete', old.mid, old.name);
	END`)
	db.Exec(`CREATE TRIGGER IF NOT EXISTS merchants_au AFTER UPDATE ON merchants BEGIN
		INSERT INTO merchants_fts(merchants_fts, rowid, name) VALUES ('delete', old.mid, old.name);
		INSERT INTO merchants_fts(rowid, name) VALUES (new.mid, new.name);
	END`)
	// populate index for pre-existing rows (no-op if already indexed)
	db.Exec(`INSERT INTO merchants_fts(merchants_fts) VALUES('rebuild')`)

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

func (s *Store) CreateOrder(id string, mid uint32, amount uint64, note string, discountPoints int64) error {
	_, err := s.db.Exec(
		`INSERT INTO orders (id, mid, amount, note, status, created_at, discount_points)
		 VALUES (?, ?, ?, ?, ?, ?, ?)`,
		id, mid, amount, note, StatusPending, time.Now().UnixMilli(), discountPoints,
	)
	return err
}

func (s *Store) GetOrder(id string) (*Order, error) {
	row := s.db.QueryRow(
		`SELECT id, mid, amount, note, status, created_at, paid_at, paid_by,
		        discount_points, points_awarded FROM orders WHERE id = ?`, id)
	var o Order
	var paidAt sql.NullInt64
	var paidBy sql.NullInt32
	if err := row.Scan(&o.ID, &o.MID, &o.Amount, &o.Note, &o.Status,
		&o.CreatedAt, &paidAt, &paidBy, &o.DiscountPoints, &o.PointsAwarded); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	if paidAt.Valid { v := paidAt.Int64; o.PaidAt = &v }
	if paidBy.Valid { v := uint32(paidBy.Int32); o.PaidBy = &v }
	return &o, nil
}

func (s *Store) ListOrders(mid uint32, limit int) ([]Order, error) {
	rows, err := s.db.Query(
		`SELECT id, mid, amount, note, status, created_at, paid_at, paid_by,
		        discount_points, points_awarded
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
			&o.CreatedAt, &paidAt, &paidBy, &o.DiscountPoints, &o.PointsAwarded); err != nil {
			return nil, err
		}
		if paidAt.Valid { v := paidAt.Int64; o.PaidAt = &v }
		if paidBy.Valid { v := uint32(paidBy.Int32); o.PaidBy = &v }
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

// MarkPaid marks an order as paid, deducts used points, and awards new points.
// Returns points awarded so callers can include it in responses.
func (s *Store) MarkPaid(orderID string, paidBy uint32) (pointsAwarded int64, err error) {
	// Load order to get amount and discount_points
	o, err := s.GetOrder(orderID)
	if err != nil || o == nil {
		return 0, fmt.Errorf("order not found or already processed")
	}
	if o.Status != StatusPending {
		return 0, fmt.Errorf("order not found or already processed")
	}

	// Points to award = floor(amount / PointsPerVND)
	pointsAwarded = int64(o.Amount / PointsPerVND)

	tx, err := s.db.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()

	res, err := tx.Exec(
		`UPDATE orders SET status=?, paid_at=?, paid_by=?, points_awarded=?
		 WHERE id=? AND status=?`,
		StatusPaid, time.Now().UnixMilli(), paidBy, pointsAwarded, orderID, StatusPending,
	)
	if err != nil {
		return 0, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return 0, fmt.Errorf("order not found or already processed")
	}

	// Deduct used points (if any)
	if o.DiscountPoints > 0 {
		if _, err := tx.Exec(
			`UPDATE loyalty_points SET points = MAX(0, points - ?)
			 WHERE uid=? AND mid=?`,
			o.DiscountPoints, paidBy, o.MID,
		); err != nil {
			return 0, err
		}
	}

	// Award earned points
	if pointsAwarded > 0 {
		if _, err := tx.Exec(
			`INSERT INTO loyalty_points (uid, mid, points) VALUES (?, ?, ?)
			 ON CONFLICT(uid, mid) DO UPDATE SET points = points + excluded.points`,
			paidBy, o.MID, pointsAwarded,
		); err != nil {
			return 0, err
		}
	}

	return pointsAwarded, tx.Commit()
}

// ─── Loyalty ──────────────────────────────────────────────────────────────────

func (s *Store) GetPoints(mid, uid uint32) (int64, error) {
	row := s.db.QueryRow(`SELECT points FROM loyalty_points WHERE uid=? AND mid=?`, uid, mid)
	var pts int64
	if err := row.Scan(&pts); err == sql.ErrNoRows {
		return 0, nil
	} else if err != nil {
		return 0, err
	}
	return pts, nil
}

func (s *Store) AwardPoints(mid, uid uint32, points int64) error {
	_, err := s.db.Exec(
		`INSERT INTO loyalty_points (uid, mid, points) VALUES (?, ?, ?)
		 ON CONFLICT(uid, mid) DO UPDATE SET points = points + excluded.points`,
		uid, mid, points,
	)
	return err
}

func (s *Store) ListLoyaltyMembers(mid uint32) ([]LoyaltyEntry, error) {
	rows, err := s.db.Query(
		`SELECT uid, points FROM loyalty_points WHERE mid=? ORDER BY points DESC`, mid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []LoyaltyEntry
	for rows.Next() {
		var e LoyaltyEntry
		if err := rows.Scan(&e.UID, &e.Points); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, nil
}

type MerchantSearchResult struct {
	MID  uint32 `json:"mid"`
	Name string `json:"name"`
}

func (s *Store) SearchMerchants(query string) ([]MerchantSearchResult, error) {
	var rows *sql.Rows
	var err error
	if query == "" {
		rows, err = s.db.Query(`SELECT mid, name FROM merchants ORDER BY name LIMIT 50`)
	} else {
		// FTS5 prefix search — append * for partial word matching, rank by BM25
		ftsQuery := query + "*"
		rows, err = s.db.Query(`
			SELECT m.mid, m.name
			FROM merchants_fts f
			JOIN merchants m ON m.mid = f.rowid
			WHERE merchants_fts MATCH ?
			ORDER BY rank
			LIMIT 20`, ftsQuery)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MerchantSearchResult
	for rows.Next() {
		var r MerchantSearchResult
		if err := rows.Scan(&r.MID, &r.Name); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, nil
}

// ─── Chat ─────────────────────────────────────────────────────────────────────

type ChatMessage struct {
	ID           int64  `json:"id"`
	MID          uint32 `json:"mid"`
	UID          uint32 `json:"uid"`
	FromMerchant bool   `json:"from_merchant"`
	Body         string `json:"body"`
	CreatedAt    int64  `json:"created_at"` // unix millis
}

func (s *Store) SendChatMessage(mid, uid uint32, fromMerchant bool, body string) (int64, error) {
	fm := 0
	if fromMerchant {
		fm = 1
	}
	res, err := s.db.Exec(
		`INSERT INTO chat_messages (mid, uid, from_merchant, body, created_at) VALUES (?, ?, ?, ?, ?)`,
		mid, uid, fm, body, time.Now().UnixMilli(),
	)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

type ChatInboxItem struct {
	UID         uint32 `json:"uid"`
	LastMessage string `json:"last_message"`
	LastAt      int64  `json:"last_at"`
	Unread      int    `json:"unread"` // customer messages without a subsequent merchant reply
}

func (s *Store) GetChatInbox(mid uint32) ([]ChatInboxItem, error) {
	rows, err := s.db.Query(`
		SELECT uid,
		       (SELECT body FROM chat_messages m2
		        WHERE m2.mid=cm.mid AND m2.uid=cm.uid
		        ORDER BY created_at DESC LIMIT 1) AS last_message,
		       MAX(created_at) AS last_at,
		       SUM(CASE WHEN from_merchant=0 AND created_at > COALESCE(
		               (SELECT MAX(created_at) FROM chat_messages m3
		                WHERE m3.mid=cm.mid AND m3.uid=cm.uid AND m3.from_merchant=1), 0)
		           THEN 1 ELSE 0 END) AS unread
		FROM chat_messages cm
		WHERE mid=?
		GROUP BY uid
		ORDER BY last_at DESC
		LIMIT 50`, mid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ChatInboxItem
	for rows.Next() {
		var item ChatInboxItem
		if err := rows.Scan(&item.UID, &item.LastMessage, &item.LastAt, &item.Unread); err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, nil
}

func (s *Store) GetChatThread(mid, uid uint32, since int64) ([]ChatMessage, error) {
	rows, err := s.db.Query(
		`SELECT id, mid, uid, from_merchant, body, created_at
		 FROM chat_messages WHERE mid=? AND uid=? AND created_at > ?
		 ORDER BY created_at ASC LIMIT 100`,
		mid, uid, since,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ChatMessage
	for rows.Next() {
		var m ChatMessage
		var fm int
		if err := rows.Scan(&m.ID, &m.MID, &m.UID, &fm, &m.Body, &m.CreatedAt); err != nil {
			return nil, err
		}
		m.FromMerchant = fm == 1
		out = append(out, m)
	}
	return out, nil
}

func (s *Store) UserLoyalty(uid uint32) ([]UserLoyaltyEntry, error) {
	rows, err := s.db.Query(
		`SELECT lp.mid, m.name, lp.points
		 FROM loyalty_points lp
		 JOIN merchants m ON m.mid = lp.mid
		 WHERE lp.uid=? AND lp.points > 0
		 ORDER BY lp.points DESC`, uid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []UserLoyaltyEntry
	for rows.Next() {
		var e UserLoyaltyEntry
		if err := rows.Scan(&e.MID, &e.MerchantName, &e.Points); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, nil
}
