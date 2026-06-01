package dev.nivic.saving;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TigerBeetle-style savings ledger: Accounts + immutable Transfers.
 *
 * <p>All balance updates are atomic via {@code SELECT FOR UPDATE} + {@code UPDATE} in a single
 * Postgres transaction. Transfer rows are never modified after INSERT.</p>
 */
public interface SavingLedger {

  /** Creates a new savings account (DEMAND or TERM). */
  SavAccount openAccount(OpenAccountCmd cmd);

  /**
   * Deposits funds into a savings account (always POSTED, one-phase).
   * Idempotent when {@link DepositCmd#idempotencyKey()} is provided.
   *
   * @throws AccountClosedException if account has {@link SavAccountFlags#CLOSED}
   * @throws AccountFrozenException if account has {@link SavAccountFlags#FROZEN}
   */
  SavTransfer deposit(DepositCmd cmd);

  /**
   * Withdraws funds from a savings account.
   *
   * <p>When {@link WithdrawalCmd#pending()} is {@code true}, creates a PENDING transfer that
   * reserves {@code debits_pending} — call {@link #postPending} (after OTP/PIN confirm) or
   * {@link #voidPending} (if cancelled). When {@code false}, posts immediately.</p>
   *
   * @throws AccountClosedException  if account has {@link SavAccountFlags#CLOSED}
   * @throws AccountFrozenException  if account has {@link SavAccountFlags#FROZEN}
   * @throws TermLockedException     if account has {@link SavAccountFlags#TERM_LOCKED} (before maturity)
   * @throws InsufficientFundsException if available balance is less than requested
   */
  SavTransfer withdrawal(WithdrawalCmd cmd);

  /**
   * Settles a PENDING withdrawal: {@code debits_pending -= amount}, {@code debits_posted += amount}.
   *
   * @throws SavTransferPhaseException if transfer is not PENDING or already settled
   */
  SavTransfer postPending(UUID pendingTransferId);

  /**
   * Cancels a PENDING withdrawal: {@code debits_pending -= amount}, no net debit.
   *
   * @throws SavTransferPhaseException if transfer is not PENDING or already settled
   */
  SavTransfer voidPending(UUID pendingTransferId);

  /**
   * Batch interest accrual: credits each savings account from the system reserve account.
   * Each {@link AccrueInterestCmd} runs in its own transaction; partial failure does not roll back
   * already-committed entries. Caller pre-computes the interest amount per account.
   */
  List<SavTransfer> accrueInterest(List<AccrueInterestCmd> cmds);

  /**
   * Closes an account (sets {@link SavAccountFlags#CLOSED}). Account balance must be zero.
   * Caller is responsible for withdrawing remaining funds before calling this.
   *
   * @throws IllegalStateException if balance is non-zero or account does not belong to ownerMid
   */
  SavAccount closeAccount(UUID accountId, long ownerMid);

  /**
   * Paginated statement: transfers where the account is debit or credit, ordered by
   * {@code created_at DESC}. Use {@code before = Instant.now()} for the first page;
   * use the last entry's {@code createdAt} as the cursor for subsequent pages.
   */
  List<SavTransfer> statement(UUID accountId, Instant before, int limit);

  /** Returns the current account snapshot, or {@code null} if not found. */
  SavAccount findAccount(UUID id);

  /**
   * Returns the full bút toán view for one transfer: header + enriched lines with
   * {@code account_no} and {@code account_kind} from {@code sav_account}.
   * Returns {@code null} if not found.
   */
  SavTransView findTransfer(UUID transId);
}
