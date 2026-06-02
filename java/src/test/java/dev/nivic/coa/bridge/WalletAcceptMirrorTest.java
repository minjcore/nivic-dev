package dev.nivic.coa.bridge;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nivic.coa.JdbcFundFlowLedger;
import dev.nivic.command.WalletInputOp;
import dev.nivic.journal.MemoryWalletJournal;
import dev.nivic.ledger.MemoryPaymentLedger;
import dev.nivic.ledger.MemoryWalletLedger;
import dev.nivic.payment.LedgerService;
import dev.nivic.payment.WalService;
import dev.nivic.payment.WalletAcceptService;
import dev.nivic.sevlet.SevletWalletPayload;
import dev.nivic.sevlet.idempotency.MemoryIdempotencyGate;
import dev.nivic.wal.SimpleWalLog;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Currency;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end: accept một payload ví (đường vận hành Sevlet) → hook async tự phản chiếu sang sổ cái
 * COA qua {@link WalletGlBridge}. Test dùng executor đồng bộ để xác định kết quả tất định.
 */
@Testcontainers
@Tag("integration")
class WalletAcceptMirrorTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private static final Currency VND = Currency.getInstance("VND");

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

  private SevletWalletPayload transfer(long mid, long req, long amount, int debit, int credit) {
    return new SevletWalletPayload(
        WalletInputOp.TRANSFER, mid, req, 0L, amount, debit, credit, new byte[0], new byte[32]);
  }

  @Test
  void acceptedTransfer_autoMirrorsToGl(@TempDir Path tmp) throws Exception {
    // GL + bridge
    JdbcFundFlowLedger gl = new JdbcFundFlowLedger(ds);
    cleanGl(gl);
    WalletGlBridge bridge = new WalletGlBridge(gl);

    // Đường vận hành (in-memory) + WAL tạm
    var idempotency = new MemoryIdempotencyGate();
    var walletLedger = new MemoryWalletLedger();
    var journal = new MemoryWalletJournal();
    var ledgerService = new LedgerService(walletLedger, journal);
    var paymentLedger = new MemoryPaymentLedger();
    var wal = new WalService(new SimpleWalLog(tmp.resolve("t.wal")), null);

    try (var accept = new WalletAcceptService(
        idempotency, wal, ledgerService, paymentLedger, VND, null, null, 15)) {
      // Nối hook: accept thành công → mirror sang GL (executor đồng bộ cho test)
      accept.setAcceptSink(bridge::mirror, Runnable::run);

      // Accept một chuyển khoản: 100 → 200, 50,000
      accept.claimAndPersist(new byte[]{1, 2, 3}, transfer(1L, 1L, 50_000L, 100, 200));

      // GL tự phản chiếu (không cần gọi tay)
      assertEquals(-50_000L, gl.walletBalance(100), "payer ví giảm trên GL");
      assertEquals( 50_000L, gl.walletBalance(200), "payee ví tăng trên GL");
      assertEquals(0L, gl.getBalance("2110"), "tổng 2110 net 0");
      assertTrue(gl.isDoubleEntryBalanced());

      // Idempotency đường vận hành: cùng (mid,req) lần 2 → duplicate, KHÔNG mirror lại
      var dup = accept.claimAndPersist(new byte[]{1, 2, 3}, transfer(1L, 1L, 50_000L, 100, 200));
      assertTrue(dup.isDuplicate());
      assertEquals(-50_000L, gl.walletBalance(100), "không mirror trùng");
    }
  }

  @Test
  void noSink_noMirror_backwardCompatible(@TempDir Path tmp) throws Exception {
    JdbcFundFlowLedger gl = new JdbcFundFlowLedger(ds);
    cleanGl(gl);
    var accept = new WalletAcceptService(
        new MemoryIdempotencyGate(),
        new WalService(new SimpleWalLog(tmp.resolve("t2.wal")), null),
        new LedgerService(new MemoryWalletLedger(), new MemoryWalletJournal()),
        new MemoryPaymentLedger(), VND, null, null, 15);
    // KHÔNG set sink → không mirror (backward compatible)
    accept.claimAndPersist(new byte[]{9}, transfer(2L, 5L, 10_000L, 300, 400));
    accept.close();

    assertEquals(0L, gl.walletBalance(300), "không có sink → GL không đổi");
    assertEquals(0L, gl.walletBalance(400));
  }

  private void cleanGl(JdbcFundFlowLedger gl) throws SQLException {
    gl.isDoubleEntryBalanced(); // ensure schema tồn tại trước khi truncate
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }
}
