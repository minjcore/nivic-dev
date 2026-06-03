package dev.nivic.coa;

import java.util.List;
import java.util.Optional;

/**
 * Account management: CRUD operations, hierarchy, status management, lookups.
 * Separate from FundFlowLedger (which posts); this manages account metadata.
 */
public interface AccountManager {

  // ── Account Lookup ──────────────────────────────────────────────────────

  Optional<CoaAccountExt> findByCode(String code);

  Optional<CoaAccountExt> findByName(String name);

  List<CoaAccountExt> findByKind(CoaAccountKind kind);

  List<CoaAccountExt> findByCurrency(String currency);

  List<CoaAccountExt> findAll();

  // ── Hierarchy ──────────────────────────────────────────────────────────

  /**
   * Find parent account of the given code.
   * @return parent account, or empty if root (no parent)
   */
  Optional<CoaAccountExt> findParent(String accountCode);

  /**
   * Find all child accounts (direct children) of the given parent.
   */
  List<CoaAccountExt> findChildren(String parentCode);

  /**
   * Find all descendants (children, grandchildren, ...) of the given parent.
   */
  List<CoaAccountExt> findDescendants(String parentCode);

  // ── Creation ────────────────────────────────────────────────────────────

  /**
   * Create a new account.
   * @param code unique account code (e.g., "1111")
   * @param name account name
   * @param kind account type
   * @param currency functional currency
   * @param parentCode optional parent code (for hierarchy)
   * @param createdBy user who created
   * @return created account
   * @throws IllegalArgumentException if code already exists
   */
  CoaAccountExt createAccount(
      String code,
      String name,
      CoaAccountKind kind,
      String currency,
      Optional<String> parentCode,
      String description,
      String createdBy);

  // ── Status Management ──────────────────────────────────────────────────

  /**
   * Deactivate an account (prevent new postings, but keep history).
   * @throws AccountActiveException if account already inactive
   * @throws AccountHasDescendantsException if account has children
   */
  CoaAccountExt deactivate(String accountCode, String deactivatedBy);

  /**
   * Reactivate a deactivated account.
   */
  CoaAccountExt activate(String accountCode, String activatedBy);

  /**
   * Archive an account (data retention, no access).
   * Only allowed if balance is zero.
   */
  CoaAccountExt archive(String accountCode, String archivedBy);

  // ── Period Management ────────────────────────────────────────────────────

  /**
   * Get current open period.
   * @return current period, or empty if none open
   */
  Optional<AccountingPeriod> currentPeriod();

  /**
   * Get accounting period for a specific date.
   */
  Optional<AccountingPeriod> findPeriodFor(java.time.LocalDate date);

  /**
   * Create a new accounting period.
   */
  AccountingPeriod createPeriod(
      java.time.LocalDate start,
      java.time.LocalDate end,
      String createdBy);

  /**
   * Close a period (execute closing entries, set status to CLOSED).
   * Idempotent on period end date.
   */
  AccountingPeriod closePeriod(
      java.time.LocalDate periodEnd,
      long closingTransId,
      String closedBy);

  /**
   * Lock a closed period (prevent administrative changes).
   */
  AccountingPeriod lockPeriod(java.time.LocalDate periodEnd);

  /**
   * Check if posting is allowed (period open, account active).
   */
  void validatePostingAllowed(String accountCode) throws IllegalStateException;

  // ── Chart of Accounts Initialization ────────────────────────────────────

  /**
   * Initialize default GtelPay Chart of Accounts.
   * Called once at application startup.
   * Idempotent: safe to call multiple times.
   */
  void initializeDefaultCOA(String initializedBy);

  /**
   * Check if COA has been initialized.
   */
  boolean isInitialized();
}
