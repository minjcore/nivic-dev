import SwiftUI

@main
struct SavingMain: App {
    @UIApplicationDelegateAdaptor(SavingAppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            SavingApp(
                host:         "wire.nivic.dev",
                merchantsURL: "http://wire.nivic.dev:8090",
                cardsURL:     "http://wire.nivic.dev:8091",
                tomcatsURL:   "http://wire.nivic.dev:8093"
            )
        }
    }
}
