package main

import (
	"database/sql"
	"fmt"
	"time"

	_ "github.com/lib/pq"
)

type Store struct {
	db *sql.DB
}

// OpenStore connects to the shared Merchants Postgres DB (read-mostly; does not migrate).
func OpenStore(dsn string) (*Store, error) {
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, fmt.Errorf("open db: %w", err)
	}
	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("ping db: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() { s.db.Close() }

// ─── Types ────────────────────────────────────────────────────────────────────

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

// ─── Queries ──────────────────────────────────────────────────────────────────

func (s *Store) Overview() (*OpsOverview, error) {
	ov := &OpsOverview{}

	err := s.db.QueryRow(`
		SELECT COUNT(*), SUM(CASE WHEN status='active' THEN 1 ELSE 0 END)
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

func (s *Store) ListMerchants() ([]MerchantOpsRow, error) {
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

func (s *Store) Settlement(fromMs, toMs int64) ([]SettlementRow, error) {
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

func (s *Store) SetMerchantStatus(mid uint32, status string) error {
	_, err := s.db.Exec(`UPDATE merchants SET status=$1 WHERE mid=$2`, status, mid)
	return err
}
