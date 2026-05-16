#if os(iOS)
import UIKit

// ─── Notification name ────────────────────────────────────────────────────────

extension Notification.Name {
    /// Posted by SavingAppDelegate when APNs returns a device token.
    /// userInfo["token"] = hex-encoded token string.
    public static let savingDeviceToken = Notification.Name("saving.deviceToken")
}

// ─── AppDelegate ──────────────────────────────────────────────────────────────

/// Forward to your @main App struct via @UIApplicationDelegateAdaptor:
///
///     @UIApplicationDelegateAdaptor(SavingAppDelegate.self) var appDelegate
///
public final class SavingAppDelegate: NSObject, UIApplicationDelegate {

    public func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        NotificationCenter.default.post(
            name: .savingDeviceToken,
            object: nil,
            userInfo: ["token": hex]
        )
    }

    public func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        // Simulator always fails — safe to ignore
    }
}
#endif
