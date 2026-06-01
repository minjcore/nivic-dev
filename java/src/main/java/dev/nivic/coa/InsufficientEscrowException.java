package dev.nivic.coa;

public final class InsufficientEscrowException extends RuntimeException {

  public InsufficientEscrowException(String accountCode, long balanceMinor, long requested) {
    super("insufficient escrow balance: account=" + accountCode
        + " balance=" + balanceMinor + " requested=" + requested);
  }
}
