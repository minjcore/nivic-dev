package dev.nivic.coa.error;

public final class ProposalNotFoundException extends RuntimeException {
  public ProposalNotFoundException(Object id) {
    super("proposal not found: " + id);
  }
}
