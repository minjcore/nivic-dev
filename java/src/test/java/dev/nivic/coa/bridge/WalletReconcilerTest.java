package dev.nivic.coa.bridge;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nivic.coa.JdbcFundFlowLedger;
import dev.nivic.command.WalletInputOp;
import dev.nivic.ledger.JdbcWalletLedger;
import dev.nivic.sevlet.SevletWalletPayload;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Currency;
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
 * Job đối soát ví vận hành (led_wallet) ↔ sổ cái COA: tìm mirror thiếu + tự sửa.
 */
@Testcontainers
@Tag("integration")
class WalletReconcilerTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private static final Currency VND = Currency.getInstance("VND");

  private JdbcFundFlowLedger gl;
  private JdbcWalletLedger walletLedger;
  private WalletGlBridge bridge;
  private WalletReconciler reconciler;

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
    gl = new JdbcFundFlowLedger(ds);
    walletLedger = new JdbcWalletLedger(ds);
    bridge = new WalletGlBridge(gl);
    reconciler = new WalletReconciler(ds, gl);
    gl.isDoubleEntryBalanced(); // ensure COA schema
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("TRUNCATE led_wallet");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  /** Ghi một bản ghi ví vận hành (led_wallet) — mô phỏng accept đã persist. */
  private SevletWalletPayload writeOperational(long mid, long req, long amount, int debit, int credit) {
    var p = new SevletWalletPayload(
        WalletInputOp.TRANSFER, mid, req, 0L, amount, debit, credit, new byte[0], new byte[32]);
    walletLedger.append(p, VND);
    return p;
  }

  // ── Phát hiện drift ────────────────────────────────────────────────────────────

  @Test
  void detectsMissingMirror() {
    // 2 giao dịch vận hành; chỉ mirror 1 (giả lập 1 mirror fail).
    var p1 = writeOperational(1L, 1L, 100_000L, 100, 200);
    writeOperational(1L, 2L, 50_000L, 100, 300); // KHÔNG mirror → drift
    bridge.mirror(p1);

    var r = reconciler.reconcile(false);
    assertEquals(2, r.operationalCount());
    assertEquals(1, r.mirroredCount());
    assertEquals(1, r.missingCount());
    assertFalse(r.inSync());
    var miss = r.missing().get(0);
    assertEquals(2L, miss.requestId());
    assertEquals("WAL:1:2", miss.ref());
  }

  @Test
  void inSync_whenAllMirrored() {
    var p1 = writeOperational(1L, 1L, 100_000L, 100, 200);
    var p2 = writeOperational(1L, 2L, 50_000L, 100, 300);
    bridge.mirror(p1);
    bridge.mirror(p2);

    var r = reconciler.reconcile(false);
    assertEquals(2, r.operationalCount());
    assertEquals(0, r.missingCount());
    assertTrue(r.inSync());
  }

  // ── Tự sửa (repair) ─────────────────────────────────────────────────────────────

  @Test
  void repair_remirrorsMissing() {
    var p1 = writeOperational(1L, 1L, 100_000L, 100, 200);
    writeOperational(1L, 2L, 50_000L, 100, 300); // thiếu
    bridge.mirror(p1);

    // GL trước sửa: chỉ có p1
    assertEquals(-100_000L, gl.walletBalance(100));
    assertEquals(       0L, gl.walletBalance(300));

    var r = reconciler.reconcile(true);
    assertEquals(1, r.repairedCount());

    // GL sau sửa: p2 đã mirror → 100 thêm −50k, 300 +50k
    assertEquals(-150_000L, gl.walletBalance(100), "100→200 và 100→300");
    assertEquals(  50_000L, gl.walletBalance(300));
    assertEquals(  100_000L, gl.walletBalance(200));
    assertTrue(gl.isDoubleEntryBalanced());

    // Đối soát lại → đã đồng bộ
    assertTrue(reconciler.reconcile(false).inSync());
  }

  @Test
  void repair_idempotent_safeToRerun() {
    writeOperational(1L, 1L, 100_000L, 100, 200);
    reconciler.reconcile(true); // sửa lần 1
    var r2 = reconciler.reconcile(true); // chạy lại — không có gì để sửa
    assertEquals(0, r2.repairedCount());
    assertTrue(r2.inSync());
    assertEquals(-100_000L, gl.walletBalance(100), "không mirror trùng khi repair lại");
  }

  // ── Bất biến đối soát số dư ──────────────────────────────────────────────────

  @Test
  void afterRepair_sumWalletEqualsControl() {
    writeOperational(1L, 1L, 100_000L, 100, 200);
    writeOperational(1L, 2L,  30_000L, 200, 300);
    writeOperational(1L, 3L,  20_000L, 100, 300);
    // không mirror gì cả → toàn bộ drift

    var before = reconciler.reconcile(false);
    assertEquals(3, before.missingCount());

    reconciler.reconcile(true); // sửa hết

    long sum = gl.walletBalance(100) + gl.walletBalance(200) + gl.walletBalance(300);
    assertEquals(-gl.getBalance("2110"), sum, "Σ ví party = natural(2110)");
    assertEquals(0L, sum, "chuyển nội bộ → tổng 0");
    assertTrue(gl.isDoubleEntryBalanced());
  }

  @Test
  void emptyOperational_inSync() {
    var r = reconciler.reconcile(false);
    assertEquals(0, r.operationalCount());
    assertTrue(r.inSync());
  }
}
