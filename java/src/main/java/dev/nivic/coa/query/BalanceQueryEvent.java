package dev.nivic.coa.query;

import java.util.concurrent.CompletableFuture;

/**
 * Disruptor event for balance queries.
 * One event per query request; handler batches multiple events for efficient DB access.
 */
public class BalanceQueryEvent {

  public enum QueryType {
    SINGLE_ACCOUNT,     // Query one account balance
    USER_WALLET,        // Query user wallet balance (subsidiary ledger of 2110)
    MERCHANT_WALLET,    // Query merchant wallet balance (2120)
    SAVINGS_WALLET,     // Query savings balance (2140)
    MULTI_ACCOUNT       // Query multiple accounts
  }

  public QueryType type;
  public String[] accountCodes;    // For SINGLE_ACCOUNT or MULTI_ACCOUNT
  public long mid;                 // For USER_WALLET, MERCHANT_WALLET, SAVINGS_WALLET
  public CompletableFuture<BalanceQueryResult> future;

  // Result: populated by event handler before publishing
  public BalanceQueryResult result;

  public void reset() {
    type = null;
    accountCodes = null;
    mid = 0;
    future = null;
    result = null;
  }

  @Override
  public String toString() {
    return "BalanceQueryEvent{" +
        "type=" + type +
        ", accountCodes=" + (accountCodes != null ? accountCodes.length : 0) +
        ", mid=" + mid +
        '}';
  }
}
