package dev.nivic.payment;

import static org.junit.jupiter.api.Assertions.*;

import dev.nivic.command.WalletInputOp;
import dev.nivic.journal.MemoryWalletJournal;
import dev.nivic.ledger.*;
import dev.nivic.merchant.MerchantConfig;
import dev.nivic.sevlet.ExtraDataPolicy;
import dev.nivic.sevlet.SevletWalletCodec;
import dev.nivic.sevlet.SevletWalletPayload;
import dev.nivic.sevlet.idempotency.MemoryIdempotencyGate;
import dev.nivic.sevlet.secret.MidProfile;
import dev.nivic.sevlet.secret.MidSecretResolver;
import dev.nivic.sevlet.secret.SevletWalletHmac;
import dev.nivic.wal.SimpleWalLog;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("hot-path")
class HotPathTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final long MID = 42L;
  private static final long REQUEST_ID = 1000L;
  private static final long ORDER_ID = 999L;
  private static final long AMOUNT = 500_00L;
  private static final int DEBIT = 1;
  private static final int CREDIT = 2;

  private byte[] secretKey;
  private MemoryIdempotencyGate idempotency;
  private MemoryWalletLedger walletLedger;
  private MemoryWalletJournal journal;
  private MemoryPaymentLedger paymentLedger;
  private LedgerService ledgerService;
  private Path walPath;
  private WalService walService;
  private WalletAcceptService acceptService;

  @BeforeEach
  void setUp(@TempDir Path tempDir) throws IOException {
    secretKey = new byte[32];
    new SecureRandom().nextBytes(secretKey);

    idempotency = new MemoryIdempotencyGate();
    walletLedger = new MemoryWalletLedger();
    journal = new MemoryWalletJournal();
    paymentLedger = new MemoryPaymentLedger();
    ledgerService = new LedgerService(walletLedger, journal);

    walPath = tempDir.resolve("test.wal");
    SimpleWalLog simpleWal = new SimpleWalLog(walPath);
    walService = new WalService(simpleWal, null);

    var midSecrets =
        (MidSecretResolver) mid -> new MidProfile(secretKey, false, MerchantConfig.defaults());

    acceptService =
        new WalletAcceptService(
            idempotency,
            walService,
            ledgerService,
            paymentLedger,
            USD,
            midSecrets,
            null,
            15);
  }

  @Test
  void fullPostTransfer() throws Exception {
    byte[] raw = signedWire(WalletInputOp.TRANSFER, new byte[0]);

    SevletWalletPayload payload = SevletWalletCodec.decode(raw);
    SevletWalletHmac.verify(raw, secretKey);

    AcceptResult result = acceptService.claimAndPersist(raw, payload);
    assertFalse(result.isDuplicate(), "first claim should succeed");

    List<byte[]> walRecords = replayWalPayloads();
    assertEquals(1, walRecords.size(), "WAL must have 1 record");
    assertEquals(raw.length, walRecords.get(0).length, "WAL record matches wire length");

    List<MemoryWalletLedger.LedgerEntry> ledgerSnap = walletLedger.snapshot();
    assertEquals(1, ledgerSnap.size(), "ledger must have 1 entry");
    MemoryWalletLedger.LedgerEntry le = ledgerSnap.get(0);
    assertEquals(MID, le.mid());
    assertEquals(REQUEST_ID, le.requestId());
    assertEquals(ORDER_ID, le.orderId());
    assertEquals(AMOUNT, le.amountMinor());
    assertEquals(DEBIT, le.debit());
    assertEquals(CREDIT, le.credit());

    List<MemoryWalletJournal.JournalRecord> journalSnap = journal.snapshot();
    assertEquals(1, journalSnap.size(), "journal must have 1 entry");
    MemoryWalletJournal.JournalRecord jr = journalSnap.get(0);
    assertEquals(2, jr.lines().size(), "journal must have 2 lines");
    assertEquals(DEBIT, jr.lines().get(0).account());
    assertEquals(AMOUNT, jr.lines().get(0).debitMinor());
    assertEquals(0, jr.lines().get(0).creditMinor());
    assertEquals(CREDIT, jr.lines().get(1).account());
    assertEquals(0, jr.lines().get(1).debitMinor());
    assertEquals(AMOUNT, jr.lines().get(1).creditMinor());

    List<MemoryPaymentLedger.PaymentEntry> paymentSnap = paymentLedger.snapshot();
    assertEquals(1, paymentSnap.size(), "payment_ledger must have 1 entry (settled)");
    assertEquals(CoreLedgerStatus.SETTLED, paymentSnap.get(0).intentStatus());
  }

  @Test
  void reversalSamePathAsTransfer() throws Exception {
    byte[] raw = signedWire(WalletInputOp.REVERSAL, new byte[0]);

    SevletWalletPayload payload = SevletWalletCodec.decode(raw);
    SevletWalletHmac.verify(raw, secretKey);

    AcceptResult result = acceptService.claimAndPersist(raw, payload);
    assertFalse(result.isDuplicate());

    assertEquals(1, walletLedger.snapshot().size());
    assertEquals(1, journal.snapshot().size());
    assertEquals(WalletInputOp.REVERSAL, walletLedger.snapshot().get(0).command());
  }

  @Test
  void orderIntentThenConfirm() throws Exception {
    var orderSecrets =
        (MidSecretResolver) mid -> new MidProfile(secretKey, true, MerchantConfig.defaults());

    try (var orderAccept =
        new WalletAcceptService(
            idempotency,
            walService,
            ledgerService,
            paymentLedger,
            USD,
            orderSecrets,
            null,
            15)) {

      byte[] raw = signedWire(WalletInputOp.TRANSFER, new byte[0]);
      SevletWalletPayload payload = SevletWalletCodec.decode(raw);
      AcceptResult intentResult = orderAccept.claimAndPersist(raw, payload);
      assertFalse(intentResult.isDuplicate());
      assertTrue(intentResult.intentAck().isPresent(), "order intent must return ack with challenge");

      String challengeB64 = intentResult.intentAck().get().confirmChallengeBase64();
      byte[] challenge = java.util.Base64.getDecoder().decode(challengeB64);

      assertEquals(0, walletLedger.snapshot().size(), "no ledger rows before confirm");
      assertEquals(0, journal.snapshot().size(), "no journal rows before confirm");

      long confirmRequestId = 2000L;
      byte[] extra = ByteBuffer.allocate(40).putLong(confirmRequestId).put(challenge).array();
      byte[] confirmRaw = signedWire(WalletInputOp.CONFIRM_PAYMENT, confirmRequestId, extra);
      SevletWalletPayload confirmPayload = SevletWalletCodec.decode(confirmRaw);
      AcceptResult confirmResult = orderAccept.claimAndPersist(confirmRaw, confirmPayload);
      assertFalse(confirmResult.isDuplicate());

      assertEquals(1, walletLedger.snapshot().size(), "ledger must have 1 row after confirm");
      assertEquals(1, journal.snapshot().size(), "journal must have 1 row after confirm");

      List<MemoryPaymentLedger.PaymentEntry> paymentSnap = paymentLedger.snapshot();
      assertEquals(1, paymentSnap.size());
      assertEquals(CoreLedgerStatus.SETTLED, paymentSnap.get(0).intentStatus());
    }
  }

  @Test
  void orderIntentThenReject() throws Exception {
    var orderSecrets =
        (MidSecretResolver) mid -> new MidProfile(secretKey, true, MerchantConfig.defaults());

    try (var orderAccept =
        new WalletAcceptService(
            idempotency,
            walService,
            ledgerService,
            paymentLedger,
            USD,
            orderSecrets,
            null,
            15)) {

      byte[] raw = signedWire(WalletInputOp.TRANSFER, new byte[0]);
      SevletWalletPayload payload = SevletWalletCodec.decode(raw);
      AcceptResult intentResult = orderAccept.claimAndPersist(raw, payload);

      byte[] challenge =
          java.util.Base64.getDecoder().decode(
              intentResult.intentAck().get().confirmChallengeBase64());
      long rejectRequestId = 3000L;
      byte[] extra = ByteBuffer.allocate(40).putLong(rejectRequestId).put(challenge).array();
      byte[] rejectRaw = signedWire(WalletInputOp.REJECT_PAYMENT, rejectRequestId, extra);
      SevletWalletPayload rejectPayload = SevletWalletCodec.decode(rejectRaw);
      AcceptResult rejectResult = orderAccept.claimAndPersist(rejectRaw, rejectPayload);
      assertFalse(rejectResult.isDuplicate());

      assertEquals(0, walletLedger.snapshot().size(), "no ledger rows after reject");
      assertEquals(0, journal.snapshot().size(), "no journal rows after reject");

      List<MemoryPaymentLedger.PaymentEntry> paymentSnap = paymentLedger.snapshot();
      assertEquals(CoreLedgerStatus.CANCELLED, paymentSnap.get(0).intentStatus());
    }
  }

  @Test
  void idempotencyDuplicateRejected() throws Exception {
    byte[] raw = signedWire(WalletInputOp.TRANSFER, new byte[0]);
    SevletWalletPayload payload = SevletWalletCodec.decode(raw);
    SevletWalletHmac.verify(raw, secretKey);

    AcceptResult first = acceptService.claimAndPersist(raw, payload);
    assertFalse(first.isDuplicate());

    AcceptResult second = acceptService.claimAndPersist(raw, payload);
    assertTrue(second.isDuplicate(), "duplicate must be detected");

    assertEquals(1, walletLedger.snapshot().size(), "only 1 ledger row despite 2 claims");
  }

  @Test
  void badHmacRejected() throws Exception {
    byte[] raw = signedWire(WalletInputOp.TRANSFER, new byte[0]);
    raw[raw.length - 1] ^= 0xFF;
    assertThrows(
        SecurityException.class,
        () -> SevletWalletHmac.verify(raw, secretKey),
        "corrupted HMAC must throw");
  }

  @Test
  void unknownMidRejected() throws Exception {
    var strictMid =
        (MidSecretResolver)
            mid -> {
              throw new dev.nivic.sevlet.secret.UnknownMidException(mid);
            };
    var verifier = new WalletVerificationService(strictMid);
    byte[] raw = signedWire(WalletInputOp.TRANSFER, new byte[0]);
    SevletWalletPayload payload = SevletWalletCodec.decode(raw);
    assertThrows(
        dev.nivic.sevlet.secret.UnknownMidException.class,
        () -> verifier.verify(raw, payload));
  }

  @Test
  void extraDataWithinLimits() {
    byte[] extra = new byte[256];
    new SecureRandom().nextBytes(extra);
    assertDoesNotThrow(() -> ExtraDataPolicy.validateLength(extra, 256));
    assertThrows(IllegalArgumentException.class, () -> ExtraDataPolicy.validateLength(extra, 200));
  }

  @Test
  void minWireLengthEnforced() {
    byte[] tooShort = new byte[SevletWalletCodec.MIN_WIRE_LEN - 1];
    assertThrows(
        IllegalArgumentException.class, () -> SevletWalletCodec.decode(tooShort));
  }

  @Test
  void twoPhaseCrossMidRejection() {
    MemoryIdempotencyGate gate = new MemoryIdempotencyGate();
    assertTrue(gate.claimFirst(1L, 10L, 100L, true));
    assertFalse(gate.claimFirst(1L, 10L, 100L, true));
    assertThrows(
        dev.nivic.sevlet.idempotency.OrderIdMismatchException.class,
        () -> gate.claimFirst(1L, 10L, 999L, true));
  }

  // ---- helpers ----

  private byte[] signedWire(long command, byte[] extraData) throws Exception {
    return signedWire(command, REQUEST_ID, extraData);
  }

  private byte[] signedWire(long command, long requestId, byte[] extraData) throws Exception {
    int bodyLen = SevletWalletCodec.PREFIX_BEFORE_EXTRA_LEN + extraData.length;
    byte[] raw = new byte[bodyLen + SevletWalletCodec.SIG_LEN];
    ByteBuffer buf = ByteBuffer.wrap(raw);
    buf.put((byte) 0);
    buf.put((byte) 0);
    buf.put((byte) 0);
    buf.putLong(command);
    buf.putLong(MID);
    buf.putLong(requestId);
    buf.putLong(ORDER_ID);
    buf.putLong(AMOUNT);
    buf.putInt(DEBIT);
    buf.putInt(CREDIT);
    buf.put(extraData);

    byte[] macInput = SevletWalletCodec.signedBytesForHmac(raw);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
    byte[] sig = mac.doFinal(macInput);
    System.arraycopy(sig, 0, raw, bodyLen, SevletWalletCodec.SIG_LEN);
    return raw;
  }

  private List<byte[]> replayWalPayloads() throws IOException {
    List<byte[]> records = new ArrayList<>();
    SimpleWalLog.replay(walPath, payload -> records.add(payload.clone()));
    return records;
  }
}
