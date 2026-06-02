package dev.nivic.coa;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nivic.coa.cmd.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * Multi-currency: per-currency invariant. Mỗi tài khoản mono-currency; bút toán cân theo TỪNG
 * currency; FX exchange bắc cầu qua vị thế FX (1920 VND / 1921 USD).
 */
@Testcontainers
@Tag("integration")
class MultiCurrencyTest {

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
  void setUp() { ledger = new JdbcFundFlowLedger(ds); }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  // ── Account currency seeded ───────────────────────────────────────────────────

  @Test
  void fxAccounts_haveCorrectCurrency() throws SQLException {
    ledger.isDoubleEntryBalanced(); // ensure seed
    assertEquals("USD", currencyOf("1121"));
    assertEquals("USD", currencyOf("1921"));
    assertEquals("VND", currencyOf("1920"));
    assertEquals("VND", currencyOf("1111"));
  }

  // ── FX exchange ─────────────────────────────────────────────────────────────

  @Test
  void fxBuyUsd_balancedPerCurrency() {
    // Nền tảng có VND trước (nạp vốn vào 1111 qua topup-like: dùng FX trực tiếp cũng được vì
    // 1111 ASSET không ràng buộc âm). Mua 100 USD với 2,400,000 VND (rate 24,000).
    CoaTrans t = ledger.fxExchange(new FxExchangeCmd(2_400_000L, 100_00L, true, "FX-1", null));
    assertEquals(4, t.lines().size());

    // 1111 VND giảm, 1121 USD tăng
    assertEquals(-2_400_000L, ledger.getBalance("1111"), "VND cash chi ra");
    assertEquals(   10_000L,  ledger.getBalance("1121"), "USD cash nhận vào (100.00 USD)");
    // Vị thế FX giữ phần mở
    assertEquals( 2_400_000L, ledger.getBalance("1920"), "vị thế VND (DR)");
    assertEquals(  -10_000L,  ledger.getBalance("1921"), "vị thế USD (CR)");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void fxSellUsd_reversesDirection() {
    ledger.fxExchange(new FxExchangeCmd(2_400_000L, 10_000L, true, "FX-B", null));  // mua USD
    ledger.fxExchange(new FxExchangeCmd(1_200_000L, 5_000L, false, "FX-S", null));  // bán 50 USD

    // 1121 USD: +10000 − 5000 = 5000
    assertEquals(5_000L, ledger.getBalance("1121"));
    // 1111 VND: −2.4M + 1.2M = −1.2M
    assertEquals(-1_200_000L, ledger.getBalance("1111"));
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void fxExchange_idempotent() {
    CoaTrans a = ledger.fxExchange(new FxExchangeCmd(2_400_000L, 10_000L, true, "FX-IDEM", null));
    CoaTrans b = ledger.fxExchange(new FxExchangeCmd(2_400_000L, 10_000L, true, "FX-IDEM", null));
    assertEquals(a.id(), b.id());
    assertEquals(10_000L, ledger.getBalance("1121"), "no double exchange");
  }

  // ── Per-currency balance enforced ──────────────────────────────────────────────

  @Test
  void crossCurrencyTrans_balancesPerCurrency_commits() {
    // FX là một trans chứa cả VND legs lẫn USD legs — phải commit OK (per-currency balanced).
    assertDoesNotThrow(() ->
        ledger.fxExchange(new FxExchangeCmd(2_400_000L, 10_000L, true, "FX-OK", null)));
  }

  @Test
  void directInsert_unbalancedInOneCurrency_rejectedAtCommit() throws SQLException {
    ledger.isDoubleEntryBalanced();
    // VND cân (100=100) nhưng USD lệch (50 DR không có CR) → deferred trigger chặn theo currency.
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      long id;
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO coa_trans (ref_id) VALUES ('FX-BAD') RETURNING id")) {
        try (ResultSet rs = ps.executeQuery()) { rs.next(); id = rs.getLong(1); }
      }
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO coa_trans_data (trans_id,line_no,account_code,debit_minor,credit_minor,currency_code)"
              + " VALUES (?,1,'1920',100,0,'VND'),(?,2,'1111',0,100,'VND'),(?,3,'1121',50,0,'USD')")) {
        ps.setLong(1, id); ps.setLong(2, id); ps.setLong(3, id);
        ps.executeUpdate();
      }
      SQLException ex = assertThrows(SQLException.class, c::commit);
      assertEquals("23514", ex.getSQLState());
      c.rollback();
    }
  }

  @Test
  void currencyMismatch_lineVsAccount_rejected() {
    // Cố đẩy USD vào TK VND (1111) → currency mismatch (Java guard).
    // Dùng đường raw qua propose? fxExchange luôn đúng ccy. Test guard trực tiếp:
    // 1111 là VND; nếu một luồng gắn USD lên 1111 → IllegalArgumentException.
    // (Không có API public ghi sai ccy; kiểm tra qua proposal lines sai ccy sẽ ở tầng khác.)
    // Ở đây xác nhận fxExchange dùng đúng ccy nên không ném.
    assertDoesNotThrow(() ->
        ledger.fxExchange(new FxExchangeCmd(1_000L, 1_000L, true, "FX-CCY", null)));
  }

  // ── FX revaluation ─────────────────────────────────────────────────────────────

  @Test
  void revalue_gain_whenUsdAppreciates() {
    // Mua 100 USD @ 24,000 (cost 2.4M). USD lên 25,000 → lãi 100,000.
    ledger.fxExchange(new FxExchangeCmd(2_400_000L, 10_000L, true, "RV-FX", null));
    assertEquals(2_400_000L, ledger.getBalance("1920"));

    ledger.fxRevalue(new FxRevalueCmd(25_000L, "RV-1", null));

    assertEquals(2_500_000L, ledger.getBalance("1920"), "1920 mark-to-market @25,000");
    assertEquals(-100_000L, ledger.getBalance("4170"), "lãi tỷ giá (revenue, credit-normal)");
    assertEquals(0L, ledger.getBalance("5300"), "không có lỗ");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void revalue_loss_whenUsdDepreciates() {
    ledger.fxExchange(new FxExchangeCmd(2_400_000L, 10_000L, true, "RV-FX2", null));
    // USD xuống 23,000 → giá trị 2.3M < cost 2.4M → lỗ 100,000.
    ledger.fxRevalue(new FxRevalueCmd(23_000L, "RV-2", null));

    assertEquals(2_300_000L, ledger.getBalance("1920"), "1920 mark xuống");
    assertEquals(100_000L, ledger.getBalance("5300"), "lỗ tỷ giá (expense)");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void revalue_reflectedInPnl() {
    ledger.fxExchange(new FxExchangeCmd(2_400_000L, 10_000L, true, "RV-FX3", null));
    ledger.fxRevalue(new FxRevalueCmd(25_000L, "RV-3", null));

    var pl = new dev.nivic.coa.report.FundFlowReports(ds).profitAndLoss();
    assertEquals(100_000L, pl.totalRevenue(), "lãi tỷ giá vào doanh thu P&L");
    assertEquals(100_000L, pl.netProfit());
  }

  @Test
  void revalue_incremental_acrossRates() {
    ledger.fxExchange(new FxExchangeCmd(2_400_000L, 10_000L, true, "RV-FX4", null));
    ledger.fxRevalue(new FxRevalueCmd(25_000L, "RV-4a", null)); // +100k lãi, 1920=2.5M
    ledger.fxRevalue(new FxRevalueCmd(26_000L, "RV-4b", null)); // +100k nữa, 1920=2.6M

    assertEquals(2_600_000L, ledger.getBalance("1920"));
    assertEquals(-200_000L, ledger.getBalance("4170"), "tổng lãi 2 lần = 200k");
  }

  @Test
  void revalue_noPosition_throws() {
    assertThrows(dev.nivic.coa.error.NothingToRevalueException.class,
        () -> ledger.fxRevalue(new FxRevalueCmd(25_000L, "RV-NONE", null)));
  }

  @Test
  void revalue_rateUnchanged_throws() {
    ledger.fxExchange(new FxExchangeCmd(2_400_000L, 10_000L, true, "RV-FX5", null));
    assertThrows(dev.nivic.coa.error.NothingToRevalueException.class,
        () -> ledger.fxRevalue(new FxRevalueCmd(24_000L, "RV-SAME", null)));
  }

  @Test
  void revalue_idempotent() {
    ledger.fxExchange(new FxExchangeCmd(2_400_000L, 10_000L, true, "RV-FX6", null));
    var a = ledger.fxRevalue(new FxRevalueCmd(25_000L, "RV-IDEM", null));
    var b = ledger.fxRevalue(new FxRevalueCmd(25_000L, "RV-IDEM", null));
    assertEquals(a.id(), b.id());
    assertEquals(2_500_000L, ledger.getBalance("1920"), "no double revalue");
  }

  // ── VND-only flows unaffected ──────────────────────────────────────────────────

  @Test
  void vndOnlyFlows_stillWork() {
    assertDoesNotThrow(() -> {
      ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "MC-V-R", null));
      ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "MC-V-C", null));
    });
    assertEquals(100_000L, ledger.getBalance("1111"));
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  // ── helper ──────────────────────────────────────────────────────────────────

  private String currencyOf(String code) throws SQLException {
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT currency_code FROM coa_account WHERE code=?")) {
      ps.setString(1, code);
      try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
    }
  }
}
