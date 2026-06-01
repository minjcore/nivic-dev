package dev.nivic.coa;

import java.util.UUID;

/**
 * Platform-level double-entry ledger following the GtelPay Chart of Accounts.
 *
 * <p>Every operation posts a balanced journal to {@code coa_trans} / {@code coa_trans_data}
 * and atomically updates {@code coa_account.balance_minor}.
 * Transit accounts (3xxx) must return to zero after each complete flow.</p>
 *
 * <p>Balance convention: {@code balance_minor = Σ debit − Σ credit}.
 * Positive = debit side heavier (natural for ASSET/EXPENSE).
 * Negative = credit side heavier (natural for LIABILITY/REVENUE/EQUITY).</p>
 */
public interface FundFlowLedger {

  /**
   * Step 1 — NH nhận tiền từ user.
   * Posts: DR 1111 / CR 3100.
   * Idempotent on {@link TopUpReceiveCmd#bankRef()}.
   */
  CoaTrans receiveTopUp(TopUpReceiveCmd cmd);

  /**
   * Step 2 — Xác nhận nạp tiền thành công.
   * Posts: DR 3100 / CR 2110 (amount − fee) / CR 4110 (fee).
   * Idempotent on {@link TopUpConfirmCmd#confirmRef()}.
   *
   * @throws InsufficientTransitException if Transit 3100 balance would go below zero
   */
  CoaTrans confirmTopUp(TopUpConfirmCmd cmd);

  /** Current balance of one account (debit − credit). */
  long getBalance(String accountCode);

  /** Full journal transaction with bút toán lines. Returns null if not found. */
  CoaTrans findTrans(UUID transId);

  /**
   * Platform double-entry sanity check: sum of all debits across all transactions
   * must equal sum of all credits. Always true if posting is correct.
   */
  boolean isDoubleEntryBalanced();
}
