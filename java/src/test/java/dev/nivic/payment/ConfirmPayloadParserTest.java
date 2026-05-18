package dev.nivic.payment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ConfirmPayloadParserTest {

  @Test
  void v0PrefixMatchesChallengeAndAllowsTlvTail() {
    byte[] challenge = new byte[32];
    Arrays.fill(challenge, (byte) 7);
    byte[] extra = new byte[ConfirmPayloadParser.CONFIRM_V0_PREFIX_LEN + 12];
    ByteBuffer.wrap(extra).putLong(0xDEADBEEFL).put(challenge);
    Arrays.fill(extra, ConfirmPayloadParser.CONFIRM_V0_PREFIX_LEN, extra.length, (byte) 42);

    assertEquals(0xDEADBEEFL, ConfirmPayloadParser.originalRequestId(extra));
    assertArrayEquals(challenge, ConfirmPayloadParser.challenge(extra));
  }
}
