package dev.nivic.coa.query;

import com.lmax.disruptor.EventHandler;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Event handler for Disruptor: processes balance query events.
 * Batches queries for efficient database access (one round-trip per batch).
 */
public class BalanceQueryHandler implements EventHandler<BalanceQueryEvent> {

  private final String dataSourceUrl;
  private final String dbUser;
  private final String dbPassword;

  // Batch accumulator: collects events before DB query
  private final List<BalanceQueryEvent> batch = new ArrayList<>();
  private final int batchSize;

  public BalanceQueryHandler(String dataSourceUrl, String dbUser, String dbPassword, int batchSize) {
    this.dataSourceUrl = dataSourceUrl;
    this.dbUser = dbUser;
    this.dbPassword = dbPassword;
    this.batchSize = batchSize;
  }

  @Override
  public void onEvent(BalanceQueryEvent event, long sequence, boolean endOfBatch) {
    // Accumulate event
    batch.add(event);

    // Process batch if full or at end of batch
    if (batch.size() >= batchSize || endOfBatch) {
      processBatch();
    }
  }

  /**
   * Process all accumulated events in a single database round-trip.
   * Groups queries by type for efficient execution.
   */
  private void processBatch() {
    if (batch.isEmpty()) {
      return;
    }

    // Group by query type
    Map<BalanceQueryEvent.QueryType, List<BalanceQueryEvent>> grouped =
        batch.stream().collect(Collectors.groupingBy(e -> e.type));

    long startNanos = System.nanoTime();

    // Execute each group
    try (Connection conn = getConnection()) {
      for (var entry : grouped.entrySet()) {
        processGroup(entry.getKey(), entry.getValue(), conn);
      }
    } catch (SQLException e) {
      // On error, complete all futures with exception
      for (BalanceQueryEvent event : batch) {
        if (event.future != null) {
          event.future.completeExceptionally(
              new RuntimeException("Balance query failed", e));
        }
      }
    }

    // Complete all futures
    long elapsedNanos = System.nanoTime() - startNanos;
    for (BalanceQueryEvent event : batch) {
      if (event.result != null && event.future != null) {
        // Update result with actual query time
        BalanceQueryResult timedResult = new BalanceQueryResult(
            event.result.balances(),
            elapsedNanos / Math.max(1, batch.size()),  // Distribute elapsed time
            event.result.timestamp()
        );
        event.future.complete(timedResult);
      }
    }

    batch.clear();
  }

  /**
   * Process one group of events (same query type).
   */
  private void processGroup(
      BalanceQueryEvent.QueryType type,
      List<BalanceQueryEvent> events,
      Connection conn) throws SQLException {

    switch (type) {
      case SINGLE_ACCOUNT -> processSingleAccountQueries(events, conn);
      case USER_WALLET -> processUserWalletQueries(events, conn);
      case MERCHANT_WALLET -> processMerchantWalletQueries(events, conn);
      case SAVINGS_WALLET -> processSavingsWalletQueries(events, conn);
      case MULTI_ACCOUNT -> processMultiAccountQueries(events, conn);
    }
  }

  /**
   * Single account balance queries: fetch all requested accounts in one batch.
   */
  private void processSingleAccountQueries(List<BalanceQueryEvent> events, Connection conn)
      throws SQLException {

    // Collect all requested account codes
    Set<String> codes = new HashSet<>();
    for (BalanceQueryEvent event : events) {
      if (event.accountCodes != null) {
        for (String code : event.accountCodes) {
          codes.add(code);
        }
      }
    }

    if (codes.isEmpty()) {
      return;
    }

    // Fetch all accounts in one query
    Map<String, Long> balances = fetchAccountBalances(conn, codes);

    // Assign results
    for (BalanceQueryEvent event : events) {
      Map<String, Long> result = new HashMap<>();
      if (event.accountCodes != null) {
        for (String code : event.accountCodes) {
          result.put(code, balances.getOrDefault(code, 0L));
        }
      }
      event.result = new BalanceQueryResult(
          result,
          0,  // Will be updated with actual elapsed time
          System.currentTimeMillis()
      );
    }
  }

