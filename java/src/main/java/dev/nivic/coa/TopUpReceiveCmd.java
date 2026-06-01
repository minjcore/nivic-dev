package dev.nivic.coa;

import java.util.Objects;

/**
 * Step 1 — NH nhận tiền từ user.
 * Bút toán: DR 1111 (TK Vietinbank) / CR 3100 (Transit Nạp).
 */
public record TopUpReceiveCmd(
    long amountMinor,
    /** Unique bank reference — dùng để idempotency và link với confirmTopUp. */
    String bankRef,
    String memo) {

  public TopUpReceiveCmd {
    Objects.requireNonNull(bankRef, "bankRef");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
  }
}
