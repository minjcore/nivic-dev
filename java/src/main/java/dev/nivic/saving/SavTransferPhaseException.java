package dev.nivic.saving;

public final class SavTransferPhaseException extends RuntimeException {

  public SavTransferPhaseException(long transferId, SavTransferPhase expected, SavTransferPhase actual) {
    super(
        "transfer phase mismatch: id="
            + transferId
            + " expected="
            + expected
            + " actual="
            + actual);
  }

  /** Transfer is already settled (POSTED or VOIDED). */
  public SavTransferPhaseException(long transferId) {
    super("transfer already settled: id=" + transferId);
  }
}
