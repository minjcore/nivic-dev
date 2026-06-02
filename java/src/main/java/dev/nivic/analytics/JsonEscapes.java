package dev.nivic.analytics;

/** Minimal JSON string escaping for NDJSON event lines. */
final class JsonEscapes {

  private JsonEscapes() {}

  static String quote(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder out = new StringBuilder(s.length() + 8);
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
    return out.toString();
  }
}
