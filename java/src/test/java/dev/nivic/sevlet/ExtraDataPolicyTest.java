package dev.nivic.sevlet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ExtraDataPolicyTest {

  @Test
  void rejectsWhenOverMax() {
    byte[] extra = new byte[100];
    assertThrows(IllegalArgumentException.class, () -> ExtraDataPolicy.validateLength(extra, 99));
  }

  @Test
  void acceptsAtMax() {
    byte[] extra = new byte[100];
    assertDoesNotThrow(() -> ExtraDataPolicy.validateLength(extra, 100));
  }
}
