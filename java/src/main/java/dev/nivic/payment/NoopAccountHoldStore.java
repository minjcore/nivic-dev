package dev.nivic.payment;

import java.sql.Connection;
import java.sql.SQLException;

public enum NoopAccountHoldStore implements AccountHoldStore {
  INSTANCE;

  @Override
  public void placeHold(
      Connection c, int debitAccount, long amountMinor, long mid, long requestId) {}

  @Override
  public void releaseHold(Connection c, long mid, long requestId) {}
}
