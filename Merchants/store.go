package main

import (
	"database/sql"
	"fmt"
	"strings"
	"time"
	"unicode"

	_ "modernc.org/sqlite"
)

// ─── Domain types ────────────────────────────────────────────────────────────

type Merchant struct {
	MID          uint32 `json:"mid"`
	Name         string `json:"name"`
	Address      string `json:"address,omitempty"`
	Website      string `json:"website,omitempty"`
	Slug         string `json:"slug,omitempty"`
	CustomDomain string `json:"custom_domain,omitempty"`
	PubkeyB64    string `json:"pubkey_b64"`
	PrivkeyB64   string `json:"-"`
	Token        string `json:"-"`
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
		CREATE TABLE IF NOT EXISTS menu_items (
			id          INTEGER PRIMARY KEY AUTOINCREMENT,
			mid         INTEGER NOT NULL REFERENCES merchants(mid),
			name        TEXT    NOT NULL,
			price       INTEGER NOT NULL DEFAULT 0,
			description TEXT    NOT NULL DEFAULT '',
			sort_order  INTEGER NOT NULL DEFAULT 0
		);
		CREATE INDEX IF NOT EXISTS idx_menu_items_mid ON menu_items(mid, sort_order);
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
	db.Exec(`ALTER TABLE merchants ADD COLUMN address     TEXT NOT NULL DEFAULT ''`)
	db.Exec(`ALTER TABLE merchants ADD COLUMN slug          TEXT NOT NULL DEFAULT ''`)
	db.Exec(`ALTER TABLE merchants ADD COLUMN website       TEXT NOT NULL DEFAULT ''`)
	db.Exec(`ALTER TABLE merchants ADD COLUMN custom_domain TEXT NOT NULL DEFAULT ''`)
	db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_merchants_slug   ON merchants(slug)          WHERE slug != ''`)
	db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_merchants_domain ON merchants(custom_domain) WHERE custom_domain != ''`)
	db.Exec(`ALTER TABLE orders ADD COLUMN discount_points INTEGER NOT NULL DEFAULT 0`)
	db.Exec(`ALTER TABLE orders ADD COLUMN points_awarded  INTEGER NOT NULL DEFAULT 0`)

	// FTS5: name (weight 10) + address (weight 1), unicode61 handles Vietnamese
	db.Exec(`DROP TABLE IF EXISTS merchants_fts`)
	db.Exec(`DROP TRIGGER IF EXISTS merchants_ai`)
	db.Exec(`DROP TRIGGER IF EXISTS merchants_ad`)
	db.Exec(`DROP TRIGGER IF EXISTS merchants_au`)
	db.Exec(`CREATE VIRTUAL TABLE IF NOT EXISTS merchants_fts USING fts5(
		name, address, content='merchants', content_rowid='mid', tokenize='unicode61'
	)`)
	db.Exec(`CREATE TRIGGER IF NOT EXISTS merchants_ai AFTER INSERT ON merchants BEGIN
		INSERT INTO merchants_fts(rowid, name, address) VALUES (new.mid, new.name, new.address);
	END`)
	db.Exec(`CREATE TRIGGER IF NOT EXISTS merchants_ad AFTER DELETE ON merchants BEGIN
		INSERT INTO merchants_fts(merchants_fts, rowid, name, address) VALUES ('delete', old.mid, old.name, old.address);
	END`)
	db.Exec(`CREATE TRIGGER IF NOT EXISTS merchants_au AFTER UPDATE ON merchants BEGIN
		INSERT INTO merchants_fts(merchants_fts, rowid, name, address) VALUES ('delete', old.mid, old.name, old.address);
		INSERT INTO merchants_fts(rowid, name, address) VALUES (new.mid, new.name, new.address);
	END`)
	db.Exec(`INSERT INTO merchants_fts(merchants_fts) VALUES('rebuild')`)

	return err
}

func (s *Store) Close() { s.db.Close() }

