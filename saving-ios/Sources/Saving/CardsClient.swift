import Foundation

// ─── Models ───────────────────────────────────────────────────────────────────

public struct CardInfo: Codable, Identifiable {
    public let id:        String
    public let uid:       UInt32
    public let last4:     String
    public let bank:      String
    public let expiry:    String
    public let label:     String?
    public let status:    String
    public let createdAt: Int64

    enum CodingKeys: String, CodingKey {
        case id, uid, last4, bank, expiry, label, status
        case createdAt = "created_at"
    }
}

public struct TopUpResult: Codable {
    public let topupID: String
    public let amount:  UInt64
    public let status:  String

    enum CodingKeys: String, CodingKey {
        case topupID = "topup_id"
        case amount, status
    }
}

// ─── Client ───────────────────────────────────────────────────────────────────

public struct CardsClient {
    public let baseURL:    String
    public let wireToken:  String

    public init(baseURL: String = "http://localhost:8091",
                wireToken: String = "change-me-in-production") {
        self.baseURL   = baseURL
        self.wireToken = wireToken
    }

    private func authedRequest(method: String, path: String) throws -> URLRequest {
        guard let url = URL(string: "\(baseURL)\(path)") else {
            throw CardError.network("bad cards URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json",      forHTTPHeaderField: "Content-Type")
        req.setValue(wireToken,               forHTTPHeaderField: "X-Wire-Token")
        req.timeoutInterval = 10
        return req
    }

    private func checkResponse(_ data: Data, _ resp: URLResponse) throws {
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else {
            struct E: Decodable { let error: String }
            let msg = (try? JSONDecoder().decode(E.self, from: data))?.error ?? "request failed"
            throw CardError.rejected(msg)
        }
    }

    public func listCards(uid: UInt32) async throws -> [CardInfo] {
        let req = try authedRequest(method: "GET", path: "/users/\(uid)/cards")
        let (data, resp) = try await URLSession.shared.data(for: req)
        try checkResponse(data, resp)
        return try JSONDecoder().decode([CardInfo].self, from: data)
    }

    public func addCard(uid: UInt32, last4: String, bank: String,
                        expiry: String, label: String) async throws -> String {
        var req = try authedRequest(method: "POST", path: "/users/\(uid)/cards")
        req.httpBody = try JSONEncoder().encode([
            "last4": last4, "bank": bank, "expiry": expiry, "label": label
        ])
        let (data, resp) = try await URLSession.shared.data(for: req)
        try checkResponse(data, resp)
        struct R: Decodable { let card_id: String }
        return try JSONDecoder().decode(R.self, from: data).card_id
    }

    public func removeCard(uid: UInt32, cardID: String) async throws {
        let req = try authedRequest(method: "DELETE", path: "/users/\(uid)/cards/\(cardID)")
        let (data, resp) = try await URLSession.shared.data(for: req)
        try checkResponse(data, resp)
    }

    public func registerDeviceToken(uid: UInt32, token: String) async throws {
        var req = try authedRequest(method: "POST", path: "/users/\(uid)/device-token")
        req.httpBody = try JSONEncoder().encode(["token": token])
        let (_, resp) = try await URLSession.shared.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 204 else {
            throw CardError.rejected("device token registration failed")
        }
    }

    public func topUp(uid: UInt32, cardID: String, amount: UInt64) async throws -> TopUpResult {
        var req = try authedRequest(method: "POST",
                                    path: "/users/\(uid)/cards/\(cardID)/topup")
        req.httpBody = try JSONEncoder().encode(["amount": amount])
        let (data, resp) = try await URLSession.shared.data(for: req)
        try checkResponse(data, resp)
        return try JSONDecoder().decode(TopUpResult.self, from: data)
    }
}

public enum CardError: Error, LocalizedError {
    case rejected(String)
    case network(String)

    public var errorDescription: String? {
        switch self {
        case .rejected(let m): return m
        case .network(let m):  return "Lỗi mạng: \(m)"
        }
    }
}
