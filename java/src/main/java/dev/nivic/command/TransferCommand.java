package dev.nivic.command;

import dev.nivic.sevlet.SevletWalletPayload;
import java.util.Objects;

/**
 * {@code command ==} {@link WalletInputOp#TRANSFER} or {@link WalletInputOp#REVERSAL}: debit/credit
 * amount movement (same journal shape; reversal uses distinct opcode for audit).
 */
public record TransferCommand(SevletWalletPayload payload) implements WalletInputCommand {

  public TransferCommand {
    Objects.requireNonNull(payload, "payload");
    long c = payload.command();
    if (c != WalletInputOp.TRANSFER && c != WalletInputOp.REVERSAL) {
      throw new IllegalArgumentException(
          "transfer/reversal requires command TRANSFER or REVERSAL, was "
              + Long.toUnsignedString(c));
    }
  }

  @Override
  public long wireCommand() {
    return payload.command();
  }
}
