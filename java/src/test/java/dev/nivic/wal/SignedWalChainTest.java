package dev.nivic.wal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignedWalChainTest {

  @Test
  void signVerifyAndChain() throws Exception {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
    KeyPair kp = kpg.generateKeyPair();
    CoreWalSigner signer = new CoreWalSigner(kp.getPrivate());
    byte[] p1 = "hello".getBytes(StandardCharsets.UTF_8);
    byte[] p2 = "world".getBytes(StandardCharsets.UTF_8);
    byte[] r1 = signer.signRecord(p1);
    byte[] r2 = signer.signRecord(p2);
    Path f = Files.createTempFile("waltest", ".wal");
    try (SimpleWalLog w = new SimpleWalLog(f)) {
      w.append(r1);
      w.append(r2);
    }
    PublicKey pub = kp.getPublic();
    List<SignedWalVerifier.VerifiedRecord> recs = SignedWalVerifier.replayVerifyCollect(f, pub);
    assertEquals(2, recs.size());
    assertTrue(recs.get(0).signed());
    assertTrue(recs.get(1).signed());
    assertEquals(0L, recs.get(0).seq());
    assertEquals(1L, recs.get(1).seq());
    assertArrayEquals(p1, recs.get(0).payload());
    assertArrayEquals(p2, recs.get(1).payload());
  }

  @Test
  void legacyPayloadPassesThrough() throws Exception {
    Path f = Files.createTempFile("walleg", ".wal");
    byte[] legacy = {0x01, 0x02, 0x03};
    try (SimpleWalLog w = new SimpleWalLog(f)) {
      w.append(legacy);
    }
    List<SignedWalVerifier.VerifiedRecord> recs = SignedWalVerifier.replayVerifyCollect(f, null);
    assertEquals(1, recs.size());
    assertTrue(recs.get(0).payload().length >= 3);
  }
}
