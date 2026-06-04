package dev.nivic.ledger;

import java.time.Instant;

public record BankAccount(
    long id,
    String accountNumber,
    String bankCode,
    String bankName,
    String accountHolderName,
    String currency,
    String accountType,
    String status,
    Instant createdAt
) {
  // Account types: CHECKING, SAVINGS
  // Status: ACTIVE, INACTIVE, PENDING_VERIFICATION
}
