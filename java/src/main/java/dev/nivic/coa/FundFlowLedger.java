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

  /** Lookup by idempotency / external ref ({@code coa_trans.ref_id}). Returns null if not found. */
  CoaTrans findTransByRefId(String refId);

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
   * Step 1 — VPBank nhận tiền qua QR/POS + ghi chi phí VPBank.
   * Posts (4 legs): DR 1113 (amount) / CR 3500 (amount) / DR 5100 (vpbankCost) / CR 1113 (vpbankCost).
   * Net 1113 = amount − vpbankCost. Idempotent on {@link QrPosReceiveCmd#requestRef()}.
   */
  CoaTrans receiveQrPos(QrPosReceiveCmd cmd);

  /**
   * Step 2 — Giải phóng transit, ghi ví merchant (chờ Settlement EOD).
   * Posts: DR 3500 (amount) / CR 2120 (amount).
   * Idempotent on {@link QrPosCreditMerchantCmd#settleRef()}.
   *
   * @throws InsufficientTransitException if Transit 3500 balance would go below zero
   */
  CoaTrans creditMerchantQrPos(QrPosCreditMerchantCmd cmd);

  // ── Chi Lương — Payroll Disbursement (Use Case 9) ────────────────────────────

  /**
   * Step 1 — Lock ví merchant doanh nghiệp vào transit chi lương.
   * Posts: DR 2120 (amount + totalFee) / CR 3600 (Transit Chi Lương).
   * Idempotent on {@link PayrollInitCmd#requestRef()}.
   *
   * @throws InsufficientWalletException if Wallet Merchant (2120) balance is insufficient
   */
  CoaTrans initPayroll(PayrollInitCmd cmd);

  /**
   * Step 2 — Bulk IBFT đến TK NH nhân viên, giải phóng transit, ghi chi phí Napas.
   * Posts (4 legs):
   * DR 3600 (amount+totalFee) / DR 5100 (napasCost) / CR 4150 (totalFee) / CR 1112 (amount+napasCost).
   * Idempotent on {@link PayrollDisburseCmd#disburseRef()}.
   *
   * @throws InsufficientTransitException if Transit 3600 balance would go below zero
   */
  CoaTrans disbursePayroll(PayrollDisburseCmd cmd);

  // ── Settlement & Clearing EOD (Use Case 11) ──────────────────────────────────

  /**
   * Step 1 — Lock toàn bộ số dư ví merchant vào transit clearing.
   * Posts: DR 2120 (totalAmount) / CR 3800 (totalAmount).
   *
   * @throws InsufficientWalletException using account 2120 if merchant balance insufficient
   */
  CoaTrans eodInitClearing(EodClearingInitCmd cmd);

  /**
   * Step 2 — Đối soát: tách MDR, chuyển phần net sang transit settlement.
   * Posts: DR 3800 total / CR 3820 mdr / CR 3810 net.
   *
   * @throws InsufficientTransitException if 3800 Transit Clearing has insufficient balance
   */
  CoaTrans eodReconcile(EodReconcileCmd cmd);

  /**
   * Step 3 — Xác nhận đối soát thành công, ghi nhận doanh thu MDR.
   * Posts: DR 3820 (mdr) / CR 4140 (mdr).
   *
   * @throws InsufficientTransitException if 3820 Transit MDR Holdback has insufficient balance
   */
  CoaTrans eodRecognizeMdr(EodRecognizeMdrCmd cmd);

  /**
   * Step 4 — Settlement Outbound qua Napas, ghi chi phí Napas.
   * Posts: DR 3810 (net) / DR 5100 (napasCost) / CR 1112 (net + napasCost).
   *
   * @throws InsufficientTransitException if 3810 Transit Settlement has insufficient balance
   */
  CoaTrans eodSettleOutbound(EodSettleOutboundCmd cmd);

  /**
   * Step 5 (Exception) — Đối soát không khớp: hoàn toàn bộ tiền về ví merchant.
   * Áp dụng sau step 2, trước step 3+4.
   * Posts: DR 3810 (net) / DR 3820 (mdr) / CR 2120 (total).
   */
  CoaTrans eodRejectSettlement(EodRejectSettlementCmd cmd);

  /**
   * Platform double-entry sanity check: sum of all debits across all transactions
   * must equal sum of all credits. Always true if posting is correct.
   */
  boolean isDoubleEntryBalanced();
}
