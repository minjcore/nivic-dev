package main

import (
	"database/sql"
	"fmt"
	"strings"
	"time"
	"unicode"

	_ "github.com/lib/pq"
)

// ─── Domain types ────────────────────────────────────────────────────────────

type Merchant struct {
	MID          uint32 `json:"mid"`
	Name         string `json:"name"`
	Email        string `json:"email,omitempty"`
	Address      string `json:"address,omitempty"`
	Website      string `json:"website,omitempty"`
	Slug         string `json:"slug,omitempty"`
	CustomDomain string `json:"custom_domain,omitempty"`
	PubkeyB64    string `json:"pubkey_b64"`
	PrivkeyB64   string `json:"-"`
	Token        string `json:"-"`
	PasswordHash string `json:"-"`
	CreatedAt    int64  `json:"created_at"`
}

type MenuItem struct {
	ID          int64  `json:"id"`
	MID         uint32 `json:"mid"`
	Name        string `json:"name"`
	Price       uint64 `json:"price"`
	Description string `json:"description,omitempty"`
	SortOrder   int    `json:"sort_order"`
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
	WireRequestID  uint64  `json:"wire_request_id,omitempty"`
}

// LoyaltyEntry is one row in the merchant's loyalty member list.
type LoyaltyEntry struct {
	UID    uint32 `json:"uid"`
	Points int64  `json:"points"`
}

// UserLoyaltyEntry is one merchant's points from a user's perspective.
type UserLoyaltyEntry struct {
	MID          uint32 `json:"mid"`
	MerchantName string `json:"merchant_name"`
	Points       int64  `json:"points"`
}

// PointsPerVND: 1 point per 1,000 VND spent.
const PointsPerVND = 1_000

// ─── Store ────────────────────────────────────────────────────────────────────

type Store struct {
	db *sql.DB
}

// OpenStore connects to a Postgres DSN (e.g. "postgres://user:pass@host/db?sslmode=disable").
func OpenStore(dsn string) (*Store, error) {
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, fmt.Errorf("open db: %w", err)
	}
	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("ping db: %w", err)
	}
	if err := migrate(db); err != nil {
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return &Store{db: db}, nil
}

