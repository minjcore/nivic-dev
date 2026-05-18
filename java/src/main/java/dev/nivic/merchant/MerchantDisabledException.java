package dev.nivic.merchant;

/** Thrown when {@link MerchantConfig#enabled()} is false for the request {@code mid}. */
public final class MerchantDisabledException extends RuntimeException {

  private final long mid;

  public MerchantDisabledException(long mid) {
    super("merchant disabled: mid=" + Long.toUnsignedString(mid));
    this.mid = mid;
  }

  public long mid() {
    return mid;
  }
}
