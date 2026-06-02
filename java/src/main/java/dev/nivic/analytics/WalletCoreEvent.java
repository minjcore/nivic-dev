package dev.nivic.analytics;

import java.time.Instant;
import java.util.Objects;

/** Downstream analytics envelope — see {@code docs/downstream-event-contract.md}. */
public record WalletCoreEvent(
    int schemaVersion,
    String eventType,
    Instant occurredAt,
    WalletCoreEventPayload payload) {

  public static final int SCHEMA_V1 = 1;
  public static final String TYPE_PAYLOAD_ACCEPTED = "wallet.payload_accepted";

  public WalletCoreEvent {
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(payload, "payload");
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("schemaVersion must be >= 1");
    }
  }
}
