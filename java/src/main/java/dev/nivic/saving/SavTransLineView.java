package dev.nivic.saving;

import java.util.Objects;

/**
 * Enriched bút toán line: {@link SavTransLine} + account metadata from {@code sav_account}.
 * Returned by {@link SavingLedger#findTransfer(long)}.
 */
public record SavTransLineView(
    int lineNo,
    long accountId,
    String accountNo,
    SavAccountKind accountKind,
    long debitMinor,
    long creditMinor,
    String currencyCode) {

  public SavTransLineView {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(accountNo, "accountNo");
    Objects.requireNonNull(accountKind, "accountKind");
    Objects.requireNonNull(currencyCode, "currencyCode");
  }

  public boolean isDebit()  { return debitMinor  > 0; }
  public boolean isCredit() { return creditMinor > 0; }
}
