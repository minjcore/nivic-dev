package dev.nivic.analytics;

import static org.junit.jupiter.api.Assertions.*;

import dev.nivic.command.WalletInputOp;
import dev.nivic.sevlet.SevletWalletCodec;
import dev.nivic.sevlet.SevletWalletPayload;
import dev.nivic.wal.CoreWalSigner;
import dev.nivic.wal.SimpleWalLog;
import dev.nivic.wal.SignedWalVerifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalShipperTest {

  private static final long MID = 42L;
  private static final long REQUEST_ID = 9001L;
  private static final long ORDER_ID = 8001L;
  private static final long AMOUNT = 50_000L;

  @Test
  void shipsLegacyWalToNdjson(@TempDir Path tmp) throws Exception {
    byte[] secret = new byte[32];
    new SecureRandom().nextBytes(secret);
    byte[] wire = signedWire(secret, WalletInputOp.TRANSFER, new byte[0]);

    Path wal = tmp.resolve("core.wal");
    Path out = tmp.resolve("events.ndjson");
    Path cursor = tmp.resolve("core.wal.cursor");

    try (SimpleWalLog log = new SimpleWalLog(wal)) {
      log.append(wire);
    }

    WalShipperResult r1 = new WalShipper(wal, out, cursor, null, java.util.Optional.of("USD"), false).runOnce();
    assertEquals(1, r1.shipped());
    assertEquals(0, r1.skipped());

    String line = Files.readString(out).trim();
    assertTrue(line.contains("\"event_type\":\"wallet.payload_accepted\""));
    assertTrue(line.contains("\"schema_version\":1"));
    assertTrue(line.contains("\"mid\":\"42\""));
    assertTrue(line.contains("\"currency_code\":\"USD\""));
    assertTrue(line.contains("\"raw_body_sha256\""));

    WalShipperResult r2 = new WalShipper(wal, out, cursor, null, java.util.Optional.empty(), false).runOnce();
    assertEquals(0, r2.shipped(), "second pass is idempotent via cursor");
    assertEquals(1, Files.readAllLines(out).size());
  }

  @Test
  void shipsNvw2SignedWalWithSequence(@TempDir Path tmp) throws Exception {
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
    KeyPair kp = kpg.generateKeyPair();
    CoreWalSigner signer = new CoreWalSigner(kp.getPrivate());
    byte[] wire = minimalWire();

    Path wal = tmp.resolve("signed.wal");
    try (SimpleWalLog log = new SimpleWalLog(wal)) {
      log.append(signer.signRecord(wire));
    }

    Path out = tmp.resolve("events.ndjson");
    Path cursor = tmp.resolve("signed.wal.cursor");
    WalShipperResult r =
        new WalShipper(wal, out, cursor, kp.getPublic(), java.util.Optional.empty(), false).runOnce();
    assertEquals(1, r.shipped());
    assertTrue(Files.readString(out).contains("\"wal_sequence\":\"0\""));
  }

  @Test
  void cursorResumesWhenNewRecordsAppended(@TempDir Path tmp) throws Exception {
    byte[] secret = new byte[32];
    new SecureRandom().nextBytes(secret);
    byte[] w1 = signedWire(secret, WalletInputOp.TRANSFER, new byte[0]);
    byte[] w2 = signedWire(secret, WalletInputOp.TRANSFER, "x".getBytes(StandardCharsets.UTF_8));

    Path wal = tmp.resolve("grow.wal");
    Path out = tmp.resolve("events.ndjson");
    Path cursor = tmp.resolve("grow.wal.cursor");

    try (SimpleWalLog log = new SimpleWalLog(wal)) {
      log.append(w1);
    }
    assertEquals(1, new WalShipper(wal, out, cursor, null, java.util.Optional.empty(), false).runOnce().shipped());

    try (SimpleWalLog log = new SimpleWalLog(wal)) {
      log.append(w2);
    }
    WalShipperResult r2 = new WalShipper(wal, out, cursor, null, java.util.Optional.empty(), false).runOnce();
    assertEquals(1, r2.shipped());
    assertEquals(2, Files.readAllLines(out).size());
  }

  @Test
  void mapperMatchesDownstreamContract() throws Exception {
    byte[] secret = new byte[32];
    byte[] wire = signedWire(secret, WalletInputOp.TRANSFER, new byte[0]);
    SevletWalletPayload p = SevletWalletCodec.decode(wire);
    var rec = new SignedWalVerifier.VerifiedRecord(-1L, wire, false);
    WalletCoreEvent event = new WalletCoreEventMapper(java.time.Instant.parse("2026-05-12T10:15:30.123Z"))
        .map(wire, rec);
    String json = WalletCoreEventJson.toLine(event);
    assertTrue(json.contains("\"occurred_at\":\"2026-05-12T10:15:30.123Z\""));
    assertTrue(json.contains("\"input_command\""));
    assertEquals(Long.toUnsignedString(p.mid()), event.payload().mid());
  }

  private static byte[] signedWire(byte[] secret, long command, byte[] extra) throws Exception {
    int bodyLen = SevletWalletCodec.PREFIX_BEFORE_EXTRA_LEN + extra.length;
    byte[] raw = new byte[bodyLen + SevletWalletCodec.SIG_LEN];
    ByteBuffer buf = ByteBuffer.wrap(raw);
    buf.put((byte) 0);
    buf.put((byte) 0);
    buf.put((byte) 0);
    buf.putLong(command);
    buf.putLong(MID);
    buf.putLong(REQUEST_ID);
    buf.putLong(ORDER_ID);
    buf.putLong(AMOUNT);
    buf.putInt(1);
    buf.putInt(2);
    buf.put(extra);
    byte[] macInput = SevletWalletCodec.signedBytesForHmac(raw);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret, "HmacSHA256"));
    byte[] sig = mac.doFinal(macInput);
    System.arraycopy(sig, 0, raw, bodyLen, SevletWalletCodec.SIG_LEN);
    return raw;
  }

  private static byte[] minimalWire() {
    return SevletWalletCodec.encode(
        new SevletWalletPayload(1L, 1L, 1L, 1L, 100L, 1, 2, new byte[0], new byte[32]));
  }
}
