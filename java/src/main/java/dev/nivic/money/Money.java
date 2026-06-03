package dev.nivic.money;

import dev.nivic.util.Bytes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Monetary amount in ISO 4217 terms: {@link #minorUnits} (smallest unit, e.g. cents for USD) and
 * {@link #currency}.
 */
public final class Money implements Comparable<Money> {

  private final Currency currency;
  private final long minorUnits;

  public Money(Currency currency, long minorUnits) {
    this.currency = Objects.requireNonNull(currency, "currency");
    this.minorUnits = minorUnits;
  }

  /** Minor units (may be negative if you use signed amounts). */
  public long minorUnits() {
    return minorUnits;
  }

  public Currency currency() {
    return currency;
  }

  public static Money zero(Currency currency) {
    return new Money(currency, 0L);
  }

  /** e.g. USD → minor units are cents. */
  public static Money ofMinor(Currency currency, long minorUnits) {
    return new Money(currency, minorUnits);
  }

  /**
   * Amount in major units (USD, EUR, …); fractional digits follow {@link
   * Currency#getDefaultFractionDigits()}.
   */
  public static Money ofMajor(Currency currency, BigDecimal major) {
    Objects.requireNonNull(major, "major");
    int fd = Math.max(0, currency.getDefaultFractionDigits());
    BigDecimal minor =
        major.movePointRight(fd).setScale(0, RoundingMode.HALF_UP);
    try {
      return new Money(currency, minor.longValueExact());
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("amount out of long range: " + major, e);
    }
  }

  /**
   * Reads eight big-endian bytes as a {@code long} (same bit layout as wire u64). Same as {@link
   * dev.nivic.util.Bytes#readInt64BE(byte[])}.
   */
  public static long longFromBigEndianBytes(byte[] bytes) {
    return Bytes.readInt64BE(bytes);
  }

  /**
   * Reads eight big-endian bytes at {@code offset} as a {@code long}.
   *
   * @throws IllegalArgumentException if {@code offset < 0} or {@code offset + 8 > bytes.length}
   */
  public static long longFromBigEndianBytes(byte[] bytes, int offset) {
    return Bytes.readInt64BE(bytes, offset);
  }

  /** Writes {@code value} as eight big-endian bytes (new array). */
  public static byte[] longToBigEndianBytes(long value) {
    return Bytes.writeInt64BE(value);
  }

  public Money add(Money other) {
    requireSameCurrency(other);
    return new Money(currency, Math.addExact(minorUnits, other.minorUnits));
  }

  public Money subtract(Money other) {
    requireSameCurrency(other);
    return new Money(currency, Math.subtractExact(minorUnits, other.minorUnits));
  }

  public Money negate() {
    return new Money(currency, Math.negateExact(minorUnits));
  }

  /** Same currency; value as major units (BigDecimal) with currency scale. */
  public BigDecimal majorAmount() {
    int fd = Math.max(0, currency.getDefaultFractionDigits());
    return BigDecimal.valueOf(minorUnits).movePointLeft(fd);
  }

  private void requireSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException(
          "currency mismatch: " + currency + " vs " + other.currency);
    }
  }

  @Override
  public int compareTo(Money o) {
    requireSameCurrency(o);
    return Long.compare(minorUnits, o.minorUnits);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    return obj instanceof Money m
        && minorUnits == m.minorUnits
        && currency.equals(m.currency);
  }

  @Override
  public int hashCode() {
    return Objects.hash(currency, minorUnits);
  }

  @Override
  public String toString() {
    return majorAmount().toPlainString() + " " + currency.getCurrencyCode();
  }
}
