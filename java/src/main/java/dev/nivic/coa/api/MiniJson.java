package dev.nivic.coa.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal JSON helper for the fund-flow REST API — no external dependency.
 *
 * <p>Parses a <strong>flat</strong> JSON object (no nested objects/arrays) into a
 * {@code Map<String,Object>} where values are {@link String}, {@link Long}, {@link Double},
 * {@link Boolean} or {@code null}. Typed accessors throw {@link IllegalArgumentException} on
 * missing/invalid fields so callers get a clean 400. Also provides string escaping for output.</p>
 */
public final class MiniJson {

  private final Map<String, Object> map;

  private MiniJson(Map<String, Object> map) {
    this.map = map;
  }

  public static MiniJson parse(String json) {
    return new MiniJson(parseObject(json));
  }

  /** Empty object (for GET requests with no body). */
  public static MiniJson empty() {
    return new MiniJson(new LinkedHashMap<>());
  }

  // ── Typed accessors ────────────────────────────────────────────────────────

  public boolean has(String key) {
    return map.containsKey(key) && map.get(key) != null;
  }

  public long reqLong(String key) {
    Object v = map.get(key);
    if (v == null) throw new IllegalArgumentException("missing field: " + key);
    if (v instanceof Long l) return l;
    if (v instanceof Double d) return d.longValue();
    if (v instanceof String s) {
      try { return Long.parseLong(s.trim()); }
      catch (NumberFormatException e) { throw new IllegalArgumentException("not a number: " + key); }
    }
    throw new IllegalArgumentException("not a number: " + key);
  }

  public long optLong(String key, long dflt) {
    return has(key) ? reqLong(key) : dflt;
  }

  public int reqInt(String key) {
    long l = reqLong(key);
    if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("out of int range: " + key);
    }
    return (int) l;
  }

  public String reqString(String key) {
    Object v = map.get(key);
    if (v == null) throw new IllegalArgumentException("missing field: " + key);
    return String.valueOf(v);
  }

  public String optString(String key) {
    Object v = map.get(key);
    return v == null ? null : String.valueOf(v);
  }

  // ── Output escaping ─────────────────────────────────────────────────────────

  /** JSON string literal (with surrounding quotes), or {@code null} literal. */
  public static String str(String s) {
    if (s == null) return "null";
    StringBuilder b = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"'  -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default   -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
      }
    }
    return b.append('"').toString();
  }

  // ── Flat parser ─────────────────────────────────────────────────────────────

  private static Map<String, Object> parseObject(String json) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (json == null) return out;
    String s = json.trim();
    if (s.isEmpty()) return out;
    if (s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') {
      throw new IllegalArgumentException("expected JSON object");
    }
    int i = 1, n = s.length() - 1;
    while (i < n) {
      i = skipWs(s, i);
      if (i >= n) break;
      if (s.charAt(i) == ',') { i++; continue; }
      if (s.charAt(i) != '"') throw new IllegalArgumentException("expected key at " + i);
      int[] keyEnd = new int[1];
      String key = parseString(s, i, keyEnd);
      i = skipWs(s, keyEnd[0]);
      if (i >= n || s.charAt(i) != ':') throw new IllegalArgumentException("expected ':' at " + i);
      i = skipWs(s, i + 1);
      int[] valEnd = new int[1];
      Object val = parseValue(s, i, valEnd);
      out.put(key, val);
      i = skipWs(s, valEnd[0]);
    }
    return out;
  }

  private static int skipWs(String s, int i) {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    return i;
  }

  private static String parseString(String s, int start, int[] end) {
    StringBuilder b = new StringBuilder();
    int i = start + 1; // skip opening quote
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '"') { end[0] = i + 1; return b.toString(); }
      if (c == '\\') {
        char e = s.charAt(++i);
        switch (e) {
          case '"' -> b.append('"');
          case '\\' -> b.append('\\');
          case '/' -> b.append('/');
          case 'n' -> b.append('\n');
          case 'r' -> b.append('\r');
          case 't' -> b.append('\t');
          case 'u' -> { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
          default -> b.append(e);
        }
      } else {
        b.append(c);
      }
      i++;
    }
    throw new IllegalArgumentException("unterminated string");
  }

  private static Object parseValue(String s, int start, int[] end) {
    char c = s.charAt(start);
    if (c == '"') return parseString(s, start, end);
    if (s.startsWith("true", start))  { end[0] = start + 4; return Boolean.TRUE; }
    if (s.startsWith("false", start)) { end[0] = start + 5; return Boolean.FALSE; }
    if (s.startsWith("null", start))  { end[0] = start + 4; return null; }
    // number
    int i = start;
    boolean dbl = false;
    while (i < s.length()) {
      char d = s.charAt(i);
      if (d == '-' || d == '+' || (d >= '0' && d <= '9')) { i++; }
      else if (d == '.' || d == 'e' || d == 'E') { dbl = true; i++; }
      else break;
    }
    String num = s.substring(start, i);
    end[0] = i;
    if (num.isEmpty()) throw new IllegalArgumentException("invalid value at " + start);
    return dbl ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
  }
}
