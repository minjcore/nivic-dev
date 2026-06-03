package dev.nivic.cli;

import dev.nivic.wal.Ed25519WalKeys;
import dev.nivic.wal.SignedWalVerifier;
import java.nio.file.Path;
import java.security.PublicKey;

/** CLI: verify WAL chain and optional Ed25519 signatures. Usage: {@code WalVerifyMain <walPath> [pub.der]}. */
public final class WalVerifyMain {

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: WalVerifyMain <walPath> [publicKey.der]");
      System.err.println("  With public key: verifies NVW2 Ed25519 signatures + hash chain.");
      System.err.println("  Without: verifies NVW2 structure + chain only (legacy bodies pass through).");
      System.exit(2);
    }
    Path walPath = Path.of(args[0]);
    PublicKey pub = null;
    if (args.length >= 2) {
      pub = Ed25519WalKeys.loadPublicKeyDer(Path.of(args[1]));
    }
    var records = SignedWalVerifier.replayVerifyCollect(walPath, pub);
    int n = 0;
    for (SignedWalVerifier.VerifiedRecord r : records) {
      n++;
      if (r.signed()) {
        System.out.println("record #" + n + " seq=" + r.seq() + " signed payload_len=" + r.payload().length);
      } else {
        System.out.println("record #" + n + " legacy payload_len=" + r.payload().length);
      }
    }
    System.out.println("OK " + records.size() + " record(s)");
  }
}
