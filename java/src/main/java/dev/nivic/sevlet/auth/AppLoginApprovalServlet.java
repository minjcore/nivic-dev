package dev.nivic.sevlet.auth;

import dev.nivic.auth.LoginApprovalBroker;
import dev.nivic.auth.LoginApprovalBroker.Request;
import dev.nivic.coa.api.MiniJson;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * App side endpoint to approve or deny a pending web login request.
 *
 * <p>Requires Authorization: Bearer &lt;APP_APPROVAL_TOKEN&gt; (default dev-allow-token).</p>
 */
@WebServlet(name = "AppLoginApprovalServlet", urlPatterns = "/api/auth/app/login/approve")
public final class AppLoginApprovalServlet extends HttpServlet {

  private static final int MAX_BODY = 8_192;

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    LoginApprovalBroker broker = LoginApprovalBroker.from(getServletContext());
    String bearer = LoginApprovalBroker.bearerToken(req.getHeader("Authorization"));
    if (!broker.isAppTokenValid(bearer)) {
      writeJson(resp, HttpServletResponse.SC_UNAUTHORIZED, "{\"ok\":false,\"error\":\"invalid_app_token\"}");
      return;
    }

    MiniJson body;
    try {
      body = parseBody(req);
    } catch (IllegalArgumentException e) {
      writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"ok\":false,\"error\":\"invalid_json\"}");
      return;
    }

    String requestId = body.optString("requestId");
    if (requestId == null || requestId.isBlank()) {
      writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"ok\":false,\"error\":\"request_id_required\"}");
      return;
    }
    boolean approve = !"deny".equalsIgnoreCase(body.optString("decision"));
    String appUserId = body.optString("appUserId");
    Request updated = broker.decide(requestId.trim(), approve, appUserId);
    if (updated == null) {
      writeJson(resp, HttpServletResponse.SC_NOT_FOUND, "{\"ok\":false,\"error\":\"request_not_found_or_expired\"}");
      return;
    }

    writeJson(
        resp,
        HttpServletResponse.SC_OK,
        "{"
            + "\"ok\":true,\"requestId\":"
            + MiniJson.str(updated.requestId())
            + ",\"status\":\""
            + updated.status().name()
            + "\"}");
  }

  private MiniJson parseBody(HttpServletRequest req) throws IOException {
    byte[] raw = req.getInputStream().readNBytes(MAX_BODY + 1);
    if (raw.length > MAX_BODY) {
      throw new IllegalArgumentException("payload_too_large");
    }
    if (raw.length == 0) {
      return MiniJson.empty();
    }
    return MiniJson.parse(new String(raw, StandardCharsets.UTF_8));
  }

  private static void writeJson(HttpServletResponse resp, int status, String body) throws IOException {
    byte[] out = body.getBytes(StandardCharsets.UTF_8);
    resp.setStatus(status);
    resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
    resp.setContentType("application/json; charset=UTF-8");
    resp.setContentLength(out.length);
    resp.getOutputStream().write(out);
  }
}
