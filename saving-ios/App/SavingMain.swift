import SwiftUI

@main
struct SavingMain: App {
    @UIApplicationDelegateAdaptor(SavingAppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            SavingApp(
                host:         "127.0.0.1",
                merchantsURL: "http://127.0.0.1:8090",
                cardsURL:     "http://127.0.0.1:8091",
                tomcatsURL:   "http://127.0.0.1:8093"
            )
        }
    }
}
