package dev.nivic.sevlet;

/** Thrown when the binary body exceeds the configured maximum size. */
public final class BodyTooLargeException extends RuntimeException {
  public BodyTooLargeException(String message) {
    super(message);
  }
}
