package dev.nivic.sevlet.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * <strong>Form container manifest</strong>: one JSON document the client loads (via proxy) that
 * describes:
 *
 * <ul>
 *   <li>{@code definitionSource} — {@code json} here; XML can be parsed server-side into the same
 *       manifest.
 *   <li>{@code container} — id and named <strong>slots</strong> for generated pieces.
 *   <li>{@code schema} — field list for the dynamic form body.
 *   <li>{@code attachments} — codegen endpoints ({@code optionsUrl}) bound to slots; the browser
 *       fetches each and passes {@code vueOptions} to {@code defineComponent} in the host app.
 * </ul>
 */
@WebServlet(name = "FormManifestServlet", urlPatterns = "/api/dynamic-form/manifest")
public class FormManifestServlet extends HttpServlet {

  private static final String PREFIX =
      """
      {
        "definitionSource": "json",
        "definitionVersion": "1",
        "container": {
          "id": "merchants-admin-root",
          "slots": ["beforeFields", "afterFields"]
        },
        "schema":
      """
          .trim();

  private static final String SUFFIX =
      """
      ,
        "attachments": [
          {
            "id": "merchant-policy-banner",
            "slot": "beforeFields",
            "componentName": "GeneratedMerchantPolicyBanner",
            "optionsUrl": "/api/dynamic-form/gen/hint-banner.json"
          },
          {
            "id": "merchant-footer",
            "slot": "afterFields",
            "componentName": "GeneratedMerchantFooter",
            "optionsUrl": "/api/dynamic-form/gen/footer-note.json"
          }
        ]
      }
      """
          .trim();

  private static final byte[] MANIFEST_JSON;

  static {
    String schema = new String(FormDefinitionJson.SCHEMA_JSON, StandardCharsets.UTF_8);
    MANIFEST_JSON = (PREFIX + schema + SUFFIX).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
    resp.setContentType("application/json; charset=UTF-8");
    resp.setHeader("Cache-Control", "no-store");
    resp.getOutputStream().write(MANIFEST_JSON);
  }
}
