package dev.nivic.sevlet;

import dev.nivic.money.Money;
import java.util.Currency;
import java.util.Objects;

/**
 * Parsed Sevlet wallet payload: {@link #command} first after header padding, then fixed ids/amounts,
 * variable {@link #extraData} last before trailing {@link #sig} (see {@link SevletWalletCodec}).
 *
 * <p>Numeric fields are big-endian u64-in-long ({@link Long#compareUnsigned} where needed).</p>
 *
 * @param mid Authenticated party id on the wire: for merchants {@code mid == merchant_id} (same value
 *     as {@code wallet_mid_secret.mid}). Holder users use the same field as wallet party id — see
 *     {@link dev.nivic.party.MidConventions}.
 */
public record SevletWalletPayload(
    long command,
    long mid,
    long requestId,
    long orderId,
    long amount,
    int debit,
    int credit,
    byte[] extraData,
    byte[] sig
) {
  public SevletWalletPayload {
    Objects.requireNonNull(sig, "sig");
    Objects.requireNonNull(extraData, "extraData");
    if (sig.length != 32) {
      throw new IllegalArgumentException("sig must be 32 bytes, got " + sig.length);
    }
  }

  /** Wire {@code amount} (u64 bits) as {@link Money} minor units for {@code currency}. */
  public Money amountAsMoney(Currency currency) {
    return Money.ofMinor(currency, amount());
  }
}
