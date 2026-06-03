package dev.nivic.money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/** Transfer between balances or convert across currencies. */
public final class MoneyTransfers {

  private MoneyTransfers() {}

  /** Balances A and B after the operation. */
  public record Balances(Money balanceA, Money balanceB) {}

  /**
   * Moves {@code amount} from balance A to balance B. All three must use the same {@link
   * Currency}.
   *
   * @throws IllegalArgumentException if {@code amount} is negative, currencies differ, or A cannot
   *     cover the transfer
   */
  public static Balances transfer(Money balanceA, Money balanceB, Money amount) {
    Objects.requireNonNull(balanceA, "balanceA");
    Objects.requireNonNull(balanceB, "balanceB");
    Objects.requireNonNull(amount, "amount");
    if (amount.minorUnits() < 0) {
      throw new IllegalArgumentException("amount must be >= 0");
    }
    if (!balanceA.currency().equals(balanceB.currency())
        || !balanceA.currency().equals(amount.currency())) {
      throw new IllegalArgumentException(
          "all amounts must share the same currency: "
              + balanceA.currency().getCurrencyCode()
              + ", "
              + balanceB.currency().getCurrencyCode()
              + ", "
              + amount.currency().getCurrencyCode());
    }
    if (balanceA.minorUnits() < amount.minorUnits()) {
      throw new IllegalArgumentException(
          "insufficient balance A: have "
              + balanceA.minorUnits()
              + ", need "
              + amount.minorUnits());
    }
    Money newA = balanceA.subtract(amount);
    Money newB = balanceB.add(amount);
    return new Balances(newA, newB);
  }

  /**
   * Converts {@code from} to {@code toCurrency} using a <strong>major-unit</strong> rate:
   * {@code majorBPerOneMajorA} is how many major units of B correspond to one major unit of A
   * (e.g. 1 USD = 0.92 EUR → {@code majorBPerOneMajorA = 0.92}).
   */
  public static Money convert(Money from, Currency toCurrency, BigDecimal majorBPerOneMajorA) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(toCurrency, "toCurrency");
    Objects.requireNonNull(majorBPerOneMajorA, "majorBPerOneMajorA");
    BigDecimal majorB = from.majorAmount().multiply(majorBPerOneMajorA);
    return Money.ofMajor(toCurrency, majorB);
  }
}
