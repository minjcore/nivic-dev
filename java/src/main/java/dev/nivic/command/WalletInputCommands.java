package dev.nivic.command;

import dev.nivic.sevlet.SevletWalletPayload;
import java.util.Objects;

/** Builds {@link WalletInputCommand} from a decoded {@link SevletWalletPayload}. */
public final class WalletInputCommands {

  private WalletInputCommands() {}

  public static WalletInputCommand from(SevletWalletPayload payload) {
    Objects.requireNonNull(payload, "payload");
    if (payload.command() == WalletInputOp.TRANSFER
        || payload.command() == WalletInputOp.REVERSAL) {
      return new TransferCommand(payload);
    }
    return new GenericInputCommand(payload.command(), payload);
  }
}
