package dev.nivic.coa;

import java.util.Objects;

/**
 * Bước 1a — Trừ ký quỹ đối tác, ghi transit chi hộ.
 * Bút toán: DR 2130 (Ký quỹ đối tác, amount + fee) / CR 3700 (Transit Chi hộ, amount + fee).
 */
public record DisbursementInitCmd(
    long amount,
    long fee,
    String requestRef,
    String memo) {

  public DisbursementInitCmd {
    Objects.requireNonNull(requestRef, "requestRef");
    if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
    if (fee    <  0) throw new IllegalArgumentException("fee must be >= 0");
  }

  public long totalDebit() { return amount + fee; }
}
