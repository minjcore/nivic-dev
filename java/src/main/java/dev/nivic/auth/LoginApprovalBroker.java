package dev.nivic.auth;

import dev.nivic.config.ApplicationProperties;
import jakarta.servlet.ServletContext;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory bridge between web login requests and app approvals.
 *
 * <p>Flow: web creates request -> app approves/denies by requestId -> web polls status and creates
 * session on approval.</p>
 */
public final class LoginApprovalBroker {

  public static final String CONTEXT_KEY = LoginApprovalBroker.class.getName();
  private static final long DEFAULT_TTL_SECONDS = 180L;

  private final Map<String, Request> requests = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();
  private final long ttlSeconds;
  private final String appApprovalToken;

  private LoginApprovalBroker(long ttlSeconds, String appApprovalToken) {
    this.ttlSeconds = ttlSeconds;
    this.appApprovalToken = appApprovalToken;
  }

  public static LoginApprovalBroker from(ServletContext context) {
    Objects.requireNonNull(context, "context");
    synchronized (context) {
      Object raw = context.getAttribute(CONTEXT_KEY);
      if (raw instanceof LoginApprovalBroker broker) {
        return broker;
      }
      long ttl = parsePositiveLong(ApplicationProperties.resolve("WEB_LOGIN_REQUEST_TTL_SECONDS"), DEFAULT_TTL_SECONDS);
      String token = ApplicationProperties.resolve("APP_APPROVAL_TOKEN");
      if (token == null || token.isBlank()) {
        token = "dev-allow-token";
      }
      LoginApprovalBroker created = new LoginApprovalBroker(ttl, token);
      context.setAttribute(CONTEXT_KEY, created);
      return created;
    }
  }

  public Request create(String webUserHint) {
    cleanupExpired();
    String requestId = randomToken(24);
    String userCode = randomCode();
    long now = Instant.now().getEpochSecond();
    long expiresAt = now + ttlSeconds;
    Request request =
        new Request(requestId, sanitize(webUserHint), userCode, Status.PENDING, null, null, now, expiresAt);
    requests.put(requestId, request);
    return request;
  }

  public Request get(String requestId) {
    cleanupExpired();
    Request request = requests.get(requestId);
    if (request == null) {
      return null;
    }
    if (isExpired(request)) {
      requests.remove(requestId);
      return null;
    }
    if (request.status == Status.PENDING) {
      return request;
    }
    return request;
  }

  public Request decide(String requestId, boolean approve, String appUserId) {
    cleanupExpired();
    Request current = requests.get(requestId);
    if (current == null || isExpired(current)) {
      requests.remove(requestId);
      return null;
    }
    if (current.status != Status.PENDING) {
      return current;
    }
    Request updated =
        new Request(
            current.requestId,
            current.webUserHint,
            current.userCode,
            approve ? Status.APPROVED : Status.DENIED,
            sanitize(appUserId),
            Instant.now().getEpochSecond(),
            current.createdAtEpochSec,
            current.expiresAtEpochSec);
    requests.put(requestId, updated);
    return updated;
  }

  public boolean isAppTokenValid(String value) {
    return value != null && value.equals(appApprovalToken);
  }

  public static String bearerToken(String authzHeader) {
    if (authzHeader == null) {
      return null;
    }
    String raw = authzHeader.trim();
    if (!raw.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return null;
    }
    return raw.substring(7).trim();
  }

  private boolean isExpired(Request request) {
    return request.expiresAtEpochSec <= Instant.now().getEpochSecond();
  }

  private void cleanupExpired() {
    long now = Instant.now().getEpochSecond();
    Iterator<Map.Entry<String, Request>> it = requests.entrySet().iterator();
    while (it.hasNext()) {
      Request request = it.next().getValue();
      if (request.expiresAtEpochSec <= now) {
        it.remove();
      }
    }
  }

  private String randomToken(int bytes) {
    byte[] raw = new byte[bytes];
    random.nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private String randomCode() {
    int value = 100_000 + random.nextInt(900_000);
    return Integer.toString(value);
  }

  private static String sanitize(String input) {
    if (input == null) {
      return null;
    }
    String out = input.trim();
    if (out.isEmpty()) {
      return null;
    }
    return out.length() <= 128 ? out : out.substring(0, 128);
  }

  private static long parsePositiveLong(String raw, long fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      long value = Long.parseLong(raw.trim());
      return value > 0 ? value : fallback;
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  public enum Status {
    PENDING,
    APPROVED,
    DENIED
  }

  public record Request(
      String requestId,
      String webUserHint,
      String userCode,
      Status status,
      String appUserId,
      Long decidedAtEpochSec,
      long createdAtEpochSec,
      long expiresAtEpochSec) {}
}
