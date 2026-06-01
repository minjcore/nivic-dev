package dev.nivic.coa;

import static org.junit.jupiter.api.Assertions.*;

import dev.nivic.coa.cmd.*;
import dev.nivic.coa.error.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for chuyển tiền nội bộ (ví → ví):
 * initInternalTransfer (DR 2110 / CR 3300) → settleInternalTransfer (DR 3300 / CR 2110 / CR 4130).
 *
 * <p>Đặc điểm: không phát sinh TK NH (1111/1112/1113). Transit 3300 về 0 sau mỗi luồng.</p>
 */
@Testcontainers
@Tag("integration")
class InternalTransferFlowTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcFundFlowLedger ledger;

  @BeforeAll
  static void initPool() {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(PG.getJdbcUrl());
    cfg.setUsername(PG.getUsername());
    cfg.setPassword(PG.getPassword());
    cfg.setMaximumPoolSize(5);
    ds = new HikariDataSource(cfg);
  }

  @AfterAll
  static void closePool() { ds.close(); }

  @BeforeEach
  void setUp() {
    ledger = new JdbcFundFlowLedger(ds);
    // Seed: 2 users nạp tiền — tổng 2110 = -300k
    ledger.receiveTopUp(new TopUpReceiveCmd(200_000L, "SEED-A", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(200_000L, 0L, "SEED-A-CONF", null));
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "SEED-B", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 0L, "SEED-B-CONF", null));
    // 2110 = -300k | 1111 = +300k | 3300 = 0
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  // ── Step 1: initInternalTransfer ──────────────────────────────────────────────

  @Test
  void init_postsTwoBalancedLines() {
    CoaTrans t = ledger.initInternalTransfer(
        new InternalTransferInitCmd(100_000L, 1_000L, "IT-INIT-01", null));

    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size());

    CoaTransLine dr = t.lines().stream().filter(CoaTransLine::isDebit).findFirst().orElseThrow();
    CoaTransLine cr = t.lines().stream().filter(CoaTransLine::isCredit).findFirst().orElseThrow();

    assertEquals("2110",  dr.accountCode());
    assertEquals(101_000L, dr.debitMinor(), "DR 2110 = amount + fee");

    assertEquals("3300",  cr.accountCode());
    assertEquals(101_000L, cr.creditMinor(), "CR 3300 = amount + fee");
    assertEquals("Transit - Chuyển tiền nội bộ", cr.accountName());
  }

  @Test
  void init_updatesWalletAndTransitBalances() {
    ledger.initInternalTransfer(new InternalTransferInitCmd(100_000L, 1_000L, "IT-INIT-02", null));

    // 2110: -300k (seed) + 101k (DR) = -199k
    assertEquals(-199_000L, ledger.getBalance("2110"));
    // 3300: 0 + (-101k) (CR) = -101k
    assertEquals(-101_000L, ledger.getBalance("3300"));
    // NH không động
    assertEquals( 300_000L, ledger.getBalance("1111"));
  }

  @Test
  void init_noBankAccountMovement() {
    long bankBefore = ledger.getBalance("1111");
    ledger.initInternalTransfer(new InternalTransferInitCmd(100_000L, 1_000L, "IT-INIT-03", null));
    assertEquals(bankBefore, ledger.getBalance("1111"), "NH không phát sinh");
  }

  @Test
  void init_idempotent() {
    CoaTrans first  = ledger.initInternalTransfer(
        new InternalTransferInitCmd(100_000L, 1_000L, "IT-INIT-04", null));
    CoaTrans second = ledger.initInternalTransfer(
        new InternalTransferInitCmd(100_000L, 1_000L, "IT-INIT-04", null));

    assertEquals(first.id(), second.id());
    assertEquals(-199_000L, ledger.getBalance("2110"), "no double-debit on retry");
  }

  @Test
  void init_insufficientWallet_throws() {
    // 2110 = -300k, gửi 400k → insufficient
    assertThrows(InsufficientWalletException.class,
        () -> ledger.initInternalTransfer(
            new InternalTransferInitCmd(400_000L, 1_000L, "IT-OVER", null)));

    assertEquals(-300_000L, ledger.getBalance("2110"), "balance unchanged");
    assertEquals(       0L, ledger.getBalance("3300"), "transit unchanged");
  }

  // ── Step 2: settleInternalTransfer ────────────────────────────────────────────

  @Test
  void settle_postsThreeLines() {
    ledger.initInternalTransfer(new InternalTransferInitCmd(100_000L, 1_000L, "IT-INIT-05", null));
    CoaTrans t = ledger.settleInternalTransfer(
        new InternalTransferSettleCmd(100_000L, 1_000L, "IT-SETL-05", null));

    assertTrue(t.isBalanced());
    assertEquals(3, t.lines().size());

    CoaTransLine dr3300 = t.lines().stream()
        .filter(l -> "3300".equals(l.accountCode()) && l.isDebit())
        .findFirst().orElseThrow();
    assertEquals(101_000L, dr3300.debitMinor(), "DR transit = amount + fee");

    CoaTransLine cr2110 = t.lines().stream()
        .filter(l -> "2110".equals(l.accountCode()))
        .findFirst().orElseThrow();
    assertEquals(100_000L, cr2110.creditMinor(), "CR 2110 = amount (người nhận)");

    CoaTransLine cr4130 = t.lines().stream()
        .filter(l -> "4130".equals(l.accountCode()))
        .findFirst().orElseThrow();
    assertEquals(1_000L, cr4130.creditMinor(), "CR 4130 = fee");
  }

  @Test
  void settle_allBalancesCorrect() {
    ledger.initInternalTransfer(new InternalTransferInitCmd(100_000L, 1_000L, "IT-INIT-06", null));
    ledger.settleInternalTransfer(new InternalTransferSettleCmd(100_000L, 1_000L, "IT-SETL-06", null));

    // 2110 balance = Σdebit − Σcredit
    // seed: -300k | step1 DR +101k: -199k | step2 CR -100k: -299k
    // net delta = +1k (fee only drains out of wallet)
    assertEquals(-299_000L, ledger.getBalance("2110"),
        "net effect on 2110 = fee only (sender -101k, receiver +100k → net -1k)");
    assertEquals(      0L, ledger.getBalance("3300"), "transit cleared");
    assertEquals( 300_000L, ledger.getBalance("1111"), "NH không động");
    assertEquals( -1_000L, ledger.getBalance("4130"), "doanh thu phí chuyển tiền");
  }

  @Test
  void settle_transitClearsToZero() {
    ledger.initInternalTransfer(new InternalTransferInitCmd(100_000L, 1_000L, "IT-INIT-07", null));
    ledger.settleInternalTransfer(new InternalTransferSettleCmd(100_000L, 1_000L, "IT-SETL-07", null));

    assertEquals(0L, ledger.getBalance("3300"), "Transit 3300 must be 0 after full flow");
  }

  @Test
  void settle_idempotent() {
    ledger.initInternalTransfer(new InternalTransferInitCmd(100_000L, 1_000L, "IT-INIT-08", null));
    CoaTrans first  = ledger.settleInternalTransfer(
        new InternalTransferSettleCmd(100_000L, 1_000L, "IT-SETL-08", null));
    CoaTrans second = ledger.settleInternalTransfer(
        new InternalTransferSettleCmd(100_000L, 1_000L, "IT-SETL-08", null));

    assertEquals(first.id(), second.id());
    assertEquals(0L, ledger.getBalance("3300"), "no double-debit on retry");
  }

  @Test
  void settle_withoutInit_throws() {
    assertThrows(InsufficientTransitException.class,
        () -> ledger.settleInternalTransfer(
            new InternalTransferSettleCmd(100_000L, 1_000L, "IT-ORPHAN", null)));
  }

  @Test
  void settle_zeroFee_twoLines() {
    ledger.initInternalTransfer(new InternalTransferInitCmd(100_000L, 0L, "IT-INIT-NOFEE", null));
    CoaTrans t = ledger.settleInternalTransfer(
        new InternalTransferSettleCmd(100_000L, 0L, "IT-SETL-NOFEE", null));

    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size(), "zero fee: DR 3300 + CR 2110 only");
    assertEquals(0L, ledger.getBalance("4130"), "no revenue entry");
    // 2110 net: -300k - 100k + 100k = -300k (unchanged — pure internal move)
    assertEquals(-300_000L, ledger.getBalance("2110"), "zero-fee: no net change on 2110");
  }

  // ── Full flow end-to-end ─────────────────────────────────────────────────────

  @Test
  void fullFlow_doubleEntryAlwaysBalanced() {
    assertTrue(ledger.isDoubleEntryBalanced(), "after setUp");

    ledger.initInternalTransfer(new InternalTransferInitCmd(100_000L, 1_000L, "IT-E2E-INIT", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after init");

    ledger.settleInternalTransfer(new InternalTransferSettleCmd(100_000L, 1_000L, "IT-E2E-SETL", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after settle");
  }

  @Test
  void fullFlow_noBankMovement_contractInvariant() {
    long bankBefore = ledger.getBalance("1111");

    ledger.initInternalTransfer(new InternalTransferInitCmd(100_000L, 1_000L, "IT-CONTRACT-I", null));
    ledger.settleInternalTransfer(new InternalTransferSettleCmd(100_000L, 1_000L, "IT-CONTRACT-S", null));

    assertEquals(bankBefore, ledger.getBalance("1111"),
        "internal transfer must never touch bank accounts");
  }

  @Test
  void fullFlow_netWalletChangeEqualsFeeOnly() {
    long walletBefore = ledger.getBalance("2110");
    long fee = 1_000L;

    ledger.initInternalTransfer(new InternalTransferInitCmd(100_000L, fee, "IT-NET-I", null));
    ledger.settleInternalTransfer(new InternalTransferSettleCmd(100_000L, fee, "IT-NET-S", null));

    long walletAfter = ledger.getBalance("2110");
    // balance = Σdebit − Σcredit. Net: DR (amount+fee) − CR (amount) = +fee
    // wallet balance moves toward 0 by fee amount (less credit-heavy = less money in wallet)
    assertEquals(walletBefore + fee, walletAfter,
        "aggregate wallet balance increases by fee (credit-normal: less negative = smaller wallet)");
  }

  @Test
  void fullFlow_multipleTransfers_accumulateFee() {
    // 3 lần chuyển tiền, mỗi lần 50k + 1k phí
    for (int i = 1; i <= 3; i++) {
      ledger.initInternalTransfer(
          new InternalTransferInitCmd(50_000L, 1_000L, "IT-MULTI-I-" + i, null));
      ledger.settleInternalTransfer(
          new InternalTransferSettleCmd(50_000L, 1_000L, "IT-MULTI-S-" + i, null));
    }

    // 2110: -300k (seed), each transfer: DR +51k, CR -50k → net +1k per transfer
    // 3 transfers: -300k + 3k = -297k
    assertEquals(-297_000L, ledger.getBalance("2110"));
    assertEquals(       0L, ledger.getBalance("3300"), "all transit cleared");
    assertEquals(  -3_000L, ledger.getBalance("4130"), "3 × 1k fee");
    assertEquals( 300_000L, ledger.getBalance("1111"), "NH không động");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void fullFlow_topUpThenTransferThenWithdraw_allTransitsClear() {
    // Luồng phức hợp: nạp → chuyển → rút
    // State sau setUp: 2110=-300k, 1111=+300k

    // Chuyển 100k (1k phí): 2110 net -1k → -301k
    ledger.initInternalTransfer(new InternalTransferInitCmd(100_000L, 1_000L, "COMBO-IT-I", null));
    ledger.settleInternalTransfer(new InternalTransferSettleCmd(100_000L, 1_000L, "COMBO-IT-S", null));

    // Rút 50k (500đ phí): DR 2110 50500, CR 3200 50500 → settleWithdraw DR 3200, CR 1111 50k, CR 4120 500
    ledger.initWithdraw(new WithdrawInitCmd(50_000L, 500L, "COMBO-W-I", null));
    ledger.settleWithdraw(new WithdrawSettleCmd(50_000L, 500L, "COMBO-W-S", null));

    assertEquals(0L, ledger.getBalance("3300"), "transit nội bộ clear");
    assertEquals(0L, ledger.getBalance("3200"), "transit rút clear");
    assertTrue(ledger.isDoubleEntryBalanced());
  }
}