func migrate(db *sql.DB) error {
	stmts := []string{
		`CREATE EXTENSION IF NOT EXISTS pg_trgm`,

		`CREATE TABLE IF NOT EXISTS merchants (
			mid           BIGINT  PRIMARY KEY,
			name          TEXT    NOT NULL,
			email         TEXT    NOT NULL DEFAULT '',
			pubkey_b64    TEXT    NOT NULL DEFAULT '',
			privkey_b64   TEXT    NOT NULL DEFAULT '',
			token         TEXT    NOT NULL DEFAULT '',
			address       TEXT    NOT NULL DEFAULT '',
			slug          TEXT    NOT NULL DEFAULT '',
			website       TEXT    NOT NULL DEFAULT '',
			custom_domain TEXT    NOT NULL DEFAULT '',
			password_hash TEXT    NOT NULL DEFAULT '',
			status        TEXT    NOT NULL DEFAULT 'active',
			created_at    BIGINT  NOT NULL
		)`,
		`ALTER TABLE merchants ADD COLUMN IF NOT EXISTS email  TEXT NOT NULL DEFAULT ''`,
		`ALTER TABLE merchants ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'active'`,

		`CREATE TABLE IF NOT EXISTS menu_items (
			id          BIGSERIAL PRIMARY KEY,
			mid         BIGINT  NOT NULL REFERENCES merchants(mid),
			name        TEXT    NOT NULL,
			price       BIGINT  NOT NULL DEFAULT 0,
			description TEXT    NOT NULL DEFAULT '',
			sort_order  INTEGER NOT NULL DEFAULT 0
		)`,

		`CREATE INDEX IF NOT EXISTS idx_menu_items_mid ON menu_items(mid, sort_order)`,

		`CREATE TABLE IF NOT EXISTS orders (
			id              TEXT   PRIMARY KEY,
			mid             BIGINT NOT NULL REFERENCES merchants(mid),
			amount          BIGINT NOT NULL,
			note            TEXT   NOT NULL DEFAULT '',
			status          TEXT   NOT NULL DEFAULT 'pending',
			created_at      BIGINT NOT NULL,
			paid_at         BIGINT,
			paid_by         BIGINT,
			discount_points BIGINT NOT NULL DEFAULT 0,
			points_awarded  BIGINT NOT NULL DEFAULT 0
		)`,

		`CREATE INDEX IF NOT EXISTS idx_orders_mid ON orders(mid)`,

		`CREATE TABLE IF NOT EXISTS loyalty_points (
			uid    BIGINT NOT NULL,
			mid    BIGINT NOT NULL REFERENCES merchants(mid),
			points BIGINT NOT NULL DEFAULT 0,
			PRIMARY KEY (uid, mid)
		)`,

		`CREATE INDEX IF NOT EXISTS idx_loyalty_uid ON loyalty_points(uid)`,

		`ALTER TABLE orders ADD COLUMN IF NOT EXISTS wire_request_id BIGINT NOT NULL DEFAULT 0`,
		`ALTER TABLE orders ADD COLUMN IF NOT EXISTS wire_txn_id BIGINT NOT NULL DEFAULT 0`,

		`CREATE TABLE IF NOT EXISTS chat_messages (
			id            BIGSERIAL PRIMARY KEY,
			mid           BIGINT  NOT NULL,
			uid           BIGINT  NOT NULL,
			from_merchant BOOLEAN NOT NULL DEFAULT false,
			body          TEXT    NOT NULL,
			created_at    BIGINT  NOT NULL
		)`,

		`CREATE INDEX IF NOT EXISTS idx_chat_mid_uid ON chat_messages(mid, uid, created_at)`,

		// Unique indexes — partial to allow empty string default
		`CREATE UNIQUE INDEX IF NOT EXISTS idx_merchants_slug   ON merchants(slug)          WHERE slug <> ''`,
		`CREATE UNIQUE INDEX IF NOT EXISTS idx_merchants_domain ON merchants(custom_domain) WHERE custom_domain <> ''`,

		// GIN trigram indexes for fast ILIKE search on Vietnamese text
		`CREATE INDEX IF NOT EXISTS idx_merchants_name_trgm ON merchants USING gin (name gin_trgm_ops)`,
		`CREATE INDEX IF NOT EXISTS idx_merchants_addr_trgm ON merchants USING gin (address gin_trgm_ops)`,
	}

	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			return fmt.Errorf("migrate stmt failed: %w\nSQL: %s", err, s)
		}
	}
	return nil
}

func (s *Store) Close() { s.db.Close() }

// ─── Merchants ────────────────────────────────────────────────────────────────

func (s *Store) Register(mid uint32, name, email, address, website, pubkeyB64, privkeyB64, token, passwordHash string) error {
	_, err := s.db.Exec(
		`INSERT INTO merchants (mid, name, email, address, website, pubkey_b64, privkey_b64, token, password_hash, created_at)
		 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
		 ON CONFLICT (mid) DO UPDATE SET
		     name=EXCLUDED.name, email=EXCLUDED.email, address=EXCLUDED.address, website=EXCLUDED.website,
		     pubkey_b64=EXCLUDED.pubkey_b64, privkey_b64=EXCLUDED.privkey_b64,
		     token=EXCLUDED.token, password_hash=EXCLUDED.password_hash`,
		mid, name, email, address, website, pubkeyB64, privkeyB64, token, passwordHash, time.Now().Unix(),
	)
	return err
}

// SetPassword updates the merchant's login password (SHA-256 hex).
func (s *Store) SetPassword(mid uint32, passwordHash string) error {
	_, err := s.db.Exec(`UPDATE merchants SET password_hash=$1 WHERE mid=$2`, passwordHash, mid)
	return err
}

// Login returns the merchant if mid + passwordHash match, nil if not found/wrong.
func (s *Store) Login(mid uint32, passwordHash string) (*Merchant, error) {
	m, err := s.Get(mid)
	if err != nil || m == nil {
		return nil, err
	}
	if m.PasswordHash == "" || m.PasswordHash != passwordHash {
		return nil, nil
	}
	return m, nil
}

const merchantCols = `mid, name, email, address, website, slug, custom_domain, pubkey_b64, privkey_b64, token, password_hash, created_at`

