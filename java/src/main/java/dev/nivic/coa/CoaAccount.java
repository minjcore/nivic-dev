package dev.nivic.coa;

import java.util.Objects;

/**
 * Chart-of-Accounts entry. Balance convention: {@code balance_minor = sum(debit) - sum(credit)}.
 *
 * <ul>
 *   <li>ASSET / EXPENSE (debit-normal): positive balance = healthy.</li>
 *   <li>LIABILITY / REVENUE / EQUITY / TRANSIT (credit-normal): negative balance = healthy.</li>
 *   <li>TRANSIT accounts must always return to 0 after a complete flow.</li>
 * </ul>
 */
public record CoaAccount(
    String code,
    String name,
    CoaAccountKind kind,
    long balanceMinor,
    long version) {

  public CoaAccount {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
  }

  /** True for ASSET and EXPENSE accounts. */
  public boolean isDebitNormal() {
    return kind == CoaAccountKind.ASSET || kind == CoaAccountKind.EXPENSE;
  }

  /** Displayed balance: always positive when the account is in its natural state. */
  public long naturalBalance() {
    return isDebitNormal() ? balanceMinor : -balanceMinor;
  }

  /** Transit accounts must be zero after every completed flow. */
  public boolean isTransitClear() {
    return kind == CoaAccountKind.TRANSIT && balanceMinor == 0;
  }
}
