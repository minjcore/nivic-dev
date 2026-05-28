import SwiftUI

@main
struct SavingMain: App {
    @UIApplicationDelegateAdaptor(SavingAppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            SavingApp(
                host:         "wire.nivic.dev",
                port:         7474,
                secret:       "saving_wire_secret_changeme",
                merchantsURL: "http://saving.nivic.dev:8090",
                cardsURL:     "http://saving.nivic.dev:8091",
                tomcatsURL:   "http://saving.nivic.dev:8093"
            )
        }
    }
}
