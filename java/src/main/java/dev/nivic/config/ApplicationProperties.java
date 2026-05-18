package dev.nivic.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Loads {@code application.properties} from the classpath and resolves configuration keys with a
 * fixed precedence: OS environment variable, then Java system property ({@code -Dkey=value}), then
 * an entry in {@code application.properties}.
 */
public final class ApplicationProperties {

  private static final Properties FILE = new Properties();

  static {
    try (InputStream in =
        ApplicationProperties.class.getClassLoader().getResourceAsStream("application.properties")) {
      if (in != null) {
        FILE.load(new InputStreamReader(in, StandardCharsets.UTF_8));
      }
    } catch (IOException ignored) {
      // Leave FILE empty; missing or unreadable file is non-fatal.
    }
  }

  private ApplicationProperties() {}

  /**
   * @return trimmed value, or {@code null} if unset or blank in all sources
   */
  public static String resolve(String key) {
    String v = System.getenv(key);
    if (notBlank(v)) {
      return v.trim();
    }
    v = System.getProperty(key);
    if (notBlank(v)) {
      return v.trim();
    }
    v = FILE.getProperty(key);
    return notBlank(v) ? v.trim() : null;
  }

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }
}
