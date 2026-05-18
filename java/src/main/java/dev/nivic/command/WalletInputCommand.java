package dev.nivic.command;

import dev.nivic.sevlet.SevletWalletPayload;
import java.util.Objects;

/**
 * Typed command after decode, keyed by wire {@link SevletWalletPayload#command()} (unsigned u64 in
 * {@code long}). Use for servlets, WAL batch replay, and workers without re-switching on raw
 * payloads.
 */
public sealed interface WalletInputCommand permits TransferCommand, GenericInputCommand {

  /** Same as {@link SevletWalletPayload#command()} on {@link #payload()}. */
  long wireCommand();

  SevletWalletPayload payload();

  /** Short label for logs and debug JSON. */
  default String debugKind() {
    return switch (this) {
      case TransferCommand c -> "TRANSFER";
      case GenericInputCommand g -> "GENERIC";
    };
  }
}
