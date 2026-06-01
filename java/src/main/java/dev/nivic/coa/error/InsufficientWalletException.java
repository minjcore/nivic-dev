package dev.nivic.coa.error;

public final class InsufficientWalletException extends RuntimeException {

  public InsufficientWalletException(String accountCode, long balanceMinor, long requested) {
    super("insufficient wallet balance: account=" + accountCode
        + " balance=" + balanceMinor + " requested=" + requested);
  }
}
