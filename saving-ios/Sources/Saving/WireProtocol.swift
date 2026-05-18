import Foundation
import CryptoKit

// ─── Wire Protocol v1 constants ───────────────────────────────────────────

enum WireType: UInt8 {
    // Client → Server
    case ping            = 0x01
    case login           = 0x02
    case logout          = 0x03
    case createAccount   = 0x10
    case transfer        = 0x11
    case getBalance      = 0x12
    case addGuardian     = 0x13
    case recoveryReq     = 0x14
    case recoveryApprove = 0x15
    case getHistory      = 0x16
    case createIntent    = 0x20
    case payIntent       = 0x21
    case enrollTotp      = 0x22

    // Server → Client (responses)
    case pong            = 0x80
    case loginAck        = 0x81
    case ack             = 0x82

    // Server → Client (push events)
    case evtTransferIn   = 0xC0
    case evtRecoveryReq  = 0xC1
    case evtRecoveryOK   = 0xC2
    case evtGuardianAdd  = 0xC3
}

enum WireCode: UInt8 {
    case ok              = 0x00
    case errBadFrame     = 0x01
    case errBadSig       = 0x02
    case errIdTaken      = 0x03
    case errIdReserved   = 0x04
    case errNotFound     = 0x05
    case errBadPassword  = 0x06
    case errBadToken     = 0x07
    case errLowBalance   = 0x08
    case errGuardianFull = 0x09
    case errNotGuardian  = 0x0A
    case errNeedGuardians  = 0x0B
    case errTotpInvalid    = 0x0C
    case errIntentSettled  = 0x0D
    case errInternal       = 0xFF
}

// ─── Account ID constraints ────────────────────────────────────────────────

enum AccountID {
    static let vipMax:  UInt32 = 16_777_215
    static let userMin: UInt32 = 16_777_216
    static let userMax: UInt32 = 4_294_967_295

    static func isValid(_ id: UInt32) -> Bool {
        id >= userMin && id <= userMax
    }
}

// ─── Frame encoding / decoding ─────────────────────────────────────────────
//
//  ┌──────────┬────────┬──────────┬──────────────────┬─────────────┐
//  │ len  4 B │ type 1B│ seq   4 B│ body  (len-41) B │  sig   32 B │
//  └──────────┴────────┴──────────┴──────────────────┴─────────────┘
//

struct WireFrame {
    static let overhead = 41  // 4 + 1 + 4 + 32
    static let maxSize  = 4096

    let type:     WireType
    let seq:      UInt32
    let body:     Data

    // Encode to raw bytes ready to send over TCP
    func encode(secret: SymmetricKey) -> Data {
        let totalLen = UInt32(WireFrame.overhead + body.count)
        var raw = Data(capacity: Int(totalLen))

        raw.appendBigEndian(totalLen)
        raw.append(type.rawValue)
        raw.appendBigEndian(seq)
        raw.append(body)

        let sig = HMAC<SHA256>.authenticationCode(for: raw, using: secret)
        raw.append(contentsOf: sig)
        return raw
    }

    // Parse from raw bytes (must be exactly one frame)
    static func decode(_ data: Data, secret: SymmetricKey) throws -> WireFrame {
        guard data.count >= WireFrame.overhead else {
            throw WireError.badFrame("too short: \(data.count) bytes")
        }
        let totalLen = data.readBigEndianUInt32(at: 0)
        guard data.count == totalLen else {
            throw WireError.badFrame("len mismatch: got \(data.count) expected \(totalLen)")
        }

        let sigSize = 32  // HMAC-SHA256 is always 32 bytes
        let covered     = data.prefix(data.count - sigSize)
        let receivedSig = data.suffix(sigSize)
        guard HMAC<SHA256>.isValidAuthenticationCode(receivedSig, authenticating: covered, using: secret) else {
            throw WireError.badSig
        }

        let rawType = data[4]
        guard let type = WireType(rawValue: rawType) else {
            throw WireError.badFrame("unknown type 0x\(String(rawType, radix: 16))")
        }
        let seq  = data.readBigEndianUInt32(at: 5)
        let body = data[9 ..< (data.count - sigSize)]
        return WireFrame(type: type, seq: seq, body: Data(body))
    }
}

// ─── Body builders (client → server) ──────────────────────────────────────

extension WireFrame {

    static func login(id: UInt32, passwordHash: Data, seq: UInt32) -> WireFrame {
        var body = Data()
        body.appendBigEndian(id)
        body.append(passwordHash)
        return WireFrame(type: .login, seq: seq, body: body)
    }

    static func createAccount(id: UInt32, passwordHash: Data, seq: UInt32) -> WireFrame {
        var body = Data()
        body.appendBigEndian(id)
        body.append(passwordHash)
        return WireFrame(type: .createAccount, seq: seq, body: body)
    }

    static func transfer(token: Data, toID: UInt32, amount: UInt64, seq: UInt32) -> WireFrame {
        var body = Data()
        body.append(token)
        body.appendBigEndian(toID)
        body.appendBigEndian(amount)
        return WireFrame(type: .transfer, seq: seq, body: body)
    }

    static func getBalance(token: Data, seq: UInt32) -> WireFrame {
        return WireFrame(type: .getBalance, seq: seq, body: token)
    }

    static func addGuardian(token: Data, guardianID: UInt32, seq: UInt32) -> WireFrame {
        var body = Data()
        body.append(token)
        body.appendBigEndian(guardianID)
        return WireFrame(type: .addGuardian, seq: seq, body: body)
    }

    static func recoveryReq(id: UInt32, seq: UInt32) -> WireFrame {
        var body = Data()
        body.appendBigEndian(id)
        return WireFrame(type: .recoveryReq, seq: seq, body: body)
    }

