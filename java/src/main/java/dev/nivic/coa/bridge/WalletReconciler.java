package dev.nivic.coa.bridge;

import dev.nivic.coa.FundFlowLedger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Job đối soát ví vận hành (Sevlet {@code led_wallet}) ↔ sổ cái COA.
 *
 * <p>Ví vận hành là nguồn sự thật. Mirror sang GL là async + có thể fail. Job này tìm các bản ghi
 * {@code led_wallet} chưa có bút toán mirror tương ứng ({@code coa_trans.ref_id = 'WAL:mid:req'}),
 * báo cáo và (tuỳ chọn) tự sửa bằng cách re-mirror — idempotent nên an toàn.</p>
 *
 * <p>Chạy định kỳ (cron) hoặc thủ công sau sự cố. Bất biến mục tiêu:
 * mọi {@code led_wallet} đều có mirror; do đó Σ walletBalance(party) = natural(2110).</p>
 */
public final class WalletReconciler {

  /** led_wallet không có mirror trên coa_trans (LEFT JOIN theo ref). */
  private static final String SELECT_MISSING =
      "SELECT w.mid, w.request_id, w.debit, w.credit, w.amount_minor"
          + " FROM led_wallet w"
          + " LEFT JOIN coa_trans t ON t.ref_id = 'WAL:' || w.mid || ':' || w.request_id"
          + " WHERE t.id IS NULL AND w.debit <> w.credit AND w.amount_minor > 0"
          + " ORDER BY w.created_at";

  private static final String COUNT_OPERATIONAL =
      "SELECT COUNT(*) FROM led_wallet WHERE debit <> credit AND amount_minor > 0";

  private final DataSource dataSource;
  private final FundFlowLedger gl;

  public WalletReconciler(DataSource dataSource, FundFlowLedger gl) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.gl = Objects.requireNonNull(gl, "gl");
  }

  /**
   * Đối soát. {@code repair=true} → re-mirror các bản ghi thiếu (idempotent).
   */
  public ReconciliationReport reconcile(boolean repair) {
    List<ReconciliationReport.Missing> missing = new ArrayList<>();
    long operational;
    try (Connection c = dataSource.getConnection()) {
      try (PreparedStatement ps = c.prepareStatement(COUNT_OPERATIONAL);
          ResultSet rs = ps.executeQuery()) {
        rs.next();
        operational = rs.getLong(1);
      }
      try (PreparedStatement ps = c.prepareStatement(SELECT_MISSING);
          ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          missing.add(new ReconciliationReport.Missing(
              rs.getLong("mid"), rs.getLong("request_id"),
              rs.getInt("debit"), rs.getInt("credit"), rs.getLong("amount_minor")));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("reconcile read failed", e);
    }

    long repaired = 0;
    if (repair) {
      for (ReconciliationReport.Missing m : missing) {
        gl.mirrorWalletTransfer(m.payer(), m.payee(), m.amount(), m.ref(),
            "Đối soát: phản chiếu bù mid=" + m.mid() + " req=" + m.requestId());
        repaired++;
      }
    }
    long mirrored = operational - missing.size();
    return new ReconciliationReport(operational, mirrored, repaired, missing);
  }
}