// ─── Merchants ────────────────────────────────────────────────────────────────

func (s *Store) Register(mid uint32, name, address, website, pubkeyB64, privkeyB64, token string) error {
	_, err := s.db.Exec(
		`INSERT OR REPLACE INTO merchants (mid, name, address, website, pubkey_b64, privkey_b64, token, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		mid, name, address, website, pubkeyB64, privkeyB64, token, time.Now().Unix(),
	)
	return err
}

const merchantCols = `mid, name, address, website, slug, custom_domain, pubkey_b64, privkey_b64, token, created_at`

func scanMerchant(row *sql.Row) (*Merchant, error) {
	var m Merchant
	if err := row.Scan(&m.MID, &m.Name, &m.Address, &m.Website, &m.Slug, &m.CustomDomain,
		&m.PubkeyB64, &m.PrivkeyB64, &m.Token, &m.CreatedAt); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return &m, nil
}

func (s *Store) Get(mid uint32) (*Merchant, error) {
	return scanMerchant(s.db.QueryRow(`SELECT `+merchantCols+` FROM merchants WHERE mid = ?`, mid))
}

func (s *Store) GetBySlug(slug string) (*Merchant, error) {
	return scanMerchant(s.db.QueryRow(`SELECT `+merchantCols+` FROM merchants WHERE slug = ?`, slug))
}

func (s *Store) GetByDomain(domain string) (*Merchant, error) {
	return scanMerchant(s.db.QueryRow(`SELECT `+merchantCols+` FROM merchants WHERE custom_domain = ?`, domain))
}

func (s *Store) DomainExists(domain string) (bool, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM merchants WHERE custom_domain=?`, domain).Scan(&n)
	return n > 0, err
}

func (s *Store) SetCustomDomain(mid uint32, domain string) error {
	_, err := s.db.Exec(`UPDATE merchants SET custom_domain=? WHERE mid=?`, domain, mid)
	return err
}

func (s *Store) UpdateProfile(mid uint32, name, address, website string) error {
	_, err := s.db.Exec(
		`UPDATE merchants SET name=?, address=?, website=? WHERE mid=?`,
		name, address, website, mid,
	)
	return err
}

func (s *Store) SetSlug(mid uint32, slug string) error {
	_, err := s.db.Exec(`UPDATE merchants SET slug=? WHERE mid=?`, slug, mid)
	return err
}

func (s *Store) SlugExists(slug string) (bool, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM merchants WHERE slug=?`, slug).Scan(&n)
	return n > 0, err
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
		// FTS5 prefix search, name weighted 10x over address via bm25 column weights
		ftsQuery := query + "*"
		rows, err = s.db.Query(`
			SELECT m.mid, m.name, m.address
			FROM merchants_fts f
			JOIN merchants m ON m.mid = f.rowid
			WHERE merchants_fts MATCH ?
			ORDER BY bm25(merchants_fts, 10.0, 1.0)
			LIMIT 20`, ftsQuery)
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

// ─── Menu items ───────────────────────────────────────────────────────────────

func (s *Store) ListMenuItems(mid uint32) ([]MenuItem, error) {
	rows, err := s.db.Query(
		`SELECT id, mid, name, price, description, sort_order FROM menu_items WHERE mid=? ORDER BY sort_order, id`, mid)
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
	res, err := s.db.Exec(
		`INSERT INTO menu_items (mid, name, price, description, sort_order) VALUES (?, ?, ?, ?, ?)`,
		mid, name, price, desc, sortOrder,
	)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

func (s *Store) DeleteMenuItem(id int64, mid uint32) error {
	res, err := s.db.Exec(`DELETE FROM menu_items WHERE id=? AND mid=?`, id, mid)
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
	prevHyphen := true // start true to trim leading hyphens
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
	// Append MID to make unique
	candidate := fmt.Sprintf("%s-%d", base, mid)
	return candidate, nil
}
