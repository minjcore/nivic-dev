import Foundation
import CryptoKit
import UserNotifications

// ─── Public models ─────────────────────────────────────────────────────────

public struct SavingAccount {
    public let id: UInt32
}

public struct BalanceInfo {
    public let balance:          UInt64
    public let pending:          UInt64
    public let availableBalance: UInt64
    public let version:          UInt64
}

public struct SavingTransfer: Equatable {
    public let fromID:  UInt32
    public let amount:  UInt64
    public let balance: UInt64
}

public enum SavingEvent {
    case transferIn(SavingTransfer)
    case recoveryRequested(accountID: UInt32)
    case recoveryGranted(accountID: UInt32)
    case guardianAdded(accountID: UInt32)
}

public struct Transaction: Identifiable {
    public let id = UUID()
    public let direction: Direction
    public let counterpartID: UInt32
    public let amount: UInt64

    public enum Direction {
        case sent, received          // transfer
        case paymentSent             // customer paid merchant
        case paymentReceived         // merchant received payment
        case cashIn                  // deposit
        case cashOut                 // withdrawal
    }
}

// ─── Main client ───────────────────────────────────────────────────────────

@MainActor
public final class SavingClient: ObservableObject {

    private static let wireSecret = "saving_wire_secret_changeme"

    private let conn: WireConnection
    private var seq: UInt32 = 0
    private var sessionToken: Data?
    public private(set) var uid: UInt32?

    public var onEvent: ((SavingEvent) -> Void)?

    @Published public var isConnected = false
    @Published public var lastTransferIn: SavingTransfer?

    public init(host: String = "127.0.0.1", port: UInt16 = 7474) {
        conn = WireConnection(host: host, port: port, secret: Self.wireSecret)
    }

    // ─── Connection ──────────────────────────────────────────────────────────

    public func connect() async throws {
        try await conn.connect()
        isConnected = true
        await conn.setEventHandler { [weak self] frame in
            Task { @MainActor in self?.handlePush(frame) }
        }
    }

    public func disconnect() async {
        await conn.disconnect()
        sessionToken = nil
        isConnected  = false
    }

    // ─── Account ─────────────────────────────────────────────────────────────

