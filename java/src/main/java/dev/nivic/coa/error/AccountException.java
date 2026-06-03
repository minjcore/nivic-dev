package dev.nivic.coa.error;

public class AccountException extends RuntimeException {
  public AccountException(String message) {
    super(message);
  }

  public AccountException(String message, Throwable cause) {
    super(message, cause);
  }
}

class AccountNotFoundException extends AccountException {
  public AccountNotFoundException(String code) {
    super("Account not found: " + code);
  }
}

class AccountAlreadyExistsException extends AccountException {
  public AccountAlreadyExistsException(String code) {
    super("Account already exists: " + code);
  }
}

class AccountActiveException extends AccountException {
  public AccountActiveException(String code) {
    super("Account is already active: " + code);
  }
}

class AccountInactiveException extends AccountException {
  public AccountInactiveException(String code) {
    super("Account is inactive: " + code);
  }
}

class AccountHasDescendantsException extends AccountException {
  public AccountHasDescendantsException(String code) {
    super("Cannot deactivate account with child accounts: " + code);
  }
}

class AccountHasBalanceException extends AccountException {
  public AccountHasBalanceException(String code, long balance) {
    super("Cannot archive account with non-zero balance: " + code + " (balance=" + balance + ")");
  }
}

class PeriodClosedException extends AccountException {
  public PeriodClosedException(java.time.LocalDate periodEnd) {
    super("Posting not allowed in closed period: " + periodEnd);
  }
}

class InvalidAccountHierarchyException extends AccountException {
  public InvalidAccountHierarchyException(String message) {
    super("Invalid account hierarchy: " + message);
  }
}
