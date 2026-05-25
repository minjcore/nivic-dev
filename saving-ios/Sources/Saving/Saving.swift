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

public struct BalanceUpdate {
    public let amount:  UInt64
    public let balance: UInt64
}

public enum SavingEvent {
    case transferIn(SavingTransfer)
    case recoveryRequested(accountID: UInt32)
    case recoveryGranted(accountID: UInt32)
    case guardianAdded(accountID: UInt32)
    case cashIn(BalanceUpdate)
    case cashOut(BalanceUpdate)
    case totpCharged(merchantID: UInt32, update: BalanceUpdate)
}

public struct Transaction: Identifiable {
    public let id = UUID()
    public let direction: Direction
    public let counterpartID: UInt32
    public let amount: UInt64
    public let afterBalance: UInt64

    public enum Direction {
        case sent, received          // transfer
        case paymentSent             // customer paid merchant
        case paymentReceived         // merchant received payment
        case cashIn                  // deposit
        case cashOut                 // withdrawal
    }
}

// ─── Background network actor ───────────────────────────────────────────────
// All I/O, sequence numbers, and session token live here — never on the main thread.

private actor SavingNetwork {

    let conn: WireConnection
    var seq: UInt32 = 0
    var sessionToken: Data?

    init(host: String, port: UInt16, secret: String) {
        conn = WireConnection(host: host, port: port, secret: secret)
    }

    // ─── Connection ─────────────────────────────────────────────────────────

    func connect() async throws {
        try await conn.connect()
    }

    func disconnect() async {
        await conn.disconnect()
        sessionToken = nil
    }

    func setEventHandler(_ handler: @escaping (WireFrame) -> Void) async {
        await conn.setEventHandler(handler)
    }

    // ─── Account ────────────────────────────────────────────────────────────

    func createAccount(id: UInt32, password: String) async throws {
        let frame = WireFrame.createAccount(id: id, passwordHash: sha256(password), seq: nextSeq())
        let ack   = try await conn.send(frame).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    func login(id: UInt32, password: String) async throws {
        let frame = WireFrame.login(id: id, passwordHash: sha256(password), seq: nextSeq())
        let ack   = try await conn.send(frame).parseLoginAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
        sessionToken = ack.token
    }

    func logout() async throws {
        let token = try requireToken()
        _ = try await conn.send(WireFrame.logout(token: token, seq: nextSeq()))
        sessionToken = nil
    }

    // ─── Balance ────────────────────────────────────────────────────────────

    func balance() async throws -> BalanceInfo {
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
        let bal = d.count >= 8 ? d.readBigEndianUInt64(at: 0) : 0
        return BalanceInfo(balance: bal, pending: 0, availableBalance: bal, version: 0)
    }

    // ─── Transfer ───────────────────────────────────────────────────────────

    @discardableResult
    func transfer(to: UInt32, amount: UInt64, ref: UInt64) async throws -> TransferAckBody {
        let token = try requireToken()
        let frame = try await conn.send(WireFrame.transfer(token: token, toID: to, amount: amount, ref: ref, seq: nextSeq()))
        let ack   = try frame.parseTransferAck()
        return ack
    }

    @discardableResult
    func payMerchant(mid: UInt32, amount: UInt64, ref: UInt64) async throws -> TransferAckBody {
        let token = try requireToken()
        let frame = try await conn.send(WireFrame.transfer(token: token, toID: mid, amount: amount, ref: ref, seq: nextSeq()))
        let ack   = try frame.parseTransferAck()
        return ack
    }

    // ─── History ────────────────────────────────────────────────────────────

    func history() async throws -> [Transaction] {
        let token = try requireToken()
        let ack   = try await conn.send(WireFrame.getHistory(token: token, seq: nextSeq())).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }

        let data  = ack.data
        guard !data.isEmpty else { return [] }
        let count = Int(data[0])
        var txs: [Transaction] = []
        txs.reserveCapacity(count)
        for i in 0..<count {
            let base = 1 + i * 21
            guard base + 21 <= data.count else { break }
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
            let counterpart  = data.readBigEndianUInt32(at: base + 1)
            let amount       = data.readBigEndianUInt64(at: base + 5)
            let afterBalance = data.readBigEndianUInt64(at: base + 13)
            txs.append(Transaction(direction: dir, counterpartID: counterpart,
                                   amount: amount, afterBalance: afterBalance))
        }
        return txs
    }

    // ─── Payment Intents ────────────────────────────────────────────────────

    func createIntent(requestID: UInt64, orderID: UInt64, amount: UInt64,
                      gatewayOrderID: String) async throws -> SavingClient.IntentResult {
        let token = try requireToken()
        let frame = WireFrame.createIntent(token: token, requestID: requestID,
                                           orderID: orderID, amount: amount,
                                           gatewayOrderID: gatewayOrderID, seq: nextSeq())
        let ack = try await conn.send(frame).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
        guard ack.data.count >= 21 else { throw WireError.badFrame("short intent reply") }
        let mid = ack.data.readBigEndianUInt32(at: 1)
        let rid = ack.data.readBigEndianUInt64(at: 5)
        let amt = ack.data.readBigEndianUInt64(at: 13)
        return SavingClient.IntentResult(mid: mid, requestID: rid, amount: amt)
    }

    func payIntent(merchantID: UInt32, requestID: UInt64, totpCode: UInt32) async throws {
        let token = try requireToken()
        let ack = try await conn.send(WireFrame.payIntent(token: token, merchantID: merchantID,
                                                          requestID: requestID, totpCode: totpCode,
                                                          seq: nextSeq())).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    func enrollTotp(customerID: UInt32, secret: Data) async throws {
        let token = try requireToken()
        let ack = try await conn.send(WireFrame.enrollTotp(token: token, customerID: customerID,
                                                            secret: secret, seq: nextSeq())).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    func registerMerchant(name: String) async throws {
        let token = try requireToken()
        let ack = try await conn.send(WireFrame.registerMerchant(token: token, name: name,
                                                                  seq: nextSeq())).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    // ─── Guardians ──────────────────────────────────────────────────────────

    func addGuardian(id: UInt32) async throws {
        let token = try requireToken()
        let ack   = try await conn.send(WireFrame.addGuardian(token: token, guardianID: id, seq: nextSeq())).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    // ─── Social Recovery ────────────────────────────────────────────────────

    func requestRecovery(id: UInt32) async throws {
        let ack = try await conn.send(WireFrame.recoveryReq(id: id, seq: nextSeq())).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    func approveRecovery(targetID: UInt32) async throws {
        let token = try requireToken()
        let ack   = try await conn.send(WireFrame.recoveryApprove(token: token, targetID: targetID, seq: nextSeq())).parseAck()
        guard ack.code == .ok else { throw WireError.serverError(ack.code) }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private func nextSeq() -> UInt32 { seq &+= 1; return seq }

    private func requireToken() throws -> Data {
        guard let t = sessionToken else { throw WireError.serverError(.errBadToken) }
        return t
    }

    private func sha256(_ input: String) -> Data {
        Data(SHA256.hash(data: Data(input.utf8)))
    }
}

// ─── Main client (UI layer) ─────────────────────────────────────────────────
// @MainActor shell: owns SavingNetwork, delegates all I/O to it, updates
// @Published properties only after network calls return to the main thread.

@MainActor
public final class SavingClient: ObservableObject {

    private let net: SavingNetwork
    public private(set) var uid: UInt32?

    public var onEvent: ((SavingEvent) -> Void)?

    @Published public var isConnected    = false
    @Published public var lastTransferIn: SavingTransfer?

    public init(host: String = "127.0.0.1", port: UInt16 = 7474, secret: String) {
        net = SavingNetwork(host: host, port: port, secret: secret)
    }

    // ─── Connection ──────────────────────────────────────────────────────────

    public func connect() async throws {
        try await net.connect()
        isConnected = true
        await net.setEventHandler { [weak self] frame in
            Task { @MainActor in self?.handlePush(frame) }
        }
    }

    public func disconnect() async {
        await net.disconnect()
        isConnected = false
    }

    // ─── Account ─────────────────────────────────────────────────────────────

    public func createAccount(id: UInt32, password: String) async throws {
        try await net.createAccount(id: id, password: password)
    }

    public func login(id: UInt32, password: String) async throws {
        try await net.login(id: id, password: password)
        uid = id
    }

    public func logout() async throws {
        try await net.logout()
        uid = nil
    }

    // ─── Balance ─────────────────────────────────────────────────────────────

    public func balance() async throws -> BalanceInfo {
        try await net.balance()
    }

    // ─── Transfer ────────────────────────────────────────────────────────────

    @discardableResult
    public func transfer(to: UInt32, amount: UInt64, ref: UInt64) async throws -> TransferAckBody {
        try await net.transfer(to: to, amount: amount, ref: ref)
    }

    @discardableResult
    public func payMerchant(mid: UInt32, amount: UInt64, ref: UInt64) async throws -> TransferAckBody {
        try await net.payMerchant(mid: mid, amount: amount, ref: ref)
    }

    // ─── History ─────────────────────────────────────────────────────────────

    public func history() async throws -> [Transaction] {
        try await net.history()
    }

    // ─── Payment Intents ─────────────────────────────────────────────────────

    public struct IntentResult {
        public let mid: UInt32
        public let requestID: UInt64
        public let amount: UInt64
    }

    public func createIntent(requestID: UInt64, orderID: UInt64, amount: UInt64,
                             gatewayOrderID: String = "") async throws -> IntentResult {
        try await net.createIntent(requestID: requestID, orderID: orderID,
                                   amount: amount, gatewayOrderID: gatewayOrderID)
    }

    public func payIntent(merchantID: UInt32, requestID: UInt64, totpCode: UInt32) async throws {
        try await net.payIntent(merchantID: merchantID, requestID: requestID, totpCode: totpCode)
    }

    public func enrollTotp(customerID: UInt32, secret: Data) async throws {
        try await net.enrollTotp(customerID: customerID, secret: secret)
    }

    public func registerMerchant(name: String) async throws {
        try await net.registerMerchant(name: name)
    }

    // ─── Guardians ───────────────────────────────────────────────────────────

    public func addGuardian(id: UInt32) async throws {
        try await net.addGuardian(id: id)
    }

    // ─── Social Recovery ─────────────────────────────────────────────────────

    public func requestRecovery(id: UInt32) async throws {
        try await net.requestRecovery(id: id)
    }

    public func approveRecovery(targetID: UInt32) async throws {
        try await net.approveRecovery(targetID: targetID)
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
        case .evtCashIn:
            guard let body = try? frame.parseEvtCashIn() else { return }
            let update = BalanceUpdate(amount: body.amount, balance: body.balance)
            onEvent?(.cashIn(update))
            let fmt = NumberFormatter()
            fmt.numberStyle = .decimal; fmt.groupingSeparator = "."
            let amtStr = (fmt.string(from: NSNumber(value: body.amount)) ?? "\(body.amount)") + " ₫"
            pushNotification(title: "Nạp tiền thành công", body: "+\(amtStr) vào tài khoản")
        case .evtCashOut:
            guard frame.body.count >= 20 else { return }
            let amount  = frame.body.readBigEndianUInt64(at: 4)
            let balance = frame.body.readBigEndianUInt64(at: 12)
            onEvent?(.cashOut(BalanceUpdate(amount: amount, balance: balance)))
        case .evtTotpCharged:
            guard let body = try? frame.parseEvtTotpCharged() else { return }
            let update = BalanceUpdate(amount: body.amount, balance: body.balance)
            onEvent?(.totpCharged(merchantID: body.merchantID, update: update))
            let fmt = NumberFormatter()
            fmt.numberStyle = .decimal; fmt.groupingSeparator = "."
            let amtStr = (fmt.string(from: NSNumber(value: body.amount)) ?? "\(body.amount)") + " ₫"
            pushNotification(title: "Thanh toán QR", body: "-\(amtStr) tại #\(body.merchantID)")
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
}

// ─── WireConnection actor extension ───────────────────────────────────────

extension WireConnection {
    func setEventHandler(_ handler: @escaping (WireFrame) -> Void) {
        onEvent = handler
    }
}
