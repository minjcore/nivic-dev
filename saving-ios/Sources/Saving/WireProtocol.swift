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
    case createIntent      = 0x20
    case payIntent         = 0x21
    case enrollTotp        = 0x22
    case registerMerchant  = 0x23
    case cashIn            = 0x24
    case totpCharge        = 0x25
    case cashOut           = 0x26
    case getMerchantInfo   = 0x27
    case listIntents       = 0x28
    case confirmIntent     = 0x29

    // Server → Client (responses)
    case pong            = 0x80
    case loginAck        = 0x81
    case ack             = 0x82

    // Server → Client (push events)
    case evtTransferIn   = 0xC0
    case evtRecoveryReq  = 0xC1
    case evtRecoveryOK   = 0xC2
    case evtGuardianAdd  = 0xC3
    case evtIntentPaid   = 0xC4
    case evtCashOut      = 0xC5
    case evtTotpCharged  = 0xC6
    case evtCashIn       = 0xC7
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
    case errNotMerchant    = 0x0E
    case errSystemOffline  = 0x0F
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

    static func transfer(token: Data, toID: UInt32, amount: UInt64,
                         ref: UInt64, seq: UInt32) -> WireFrame {
        var body = Data()
        body.append(token)
        body.appendBigEndian(toID)
        body.appendBigEndian(amount)
        body.appendBigEndian(ref)
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

    /* CREATE_INTENT  body: [token 32B][request_id 8B][order_id 8B][amount 8B][gateway_order_id N bytes] */
    static func createIntent(token: Data, requestID: UInt64, orderID: UInt64,
                              amount: UInt64, gatewayOrderID: String = "",
                              seq: UInt32) -> WireFrame {
        var body = Data()
        body.append(token)
        body.appendBigEndian(requestID)
        body.appendBigEndian(orderID)
        body.appendBigEndian(amount)
        if !gatewayOrderID.isEmpty { body.append(Data(gatewayOrderID.utf8)) }
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

    /* REGISTER_MERCHANT  body: [token 32B][name N bytes] */
    static func registerMerchant(token: Data, name: String, seq: UInt32) -> WireFrame {
        var body = token
        body.append(Data(name.utf8))
        return WireFrame(type: .registerMerchant, seq: seq, body: body)
    }

    /* CASH_IN  body: [bank_token 32B][to_uid 4B][amount 8B][topup_id N bytes] */
    static func cashIn(token: Data, toUID: UInt32, amount: UInt64,
                       topupID: String, seq: UInt32) -> WireFrame {
        var body = Data()
        body.append(token)
        body.appendBigEndian(toUID)
        body.appendBigEndian(amount)
        body.append(Data(topupID.utf8))
        return WireFrame(type: .cashIn, seq: seq, body: body)
    }

    /* TOTP_CHARGE  body: [merchant_token 32B][customer_uid 4B][totp_code 4B][amount 8B] */
    static func totpCharge(token: Data, customerUID: UInt32, totpCode: UInt32,
                           amount: UInt64, seq: UInt32) -> WireFrame {
        var body = Data()
        body.append(token)
        body.appendBigEndian(customerUID)
        body.appendBigEndian(totpCode)
        body.appendBigEndian(amount)
        return WireFrame(type: .totpCharge, seq: seq, body: body)
    }

    /* CASH_OUT  body: [bank_token 32B][from_uid 4B][amount 8B][cashout_id N bytes] */
    static func cashOut(token: Data, fromUID: UInt32, amount: UInt64,
                        cashoutID: String, seq: UInt32) -> WireFrame {
        var body = Data()
        body.append(token)
        body.appendBigEndian(fromUID)
        body.appendBigEndian(amount)
        body.append(Data(cashoutID.utf8))
        return WireFrame(type: .cashOut, seq: seq, body: body)
    }

    /* GET_MERCHANT_INFO  body: [token 32B][merchant_id 4B] */
    static func getMerchantInfo(token: Data, merchantID: UInt32, seq: UInt32) -> WireFrame {
        var body = Data()
        body.append(token)
        body.appendBigEndian(merchantID)
        return WireFrame(type: .getMerchantInfo, seq: seq, body: body)
    }

    /* LIST_INTENTS  body: [merchant_token 32B] */
    static func listIntents(token: Data, seq: UInt32) -> WireFrame {
        WireFrame(type: .listIntents, seq: seq, body: token)
    }

    /* CONFIRM_INTENT  body: [customer_token 32B][merchant_id 4B][request_id 8B] */
    static func confirmIntent(token: Data, merchantID: UInt32, requestID: UInt64,
                               seq: UInt32) -> WireFrame {
        var body = Data()
        body.append(token)
        body.appendBigEndian(merchantID)
        body.appendBigEndian(requestID)
        return WireFrame(type: .confirmIntent, seq: seq, body: body)
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

// TRANSFER ACK extra: [txn_id 8B][after_balance 8B]
public struct TransferAckBody {
    public let txnID:        UInt64
    public let afterBalance: Int64
}

// GET_BALANCE ACK extra: [balance 8B][pending 8B][available 8B][version 8B]
struct BalanceDetail {
    let balance:   Int64
    let pending:   Int64
    let available: Int64
    let version:   Int64
}

// GET_HISTORY ACK entry: [dir 1B][counterpart 4B][amount 8B][after_balance 8B]
struct TxEntry {
    enum Direction: UInt8 {
        case c2cSent  = 0
        case c2cRecv  = 1
        case c2mSent  = 2
        case c2mRecv  = 3
        case m2cRecv  = 4
        case c2bSent  = 5
    }
    let direction:    Direction
    let counterpart:  UInt32
    let amount:       UInt64
    let afterBalance: Int64
}

// LIST_INTENTS ACK entry: [request_id 8B][amount 8B]
struct IntentSummary {
    let requestID: UInt64
    let amount:    UInt64
}

// EVT_INTENT_PAID  body: [request_id 8B][customer_id 4B][amount 8B]
struct EvtIntentPaidBody {
    let requestID:  UInt64
    let customerID: UInt32
    let amount:     UInt64
}

// EVT_CASH_OUT  body: [bank_mid 4B][amount 8B][balance 8B]
struct EvtCashOutBody {
    let bankMID: UInt32
    let amount:  UInt64
    let balance: UInt64
}

// EVT_TOTP_CHARGED  body: [merchant_id 4B][amount 8B][balance 8B]
struct EvtTotpChargedBody {
    let merchantID: UInt32
    let amount:     UInt64
    let balance:    UInt64
}

// EVT_CASH_IN  body: [amount 8B][balance 8B]
struct EvtCashInBody {
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

    // TRANSFER ACK extra: [txn_id 8B][after_balance 8B]
    func parseTransferAck() throws -> TransferAckBody {
        guard body.count >= 17 else { throw WireError.badFrame("transferAck too short") }
        return TransferAckBody(
            txnID:        body.readBigEndianUInt64(at: 1),
            afterBalance: Int64(bitPattern: body.readBigEndianUInt64(at: 9))
        )
    }

    // GET_BALANCE ACK extra: [balance 8B][pending 8B][available 8B][version 8B]
    func parseBalanceDetail() throws -> BalanceDetail {
        guard body.count >= 33 else { throw WireError.badFrame("balanceDetail too short") }
        return BalanceDetail(
            balance:   Int64(bitPattern: body.readBigEndianUInt64(at: 1)),
            pending:   Int64(bitPattern: body.readBigEndianUInt64(at: 9)),
            available: Int64(bitPattern: body.readBigEndianUInt64(at: 17)),
            version:   Int64(bitPattern: body.readBigEndianUInt64(at: 25))
        )
    }

    // GET_HISTORY ACK extra: [count 1B][dir 1B | counterpart 4B | amount 8B | after_bal 8B]xN
    func parseHistory() throws -> [TxEntry] {
        guard body.count >= 2 else { throw WireError.badFrame("history too short") }
        let count = Int(body[1])
        let entrySize = 1 + 4 + 8 + 8  // 21 bytes per entry
        guard body.count >= 2 + count * entrySize else {
            throw WireError.badFrame("history body truncated")
        }
        return (0..<count).map { i in
            let off = 2 + i * entrySize
            return TxEntry(
                direction:    TxEntry.Direction(rawValue: body[off]) ?? .c2cSent,
                counterpart:  body.readBigEndianUInt32(at: off + 1),
                amount:       body.readBigEndianUInt64(at: off + 5),
                afterBalance: Int64(bitPattern: body.readBigEndianUInt64(at: off + 13))
            )
        }
    }

    // LIST_INTENTS ACK extra: [count 1B][request_id 8B | amount 8B]xN
    func parseIntentList() throws -> [IntentSummary] {
        guard body.count >= 2 else { throw WireError.badFrame("intentList too short") }
        let count = Int(body[1])
        let entrySize = 8 + 8  // 16 bytes per entry
        guard body.count >= 2 + count * entrySize else {
            throw WireError.badFrame("intentList body truncated")
        }
        return (0..<count).map { i in
            let off = 2 + i * entrySize
            return IntentSummary(
                requestID: body.readBigEndianUInt64(at: off),
                amount:    body.readBigEndianUInt64(at: off + 8)
            )
        }
    }

    func parseEvtIntentPaid() throws -> EvtIntentPaidBody {
        guard body.count >= 20 else { throw WireError.badFrame("evtIntentPaid too short") }
        return EvtIntentPaidBody(
            requestID:  body.readBigEndianUInt64(at: 0),
            customerID: body.readBigEndianUInt32(at: 8),
            amount:     body.readBigEndianUInt64(at: 12)
        )
    }

    func parseEvtCashOut() throws -> EvtCashOutBody {
        guard body.count >= 20 else { throw WireError.badFrame("evtCashOut too short") }
        return EvtCashOutBody(
            bankMID: body.readBigEndianUInt32(at: 0),
            amount:  body.readBigEndianUInt64(at: 4),
            balance: body.readBigEndianUInt64(at: 12)
        )
    }

    func parseEvtTotpCharged() throws -> EvtTotpChargedBody {
        guard body.count >= 20 else { throw WireError.badFrame("evtTotpCharged too short") }
        return EvtTotpChargedBody(
            merchantID: body.readBigEndianUInt32(at: 0),
            amount:     body.readBigEndianUInt64(at: 4),
            balance:    body.readBigEndianUInt64(at: 12)
        )
    }

    func parseEvtCashIn() throws -> EvtCashInBody {
        guard body.count >= 16 else { throw WireError.badFrame("evtCashIn too short") }
        return EvtCashInBody(
            amount:  body.readBigEndianUInt64(at: 0),
            balance: body.readBigEndianUInt64(at: 8)
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
