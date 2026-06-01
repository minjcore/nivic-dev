package dev.nivic.coa.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unit tests for the minimal flat-JSON parser/serializer (no DB). */
class MiniJsonTest {

  @Test
  void parsesStringsNumbersBooleans() {
    MiniJson j = MiniJson.parse("{\"a\":\"x\",\"n\":123,\"b\":true,\"z\":null}");
    assertEquals("x", j.reqString("a"));
    assertEquals(123L, j.reqLong("n"));
    assertTrue(j.has("a"));
    assertFalse(j.has("z"), "null value treated as absent");
  }

  @Test
  void reqLongAcceptsNumericString() {
    MiniJson j = MiniJson.parse("{\"n\":\"456\"}");
    assertEquals(456L, j.reqLong("n"));
  }

  @Test
  void optLongAndOptStringDefaults() {
    MiniJson j = MiniJson.parse("{\"a\":\"x\"}");
    assertEquals(7L, j.optLong("missing", 7L));
    assertNull(j.optString("missing"));
  }

  @Test
  void reqIntRangeChecked() {
    MiniJson j = MiniJson.parse("{\"big\":9999999999}");
    assertThrows(IllegalArgumentException.class, () -> j.reqInt("big"));
  }

  @Test
  void missingRequiredThrows() {
    MiniJson j = MiniJson.parse("{}");
    assertThrows(IllegalArgumentException.class, () -> j.reqString("x"));
    assertThrows(IllegalArgumentException.class, () -> j.reqLong("x"));
  }

  @Test
  void emptyAndNullBody() {
    assertEquals(0L, MiniJson.parse("").optLong("a", 0));
    assertEquals(0L, MiniJson.parse(null).optLong("a", 0));
    assertFalse(MiniJson.empty().has("a"));
  }

  @Test
  void nonNumericStringForLongThrows() {
    MiniJson j = MiniJson.parse("{\"n\":\"abc\"}");
    assertThrows(IllegalArgumentException.class, () -> j.reqLong("n"));
  }

  @Test
  void malformedObjectThrows() {
    assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("[1,2,3]"));
    assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\" 1}"));
  }

  @Test
  void escapesOutput() {
    assertEquals("\"a\\\"b\"", MiniJson.str("a\"b"));
    assertEquals("\"l1\\nl2\"", MiniJson.str("l1\nl2"));
    assertEquals("null", MiniJson.str(null));
  }

  @Test
  void handlesWhitespaceAndEscapesInValues() {
    MiniJson j = MiniJson.parse("{  \"memo\" : \"dòng 1\\ndòng 2\" , \"x\": 1 }");
    assertEquals("dòng 1\ndòng 2", j.reqString("memo"));
    assertEquals(1L, j.reqLong("x"));
  }
}
