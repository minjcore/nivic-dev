package dev.nivic.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * HTTP middleware: stable {@code X-Request-Id} (from client or generated), response echo, and
 * access-style timing log via {@link jakarta.servlet.ServletContext#log(String)}.
 */
@WebFilter(filterName = "RequestMiddlewareFilter", urlPatterns = "/*")
public final class RequestMiddlewareFilter implements Filter {

  public static final String REQUEST_ID_HEADER = "X-Request-Id";

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest httpRequest)
        || !(response instanceof HttpServletResponse httpResponse)) {
      chain.doFilter(request, response);
      return;
    }

    String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
    if (requestId == null || requestId.isBlank()) {
      requestId = UUID.randomUUID().toString();
    } else {
      requestId = requestId.trim();
    }
    httpResponse.setHeader(REQUEST_ID_HEADER, requestId);

    long startNanos = System.nanoTime();
    StatusCaptureResponse capture = new StatusCaptureResponse(httpResponse);
    try {
      chain.doFilter(httpRequest, capture);
    } finally {
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      httpRequest
          .getServletContext()
          .log(
              String.format(
                  "[%s] %s %s -> %d (%d ms)",
                  requestId,
                  httpRequest.getMethod(),
                  httpRequest.getRequestURI(),
                  capture.statusCode(),
                  elapsedMs));
    }
  }

  /** Captures status set by the servlet / downstream filters for logging. */
  private static final class StatusCaptureResponse extends HttpServletResponseWrapper {

    private int status = HttpServletResponse.SC_OK;

    StatusCaptureResponse(HttpServletResponse response) {
      super(response);
    }

    int statusCode() {
      return status;
    }

    @Override
    public void setStatus(int sc) {
      super.setStatus(sc);
      status = sc;
    }

    @Override
    public void sendError(int sc) throws IOException {
      status = sc;
      super.sendError(sc);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
      status = sc;
      super.sendError(sc, msg);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
      status = HttpServletResponse.SC_FOUND;
      super.sendRedirect(location);
    }
  }
}
