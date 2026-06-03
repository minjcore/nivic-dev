package dev.nivic.command;

import dev.nivic.sevlet.SevletWalletPayload;
import java.util.Objects;

/** Any wire {@code command} value not mapped to a dedicated command type yet. */
public record GenericInputCommand(long wireCommand, SevletWalletPayload payload)
    implements WalletInputCommand {

  public GenericInputCommand {
    Objects.requireNonNull(payload, "payload");
    if (payload.command() != wireCommand) {
      throw new IllegalArgumentException(
          "wireCommand "
              + Long.toUnsignedString(wireCommand)
              + " != payload.command "
              + Long.toUnsignedString(payload.command()));
    }
  }
}
