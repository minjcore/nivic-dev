package repository

import (
	"context"
	"database/sql"
	"fmt"
	"strconv"
)

// MerchantRepository persists Mc (tenant) data on Postgres with RLS (tenant_bills).
// Requires migration 001_tenant_bills.up.sql and session var app.current_tenant_id.
type MerchantRepository struct {
	db *sql.DB
}

func NewMerchantRepository(db *sql.DB) *MerchantRepository {
	return &MerchantRepository{db: db}
}

func (r *MerchantRepository) setTenantContext(ctx context.Context, tx *sql.Tx, tenantID uint32) error {
	_, err := tx.ExecContext(ctx,
		`SELECT set_config('app.current_tenant_id', $1, true)`,
		strconv.FormatUint(uint64(tenantID), 10),
	)
	if err != nil {
		return fmt.Errorf("set tenant context: %w", err)
	}
	return nil
}

// UpdateBillToPaid marks a bill PAID after queryOrderStatus succeeded on CORE.
// RLS restricts rows to the tenant set in the transaction session.
func (r *MerchantRepository) UpdateBillToPaid(ctx context.Context, tenantID uint32, billNumber uint64, paidByUID uint32) error {
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if err := r.setTenantContext(ctx, tx, tenantID); err != nil {
		return err
	}

	result, err := tx.ExecContext(ctx,
		`UPDATE tenant_bills
		    SET status = 'PAID', paid_at = now(), paid_by_uid = $2
		  WHERE bill_number = $1 AND status = 'PENDING'`,
		billNumber, paidByUID,
	)
	if err != nil {
		return fmt.Errorf("update bill status: %w", err)
	}

	rowsAffected, _ := result.RowsAffected()
	if rowsAffected == 0 {
		return fmt.Errorf("bill not found, not pending, or tenant isolation blocked update")
	}

	return tx.Commit()
}