func scanMerchant(row *sql.Row) (*Merchant, error) {
	var m Merchant
	if err := row.Scan(&m.MID, &m.Name, &m.Email, &m.Address, &m.Website, &m.Slug, &m.CustomDomain,
		&m.PubkeyB64, &m.PrivkeyB64, &m.Token, &m.PasswordHash, &m.CreatedAt); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return &m, nil
}

func (s *Store) Get(mid uint32) (*Merchant, error) {
	return scanMerchant(s.db.QueryRow(`SELECT `+merchantCols+` FROM merchants WHERE mid = $1`, mid))
}

func (s *Store) GetBySlug(slug string) (*Merchant, error) {
	return scanMerchant(s.db.QueryRow(`SELECT `+merchantCols+` FROM merchants WHERE slug = $1`, slug))
}

func (s *Store) GetByDomain(domain string) (*Merchant, error) {
	return scanMerchant(s.db.QueryRow(`SELECT `+merchantCols+` FROM merchants WHERE custom_domain = $1`, domain))
}

func (s *Store) DomainExists(domain string) (bool, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM merchants WHERE custom_domain=$1`, domain).Scan(&n)
	return n > 0, err
}

func (s *Store) SetCustomDomain(mid uint32, domain string) error {
	_, err := s.db.Exec(`UPDATE merchants SET custom_domain=$1 WHERE mid=$2`, domain, mid)
	return err
}

func (s *Store) UpdateProfile(mid uint32, name, address, website string) error {
	_, err := s.db.Exec(
		`UPDATE merchants SET name=$1, address=$2, website=$3 WHERE mid=$4`,
		name, address, website, mid,
	)
	return err
}

func (s *Store) SetSlug(mid uint32, slug string) error {
	_, err := s.db.Exec(`UPDATE merchants SET slug=$1 WHERE mid=$2`, slug, mid)
	return err
}

func (s *Store) SlugExists(slug string) (bool, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM merchants WHERE slug=$1`, slug).Scan(&n)
	return n > 0, err
}

// ─── Orders ───────────────────────────────────────────────────────────────────

