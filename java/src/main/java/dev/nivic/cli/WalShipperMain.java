package dev.nivic.cli;

import dev.nivic.analytics.WalShipper;
import dev.nivic.analytics.WalShipperResult;
import dev.nivic.wal.Ed25519WalKeys;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Optional;

/**
 * CLI: ship Core WAL records to NDJSON for analytics (Phase 1).
 *
 * <pre>
 *   WalShipperMain --wal /path/to/sevlet-wallet.wal --out /path/to/events.ndjson
 *     [--cursor /path/to/wal.shipper.cursor]
 *     [--pubkey /path/to/wal.pub.der]
 *     [--currency USD]
 *     [--sync]
 * </pre>
 */
public final class WalShipperMain {

  private WalShipperMain() {}

  public static void main(String[] args) throws Exception {
    Path wal = null;
    Path out = null;
    Path cursor = null;
    Path pubkey = null;
    String currency = null;
    boolean sync = false;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--wal" -> wal = Path.of(requireArg(args, ++i, "--wal"));
        case "--out" -> out = Path.of(requireArg(args, ++i, "--out"));
        case "--cursor" -> cursor = Path.of(requireArg(args, ++i, "--cursor"));
        case "--pubkey" -> pubkey = Path.of(requireArg(args, ++i, "--pubkey"));
        case "--currency" -> currency = requireArg(args, ++i, "--currency");
        case "--sync" -> sync = true;
        case "-h", "--help" -> {
          printUsage();
          System.exit(0);
        }
        default -> {
          System.err.println("Unknown arg: " + args[i]);
          printUsage();
          System.exit(2);
        }
      }
    }

    if (wal == null || out == null) {
      printUsage();
      System.exit(2);
    }
    if (cursor == null) {
      cursor = out.resolveSibling(out.getFileName() + ".cursor");
    }

    PublicKey pub = pubkey == null ? null : Ed25519WalKeys.loadPublicKeyDer(pubkey);
    WalShipper shipper =
        new WalShipper(
            wal,
            out,
            cursor,
            pub,
            currency == null ? Optional.empty() : Optional.of(currency),
            sync);
    WalShipperResult result = shipper.runOnce();
    System.out.println(
        "shipped="
            + result.shipped()
            + " skipped="
            + result.skipped()
            + " wal_offset="
            + result.walByteOffset()
            + " out="
            + out.toAbsolutePath());
  }

  private static String requireArg(String[] args, int idx, String flag) {
    if (idx >= args.length) {
      throw new IllegalArgumentException("Missing value for " + flag);
    }
    return args[idx];
  }

  private static void printUsage() {
    System.err.println("Usage: WalShipperMain --wal <path> --out <events.ndjson> [options]");
    System.err.println("  --cursor <path>   Progress file (default: <out>.cursor)");
    System.err.println("  --pubkey <.der>   Ed25519 public key for NVW2 verification");
    System.err.println("  --currency XXX    Optional ISO 4217 in payload");
    System.err.println("  --sync            fsync each NDJSON line");
  }
}
