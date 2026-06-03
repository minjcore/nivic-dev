package dev.nivic.coa;

/**
 * Crypto deposit handler — pure interface, no framework dependencies.
 * Can be implemented in Java, Go, Rust, Node.js, etc.
 *
 * Single responsibility: take a crypto deposit event and atomically post
 * double-entry journal entries to the ledger.
 */
public interface CryptoDepositHandler {

  /**
   * Process: Crypto received on-chain → DR 1100-CRYPTO / CR 3500-TRANSIT.
   *
   * @param depositId unique deposit identifier (idempotency key)
   * @param cryptoAmountMinor amount in smallest unit (wei, satoshi, etc)
   * @param cryptoCurrency asset code (USDT, ETH, BTC)
   * @param txHash blockchain transaction hash
   * @param blockHeight block number where deposit was confirmed
   * @return transaction ID in ledger (for audit/replay)
   * @throws IllegalArgumentException if amount <= 0 or currency unknown
   * @throws ConflictException if depositId already processed (idempotent)
   * @throws LedgerException if journal post fails
   */
  long depositConfirmed(
      String depositId,
      long cryptoAmountMinor,
      String cryptoCurrency,
      String txHash,
      long blockHeight)
      throws LedgerException;

  /**
   * Process: FX conversion → 3 lines as per CRYPTO_TOPUP_FLOW.md Step 2.
   * DR 1200 / CR 3500 (clear transit)
   * DR 2100 / CR 1200 (accrual)
   * DR 1300 / CR 2100 (post to merchant balance)
   *
   * @param depositId same ID from depositConfirmed (must exist)
   * @param cryptoAmountMinor original crypto amount (for validation)
   * @param cryptoCurrency asset code (USDT, ETH, BTC)
   * @param vndAmountMinor converted amount (VND minor units)
   * @param fxRate exchange rate applied (e.g., 24500 for 1 USDT = 24,500 VND)
   * @param merchantId merchant to credit
   * @param custodyFeeMinor platform fee (deducted from vnd amount)
   * @return transaction ID in ledger
   * @throws IllegalArgumentException if inputs don't match prior depositConfirmed
   * @throws ConflictException if already converted (idempotent)
   * @throws LedgerException if journal post fails
   */
  long convertToFiat(
      String depositId,
      long cryptoAmountMinor,
      String cryptoCurrency,
      long vndAmountMinor,
      long fxRate,
      String merchantId,
      long custodyFeeMinor)
      throws LedgerException;

  /**
   * Query: Check if a deposit is already posted to ledger.
   *
   * @param depositId deposit identifier
   * @return true if depositConfirmed() was called and succeeded
   */
  boolean isDepositPosted(String depositId);

  /**
   * Query: Get the transaction ID for a deposit (for audit/replay).
   *
   * @param depositId deposit identifier
   * @return transaction ID, or -1 if not found
   */
  long getDepositTransactionId(String depositId);
}
