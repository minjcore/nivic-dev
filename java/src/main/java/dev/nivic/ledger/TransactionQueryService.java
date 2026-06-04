package dev.nivic.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class TransactionQueryService {

  private final JdbcTemplate jdbcTemplate;

  public TransactionQueryService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<TransactionSummary> findByAccount(String accountCode) {
    return jdbcTemplate.query(
        "SELECT id, ref_id, created_at FROM coa_trans WHERE id IN "
            + "(SELECT DISTINCT trans_id FROM coa_trans_data WHERE account_code = ?) "
            + "ORDER BY created_at DESC LIMIT 100",
        new Object[]{accountCode},
        (rs, rowNum) -> new TransactionSummary(
            rs.getLong("id"),
            rs.getString("ref_id"),
            rs.getTimestamp("created_at")
        )
    );
  }

  public List<TransactionSummary> findByRefId(String refPattern) {
    return jdbcTemplate.query(
        "SELECT id, ref_id, created_at FROM coa_trans WHERE ref_id LIKE ? "
            + "ORDER BY created_at DESC LIMIT 100",
        new Object[]{refPattern},
        (rs, rowNum) -> new TransactionSummary(
            rs.getLong("id"),
            rs.getString("ref_id"),
            rs.getTimestamp("created_at")
        )
    );
  }

  public TransactionDetail getTransaction(long transId) {
    List<TransactionLine> lines = jdbcTemplate.query(
        "SELECT line_no, account_code, debit_minor, credit_minor, currency_code "
            + "FROM coa_trans_data WHERE trans_id = ? ORDER BY line_no",
        new Object[]{transId},
        (rs, rowNum) -> new TransactionLine(
            rs.getInt("line_no"),
            rs.getString("account_code"),
            rs.getLong("debit_minor"),
            rs.getLong("credit_minor"),
            rs.getString("currency_code")
        )
    );

    var header = jdbcTemplate.queryForObject(
        "SELECT id, ref_id, created_at FROM coa_trans WHERE id = ?",
        new Object[]{transId},
        (rs, rowNum) -> new TransactionHeader(
            rs.getLong("id"),
            rs.getString("ref_id"),
            rs.getTimestamp("created_at")
        )
    );

    long totalDebit = lines.stream().mapToLong(l -> l.debitMinor).sum();
    long totalCredit = lines.stream().mapToLong(l -> l.creditMinor).sum();

    return new TransactionDetail(header, lines, totalDebit, totalCredit, totalDebit == totalCredit);
  }

  public record TransactionSummary(
      long id,
      String refId,
      java.sql.Timestamp createdAt
  ) {}

  public record TransactionHeader(
      long id,
      String refId,
      java.sql.Timestamp createdAt
  ) {}

  public record TransactionLine(
      int lineNo,
      String accountCode,
      long debitMinor,
      long creditMinor,
      String currencyCode
  ) {}

  public record TransactionDetail(
      TransactionHeader header,
      List<TransactionLine> lines,
      long totalDebit,
      long totalCredit,
      boolean balanced
  ) {}
}
