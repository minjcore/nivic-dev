package dev.nivic.sevlet;

import dev.nivic.config.ApplicationProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Reverse HTTP proxy: maps {@code /httpproxy/<path>} to {@code <targetBase><path>?<query>}.
 *
 * <p>Configure {@code targetBase} (servlet init-param), {@code HTTP_PROXY_TARGET_BASE} (env,
 * {@code -D}, or {@code application.properties}), or context-param {@code http.proxy.targetBase} in
 * {@code web.xml}. Precedence: servlet init-param, then resolved {@code HTTP_PROXY_TARGET_BASE},
 * then context-param.</p>
 *
 * <p>Example: {@code targetBase=https://api.example.com/v1} and {@code GET /httpproxy/users/1}
 * → {@code GET https://api.example.com/v1/users/1}. If unset, all requests return 503.</p>
 *
 * <p>Optional init-params: {@code connectTimeoutMs} (default 10000), {@code requestTimeoutMs}
 * (default 60000).</p>
 *
 * <p>Demo: {@code webapp/dynamic-form/index.html} loads {@code /httpproxy/api/dynamic-form/manifest}
 * (Merchants UI: container + schema + attachment URLs), then each {@code /api/dynamic-form/gen/*.json} codegen
 * payload, then POSTs submit — set {@code HTTP_PROXY_TARGET_BASE} to the app origin (e.g. {@code
 * http://127.0.0.1:8080}). See {@link dev.nivic.sevlet.api.FormManifestServlet}, {@link
 * dev.nivic.sevlet.api.FormCodegenServlet}, {@link dev.nivic.sevlet.api.FormSubmitServlet}.</p>
 */
@WebServlet(
    name = "HttpProxyServlet",
    urlPatterns = "/httpproxy/*",
    initParams = {
      @WebInitParam(name = "connectTimeoutMs", value = "10000"),
      @WebInitParam(name = "requestTimeoutMs", value = "60000")
    })
public class HttpProxyServlet extends HttpServlet {

  private static final Set<String> HOP_BY_HOP =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailers",
          "transfer-encoding",
          "upgrade",
          "host",
          "content-length");

  private volatile String targetBase;
  private volatile HttpClient httpClient;
  private volatile Duration requestTimeout;

  @Override
  public void init() throws ServletException {
    super.init();
    String fromParam = trimToNull(getServletConfig().getInitParameter("targetBase"));
    String fromResolved = trimToNull(ApplicationProperties.resolve("HTTP_PROXY_TARGET_BASE"));
    String fromCtx = trimToNull(getServletContext().getInitParameter("http.proxy.targetBase"));
    targetBase = fromParam != null ? fromParam : (fromResolved != null ? fromResolved : fromCtx);
    if (targetBase != null) {
      targetBase = targetBase.replaceAll("/+$", "");
      validateTargetBase(targetBase);
    }

    int connectMs = parsePositiveInt(getServletConfig().getInitParameter("connectTimeoutMs"), 10_000);
    int requestMs = parsePositiveInt(getServletConfig().getInitParameter("requestTimeoutMs"), 60_000);
    requestTimeout = Duration.ofMillis(requestMs);
    httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMs)).followRedirects(HttpClient.Redirect.NORMAL).build();

    if (targetBase != null) {
      getServletContext().log("HttpProxyServlet: targetBase=" + targetBase);
    } else {
      getServletContext().log("HttpProxyServlet: disabled (no targetBase / HTTP_PROXY_TARGET_BASE)");
    }
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    if (targetBase == null) {
      resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "HTTP proxy not configured");
      return;
    }

    String pathInfo = req.getPathInfo();
    if (pathInfo != null && pathInfo.contains("..")) {
      resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "invalid path");
      return;
    }
    String suffix = pathInfo == null ? "" : pathInfo;
    StringBuilder url = new StringBuilder(targetBase.length() + suffix.length() + 32);
    url.append(targetBase);
    if (!suffix.isEmpty()) {
      url.append(suffix.startsWith("/") ? suffix : "/" + suffix);
    }
    String q = req.getQueryString();
    if (q != null) {
      url.append('?').append(q);
    }

    URI uri;
    try {
      uri = URI.create(url.toString());
    } catch (IllegalArgumentException e) {
      resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "invalid upstream URL");
      return;
    }

    if (!isAllowedUpstream(uri)) {
      resp.sendError(HttpServletResponse.SC_FORBIDDEN, "upstream host mismatch");
      return;
    }

    HttpRequest.Builder rb =
        HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .method(req.getMethod(), bodyPublisher(req));

    for (String name : Collections.list(req.getHeaderNames())) {
      if (isHopByHop(name)) {
        continue;
      }
      for (String value : Collections.list(req.getHeaders(name))) {
        rb.header(name, value);
      }
    }

    HttpRequest upstreamReq = rb.build();
    HttpResponse<InputStream> upstreamResp;
    try {
      upstreamResp = httpClient.send(upstreamReq, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      getServletContext().log("HttpProxyServlet upstream interrupted");
      resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "upstream interrupted");
      return;
    } catch (IOException e) {
      getServletContext().log("HttpProxyServlet upstream error: " + e);
      resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "upstream error");
      return;
    }

    int status = upstreamResp.statusCode();
    resp.setStatus(status);
    for (var header : upstreamResp.headers().map().entrySet()) {
      String name = header.getKey();
      if (isHopByHop(name)) {
        continue;
      }
      for (String value : header.getValue()) {
        resp.addHeader(name, value);
      }
    }

    try (InputStream in = upstreamResp.body();
        OutputStream out = resp.getOutputStream()) {
      if (in != null) {
        in.transferTo(out);
      }
    }
  }

  private static HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest req) {
    String m = req.getMethod().toUpperCase(Locale.ROOT);
    if ("GET".equals(m) || "HEAD".equals(m) || "DELETE".equals(m) || "OPTIONS".equals(m)) {
      return HttpRequest.BodyPublishers.noBody();
    }
    return HttpRequest.BodyPublishers.ofInputStream(
        () -> {
          try {
            return req.getInputStream();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  private boolean isAllowedUpstream(URI uri) {
    try {
      URI base = URI.create(targetBase);
      String h1 = uri.getHost();
      String h2 = base.getHost();
      if (h1 == null || h2 == null) {
        return false;
      }
      if (!h1.equalsIgnoreCase(h2)) {
        return false;
      }
      int p1 = uri.getPort() > 0 ? uri.getPort() : defaultPort(uri.getScheme());
      int p2 = base.getPort() > 0 ? base.getPort() : defaultPort(base.getScheme());
      return p1 == p2;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static int defaultPort(String scheme) {
    if (scheme != null && scheme.equalsIgnoreCase("https")) {
      return 443;
    }
    return 80;
  }

  private static void validateTargetBase(String base) throws ServletException {
    try {
      URI u = URI.create(base);
      if (u.getScheme() == null || u.getHost() == null) {
        throw new ServletException("targetBase must be absolute with host: " + base);
      }
      if (!"http".equalsIgnoreCase(u.getScheme()) && !"https".equalsIgnoreCase(u.getScheme())) {
        throw new ServletException("targetBase scheme must be http or https: " + base);
      }
    } catch (IllegalArgumentException e) {
      throw new ServletException("invalid targetBase: " + base, e);
    }
  }

  private static boolean isHopByHop(String name) {
    return name != null && HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
  }

  private static String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  private static int parsePositiveInt(String raw, int def) {
    if (raw == null || raw.isBlank()) {
      return def;
    }
    try {
      int v = Integer.parseInt(raw.trim());
      return v > 0 ? v : def;
    } catch (NumberFormatException e) {
      return def;
    }
  }
}