    static func recoveryApprove(token: Data, targetID: UInt32, seq: UInt32) -> WireFrame {
        var body = Data()
        body.append(token)
        body.appendBigEndian(targetID)
        return WireFrame(type: .recoveryApprove, seq: seq, body: body)
    }

    static func ping(seq: UInt32) -> WireFrame {
        WireFrame(type: .ping, seq: seq, body: Data())
    }

    static func getHistory(token: Data, seq: UInt32) -> WireFrame {
        WireFrame(type: .getHistory, seq: seq, body: token)
    }

    static func logout(token: Data, seq: UInt32) -> WireFrame {
        WireFrame(type: .logout, seq: seq, body: token)
    }

    /* CREATE_INTENT  body: [token 32B][request_id 8B][order_id 8B][amount 8B] */
    static func createIntent(token: Data, requestID: UInt64, orderID: UInt64,
                              amount: UInt64, seq: UInt32) -> WireFrame {
        var body = Data()
        body.append(token)
        body.appendBigEndian(requestID)
        body.appendBigEndian(orderID)
        body.appendBigEndian(amount)
        return WireFrame(type: .createIntent, seq: seq, body: body)
    }

    /* PAY_INTENT  body: [token 32B][merchant_id 4B][request_id 8B][totp_code 4B] */
    static func payIntent(token: Data, merchantID: UInt32, requestID: UInt64,
                          totpCode: UInt32, seq: UInt32) -> WireFrame {
        var body = Data()
        body.append(token)
        body.appendBigEndian(merchantID)
        body.appendBigEndian(requestID)
        body.appendBigEndian(totpCode)
        return WireFrame(type: .payIntent, seq: seq, body: body)
    }

    /* ENROLL_TOTP  body: [token 32B][customer_id 4B][secret 20B] */
    static func enrollTotp(token: Data, customerID: UInt32, secret: Data,
                            seq: UInt32) -> WireFrame {
        var body = Data()
        body.append(token)
        body.appendBigEndian(customerID)
        body.append(secret.prefix(20))
        return WireFrame(type: .enrollTotp, seq: seq, body: body)
    }
}

// ─── Body parsers (server → client) ───────────────────────────────────────

struct LoginAckBody {
    let code:  WireCode
    let token: Data      // 32 bytes, only valid when code == .ok
}

struct AckBody {
    let code: WireCode
    let data: Data
}

struct EvtTransferInBody {
    let fromID:  UInt32
    let amount:  UInt64
    let balance: UInt64
}

extension WireFrame {

    func parseLoginAck() throws -> LoginAckBody {
        guard body.count >= 1 else { throw WireError.badFrame("loginAck too short") }
        let code  = WireCode(rawValue: body[0]) ?? .errInternal
        let token = code == .ok ? Data(body[1...]) : Data()
        return LoginAckBody(code: code, token: token)
    }

    func parseAck() -> AckBody {
        guard !body.isEmpty else { return AckBody(code: .errInternal, data: Data()) }
        let code = WireCode(rawValue: body[0]) ?? .errInternal
        let data = body.count > 1 ? Data(body[1...]) : Data()
        return AckBody(code: code, data: data)
    }

    func parseEvtTransferIn() throws -> EvtTransferInBody {
        guard body.count >= 20 else { throw WireError.badFrame("evtTransferIn too short") }
        return EvtTransferInBody(
            fromID:  body.readBigEndianUInt32(at: 0),
            amount:  body.readBigEndianUInt64(at: 4),
            balance: body.readBigEndianUInt64(at: 12)
        )
    }
}

// ─── Errors ────────────────────────────────────────────────────────────────

enum WireError: Error, LocalizedError {
    case badFrame(String)
    case badSig
    case serverError(WireCode)
    case disconnected
    case timeout

    var errorDescription: String? {
        switch self {
        case .badFrame(let msg): return "Bad frame: \(msg)"
        case .badSig:            return "Signature mismatch"
        case .serverError(let c): return "Server error: \(c)"
        case .disconnected:      return "Connection lost"
        case .timeout:           return "Request timed out"
        }
    }
}

// ─── Data helpers ──────────────────────────────────────────────────────────

extension Data {
    mutating func appendBigEndian(_ v: UInt32) {
        append(UInt8((v >> 24) & 0xFF))
        append(UInt8((v >> 16) & 0xFF))
        append(UInt8((v >>  8) & 0xFF))
        append(UInt8( v        & 0xFF))
    }

    mutating func appendBigEndian(_ v: UInt64) {
        append(UInt8((v >> 56) & 0xFF))
        append(UInt8((v >> 48) & 0xFF))
        append(UInt8((v >> 40) & 0xFF))
        append(UInt8((v >> 32) & 0xFF))
        append(UInt8((v >> 24) & 0xFF))
        append(UInt8((v >> 16) & 0xFF))
        append(UInt8((v >>  8) & 0xFF))
        append(UInt8( v        & 0xFF))
    }

    func readBigEndianUInt32(at offset: Int) -> UInt32 {
        let s = self[index(startIndex, offsetBy: offset)...]
        return UInt32(s[s.startIndex]) << 24
             | UInt32(s[s.index(s.startIndex, offsetBy: 1)]) << 16
             | UInt32(s[s.index(s.startIndex, offsetBy: 2)]) << 8
             | UInt32(s[s.index(s.startIndex, offsetBy: 3)])
    }

    func readBigEndianUInt64(at offset: Int) -> UInt64 {
        let s = self[index(startIndex, offsetBy: offset)...]
        var v: UInt64 = 0
        for i in 0..<8 {
            v = (v << 8) | UInt64(s[s.index(s.startIndex, offsetBy: i)])
        }
        return v
    }
}
