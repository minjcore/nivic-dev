package dev.nivic.sevlet.auth;

import dev.nivic.auth.LoginApprovalBroker;
import dev.nivic.auth.LoginApprovalBroker.Request;
import dev.nivic.coa.api.MiniJson;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Web side of app-approved login flow.
 *
 * <p>POST /api/auth/web/login/start -> create pending request
 * GET /api/auth/web/login/status?requestId=... -> poll status (and create session if approved)
 * GET /api/auth/web/me -> current session principal.</p>
 */
@WebServlet(
    name = "WebLoginServlet",
    urlPatterns = {"/api/auth/web/login/start", "/api/auth/web/login/status", "/api/auth/web/me"})
public final class WebLoginServlet extends HttpServlet {

  private static final int MAX_BODY = 8_192;
  private static final String SESSION_USER_KEY = "auth.web.user";

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if (!"/api/auth/web/login/start".equals(req.getServletPath())) {
      resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
      return;
    }
    LoginApprovalBroker broker = LoginApprovalBroker.from(getServletContext());
    MiniJson body;
    try {
      body = parseBody(req);
    } catch (IllegalArgumentException e) {
      writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"ok\":false,\"error\":\"invalid_json\"}");
      return;
    }
    String userHint = body.optString("userHint");
    Request request = broker.create(userHint);
    writeJson(
        resp,
        HttpServletResponse.SC_OK,
        "{"
            + "\"ok\":true,"
            + "\"requestId\":"
            + MiniJson.str(request.requestId())
            + ",\"userCode\":"
            + MiniJson.str(request.userCode())
            + ",\"status\":\""
            + request.status().name()
            + "\",\"expiresAtEpochSec\":"
            + request.expiresAtEpochSec()
            + "}");
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String path = req.getServletPath();
    if ("/api/auth/web/login/status".equals(path)) {
      handleStatus(req, resp);
      return;
    }
    if ("/api/auth/web/me".equals(path)) {
      Object principal = req.getSession(false) == null ? null : req.getSession(false).getAttribute(SESSION_USER_KEY);
      if (principal == null) {
        writeJson(resp, HttpServletResponse.SC_UNAUTHORIZED, "{\"ok\":false,\"error\":\"not_authenticated\"}");
        return;
      }
      writeJson(
          resp,
          HttpServletResponse.SC_OK,
          "{\"ok\":true,\"authenticated\":true,\"userId\":" + MiniJson.str(String.valueOf(principal)) + "}");
      return;
    }
    resp.sendError(HttpServletResponse.SC_NOT_FOUND);
  }

  private void handleStatus(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String requestId = req.getParameter("requestId");
    if (requestId == null || requestId.isBlank()) {
      writeJson(resp, HttpServletResponse.SC_BAD_REQUEST, "{\"ok\":false,\"error\":\"request_id_required\"}");
      return;
    }
    LoginApprovalBroker broker = LoginApprovalBroker.from(getServletContext());
    Request request = broker.get(requestId.trim());
    if (request == null) {
      writeJson(resp, HttpServletResponse.SC_NOT_FOUND, "{\"ok\":false,\"error\":\"request_not_found_or_expired\"}");
      return;
    }
    if (request.status() == LoginApprovalBroker.Status.APPROVED) {
      String principal = request.appUserId() != null ? request.appUserId() : "app-user";
      HttpSession session = req.getSession(true);
      session.setAttribute(SESSION_USER_KEY, principal);
      writeJson(
          resp,
          HttpServletResponse.SC_OK,
          "{"
              + "\"ok\":true,\"status\":\"APPROVED\",\"authenticated\":true,"
              + "\"userId\":"
              + MiniJson.str(principal)
              + "}");
      return;
    }
    if (request.status() == LoginApprovalBroker.Status.DENIED) {
      writeJson(resp, HttpServletResponse.SC_OK, "{\"ok\":true,\"status\":\"DENIED\"}");
      return;
    }
    writeJson(
        resp,
        HttpServletResponse.SC_OK,
        "{"
            + "\"ok\":true,\"status\":\"PENDING\",\"requestId\":"
            + MiniJson.str(request.requestId())
            + ",\"userCode\":"
            + MiniJson.str(request.userCode())
            + ",\"expiresAtEpochSec\":"
            + request.expiresAtEpochSec()
            + "}");
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