  /**
   * User wallet balance queries: sum credits − debits on account 2110 per user.
   */
  private void processUserWalletQueries(List<BalanceQueryEvent> events, Connection conn)
      throws SQLException {

    String sql = """
        SELECT party_mid, SUM(CASE WHEN credit_minor > 0 THEN credit_minor - debit_minor ELSE 0 END)
        FROM coa_trans_data
        WHERE account_code = '2110' AND party_mid IN (?)
        GROUP BY party_mid
        """;

    // Collect all mids
    List<Long> mids = events.stream().map(e -> e.mid).distinct().toList();

    // Execute query
    Map<Long, Long> midToBalance = new HashMap<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      // Note: this is simplified; real implementation needs to handle array binding
      // For now, execute per-mid (can optimize with UNNEST in PostgreSQL)
      for (long mid : mids) {
        String perMidSql = """
            SELECT SUM(CASE WHEN credit_minor > 0 THEN credit_minor - debit_minor ELSE 0 END)
            FROM coa_trans_data
            WHERE account_code = '2110' AND party_mid = ?
            """;
        try (PreparedStatement psMid = conn.prepareStatement(perMidSql)) {
          psMid.setLong(1, mid);
          try (ResultSet rs = psMid.executeQuery()) {
            if (rs.next()) {
              midToBalance.put(mid, rs.getLong(1));
            }
          }
        }
      }
    }

    // Assign results
    for (BalanceQueryEvent event : events) {
      long balance = midToBalance.getOrDefault(event.mid, 0L);
      event.result = new BalanceQueryResult(
          Map.of("2110:" + event.mid, balance),
          0,
          System.currentTimeMillis()
      );
    }
  }

  /**
   * Merchant wallet balance queries: sum credits − debits on account 2120 per merchant.
   */
  private void processMerchantWalletQueries(List<BalanceQueryEvent> events, Connection conn)
      throws SQLException {

    // Similar to user wallet but account 2120
    Map<Long, Long> midToBalance = new HashMap<>();
    for (long mid : events.stream().map(e -> e.mid).distinct().toList()) {
      String sql = """
          SELECT SUM(CASE WHEN credit_minor > 0 THEN credit_minor - debit_minor ELSE 0 END)
          FROM coa_trans_data
          WHERE account_code = '2120' AND party_mid = ?
          """;
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setLong(1, mid);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            midToBalance.put(mid, rs.getLong(1));
          }
        }
      }
    }

    for (BalanceQueryEvent event : events) {
      long balance = midToBalance.getOrDefault(event.mid, 0L);
      event.result = new BalanceQueryResult(
          Map.of("2120:" + event.mid, balance),
          0,
          System.currentTimeMillis()
      );
    }
  }

  /**
   * Savings balance queries: sum on account 2140 per user.
   */
  private void processSavingsWalletQueries(List<BalanceQueryEvent> events, Connection conn)
      throws SQLException {

    Map<Long, Long> midToBalance = new HashMap<>();
    for (long mid : events.stream().map(e -> e.mid).distinct().toList()) {
      String sql = """
          SELECT SUM(CASE WHEN credit_minor > 0 THEN credit_minor - debit_minor ELSE 0 END)
          FROM coa_trans_data
          WHERE account_code = '2140' AND party_mid = ?
          """;
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setLong(1, mid);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            midToBalance.put(mid, rs.getLong(1));
          }
        }
      }
    }

    for (BalanceQueryEvent event : events) {
      long balance = midToBalance.getOrDefault(event.mid, 0L);
      event.result = new BalanceQueryResult(
          Map.of("2140:" + event.mid, balance),
          0,
          System.currentTimeMillis()
      );
    }
  }

  /**
   * Multi-account queries: flexible combination of accounts.
   */
  private void processMultiAccountQueries(List<BalanceQueryEvent> events, Connection conn)
      throws SQLException {

    // Collect all codes
    Set<String> allCodes = new HashSet<>();
    for (BalanceQueryEvent event : events) {
      if (event.accountCodes != null) {
        allCodes.addAll(Arrays.asList(event.accountCodes));
      }
    }

    // Fetch all
    Map<String, Long> balances = fetchAccountBalances(conn, allCodes);

    // Assign
    for (BalanceQueryEvent event : events) {
      Map<String, Long> result = new HashMap<>();
      if (event.accountCodes != null) {
        for (String code : event.accountCodes) {
          result.put(code, balances.getOrDefault(code, 0L));
        }
      }
      event.result = new BalanceQueryResult(
          result,
          0,
          System.currentTimeMillis()
      );
    }
  }

  /**
   * Fetch account balances from coa_account table.
   */
  private Map<String, Long> fetchAccountBalances(Connection conn, Set<String> codes)
      throws SQLException {

    Map<String, Long> result = new HashMap<>();

    if (codes.isEmpty()) {
      return result;
    }

    // Build dynamic SQL for multiple codes
    String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
    String sql = "SELECT code, balance_minor FROM coa_account WHERE code IN (" + placeholders + ")";

    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int idx = 1;
      for (String code : codes) {
        ps.setString(idx++, code);
      }

      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.put(rs.getString("code"), rs.getLong("balance_minor"));
        }
      }
    }

    return result;
  }

  private Connection getConnection() throws SQLException {
    return DriverManager.getConnection(dataSourceUrl, dbUser, dbPassword);
  }
}
