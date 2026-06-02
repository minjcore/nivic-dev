package dev.nivic.analytics;

import java.util.Objects;
import java.util.Optional;

/** Business payload for {@code wallet.payload_accepted} (schema_version = 1). */
public record WalletCoreEventPayload(
    String mid,
    String requestId,
    String orderId,
    String command,
    String inputCommand,
    String amount,
    String debit,
    String credit,
    String extraDataBase64,
    String sigHex,
    Optional<String> currencyCode,
    Optional<String> rawBodySha256,
    Optional<String> walSequence) {

  public WalletCoreEventPayload {
    Objects.requireNonNull(mid, "mid");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(inputCommand, "inputCommand");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(debit, "debit");
    Objects.requireNonNull(credit, "credit");
    Objects.requireNonNull(extraDataBase64, "extraDataBase64");
    Objects.requireNonNull(sigHex, "sigHex");
    currencyCode = currencyCode == null ? Optional.empty() : currencyCode;
    rawBodySha256 = rawBodySha256 == null ? Optional.empty() : rawBodySha256;
    walSequence = walSequence == null ? Optional.empty() : walSequence;
  }
}
