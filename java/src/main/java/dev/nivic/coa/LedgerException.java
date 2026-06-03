package dev.nivic.coa;

public class LedgerException extends Exception {
  public LedgerException(String message) {
    super(message);
  }

  public LedgerException(String message, Throwable cause) {
    super(message, cause);
  }
}
