package dev.nivic.payment;

import java.sql.Connection;
import java.sql.SQLException;

/** Optional soft-hold on debit account for order intents. */
public interface AccountHoldStore {

  void placeHold(Connection c, int debitAccount, long amountMinor, long mid, long requestId)
      throws SQLException;

  void releaseHold(Connection c, long mid, long requestId) throws SQLException;
}
