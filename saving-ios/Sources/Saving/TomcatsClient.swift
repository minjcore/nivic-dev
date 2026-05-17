#if os(iOS)
import Foundation

struct TomcatsClient {
    private let baseURL: String
    private let session = URLSession.shared

    init(baseURL: String) {
        self.baseURL = baseURL
    }

    func registerToken(uid: UInt32, token: String) async throws {
        guard let url = URL(string: "\(baseURL)/tokens") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: [
            "uid": uid,
            "platform": "ios",
            "token": token
        ])
        let (_, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse, http.statusCode == 204 else {
            throw URLError(.badServerResponse)
        }
    }
}
#endif
