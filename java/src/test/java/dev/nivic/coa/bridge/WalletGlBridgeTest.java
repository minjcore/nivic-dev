package dev.nivic.coa.bridge;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nivic.coa.JdbcFundFlowLedger;
import dev.nivic.coa.cmd.TopUpConfirmCmd;
import dev.nivic.coa.cmd.TopUpReceiveCmd;
import dev.nivic.sevlet.SevletWalletPayload;
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
 * Cầu nối ví vận hành (Sevlet) → sổ cái COA. Mỗi payload ví phản chiếu thành DR/CR 2110 theo party.
 */
@Testcontainers
@Tag("integration")
class WalletGlBridgeTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcFundFlowLedger ledger;
  private WalletGlBridge bridge;

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
    bridge = new WalletGlBridge(ledger);
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  private SevletWalletPayload payload(long mid, long req, long amount, int debit, int credit) {
    return new SevletWalletPayload(1L, mid, req, 0L, amount, debit, credit, new byte[0], new byte[32]);
  }

  // ── Mirror một chuyển khoản ───────────────────────────────────────────────────

  @Test
  void mirror_movesBetweenWalletParties() {
    // Ví: account 100 chuyển 50,000 cho account 200.
    bridge.mirror(payload(1L, 1001L, 50_000L, 100, 200));

    // GL: 2110 subledger — payer(100) giảm, payee(200) tăng.
    assertEquals(-50_000L, ledger.walletBalance(100), "payer ví giảm");
    assertEquals( 50_000L, ledger.walletBalance(200), "payee ví tăng");
    // Tổng 2110 không đổi (chuyển nội bộ)
    assertEquals(0L, ledger.getBalance("2110"), "control account 2110 net 0");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void mirror_postsBalancedJournal() {
    bridge.mirror(payload(1L, 2002L, 30_000L, 100, 200));
    var t = ledger.findTransByRefId(WalletGlBridge.ref(1L, 2002L));
    assertNotNull(t, "bút toán GL được tạo theo ref WAL:1:2002");
    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size());
    assertEquals(30_000L, t.debitTotal());
  }

  @Test
  void mirror_idempotent_sameMidRequest() {
    bridge.mirror(payload(1L, 3003L, 40_000L, 100, 200));
    bridge.mirror(payload(1L, 3003L, 40_000L, 100, 200)); // gọi lại (replay) — an toàn
    assertEquals(-40_000L, ledger.walletBalance(100), "không mirror trùng");
    assertEquals( 40_000L, ledger.walletBalance(200));
  }

  // ── Reconciliation: Σ ví party = control 2110 ──────────────────────────────────

  @Test
  void reconciliation_sumOfPartiesEqualsControl() {
    bridge.mirror(payload(1L, 1L, 100_000L, 100, 200)); // 100→200
    bridge.mirror(payload(1L, 2L,  30_000L, 200, 300)); // 200→300
    bridge.mirror(payload(1L, 3L,  20_000L, 100, 300)); // 100→300

    long p100 = ledger.walletBalance(100); // -120k
    long p200 = ledger.walletBalance(200); // +70k
    long p300 = ledger.walletBalance(300); // +50k
    assertEquals(-120_000L, p100);
    assertEquals(  70_000L, p200);
    assertEquals(  50_000L, p300);
    // Σ = 0 = số dư control 2110
    assertEquals(-ledger.getBalance("2110"), p100 + p200 + p300, "Σ ví party = natural(2110)");
    assertEquals(0L, p100 + p200 + p300, "chuyển nội bộ → tổng 0");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  // ── Ví vận hành + topup COA cùng tồn tại trên một control account ───────────────

  @Test
  void coexists_withCoaTopup_onSameControlAccount() {
    // COA topup nạp 200k cho user 100 (tiền vào hệ thống)
    ledger.receiveTopUp(new TopUpReceiveCmd(200_000L, "TU-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(200_000L, 0L, "TU-C", null, 100L));
    // Ví vận hành: user 100 chuyển 50k cho 200
    bridge.mirror(payload(1L, 1L, 50_000L, 100, 200));

    assertEquals(150_000L, ledger.walletBalance(100), "200k nạp − 50k chuyển");
    assertEquals( 50_000L, ledger.walletBalance(200));
    // Control 2110 = 200k (chỉ topup đưa tiền vào; chuyển nội bộ không đổi tổng)
    assertEquals(-200_000L, ledger.getBalance("2110"));
    assertEquals(200_000L, ledger.walletBalance(100) + ledger.walletBalance(200),
        "Σ ví = natural(2110) — đối soát khớp");
    assertTrue(ledger.isDoubleEntryBalanced());
  }
}