func (s *Store) CreateOrder(id string, mid uint32, amount uint64, note string, discountPoints int64) error {
	_, err := s.db.Exec(
		`INSERT INTO orders (id, mid, amount, note, status, created_at, discount_points)
		 VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		id, mid, amount, note, StatusPending, time.Now().UnixMilli(), discountPoints,
	)
	return err
}

func (s *Store) GetOrder(id string) (*Order, error) {
	row := s.db.QueryRow(
		`SELECT id, mid, amount, note, status, created_at, paid_at, paid_by,
		        discount_points, points_awarded, wire_request_id FROM orders WHERE id = $1`, id)
	var o Order
	var paidAt sql.NullInt64
	var paidBy sql.NullInt64
	if err := row.Scan(&o.ID, &o.MID, &o.Amount, &o.Note, &o.Status,
		&o.CreatedAt, &paidAt, &paidBy, &o.DiscountPoints, &o.PointsAwarded, &o.WireRequestID); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	if paidAt.Valid { v := paidAt.Int64; o.PaidAt = &v }
	if paidBy.Valid { v := uint32(paidBy.Int64); o.PaidBy = &v }
	return &o, nil
}

func (s *Store) SetOrderWireRequestID(id string, rid uint64) error {
	_, err := s.db.Exec(`UPDATE orders SET wire_request_id=$1 WHERE id=$2`, rid, id)
	return err
}

func (s *Store) ListOrders(mid uint32, limit int) ([]Order, error) {
	rows, err := s.db.Query(
		`SELECT id, mid, amount, note, status, created_at, paid_at, paid_by,
		        discount_points, points_awarded, wire_request_id
		 FROM orders WHERE mid = $1 ORDER BY created_at DESC LIMIT $2`, mid, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Order
	for rows.Next() {
		var o Order
		var paidAt sql.NullInt64
		var paidBy sql.NullInt64
		if err := rows.Scan(&o.ID, &o.MID, &o.Amount, &o.Note, &o.Status,
			&o.CreatedAt, &paidAt, &paidBy, &o.DiscountPoints, &o.PointsAwarded, &o.WireRequestID); err != nil {
			return nil, err
		}
		if paidAt.Valid { v := paidAt.Int64; o.PaidAt = &v }
		if paidBy.Valid { v := uint32(paidBy.Int64); o.PaidBy = &v }
		out = append(out, o)
	}
	return out, nil
}

func (s *Store) Stats(mid uint32) (totalEarned uint64, orderCount int, err error) {
	row := s.db.QueryRow(
		`SELECT COALESCE(SUM(amount),0), COUNT(*) FROM orders WHERE mid=$1 AND status='paid'`, mid)
	err = row.Scan(&totalEarned, &orderCount)
	return
}

// MarkPaid marks an order as paid, deducts used points, and awards new points.
func (s *Store) MarkPaid(orderID string, paidBy uint32) (pointsAwarded int64, err error) {
	o, err := s.GetOrder(orderID)
	if err != nil || o == nil {
		return 0, fmt.Errorf("order not found or already processed")
	}
	if o.Status != StatusPending {
		return 0, fmt.Errorf("order not found or already processed")
	}

	pointsAwarded = int64(o.Amount / PointsPerVND)

	tx, err := s.db.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()

	res, err := tx.Exec(
		`UPDATE orders SET status=$1, paid_at=$2, paid_by=$3, points_awarded=$4
		 WHERE id=$5 AND status=$6`,
		StatusPaid, time.Now().UnixMilli(), paidBy, pointsAwarded, orderID, StatusPending,
	)
	if err != nil {
		return 0, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return 0, fmt.Errorf("order not found or already processed")
	}

	if o.DiscountPoints > 0 {
		if _, err := tx.Exec(
			`UPDATE loyalty_points SET points = GREATEST(0, points - $1)
			 WHERE uid=$2 AND mid=$3`,
			o.DiscountPoints, paidBy, o.MID,
		); err != nil {
			return 0, err
		}
	}

	if pointsAwarded > 0 {
		if _, err := tx.Exec(
			`INSERT INTO loyalty_points (uid, mid, points) VALUES ($1, $2, $3)
			 ON CONFLICT (uid, mid) DO UPDATE SET points = loyalty_points.points + EXCLUDED.points`,
			paidBy, o.MID, pointsAwarded,
		); err != nil {
			return 0, err
		}
	}

	return pointsAwarded, tx.Commit()
}

// MarkOrderPaidByRef marks an order paid via the ACS QR callback from Wire server.
func (s *Store) MarkOrderPaidByRef(ref string, paidBy uint32, txnID int64) error {
	o, err := s.GetOrder(ref)
	if err != nil || o == nil {
		return fmt.Errorf("order not found: %s", ref)
	}
	if o.Status != StatusPending {
		return nil // already settled — idempotent
	}

	pointsAwarded := int64(o.Amount / PointsPerVND)

	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	res, err := tx.Exec(
		`UPDATE orders SET status=$1, paid_at=$2, paid_by=$3, points_awarded=$4, wire_txn_id=$5
		 WHERE id=$6 AND status=$7`,
		StatusPaid, time.Now().UnixMilli(), paidBy, pointsAwarded, txnID, ref, StatusPending,
	)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return nil
	}

	if pointsAwarded > 0 {
		if _, err := tx.Exec(
			`INSERT INTO loyalty_points (uid, mid, points) VALUES ($1, $2, $3)
			 ON CONFLICT (uid, mid) DO UPDATE SET points = loyalty_points.points + EXCLUDED.points`,
			paidBy, o.MID, pointsAwarded,
		); err != nil {
			return err
		}
	}

	return tx.Commit()
}

// ─── Loyalty ──────────────────────────────────────────────────────────────────

func (s *Store) GetPoints(mid, uid uint32) (int64, error) {
	row := s.db.QueryRow(`SELECT points FROM loyalty_points WHERE uid=$1 AND mid=$2`, uid, mid)
	var pts int64
	if err := row.Scan(&pts); err == sql.ErrNoRows {
		return 0, nil
	} else if err != nil {
		return 0, err
	}
	return pts, nil
}

// ─── CRM ──────────────────────────────────────────────────────────────────────

const (
	SegmentNew     = "new"
	SegmentRegular = "regular"
	SegmentAtRisk  = "at_risk"
	SegmentChurned = "churned"
	SegmentVIP     = "vip"
)

type CustomerInsight struct {
	UID           uint32 `json:"uid"`
	TotalVisits   int    `json:"total_visits"`
	TotalSpend    uint64 `json:"total_spend"`
	AvgOrder      uint64 `json:"avg_order"`
	FirstVisitMs  int64  `json:"first_visit_ms"`
	LastVisitMs   int64  `json:"last_visit_ms"`
	DaysSinceLast int    `json:"days_since_last"`
	LoyaltyPoints int64  `json:"loyalty_points"`
	Segment       string `json:"segment"`
}

type CRMSummary struct {
	TotalCustomers  int               `json:"total_customers"`
	NewThisMonth    int               `json:"new_this_month"`
	ActiveThisMonth int               `json:"active_this_month"`
	AtRiskCount     int               `json:"at_risk_count"`
	ChurnedCount    int               `json:"churned_count"`
	VIPCount        int               `json:"vip_count"`
	AvgCLV          uint64            `json:"avg_clv"`
	Customers       []CustomerInsight `json:"customers"`
}

func (s *Store) CRMInsights(mid uint32) (*CRMSummary, error) {
	nowMs := time.Now().UnixMilli()
	day30Ms := int64(30 * 24 * 60 * 60 * 1000)
	day14Ms := int64(14 * 24 * 60 * 60 * 1000)
	monthMs := nowMs - day30Ms

	rows, err := s.db.Query(`
		SELECT
			o.paid_by,
			COUNT(*)               AS visits,
			SUM(o.amount)          AS total_spend,
			MIN(o.paid_at)         AS first_visit,
			MAX(o.paid_at)         AS last_visit,
			COALESCE(lp.points, 0) AS loyalty_points
		FROM orders o
		LEFT JOIN loyalty_points lp ON lp.uid = o.paid_by AND lp.mid = o.mid
		WHERE o.mid = $1 AND o.status = 'paid' AND o.paid_by IS NOT NULL
		GROUP BY o.paid_by, lp.points
		ORDER BY total_spend DESC`,
		mid,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var customers []CustomerInsight
	var totalSpend uint64
	for rows.Next() {
		var c CustomerInsight
		if err := rows.Scan(&c.UID, &c.TotalVisits, &c.TotalSpend,
			&c.FirstVisitMs, &c.LastVisitMs, &c.LoyaltyPoints); err != nil {
			return nil, err
		}
		if c.TotalVisits > 0 {
			c.AvgOrder = c.TotalSpend / uint64(c.TotalVisits)
		}
		c.DaysSinceLast = int((nowMs - c.LastVisitMs) / (24 * 60 * 60 * 1000))
		customers = append(customers, c)
		totalSpend += c.TotalSpend
	}

	vipThreshold := len(customers) / 10
	if vipThreshold < 1 {
		vipThreshold = 1
	}

	sum := &CRMSummary{TotalCustomers: len(customers)}
	if len(customers) > 0 {
		sum.AvgCLV = totalSpend / uint64(len(customers))
	}

	for i := range customers {
		c := &customers[i]
		isVIP := i < vipThreshold
		recentVisits := 0
		if c.LastVisitMs >= monthMs {
			recentVisits = c.TotalVisits
		}

		switch {
		case isVIP:
			c.Segment = SegmentVIP
			sum.VIPCount++
		case c.TotalVisits == 1 && c.FirstVisitMs >= monthMs:
			c.Segment = SegmentNew
			sum.NewThisMonth++
		case recentVisits >= 3:
			c.Segment = SegmentRegular
			sum.ActiveThisMonth++
		case c.LastVisitMs >= monthMs-day14Ms:
			c.Segment = SegmentAtRisk
			sum.AtRiskCount++
		default:
			c.Segment = SegmentChurned
			sum.ChurnedCount++
		}
	}

	sum.Customers = customers
	return sum, nil
}

func (s *Store) AwardPoints(mid, uid uint32, points int64) error {
	_, err := s.db.Exec(
		`INSERT INTO loyalty_points (uid, mid, points) VALUES ($1, $2, $3)
		 ON CONFLICT (uid, mid) DO UPDATE SET points = loyalty_points.points + EXCLUDED.points`,
		uid, mid, points,
	)
	return err
}

func (s *Store) ListLoyaltyMembers(mid uint32) ([]LoyaltyEntry, error) {
	rows, err := s.db.Query(
		`SELECT uid, points FROM loyalty_points WHERE mid=$1 ORDER BY points DESC`, mid)
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
	MID     uint32 `json:"mid"`
	Name    string `json:"name"`
	Address string `json:"address,omitempty"`
}

func (s *Store) SearchMerchants(query string) ([]MerchantSearchResult, error) {
	var rows *sql.Rows
	var err error
	if query == "" {
		rows, err = s.db.Query(`SELECT mid, name, address FROM merchants ORDER BY name LIMIT 50`)
	} else {
		pattern := "%" + query + "%"
		rows, err = s.db.Query(`
			SELECT mid, name, address FROM merchants
			WHERE name ILIKE $1 OR address ILIKE $1
			ORDER BY name LIMIT 20`, pattern)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MerchantSearchResult
	for rows.Next() {
		var r MerchantSearchResult
		if err := rows.Scan(&r.MID, &r.Name, &r.Address); err != nil {
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
	CreatedAt    int64  `json:"created_at"`
}

func (s *Store) SendChatMessage(mid, uid uint32, fromMerchant bool, body string) (int64, error) {
	var id int64
	err := s.db.QueryRow(
		`INSERT INTO chat_messages (mid, uid, from_merchant, body, created_at)
		 VALUES ($1, $2, $3, $4, $5) RETURNING id`,
		mid, uid, fromMerchant, body, time.Now().UnixMilli(),
	).Scan(&id)
	return id, err
}

type ChatInboxItem struct {
	UID         uint32 `json:"uid"`
	LastMessage string `json:"last_message"`
	LastAt      int64  `json:"last_at"`
	Unread      int    `json:"unread"`
}

func (s *Store) GetChatInbox(mid uint32) ([]ChatInboxItem, error) {
	rows, err := s.db.Query(`
		SELECT uid,
		       (SELECT body FROM chat_messages m2
		        WHERE m2.mid=cm.mid AND m2.uid=cm.uid
		        ORDER BY created_at DESC LIMIT 1) AS last_message,
		       MAX(created_at) AS last_at,
		       SUM(CASE WHEN from_merchant=false AND created_at > COALESCE(
		               (SELECT MAX(created_at) FROM chat_messages m3
		                WHERE m3.mid=cm.mid AND m3.uid=cm.uid AND m3.from_merchant=true), 0)
		           THEN 1 ELSE 0 END) AS unread
		FROM chat_messages cm
		WHERE mid=$1
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
		 FROM chat_messages WHERE mid=$1 AND uid=$2 AND created_at > $3
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
		if err := rows.Scan(&m.ID, &m.MID, &m.UID, &m.FromMerchant, &m.Body, &m.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, nil
}

func (s *Store) UserLoyalty(uid uint32) ([]UserLoyaltyEntry, error) {
	rows, err := s.db.Query(
		`SELECT lp.mid, m.name, lp.points
		 FROM loyalty_points lp
		 JOIN merchants m ON m.mid = lp.mid
		 WHERE lp.uid=$1 AND lp.points > 0
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

// ─── Menu items ───────────────────────────────────────────────────────────────

func (s *Store) ListMenuItems(mid uint32) ([]MenuItem, error) {
	rows, err := s.db.Query(
		`SELECT id, mid, name, price, description, sort_order FROM menu_items WHERE mid=$1 ORDER BY sort_order, id`, mid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MenuItem
	for rows.Next() {
		var item MenuItem
		if err := rows.Scan(&item.ID, &item.MID, &item.Name, &item.Price, &item.Description, &item.SortOrder); err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, nil
}

func (s *Store) AddMenuItem(mid uint32, name string, price uint64, desc string, sortOrder int) (int64, error) {
	var id int64
	err := s.db.QueryRow(
		`INSERT INTO menu_items (mid, name, price, description, sort_order)
		 VALUES ($1, $2, $3, $4, $5) RETURNING id`,
		mid, name, price, desc, sortOrder,
	).Scan(&id)
	return id, err
}

func (s *Store) DeleteMenuItem(id int64, mid uint32) error {
	res, err := s.db.Exec(`DELETE FROM menu_items WHERE id=$1 AND mid=$2`, id, mid)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return fmt.Errorf("item not found")
	}
	return nil
}

// ─── Slug ─────────────────────────────────────────────────────────────────────

var viMap = strings.NewReplacer(
	"á", "a", "à", "a", "ả", "a", "ã", "a", "ạ", "a",
	"ă", "a", "ắ", "a", "ằ", "a", "ẳ", "a", "ẵ", "a", "ặ", "a",
	"â", "a", "ấ", "a", "ầ", "a", "ẩ", "a", "ẫ", "a", "ậ", "a",
	"đ", "d",
	"é", "e", "è", "e", "ẻ", "e", "ẽ", "e", "ẹ", "e",
	"ê", "e", "ế", "e", "ề", "e", "ể", "e", "ễ", "e", "ệ", "e",
	"í", "i", "ì", "i", "ỉ", "i", "ĩ", "i", "ị", "i",
	"ó", "o", "ò", "o", "ỏ", "o", "õ", "o", "ọ", "o",
	"ô", "o", "ố", "o", "ồ", "o", "ổ", "o", "ỗ", "o", "ộ", "o",
	"ơ", "o", "ớ", "o", "ờ", "o", "ở", "o", "ỡ", "o", "ợ", "o",
	"ú", "u", "ù", "u", "ủ", "u", "ũ", "u", "ụ", "u",
	"ư", "u", "ứ", "u", "ừ", "u", "ử", "u", "ữ", "u", "ự", "u",
	"ý", "y", "ỳ", "y", "ỷ", "y", "ỹ", "y", "ỵ", "y",
	"Á", "a", "À", "a", "Ả", "a", "Ã", "a", "Ạ", "a",
	"Ă", "a", "Ắ", "a", "Ằ", "a", "Ẳ", "a", "Ẵ", "a", "Ặ", "a",
	"Â", "a", "Ấ", "a", "Ầ", "a", "Ẩ", "a", "Ẫ", "a", "Ậ", "a",
	"Đ", "d",
	"É", "e", "È", "e", "Ẻ", "e", "Ẽ", "e", "Ẹ", "e",
	"Ê", "e", "Ế", "e", "Ề", "e", "Ể", "e", "Ễ", "e", "Ệ", "e",
	"Í", "i", "Ì", "i", "Ỉ", "i", "Ĩ", "i", "Ị", "i",
	"Ó", "o", "Ò", "o", "Ỏ", "o", "Õ", "o", "Ọ", "o",
	"Ô", "o", "Ố", "o", "Ồ", "o", "Ổ", "o", "Ỗ", "o", "Ộ", "o",
	"Ơ", "o", "Ớ", "o", "Ờ", "o", "Ở", "o", "Ỡ", "o", "Ợ", "o",
	"Ú", "u", "Ù", "u", "Ủ", "u", "Ũ", "u", "Ụ", "u",
	"Ư", "u", "Ứ", "u", "Ừ", "u", "Ử", "u", "Ữ", "u", "Ự", "u",
	"Ý", "y", "Ỳ", "y", "Ỷ", "y", "Ỹ", "y", "Ỵ", "y",
)

func slugify(name string) string {
	s := strings.ToLower(viMap.Replace(name))
	var b strings.Builder
	prevHyphen := true
	for _, r := range s {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
			prevHyphen = false
		} else if !prevHyphen && !unicode.IsControl(r) {
			b.WriteByte('-')
			prevHyphen = true
		}
	}
	result := strings.TrimRight(b.String(), "-")
	if result == "" {
		return "merchant"
	}
	return result
}

// BackfillSlugs assigns slugs to all merchants that currently have an empty slug.
func (s *Store) BackfillSlugs() (int, error) {
	rows, err := s.db.Query(`SELECT mid, name FROM merchants WHERE slug = '' OR slug IS NULL`)
	if err != nil {
		return 0, err
	}
	defer rows.Close()
	type row struct {
		mid  uint32
		name string
	}
	var targets []row
	for rows.Next() {
		var r row
		if err := rows.Scan(&r.mid, &r.name); err != nil {
			return 0, err
		}
		targets = append(targets, r)
	}
	count := 0
	for _, t := range targets {
		slug, err := s.GenerateSlug(t.mid, t.name)
		if err != nil {
			continue
		}
		if err := s.SetSlug(t.mid, slug); err == nil {
			count++
		}
	}
	return count, nil
}

// GenerateSlug creates a unique slug for the merchant, appending the MID if the base slug is taken.
func (s *Store) GenerateSlug(mid uint32, name string) (string, error) {
	base := slugify(name)
	exists, err := s.SlugExists(base)
	if err != nil {
		return "", err
	}
	if !exists {
		return base, nil
	}
	return fmt.Sprintf("%s-%d", base, mid), nil
}

// ─── Control Plane / Ops ─────────────────────────────────────────────────────

type OpsOverview struct {
	TotalMerchants  int    `json:"total_merchants"`
	ActiveMerchants int    `json:"active_merchants"`
	TotalOrders     int    `json:"total_orders"`
	TotalVolume     uint64 `json:"total_volume"`
	TodayOrders     int    `json:"today_orders"`
	TodayVolume     uint64 `json:"today_volume"`
}

type MerchantOpsRow struct {
	MID         uint32 `json:"mid"`
	Name        string `json:"name"`
	Slug        string `json:"slug"`
	Status      string `json:"status"`
	OrderCount  int    `json:"order_count"`
	TotalVolume uint64 `json:"total_volume"`
	CreatedAt   int64  `json:"created_at"`
}

type SettlementRow struct {
	MID        uint32 `json:"mid"`
	Name       string `json:"name"`
	OrderCount int    `json:"order_count"`
	Volume     uint64 `json:"volume"`
	AvgOrder   uint64 `json:"avg_order"`
}

func (s *Store) SetMerchantStatus(mid uint32, status string) error {
	_, err := s.db.Exec(`UPDATE merchants SET status=$1 WHERE mid=$2`, status, mid)
	return err
}

func (s *Store) OpsOverview() (*OpsOverview, error) {
	ov := &OpsOverview{}

	err := s.db.QueryRow(`
		SELECT COUNT(*),
		       SUM(CASE WHEN status='active' THEN 1 ELSE 0 END)
		FROM merchants`).Scan(&ov.TotalMerchants, &ov.ActiveMerchants)
	if err != nil {
		return nil, err
	}

	err = s.db.QueryRow(`
		SELECT COUNT(*), COALESCE(SUM(CASE WHEN status='paid' THEN amount ELSE 0 END),0)
		FROM orders`).Scan(&ov.TotalOrders, &ov.TotalVolume)
	if err != nil {
		return nil, err
	}

	now := time.Now()
	todayStart := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location()).UnixMilli()
	err = s.db.QueryRow(`
		SELECT COUNT(*), COALESCE(SUM(amount),0)
		FROM orders WHERE status='paid' AND paid_at >= $1`, todayStart).
		Scan(&ov.TodayOrders, &ov.TodayVolume)
	if err != nil {
		return nil, err
	}

	return ov, nil
}

func (s *Store) ListAllMerchantsOps() ([]MerchantOpsRow, error) {
	rows, err := s.db.Query(`
		SELECT m.mid, m.name, m.slug, m.status, m.created_at,
		       COUNT(o.id) AS order_count,
		       COALESCE(SUM(CASE WHEN o.status='paid' THEN o.amount ELSE 0 END),0) AS total_volume
		FROM merchants m
		LEFT JOIN orders o ON o.mid = m.mid
		GROUP BY m.mid, m.name, m.slug, m.status, m.created_at
		ORDER BY total_volume DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MerchantOpsRow
	for rows.Next() {
		var r MerchantOpsRow
		if err := rows.Scan(&r.MID, &r.Name, &r.Slug, &r.Status, &r.CreatedAt,
			&r.OrderCount, &r.TotalVolume); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, nil
}

func (s *Store) SettlementReport(fromMs, toMs int64) ([]SettlementRow, error) {
	rows, err := s.db.Query(`
		SELECT m.mid, m.name,
		       COUNT(o.id) AS order_count,
		       COALESCE(SUM(o.amount),0) AS volume
		FROM orders o
		JOIN merchants m ON m.mid = o.mid
		WHERE o.status='paid' AND o.paid_at >= $1 AND o.paid_at < $2
		GROUP BY m.mid, m.name
		ORDER BY volume DESC`, fromMs, toMs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []SettlementRow
	for rows.Next() {
		var r SettlementRow
		if err := rows.Scan(&r.MID, &r.Name, &r.OrderCount, &r.Volume); err != nil {
			return nil, err
		}
		if r.OrderCount > 0 {
			r.AvgOrder = r.Volume / uint64(r.OrderCount)
		}
		out = append(out, r)
	}
	return out, nil
}
