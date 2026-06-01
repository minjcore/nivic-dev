package dev.nivic.coa;

import java.util.Objects;

/** One bút toán line in {@code coa_trans_data}: debit or credit on a COA account. */
public record CoaTransLine(
    int lineNo,
    String accountCode,
    String accountName,
    long debitMinor,
    long creditMinor,
    String currencyCode) {

  public CoaTransLine {
    Objects.requireNonNull(accountCode, "accountCode");
    Objects.requireNonNull(currencyCode, "currencyCode");
  }

  public boolean isDebit()  { return debitMinor  > 0; }
  public boolean isCredit() { return creditMinor > 0; }

  /** Net change to the account balance (debit − credit). */
  public long netDelta() { return debitMinor - creditMinor; }
}
