package dev.nivic.coa;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nivic.coa.cmd.*;
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
 * Per-user wallet subledger: 2110 là control account, mỗi dòng 2110 mang party_mid.
 * Số dư ví user = Σ(credit−debit) dòng 2110 có party_mid=X. Bất biến: Σ ví = natural(2110).
 */
@Testcontainers
@Tag("integration")
class WalletSubledgerTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcFundFlowLedger ledger;

  private static final long U1 = 1001L, U2 = 1002L, U3 = 1003L;

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
  void setUp() { ledger = new JdbcFundFlowLedger(ds); }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  /** Nạp tiền cho user (confirm gắn mid). Trả về số tiền vào ví (amount − fee). */
  private long topupTo(long mid, long amount, long fee, String tag) {
    ledger.receiveTopUp(new TopUpReceiveCmd(amount, tag + "-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(amount, fee, tag + "-C", null, mid));
    return amount - fee;
  }

  private long natural2110() { return -ledger.getBalance("2110"); }

  // ── Basic per-user tracking ─────────────────────────────────────────────────

  @Test
  void topup_creditsUserWallet() {
    topupTo(U1, 100_000L, 1_000L, "S1");
    assertEquals(99_000L, ledger.walletBalance(U1), "ví user = amount − fee");
    assertEquals(0L, ledger.walletBalance(U2), "user khác = 0");
  }

  @Test
  void multipleUsers_trackedSeparately() {
    topupTo(U1, 100_000L, 1_000L, "M1");
    topupTo(U2, 200_000L, 1_000L, "M2");
    topupTo(U3, 50_000L, 0L, "M3");

    assertEquals( 99_000L, ledger.walletBalance(U1));
    assertEquals(199_000L, ledger.walletBalance(U2));
    assertEquals( 50_000L, ledger.walletBalance(U3));
  }

  @Test
  void sumOfWallets_equalsControlAccount() {
    topupTo(U1, 100_000L, 1_000L, "C1");
    topupTo(U2, 200_000L, 1_000L, "C2");
    topupTo(U3, 50_000L, 0L, "C3");

    long sumWallets = ledger.walletBalance(U1) + ledger.walletBalance(U2) + ledger.walletBalance(U3);
    assertEquals(natural2110(), sumWallets, "Σ ví user = số dư 2110 (control account)");
    assertEquals(348_000L, sumWallets); // 99k + 199k + 50k
  }

  // ── Debit operations reduce the right wallet ────────────────────────────────

  @Test
  void withdraw_debitsUserWallet() {
    topupTo(U1, 500_000L, 0L, "W1");
    ledger.initWithdraw(new WithdrawInitCmd(100_000L, 1_000L, "W1-WI", null, U1));
    ledger.settleWithdraw(new WithdrawSettleCmd(100_000L, 1_000L, "W1-WS", null));

    assertEquals(500_000L - 101_000L, ledger.walletBalance(U1), "trừ gốc + phí");
    assertEquals(natural2110(), ledger.walletBalance(U1));
  }

  @Test
  void walletPayment_debitsPayer() {
    topupTo(U1, 500_000L, 0L, "P1");
    ledger.initWalletPayment(new WalletPaymentInitCmd(120_000L, "P1-I", null, U1));
    ledger.settleWalletPayment(new WalletPaymentSettleCmd(120_000L, "P1-S", null));

    assertEquals(380_000L, ledger.walletBalance(U1));
  }

  @Test
  void ibft_debitsSender() {
    topupTo(U1, 500_000L, 0L, "I1");
    ledger.initIbftTransfer(new IbftInitCmd(40_000L, 1_000L, "I1-I", null, U1));
    ledger.settleIbftTransfer(new IbftSettleCmd(40_000L, 1_000L, 500L, "I1-S", null));

    assertEquals(500_000L - 41_000L, ledger.walletBalance(U1));
  }

  // ── Internal transfer moves between two wallets ──────────────────────────────

  @Test
  void internalTransfer_senderToReceiver() {
    topupTo(U1, 500_000L, 0L, "T1");
    // U1 gửi 100k (phí 1k) → U2
    ledger.initInternalTransfer(new InternalTransferInitCmd(100_000L, 1_000L, "T1-I", null, U1));
    ledger.settleInternalTransfer(new InternalTransferSettleCmd(100_000L, 1_000L, "T1-S", null, U2));

    assertEquals(500_000L - 101_000L, ledger.walletBalance(U1), "người gửi: −(gốc+phí)");
    assertEquals(100_000L,            ledger.walletBalance(U2), "người nhận: +gốc");
    // Σ ví = natural 2110; chênh đúng bằng phí (đã vào doanh thu 4130, không thuộc ví)
    assertEquals(natural2110(), ledger.walletBalance(U1) + ledger.walletBalance(U2));
  }

  // ── Reconciliation invariant across mixed flows ──────────────────────────────

  @Test
  void reconciliation_holdsAcrossMixedFlows() {
    topupTo(U1, 1_000_000L, 1_000L, "R1");
    topupTo(U2, 500_000L, 0L, "R2");
    ledger.initWithdraw(new WithdrawInitCmd(100_000L, 1_000L, "R-W", null, U1));
    ledger.settleWithdraw(new WithdrawSettleCmd(100_000L, 1_000L, "R-WS", null));
    ledger.initInternalTransfer(new InternalTransferInitCmd(50_000L, 1_000L, "R-T", null, U1));
    ledger.settleInternalTransfer(new InternalTransferSettleCmd(50_000L, 1_000L, "R-TS", null, U2));
    ledger.initWalletPayment(new WalletPaymentInitCmd(30_000L, "R-P", null, U2));
    ledger.settleWalletPayment(new WalletPaymentSettleCmd(30_000L, "R-PS", null));

    long sum = ledger.walletBalance(U1) + ledger.walletBalance(U2);
    assertEquals(natural2110(), sum, "Σ ví = 2110 sau nhiều luồng hỗn hợp");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  // ── Backward compatibility (no mid) ─────────────────────────────────────────

  @Test
  void noMid_aggregateOnly_walletBalanceZero() {
    // Dùng constructor cũ (không mid) → party_mid null
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "NM-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "NM-C", null)); // no mid

    assertEquals(0L, ledger.walletBalance(U1), "không gắn mid → ví trống");
    assertEquals(99_000L, natural2110(), "nhưng control 2110 vẫn ghi nhận");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void unknownUser_zeroBalance() {
    topupTo(U1, 100_000L, 0L, "UK");
    assertEquals(0L, ledger.walletBalance(9999L), "user chưa từng giao dịch = 0");
  }
}
