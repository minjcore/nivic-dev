package dev.nivic.coa;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Extended account record with hierarchy, status, and audit fields.
 * Complements CoaAccount (simple balance record) with full account metadata.
 */
public record CoaAccountExt(
    String code,
    String name,
    CoaAccountKind kind,
    String currencyCode,
    long balanceMinor,
    long version,
    Optional<String> parentCode,    // Parent account code for hierarchy
    AccountStatus status,           // ACTIVE, INACTIVE, ARCHIVED
    String description,             // Account description/notes
    Instant createdAt,
    Instant updatedAt,
    String createdBy,
    String updatedBy) {

  public CoaAccountExt {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(currencyCode, "currencyCode");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");

    // Validate: parent code must be different from code
    if (parentCode.isPresent() && parentCode.get().equals(code)) {
      throw new IllegalArgumentException("Account cannot be its own parent");
    }
  }

  public boolean isActive() {
    return status == AccountStatus.ACTIVE;
  }

  public boolean isDebitNormal() {
    return kind == CoaAccountKind.ASSET || kind == CoaAccountKind.EXPENSE;
  }

  public long naturalBalance() {
    return isDebitNormal() ? balanceMinor : -balanceMinor;
  }

  public boolean isTransitClear() {
    return kind == CoaAccountKind.TRANSIT && balanceMinor == 0;
  }

  public CoaAccountExt withStatus(AccountStatus newStatus, String updatedByUser) {
    return new CoaAccountExt(
        code, name, kind, currencyCode, balanceMinor, version,
        parentCode, newStatus, description,
        createdAt, Instant.now(), createdBy, updatedByUser);
  }

  public CoaAccountExt withBalance(long newBalance, long newVersion) {
    return new CoaAccountExt(
        code, name, kind, currencyCode, newBalance, newVersion,
        parentCode, status, description,
        createdAt, updatedAt, createdBy, updatedBy);
  }
}
