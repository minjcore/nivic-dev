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
   * Step 1 — User khởi tạo rút tiền.
   * Posts: DR 2110 (amount + fee) / CR 3200 (Transit Rút, amount + fee).
   * Idempotent on {@link WithdrawInitCmd#requestRef()}.
   *
   * @throws InsufficientWalletException if Wallet User (2110) balance is insufficient
   */
  CoaTrans initWithdraw(WithdrawInitCmd cmd);

  /**
   * Step 2 — NH chuyển tiền, giải phóng transit.
   * Posts: DR 3200 (amount + fee) / CR 1111 (amount) / CR 4120 (fee).
   * Idempotent on {@link WithdrawSettleCmd#settleRef()}.
   *
   * @throws InsufficientTransitException if Transit 3200 balance would go below zero
   */
  CoaTrans settleWithdraw(WithdrawSettleCmd cmd);

  /**
   * Step 1 — Trừ ví người gửi, ghi transit nội bộ.
   * Posts: DR 2110 (amount + fee) / CR 3300 (Transit Nội bộ, amount + fee).
   * Idempotent on {@link InternalTransferInitCmd#requestRef()}.
   *
   * @throws InsufficientWalletException if Wallet User (2110) balance is insufficient
   */
  CoaTrans initInternalTransfer(InternalTransferInitCmd cmd);

  /**
   * Step 2 — Cộng ví người nhận, giải phóng transit nội bộ.
   * Posts: DR 3300 (amount + fee) / CR 2110 (amount) / CR 4130 (fee).
   * Không phát sinh tài khoản NH. Idempotent on {@link InternalTransferSettleCmd#settleRef()}.
   *
   * @throws InsufficientTransitException if Transit 3300 balance would go below zero
   */
  CoaTrans settleInternalTransfer(InternalTransferSettleCmd cmd);

  /**
   * Step 1 — Trừ ví user, ghi transit IBFT.
   * Posts: DR 2110 (amount + fee) / CR 3400 (Transit IBFT).
   * Idempotent on {@link IbftInitCmd#requestRef()}.
   *
   * @throws InsufficientWalletException if Wallet User (2110) balance is insufficient
   */
  CoaTrans initIbftTransfer(IbftInitCmd cmd);

  /**
   * Step 2 — Napas thực hiện, giải phóng transit, ghi chi phí.
   * Posts một entry 4 legs:
   * DR 3400 (amount+fee) / DR 5100 (napasCost) / CR 1112 (amount+napasCost) / CR 4130 (fee).
   * Idempotent on {@link IbftSettleCmd#settleRef()}.
   *
   * @throws InsufficientTransitException if Transit 3400 balance would go below zero
   */
  CoaTrans settleIbftTransfer(IbftSettleCmd cmd);

  /**
   * Platform double-entry sanity check: sum of all debits across all transactions
   * must equal sum of all credits. Always true if posting is correct.
   */
  boolean isDoubleEntryBalanced();
}
