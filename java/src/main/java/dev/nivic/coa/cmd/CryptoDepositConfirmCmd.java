package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 1 — Crypto deposit confirmed on-chain.
 * Bút toán: DR 1100-USDT (crypto asset) / CR 3500-CRYPTO-RECV (transit).
 *
 * Framework-agnostic: no Spring annotations. Can be called from any layer.
 */
public record CryptoDepositConfirmCmd(
    String depositId,
    long cryptoAmountMinor,      // smallest unit (satoshi, wei, etc)
    String cryptoCurrency,        // USDT, ETH, BTC
    String txHash,
    int blockConfirmations,
    long blockHeight) {

  public CryptoDepositConfirmCmd {
    Objects.requireNonNull(depositId, "depositId");
    Objects.requireNonNull(cryptoCurrency, "cryptoCurrency");
    Objects.requireNonNull(txHash, "txHash");
    if (cryptoAmountMinor <= 0) throw new IllegalArgumentException("amount must be positive");
    if (blockConfirmations < 0) throw new IllegalArgumentException("confirmations must be >= 0");
  }
}
