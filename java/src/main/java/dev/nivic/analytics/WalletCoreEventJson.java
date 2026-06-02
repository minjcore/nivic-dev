package dev.nivic.analytics;

import java.time.Instant;

/** Serializes {@link WalletCoreEvent} to contract JSON (one object, no pretty-print). */
public final class WalletCoreEventJson {

  private WalletCoreEventJson() {}

  public static String toLine(WalletCoreEvent event) {
    WalletCoreEventPayload p = event.payload();
    StringBuilder sb = new StringBuilder(256);
    sb.append('{');
    sb.append("\"schema_version\":").append(event.schemaVersion()).append(',');
    sb.append("\"event_type\":").append(JsonEscapes.quote(event.eventType())).append(',');
    sb.append("\"occurred_at\":").append(JsonEscapes.quote(event.occurredAt().toString())).append(',');
    sb.append("\"payload\":{");
    sb.append("\"mid\":").append(JsonEscapes.quote(p.mid())).append(',');
    sb.append("\"request_id\":").append(JsonEscapes.quote(p.requestId())).append(',');
    sb.append("\"order_id\":").append(JsonEscapes.quote(p.orderId())).append(',');
    sb.append("\"command\":").append(JsonEscapes.quote(p.command())).append(',');
    sb.append("\"input_command\":").append(JsonEscapes.quote(p.inputCommand())).append(',');
    sb.append("\"amount\":").append(JsonEscapes.quote(p.amount())).append(',');
    sb.append("\"debit\":").append(JsonEscapes.quote(p.debit())).append(',');
    sb.append("\"credit\":").append(JsonEscapes.quote(p.credit())).append(',');
    sb.append("\"extra_data\":").append(JsonEscapes.quote(p.extraDataBase64())).append(',');
    sb.append("\"sig\":").append(JsonEscapes.quote(p.sigHex()));
    p.currencyCode().ifPresent(c -> sb.append(",\"currency_code\":").append(JsonEscapes.quote(c)));
    p.rawBodySha256().ifPresent(h -> sb.append(",\"raw_body_sha256\":").append(JsonEscapes.quote(h)));
    p.walSequence().ifPresent(s -> sb.append(",\"wal_sequence\":").append(JsonEscapes.quote(s)));
    sb.append("}}");
    return sb.toString();
  }

  /** Uses {@link Instant#now()} for {@code occurred_at}. */
  public static String fromPayload(WalletCoreEventPayload payload) {
    return toLine(
        new WalletCoreEvent(
            WalletCoreEvent.SCHEMA_V1,
            WalletCoreEvent.TYPE_PAYLOAD_ACCEPTED,
            Instant.now(),
            payload));
  }
}
