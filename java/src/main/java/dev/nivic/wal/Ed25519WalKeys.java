package dev.nivic.wal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/** Load Ed25519 keys for Core WAL signing / verification (PKCS#8 private, SPKI public DER). */
public final class Ed25519WalKeys {

  private Ed25519WalKeys() {}

  public static PrivateKey loadPrivateKeyDer(Path path) throws IOException, GeneralSecurityException {
    Objects.requireNonNull(path, "path");
    byte[] der = Files.readAllBytes(path);
    return loadPrivateKeyDer(der);
  }

  public static PrivateKey loadPrivateKeyDer(byte[] pkcs8Der) throws GeneralSecurityException {
    Objects.requireNonNull(pkcs8Der, "pkcs8Der");
    KeyFactory kf = KeyFactory.getInstance("Ed25519");
    return kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Der));
  }

  /** Base64-encoded PKCS#8 DER (no PEM headers). */
  public static PrivateKey loadPrivateKeyFromBase64(String b64) throws GeneralSecurityException {
    Objects.requireNonNull(b64, "b64");
    String trimmed = b64.trim().replaceAll("\\s+", "");
    return loadPrivateKeyDer(Base64.getDecoder().decode(trimmed));
  }

  public static PublicKey loadPublicKeyDer(Path path) throws IOException, GeneralSecurityException {
    Objects.requireNonNull(path, "path");
    byte[] der = Files.readAllBytes(path);
    return loadPublicKeyDer(der);
  }

  public static PublicKey loadPublicKeyDer(byte[] x509Der) throws GeneralSecurityException {
    Objects.requireNonNull(x509Der, "x509Der");
    KeyFactory kf = KeyFactory.getInstance("Ed25519");
    return kf.generatePublic(new X509EncodedKeySpec(x509Der));
  }

  public static PublicKey loadPublicKeyFromBase64(String b64) throws GeneralSecurityException {
    Objects.requireNonNull(b64, "b64");
    String trimmed = b64.trim().replaceAll("\\s+", "");
    return loadPublicKeyDer(Base64.getDecoder().decode(trimmed));
  }
}
