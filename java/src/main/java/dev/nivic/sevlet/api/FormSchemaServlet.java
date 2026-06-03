package dev.nivic.sevlet.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Returns the core JSON <strong>field schema</strong> (same object embedded in {@link
 * FormManifestServlet}). Prefer loading {@code /api/dynamic-form/manifest} for the full container +
 * attachments flow.
 */
@WebServlet(name = "FormSchemaServlet", urlPatterns = "/api/dynamic-form/schema")
public class FormSchemaServlet extends HttpServlet {

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
    resp.setContentType("application/json; charset=UTF-8");
    resp.setHeader("Cache-Control", "no-store");
    resp.getOutputStream().write(FormDefinitionJson.SCHEMA_JSON);
  }
}
