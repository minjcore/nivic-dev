package dev.nivic.ledger;

import java.time.Instant;

/**
 * Wallet: user/merchant fund container linked to COA account.
 * All transfers MUST go through wallet (never direct blockchain).
 */
public record Wallet(
    long id,
    String uid,                    // user-id or merchant-id
    String walletType,             // USER, MERCHANT, HOT, TRANSIT
    String status,                 // ACTIVE, FROZEN, CLOSED
    long balanceMinor,
    String currencyCode,
    String accountCode,            // COA account: 1200, 2200, etc
    long version,
    Instant createdAt,
    Instant lastActivityAt
) {
  public static Wallet user(String uid, String currency, String accountCode) {
    return new Wallet(
        System.currentTimeMillis(),
        uid, "USER", "ACTIVE", 0L, currency,
        accountCode, 0L, Instant.now(), null
    );
  }

  public static Wallet merchant(String uid, String currency, String accountCode) {
    return new Wallet(
        System.currentTimeMillis(),
        uid, "MERCHANT", "ACTIVE", 0L, currency,
        accountCode, 0L, Instant.now(), null
    );
  }

  public boolean canTransferOut() {
    return "ACTIVE".equals(status);
  }

  public boolean canReceive() {
    return "ACTIVE".equals(status) || "FROZEN".equals(status);
  }
}
