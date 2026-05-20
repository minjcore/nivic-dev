import Foundation
import Network
import CryptoKit

// ─── Low-level TCP connection that speaks the Wire frame protocol ──────────

actor WireConnection {

    private let host:    NWEndpoint.Host
    private let port:    NWEndpoint.Port
    private let secret:  SymmetricKey
    private var conn:    NWConnection?
    private var recvBuf: Data = Data()

    // Pending request continuations: seq → continuation
    private var pending: [UInt32: CheckedContinuation<WireFrame, Error>] = [:]

    // Push event handler — set by the caller
    var onEvent: ((WireFrame) -> Void)?

    init(host: String, port: UInt16, secret: String) {
        self.host   = NWEndpoint.Host(host)
        self.port   = NWEndpoint.Port(rawValue: port)!
        self.secret = SymmetricKey(data: Data(secret.utf8))
    }

    // ─── Connect ────────────────────────────────────────────────────────────

    func connect() async throws {
        let conn = NWConnection(host: host, port: port, using: .tcp)
        self.conn = conn

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            conn.stateUpdateHandler = { [weak conn] state in
                switch state {
                case .ready:
                    conn?.stateUpdateHandler = nil
                    cont.resume()
                case .failed(let err):
                    conn?.stateUpdateHandler = nil
                    cont.resume(throwing: err)
                case .cancelled:
                    conn?.stateUpdateHandler = nil
                    cont.resume(throwing: WireError.disconnected)
                default:
                    break
                }
            }
            conn.start(queue: .global(qos: .userInitiated))
        }

        startReceiving()
    }

    func disconnect() {
        conn?.cancel()
        conn = nil
        for (_, cont) in pending {
            cont.resume(throwing: WireError.disconnected)
        }
        pending.removeAll()
    }

    // ─── Send a frame and wait for its response ──────────────────────────────

    func send(_ frame: WireFrame, timeout: TimeInterval = 10) async throws -> WireFrame {
        guard let conn else { throw WireError.disconnected }

        let raw = frame.encode(secret: secret)
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            conn.send(content: raw, completion: .contentProcessed { err in
                if let err { cont.resume(throwing: err) }
                else        { cont.resume() }
            })
        }

        return try await withCheckedThrowingContinuation { cont in
            pending[frame.seq] = cont
            let seq = frame.seq
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                await self?.expire(seq: seq)
            }
        }
    }

    private func expire(seq: UInt32) {
        guard let cont = pending.removeValue(forKey: seq) else { return }
        cont.resume(throwing: WireError.timeout)
    }

    // ─── Receive loop ────────────────────────────────────────────────────────

    private func startReceiving() {
        guard let conn else { return }
        conn.receive(minimumIncompleteLength: 4, maximumLength: WireFrame.maxSize) {
            [weak self] data, _, isComplete, err in
            guard let self else { return }
            Task {
                await self.handleReceive(data: data, isComplete: isComplete, error: err)
            }
        }
    }

    private func handleReceive(data: Data?, isComplete: Bool, error: NWError?) {
        if let data { recvBuf.append(data) }

        // Drain complete frames from the buffer
        while recvBuf.count >= 4 {
            let frameLen = Int(recvBuf.readBigEndianUInt32(at: 0))
            guard recvBuf.count >= frameLen else { break }

            let frameData = Data(recvBuf.prefix(frameLen))
            recvBuf.removeFirst(frameLen)

            do {
                let frame = try WireFrame.decode(frameData, secret: secret)
                dispatch(frame)
            } catch {
                // Corrupted frame — log and drop
                print("[saving] frame decode error: \(error)")
            }
        }

        if isComplete || error != nil {
            disconnect()
            return
        }
        startReceiving()
    }

    private func dispatch(_ frame: WireFrame) {
        // Push events (seq == 0, type >= 0xC0)
        if frame.type.rawValue >= 0xC0 {
            onEvent?(frame)
            return
        }
        // Match response to pending request by seq
        if let cont = pending.removeValue(forKey: frame.seq) {
            cont.resume(returning: frame)
        }
    }
}
