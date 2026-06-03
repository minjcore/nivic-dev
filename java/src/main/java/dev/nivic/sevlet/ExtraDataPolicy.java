package dev.nivic.sevlet;

import java.util.Objects;

/**
 * Policy for {@link SevletWalletPayload#extraData()}: maximum size and optional wire profiles.
 *
 * <p><b>Profile 0 (opaque):</b> entire {@code extraData} is application-defined. Core does not parse
 * it except command-specific parsers (e.g. {@link dev.nivic.payment.ConfirmPayloadParser} for
 * CONFIRM/REJECT).</p>
 *
 * <p><b>Future profiles:</b> a leading version or TLV header may be introduced; bytes after a
 * command-specific fixed prefix may carry TLV extensions (see confirm profile v0).</p>
 */
public final class ExtraDataPolicy {

  /** Default cap on {@code extraData} length (256 KiB), independent of {@code maxBodyBytes}. */
  public static final int DEFAULT_MAX_EXTRA_DATA_BYTES = 262_144;

  /**
   * Wire profile: application opaque blob (default). No structural prefix required on generic
   * TRANSFER / intent posts.
   */
  public static final byte PROFILE_OPAQUE = 0;

  private ExtraDataPolicy() {}

  /**
   * @throws IllegalArgumentException if {@code extraData.length > maxBytes}
   */
  public static void validateLength(byte[] extraData, int maxBytes) {
    Objects.requireNonNull(extraData, "extraData");
    if (maxBytes < 0) {
      throw new IllegalArgumentException("maxBytes must be >= 0");
    }
    if (extraData.length > maxBytes) {
      throw new IllegalArgumentException(
          "extraData length "
              + extraData.length
              + " exceeds maxExtraDataBytes "
              + maxBytes
              + " (see ExtraDataPolicy / servlet init maxExtraDataBytes)");
    }
  }
}
