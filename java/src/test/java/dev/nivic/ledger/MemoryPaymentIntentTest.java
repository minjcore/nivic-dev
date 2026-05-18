package dev.nivic.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.nivic.command.WalletInputOp;
import dev.nivic.sevlet.SevletWalletPayload;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MemoryPaymentIntentTest {

  @Test
  void appendSettleByOrder() {
    MemoryPaymentLedger pl = new MemoryPaymentLedger();
    byte[] ch = new byte[32];
    Arrays.fill(ch, (byte)9);
    byte[] sig = new byte[32];
    SevletWalletPayload intent =
        new SevletWalletPayload(
            WalletInputOp.TRANSFER, 1L, 100L, 999L, 50L, 2, 3, new byte[0], sig);
    pl.append(
        intent, Currency.getInstance("USD"), PaymentIntentAppendCtx.orderIntent(15, ch));

    byte[] extra = ByteBuffer.allocate(40).putLong(100L).put(ch).array();
    SevletWalletPayload confirm =
        new SevletWalletPayload(
            WalletInputOp.CONFIRM_PAYMENT, 1L, 200L, 999L, 50L, 2, 3, extra, sig);
    pl.settleIntentByOrder(confirm, Currency.getInstance("USD"));
    var snap = pl.snapshot();
    assertEquals(1, snap.size());
    assertEquals(CoreLedgerStatus.SETTLED, snap.get(0).intentStatus());
    assertEquals(2, snap.get(0).debit());
  }

  @Test
  void requireNoConflictingOpenIntent_blocksSecondRequestSameOrder() {
    MemoryPaymentLedger pl = new MemoryPaymentLedger();
    byte[] ch = new byte[32];
    Arrays.fill(ch, (byte) 2);
    byte[] sig = new byte[32];
    long orderId = 777L;
    SevletWalletPayload first =
        new SevletWalletPayload(
            WalletInputOp.TRANSFER, 1L, 100L, orderId, 50L, 2, 3, new byte[0], sig);
    pl.append(
        first, Currency.getInstance("USD"), PaymentIntentAppendCtx.orderIntent(15, ch));

    SevletWalletPayload second =
        new SevletWalletPayload(
            WalletInputOp.TRANSFER, 1L, 200L, orderId, 50L, 2, 3, new byte[0], sig);
    assertThrows(
        OrderIdConflictException.class,
        () -> pl.requireNoConflictingOpenIntent(second.mid(), second.orderId(), second.requestId()));
  }
}
