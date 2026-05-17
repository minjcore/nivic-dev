#if os(iOS)
import Foundation

// ─── Payment request ──────────────────────────────────────────────────────────
//  Signed by Merchants Host using merchant's Ed25519 private key.
//  msg = mid(4 BE) || amount(8 BE) || ts(8 BE) || order_id(utf-8)
//  QR:  saving://pay?pr=BASE64URL(JSON)

public struct PaymentRequest: Codable {
    public let mid:     UInt32
    public let orderID: String
    public let amount:  UInt64
    public let ts:      Int64   // unix milliseconds
    public let sig:     String  // base64(ed25519 sig, 64 bytes)

    enum CodingKeys: String, CodingKey {
        case mid, orderID = "order_id", amount, ts, sig
    }

    public static func decode(base64url: String) throws -> PaymentRequest {
        var b64 = base64url
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64) else {
            throw DecodingError.dataCorrupted(
                .init(codingPath: [], debugDescription: "invalid base64url"))
        }
        return try JSONDecoder().decode(PaymentRequest.self, from: data)
    }
}

// ─── Merchant info ────────────────────────────────────────────────────────────

public struct MerchantInfo: Codable {
    public let mid:       UInt32
    public let name:      String
    public let pubkeyB64: String
    public let createdAt: Int64

    enum CodingKeys: String, CodingKey {
        case mid, name, pubkeyB64 = "pubkey_b64", createdAt = "created_at"
    }
}

// ─── Order info ───────────────────────────────────────────────────────────────

public struct OrderInfo: Codable {
    public let id:        String
    public let mid:       UInt32
    public let amount:    UInt64
    public let note:      String?
    public let status:    String   // "pending" | "paid" | "expired"
    public let createdAt: Int64
    public let paidAt:    Int64?
    public let paidBy:    UInt32?

    enum CodingKeys: String, CodingKey {
        case id, mid, amount, note, status
        case createdAt = "created_at"
        case paidAt    = "paid_at"
        case paidBy    = "paid_by"
    }
}

// ─── Verify response ──────────────────────────────────────────────────────────

public struct VerifyResponse: Codable {
    public let valid:    Bool
    public let merchant: MerchantInfo
    public let order:    OrderInfo?
}

// ─── Client ───────────────────────────────────────────────────────────────────

public struct MerchantsClient {
    public let baseURL:   String
    public let wireToken: String  // X-Wire-Token for confirm endpoint

    public init(baseURL: String = "http://localhost:8090",
                wireToken: String = "change-me-in-production") {
        self.baseURL   = baseURL
        self.wireToken = wireToken
    }

    // Verify a signed payment_request QR before showing the pay screen.
    public func verify(_ pr: PaymentRequest) async throws -> VerifyResponse {
        guard let url = URL(string: "\(baseURL)/payment_request/verify") else {
            throw VerifyError.network("bad merchants host URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody   = try JSONEncoder().encode(pr)
        req.timeoutInterval = 10

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else {
            struct ErrBody: Decodable { let error: String }
            let msg = (try? JSONDecoder().decode(ErrBody.self, from: data))?.error
                      ?? "verification failed"
            throw VerifyError.rejected(msg)
        }
        return try JSONDecoder().decode(VerifyResponse.self, from: data)
    }

    // Self-service merchant onboarding. Returns the merchant token (shown once).
    public func onboard(uid: UInt32, name: String) async throws -> String {
        guard let url = URL(string: "\(baseURL)/merchants/onboard") else {
            throw VerifyError.network("bad URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONEncoder().encode(["uid": uid, "name": name])
        req.timeoutInterval = 10
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw VerifyError.network("no response") }
        if http.statusCode == 409 { throw VerifyError.rejected("Bạn đã là merchant rồi") }
        guard http.statusCode == 200 else {
            struct E: Decodable { let error: String }
            let msg = (try? JSONDecoder().decode(E.self, from: data))?.error ?? "onboard failed"
            throw VerifyError.rejected(msg)
        }
        struct Resp: Decodable { let token: String }
        return try JSONDecoder().decode(Resp.self, from: data).token
    }

    // Merchant dashboard stats.
    public func stats(mid: UInt32, token: String) async throws -> MerchantStats {
        guard let url = URL(string: "\(baseURL)/merchants/\(mid)/stats") else {
            throw VerifyError.network("bad URL")
        }
        var req = URLRequest(url: url)
        req.setValue(token, forHTTPHeaderField: "X-Merchant-Token")
        req.timeoutInterval = 10
        let (data, _) = try await URLSession.shared.data(for: req)
        return try JSONDecoder().decode(MerchantStats.self, from: data)
    }

    // List recent orders.
    public func listOrders(mid: UInt32, token: String) async throws -> [OrderInfo] {
        guard let url = URL(string: "\(baseURL)/merchants/\(mid)/orders") else {
            throw VerifyError.network("bad URL")
        }
        var req = URLRequest(url: url)
        req.setValue(token, forHTTPHeaderField: "X-Merchant-Token")
        req.timeoutInterval = 10
        let (data, _) = try await URLSession.shared.data(for: req)
        return (try? JSONDecoder().decode([OrderInfo].self, from: data)) ?? []
    }

    // Create an order and get back a QR payload.
    public func createOrder(mid: UInt32, token: String, amount: UInt64, note: String) async throws -> CreateOrderResponse {
        guard let url = URL(string: "\(baseURL)/merchants/\(mid)/orders") else {
            throw VerifyError.network("bad URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(token, forHTTPHeaderField: "X-Merchant-Token")
        req.httpBody = try JSONEncoder().encode(["amount": amount, "note": note])
        req.timeoutInterval = 10
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else {
            throw VerifyError.rejected("Tạo đơn thất bại")
        }
        return try JSONDecoder().decode(CreateOrderResponse.self, from: data)
    }

    // Notify Merchants Host that the order was paid. Called after Wire ACKs payment.
    public func confirmPaid(orderID: String, paidBy: UInt32) async {
        guard let url = URL(string: "\(baseURL)/orders/\(orderID)/confirm") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(wireToken, forHTTPHeaderField: "X-Wire-Token")
        req.httpBody = try? JSONEncoder().encode(["paid_by": paidBy])
        req.timeoutInterval = 10
        _ = try? await URLSession.shared.data(for: req)
        // Fire-and-forget: payment already succeeded in Wire; order confirmation is best-effort.
    }
}

public struct MerchantStats: Codable {
    public let totalEarned: UInt64
    public let orderCount:  Int
    enum CodingKeys: String, CodingKey {
        case totalEarned = "total_earned"
        case orderCount  = "order_count"
    }
}

public struct CreateOrderResponse: Codable {
    public let orderID: String
    public let pr:      String  // base64url payment request
    public let qrURL:   String
    enum CodingKeys: String, CodingKey {
        case orderID = "order_id", pr, qrURL = "qr_url"
    }
}

public enum VerifyError: Error, LocalizedError {
    case rejected(String)
    case network(String)

    public var errorDescription: String? {
        switch self {
        case .rejected(let m): return m
        case .network(let m):  return "Lỗi mạng: \(m)"
        }
    }
}
#endif
