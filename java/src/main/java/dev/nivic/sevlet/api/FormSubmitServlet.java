package dev.nivic.sevlet.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Accepts POST {@code application/json} merchant draft from the Merchants UI; responds with a small
 * JSON echo for wiring tests. Add persistence and validation from the same schema source in production.
 */
@WebServlet(name = "FormSubmitServlet", urlPatterns = "/api/dynamic-form/submit")
public class FormSubmitServlet extends HttpServlet {

  private static final int MAX_BODY = 65_536;

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (req.getContentLengthLong() > MAX_BODY) {
      resp.sendError(413);
      return;
    }
    String ct = req.getContentType();
    if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("application/json")) {
      resp.sendError(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "expected application/json");
      return;
    }

    byte[] raw = req.getInputStream().readNBytes(MAX_BODY + 1);
    if (raw.length > MAX_BODY) {
      resp.sendError(413);
      return;
    }

    String body = new String(raw, StandardCharsets.UTF_8);
    resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
    resp.setContentType("application/json; charset=UTF-8");
    String escaped = escapeJsonString(body);
    String out =
        "{\"ok\":true,\"message\":\"merchant_draft_received\",\"bytes\":"
            + raw.length
            + ",\"raw\":"
            + escaped
            + "}";
    resp.getOutputStream().write(out.getBytes(StandardCharsets.UTF_8));
  }

  private static String escapeJsonString(String s) {
    StringBuilder b = new StringBuilder(s.length() + 16);
    b.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> b.append("\\\\");
        case '"' -> b.append("\\\"");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    b.append('"');
    return b.toString();
  }
}
