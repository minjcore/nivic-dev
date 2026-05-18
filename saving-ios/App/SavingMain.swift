import SwiftUI

@main
struct SavingMain: App {
    @UIApplicationDelegateAdaptor(SavingAppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            SavingApp()
        }
    }
}
