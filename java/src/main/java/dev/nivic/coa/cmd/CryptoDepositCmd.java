package dev.nivic.coa.cmd;

/**
 * Command to post a crypto deposit from blockchain.
 *
 * Flow (3 journal entries):
 *   Step 1: DR 1100-CRYPTO / CR 3500-TRANSIT (on-chain receipt)
 *   Step 2: DR 1200-CONVERSION / CR 3500-TRANSIT (clear transit)
 *   Step 3: DR 2100-MERCHANT-PAYABLE / CR 1200-CONVERSION (accrual)
 *
 * @param refId idempotency key (e.g., "crypto-{deposit_id}")
 * @param amountMinor amount in smallest unit (wei, satoshi, etc.)
 * @param currency asset code (USDT, USDC, BTC, ETH)
 * @param txHash blockchain transaction hash
 * @param blockHeight block number for audit trail
 */
public record CryptoDepositCmd(
    String refId,
    long amountMinor,
    String currency,
    String txHash,
    long blockHeight) {

  public CryptoDepositCmd {
    if (amountMinor <= 0) throw new IllegalArgumentException("amount must be > 0");
    if (currency == null || currency.isEmpty()) throw new IllegalArgumentException("currency required");
  }
}
