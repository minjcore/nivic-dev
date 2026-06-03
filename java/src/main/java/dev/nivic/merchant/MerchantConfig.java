package dev.nivic.merchant;

import java.util.Objects;
import java.util.Optional;

/**
 * Per-merchant tuning loaded with {@link dev.nivic.sevlet.secret.MidProfile} from {@code
 * merchant_config} (optional row; defaults apply when absent).
 */
public record MerchantConfig(
    boolean enabled,
    Optional<Integer> intentTtlMinutes,
    Optional<String> displayName) {

  public MerchantConfig {
    Objects.requireNonNull(intentTtlMinutes, "intentTtlMinutes");
    Objects.requireNonNull(displayName, "displayName");
  }

  public static MerchantConfig defaults() {
    return new MerchantConfig(true, Optional.empty(), Optional.empty());
  }

  /** Row from {@code merchant_config} joined on {@code mid}; null JDBC cells → defaults. */
  public static MerchantConfig fromNullableRow(Boolean enabled, Integer intentTtlMinutes, String displayName) {
    boolean en = enabled == null || enabled.booleanValue();
    return new MerchantConfig(
        en, Optional.ofNullable(intentTtlMinutes), Optional.ofNullable(displayName));
  }
}
