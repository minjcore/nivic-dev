package dev.nivic.analytics;

import dev.nivic.sevlet.SevletWalletCodec;
import dev.nivic.wal.SignedWalVerifier;
import java.io.IOException;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 1 analytics sidecar: tail Core WAL → NDJSON downstream events.
 *
 * <p>Idempotent via {@link WalShipperCursor}; at-least-once if crash between NDJSON append and
 * cursor save (duplicate lines possible — sink dedups on {@code raw_body_sha256}).
 */
public final class WalShipper {

  private final Path walPath;
  private final Path outPath;
  private final Path cursorPath;
  private final PublicKey walPublicKey;
  private final Optional<String> currencyCode;
  private final boolean syncNdjson;

  public WalShipper(
      Path walPath,
      Path outPath,
      Path cursorPath,
      PublicKey walPublicKey,
      Optional<String> currencyCode,
      boolean syncNdjson) {
    this.walPath = Objects.requireNonNull(walPath, "walPath");
    this.outPath = Objects.requireNonNull(outPath, "outPath");
    this.cursorPath = cursorPath;
    this.walPublicKey = walPublicKey;
    this.currencyCode = currencyCode == null ? Optional.empty() : currencyCode;
    this.syncNdjson = syncNdjson;
  }

  public WalShipperResult runOnce() throws IOException {
    WalShipperCursor cursor =
        cursorPath == null ? WalShipperCursor.initial() : WalShipperCursor.load(cursorPath);
    int[] counts = {0, 0};

    try (NdjsonEventSink sink = new NdjsonEventSink(outPath, syncNdjson)) {
      cursor =
          WalIncrementalReader.drain(
              walPath,
              cursor,
              walPublicKey,
              rec -> {
                try {
                  byte[] wire = rec.verified().payload();
                  if (wire.length < SevletWalletCodec.MIN_WIRE_LEN) {
                    counts[1]++;
                    return;
                  }
                  WalletCoreEventMapper mapper =
                      new WalletCoreEventMapper(Instant.now(), currencyCode);
                  WalletCoreEvent event = mapper.map(wire, rec.verified());
                  sink.appendLine(WalletCoreEventJson.toLine(event));
                  counts[0]++;
                } catch (IllegalArgumentException e) {
                  counts[1]++;
                } catch (IOException e) {
                  throw new IllegalStateException("NDJSON append failed", e);
                }
              });
    }

    if (cursorPath != null) {
      cursor.save(cursorPath);
    }
    return new WalShipperResult(counts[0], counts[1], cursor.byteOffset());
  }
}
