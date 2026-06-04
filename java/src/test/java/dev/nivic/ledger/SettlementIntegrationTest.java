package dev.nivic.ledger;

import static org.junit.jupiter.api.Assertions.*;

import dev.nivic.coa.*;
import dev.nivic.bank.BankGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

@Testcontainers
class SettlementIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
      .withDatabaseName("test_ledger")
      .withUsername("test")
      .withPassword("test");

  private DataSource dataSource;
  private WalletManager walletManager;
  private CurrencyManager currencyManager;
  private FundFlowLedger fundFlowLedger;
  private SettlementManager settlementManager;
  private BankGateway bankGateway;

  @BeforeEach
  void setup() {
    // Setup datasource
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(postgres.getJdbcUrl());
    ds.setUsername("test");
    ds.setPassword("test");
    this.dataSource = ds;

    // Initialize managers
    this.walletManager = new JdbcWalletManager(dataSource);
    this.currencyManager = new JdbcCurrencyManager(dataSource);
    this.fundFlowLedger = new JdbcFundFlowLedger(dataSource);
    this.bankGateway = new BankGateway(
        "https://swift.example.com",
        "https://ach.example.com",
        "https://local.example.com"
    );
    this.settlementManager = new JdbcSettlementManager(dataSource, walletManager, fundFlowLedger, bankGateway);

    // Seed currency
    currencyManager.register(Currency.usdt());
  }

  @Test
  void testMerchantDailySettlement() throws Exception {
    // 1. Create merchant wallet with 1000 USDT
    var merchantWallet = walletManager.createWallet("merchant-001", "MERCHANT", "USDT", "2200");
    assertNotNull(merchantWallet);
    assertEquals("MERCHANT", merchantWallet.walletType());

    // Manually add balance to wallet (simulate earnings)
    var conn = dataSource.getConnection();
    var ps = conn.prepareStatement("UPDATE wallet SET balance_minor = ? WHERE id = ?");
    ps.setLong(1, 1000_000_000L);  // 1000 USDT (18 decimals)
    ps.setLong(2, merchantWallet.id());
    ps.executeUpdate();
    conn.close();

    // 2. Initiate settlement
    var settlement = settlementManager.initiate(
        merchantWallet.id(),
        1000_000_000L,
        "MERCHANT",
        "USDT",
        "vietcombank"
    );
    assertEquals("PENDING", settlement.status());

    // 3. Hold balance
    settlementManager.hold(settlement.id());
    var held = settlementManager.get(settlement.id()).get();
    assertEquals("HOLD", held.status());

    // 4. Post to ledger (simulate FX conversion USDT → VND)
    // In real implementation, would create journal entries
    long transactionId = System.currentTimeMillis();
    settlementManager.post(settlement.id(), transactionId);
    var posted = settlementManager.get(settlement.id()).get();
    assertEquals("POSTED", posted.status());
    assertEquals(transactionId, posted.transactionId());

    // 5. Execute settlement (initiate bank transfer)
    settlementManager.execute(settlement.id(), "SWIFT-001");
    var executing = settlementManager.get(settlement.id()).get();
    assertEquals("EXECUTING", executing.status());
    assertEquals("SWIFT-001", executing.bankTransactionId());

    // 6. Confirm settlement (bank webhook)
    settlementManager.confirm(settlement.id(), "SWIFT-001-CONFIRMED");
    var confirmed = settlementManager.get(settlement.id()).get();
    assertEquals("CONFIRMED", confirmed.status());
    assertNotNull(confirmed.confirmedAt());
  }

  @Test
  void testUserCryptoWithdrawal() throws Exception {
    // 1. Create user wallet with 5 BTC
    var userWallet = walletManager.createWallet("user-001", "USER", "BTC", "1200");
    assertNotNull(userWallet);
    assertEquals("USER", userWallet.walletType());

    // Manually add balance
    var conn = dataSource.getConnection();
    var ps = conn.prepareStatement("UPDATE wallet SET balance_minor = ? WHERE id = ?");
    ps.setLong(1, 500_000_000L);  // 5 BTC (8 decimals)
    ps.setLong(2, userWallet.id());
    ps.executeUpdate();
    conn.close();

    // 2. Initiate withdrawal
    var withdrawal = settlementManager.initiate(
        userWallet.id(),
        500_000_000L,
        "WITHDRAWAL",
        "BTC",
        "1A1z7agoat2GNXRN2UQSCLCNTuUWEqeSeE"  // Example BTC address
    );
    assertEquals("PENDING", withdrawal.status());
    assertEquals("WITHDRAWAL", withdrawal.settlementType());

    // 3. Hold balance
    settlementManager.hold(withdrawal.id());
    var held = settlementManager.get(withdrawal.id()).get();
    assertEquals("HOLD", held.status());

    // 4. Post to ledger
    long transactionId = System.currentTimeMillis();
    settlementManager.post(withdrawal.id(), transactionId);

    // 5. Execute withdrawal
    settlementManager.execute(withdrawal.id(), "0x1234567890abcdef...");
    var executing = settlementManager.get(withdrawal.id()).get();
    assertEquals("EXECUTING", executing.status());

    // 6. Confirm once blockchain confirms (6 blocks)
    settlementManager.confirm(withdrawal.id(), "0x1234567890abcdef...");
    var confirmed = settlementManager.get(withdrawal.id()).get();
    assertEquals("CONFIRMED", confirmed.status());
  }

  @Test
  void testSettlementFailureAndRetry() throws Exception {
    // 1. Create merchant wallet
    var merchantWallet = walletManager.createWallet("merchant-002", "MERCHANT", "USDT", "2200");
    var conn = dataSource.getConnection();
    var ps = conn.prepareStatement("UPDATE wallet SET balance_minor = ? WHERE id = ?");
    ps.setLong(1, 1000_000_000L);
    ps.setLong(2, merchantWallet.id());
    ps.executeUpdate();
    conn.close();

    // 2. Initiate → Hold → Post → Execute
    var settlement = settlementManager.initiate(
        merchantWallet.id(), 1000_000_000L, "MERCHANT", "USDT", "vietcombank"
    );
    settlementManager.hold(settlement.id());
    long transactionId = System.currentTimeMillis();
    settlementManager.post(settlement.id(), transactionId);
    settlementManager.execute(settlement.id(), "SWIFT-FAIL");

    // 3. Simulate bank failure
    settlementManager.fail(settlement.id(), "Bank account closed");
    var failed = settlementManager.get(settlement.id()).get();
    assertEquals("FAILED_BANK", failed.status());

    // 4. Retry settlement
    settlementManager.retry(settlement.id());
    var retried = settlementManager.get(settlement.id()).get();
    assertEquals("PENDING", retried.status());
  }

  @Test
  void testListPendingSettlements() throws Exception {
    // Create 3 merchant wallets
    var wallet1 = walletManager.createWallet("m-001", "MERCHANT", "USDT", "2200");
    var wallet2 = walletManager.createWallet("m-002", "MERCHANT", "USDT", "2200");

    // Add balances
    var conn = dataSource.getConnection();
    for (var wallet : new Wallet[]{wallet1, wallet2}) {
      var ps = conn.prepareStatement("UPDATE wallet SET balance_minor = ? WHERE id = ?");
      ps.setLong(1, 1000_000_000L);
      ps.setLong(2, wallet.id());
      ps.executeUpdate();
    }
    conn.close();

    // Initiate settlements
    var s1 = settlementManager.initiate(wallet1.id(), 1000_000_000L, "MERCHANT", "USDT", "bank1");
    var s2 = settlementManager.initiate(wallet2.id(), 500_000_000L, "MERCHANT", "USDT", "bank2");

    // Hold s1, leave s2 pending
    settlementManager.hold(s1.id());

    // List pending (should only show PENDING, not HOLD)
    var pending = settlementManager.listPending();
    assertEquals(1, pending.size());
    assertEquals(s2.id(), pending.get(0).id());

    // List by wallet
    var wallet1Settlements = settlementManager.listByWallet(wallet1.id());
    assertEquals(1, wallet1Settlements.size());

    // List by status HOLD
    var onHold = settlementManager.listByStatus("HOLD");
    assertEquals(1, onHold.size());
    assertEquals(s1.id(), onHold.get(0).id());
  }
}
