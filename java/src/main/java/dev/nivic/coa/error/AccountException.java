package dev.nivic.coa.error;

public class AccountException extends RuntimeException {
  public AccountException(String message) {
    super(message);
  }

  public AccountException(String message, Throwable cause) {
    super(message, cause);
  }

  public static class NotFound extends AccountException {
    public NotFound(String code) {
      super("Account not found: " + code);
    }
  }

  public static class AlreadyExists extends AccountException {
    public AlreadyExists(String code) {
      super("Account already exists: " + code);
    }
  }

  public static class ActiveException extends AccountException {
    public ActiveException(String code) {
      super("Account is already active: " + code);
    }
  }

  public static class InactiveException extends AccountException {
    public InactiveException(String code) {
      super("Account is inactive: " + code);
    }
  }

  public static class HasDescendants extends AccountException {
    public HasDescendants(String code) {
      super("Cannot deactivate account with child accounts: " + code);
    }
  }

  public static class HasBalance extends AccountException {
    public HasBalance(String code, long balance) {
      super("Cannot archive account with non-zero balance: " + code + " (balance=" + balance + ")");
    }
  }

  public static class PeriodClosed extends AccountException {
    public PeriodClosed(java.time.LocalDate periodEnd) {
      super("Posting not allowed in closed period: " + periodEnd);
    }
  }

  public static class InvalidHierarchy extends AccountException {
    public InvalidHierarchy(String message) {
      super("Invalid account hierarchy: " + message);
    }
  }
}
