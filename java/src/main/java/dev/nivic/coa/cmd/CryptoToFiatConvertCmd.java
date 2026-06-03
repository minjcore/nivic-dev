package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 2 — Convert crypto to fiat and credit merchant.
 * Multi-line bút toán:
 *  Line 1: DR 1200-CRYPTO-FIAT / CR 3500-CRYPTO-RECV (FX buffer; clears transit)
 *  Line 2: DR 2100-USER-PAYABLE / CR 1200-CRYPTO-FIAT (Accrual)
 *  Line 3: DR 1300-MERCHANT-BAL / CR 2100-USER-PAYABLE (Post to merchant)
 *
 * Framework-agnostic: no Spring annotations.
 */
public record CryptoToFiatConvertCmd(
    String depositId,
    long cryptoAmountMinor,           // original crypto amount
    String cryptoCurrency,             // USDT, ETH, BTC
    long vndAmountMinor,              // converted amount (VND in minor units)
    long fxRateSnapshot,              // fixed FX rate applied (e.g., 24500 for 1 USDT = 24,500 VND)
    long rateTimestampMs,             // timestamp when rate was captured
    String merchantId,                // merchant receiving the credit
    long custodyFeeMinor) {           // platform fee (if any; deducted from vndAmountMinor)

  public CryptoToFiatConvertCmd {
    Objects.requireNonNull(depositId, "depositId");
    Objects.requireNonNull(cryptoCurrency, "cryptoCurrency");
    Objects.requireNonNull(merchantId, "merchantId");
    if (cryptoAmountMinor <= 0) throw new IllegalArgumentException("crypto amount must be positive");
    if (vndAmountMinor <= 0) throw new IllegalArgumentException("fiat amount must be positive");
    if (fxRateSnapshot <= 0) throw new IllegalArgumentException("fx rate must be positive");
    if (custodyFeeMinor < 0) throw new IllegalArgumentException("fee cannot be negative");
    if (custodyFeeMinor >= vndAmountMinor) throw new IllegalArgumentException("fee exceeds converted amount");
  }

  /** Net amount credited to merchant (after fees). */
  public long netCreditMinor() {
    return vndAmountMinor - custodyFeeMinor;
  }
}
