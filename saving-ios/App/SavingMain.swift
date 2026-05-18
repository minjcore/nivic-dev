import SwiftUI

@main
struct SavingMain: App {
    @UIApplicationDelegateAdaptor(SavingAppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            SavingApp(
                host:         "10.10.10.156",
                merchantsURL: "http://10.10.10.156:8090",
                cardsURL:     "http://10.10.10.156:8091",
                tomcatsURL:   "http://10.10.10.156:8093"
            )
        }
    }
}
