package dev.nivic.payment;

import dev.nivic.command.WalletInputCommands;
import dev.nivic.sevlet.SevletWalletPayload;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/** Debug JSON body shape for a parsed {@link SevletWalletPayload}. */
public final class WalletPayloadJson {

  private WalletPayloadJson() {}

  public static String format(SevletWalletPayload p) {
    HexFormat hx = HexFormat.of();
    String sigHex = hx.formatHex(p.sig());
    String extraB64 = Base64.getEncoder().encodeToString(p.extraData());
    String inputCommand = WalletInputCommands.from(p).debugKind();
    return "{"
        + "\"command\":\""
        + Long.toUnsignedString(p.command())
        + "\",\"inputCommand\":\""
        + inputCommand
        + "\",\"mid\":\""
        + Long.toUnsignedString(p.mid())
        + "\",\"requestId\":\""
        + Long.toUnsignedString(p.requestId())
        + "\",\"orderId\":\""
        + Long.toUnsignedString(p.orderId())
        + "\",\"amount\":\""
        + Long.toUnsignedString(p.amount())
        + "\",\"debit\":"
        + Integer.toUnsignedLong(p.debit())
        + ",\"credit\":"
        + Integer.toUnsignedLong(p.credit())
        + ",\"extraData\":\""
        + extraB64
        + "\",\"sig\":\""
        + sigHex
        + "\"}";
  }

  /** Same as {@link #format} plus intent acknowledgement fields when {@code ack != null}. */
  public static String formatWithIntentAck(SevletWalletPayload p, PaymentIntentAck ack) {
    String base = format(p);
    if (ack == null) {
      return base;
    }
    Instant exp = ack.expiresAt();
    return base.substring(0, base.length() - 1)
        + ",\"confirmChallenge\":\""
        + ack.confirmChallengeBase64()
        + "\",\"intentExpiresAt\":\""
        + exp.toString()
        + "\"}";
  }
}
