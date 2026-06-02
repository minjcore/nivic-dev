package dev.nivic.analytics;

import dev.nivic.command.WalletInputCommands;
import dev.nivic.sevlet.SevletWalletCodec;
import dev.nivic.sevlet.SevletWalletPayload;
import dev.nivic.wal.SignedWalVerifier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Maps accepted servlet wire payloads to downstream analytics events. */
public final class WalletCoreEventMapper {

  private final Instant occurredAt;
  private final Optional<String> currencyCode;

  public WalletCoreEventMapper(Instant occurredAt) {
    this(occurredAt, Optional.empty());
  }

  public WalletCoreEventMapper(Instant occurredAt, Optional<String> currencyCode) {
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    this.currencyCode = currencyCode == null ? Optional.empty() : currencyCode;
  }

  public WalletCoreEvent map(byte[] rawWire, SignedWalVerifier.VerifiedRecord walRecord) {
    Objects.requireNonNull(rawWire, "rawWire");
    Objects.requireNonNull(walRecord, "walRecord");
    SevletWalletPayload p = SevletWalletCodec.decode(rawWire);
    return mapPayload(p, rawWire, walRecord);
  }

  public WalletCoreEvent mapPayload(
      SevletWalletPayload p, byte[] rawWire, SignedWalVerifier.VerifiedRecord walRecord) {
    HexFormat hx = HexFormat.of();
    String extraB64 = Base64.getEncoder().encodeToString(p.extraData());
    String inputCommand = WalletInputCommands.from(p).debugKind();
    Optional<String> walSeq =
        walRecord.signed() && walRecord.seq() >= 0
            ? Optional.of(Long.toUnsignedString(walRecord.seq()))
            : Optional.empty();
    WalletCoreEventPayload payload =
        new WalletCoreEventPayload(
            Long.toUnsignedString(p.mid()),
            Long.toUnsignedString(p.requestId()),
            Long.toUnsignedString(p.orderId()),
            Long.toUnsignedString(p.command()),
            inputCommand,
            Long.toUnsignedString(p.amount()),
            Integer.toUnsignedString(p.debit()),
            Integer.toUnsignedString(p.credit()),
            extraB64,
            hx.formatHex(p.sig()),
            currencyCode,
            Optional.of(sha256Hex(rawWire)),
            walSeq);
    return new WalletCoreEvent(
        WalletCoreEvent.SCHEMA_V1, WalletCoreEvent.TYPE_PAYLOAD_ACCEPTED, occurredAt, payload);
  }

  private static String sha256Hex(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
