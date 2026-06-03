package dev.nivic.sevlet.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Servlet-side <strong>codegen</strong> for dynamic form UI: returns small JSON documents with a
 * {@code vueOptions} object suitable for {@code defineComponent} in the host Vue app (avoids
 * dynamic {@code import} from blob URLs that cannot resolve the {@code vue} bare specifier).
 */
@WebServlet(name = "FormCodegenServlet", urlPatterns = "/api/dynamic-form/gen/*")
public class FormCodegenServlet extends HttpServlet {

  private static final Pattern SEGMENT = Pattern.compile("^/[a-z0-9-]+\\.json$");

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String pi = req.getPathInfo();
    if (pi == null || !SEGMENT.matcher(pi).matches()) {
      resp.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    String key = pi.substring(1, pi.length() - ".json".length());
    byte[] body =
        switch (key) {
          case "hint-banner" -> hintBanner();
          case "footer-note" -> footerNote();
          default -> null;
        };
    if (body == null) {
      resp.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
    resp.setContentType("application/json; charset=UTF-8");
    resp.setHeader("Cache-Control", "no-store");
    resp.getOutputStream().write(body);
  }

  private static byte[] hintBanner() {
    return """
        {"vueOptions":{"template":"<aside class='hint' style='padding:0.75rem 1rem;margin-bottom:1rem;background:#e8f4fc;border-radius:6px;font-size:0.9rem'>Kiểm tra <strong>MCC</strong> và <strong>quốc gia</strong> đúng scheme thanh toán. Banner codegen (<code>beforeFields</code>).</aside>"}}
        """
        .trim()
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] footerNote() {
    return """
        {"vueOptions":{"template":"<p class='hint' style='margin-top:1rem;color:#555;font-size:0.85rem'>Đây là <strong>draft merchant</strong> — lưu DB / workflow KYC ở backend production. Slot <code>afterFields</code>.</p>"}}
        """
        .trim()
        .getBytes(StandardCharsets.UTF_8);
  }
}
