package dev.nivic.coa;

import java.util.Objects;

/**
 * Bước 0 — Đối tác pre-fund (nạp tiền ký quỹ vào tài khoản chi hộ).
 * Bút toán: DR 1111 (TK Vietinbank) / CR 2130 (Ký quỹ đối tác).
 *
 * <p>Ghi nhận TGTT nhận tiền ký quỹ và phát sinh nghĩa vụ với đối tác.</p>
 */
public record DisbursementPrefundCmd(
    long amount,
    /** Unique reference cho lần pre-fund — idempotency key. */
    String prefundRef,
    String memo) {

  public DisbursementPrefundCmd {
    Objects.requireNonNull(prefundRef, "prefundRef");
    if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
  }
}
