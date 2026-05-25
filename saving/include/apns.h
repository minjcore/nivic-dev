#pragma once

/* APNs push notification delivery via HTTP/2.
 *
 * Config via environment variables:
 *   APNS_KEY_PATH   — path to .p8 private key file (from Apple Developer portal)
 *   APNS_KEY_ID     — 10-char key ID (e.g. "ABCD1234EF")
 *   APNS_TEAM_ID    — 10-char team ID (e.g. "ABCD1234EF")
 *   APNS_BUNDLE_ID  — app bundle ID (e.g. "com.example.saving")
 *   APNS_SANDBOX    — "1" for sandbox, "0" or unset for production
 *
 * If any required env var is missing, APNs is silently disabled.
 * All notify calls become no-ops when disabled. */

/* Call once at startup before any worker threads spawn. */
int  apns_init(void);

/* Queue a push notification to device_token (64-char hex APNs token).
 * Returns immediately; delivery is async on a background thread.
 * title/body: UTF-8, max 200 bytes each. */
void apns_notify_async(const char *device_token,
                       const char *title,
                       const char *body);