    public func createAccount(id: UInt32, password: String) async throws {
        let frame = WireFrame.createAccount(id: id, passwordHash: sha256(password), seq: nextSeq())
        let ack   = try await conn.send(frame).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    public func login(id: UInt32, password: String) async throws {
        let frame = WireFrame.login(id: id, passwordHash: sha256(password), seq: nextSeq())
        let ack   = try await conn.send(frame).parseLoginAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
        sessionToken = ack.token
        uid = id
    }

    public func logout() async throws {
        let token = try requireToken()
        _ = try await conn.send(WireFrame.logout(token: token, seq: nextSeq()))
        sessionToken = nil
    }

    // ─── Balance ─────────────────────────────────────────────────────────────

    public func balance() async throws -> BalanceInfo {
        let token = try requireToken()
        let ack   = try await conn.send(WireFrame.getBalance(token: token, seq: nextSeq())).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
        let d = ack.data
        if d.count >= 32 {
            return BalanceInfo(
                balance:          d.readBigEndianUInt64(at: 0),
                pending:          d.readBigEndianUInt64(at: 8),
                availableBalance: d.readBigEndianUInt64(at: 16),
                version:          d.readBigEndianUInt64(at: 24)
            )
        }
        // Legacy server: single 8-byte balance
        let bal = d.count >= 8 ? d.readBigEndianUInt64(at: 0) : 0
        return BalanceInfo(balance: bal, pending: 0, availableBalance: bal, version: 0)
    }

    // ─── Transfer ────────────────────────────────────────────────────────────

    public func transfer(to: UInt32, amount: UInt64) async throws {
        let token = try requireToken()
        let frame = WireFrame.transfer(token: token, toID: to, amount: amount, seq: nextSeq())
        let ack   = try await conn.send(frame).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    // ─── Merchant pay ─────────────────────────────────────────────────────────
    // Mechanically identical to transfer; mid is in the VIP account range.

    public func payMerchant(mid: UInt32, amount: UInt64) async throws {
        let token = try requireToken()
        let frame = WireFrame.transfer(token: token, toID: mid, amount: amount, seq: nextSeq())
        let ack   = try await conn.send(frame).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    // ─── History ─────────────────────────────────────────────────────────────

    public func history() async throws -> [Transaction] {
        let token = try requireToken()
        let ack   = try await conn.send(WireFrame.getHistory(token: token, seq: nextSeq())).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }

        /* extra layout: [count 1B][direction 1B | counterpart 4B | amount 8B] × count */
        let data  = ack.data
        guard !data.isEmpty else { return [] }
        let count = Int(data[0])
        var txs: [Transaction] = []
        txs.reserveCapacity(count)
        for i in 0..<count {
            let base = 1 + i * 13
            guard base + 13 <= data.count else { break }
            let kind = data[base]
            let dir: Transaction.Direction = switch kind {
                case 0: .sent
                case 1: .received
                case 2: .paymentSent
                case 3: .paymentReceived
                case 4: .cashIn
                case 5: .cashOut
                default: .sent
            }
            let counterpart = data.readBigEndianUInt32(at: base + 1)
            let amount      = data.readBigEndianUInt64(at: base + 5)
            txs.append(Transaction(direction: dir, counterpartID: counterpart, amount: amount))
        }
        return txs
    }

    // ─── Payment Intents ─────────────────────────────────────────────────────

    public struct IntentResult {
        public let mid: UInt32
        public let requestID: UInt64
        public let amount: UInt64
    }

    /// Merchant creates a payment intent. requestID and orderID must be unique per merchant.
    public func createIntent(requestID: UInt64, orderID: UInt64, amount: UInt64,
                             gatewayOrderID: String = "") async throws -> IntentResult {
        let token = try requireToken()
        let frame = WireFrame.createIntent(token: token, requestID: requestID,
                                           orderID: orderID, amount: amount,
                                           gatewayOrderID: gatewayOrderID, seq: nextSeq())
        let ack = try await conn.send(frame).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
        /* extra: [status 1B][mid 4B][request_id 8B][amount 8B] = 21 bytes */
        guard ack.data.count >= 21 else { throw WireError.badFrame("short intent reply") }
        let mid       = ack.data.readBigEndianUInt32(at: 1)
        let rid       = ack.data.readBigEndianUInt64(at: 5)
        let amt       = ack.data.readBigEndianUInt64(at: 13)
        return IntentResult(mid: mid, requestID: rid, amount: amt)
    }

    /// Customer pays a payment intent with their TOTP code.
    public func payIntent(merchantID: UInt32, requestID: UInt64, totpCode: UInt32) async throws {
        let token = try requireToken()
        let frame = WireFrame.payIntent(token: token, merchantID: merchantID,
                                        requestID: requestID, totpCode: totpCode, seq: nextSeq())
        let ack = try await conn.send(frame).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    /// Merchant enrolls a TOTP secret for a customer.
    public func enrollTotp(customerID: UInt32, secret: Data) async throws {
        let token = try requireToken()
        let frame = WireFrame.enrollTotp(token: token, customerID: customerID,
                                          secret: secret, seq: nextSeq())
        let ack = try await conn.send(frame).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    /// Register or update merchant name in the Wire server.
    public func registerMerchant(name: String) async throws {
        let token = try requireToken()
        let frame = WireFrame.registerMerchant(token: token, name: name, seq: nextSeq())
        let ack = try await conn.send(frame).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    // ─── Guardians ───────────────────────────────────────────────────────────

    public func addGuardian(id: UInt32) async throws {
        let token = try requireToken()
        let ack   = try await conn.send(WireFrame.addGuardian(token: token, guardianID: id, seq: nextSeq())).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    // ─── Social Recovery ─────────────────────────────────────────────────────

    public func requestRecovery(id: UInt32) async throws {
        let ack = try await conn.send(WireFrame.recoveryReq(id: id, seq: nextSeq())).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    public func approveRecovery(targetID: UInt32) async throws {
        let token = try requireToken()
        let ack   = try await conn.send(WireFrame.recoveryApprove(token: token, targetID: targetID, seq: nextSeq())).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    // ─── Push events ─────────────────────────────────────────────────────────

    private func handlePush(_ frame: WireFrame) {
        switch frame.type {
        case .evtTransferIn:
            guard let body = try? frame.parseEvtTransferIn() else { return }
            let transfer = SavingTransfer(fromID: body.fromID, amount: body.amount, balance: body.balance)
            lastTransferIn = transfer
            onEvent?(.transferIn(transfer))
            let fmt = NumberFormatter()
            fmt.numberStyle = .decimal; fmt.groupingSeparator = "."
            let amtStr = (fmt.string(from: NSNumber(value: body.amount)) ?? "\(body.amount)") + " ₫"
            pushNotification(title: "Nhận tiền 🎉", body: "+\(amtStr) từ #\(body.fromID)")
        case .evtRecoveryReq:
            guard frame.body.count >= 4 else { return }
            onEvent?(.recoveryRequested(accountID: frame.body.readBigEndianUInt32(at: 0)))
        case .evtRecoveryOK:
            guard frame.body.count >= 4 else { return }
            onEvent?(.recoveryGranted(accountID: frame.body.readBigEndianUInt32(at: 0)))
        case .evtGuardianAdd:
            guard frame.body.count >= 4 else { return }
            onEvent?(.guardianAdded(accountID: frame.body.readBigEndianUInt32(at: 0)))
        default:
            break
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private func pushNotification(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body  = body
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        )
    }

    private func nextSeq() -> UInt32 { seq &+= 1; return seq }

    private func requireToken() throws -> Data {
        guard let t = sessionToken else { throw WireError.serverError(.errBadToken) }
        return t
    }

    private func sha256(_ input: String) -> Data {
        Data(SHA256.hash(data: Data(input.utf8)))
    }
}

// ─── WireConnection actor extension ───────────────────────────────────────

extension WireConnection {
    func setEventHandler(_ handler: @escaping (WireFrame) -> Void) {
        onEvent = handler
    }
}
