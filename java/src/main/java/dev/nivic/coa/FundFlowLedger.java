package dev.nivic.coa;

import dev.nivic.coa.cmd.*;
import dev.nivic.coa.error.*;

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

  /**
   * Số dư ví cá nhân của một user — sổ chi tiết (subsidiary ledger) của control account 2110.
   * Bằng Σ(credit − debit) trên các dòng bút toán 2110 mang {@code party_mid = mid}.
   * Bất biến: Σ {@code walletBalance(mọi mid)} = natural liability của 2110.
   */
  long walletBalance(long mid);

  /** Số dư tiền gửi tiết kiệm của một user — sổ chi tiết của control account 2140. */
  long savingsBalance(long mid);

  /**
   * Phản chiếu (mirror) một chuyển khoản ví vận hành (Sevlet wallet) vào sổ cái COA:
   * DR 2110(party=payerAcct) / CR 2110(party=payeeAcct). Tiền dịch chuyển giữa hai ví trong
   * sổ chi tiết của control account 2110; tổng 2110 không đổi (chuyển nội bộ).
   * Idempotent on {@code ref} (thường = "WAL:" + mid + ":" + requestId).
   */
  CoaTrans mirrorWalletTransfer(long payerAcct, long payeeAcct, long amount, String ref, String memo);

  /**
   * Đổi tiền VND ↔ USD (multi-currency). Post một bút toán CÂN THEO TỪNG CURRENCY,
   * bắc cầu qua tài khoản vị thế FX (1920 VND / 1921 USD).
   * Idempotent on {@link dev.nivic.coa.cmd.FxExchangeCmd#requestRef()}.
   */
  CoaTrans fxExchange(dev.nivic.coa.cmd.FxExchangeCmd cmd);

  /**
   * Đánh giá lại vị thế FX theo tỷ giá hiện tại → ghi nhận lãi/lỗ chênh lệch tỷ giá (mark-to-market
   * vị thế VND 1920). DR 1920 / CR 4170 (lãi) hoặc DR 5300 / CR 1920 (lỗ).
   *
   * @throws dev.nivic.coa.error.NothingToRevalueException nếu không có vị thế FX mở hoặc tỷ giá không đổi
   */
  CoaTrans fxRevalue(dev.nivic.coa.cmd.FxRevalueCmd cmd);

  // ── Savings ví ↔ Sổ cái (control account 2140 + chi phí lãi 5200) ─────────────

  /**
   * Chuyển ví → tiết kiệm: DR 2110(mid) / CR 2140(mid). Tái phân loại nợ phải trả.
   * @throws InsufficientWalletException nếu số dư ví user &lt; amount
   */
  CoaTrans savingsDeposit(SavingsDepositCmd cmd);

  /**
   * Rút tiết kiệm → ví: DR 2140(mid) / CR 2110(mid).
   * @throws InsufficientWalletException nếu số dư tiết kiệm user &lt; amount
   */
  CoaTrans savingsWithdraw(SavingsWithdrawCmd cmd);

  /**
   * Ghi lãi tiền gửi (chi phí nền tảng): DR 5200 / CR 2140(mid).
   */
  CoaTrans savingsInterest(SavingsInterestCmd cmd);

  /** Full journal transaction with bút toán lines. Returns null if not found. */
  CoaTrans findTrans(long transId);

  /** Lookup by idempotency / external ref ({@code coa_trans.ref_id}). Returns null if not found. */
  CoaTrans findTransByRefId(String refId);

  /**
   * Crypto deposit from blockchain received.
   * Step 1: Posts DR 1100 (Crypto Received) / CR 3500 (Transit - crypto receive).
   * Idempotent on {@link dev.nivic.coa.cmd.CryptoDepositCmd#refId()}.
   */
  CoaTrans postCryptoDeposit(dev.nivic.coa.cmd.CryptoDepositCmd cmd);

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

  // ── Thanh Toán Bằng Ví (Use Case 8) ──────────────────────────────────────────

  /**
   * Step 1 — Trừ số dư ví user, ghi transit thanh toán.
   * Posts: DR 2110 (amount) / CR 3500 (Transit Thanh toán).
   * Idempotent on {@link WalletPaymentInitCmd#requestRef()}.
   *
   * @throws InsufficientWalletException if Wallet User (2110) balance is insufficient
   */
  CoaTrans initWalletPayment(WalletPaymentInitCmd cmd);

  /**
   * Step 2 — Giải phóng transit, cộng ví merchant (chờ Settlement EOD).
   * Posts: DR 3500 (amount) / CR 2120 (Wallet Merchant).
   * Idempotent on {@link WalletPaymentSettleCmd#settleRef()}.
   *
   * @throws InsufficientTransitException if Transit 3500 balance would go below zero
   */
  CoaTrans settleWalletPayment(WalletPaymentSettleCmd cmd);

  // ── Chi Hộ — Disbursement on Behalf (Use Case 10) ────────────────────────────

  /**
   * Bước 0 — Đối tác pre-fund ký quỹ.
   * Posts: DR 1111 (amount) / CR 2130 (amount).
   * Idempotent on {@link DisbursementPrefundCmd#prefundRef()}.
   */
  CoaTrans prefundDisbursement(DisbursementPrefundCmd cmd);

  /**
   * Bước 1a — Trừ ký quỹ đối tác, ghi transit chi hộ.
   * Posts: DR 2130 (amount + fee) / CR 3700 (Transit Chi hộ).
   * Idempotent on {@link DisbursementInitCmd#requestRef()}.
   *
   * @throws InsufficientEscrowException if Ký quỹ đối tác (2130) balance is insufficient
   */
  CoaTrans initDisbursement(DisbursementInitCmd cmd);

  /**
   * Bước 1b — Napas gửi bên thụ hưởng, giải phóng transit, ghi chi phí.
   * Posts (4 legs):
   * DR 3700 (amount+fee) / DR 5100 (napasCost) / CR 4150 (fee) / CR 1112 (amount+napasCost).
   * Idempotent on {@link DisbursementSettleCmd#settleRef()}.
   *
   * @throws InsufficientTransitException if Transit 3700 balance would go below zero
   */
  CoaTrans settleDisbursement(DisbursementSettleCmd cmd);

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

  // ── Maker-checker (4-eyes / segregation of duties) ────────────────────────────

  /**
   * Maker đề xuất một bút toán cân — lưu PENDING, CHƯA ảnh hưởng số dư.
   * Idempotent on {@link dev.nivic.coa.mc.ProposeJournalCmd#requestRef()}.
   *
   * @throws IllegalArgumentException nếu bút toán không cân
   */
  dev.nivic.coa.mc.Proposal propose(dev.nivic.coa.mc.ProposeJournalCmd cmd);

  /**
   * Checker duyệt — post bút toán vào sổ cái (atomic) và đánh dấu APPROVED.
   *
   * @throws dev.nivic.coa.error.ProposalNotFoundException nếu không tồn tại
   * @throws dev.nivic.coa.error.ProposalStateException nếu đã quyết định
   * @throws dev.nivic.coa.error.SegregationOfDutiesException nếu checker trùng maker
   */
  CoaTrans approve(long proposalId, String checkerId);

  /**
   * Checker từ chối — đánh dấu REJECTED, không post.
   *
   * @throws dev.nivic.coa.error.ProposalNotFoundException / ProposalStateException
   *     / SegregationOfDutiesException tương tự {@link #approve}
   */
  dev.nivic.coa.mc.Proposal reject(long proposalId, String checkerId, String reason);

  /** Đề xuất theo id (kèm lines), hoặc null nếu không tồn tại. */
  dev.nivic.coa.mc.Proposal findProposal(long proposalId);

  /** Danh sách đề xuất đang chờ duyệt (PENDING), mới nhất trước. */
  java.util.List<dev.nivic.coa.mc.Proposal> pendingProposals();

  // ── Reversal / Hoàn tiền ──────────────────────────────────────────────────────

  /**
   * Đảo ngược một giao dịch đã ghi sổ: post bút toán bù trừ (swap debit↔credit từng dòng).
   * Giao dịch gốc giữ nguyên (audit trail); reversal được link qua {@code reverses_ref}.
   * Idempotent on {@link ReversalCmd#reversalRef()}.
   *
   * @throws TransactionNotFoundException if {@code originalRef} không tồn tại
   * @throws AlreadyReversedException     if giao dịch gốc đã được đảo bởi một reversal khác
   */
  CoaTrans reverse(ReversalCmd cmd);

  // ── Period Close / Khoá sổ ────────────────────────────────────────────────────

  /**
   * Khoá sổ cuối kỳ: kết chuyển doanh thu (4xxx) và chi phí (5xxx) về Lợi nhuận giữ lại (6100).
   * Posts một journal: DR mọi 4xxx (về 0) / CR mọi 5xxx (về 0) / cân bằng vào 6100 (lãi → CR,
   * lỗ → DR). Sau khoá: 4xxx/5xxx = 0, 6100 += lãi thuần. Idempotent on
   * {@link PeriodCloseCmd#closeRef()}.
   *
   * @throws NothingToCloseException nếu không có số dư doanh thu/chi phí để kết chuyển
   */
  CoaTrans closePeriod(PeriodCloseCmd cmd);

  /**
   * Platform double-entry sanity check: sum of all debits across all transactions
   * must equal sum of all credits. Always true if posting is correct.
   */
  boolean isDoubleEntryBalanced();
}
