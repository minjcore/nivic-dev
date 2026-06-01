package dev.nivic.saving;

import java.util.UUID;

public final class SavTransferPhaseException extends RuntimeException {

  public SavTransferPhaseException(UUID transferId, SavTransferPhase expected, SavTransferPhase actual) {
    super(
        "transfer phase mismatch: id="
            + transferId
            + " expected="
            + expected
            + " actual="
            + actual);
  }

  /** Transfer is already settled (POSTED or VOIDED). */
  public SavTransferPhaseException(UUID transferId) {
    super("transfer already settled: id=" + transferId);
  }
}
