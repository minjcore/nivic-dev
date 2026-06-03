package dev.nivic.payment;

import dev.nivic.wal.CoreWalSigner;
import dev.nivic.wal.SimpleWalLog;
import java.io.IOException;
import java.util.Objects;

/**
 * Append-only file WAL facade ({@link SimpleWalLog}) for durable accept-before-batch-DB semantics.
 *
 * <p>When a {@link CoreWalSigner} is configured, each append wraps the raw client wire in an NVW2
 * Ed25519-signed frame (see {@link dev.nivic.wal.SignedWalVerifier}); otherwise records remain
 * legacy length-prefixed raw bodies.
 */
public final class WalService implements AutoCloseable {

  private final SimpleWalLog wal;
  private final CoreWalSigner coreSigner;

  public WalService(SimpleWalLog wal) {
    this(wal, null);
  }

  public WalService(SimpleWalLog wal, CoreWalSigner coreSigner) {
    this.wal = Objects.requireNonNull(wal, "wal");
    this.coreSigner = coreSigner;
  }

  /** @return {@code true} when Core signs each record (NVW2 frames). */
  public boolean coreSigningEnabled() {
    return coreSigner != null;
  }

  public void append(byte[] rawWire) {
    Objects.requireNonNull(rawWire, "rawWire");
    try {
      byte[] record = coreSigner == null ? rawWire : coreSigner.signRecord(rawWire);
      wal.append(record);
    } catch (IOException e) {
      throw new IllegalStateException("WAL append failed: " + e.getMessage(), e);
    }
  }

  @Override
  public void close() throws IOException {
    wal.close();
  }
}
