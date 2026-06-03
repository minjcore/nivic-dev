package dev.nivic.command;

/** Wire {@code command} (u64) opcodes; authenticated (included in HMAC from {@code command} onward). */
public final class WalletInputOp {

  private WalletInputOp() {}

  /** Default signed wallet movement ({@link TransferCommand}). */
  public static final long TRANSFER = 0L;

  /** Second-phase settle for order-payment intents (challenge in {@code extraData}). */
  public static final long CONFIRM_PAYMENT = 1L;

  /** Cancel an intent with matching challenge. */
  public static final long REJECT_PAYMENT = 2L;

  /** Same persistence path as transfer; distinct opcode for audit / reconciliation. */
  public static final long REVERSAL = 3L;
}
