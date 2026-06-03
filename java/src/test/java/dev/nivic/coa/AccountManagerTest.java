package dev.nivic.coa;

import dev.nivic.coa.error.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class AccountManagerTest {

  @Container
  static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:15")
      .withDatabaseName("testdb")
      .withUsername("postgres")
      .withPassword("password");

  private AccountManager accountManager;

  @BeforeEach
  void setUp() {
    accountManager = new JdbcAccountManager(
        db.getJdbcUrl(),
        db.getUsername(),
        db.getPassword()
    );

    // Create tables
    try (var conn = java.sql.DriverManager.getConnection(
        db.getJdbcUrl(), db.getUsername(), db.getPassword());
         var stmt = conn.createStatement()) {
      stmt.execute("""
          CREATE TABLE IF NOT EXISTS coa_account_ext (
            code VARCHAR(10) PRIMARY KEY,
            name VARCHAR(255),
            kind VARCHAR(20),
            currency_code VARCHAR(3),
            balance_minor BIGINT,
            version BIGINT,
            parent_code VARCHAR(10),
            status VARCHAR(20),
            description TEXT,
            created_at TIMESTAMP,
            updated_at TIMESTAMP,
            created_by VARCHAR(100),
            updated_by VARCHAR(100)
          )
          """);
      stmt.execute("""
          CREATE TABLE IF NOT EXISTS accounting_periods (
            period_start DATE,
            period_end DATE PRIMARY KEY,
            status VARCHAR(20),
            created_at TIMESTAMP,
            closed_at TIMESTAMP,
            closed_by VARCHAR(100),
            closing_trans_id BIGINT
          )
          """);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void testInitializeDefaultCOA() {
    accountManager.initializeDefaultCOA("system");
    assertTrue(accountManager.isInitialized());

    // Verify key accounts exist
    assertTrue(accountManager.findByCode("1111").isPresent());
    assertTrue(accountManager.findByCode("2110").isPresent());
    assertTrue(accountManager.findByCode("3100").isPresent());
    assertTrue(accountManager.findByCode("4110").isPresent());
    assertTrue(accountManager.findByCode("5100").isPresent());
    assertTrue(accountManager.findByCode("6000").isPresent());
  }

  @Test
  void testCreateAccountWithHierarchy() {
    accountManager.initializeDefaultCOA("system");

    // Create parent account
    CoaAccountExt parent = accountManager.createAccount(
        "1200", "Bank Accounts Group", CoaAccountKind.ASSET, "VND",
        Optional.empty(), "Group of bank accounts", "user");
    assertEquals("1200", parent.code());

    // Create child account
    CoaAccountExt child = accountManager.createAccount(
        "1201", "Sub-account", CoaAccountKind.ASSET, "VND",
        Optional.of("1200"), "Child account", "user");
    assertEquals("1200", child.parentCode().get());

    // Verify hierarchy
    assertTrue(accountManager.findParent("1201").isPresent());
    assertEquals(1, accountManager.findChildren("1200").size());
  }

  @Test
  void testAccountStatusTransitions() {
    accountManager.initializeDefaultCOA("system");

    CoaAccountExt account = accountManager.findByCode("1111").get();
    assertTrue(account.isActive());

    // Deactivate
    CoaAccountExt deactivated = accountManager.deactivate("1111", "admin");
    assertFalse(deactivated.isActive());

    // Reactivate
    CoaAccountExt reactivated = accountManager.activate("1111", "admin");
    assertTrue(reactivated.isActive());
  }

  @Test
  void testCannotDeactivateAccountWithChildren() {
    accountManager.initializeDefaultCOA("system");

    // Create hierarchy
    accountManager.createAccount(
        "1200", "Parent", CoaAccountKind.ASSET, "VND",
        Optional.empty(), "Parent", "user");
    accountManager.createAccount(
        "1201", "Child", CoaAccountKind.ASSET, "VND",
        Optional.of("1200"), "Child", "user");

    // Should fail to deactivate parent
    assertThrows(AccountHasDescendantsException.class, () ->
        accountManager.deactivate("1200", "admin"));
  }

  @Test
  void testPeriodManagement() {
    LocalDate start = LocalDate.of(2026, 1, 1);
    LocalDate end = LocalDate.of(2026, 1, 31);

    // Create period
    AccountingPeriod period = accountManager.createPeriod(start, end, "user");
    assertTrue(period.isOpen());

    // Find current period
    Optional<AccountingPeriod> current = accountManager.currentPeriod();
    assertTrue(current.isPresent());
    assertEquals(end, current.get().periodEnd());

    // Close period
    AccountingPeriod closed = accountManager.closePeriod(end, 999L, "admin");
    assertFalse(closed.canPost());
  }

  @Test
  void testValidatePostingAllowed() {
    accountManager.initializeDefaultCOA("system");

    LocalDate start = LocalDate.of(2026, 1, 1);
    LocalDate end = LocalDate.of(2026, 1, 31);
    accountManager.createPeriod(start, end, "system");

    // Should succeed when period open
    assertDoesNotThrow(() -> accountManager.validatePostingAllowed("1111"));

    // Deactivate account
    accountManager.deactivate("1111", "admin");

    // Should fail when account inactive
    assertThrows(AccountInactiveException.class, () ->
        accountManager.validatePostingAllowed("1111"));
  }

  @Test
  void testFindByKind() {
    accountManager.initializeDefaultCOA("system");

    var assets = accountManager.findByKind(CoaAccountKind.ASSET);
    assertTrue(assets.size() > 0);
    assertTrue(assets.stream().allMatch(a -> a.kind() == CoaAccountKind.ASSET));

    var transits = accountManager.findByKind(CoaAccountKind.TRANSIT);
    assertTrue(transits.size() > 0);
    assertTrue(transits.stream().allMatch(a -> a.kind() == CoaAccountKind.TRANSIT));
  }

  @Test
  void testFindByCurrency() {
    accountManager.initializeDefaultCOA("system");

    var vndAccounts = accountManager.findByCurrency("VND");
    assertTrue(vndAccounts.size() > 0);
    assertTrue(vndAccounts.stream().allMatch(a -> a.currencyCode().equals("VND")));
  }

  @Test
  void testIdempotentInitialization() {
    accountManager.initializeDefaultCOA("system");
    int countFirst = accountManager.findAll().size();

    // Initialize again (should be no-op)
    accountManager.initializeDefaultCOA("system");
    int countSecond = accountManager.findAll().size();

    assertEquals(countFirst, countSecond);
  }
}
