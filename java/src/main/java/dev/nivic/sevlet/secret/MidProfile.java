package dev.nivic.sevlet.secret;

import dev.nivic.merchant.MerchantConfig;
import java.util.Objects;

/**
 * Secret, per-{@code mid} policy, and optional {@link MerchantConfig} from {@link
 * MidSecretResolver#requireProfile(long)}.
 *
 * <p>{@link #orderPaymentMode()} maps to {@code wallet_mid_secret.payment_check_order}: when {@code
 * true}, the accept path is <strong>order payment</strong> only — idempotency stores/compares {@code
 * order_id}, and the response is written to the WAL <strong>without</strong> ledger / journal
 * (transaction is applied later, e.g. WAL replay). When {@code false}, WAL and ledger/journal run in
 * one step.</p>
 */
public record MidProfile(byte[] secretKey, boolean orderPaymentMode, MerchantConfig merchantConfig) {

  public MidProfile {
    Objects.requireNonNull(secretKey, "secretKey");
    Objects.requireNonNull(merchantConfig, "merchantConfig");
  }
}
